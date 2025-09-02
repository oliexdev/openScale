/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.modern

import android.bluetooth.le.ScanResult
import androidx.annotation.VisibleForTesting
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.experimental.or
import kotlin.math.max

/**
 * Beurer/Sanitas handler (BF700/800/RT Libra, BF710, Sanitas SBF70/SBF75/Crane).
 *
 * Protocol outline:
 *  - Single custom service/characteristic 0xFFE0/0xFFE1.
 *  - Device-specific start byte (high nibble 0xE or 0xF) + command byte + payload.
 *  - Extra "alternative start" frames for INIT/SET_TIME/DISCONNECT.
 *  - Rich user management (list, add, details) and saved/unknown measurement flows.
 *
 * This class mirrors the legacy state machine with a simple step controller.
 */
class BeurerSanitasHandler : ScaleDeviceHandler() {

    enum class DeviceType { BEURER_BF700_800_RT_LIBRA, BEURER_BF710, SANITAS_SBF70_70 }

    // UUIDs
    private val SERVICE: UUID = uuid16(0xFFE0)
    private val CHR: UUID = uuid16(0xFFE1)

    // Step machine: negative means "not waiting"; >=0 means "expect data in that step"
    private var waitForDataInStep: Int = -1
    private var step: Int = 0

    // Device flavor (set in supportFor based on name)
    private var deviceType: DeviceType? = null

    // Start byte and "alternative start byte" masks
    private var startByte: Byte = 0x00.toByte()

    // Alternative-start identifiers (low nibble toggled on the startByte)
    private val ID_START_NIBBLE_INIT = 6
    private val ID_START_NIBBLE_CMD = 7
    private val ID_START_NIBBLE_SET_TIME = 9
    private val ID_START_NIBBLE_DISCONNECT = 10

    // Commands
    private val CMD_SET_UNIT: Byte = 0x4d.toByte()
    private val CMD_SCALE_STATUS: Byte = 0x4f.toByte()

    private val CMD_USER_ADD: Byte = 0x31.toByte()
    private val CMD_USER_DELETE: Byte = 0x32.toByte()
    private val CMD_USER_LIST: Byte = 0x33.toByte()
    private val CMD_USER_INFO: Byte = 0x34.toByte()
    private val CMD_USER_UPDATE: Byte = 0x35.toByte()
    private val CMD_USER_DETAILS: Byte = 0x36.toByte()

    private val CMD_DO_MEASUREMENT: Byte = 0x40.toByte()
    private val CMD_GET_SAVED_MEASUREMENTS: Byte = 0x41.toByte()
    private val CMD_SAVED_MEASUREMENT: Byte = 0x42.toByte()
    private val CMD_DELETE_SAVED_MEASUREMENTS: Byte = 0x43.toByte()

    private val CMD_GET_UNKNOWN_MEASUREMENTS: Byte = 0x46.toByte()
    private val CMD_UNKNOWN_MEASUREMENT_INFO: Byte = 0x47.toByte()
    private val CMD_ASSIGN_UNKNOWN_MEASUREMENT: Byte = 0x4b.toByte()
    private val CMD_UNKNOWN_MEASUREMENT: Byte = 0x4c.toByte()
    private val CMD_DELETE_UNKNOWN_MEASUREMENT: Byte = 0x49.toByte()

    private val CMD_WEIGHT_MEASUREMENT: Byte = 0x58.toByte()
    private val CMD_MEASUREMENT: Byte = 0x59.toByte()

    private val CMD_SCALE_ACK: Byte = 0xf0.toByte()
    private val CMD_APP_ACK: Byte = 0xf1.toByte()

    /** Remote user descriptor projected from the scale. */
    private data class RemoteUser(
        val remoteUserId: Long,
        val name: String,
        val year: Int,
        var localUserId: Int = -1,
        var isNew: Boolean = false
    )

    /** Temporary buffer for multi-part measurement payloads and late storage. */
    private data class StoredData(
        var measurementData: ByteArray? = null,
        var storedUid: Long = -1,
        var candidateUid: Long = -1
    )

