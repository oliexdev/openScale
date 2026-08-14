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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

/**
 * Weight Gurus Bluetooth Smart Scale 0376 (Greater Goods / DMD Brands, Transtek A3).
 *
 * Service 0d005750-c36b-11e3-9c1a-0800200c9a66:
 * - 0x8A20 feature (read)
 * - 0x8A22 body composition, 16-bit SFLOATs (indicate)
 * - 0x8A24 weight, 32-bit FLOAT (indicate)
 * - 0x8A81 commands, host to device (write)
 * - 0x8A82 events, device to host (indicate)
 *
 * Events: 0xA0 password, 0xA1 random, 0x83 slot status, 0xC0 profile echo.
 * Commands: 0x02 time, 0x03 add user, 0x20 verification, 0x21 account id,
 * 0x22 enable disconnect, 0x51 profile.
 *
 * Pairing: 0xA0 -> 0x21; 0xA1 -> 0x20; then eight 0x83 frames, and only after the last
 * -> 0x03, 0x51, 0x02, 0x22. Established session: 0xA1 -> 0x20, 0x02.
 *
 * Body composition is computed on the scale from the profile stored in the slot a reading is
 * attributed to. Slot selection is a button on the scale and cannot be set by the host, so a
 * slot holding a stale height or age yields wrong percentages for a correct weight; writing
 * 0x51 to that slot corrects it.
 *
 * Protocol reference: `docs/misc/weight_gurus_a3_protocol.md`.
 */
