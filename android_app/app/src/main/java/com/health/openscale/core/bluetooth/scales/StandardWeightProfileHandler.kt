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
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/**
 * Handler for **Bluetooth Standard Weight Profile** devices (GATT 181D/181B/181C/1805/180F).
 *
 * - Subscribes to Weight (2A9D) & Body Composition (2A9C)
 * - Sets Current Time (2A2B)
 * - Drives UDS User Control Point (register/select/consent)
 * - Persists mapping: scaleIndex → appUserId and consent: scaleIndex → consentCode
 *
 * Flow:
 * 1) On connect: try auto-consent from persisted mapping/consent.
 * 2) If missing: send UDS LIST_ALL_USERS.
 *    - If list arrives → show CHOOSE_USER with slots (+ “Create new user…”).
 *    - If not supported/empty → show CHOOSE_USER with only “Create new user…”.
 */
open class StandardWeightProfileHandler : ScaleDeviceHandler() {

    // ---- Standard services/characteristics (16-bit UUIDs) --------------------
    private val SVC_DEVICE_INFO                = uuid16(0x180A)
    private val CHR_MANUFACTURER_NAME          = uuid16(0x2A29)
    private val CHR_MODEL_NUMBER               = uuid16(0x2A24)

    private val SVC_CURRENT_TIME               = uuid16(0x1805)
    private val CHR_CURRENT_TIME               = uuid16(0x2A2B)

    private val SVC_WEIGHT_SCALE               = uuid16(0x181D)
    private val CHR_WEIGHT_MEASUREMENT         = uuid16(0x2A9D)

    private val SVC_BODY_COMPOSITION           = uuid16(0x181B)
    private val CHR_BODY_COMPOSITION_MEAS      = uuid16(0x2A9C)

    private val SVC_USER_DATA                  = uuid16(0x181C)
    private val CHR_DATABASE_CHANGE_INCREMENT  = uuid16(0x2A99) // NOTIFY/READ/WRITE
    protected val CHR_USER_CONTROL_POINT         = uuid16(0x2A9F) // Indication

    // UDS user attributes
    private val CHR_USER_DATE_OF_BIRTH         = uuid16(0x2A85) // Year-Month-Day
    private val CHR_USER_GENDER                = uuid16(0x2A8C) // 0=male, 1=female
    private val CHR_USER_HEIGHT                = uuid16(0x2A8E) // centimeters

    private val SVC_BATTERY                    = uuid16(0x180F)
    private val CHR_BATTERY_LEVEL              = uuid16(0x2A19)

    // ---- UDS Control Point opcodes -------------------------------------------
    private val UDS_CP_REGISTER_NEW_USER       = 0x01
    private val UDS_CP_CONSENT                 = 0x02
    private val UDS_CP_DELETE_USER_DATA        = 0x03
    private val UDS_CP_LIST_ALL_USERS          = 0x04
    private val UDS_CP_DELETE_USERS            = 0x05
    private val UDS_CP_RESPONSE                = 0x20

    // UDS response values
    private val UDS_CP_RESP_VALUE_SUCCESS          = 0x01
    private val UDS_CP_RESP_OP_CODE_NOT_SUPPORTED  = 0x02
    private val UDS_CP_RESP_INVALID_PARAMETER      = 0x03
    private val UDS_CP_RESP_OPERATION_FAILED       = 0x04
    private val UDS_CP_RESP_USER_NOT_AUTHORIZED    = 0x05

    // ---- Internal state -------------------------------------------------------
    private var pendingMeasurement: ScaleMeasurement? = null
    private var registeringNewUser = false
    private var pendingAppUserId: Int? = null
    private var pendingConsentForNewUser: Int? = null
    private var awaitingReferenceAfterRegister = false

    /**
     * Identify devices that expose any of the standard scale services.
     */
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val adv = try { device.serviceUuids } catch (_: Throwable) { emptySet<UUID>() }
        val looksStandard = adv?.any {
            it == SVC_WEIGHT_SCALE || it == SVC_BODY_COMPOSITION || it == SVC_USER_DATA
        } == true
        if (!looksStandard) return null