    private val remoteUsers = mutableListOf<RemoteUser>()
    private var currentRemoteUser: RemoteUser? = null
    private var measurementData: ByteArray? = null
    private val storedMeasurement = StoredData()
    private var readyForData = false
    private var dataReceived = false

    // -------------------- Capability & discovery --------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val n = device.name.lowercase(Locale.US)

        val type = when {
            // Beurer BF700/BF800/Runtastic Libra variants
            n.contains("bf700") || n.contains("bf 700") ||
                    n.contains("bf800") || n.contains("bf 800") ||
                    n.contains("runtastic libra") || n.contains("rt libra") ->
                DeviceType.BEURER_BF700_800_RT_LIBRA

            // Beurer BF710
            n.contains("bf710") || n.contains("bf 710") ->
                DeviceType.BEURER_BF710

            // Sanitas / SilverCrest / Crane
            n.contains("sbf70") || n.contains("sbf 70") ||
                    n.contains("sbf75") || n.contains("sbf 75") ||
                    n.contains("sanitas") || n.contains("crane") || n.contains("silvercrest") ->
                DeviceType.SANITAS_SBF70_70

            else -> null
        } ?: return null

        deviceType = type
        val display = when (type) {
            DeviceType.BEURER_BF700_800_RT_LIBRA -> "Beurer BF700/800 / Runtastic Libra"
            DeviceType.BEURER_BF710 -> "Beurer BF710"
            DeviceType.SANITAS_SBF70_70 -> "Sanitas SBF70 / SilverCrest SBF75 / Crane"
        }
        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.UNIT_CONFIG,
            DeviceCapability.BATTERY_LEVEL
        )
        return DeviceSupport(
            displayName = display,
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Compute device-specific start byte
        startByte = when (deviceType) {
            DeviceType.BEURER_BF700_800_RT_LIBRA -> ((0xF shl 4) or ID_START_NIBBLE_CMD).toByte()
            DeviceType.BEURER_BF710,
            DeviceType.SANITAS_SBF70_70 -> ((0xE shl 4) or ID_START_NIBBLE_CMD).toByte()
            null -> ((0xE shl 4) or ID_START_NIBBLE_CMD).toByte() // safe default
        }

        // Reset state
        remoteUsers.clear()
        currentRemoteUser = null
        measurementData = null
        storedMeasurement.measurementData = null
        storedMeasurement.storedUid = -1
        storedMeasurement.candidateUid = -1
        readyForData = false
        dataReceived = false
        waitForDataInStep = -1
        step = 0

        // Subscribe & kick off step flow
        setNotifyOn(SERVICE, CHR)
        proceedTo(1)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR || data.isEmpty()) return

        // INIT-ACK uses alternative start byte with nibble 6
        if (data[0] == getAlternativeStartByte(ID_START_NIBBLE_INIT)) {
            logD("Received INIT-ACK from scale")
            waitForDataInStep = -1
            proceedTo(2)
            return
        }

        if (data[0] != startByte) {
            logD("Unexpected start byte 0x%02X".format(data[0]))
            return
        }

        try {
            when (data[1]) {
                CMD_USER_INFO -> {
                    logD("← CMD_USER_INFO")
                    processUserInfo(data)
                }
                CMD_SAVED_MEASUREMENT -> {
                    logD("← CMD_SAVED_MEASUREMENT")
                    processSavedMeasurement(data)
                }
                CMD_WEIGHT_MEASUREMENT -> {
                    logD("← CMD_WEIGHT_MEASUREMENT")
                    processWeightMeasurement(data)
                }
                CMD_MEASUREMENT -> {
                    logD("← CMD_MEASUREMENT")
                    processMeasurement(data)
                }
                CMD_SCALE_ACK -> {
                    logD("← CMD_SCALE_ACK")
                    processScaleAck(data, user)
                }
                else -> logD("Unknown command 0x%02X".format(data[1]))
            }
        } catch (t: Throwable) {
            logE("Parse error: ${t.message}", t)
        }
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction =
        BroadcastAction.IGNORED

    // -------------------- Step controller --------------------

    private fun proceedTo(next: Int) {
        step = next
        when (step) {
            1 -> {
                // Say hello → wait for ACK with alternative start code (INIT)
                waitForDataInStep = 1
                logD("→ INIT (alt-start)")
                sendAlternativeStartCode(ID_START_NIBBLE_INIT, 0x01.toByte())
                // wait for ack
            }
            2 -> {
                // Set time (no ack required)
                val unix = (System.currentTimeMillis() / 1000L)
                logD("→ SET_TIME (alt-start)")
                sendAlternativeStartCode(ID_START_NIBBLE_SET_TIME, *ConverterUtils.toInt32Be(unix))
                proceedTo(3)
            }
            3 -> {
                // Ask scale status → expect ACK
                waitForDataInStep = 3
                logD("→ CMD_SCALE_STATUS")
                sendCommand(CMD_SCALE_STATUS, *encodeUserId(null))
            }
            4 -> {
                // Request user list → expect list and then ACKs
                waitForDataInStep = 4
                logD("→ CMD_USER_LIST")
                sendCommand(CMD_USER_LIST)
            }
            5 -> {
                // Iterate over remote users that exist locally, request saved measurements
                val nextIdx = remoteUsers.indexOf(currentRemoteUser).let { if (it < 0) 0 else it + 1 }
                currentRemoteUser = null
                for (i in nextIdx until remoteUsers.size) {
                    if (remoteUsers[i].localUserId != -1) {
                        currentRemoteUser = remoteUsers[i]
                        break
                    }
                }
                if (currentRemoteUser != null) {
                    waitForDataInStep = 5
                    logD("→ CMD_GET_SAVED_MEASUREMENTS for ${currentRemoteUser!!.name}")
                    sendCommand(CMD_GET_SAVED_MEASUREMENTS, *encodeUserId(currentRemoteUser))
                } else {
                    proceedTo(6)
                }
            }
            6 -> {
                // Ensure there is a remote user entry for the selected local user
                val selected = currentAppUser()
                val mapped = remoteUsers.firstOrNull { it.localUserId == selected.id }
                if (mapped == null) {
                    waitForDataInStep = 6
                    createRemoteUser(selected)
                } else {
                    currentRemoteUser = mapped
                    proceedTo(7)
                }
            }
            7 -> {
                // Request user details → expect ACK
                currentRemoteUser?.let {
                    waitForDataInStep = 7
                    logD("→ CMD_USER_DETAILS for ${it.name}")
                    sendCommand(CMD_USER_DETAILS, *encodeUserId(it))
                } ?: run {
                    // Nothing to query
                    proceedTo(8)
                }
            }
            8 -> {
                // Finalization / optional on-demand measurement
                when {
                    storedMeasurement.measurementData != null -> {
                        logD("Final step: deferred data present → store now.")
                        val uidOwner = currentRemoteUser?.localUserId ?: currentAppUser().id
                        addMeasurement(storedMeasurement.measurementData!!, uidOwner)
                        storedMeasurement.measurementData = null
                    }
                    !dataReceived && currentRemoteUser != null && currentRemoteUser?.isNew == false -> {
                        // Try to trigger live measurement
                        waitForDataInStep = 8
                        logD("→ CMD_DO_MEASUREMENT (prompt user to step on)")
                        userInfo(R.string.bt_info_step_on_scale, 0)
                        sendCommand(CMD_DO_MEASUREMENT, *encodeUserId(currentRemoteUser))
                    }
                    else -> {
                        logD("All finished.")
                        // Stop here; adapter may disconnect due to inactivity timeout on device
                    }
                }
            }
        }
    }

    // -------------------- Incoming processing --------------------

    private fun processUserInfo(data: ByteArray) {
        val count = data[2].toInt() and 0xFF
        val current = data[3].toInt() and 0xFF

        if (remoteUsers.size == current - 1) {
            val name = decodeString(data, 12, 3)
            val year = 1900 + (data[15].toInt() and 0xFF)
            remoteUsers.add(RemoteUser(decodeUserId(data, 4), name, year))
            logD("User $current/$count: $name ($year)")
        }

        logD("ACK → CMD_USER_INFO")
        sendAck(data)

        if (current != count) {
            // wait for the remaining users
            return
        }

        // Map remote users to local users (by normalized name prefix + birth year)
        val cal = Calendar.getInstance()
        for (local in usersForDevice()) {
            val localName = convertUserNameToScale(local)
            cal.time = local.birthday
            val year = cal.get(Calendar.YEAR)
            for (ru in remoteUsers) {
                if (localName.startsWith(ru.name) && year == ru.year) {
                    ru.localUserId = local.id
                    logD("Remote ${ru.name} (0x${ru.remoteUserId.toString(16)}) → local ${local.userName} (${ru.localUserId})")
                    break
                }
            }
        }

        // Only valid in step 4; proceed
        waitForDataInStep = -1
        proceedTo(5)
    }

    private fun processSavedMeasurement(data: ByteArray) {
        val count = data[2].toInt() and 0xFF
        val current = data[3].toInt() and 0xFF
        logD("Saved measurement part ${if (current % 2 == 1) 1 else 2} of ${count / 2}")

        processMeasurementData(data, 4, firstPart = (current % 2 == 1), processingSavedMeasurements = true)

        logD("ACK → CMD_SAVED_MEASUREMENT")
        sendAck(data)

        if (current != count) {
            // wait for more parts
            return
        }

        // All saved measurements done
        if (waitForDataInStep != 5) {
            // unexpected but tolerated: don't discard data; retry previous step if we were waiting
            if (waitForDataInStep >= 0) {
                logD("Final CMD_SAVED_MEASUREMENT in wrong step; retry previous.")
                proceedTo(max(step - 1, 1))
            }
            return
        }

        readyForData = true
        // Delete saved for the current remote user
        currentRemoteUser?.let {
            logD("→ CMD_DELETE_SAVED_MEASUREMENTS for ${it.name}")
            sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, *encodeUserId(it))
        }
        // wait for ACK
    }

    private fun processWeightMeasurement(data: ByteArray) {
        val stable = data[2] == 0.toByte()
        val weight = getKiloGram(data, 3)
        if (!stable) {
            logD("Active measurement (unstable) weight=%.2f".format(weight))
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weight)
        } else {
            logI("Stable weight (only weight frame): %.2f".format(weight))
        }
    }

    private fun processMeasurement(data: ByteArray) {
        val count = data[2].toInt() and 0xFF
        val current = data[3].toInt() and 0xFF
        logD("Measurement part $current of $count")

        if (current == 1) {
            val uid = decodeUserId(data, 5)
            storedMeasurement.candidateUid = uid
            currentRemoteUser = remoteUsers.firstOrNull { it.remoteUserId == uid }
            if (currentRemoteUser == null) {
                logD("No local user identified for remote UID $uid")
            } else {
                logD("User ${currentRemoteUser!!.name} matches UID $uid")
            }
        } else {
            processMeasurementData(data, 4, firstPart = (current == 2), processingSavedMeasurements = false)
        }

        // Always ACK
        logD("ACK → CMD_MEASUREMENT")
        sendAck(data)

        if (current != count) {
            // wait for more parts
            return
        }

        // All parts received
        if (currentRemoteUser != null && readyForData) {
            // Ask scale to delete saved now that we stored it
            logD("→ CMD_DELETE_SAVED_MEASUREMENTS")
            sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, *encodeUserId(currentRemoteUser))
            return
        }

        // If we were explicitly waiting in step 6 or 8, continue
        if (waitForDataInStep == 6 || waitForDataInStep == 8) {
            proceedTo(step + 1)
        } else if (waitForDataInStep >= 0) {
            // Wrong phase but we were waiting for something: try previous
            proceedTo(max(step - 1, 1))
        }
    }

    private fun processScaleAck(data: ByteArray, selectedUser: ScaleUser) {
        when (data[2]) {
            CMD_SCALE_STATUS -> {
                val batteryLevel = data[4].toInt() and 0xFF
                val weightThreshold = (data[5].toInt() and 0xFF) / 10f
                val bodyFatThreshold = (data[6].toInt() and 0xFF) / 10f
                val currentUnit = data[7].toInt() and 0xFF
                val userExists = data[8] == 0.toByte()
                val userReferWeightExists = data[9] == 0.toByte()
                val userMeasurementExist = data[10] == 0.toByte()
                val scaleVersion = data[11].toInt() and 0xFF

                logD(
                    "ScaleStatus: battery=$batteryLevel wtTh=%.1f fatTh=%.1f unit=$currentUnit " +
                            "userExists=$userExists refWeight=$userReferWeightExists hasMeas=$userMeasurementExist ver=$scaleVersion"
                                .format(weightThreshold, bodyFatThreshold)
                )

                if (batteryLevel <= 10) {
                    userWarn(R.string.bluetooth_scale_warning_low_battery, batteryLevel)
                }

                val desiredUnit = when (selectedUser.scaleUnit) {
                    com.health.openscale.core.data.WeightUnit.KG -> 1
                    com.health.openscale.core.data.WeightUnit.LB -> 2
                    com.health.openscale.core.data.WeightUnit.ST -> 4
                }

                if (desiredUnit != currentUnit) {
                    logD("→ CMD_SET_UNIT to ${selectedUser.scaleUnit} ($desiredUnit)")
                    sendCommand(CMD_SET_UNIT, desiredUnit.toByte())
                    // wait for ACK to CMD_SET_UNIT, still within step 3
                } else {
                    // Finished step 3
                    waitForDataInStep = -1
                    proceedTo(4)
                }
            }

            CMD_SET_UNIT -> {
                if (data[3] == 0.toByte()) logD("Scale unit successfully set")
                // End of step 3
                waitForDataInStep = -1
                proceedTo(4)
            }

            CMD_USER_LIST -> {
                val userCount = data[4].toInt() and 0xFF
                val maxUsers = data[5].toInt() and 0xFF
                logD("UserList: $userCount users (max $maxUsers)")
                if (userCount == 0) {
                    waitForDataInStep = -1
                    proceedTo(5) // move on
                } else {
                    // expect CMD_USER_INFO items, keep waiting
                }
            }

            CMD_GET_SAVED_MEASUREMENTS -> {
                val measurementCount = data[3].toInt() and 0xFF
                logD("ACK GET_SAVED_MEASUREMENTS: $measurementCount parts")
                if (measurementCount == 0) {
                    readyForData = true
                    waitForDataInStep = -1
                    proceedTo(6)
                } else {
                    // wait for CMD_SAVED_MEASUREMENT parts
                }
            }

            CMD_DELETE_SAVED_MEASUREMENTS -> {
                if (data[3] == 0.toByte()) {
                    logD("Saved measurements successfully deleted for ${currentRemoteUser?.name}")
                }
                waitForDataInStep = -1
                // After deleting saved, continue with user ensure or finalization
                if (step < 6) proceedTo(6) else proceedTo(8)
            }

            CMD_USER_ADD -> {
                // Only valid in step 6
                if (data[3] == 0.toByte()) {
                    currentRemoteUser?.let {
                        it.isNew = true
                        // If we have pending data → store now
                        storedMeasurement.measurementData?.let { buf ->
                            logD("User identified, storing deferred data")
                            addMeasurement(buf, currentRemoteUser!!.localUserId)
                            storedMeasurement.measurementData = null
                        }
                        readyForData = true
                        // Try to kick off a reference measurement
                        userInfo(R.string.bluetooth_scale_info_step_on_for_reference, 0)
                        logD("→ CMD_DO_MEASUREMENT (start reference)")
                        sendCommand(CMD_DO_MEASUREMENT, *encodeUserId(currentRemoteUser))
                        // wait for measurement
                        waitForDataInStep = 6
                    } ?: run { proceedTo(7) }
                } else {
                    userWarn(R.string.bluetooth_scale_error_max_users_reached, 0)
                    // terminate gracefully
                }
            }

            CMD_DO_MEASUREMENT -> {
                if (data[3] != 0.toByte()) {
                    logD("Measure command rejected")
                    waitForDataInStep = -1
                    proceedTo(7)
                } else {
                    userInfo(R.string.bt_info_step_on_scale, 0)
                    // wait for measurement frames
                }
            }

            CMD_USER_DETAILS -> {
                if (data[3] == 0.toByte()) {
                    val name = decodeString(data, 4, 3)
                    val year = 1900 + (data[7].toInt() and 0xFF)
                    val month = 1 + (data[8].toInt() and 0xFF)
                    val day = data[9].toInt() and 0xFF
                    val height = data[10].toInt() and 0xFF
                    val male = (data[11].toInt() and 0xF0) != 0
                    val activity = data[11].toInt() and 0x0F
                    logD("UserDetails name=$name, dob=$year-%02d-%02d, height=$height, male=$male, activity=$activity"
                        .format(month, day))
                }
                waitForDataInStep = -1
                proceedTo(8)
            }

            else -> logD("Unhandled ACK for 0x%02X".format(data[2]))
        }
    }

    // -------------------- Helpers (encoding, parsing, storage) --------------------

    private fun processMeasurementData(
        data: ByteArray,
        offset: Int,
        firstPart: Boolean,
        processingSavedMeasurements: Boolean
    ) {
        if (firstPart) {
            if (measurementData != null) logD("Discarding previous partial data")
            measurementData = data.copyOfRange(offset, data.size)
            return
        }

        val existing = measurementData ?: run {
            logD("Got second part without first; discarding")
            return
        }
        val toCopy = data.size - offset
        val merged = existing.copyOf(existing.size + toCopy)
        System.arraycopy(data, offset, merged, existing.size, toCopy)
        measurementData = merged

        // Store immediately if ready and user known
        val ru = currentRemoteUser
        if (ru != null && (readyForData || processingSavedMeasurements)) {
            logD("Measurement complete + user known → store")
            addMeasurement(merged, ru.localUserId)
            dataReceived = true

            // If we had deferred data and it matches the same timestamp/user, drop it
            storedMeasurement.measurementData?.let { pending ->
                if (ru.remoteUserId == storedMeasurement.storedUid) {
                    val tsA = ConverterUtils.fromUnsignedInt32Be(merged, 0)
                    val tsB = ConverterUtils.fromUnsignedInt32Be(pending, 0)
                    if (tsA == tsB) {
                        logD("Dropping deferred data (duplicate of saved data)")
                        storedMeasurement.measurementData = null
                    }
                }
            }
            measurementData = null
            storedMeasurement.measurementData = null
        } else if (!processingSavedMeasurements) {
            // Defer storing
            val reason = if (!readyForData) "app not ready" else "user not identified"
            logD("Measurement complete but not stored ($reason) → defer")
            storedMeasurement.measurementData = merged
            storedMeasurement.storedUid = storedMeasurement.candidateUid
        } else {
            logD("Saved measurement arrived but no user identified → discard")
            measurementData = null
        }
    }

    private fun addMeasurement(buf: ByteArray, userId: Int) {
        val timestampMs = ConverterUtils.fromUnsignedInt32Be(buf, 0) * 1000L
        val weight = getKiloGram(buf, 4)
        val impedance = ConverterUtils.fromUnsignedInt16Be(buf, 6) // FYI
        val fat = getPercent(buf, 8)
        val water = getPercent(buf, 10)
        val muscle = getPercent(buf, 12)
        val bone = getKiloGram(buf, 14)
        // bmr/amr/bmi present but not stored in app DB in legacy code

        val m = ScaleMeasurement().apply {
            setUserId(userId)
            dateTime = Date(timestampMs)
            this.weight = weight
            this.fat = fat
            this.water = water
            this.muscle = muscle
            this.bone = bone
        }
        publish(m)
    }

    private fun getKiloGram(data: ByteArray, offset: Int): Float {
        // Unit is 50 g
        return ConverterUtils.fromUnsignedInt16Be(data, offset) * 50.0f / 1000.0f
    }

    private fun getPercent(data: ByteArray, offset: Int): Float {
        // Unit is 0.1 %
        return ConverterUtils.fromUnsignedInt16Be(data, offset) / 10.0f
    }

    private fun decodeUserId(data: ByteArray, offset: Int): Long {
        val high = ConverterUtils.fromUnsignedInt32Be(data, offset)
        val low = ConverterUtils.fromUnsignedInt32Be(data, offset + 4)
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    private fun encodeUserId(remoteUser: RemoteUser?): ByteArray {
        val uid = remoteUser?.remoteUserId ?: 0L
        val out = ByteArray(8)
        ConverterUtils.toInt32Be(out, 0, uid shr 32)
        ConverterUtils.toInt32Be(out, 4, uid and 0xFFFFFFFF)
        return out
    }

    private fun decodeString(data: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && data[offset + len] != 0.toByte()) len++
        return String(data, offset, len)
    }

    private fun normalizeString(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace("[^A-Za-z0-9]".toRegex(), "")
    }

    private fun convertUserNameToScale(user: ScaleUser): String {
        val n = normalizeString(user.userName)
        return if (n.isEmpty()) "${user.id}" else n.uppercase(Locale.US)
    }

    // -------------------- Outgoing (writes) --------------------

    private fun writeBytes(raw: ByteArray) {
        writeTo(SERVICE, CHR, raw, withResponse = true)
    }

    private fun sendCommand(command: Byte, vararg params: Byte) {
        val data = ByteArray(params.size + 2)
        data[0] = startByte
        data[1] = command
        for (i in params.indices) data[2 + i] = params[i]
        writeBytes(data)
    }

    private fun sendAck(incoming: ByteArray) {
        // echo bytes [1..3] (cmd + seq)
        val echo = incoming.copyOfRange(1, 4)
        val data = ByteArray(1 + echo.size)
        data[0] = startByte
        data[1] = CMD_APP_ACK
        // shift because we already placed start+ACK?
        // Legacy sent start+CMD_APP_ACK + copy 1..3; we replicate:
        val out = ByteArray(2 + echo.size)
        out[0] = startByte
        out[1] = CMD_APP_ACK
        System.arraycopy(echo, 0, out, 2, echo.size)
        writeBytes(out)
    }

    private fun sendAlternativeStartCode(idNibble: Int, vararg payload: Byte) {
        val alt = getAlternativeStartByte(idNibble)
        val data = ByteArray(1 + payload.size)
        data[0] = alt
        payload.copyInto(data, destinationOffset = 1) // payload ist ein ByteArray bei vararg
        writeBytes(data)
    }

    private fun getAlternativeStartByte(startNibble: Int): Byte {
        // Combine high nibble from startByte with provided low nibble
        return ((startByte.toInt() and 0xF0) or (startNibble and 0x0F)).toByte()
    }

    // -------------------- Remote user creation --------------------

    private fun createRemoteUser(scaleUser: ScaleUser) {
        logD("Create remote user for ${scaleUser.userName}")

        val cal = Calendar.getInstance().apply { time = scaleUser.birthday }
        val nick = convertUserNameToScale(scaleUser).toByteArray().copyOf(3) // pad/trim to 3
        val year = (cal.get(Calendar.YEAR) - 1900).toByte()
        val month = cal.get(Calendar.MONTH).toByte() // device expects 0..11
        val day = cal.get(Calendar.DAY_OF_MONTH).toByte()
        val height = scaleUser.bodyHeight.toInt().toByte()
        val sex = if (scaleUser.gender.isMale()) 0x80.toByte() else 0x00.toByte()
        val activity = (scaleUser.activityLevel.toInt() + 1).toByte() // 1..5

        // Choose a new remote UID (legacy used "max + 1" with floor 100)
        var maxUid = if (remoteUsers.isEmpty()) 100L else 0L
        for (ru in remoteUsers) maxUid = max(maxUid, ru.remoteUserId)
        val newRemote = RemoteUser(maxUid + 1, String(nick), 1900 + year, scaleUser.id, isNew = true)
        currentRemoteUser = newRemote

        val uid = encodeUserId(newRemote)

        logD("→ CMD_USER_ADD")
        sendCommand(
            CMD_USER_ADD,
            uid[0], uid[1], uid[2], uid[3], uid[4], uid[5], uid[6], uid[7],
            nick[0], nick[1], nick[2], year, month, day, height, (sex or activity)
        )
    }

    // -------------------- Testing hooks --------------------

    @VisibleForTesting
    internal fun setDeviceTypeForTests(dt: DeviceType) {
        deviceType = dt
    }
}