class WeightGurusA3Handler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The scale renames itself between sessions: "10376B" while it is waiting to be
        // paired, "00376B<serial>" once it is. Both carry the Transtek model code 0376B.
        if (!NAME_PATTERN.matches(device.name)) return null
        return DeviceSupport(
            displayName = "Weight Gurus Smart Scale 0376",
            capabilities = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.BATTERY_LEVEL
            ),
            implemented = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
                // The scale queues weigh-ins taken while disconnected and hands the whole
                // backlog over on the next connection; those are stored like live readings.
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- UUIDs ----------------------------------------------------------------

    private val SVC: UUID = UUID.fromString("0d005750-c36b-11e3-9c1a-0800200c9a66")
    private val CHR_WEIGHT = uuid16(0x8A24) // device → host, weight
    private val CHR_APPEND = uuid16(0x8A22) // device → host, body composition
    private val CHR_DNLD   = uuid16(0x8A81) // host → device, commands
    private val CHR_UPLD   = uuid16(0x8A82) // device → host, events

    // Device Information Service, read on connect before the pairing frames arrive.
    private val DIS = uuid16(0x180A)
    private val DIS_CHARS = listOf(0x2A23, 0x2A24, 0x2A25, 0x2A26, 0x2A27, 0x2A28, 0x2A29)
        .map { uuid16(it) }

    // --- Upload (device → host) opcodes ---------------------------------------

    private val EVT_PASSWORD: Byte     = 0xA0.toByte()
    private val EVT_RANDOM: Byte       = 0xA1.toByte()
    private val EVT_SLOT_STATUS: Byte  = 0x83.toByte()
    private val EVT_PROFILE_ECHO: Byte = 0xC0.toByte()

    // --- Download (host → device) opcodes -------------------------------------

    private val CMD_TIME: Byte              = 0x02
    private val CMD_ADD_USER: Byte          = 0x03
    private val CMD_VERIFICATION: Byte      = 0x20
    private val CMD_ACCOUNT_ID: Byte        = 0x21
    private val CMD_ENABLE_DISCONNECT: Byte = 0x22
    private val CMD_PROFILE: Byte           = 0x51

    /** Field mask the vendor app sends with the 0x51 profile command. */
    private val PROFILE_MASK: Byte = 0x17

    /** Device timestamps are seconds since 2010-01-01 00:00:00 (0x4B3D3B00). */
    private val TS_OFFSET = 1262304000L

    // --- Session state --------------------------------------------------------

    private var password: ByteArray? = null

    /**
     * A 0x8A24 frame whose status byte announces a follow-up composition frame is held
     * here until the matching 0x8A22 arrives, so both land in a single measurement.
     */
    private var pendingWeight: ScaleMeasurement? = null
    private var pendingTimestamp: Int? = null

    /** Guards against the same record arriving twice within a single session. */
    private val publishedAtMs = mutableSetOf<Long>()

    private var slotAcked = false
    private var setupFinished = false
    private var profileSent = false

    /** Slots the scale reported as empty, collected across the whole 0x83 stream. */
    private val freeSlots = mutableSetOf<Int>()

    /** Slot number to the name stored on the scale, collected across the whole 0x83 stream. */
    private val slotNames = mutableMapOf<Int, String>()

    /** Non-null while a slot-choice dialog is open, holding the user the answer applies to. */
    private var pendingUser: ScaleUser? = null

    /** True once the scale has handed over a password and the link is being dropped to commit it. */
    private var pairing = false

    // --- Lifecycle ------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(SVC, CHR_UPLD)
        setNotifyOn(SVC, CHR_WEIGHT)
        setNotifyOn(SVC, CHR_APPEND)

        // Some firmware in this family will not finalise pairing until these have been read.
        DIS_CHARS.forEach { readFrom(DIS, it) }

        password = settingsGetString(KEY_PASSWORD)?.let(::hexToBytes)
        pendingWeight = null
        pendingTimestamp = null
        publishedAtMs.clear()
        slotAcked = false
        setupFinished = false
        profileSent = false
        pairing = false
        freeSlots.clear()
        slotNames.clear()
        pendingUser = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_UPLD   -> onEvent(data, user)
            CHR_WEIGHT -> onWeight(data)
            CHR_APPEND -> onAppend(data)
            // The Device Information reads issued in onConnected come back through here too.
            in DIS_CHARS -> logD("Device info ${characteristic.shortId()}: ${data.toAsciiPreview()}")
            else       -> logW("Unknown characteristic: $characteristic")
        }
    }

    override fun onDisconnected() {
        // A weight frame that never got its promised composition frame is still worth keeping.
        flushPendingWeight()
    }

    // --- Upload (device → host) processing -------------------------------------

    private fun onEvent(data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return
        when (data[0]) {
            EVT_PASSWORD     -> onPassword(data)
            EVT_RANDOM       -> onRandom(data, user)
            EVT_SLOT_STATUS  -> onSlotStatus(data, user)
            EVT_PROFILE_ECHO -> onProfileEcho()
            else             -> logW("Unknown event opcode: ${data.toHexPreview(4)}")
        }
    }

    /**
     * Pairing: the scale hands out a password exactly once, while it advertises as "1…".
     * It has to be kept; without it no later session can pass the challenge.
     */
    private fun onPassword(data: ByteArray) {
        if (data.size < 5) {
            logW("Password frame too short: ${data.size}b")
            return
        }
        val pw = data.copyOfRange(1, 5)
        password = pw
        settingsPutString(KEY_PASSWORD, bytesToHex(pw))
        userInfo(R.string.bluetooth_scale_trisa_success_pairing)

        // Claiming an account ID is what completes pairing on the scale's side.
        pairing = true
        writeCommand(CMD_ACCOUNT_ID, randomAccountId())

        // Registration, profile, clock and the closing 0x22 are all issued from the slot handler
        // after the eighth 0x83 frame. No timer may race that: 0x22 sent before the stream ends
        // aborts setup with E1.
        //
        // Nor may the link be dropped here. The scale closes it once the pairing is committed to
        // flash; cutting it first interrupts the commit and is also reported as E1. Force a
        // disconnect only if the scale never closes.
        scope.launch {
            delay(PAIRING_COMMIT_FALLBACK_MS)
            if (pendingUser != null) {
                logD("Still waiting for a slot to be chosen; leaving the link alone")
                return@launch
            }
            logW("Scale did not close the link after setup; forcing disconnect")
            requestDisconnect()
        }
    }

    private fun onRandom(data: ByteArray, user: ScaleUser) {
        if (data.size < 5) {
            logW("Random frame too short: ${data.size}b")
            return
        }
        val pw = password ?: run {
            userWarn(R.string.bluetooth_scale_trisa_message_not_paired_instruction)
            requestDisconnect()
            return
        }
        val random = data.copyOfRange(1, 5)

        // Answered in every session, pairing included; the scale waits for it before committing.
        writeCommand(CMD_VERIFICATION, xor(pw, random))

        // During pairing both are issued from the slot handler instead. Resending the profile
        // here carries a changed height or age to the scale without re-pairing.
        if (!pairing) {
            writeCommand(CMD_TIME, ConverterUtils.toInt32Le(javaTimeToDevice(System.currentTimeMillis()).toLong()))
            writeProfile(settingsGetInt(KEY_SLOT, DEFAULT_SLOT), user)
        }
    }

    /**
     * The scale streams all eight of its user slots as `[0x83][slot][18-byte name]` frames.
     *
     * Registration is deferred until the eighth frame. Replying mid-stream, for instance on the
     * first occupied slot, aborts setup with E1.
     */
    private fun onSlotStatus(data: ByteArray, user: ScaleUser) {
        if (data.size < 2) return
        val slot = data[1].toInt() and 0xFF

        // Slot names are space padded; anything else means the slot is taken.
        val occupied = (2 until data.size).any { i ->
            val b = data[i].toInt() and 0xFF
            b != 0x00 && b != 0x20
        }
        if (!occupied) freeSlots.add(slot)
        slotNames[slot] = decodeSlotName(data)
        logD("Slot $slot ${if (occupied) "occupied" else "free"} '${slotNames[slot]}': ${data.toHexPreview(20)}")

        if (slot != LAST_SLOT || slotAcked) return
        slotAcked = true

        // A slot chosen in an earlier session is reused without asking again.
        val stored = settingsGetInt(KEY_SLOT, -1)
        if (stored in 1..LAST_SLOT) {
            logD("Reusing stored slot $stored (free: ${freeSlots.sorted()}, names: $slotNames)")
            registerIntoSlot(stored, user)
            return
        }

        // First pairing: ask which scale user this is. Guessing is wrong too often. A brand new
        // scale has eight empty slots and nothing to match on, and a second-hand one may be full
        // of a previous owner's profiles that the user legitimately wants to take over.
        pendingUser = user
        presentSlotChoice(user)
    }

    /**
     * Ask the user which of the scale's eight slots is theirs, listing the names the scale just
     * streamed so the choice is made against what is actually on the device. A slot whose name
     * matches the openScale user is flagged as the suggestion.
     *
     * If the link drops before the answer arrives the writes fail harmlessly. The choice is still
     * persisted, so the next connection takes the stored-slot path above.
     */
    private fun presentSlotChoice(user: ScaleUser) {
        val suggested = chooseSlot(-1, user.userName, slotNames, freeSlots)
        val labels = ArrayList<CharSequence>(LAST_SLOT)
        val ids = IntArray(LAST_SLOT)
        for (slot in 1..LAST_SLOT) {
            val stored = slotNames[slot].orEmpty()
            val name = stored.ifEmpty { resolveString(R.string.bluetooth_scale_weightgurus_slot_empty) }
            labels += resolveString(
                if (slot == suggested && stored.isNotEmpty()) {
                    R.string.bluetooth_scale_weightgurus_slot_match
                } else {
                    R.string.bluetooth_scale_weightgurus_slot
                },
                slot, name
            )
            ids[slot - 1] = slot
        }
        logD("Asking which slot to claim (suggesting $suggested); names: $slotNames")
        requestUserInteraction(UserInteractionType.CHOOSE_USER, Pair(labels.toTypedArray(), ids))
    }

    override suspend fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    ) {
        if (interactionType != UserInteractionType.CHOOSE_USER) return
        val slot = (feedbackData as? Int)?.takeIf { it in 1..LAST_SLOT } ?: run {
            logW("Ignoring CHOOSE_USER feedback: $feedbackData")
            return
        }
        val user = pendingUser ?: currentAppUser()
        pendingUser = null
        logD("User chose slot $slot")
        registerIntoSlot(slot, user)
    }

    /**
     * Claim [slot] for this openScale user and close out setup. Writing the profile here is what
     * corrects a slot still holding a stale height/age, which is what the scale computes body
     * composition from.
     */
    private fun registerIntoSlot(slot: Int, user: ScaleUser) {
        settingsPutInt(KEY_SLOT, slot)
        writeAddUser(slot, user)
        writeProfile(slot, user)
        writeCommand(CMD_TIME, ConverterUtils.toInt32Le(javaTimeToDevice(System.currentTimeMillis()).toLong()))
        finishSetup()
    }

    private fun onProfileEcho() {
        logD("Profile echo - the scale accepted the user profile")
        // Only the pairing flow ends with 0x22; sending it mid-session would cut the sync short.
        if (pairing) finishSetup()
    }

    /** Tells the scale the host is done configuring it; sent once per session. */
    private fun finishSetup() {
        if (setupFinished) return
        setupFinished = true
        writeTo(SVC, CHR_DNLD, byteArrayOf(CMD_ENABLE_DISCONNECT), withResponse = true)
    }

    // --- Measurement frames ----------------------------------------------------

    /**
     * 0x8A24 - weight measurement.
     *
     * ```
     * off 0     flags
     * off 1-4   weight, 32-bit FLOAT, in kg
     * then, in flag order:
     *   0x01    timestamp        (uint32)
     *   0x02    weight delta     (32-bit FLOAT, ignored)
     *   0x04    impedance        (32-bit FLOAT)
     *   0x08    user id          (uint8, ignored; openScale tracks its own users)
     *   0x10    status           (uint8; bit4 announces a follow-up 0x8A22 frame)
     * ```
     */
    private fun onWeight(data: ByteArray) {
        if (data.size < 5) return
        // A new weight frame supersedes anything still waiting for a partner.
        flushPendingWeight()

        val flags = data[0].toInt() and 0xFF
        val weight = floatFrom32(data, 1)
        if (weight <= 0f) {
            logW("Ignoring non-positive weight: $weight")
            return
        }

        var off = 5
        var timestamp: Int? = null
        if (flags and 0x01 != 0) {
            if (off + 4 > data.size) return
            timestamp = ConverterUtils.fromSignedInt32Le(data, off)
            off += 4
        }
        if (flags and 0x02 != 0) off += 4 // weight difference, not tracked by openScale
        var impedance: Float? = null
        if (flags and 0x04 != 0) {
            if (off + 4 > data.size) return
            impedance = floatFrom32(data, off)
            off += 4
        }
        if (flags and 0x08 != 0) off += 1 // scale-side user slot
        var hasAppendFrame = false
        if (flags and 0x10 != 0 && off < data.size) {
            val status = data[off].toInt() and 0xFF
            hasAppendFrame = (status and 0x10) != 0
            logD("Status: stable=${status and 0x01}, state=${(status and 0x0E) shr 1}, append=$hasAppendFrame")
        }

        val measurement = ScaleMeasurement().apply {
            dateTime = Date(timestamp?.let(::deviceTimeToJava) ?: System.currentTimeMillis())
            this.weight = weight
            impedance?.let { this.impedance = it.toDouble() }
        }

        logD("Weight frame → ${weight}kg at ${measurement.dateTime}, impedance=$impedance, append=$hasAppendFrame")

        if (hasAppendFrame && timestamp != null) {
            // Body composition follows in its own frame; publish once both are in hand.
            pendingWeight = measurement
            pendingTimestamp = timestamp
        } else {
            publishIfNew(measurement)
        }
    }

    /**
     * 0x8A22 - append measurement (body composition).
     *
     * ```
     * off 0     flags
     * off 1-4   timestamp (uint32, matches the paired 0x8A24 frame)
     * then, in flag order, each a 16-bit SFLOAT unless noted:
     *   0x01    user id            (uint8)
     *   0x02    basal metabolism   (uint16, kcal)
     *   0x04    body fat %
     *   0x08    body water %
     *   0x10    visceral fat level
     *   0x20    muscle mass %
     *   0x40    bone mass %  (converted to kg on the way into ScaleMeasurement)
     *   0x80    battery            (uint8)
     * ```
     */
    private fun onAppend(data: ByteArray) {
        if (data.size < 5) return
        val measurement = pendingWeight ?: run {
            logW("Composition frame without a matching weight frame - dropping")
            return
        }

        val flags = data[0].toInt() and 0xFF
        val timestamp = ConverterUtils.fromSignedInt32Le(data, 1)
        if (timestamp != pendingTimestamp) {
            logW("Composition timestamp $timestamp does not match weight frame $pendingTimestamp")
            flushPendingWeight()
            return
        }

        var off = 5
        if (flags and 0x01 != 0) off += 1 // scale-side user slot
        if (flags and 0x02 != 0) {
            if (off + 2 > data.size) return
            measurement.bmr = ConverterUtils.fromUnsignedInt16Le(data, off).toFloat()
            off += 2
        }
        if (flags and 0x04 != 0) {
            if (off + 2 > data.size) return
            measurement.fat = sfloatFrom16(data, off)
            off += 2
        }
        if (flags and 0x08 != 0) {
            if (off + 2 > data.size) return
            measurement.water = sfloatFrom16(data, off)
            off += 2
        }
        if (flags and 0x10 != 0) {
            if (off + 2 > data.size) return
            measurement.visceralFat = sfloatFrom16(data, off)
            off += 2
        }
        if (flags and 0x20 != 0) {
            if (off + 2 > data.size) return
            measurement.muscle = sfloatFrom16(data, off)
            off += 2
        }
        if (flags and 0x40 != 0) {
            if (off + 2 > data.size) return
            // The scale sends bone as a percentage, but openScale stores it as a mass in kg.
            measurement.bone = bonePercentToKg(sfloatFrom16(data, off), measurement.weight)
            off += 2
        }

        pendingWeight = null
        pendingTimestamp = null
        publishIfNew(measurement)
    }

    private fun flushPendingWeight() {
        pendingWeight?.let {
            logD("Publishing weight frame without its composition partner")
            publishIfNew(it)
        }
        pendingWeight = null
        pendingTimestamp = null
    }

    /**
     * Publish unless this exact record has already been seen in this session.
     *
     * Deliberately does *not* filter against the newest stored measurement. The scale keeps a
     * separate queue per user slot and drains only the selected slot's queue, newest first, so
     * records legitimately arrive older than ones already stored: a reading taken on one slot is
     * delivered after a newer reading from another. A high-water-mark filter drops those, and
     * because the scale clears a record once it has been delivered, dropping one loses it for
     * good. Cross-session duplicates are handled by the unique `(userId, timestamp)` index on
     * `Measurement`, whose insert ignores conflicts.
     */
    private fun publishIfNew(measurement: ScaleMeasurement) {
        val at = measurement.dateTime?.time ?: 0
        if (at != 0L && !publishedAtMs.add(at)) {
            logD("Skipping duplicate record from ${measurement.dateTime} in this session")
            return
        }
        logD(
            "Decoded ${measurement.dateTime}: weight=${measurement.weight}kg fat=${measurement.fat}% " +
                "water=${measurement.water}% muscle=${measurement.muscle}% bone=${measurement.bone}kg " +
                "visceral=${measurement.visceralFat} bmr=${measurement.bmr}kcal"
        )
        publish(measurement)
    }

    // --- Download (host → device) helpers --------------------------------------

    private fun writeCommand(opcode: Byte, payload: ByteArray) {
        writeTo(SVC, CHR_DNLD, byteArrayOf(opcode) + payload, withResponse = true)
    }

    /**
     * 0x03 registers a user in a scale-side slot: `[opcode][slot][18-byte name]`. The name is
     * truncated to 18 characters and right-padded with spaces. It mirrors the shape of the
     * 0x83 frame that prompts it, but this is a registration, not an acknowledgement.
     */
    private fun writeAddUser(slot: Int, user: ScaleUser) {
        val name = ByteArray(NAME_LENGTH) { SPACE }
        user.userName.take(NAME_LENGTH).forEachIndexed { i, c ->
            // The slot list is plain ASCII; anything else is replaced.
            name[i] = if (c.code in 0x20..0x7E) c.code.toByte() else SPACE
        }
        writeCommand(CMD_ADD_USER, byteArrayOf(slot.toByte()) + name)
    }

    private fun writeProfile(slot: Int, user: ScaleUser) {
        // Both the challenge reply and the registration want the profile sent; only the first
        // one should actually write it.
        if (profileSent) return
        profileSent = true

        val height = encodeHeightCm(user.bodyHeight.toInt())
        val payload = byteArrayOf(
            PROFILE_MASK,
            slot.toByte(),
            if (user.gender.isMale()) 0x01 else 0x02,    // 3/4 select the athlete variants
            user.age.toByte(),
            (height and 0xFF).toByte(),
            ((height shr 8) and 0xFF).toByte(),
            0x00                                         // weight unit: kg (pounds 1, stones 2)
        )
        writeCommand(CMD_PROFILE, payload)
    }

    /** The 16-bit short form of a Bluetooth base UUID, for logging. */
    private fun UUID.shortId(): String = String.format("0x%04x", (mostSignificantBits shr 32) and 0xFFFF)

    /** Pairing only completes for a non-zero account id; the value itself is arbitrary. */
    private fun randomAccountId(): ByteArray =
        ConverterUtils.toInt32Le((Random.nextInt(Int.MAX_VALUE - 1) + 1).toLong())

    // --- Encoding helpers ------------------------------------------------------

    companion object {
        private val NAME_PATTERN = Regex("^[01]0376B.*")
        private const val KEY_PASSWORD = "weightgurus/password"
        private const val KEY_SLOT = "weightgurus/slot"

        /** The scale reports eight user slots, numbered from one. */
        private const val LAST_SLOT = 8
        private const val DEFAULT_SLOT = 1

        /** Slot names are a fixed 18 bytes, right-padded with spaces. */
        private const val NAME_LENGTH = 18
        private const val SPACE: Byte = 0x20

        /** SFLOAT exponent nibble for 10^-3, used to send the height in metres. */
        private const val SFLOAT_EXP_MILLI = 0xD000

        /**
         * The SFLOAT mantissa is a *signed* 12-bit value, so at exponent -3 the largest height
         * it can carry is 2047 x 10^-3 m = 204.7 cm; 205 cm would set the sign bit and decode as
         * a negative height. Anything taller is capped here so it cannot wrap.
         */
        private const val MAX_HEIGHT_CM = 204

        /**
         * How long to wait for the scale to close the link on its own after setup before forcing
         * a disconnect. The scale normally drops us within a second or two once it has committed;
         * this backstop only fires if it never does, so it is deliberately generous.
         */
        private const val PAIRING_COMMIT_FALLBACK_MS = 15_000L

        /** The 18-byte, space-padded ASCII name that follows the slot number in an 0x83 frame. */
        fun decodeSlotName(frame: ByteArray): String {
            if (frame.size <= 2) return ""
            return frame.copyOfRange(2, minOf(frame.size, 2 + NAME_LENGTH))
                .map { (it.toInt() and 0xFF).toChar() }
                .filter { it.code in 0x20..0x7E }
                .joinToString("")
                .trim()
        }

        /**
         * Suggest which slot to register into.
         *
         * This is only a suggestion. The slot is chosen by the user, who is shown the scale's
         * actual roster (see `presentSlotChoice`). It is used to flag one entry in that list, and
         * as the answer on later connections once a choice has been stored.
         *
         * Re-pairing should land on the slot it used last time. Taking a fresh slot each time
         * leaves another copy of the same person on the scale, and measurements get attributed
         * to whichever duplicate the user happens to step on.
         *
         * The stored slot number is the first choice, but it does not survive the app's data
         * being cleared, so a slot already carrying this user's name is matched next. Only then
         * does it take the lowest free slot, falling back to the first slot if the scale is full.
         */
        fun chooseSlot(
            storedSlot: Int,
            userName: String,
            slotNames: Map<Int, String>,
            freeSlots: Set<Int>
        ): Int {
            if (storedSlot in 1..LAST_SLOT) return storedSlot

            val wanted = userName.take(NAME_LENGTH).trim()
            if (wanted.isNotEmpty()) {
                slotNames.entries
                    .filter { it.value.equals(wanted, ignoreCase = true) }
                    .minByOrNull { it.key }
                    ?.let { return it.key }
            }
            return freeSlots.minOrNull() ?: DEFAULT_SLOT
        }

        /**
         * The 0x51 profile wants the height in metres, encoded as an IEEE-11073 SFLOAT, not the
         * raw centimetre value: exponent -3 (nibble 0xD) with the centimetre value scaled by ten as
         * the mantissa, so 170 cm goes out as 1700 x 10^-3 = 0xD6A4.
         */
        fun encodeHeightCm(cm: Int): Int =
            (cm.coerceIn(1, MAX_HEIGHT_CM) * 10) or SFLOAT_EXP_MILLI

        /**
         * IEEE-11073 32-bit FLOAT: 24-bit little-endian mantissa scaled by a signed
         * base-10 exponent in the top byte.
         */
        fun floatFrom32(data: ByteArray, offset: Int): Float {
            if (offset + 4 > data.size) return 0f
            val mantissa = ConverterUtils.fromUnsignedInt24Le(data, offset)
            val exponent = data[offset + 3].toInt() // signed
            return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
        }

        /**
         * IEEE-11073 16-bit SFLOAT: 12-bit two's-complement mantissa scaled by a 4-bit
         * two's-complement base-10 exponent.
         */
        fun sfloatFrom16(data: ByteArray, offset: Int): Float {
            if (offset + 2 > data.size) return 0f
            val raw = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)

            var exponent = (raw and 0xF000) shr 12
            if (exponent >= 0x8) exponent -= 0x10

            // The sign bit is part of the mantissa: a set bit means the low 11 bits carry a
            // two's-complement negative, so 0x800 means -2048, not 0.
            var mantissa = raw and 0x0FFF
            if (mantissa >= 0x0800) mantissa = (raw and 0x07FF) - 0x0800

            return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
        }

        /**
         * Bone arrives as a percentage of body weight; `bone` is a mass in kg. Muscle needs no
         * such conversion, being stored as a percentage.
         */
        fun bonePercentToKg(percent: Float, weightKg: Float): Float =
            if (weightKg <= 0f) 0f else percent * weightKg / 100f

        fun xor(a: ByteArray, b: ByteArray): ByteArray =
            ByteArray(minOf(a.size, b.size)) { (a[it].toInt() xor b[it].toInt()).toByte() }

        fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return runCatching {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { String.format("%02x", it) }
    }

    // --- Timestamp conversion --------------------------------------------------
    // The scale stores local time, so shift by the current UTC offset in both directions.

    private fun timeZoneOffsetSeconds(now: Long): Int =
        TimeZone.getDefault().getOffset(now) / 1000

    private fun javaTimeToDevice(ms: Long): Int =
        (((ms + 500) / 1000) - TS_OFFSET + timeZoneOffsetSeconds(ms)).toInt()

    private fun deviceTimeToJava(seconds: Int): Long {
        val approx = 1000L * (TS_OFFSET + seconds.toLong())
        return approx - 1000L * timeZoneOffsetSeconds(approx)
    }
}
