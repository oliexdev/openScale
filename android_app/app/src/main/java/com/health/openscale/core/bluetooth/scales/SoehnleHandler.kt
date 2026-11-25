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
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.SoehnleLib
import com.health.openscale.core.bluetooth.libs.SoehnleLiveFrame
import com.health.openscale.core.bluetooth.libs.SoehnleUserSlot
import com.health.openscale.core.bluetooth.libs.soehnleCurrentTime
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Percent

/**
 * SoehnleHandler
 * --------------------
 * Modern Kotlin handler for Soehnle smart scales using the custom service
 * 352e3000-28e9-40b8-a361-6db4cca4147c in combination with standard
 * Battery (0x180F), Current Time (0x1805) and User Data (0x181C) services.
 *
 * The device supports per-scale user indices (1..7). We keep a mapping
 * between *app userId* and *scale user index* using [ScaleDeviceHandler]'s
 * built-in DriverSettings helpers (persisted per device address).
 *
 * Flow on connect:
 *  1) (Last resort only) Factory reset if registering a user keeps failing and the
 *     scale's user list shows all slots taken.
 *  2) Subscribe Battery and read initial level.
 *  3) Write Current Time.
 *  4) Subscribe User Control Point (UDS).
 *  5) Create/select user on the scale via UCP and wait for its response.
 *  6) Write age, gender, height.
 *  7) Subscribe custom measurement notifications (A/B).
 *  8) Request the stored history of the connected user's own scale index only
 *     (openScale saves every published measurement for the current app user).
 *  9) Start the live weight stream (`30 FF 01`); the final live frame is published, the
 *     stream is stopped again (`30 FF 00`) as the vendor app does, and the link is
 *     dropped so the scale is free for the next phone right away.
 */
class SoehnleHandler : ScaleDeviceHandler() {

    override fun supportFor(device: com.health.openscale.core.service.ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        val supported = name.startsWith("Shape200") || name.startsWith("Shape100") ||
                name.startsWith("Shape50") || name.startsWith("Style100")
        return if (supported) {
            DeviceSupport(
                displayName = "Soehnle Scale",
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BATTERY_LEVEL
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BATTERY_LEVEL
                ),
                tuningProfile = TuningProfile.Balanced,
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // --- UUIDs ---------------------------------------------------------------

    // Standard services/characteristics
    private val SVC_BATTERY = uuid16(0x180F)
    private val CHR_BATTERY_LEVEL = uuid16(0x2A19)

    private val SVC_CURRENT_TIME = uuid16(0x1805)
    private val CHR_CURRENT_TIME = uuid16(0x2A2B)

    private val SVC_USER_DATA = uuid16(0x181C)
    private val CHR_USER_CONTROL_POINT = uuid16(0x2A9F)
    private val CHR_USER_AGE = uuid16(0x2A80)
    private val CHR_USER_GENDER = uuid16(0x2A8C)
    private val CHR_USER_HEIGHT = uuid16(0x2A8E)

    // Soehnle custom service
    private val SVC_SOEHNLE = UUID.fromString("352e3000-28e9-40b8-a361-6db4cca4147c")
    private val CHR_SOEHNLE_A = UUID.fromString("352e3001-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_B = UUID.fromString("352e3004-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_CMD = UUID.fromString("352e3002-28e9-40b8-a361-6db4cca4147c") // write

    private val CMD_LIVE_START = byteArrayOf(0x30, 0xFF.toByte(), 0x01)
    private val CMD_LIVE_STOP = byteArrayOf(0x30, 0xFF.toByte(), 0x00)
    private val CMD_LIST_USERS = byteArrayOf(0x0C, 0xFF.toByte())
    private val MAX_CREATE_ATTEMPTS = 8 // registering also answers 02 while the scale is busy
    private val MAX_SELECT_ATTEMPTS = 8 // 16 s, longer than a weighing
    private val SCALE_USER_SLOTS = 8
    private val DISCONNECT_DELAY_MS = 1000L
    private val HISTORY_DISCONNECT_DELAY_MS = 3000L // let the rest of the history arrive
    private val FRESH_HISTORY_MS = 3 * 60 * 1000L
    private val CREATE_RETRY_DELAY_MS = 2000L

    // --- Per-connection state ---------------------------------------------------

    private var sessionStarted = false
    private var liveFinalPublished = false
    private var createAttempts = 0
    private var selectAttempts = 0
    private var selectedIndex = -1
    private var disconnectScheduled = false
    private var resetTried = false
    /** Scale user list from the 0x0C frames: slot index -> registered. */
    private val userSlots = mutableMapOf<Int, Boolean>()

    // --- Lifecycle -----------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // (0) No factory reset up front: the vendor app never sends one, and on a Shape50
        //     registering a user right after `0B FF` was refused with [20 01 02]. The reset
        //     is only used as a last resort when registration keeps failing (scale full).

        // (1) Battery: subscribe + read once
        setNotifyOn(SVC_BATTERY, CHR_BATTERY_LEVEL)
        readFrom(SVC_BATTERY, CHR_BATTERY_LEVEL)

        // Custom notifications early, so the user list below (0x0C frames) is received
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_A)
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_B)

        // (2) Write current time (CTS, full 10-byte layout)
        val calendar = Calendar.getInstance()
        val currentTime = soehnleCurrentTime(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH),
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
            second = calendar.get(Calendar.SECOND),
            calendarDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
        )