        val capabilities = buildSet {
            add(DeviceCapability.BODY_COMPOSITION)
            add(DeviceCapability.TIME_SYNC)
            add(DeviceCapability.USER_SYNC)
            add(DeviceCapability.BATTERY_LEVEL)
        }
        return DeviceSupport(
            displayName = "Bluetooth Standard Weight Profile",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    protected open fun onRequestMeasurement() { }

    // ---- Connection sequencing ------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // Subscribe first
        setNotifyOn(SVC_WEIGHT_SCALE, CHR_WEIGHT_MEASUREMENT)
        setNotifyOn(SVC_BODY_COMPOSITION, CHR_BODY_COMPOSITION_MEAS)
        setNotifyOn(SVC_USER_DATA, CHR_DATABASE_CHANGE_INCREMENT)
        setNotifyOn(SVC_USER_DATA, CHR_USER_CONTROL_POINT) // Indication
        setNotifyOn(SVC_BATTERY, CHR_BATTERY_LEVEL)

        // Align device clock (best-effort)
        writeTo(SVC_CURRENT_TIME, CHR_CURRENT_TIME, buildCurrentTimePayload())

        // Helpful (non-essential) info
        readFrom(SVC_DEVICE_INFO, CHR_MANUFACTURER_NAME)
        readFrom(SVC_DEVICE_INFO, CHR_MODEL_NUMBER)
        readFrom(SVC_BATTERY, CHR_BATTERY_LEVEL)

        // 1) Try auto-consent from persisted mapping/consent
        if (!tryAutoConsent(user)) {
            // 2) No mapping → try to list users via UDS (may be unsupported)
            requestUdsListAllUsers()
        }

        // Generic hint
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_WEIGHT_MEASUREMENT       -> handleWeightMeasurement(data)
            CHR_BODY_COMPOSITION_MEAS    -> handleBodyCompositionMeasurement(data)
            CHR_USER_CONTROL_POINT       -> handleUcpIndication(data)
            CHR_DATABASE_CHANGE_INCREMENT-> logD("UDS Change Increment notified")
            CHR_MANUFACTURER_NAME, CHR_MODEL_NUMBER -> logD("Device info: ${data.toString(Charsets.UTF_8)}")
            CHR_BATTERY_LEVEL -> {
                if (data.isNotEmpty()) {
                    val level = data[0].toInt() and 0xFF
                    logD("Battery level: $level%")
                    if (level in 0..10) userWarn(R.string.bt_warn_low_battery, level)
                }
            }
            else ->
                logD("Unhandled notification chr=$characteristic len=${data.size} ${data.toHexPreview(24)}")
        }
    }

    override fun onDisconnected() {
        pendingMeasurement?.let {
            publishTransformed(it)
            pendingMeasurement = null
        }

        super.onDisconnected()
    }

    protected open fun transformBeforePublish(m: ScaleMeasurement): ScaleMeasurement {
        logD("transformBeforePublish called for measurement: $m")

        val w = m.weight.takeIf { it > 0f } ?: 1f

        m.muscle = (m.muscle / w) * 100f
        m.water = (m.water / w) * 100f

        logD("transformed values before publish: weight=${m.weight}kg, lbm=${m.lbm}kg, bone=${m.bone}kg, " +
                    "fat=${m.fat}%, muscle=${m.muscle}%, water=${m.water}%")

        return m
    }

    protected fun saveUserIdForScaleIndex(scaleIndex: Int, appUserId: Int) {
        logD("Saving appUserId=$appUserId for scaleIndex=$scaleIndex")
        settingsPutInt("userMap/userIdByIndex/$scaleIndex", appUserId)
    }

    protected fun loadUserIdForScaleIndex(scaleIndex: Int): Int {
        val userId = settingsGetInt("userMap/userIdByIndex/$scaleIndex", -1)
        //logD("Loaded appUserId=$userId for scaleIndex=$scaleIndex")
        return userId
    }

    protected fun saveConsentForScaleIndex(scaleIndex: Int, consent: Int) {
        logD("Saving consent=$consent for scaleIndex=$scaleIndex")
        settingsPutInt("userMap/consentByIndex/$scaleIndex", consent)
    }

    protected fun loadConsentForScaleIndex(scaleIndex: Int): Int {
        val consent = settingsGetInt("userMap/consentByIndex/$scaleIndex", -1)
        logD("Loaded consent=$consent for scaleIndex=$scaleIndex")
        return consent
    }

    private fun publishTransformed(m: ScaleMeasurement) {
        logD("Publishing measurement after transform: $m")
        publish(transformBeforePublish(m))
    }
    // ---- Auto-consent + UDS list ---------------------------------------------

    /** Try to send CONSENT immediately if a persisted mapping/consent exists. */
    private fun tryAutoConsent(user: ScaleUser): Boolean {
        logD("tryAutoConsent called for appUserId=${user.id}")

        val idx = findKnownScaleIndexForAppUser(user.id)
        if (idx == null) {
            logD("No known scale index found for appUserId=${user.id}, auto-consent cannot proceed")
            return false
        }

        logD("Found known scale index=$idx for appUserId=${user.id}")

        val consent = loadConsentForScaleIndex(idx)
        logD("Loaded consent=$consent for scaleIndex=$idx")

        userInfo(R.string.bt_info_using_existing_mapping, user.id, idx)

        if (consent == -1) {
            logD("No consent previously stored for scaleIndex=$idx, requesting consent from user")
            userInfo(R.string.bt_info_consent_needed, idx)
            requestScaleUserConsent(user.id, idx)
        } else {
            logD("Consent found for scaleIndex=$idx, sending consent automatically")
            sendConsent(idx, consent)
        }

        logD("tryAutoConsent finished for appUserId=${user.id} with result=${consent != -1}")
        return true
    }

    /** Send UDS "LIST_ALL_USERS". Many devices don't support this; we handle that gracefully. */
    private fun requestUdsListAllUsers() {
        val payload = byteArrayOf(UDS_CP_LIST_ALL_USERS.toByte())
        logD("→ UCP LIST_ALL_USERS")
        writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, payload)
    }

    /** Show a CHOOSE_USER dialog with only a "Create new user…" option. */
    protected fun presentCreateOnlyChoice() {
        logD("Presenting user choice dialog with only 'Create new user' option")
        val items = arrayOf<CharSequence>(resolveString(R.string.bluetooth_scale_info_create_user_instruction))
        val indices = intArrayOf(-1)
        requestUserInteraction(UserInteractionType.CHOOSE_USER, Pair(items, indices))
        logD("UserInteraction requested: CHOOSE_USER with items=${items.joinToString()} indices=${indices.joinToString()}")
    }

    /** Show a CHOOSE_USER dialog built from simple slot indices (+ "Create new"). */
    protected fun presentChooseFromIndices(indicesList: List<Int>) {
        logD("Presenting user choice dialog with existing scale slots: $indicesList and 'Create new'")
        val labels = indicesList.map { "P%02d".format(it) }.toMutableList<CharSequence>()
        val ids = indicesList.toMutableList()
        labels += resolveString(R.string.bluetooth_scale_info_create_user_instruction)
        ids += -1
        requestUserInteraction(UserInteractionType.CHOOSE_USER, Pair(labels.toTypedArray(), ids.toIntArray()))
        logD("UserInteraction requested: CHOOSE_USER with labels=${labels.joinToString()} ids=${ids.joinToString()}")
    }

    // ---- Decoding & merge logic ----------------------------------------------

    private fun handleWeightMeasurement(value: ByteArray) {
        val m = parseWeightToMeasurement(value) ?: return
        handleNewMeasurement(m)
    }

    private fun handleBodyCompositionMeasurement(value: ByteArray) {
        val m = parseBodyCompToMeasurement(value) ?: return
        handleNewMeasurement(m)
    }

    private fun handleNewMeasurement(newM: ScaleMeasurement) {
        val prev = pendingMeasurement

        // Log for debugging
        logD("mergeWithPrevious called: prev.userId=${prev?.userId}, newM.userId=${newM.userId}")

        if (prev == null) {
            // First packet → just buffer it
            pendingMeasurement = newM
            return
        }

        if (prev.userId != -1) {
            // Merge fields from newM into prev
            prev.mergeWith(newM)

            // When we already have weight (or stabilized) → publish
            if (prev.hasWeight()) {
                publishTransformed(prev)
                pendingMeasurement = null
            } else {
                // Not yet all fields: keep buffering
                pendingMeasurement = prev
            }
        } else {
            // Different userId → publish old, start new
            publishTransformed(prev)
            pendingMeasurement = newM
        }
    }

    private fun parseWeightToMeasurement(value: ByteArray): ScaleMeasurement? {
        if (value.isEmpty()) return null
        var offset = 0

        val flags = u8(value, offset); offset += 1
        val isKg        = (flags and 0x01) == 0
        val tsPresent   = (flags and 0x02) != 0
        val userPresent = (flags and 0x04) != 0
        val bmiHeight   = (flags and 0x08) != 0

        val multiplier = if (isKg) 0.005f else 0.01f
        val weightRaw = u16le(value, offset); offset += 2

        val m = ScaleMeasurement()
        m.weight = weightRaw * multiplier

        if (tsPresent) {
            val cal = Calendar.getInstance()
            val year = u16le(value, offset); offset += 2
            val month = u8(value, offset); offset += 1
            val day = u8(value, offset); offset += 1
            val hour = u8(value, offset); offset += 1
            val minute = u8(value, offset); offset += 1
            val second = u8(value, offset); offset += 1
            cal.set(year, (month - 1).coerceAtLeast(0), day, hour, minute, second)
            m.dateTime = cal.time
        }

        if (userPresent) {
            val scaleUserIndex = u8(value, offset); offset += 1
            val appId = loadUserIdForScaleIndex(scaleUserIndex)
            if (appId != -1) m.userId = appId
            logD("Weight: flags=0x${flags.toString(16)} idx=$scaleUserIndex mappedAppId=$appId kg=$isKg value=${m.weight}")
        }

        if (bmiHeight) {
            val bmi = u16le(value, offset) * 0.1f; offset += 2
            val heightMeters = u16le(value, offset) * 0.001f; offset += 2
            logD("BMI=$bmi height(m)=$heightMeters")
        }

        return m
    }

    private fun parseBodyCompToMeasurement(value: ByteArray): ScaleMeasurement? {
        if (value.size < 4) return null
        var offset = 0

        val flags = u16le(value, offset); offset += 2
        val isKg              = (flags and 0x0001) == 0
        val tsPresent         = (flags and 0x0002) != 0
        val userPresent       = (flags and 0x0004) != 0
        val bmrPresent        = (flags and 0x0008) != 0
        val musclePctPresent  = (flags and 0x0010) != 0
        val muscleMassPresent = (flags and 0x0020) != 0
        val fatFreeMassPresent= (flags and 0x0040) != 0
        val softLeanPresent   = (flags and 0x0080) != 0
        val waterMassPresent  = (flags and 0x0100) != 0
        val impedancePresent  = (flags and 0x0200) != 0
        val weightPresent     = (flags and 0x0400) != 0
        val heightPresent     = (flags and 0x0800) != 0
        val multiPacket       = (flags and 0x1000) != 0

        val massMultiplier = if (isKg) 0.005f else 0.01f

        val m = ScaleMeasurement()

        val bodyFatPct = u16le(value, offset) * 0.1f; offset += 2
        m.fat = bodyFatPct

        if (tsPresent) {
            val cal = Calendar.getInstance()
            val year = u16le(value, offset); offset += 2
            val month = u8(value, offset); offset += 1
            val day = u8(value, offset); offset += 1
            val hour = u8(value, offset); offset += 1
            val minute = u8(value, offset); offset += 1
            val second = u8(value, offset); offset += 1
            cal.set(year, (month - 1).coerceAtLeast(0), day, hour, minute, second)
            m.dateTime = cal.time
        }

        if (userPresent) {
            val scaleUserIndex = u8(value, offset); offset += 1
            val appId = loadUserIdForScaleIndex(scaleUserIndex)
            if (appId != -1) m.userId = appId
            logD("BodyComp: idx=$scaleUserIndex mappedAppId=$appId fat=$bodyFatPct%")
        }

        if (bmrPresent) {
            val bmrJ = u16le(value, offset); offset += 2
            val bmrKcal = ((bmrJ / 4.1868f) * 10f).toInt() / 10f
            logD("BMR ≈ $bmrKcal kcal")
        }

        if (musclePctPresent) {
            val musclePct = u16le(value, offset) * 0.1f; offset += 2
            m.muscle = musclePct
        }

        var softLean = 0.0f
        if (muscleMassPresent) {
            val muscleMass = u16le(value, offset) * massMultiplier; offset += 2
            logD("Muscle mass=$muscleMass kg")
        }

        if (fatFreeMassPresent) {
            val ffm = u16le(value, offset) * massMultiplier; offset += 2
            logD("Fat-free mass=$ffm kg")
        }

        if (softLeanPresent) {
            softLean = u16le(value, offset) * massMultiplier; offset += 2
            logD("Soft lean mass=$softLean kg")
        }

        if (waterMassPresent) {
            val bodyWaterMass = u16le(value, offset) * massMultiplier; offset += 2
            m.water = bodyWaterMass
        }

        if (impedancePresent) {
            val z = u16le(value, offset) * 0.1f; offset += 2
            logD("Impedance=$z Ω")
        }

        if (weightPresent) {
            val w = u16le(value, offset) * massMultiplier; offset += 2
            m.weight = w
        } else {
            pendingMeasurement?.weight?.takeIf { it > 0f }?.let { m.weight = it }
        }

        if (heightPresent) {
            val heightVal = u16le(value, offset); offset += 2
            logD("Height(raw)=$heightVal")
        }

        if (multiPacket) logW("Body Composition: multi-packet measurement not supported")

        // Derive LBM & bone if we have soft-lean and weight
        val w2 = m.weight
        if (w2 > 0f && softLeanPresent) {
            val fatMass = w2 * (m.fat / 100f)
            val leanBodyMass = w2 - fatMass
            val boneMass = leanBodyMass - softLean
            m.lbm = leanBodyMass
            m.bone = boneMass
        }

        return m
    }

    // ---- UDS: User Control Point + User data writes ---------------------------

    private fun handleUcpIndication(value: ByteArray) {
        if (value.isEmpty()) {
            logW("UCP indication received with empty payload")
            return
        }

        val op = value[0].toInt() and 0xFF
        if (op != UDS_CP_RESPONSE) {
            logD("UCP indication received with non-response opcode=0x${op.toString(16)} data=${value.toHex()}")
            return
        }

        if (value.size < 3) {
            logW("UCP response too short: ${value.toHex()}")
            return
        }

        val reqOp  = value[1].toInt() and 0xFF
        val result = value[2].toInt() and 0xFF
        logD("UCP response received: reqOp=0x${reqOp.toString(16)} result=$result fullData=${value.toHex()}")

        when (reqOp) {
            UDS_CP_REGISTER_NEW_USER -> {
                if (result == UDS_CP_RESP_VALUE_SUCCESS && value.size >= 4) {
                    val newScaleIndex = value[3].toInt() and 0xFF
                    val appId = pendingAppUserId ?: currentAppUser().id
                    logD("UDS REGISTER_NEW_USER success: scaleIndex=$newScaleIndex for appUserId=$appId")

                    for (i in 0..255) {
                        if (i != newScaleIndex && loadUserIdForScaleIndex(i) == appId) {
                            saveUserIdForScaleIndex(i, -1)
                            logD("Cleared previous mapping for scaleIndex=$i and appUserId=$appId")
                        }
                    }

                    saveUserIdForScaleIndex(newScaleIndex, appId)
                    pendingConsentForNewUser?.let {
                        saveConsentForScaleIndex(newScaleIndex, it)
                        logD("Saved pending consent $it for new scaleIndex=$newScaleIndex")
                    }

                    logD("Writing user data to scale for new user...")
                    writeUserDataToScale()

                    val consent = loadConsentForScaleIndex(newScaleIndex).takeIf { it != -1 }
                        ?: pendingConsentForNewUser ?: randomConsent().also {
                            saveConsentForScaleIndex(newScaleIndex, it)
                            logD("Generated random consent $it for new scaleIndex=$newScaleIndex")
                        }
                    sendConsent(newScaleIndex, consent)

                    awaitingReferenceAfterRegister = true
                    registeringNewUser = false
                    pendingAppUserId = null
                    pendingConsentForNewUser = null
                } else {
                    logW("UDS REGISTER_NEW_USER failed with result=$result")
                    if (result == UDS_CP_RESP_OPERATION_FAILED) {
                        userWarn(R.string.bt_warn_slots_full)
                    } else {
                        userWarn(R.string.bt_warn_register_failed_with_code, result)
                    }
                    registeringNewUser = false
                }
            }

            UDS_CP_CONSENT -> {
                when (result) {
                    UDS_CP_RESP_VALUE_SUCCESS -> {
                        logD("UDS CONSENT success for appUserId=${pendingAppUserId ?: currentAppUser().id}")
                        pendingAppUserId = null
                        if (awaitingReferenceAfterRegister) {
                            userInfo(R.string.bluetooth_scale_info_step_on_for_reference)
                            awaitingReferenceAfterRegister = false
                            logD("Prompted user to step on scale for reference measurement")
                        }
                        writeUserDataToScale()
                        onRequestMeasurement()
                        logD("onRequestMeasurement() triggered after successful consent")
                    }
                    UDS_CP_RESP_USER_NOT_AUTHORIZED -> {
                        userError(R.string.bt_error_ucp_not_authorized)
                        val appId = pendingAppUserId ?: currentAppUser().id
                        val idx = findKnownScaleIndexForAppUser(appId)
                        idx?.let { userInfo(R.string.bt_info_consent_needed, it) }
                        logW("UDS CONSENT failed: User not authorized for appUserId=$appId")
                        idx?.let {
                            saveConsentForScaleIndex(it, -1)
                            logD("Cleared bad consent for scaleIndex=$it, re-prompting user")
                            requestScaleUserConsent(appId, it)
                        }
                    }
                    else -> {
                        logW("UDS CONSENT unhandled result=$result for appUserId=${pendingAppUserId ?: currentAppUser().id}")
                    }
                }
            }

            UDS_CP_LIST_ALL_USERS -> {
                logD("UDS LIST_ALL_USERS response: ${value.toHex()} (result=$result)")
                if (result == UDS_CP_RESP_VALUE_SUCCESS) {
                    if (value.size >= 4) {
                        val count = value[3].toInt() and 0xFF
                        logD("LIST_ALL_USERS: user count=$count")
                        if (count > 0 && value.size >= 4 + count) {
                            val indices = mutableListOf<Int>()
                            for (i in 0 until count) {
                                indices += (value[4 + i].toInt() and 0xFF)
                            }
                            logD("Available user slots: $indices")
                            presentChooseFromIndices(indices)
                        } else {
                            logD("LIST_ALL_USERS success but no users found, defaulting to 'Create new'")
                            findKnownScaleIndexForAppUser(currentAppUser().id)?.let { idx ->
                                saveUserIdForScaleIndex(idx, -1)
                                logD("Cleared previous mapping for currentAppUserId at scaleIndex=$idx")
                            }
                            presentCreateOnlyChoice()
                        }
                    } else {
                        logW("LIST_ALL_USERS payload too short, using fallback")
                        presentCreateOnlyChoice()
                    }
                } else {
                    logW("LIST_ALL_USERS not supported or failed, switching to read-only mode")
                    userInfo(R.string.bt_info_read_only_mode_no_consent)
                    userInfo(R.string.bt_info_suggest_create_new_user)
                    presentCreateOnlyChoice()
                }
            }

            else -> logW("UCP response received for unknown reqOp=0x${reqOp.toString(16)} result=$result data=${value.toHex()}")
        }
    }

    /**
     * Write core UDS user data (best effort).
     */
    protected open fun writeUserDataToScale() {
        logD("writeUserDataToScale() called")
        val u = currentAppUser()

        // Date of Birth: Year(2 LE), Month(1), Day(1)
        val dob = Calendar.getInstance().apply { time = u.birthday }
        val year = dob.get(Calendar.YEAR)
        val month = dob.get(Calendar.MONTH) + 1
        val day = dob.get(Calendar.DAY_OF_MONTH)
        val dobPayload = byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            month.toByte(),
            day.toByte()
        )
        writeTo(SVC_USER_DATA, CHR_USER_DATE_OF_BIRTH, dobPayload)

        // Gender: 0 male, 1 female
        val gender = if (u.gender.isMale()) 0 else 1
        writeTo(SVC_USER_DATA, CHR_USER_GENDER, byteArrayOf(gender.toByte()))

        // Height in cm (uint16, LE)
        val heightCm = u.bodyHeight.toInt().coerceIn(0, 300)
        val heightPayload = byteArrayOf(
            (heightCm and 0xFF).toByte(), ((heightCm shr 8) and 0xFF).toByte()
        )
        writeTo(SVC_USER_DATA, CHR_USER_HEIGHT, heightPayload)

        // Change Increment: write 1 to bump DB revision (optional)
        val changeInc = 1
        val changePayload = byteArrayOf(
            (changeInc and 0xFF).toByte(),
            ((changeInc shr 8) and 0xFF).toByte(),
            ((changeInc shr 16) and 0xFF).toByte(),
            ((changeInc shr 24) and 0xFF).toByte()
        )
        writeTo(SVC_USER_DATA, CHR_DATABASE_CHANGE_INCREMENT, changePayload)
    }

    // ---- UI round-trips (user selection & consent) ----------------------------

    override suspend fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    ) {
        logD("UserInteractionFeedback received: type=$interactionType appUserId=$appUserId feedbackData=$feedbackData")

        when (interactionType) {
            UserInteractionType.CHOOSE_USER -> {
                val scaleIndex = (feedbackData as? Int)
                if (scaleIndex == null) {
                    logW("CHOOSE_USER feedback is not Int: $feedbackData")
                    return
                }

                pendingAppUserId = appUserId
                logD("CHOOSE_USER selected: scaleIndex=$scaleIndex for appUserId=$appUserId")

                if (scaleIndex == -1) {
                    // Create/register new user on the scale
                    val consent = randomConsent().also { pendingConsentForNewUser = it }
                    registeringNewUser = true
                    logD("Starting registration of new user with appUserId=$appUserId and generated consent=$consent")
                    userInfo(R.string.bt_info_register_new_user_started)
                    sendRegisterNewUser(consent)
                } else {
                    // Existing slot selected: persist mapping; use stored consent if available
                    logD("Linking existing scale slot $scaleIndex to appUserId=$appUserId")
                    for (i in 0..255) {
                        if (i != scaleIndex && loadUserIdForScaleIndex(i) == appUserId) {
                            saveUserIdForScaleIndex(i, -1)
                            logD("Cleared previous mapping for appUserId=$appUserId at scaleIndex=$i")
                        }
                    }

                    saveUserIdForScaleIndex(scaleIndex, appUserId)
                    userInfo(R.string.bt_info_linked_app_user_to_slot, appUserId, scaleIndex)

                    val consent = loadConsentForScaleIndex(scaleIndex)
                    if (consent == -1) {
                        logD("No consent found for scaleIndex=$scaleIndex, requesting consent")
                        userInfo(R.string.bt_info_consent_needed, scaleIndex)
                        requestScaleUserConsent(appUserId, scaleIndex)
                    } else {
                        logD("Found existing consent=$consent for scaleIndex=$scaleIndex, sending to scale")
                        sendConsent(scaleIndex, consent)
                    }
                }
            }

            UserInteractionType.ENTER_CONSENT -> {
                logD("ENTER_CONSENT feedback received: $feedbackData for appUserId=$appUserId")

                var consent: Int? = null
                var scaleIndex: Int? = null

                when (feedbackData) {
                    is Int -> consent = feedbackData
                    is IntArray -> if (feedbackData.size >= 2) { scaleIndex = feedbackData[0]; consent = feedbackData[1] }
                    is Pair<*, *> -> {
                        val a = (feedbackData.first as? Int)
                        val b = (feedbackData.second as? Int)
                        if (a != null && b != null) {
                            if (a in 0..255) { scaleIndex = a; consent = b }
                            else if (b in 0..255) { scaleIndex = b; consent = a }
                        }
                    }
                }

                if (consent == null || consent == -1) {
                    logW("ENTER_CONSENT received invalid consent=$consent, ignoring")
                    return
                }

                if (scaleIndex == null) {
                    scaleIndex = findKnownScaleIndexForAppUser(appUserId)
                    logD("Resolved scaleIndex=$scaleIndex for appUserId=$appUserId from known mapping")
                }

                if (scaleIndex == null) {
                    logW("Could not determine scaleIndex for appUserId=$appUserId, suggesting user creation")
                    userInfo(R.string.bt_info_suggest_create_new_user)
                    return
                }

                logD("Saving consent=$consent for scaleIndex=$scaleIndex and sending to scale")
                saveConsentForScaleIndex(scaleIndex, consent)
                sendConsent(scaleIndex, consent)
            }

            else -> logW("Unhandled UserInteractionType=$interactionType for appUserId=$appUserId")
        }
    }

    // ---- UDS command builders -------------------------------------------------

    private fun sendRegisterNewUser(consentCode: Int) {
        val payload = byteArrayOf(
            UDS_CP_REGISTER_NEW_USER.toByte(),
            (consentCode and 0xFF).toByte(),
            ((consentCode shr 8) and 0xFF).toByte()
        )
        logD("→ UCP REGISTER_NEW_USER consent=$consentCode")
        writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, payload)
    }

    private fun sendConsent(scaleIndex: Int, consentCode: Int) {
        val payload = byteArrayOf(
            UDS_CP_CONSENT.toByte(),
            (scaleIndex and 0xFF).toByte(),
            (consentCode and 0xFF).toByte(),
            ((consentCode shr 8) and 0xFF).toByte()
        )
        logD("→ UCP CONSENT idx=$scaleIndex consent=$consentCode")
        writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, payload)
    }

    protected open fun requestScaleUserConsent(appUserId: Int, scaleIndex: Int) {
        pendingAppUserId = appUserId
        requestUserInteraction(
            UserInteractionType.ENTER_CONSENT,
            intArrayOf(scaleIndex, /* UI should return the consent here */ 0)
        )
    }

    /** Find a previously persisted mapping (scaleIndex → this appUserId). */
    protected fun findKnownScaleIndexForAppUser(appUserId: Int): Int? {
        for (idx in 0..255) {
            if (loadUserIdForScaleIndex(idx) == appUserId) return idx
        }
        return null
    }

    // ---- Helpers --------------------------------------------------------------

    private fun randomConsent(): Int = Random.nextInt(0, 10_000)

    private fun buildCurrentTimePayload(now: Date = Date()): ByteArray {
        val cal = Calendar.getInstance().apply { time = now }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val dayOfWeek = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // 1=Mon…7=Sun

        return byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            month.toByte(),
            day.toByte(),
            hour.toByte(),
            minute.toByte(),
            second.toByte(),
            dayOfWeek.toByte(),
            0x00, // Fractions256
            0x00  // AdjustReason
        )
    }

    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.toHex(): String {
        if (isEmpty()) return "[]"
        val show = min(size, 64)
        val sb = StringBuilder("[")
        for (i in 0 until show) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", this[i]))
        }
        if (size > show) sb.append(" …(+").append(size - show).append("b)")
        sb.append(']')
        return sb.toString()
    }
}
