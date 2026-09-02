/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.OmronBodyCompositionLib
import com.health.openscale.core.bluetooth.libs.OmronWlpFrame
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Percent

/**
 * Handler for Omron body composition monitors that speak the WLC ("Wellness Link") transfer
 * protocol — the HBF-702T family and the closely related HBF-222T/227T/228T/230T.
 *
 * These scales do not stream live weights. They store up to 30 measurements per on-device user slot
 * in EEPROM, and the phone reads them out afterwards over a vendor GATT service:
 *
 *  1. unlock the device with a 16-byte key (programming a fresh one if we have none yet, which
 *     requires the scale to be in pairing mode),
 *  2. open a transfer session,
 *  3. read the record ring buffer of the slot bound to the current openScale user,
 *  4. close the session.
 *
 * Nothing is ever written to the device beyond the unlock handshake, so a misread cannot corrupt
 * stored measurements. "New" records are the ones stamped later than the newest measurement
 * openScale already holds for this user.
 */
class OmronWlcHandler : ScaleDeviceHandler() {

    companion object {
        val SVC_OMRON_WLP: UUID = UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b")
        val CHR_UNLOCK: UUID = UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b")

        /** Command channels; a command is written across them in 16-byte pieces. */
        val CHR_TX: List<UUID> = listOf(
            UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
            UUID.fromString("e0b8a060-aee7-11e1-92f4-0002a5d5c51b"),
            UUID.fromString("0ae12b00-aee8-11e1-a192-0002a5d5c51b"),
            UUID.fromString("10e1ba60-aee8-11e1-89e5-0002a5d5c51b")
        )

        /** Response channels, notified in the same order the command channels are written. */
        val CHR_RX: List<UUID> = listOf(
            UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b"),
            UUID.fromString("4d0bf320-aee8-11e1-a0d9-0002a5d5c51b"),
            UUID.fromString("5128ce60-aee8-11e1-b84b-0002a5d5c51b"),
            UUID.fromString("560f1420-aee8-11e1-8184-0002a5d5c51b")
        )

        /**
         * Settings block holding, for each user slot, the ring buffer write pointer (bytes 0-3) and
         * the number of records not yet transferred (bytes 4-7).
         */
        private const val ADDR_INDEX_BLOCK = 0x01A0
        private const val INDEX_BLOCK_SIZE = 8

        /** The pointer occupies the low six bits; the top two are status flags. */
        private const val POINTER_MASK = 0x3F

        /** Give up if the scale stops answering; it is a battery device and may simply walk away. */
        private const val RESPONSE_TIMEOUT_MS = 8_000L

        private const val KEY_SETTING_PREFIX = "pairingKey_"
        private const val SLOT_SETTING_PREFIX = "userSlot_"

        /**
         * Omron devices advertise as `BLEsmart_<group><model><mac>`, where the two 16-bit hex
         * fields are exactly the device group and model ids the OMRON connect app keys its device
         * table on. Scales live in group 1.
         */
        private const val LOCAL_NAME_PREFIX = "blesmart_"

        private data class KnownModel(
            val displayName: String,
            val profile: OmronBodyCompositionLib.Profile
        )

        private val MODELS_BY_ADVERTISED_ID: Map<Int, KnownModel> = mapOf(
            0x0001_000C to KnownModel("Omron HBF-702T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            0x0001_0011 to KnownModel("Omron KRD-703T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            0x0001_040C to KnownModel("Omron HBF-702T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            0x0001_0009 to KnownModel("Omron HBF-227T", OmronBodyCompositionLib.PROFILE_HBF_32),
            0x0001_000B to KnownModel("Omron HBF-228T", OmronBodyCompositionLib.PROFILE_HBF_32),
            0x0001_000D to KnownModel("Omron HBF-230T", OmronBodyCompositionLib.PROFILE_HBF_32),
            0x0001_0408 to KnownModel("Omron HBF-222T", OmronBodyCompositionLib.PROFILE_HBF_32),
            0x0001_0110 to KnownModel(
                "Omron BCM-500", OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE
            ),
            0x0001_0208 to KnownModel(
                "Omron VIVA", OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE
            )
        )

        /**
         * Once bonded, Android reports the GAP device name rather than the advertised local name,
         * so the model has to be recognisable from that too.
         */
        private val MODELS_BY_DEVICE_NAME: Map<String, KnownModel> = mapOf(
            "hbf-702t" to KnownModel("Omron HBF-702T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            "krd-703t" to KnownModel("Omron KRD-703T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            "hbf-227t" to KnownModel("Omron HBF-227T", OmronBodyCompositionLib.PROFILE_HBF_32),
            "hbf-228t" to KnownModel("Omron HBF-228T", OmronBodyCompositionLib.PROFILE_HBF_32),
            "hbf-230t" to KnownModel("Omron HBF-230T", OmronBodyCompositionLib.PROFILE_HBF_32),
            "hbf-222t" to KnownModel("Omron HBF-222T", OmronBodyCompositionLib.PROFILE_HBF_32),
            "bcm-500" to KnownModel(
                "Omron BCM-500", OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE
            ),
            "viva" to KnownModel(
                "Omron VIVA", OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE
            )
        )

        /**
         * Resolves a scanned name to a supported model, or `null` if it is not one of ours.
         * Exposed for tests.
         */
        internal fun modelFor(deviceName: String): Pair<String, OmronBodyCompositionLib.Profile>? {
            val name = deviceName.trim().lowercase(Locale.US)

            if (name.startsWith(LOCAL_NAME_PREFIX)) {
                val ids = name.removePrefix(LOCAL_NAME_PREFIX)
                if (ids.length >= 8) {
                    val advertisedId = ids.substring(0, 8).toIntOrNull(16)
                    MODELS_BY_ADVERTISED_ID[advertisedId]?.let { return it.displayName to it.profile }
                }
                return null
            }

            return MODELS_BY_DEVICE_NAME[name]?.let { it.displayName to it.profile }
        }
    }

    /** Where the read-out has got to. Every step is driven by the device's previous answer. */
    private enum class Phase {
        IDLE,
        UNLOCKING,
        ENTERING_KEY_PROGRAMMING,
        WRITING_KEY,
        STARTING_SESSION,
        PROBING_INDEX,
        PROBING_SLOT,
        READING_SLOT,
        AWAITING_SLOT_CHOICE,
        ENDING_SESSION,
        FINISHED
    }

    private var phase = Phase.IDLE
    private var profile: OmronBodyCompositionLib.Profile = OmronBodyCompositionLib.PROFILE_HBF_702T

    /** Partially received response, one entry per notify channel. */
    private val rxChannels = arrayOfNulls<ByteArray>(CHR_RX.size)

    // Chunked EEPROM read in progress.
    private var readAddress = 0
    private var readLength = 0
    private var readCursor = 0
    private val readBuffer = ArrayList<Byte>()
    private var onReadComplete: ((ByteArray) -> Unit)? = null

    // Slot probing state, used only when the current app user is not bound to a slot yet.
    private val slotPreviews = arrayOfNulls<OmronBodyCompositionLib.Record>(4)
    private var probeSlot = 0
    private var probePointers = IntArray(0)

    private var boundSlot = -1
    private var pairingKey: ByteArray? = null
    private var watchdog: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val (displayName, _) = modelFor(device.name) ?: return null

        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ
        )

        return DeviceSupport(
            displayName = displayName,
            capabilities = capabilities,
            implemented = capabilities,
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetSession()

        // The record layout differs per model, and guessing it would silently produce nonsense
        // measurements, so an unrecognisable name aborts the session instead.
        val peripheralName = getPeripheral()?.name.orEmpty()
        val resolved = modelFor(peripheralName)
        if (resolved == null) {
            logE("cannot resolve an Omron record layout for '$peripheralName'")
            userError(R.string.bt_error_omron_unknown_model, peripheralName)
            requestDisconnect()
            return
        }
        profile = resolved.second

        if (!hasCharacteristic(SVC_OMRON_WLP, CHR_UNLOCK)) {
            userError(R.string.bt_error_omron_service_missing)
            requestDisconnect()
            return
        }

        // Subscribing is also what prompts the scale to start BLE bonding, so it has to happen
        // before the first unlock attempt.
        setNotifyOn(SVC_OMRON_WLP, CHR_UNLOCK)
        CHR_RX.forEach { setNotifyOn(SVC_OMRON_WLP, it) }

        boundSlot = settingsGetInt(slotSettingKey(user.id), -1)
        pairingKey = loadPairingKey()

        val key = pairingKey
        if (key == null) {
            userInfo(R.string.bt_info_omron_pairing_mode_required)
            phase = Phase.ENTERING_KEY_PROGRAMMING
            writeUnlockChannel(OmronWlpFrame.enterKeyProgramming())
        } else {
            phase = Phase.UNLOCKING
            writeUnlockChannel(OmronWlpFrame.unlock(key))
        }
    }

    override fun onDisconnected() {
        watchdog?.cancel()
        watchdog = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        armWatchdog()

        if (characteristic == CHR_UNLOCK) {
            handleUnlockResponse(data, user)
            return
        }

        val channel = CHR_RX.indexOf(characteristic)
        if (channel < 0) return

        rxChannels[channel] = data
        val frame = assembleFrame() ?: return

        val response = OmronWlpFrame.parseResponse(frame)
        if (response == null) {
            logW("discarding corrupt response ${frame.toHexPreview(16)}")
            return
        }
        handleResponse(response, user)
    }

    // ---- unlock / pairing ----------------------------------------------------------------------

    private fun handleUnlockResponse(data: ByteArray, user: ScaleUser) {
        if (data.size < 2) return
        val ack = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

        when (phase) {
            Phase.UNLOCKING -> if (ack == OmronWlpFrame.ACK_UNLOCKED) {
                openSession()
            } else {
                // The stored key no longer matches — most likely the scale was re-paired with the
                // vendor app. Ask for pairing mode and program a fresh key.
                logW("unlock rejected (ack=0x${ack.toString(16)}), re-pairing")
                userInfo(R.string.bt_info_omron_pairing_mode_required)
                pairingKey = null
                phase = Phase.ENTERING_KEY_PROGRAMMING
                writeUnlockChannel(OmronWlpFrame.enterKeyProgramming())
            }

            Phase.ENTERING_KEY_PROGRAMMING -> if (ack == OmronWlpFrame.ACK_KEY_PROGRAMMING) {
                val key = ByteArray(OmronWlpFrame.KEY_SIZE).also { SecureRandom().nextBytes(it) }
                pairingKey = key
                phase = Phase.WRITING_KEY
                writeUnlockChannel(OmronWlpFrame.writeKey(key))
            } else {
                failSession(R.string.bt_error_omron_pairing_mode_required)
            }

            Phase.WRITING_KEY -> if (ack == OmronWlpFrame.ACK_KEY_WRITTEN) {
                pairingKey?.let { storePairingKey(it) }
                userInfo(R.string.bt_info_omron_paired)
                openSession()
            } else {
                failSession(R.string.bt_error_omron_pairing_failed)
            }

            else -> logD("ignoring unlock-channel data in phase $phase")
        }
    }

    private fun openSession() {
        phase = Phase.STARTING_SESSION
        sendCommand(OmronWlpFrame.startTransmission())
    }

    // ---- transfer session ----------------------------------------------------------------------

    private fun handleResponse(response: OmronWlpFrame.Response, user: ScaleUser) {
        when (phase) {
            Phase.STARTING_SESSION -> {
                if (response.type != OmronWlpFrame.TYPE_START) {
                    failSession(R.string.bt_error_omron_session_failed)
                    return
                }
                if (boundSlot in 0 until profile.userSlotCount) {
                    startSlotReadout(boundSlot)
                } else {
                    phase = Phase.PROBING_INDEX
                    readEeprom(ADDR_INDEX_BLOCK, INDEX_BLOCK_SIZE) { block ->
                        probePointers = decodeSlotPointers(block)
                        probeSlot = 0
                        probeNextSlot(user)
                    }
                }
            }

            Phase.PROBING_INDEX, Phase.PROBING_SLOT, Phase.READING_SLOT -> {
                if (response.type != OmronWlpFrame.TYPE_READ) {
                    failSession(R.string.bt_error_omron_read_failed)
                    return
                }
                consumeReadChunk(response.data)
            }

            Phase.ENDING_SESSION -> {
                if (response.result != 0) {
                    logW("device reported result ${response.result} on session end")
                }
                phase = Phase.FINISHED
                watchdog?.cancel()
                requestDisconnect()
            }

            else -> logD("ignoring response 0x${response.type.toString(16)} in phase $phase")
        }
    }

    /**
     * The write pointer names the *next* slot to be filled, so the newest record sits one place
     * behind it. Values outside the ring buffer mean the block could not be interpreted.
     */
    private fun decodeSlotPointers(block: ByteArray): IntArray {
        if (block.size < profile.userSlotCount) return IntArray(0)
        return IntArray(profile.userSlotCount) { slot ->
            val pointer = block[slot].toInt() and POINTER_MASK
            if (pointer > profile.recordsPerSlot) {
                -1
            } else {
                (pointer - 1 + profile.recordsPerSlot) % profile.recordsPerSlot
            }
        }
    }

    private fun probeNextSlot(user: ScaleUser) {
        if (probeSlot >= profile.userSlotCount) {
            presentSlotChoice()
            return
        }

        val slot = probeSlot
        val newest = probePointers.getOrElse(slot) { -1 }
        if (newest < 0) {
            probeSlot++
            probeNextSlot(user)
            return
        }

        phase = Phase.PROBING_SLOT
        readEeprom(profile.recordAddress(slot, newest), profile.recordSize) { record ->
            slotPreviews[slot] = OmronBodyCompositionLib.decodeRecord(record, profile)
            probeSlot++
            probeNextSlot(user)
        }
    }

    private fun presentSlotChoice() {
        phase = Phase.AWAITING_SLOT_CHOICE

        val labels = ArrayList<String>(profile.userSlotCount)
        val indices = IntArray(profile.userSlotCount) { it }
        for (slot in 0 until profile.userSlotCount) {
            val preview = slotPreviews[slot]
            labels += if (preview != null) {
                resolveString(
                    R.string.bt_omron_slot_with_measurement,
                    slot + 1,
                    preview.weightKg,
                    DateFormat.getDateInstance(DateFormat.SHORT).format(preview.timestamp)
                )
            } else {
                resolveString(R.string.bt_omron_slot_empty, slot + 1)
            }
        }

        requestUserInteraction(
            UserInteractionType.CHOOSE_USER,
            Pair(labels.toTypedArray(), indices)
        )
    }

    override suspend fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    ) {
        if (interactionType != UserInteractionType.CHOOSE_USER) return
        if (phase != Phase.AWAITING_SLOT_CHOICE) return

        val slot = feedbackData as? Int
        if (slot == null || slot !in 0 until profile.userSlotCount) {
            logW("CHOOSE_USER feedback is not a valid slot: $feedbackData")
            return
        }

        settingsPutInt(slotSettingKey(appUserId), slot)
        boundSlot = slot
        userInfo(R.string.bt_info_linked_app_user_to_slot, appUserId, slot + 1)
        startSlotReadout(slot)
    }

    private fun startSlotReadout(slot: Int) {
        phase = Phase.READING_SLOT
        val length = profile.recordSize * profile.recordsPerSlot
        readEeprom(profile.recordAddress(slot, 0), length) { records ->
            publishRecords(records)
            closeSession()
        }
    }

    private fun closeSession() {
        phase = Phase.ENDING_SESSION
        sendCommand(OmronWlpFrame.endTransmission())
    }

    // ---- record publication --------------------------------------------------------------------

    private fun publishRecords(raw: ByteArray) {
        val user = currentAppUser()
        val knownUpTo = lastMeasurementFor(user.id)?.dateTime

        val decoded = ArrayList<OmronBodyCompositionLib.Record>()
        var offset = 0
        while (offset + profile.recordSize <= raw.size) {
            val record = raw.copyOfRange(offset, offset + profile.recordSize)
            OmronBodyCompositionLib.decodeRecord(record, profile)?.let { decoded += it }
            offset += profile.recordSize
        }

        val fresh = decoded
            .filter { knownUpTo == null || it.timestamp.after(knownUpTo) }
            .sortedBy { it.timestamp }

        logI("decoded ${decoded.size} records, ${fresh.size} newer than ${knownUpTo ?: "(none)"}")

        fresh.forEach { publish(it.toMeasurement(user.id)) }

        if (fresh.isEmpty()) {
            userInfo(R.string.bt_info_omron_no_new_measurements)
        } else {
            userInfo(R.string.bt_info_omron_measurements_read, fresh.size)
        }
    }

    private fun OmronBodyCompositionLib.Record.toMeasurement(userId: Int) = ScaleMeasurement(
        userId = userId,
        dateTime = timestamp,
    ).also { m ->
        m[MeasurementType.WEIGHT] = Kg(weightKg)
        m[MeasurementType.BODY_FAT] = Percent(bodyFatPercent ?: 0f)
        m[MeasurementType.MUSCLE] = Percent(skeletalMusclePercent ?: 0f)
        m[MeasurementType.VISCERAL_FAT] = visceralFatLevel ?: 0f
        m[MeasurementType.BMR] = Kcal(bmrKcal?.toFloat() ?: 0f)
    }

    // ---- chunked EEPROM reads ------------------------------------------------------------------

    private fun readEeprom(address: Int, length: Int, onComplete: (ByteArray) -> Unit) {
        readAddress = address
        readLength = length
        readCursor = 0
        readBuffer.clear()
        readBuffer.ensureCapacity(length)
        onReadComplete = onComplete
        requestNextChunk()
    }

    private fun requestNextChunk() {
        if (readCursor >= readLength) {
            val callback = onReadComplete
            onReadComplete = null
            callback?.invoke(readBuffer.toByteArray())
            return
        }
        val chunk = min(OmronWlpFrame.MAX_READ_BLOCK, readLength - readCursor)
        sendCommand(OmronWlpFrame.readEeprom(readAddress + readCursor, chunk))
    }

    private fun consumeReadChunk(data: ByteArray) {
        val expected = min(OmronWlpFrame.MAX_READ_BLOCK, readLength - readCursor)
        if (expected <= 0) return

        val accepted = min(expected, data.size)
        for (i in 0 until accepted) readBuffer += data[i]
        // A short answer would desynchronise every following address; pad it out as erased memory.
        repeat(expected - accepted) { readBuffer += 0xFF.toByte() }

        readCursor += expected
        requestNextChunk()
    }

    // ---- transport helpers ---------------------------------------------------------------------

    private fun sendCommand(command: ByteArray) {
        armWatchdog()
        OmronWlpFrame.toChannelChunks(command).forEachIndexed { index, chunk ->
            if (index < CHR_TX.size) writeTo(SVC_OMRON_WLP, CHR_TX[index], chunk)
        }
    }

    private fun writeUnlockChannel(payload: ByteArray) {
        armWatchdog()
        writeTo(SVC_OMRON_WLP, CHR_UNLOCK, payload)
    }

    /** Reassembles a response once every channel it spans has arrived. */
    private fun assembleFrame(): ByteArray? {
        val first = rxChannels[0] ?: return null
        if (first.isEmpty()) return null

        val frameLength = first[0].toInt() and 0xFF
        val needed = OmronWlpFrame.channelsForFrame(frameLength)
        if (needed > rxChannels.size) {
            rxChannels.fill(null)
            return null
        }
        for (i in 0 until needed) if (rxChannels[i] == null) return null

        var frame = ByteArray(0)
        for (i in 0 until needed) frame += rxChannels[i]!!
        rxChannels.fill(null)

        return if (frame.size >= frameLength) frame.copyOf(frameLength) else null
    }

    // ---- session bookkeeping -------------------------------------------------------------------

    private fun resetSession() {
        phase = Phase.IDLE
        rxChannels.fill(null)
        readBuffer.clear()
        onReadComplete = null
        slotPreviews.fill(null)
        probePointers = IntArray(0)
        probeSlot = 0
    }

    /**
     * Restarted on every byte from the scale. If the conversation stalls — a lost notification, or
     * the scale simply powering down — the session is abandoned instead of hanging the connection.
     */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (phase != Phase.FINISHED && phase != Phase.AWAITING_SLOT_CHOICE) {
                logW("no response for ${RESPONSE_TIMEOUT_MS}ms in phase $phase, aborting")
                userWarn(R.string.bt_error_omron_timeout)
                requestDisconnect()
            }
        }
    }

    private fun failSession(resId: Int) {
        userError(resId)
        phase = Phase.FINISHED
        watchdog?.cancel()
        requestDisconnect()
    }

    // ---- persisted per-device state ------------------------------------------------------------

    private fun deviceKey(): String = getPeripheral()?.address ?: "unknown"

    private fun slotSettingKey(appUserId: Int) = "$SLOT_SETTING_PREFIX${deviceKey()}_$appUserId"

    private fun loadPairingKey(): ByteArray? {
        val hex = settingsGetString(KEY_SETTING_PREFIX + deviceKey()) ?: return null
        if (hex.length != OmronWlpFrame.KEY_SIZE * 2) return null
        return runCatching {
            ByteArray(OmronWlpFrame.KEY_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private fun storePairingKey(key: ByteArray) {
        val hex = key.joinToString("") { String.format(Locale.US, "%02x", it) }
        settingsPutString(KEY_SETTING_PREFIX + deviceKey(), hex)
    }
}