        LogManager.d(TAG, "Writing Current Time to $CHR_CURRENT_TIME: ${calendar.time}")

        writeTo(SVC_CURRENT_TIME, CHR_CURRENT_TIME, currentTime, withResponse = true)

        // (3) Subscribe to UDS User Control Point for create/select responses
        setNotifyOn(SVC_USER_DATA, CHR_USER_CONTROL_POINT)

        // (4) Ensure user exists on scale. The rest of the sequence runs once the scale
        //     has answered on the User Control Point (see handleUserControlPoint).
        sessionStarted = false
        liveFinalPublished = false
        createAttempts = 0
        selectAttempts = 0
        disconnectScheduled = false
        resetTried = false
        userSlots.clear()
        // Ask for the scale's user list like the vendor app does (logged for diagnosis)
        writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, CMD_LIST_USERS, withResponse = true)
        val scaleIndex = loadScaleIndexForAppUser(user.id)
        if (scaleIndex == -1) {
            createScaleUser()
        } else {
            selectScaleUser(scaleIndex)
        }
    }

    override fun onDisconnected() {
        sessionStarted = false
    }

    private fun createScaleUser() {
        createAttempts++
        // Payload per legacy: [0x01, 0x00, 0x00] (register new user, consent code 0x0000)
        writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, byteArrayOf(0x01, 0x00, 0x00), withResponse = true)
    }

    private fun selectScaleUser(scaleIndex: Int) {
        if (scaleIndex != selectedIndex) selectAttempts = 0
        selectedIndex = scaleIndex
        selectAttempts++
        // [0x02, index, consent code 0x0000]
        writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, byteArrayOf(0x02, scaleIndex.toByte(), 0x00, 0x00), withResponse = true)
    }

    /** Remaining steps, run once per connection after the scale user is created or selected. */
    private fun startSession(user: ScaleUser) {
        if (sessionStarted) return
        sessionStarted = true

        // (5-7) Push profile fields
        writeTo(SVC_USER_DATA, CHR_USER_AGE, byteArrayOf(user.age.toByte()), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_GENDER, byteArrayOf(if (user.gender.isMale()) 0x00 else 0x01), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_HEIGHT, ConverterUtils.toInt16Le(user.bodyHeight.toInt()), withResponse = true)

        // (9) Request the history of this user's scale index only, like the vendor app.
        //     Every published measurement is stored for the current app user, so other
        //     users' history must not be fetched here.
        val ownIndex = loadScaleIndexForAppUser(user.id)
        if (ownIndex in 1..7) {
            writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x09, ownIndex.toByte()), withResponse = true)
        }

        // (10) Start the live weight stream
        writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, CMD_LIVE_START, withResponse = true)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return
        when (characteristic) {
            CHR_SOEHNLE_A -> {
                if (data[0] != 0x30.toByte()) logD("352e3001: ${data.toHexPreview(20)}")
                handleUserListFrame(data)
                handleSoehnleA(data, user)
                handleLiveFrame(data, user)
            }
            CHR_USER_CONTROL_POINT -> handleUserControlPoint(data, user)
            CHR_BATTERY_LEVEL -> handleBattery(data)
            else -> Unit
        }
    }

    protected fun saveUserIdForScaleIndex(scaleIndex: Int) {
        settingsPutInt("userMap/userIdByIndex/$scaleIndex", -1)
    }
    protected fun loadUserIdForScaleIndex(scaleIndex: Int): Int =
        settingsGetInt("userMap/userIdByIndex/$scaleIndex", -1)

    protected fun loadScaleIndexForAppUser(appUserId: Int): Int =
        settingsGetInt("userMap/scaleIndexByAppUser/$appUserId", -1)

    protected fun saveScaleIndexForAppUser(appUserId: Int, scaleIndex: Int) {
        settingsPutInt("userMap/scaleIndexByAppUser/$appUserId", scaleIndex)
        settingsPutInt("userMap/userIdByIndex/$scaleIndex", appUserId)
    }
    // --- Handlers -------------------------------------------------------------

    private fun handleBattery(value: ByteArray) {
        val level = (value.first().toInt() and 0xFF)
        if (level <= 10) {
            userWarn(R.string.bluetooth_scale_warning_low_battery, level)
        }
    }

    private fun handleUserControlPoint(value: ByteArray, user: ScaleUser) {
        if (value.isEmpty() || value[0] != 0x20.toByte()) return
        val cmd = value.getOrNull(1)?.toInt() ?: return
        when (cmd) {
            0x01 -> { // user create
                val success = value.getOrNull(2) ?: return
                val idx = value.getOrNull(3)?.toInt()
                if (success == 0x01.toByte() && idx != null) {
                    saveScaleIndexForAppUser(user.id, idx)
                    userInfo(R.string.bluetooth_scale_info_step_on_for_reference, 0)
                    // The vendor app gives consent for the user before it starts a
                    // live measurement; do the same for the freshly created one.
                    selectScaleUser(idx)
                } else if (createAttempts < MAX_CREATE_ATTEMPTS) {
                    // Seen on a Shape50 right after a factory reset: [20 01 02].
                    // Give the scale time to finish the reset and try again.
                    logW("Soehnle: creating user failed (${value.toHexPreview(8)}), retry $createAttempts/$MAX_CREATE_ATTEMPTS")
                    scope.launch {
                        delay(CREATE_RETRY_DELAY_MS)
                        createScaleUser()
                    }
                } else if (!resetTried && allSlotsTaken()) {
                    // Every user slot is taken (other phones/apps): wipe and retry once.
                    // Never reset otherwise - it would delete the users of other phones.
                    logW("Soehnle: creating user keeps failing and all ${userSlots.size} slots are taken, factory reset as last resort")
                    resetTried = true
                    createAttempts = 0
                    factoryReset()
                    writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, CMD_LIST_USERS, withResponse = true)
                    scope.launch {
                        delay(CREATE_RETRY_DELAY_MS)
                        createScaleUser()
                    }
                } else {
                    // Without a user the scale rejects the profile and sends no live data;
                    // still request history in case it has stored measurements.
                    logE("Soehnle: error creating user, response ${value.toHexPreview(8)}")
                    startSession(user)
                }
            }
            0x02 -> { // user select
                val success = value.getOrNull(2) ?: return
                if (success == 0x01.toByte()) {
                    startSession(user)
                } else if (sessionStarted) {
                    logE("Soehnle: error selecting user")
                } else if (userSlots[selectedIndex] == false) {
                    // The scale lists our slot as free (wiped by a reset elsewhere): register anew
                    logE("Soehnle: scale user index $selectedIndex no longer exists; creating a new one")
                    createScaleUser()
                } else if (selectAttempts < MAX_SELECT_ATTEMPTS) {
                    // [20 02 02] comes while the scale is busy, e.g. measuring someone who
                    // stepped on before the app connected; it accepts the same command once
                    // the weighing is done. Never register a second user for this.
                    logW("Soehnle: selecting user failed (${value.toHexPreview(8)}), retry $selectAttempts/$MAX_SELECT_ATTEMPTS")
                    val idx = selectedIndex
                    scope.launch {
                        delay(CREATE_RETRY_DELAY_MS)
                        selectScaleUser(idx)
                    }
                } else {
                    // Still busy: fetch whatever the scale stored (a weighing done meanwhile)
                    logE("Soehnle: selecting user keeps failing (${value.toHexPreview(8)})")
                    startSession(user)
                }
            }
        }
    }

    private fun handleUserListFrame(value: ByteArray) {
        val slot = SoehnleUserSlot.parse(value) ?: return
        userSlots[slot.index] = slot.registered
    }

    private fun allSlotsTaken(): Boolean =
        userSlots.size >= SCALE_USER_SLOTS && userSlots.values.all { it }

    private fun handleLiveFrame(value: ByteArray, user: ScaleUser) {
        val frame = SoehnleLiveFrame.parse(value) ?: return
        if (frame.weightKg <= 0f) return // stream runs before anyone steps on

        if (!frame.isFinal) {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, frame.weightKg)
            return
        }
        if (liveFinalPublished) return
        liveFinalPublished = true

        // Stop the stream like the vendor app does after the final frame
        writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, CMD_LIVE_STOP, withResponse = true)

        // The weighing belongs to whoever is connected right now (openScale stores it for
        // the current app user anyway); skip it only if the scale attributes it to an
        // index that is mapped to a different app user.
        val mappedUserId = loadUserIdForScaleIndex(frame.userIndex)
        if (mappedUserId != -1 && mappedUserId != user.id) {
            logW("Skipping live measurement of scale user index ${frame.userIndex} (app user $mappedUserId, connected ${user.id})")
            return
        }
        val openScaleUserId = user.id
        logI("Final live measurement: ${frame.weightKg} kg (user index ${frame.userIndex}, status 0x%02X)".format(frame.status))
        publish(buildMeasurement(openScaleUserId, Date(), frame.weightKg, frame.imp5, frame.imp50))

        // Free the scale for the next phone; give the stop command time to go out first
        scheduleDisconnect(DISCONNECT_DELAY_MS)
    }

    private fun handleSoehnleA(value: ByteArray, user: ScaleUser) {
        // Only handle 0x09 frames of length 15
        if (value.size != 15 || value[0] != 0x09.toByte()) return

        val weightKg = ConverterUtils.fromUnsignedInt16Be(value, 9) / 10.0f
        val soehnleUserIndex = (value[1].toInt() and 0xFF)
        val year = ConverterUtils.fromUnsignedInt16Be(value, 2)
        val month = (value[4].toInt() and 0xFF)
        val day = (value[5].toInt() and 0xFF)
        val hour = (value[6].toInt() and 0xFF)
        val minute = (value[7].toInt() and 0xFF)
        val second = (value[8].toInt() and 0xFF)

        val imp5 = ConverterUtils.fromUnsignedInt16Be(value, 11)
        val imp50 = ConverterUtils.fromUnsignedInt16Be(value, 13)

        val cal: Calendar = GregorianCalendar(TimeZone.getDefault()).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

        val openScaleUserId = loadUserIdForScaleIndex(soehnleUserIndex)
        if (openScaleUserId == -1) {
            logE("Unknown Soehnle user index $soehnleUserIndex")
            return
        }
        if (openScaleUserId != user.id) {
            logW("Skipping history of scale user index $soehnleUserIndex (app user $openScaleUserId, connected ${user.id})")
            return
        }

        publish(buildMeasurement(openScaleUserId, cal.time, weightKg, imp5, imp50))

        // A weighing that finished just now (the user stepped on before the app connected)
        // means there is nothing left to wait for: free the scale.
        if (System.currentTimeMillis() - cal.timeInMillis in 0..FRESH_HISTORY_MS) {
            scheduleDisconnect(HISTORY_DISCONNECT_DELAY_MS)
        }
    }

    private fun scheduleDisconnect(delayMs: Long) {
        if (disconnectScheduled) return
        disconnectScheduled = true
        scope.launch {
            delay(delayMs)
            requestDisconnect()
        }
    }

    private fun buildMeasurement(openScaleUserId: Int, dateTime: Date, weightKg: Float, imp5: Int, imp50: Int): ScaleMeasurement {
        // We need user's profile for composition calcs → try to use the currently selected user
        // (This is usually the same as 'openScaleUserId')
        val u = try { currentAppUser() } catch (_: Throwable) { null }
        val activity = mapActivityLevel(u)
        val isMale = u?.gender?.isMale() ?: true
        val age = u?.age ?: 30
        val height = u?.bodyHeight ?: 175f

        val lib = SoehnleLib(isMale, age, height, activity)

        return ScaleMeasurement().apply {
            userId = openScaleUserId
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            this.dateTime = dateTime
            // Body composition only from usable impedances; otherwise the weight alone
            if (SoehnleLib.isValidImpedance(imp50)) {
                this[MeasurementType.WATER] = Percent(lib.getWater(weightKg, imp50.toFloat()))
                this[MeasurementType.BODY_FAT] = Percent(lib.getFat(weightKg, imp50.toFloat()))
                if (SoehnleLib.isValidImpedance(imp5)) {
                    this[MeasurementType.MUSCLE] = Percent(lib.getMuscle(weightKg, imp50.toFloat(), imp5.toFloat()))
                }
            } else {
                logW("Soehnle: impedance not usable (imp5=0x%04X, imp50=0x%04X), weight only".format(imp5, imp50))
            }
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun factoryReset() {
        logD("Soehnle: factory reset + clear mappings")
        writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x0B, 0xFF.toByte()), withResponse = true)
        for (i in 1..7) saveUserIdForScaleIndex(i)
    }

    private fun mapActivityLevel(user: ScaleUser?): Int = when (user?.activityLevel) {
        ActivityLevel.SEDENTARY -> 0
        ActivityLevel.MILD -> 1
        ActivityLevel.MODERATE -> 2
        ActivityLevel.HEAVY -> 4
        ActivityLevel.EXTREME -> 5
        else -> 0
    }
}
