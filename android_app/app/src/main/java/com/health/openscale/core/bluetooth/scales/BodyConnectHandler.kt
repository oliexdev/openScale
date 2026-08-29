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
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils

import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.UUID
import kotlin.random.Random

/**
 * BodyConnectHandler
 * ------------------
 * Modern Kotlin handler for the **1BODY CONNECT** and X-LINE smart scales (Transtek family).
 *
 * Protocol highlights (per BTSnoop analysis):
 * - GATT Service:      0x7892
 * - Weight:     0x8A24  (0x1F frames — weight records)
 * - Body Comp: 0x8A22  (0x7F frames — body composition)
 * - Download (host→dev): 0x8A81  (commands)
 * - Upload   (dev→host): 0x8A82  (notifications)
 *
 * Device→host opcodes:
 * - 0xA0 = Password        (32-bit, unknown; persisted per device)
 * - 0xA1 = Challenge       (always 0x11111111; host XORs with password and replies)
 * - 0x83 = Slot Status     (8 user slots, each with a 16-char name)
 * - 0xC0 = Profile Echo    (confirms user profile after time set)
 *
 * Host→device opcodes:
 * - 0x02 = Set Time        (UTC timestamp as seconds since 2010-01-01)
 * - 0x03 = Add User        (Register a user name into a slot)
 * - 0x20 = Challenge Resp  (challenge XOR password)
 * - 0x21 = Broadcast ID    (sent during pairing)
 * - 0x22 = Enable Disconnect
 * - 0x51 = User Profile    (gender, age, height)
 *
 * @see TrisaBodyAnalyzeHandler similar challenge-response protocol
 */
class BodyConnectHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        val prefixes = listOf("1BODY CONNECT", "0BODY CONNECT", "0X-LINE", "1X-LINE")
        if (!prefixes.any { name.startsWith(it) }) return null
        val displayName = if (name.contains("BODY CONNECT")) "1BODY CONNECT" else "1X-LINE"
        return DeviceSupport(
            displayName = displayName,
            capabilities = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC, DeviceCapability.USER_SYNC, DeviceCapability.HISTORY_READ),
            implemented = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC, DeviceCapability.USER_SYNC, DeviceCapability.HISTORY_READ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- UUIDs (Bluetooth Base UUID, 16-bit short codes) -----------------------

    private val SVC = uuid16(0x7892)
    private val CHR_WEIGHT = uuid16(0x8A24) // 0x1F weight frames
    private val CHR_BODY  = uuid16(0x8A22) // 0x7F body comp frames
    private val CHR_DNLD = uuid16(0x8A81) // host → device
    private val CHR_UPLD = uuid16(0x8A82) // device → host

    // --- Upload (device → host) opcodes ----------------------------------------

    private val CMD_PASSWORD: Byte     = 0xA0.toByte()
    private val CMD_CHALLENGE: Byte    = 0xA1.toByte()
    private val CMD_SLOT_STATUS: Byte  = 0x83.toByte()
    private val CMD_PROFILE_ECHO: Byte = 0xC0.toByte()

    // --- Download (host → device) opcodes --------------------------------------

    private val CMD_ADD_USER: Byte           = 0x03
    private val CMD_TIME: Byte               = 0x02
    private val CMD_CHALLENGE_RESPONSE: Byte = 0x20
    private val CMD_BROADCAST: Byte          = 0x21
    private val CMD_ENABLE_DISCONNECT: Byte  = 0x22

    private val OP_WRITE_PROFILE: Byte = 0x51
    private val OP_BODY: Byte          = 0x7F
    private val OP_WEIGHT: Byte        = 0x1F

    // Non-zero broadcast ID required for pairing to succeed; generated randomly per device instance
    private val BROADCAST_ID = Random.nextInt(Int.MAX_VALUE - 1) + 1

    // Timestamp base: 2010-01-01 00:00:00 UTC; device stores seconds since this epoch
    private val TS_OFFSET = 1262304000L
    // The X-LINE returns a timestamp in seconds since 2020-01-01 only in the body composition response
    private val ALT_TS_OFFSET = 315619200

    private val KEY_PASSWORD = "bodyconnect/password"
    private val KEY_SLOT = "bodyconnect/slot"

    // --- Pairing state ---------------------------------------------------------

    private var pairing = false
    private var password: Int? = null
    private var slotAcked = false

    // Slots the scale reported as empty, collected across the whole 0x83 stream.
    private val freeSlots = mutableSetOf<Int>()

    // Slot number to the name stored on the scale, collected across the whole 0x83 stream.
    private val slotNames = mutableMapOf<Int, String>()

    // Non-null while a slot-choice dialog is open, holding the user the answer applies to.
    private var pendingUser: ScaleUser? = null

    private val LAST_SLOT = 8
    private val DEFAULT_SLOT = 1
    private val NAME_LENGTH = 16
    private val SPACE: Byte = 0x20

    // --- Frame matching --------------------------------------------------------
    // 0x1F and 0x7F frames share a device timestamp; we cache the weight from
    // 0x1F and match it when 0x7F arrives with the same timestamp.

    private var lastTS: Int? = null
    private var lastWeight: Float? = null

    // --- Lifecycle -------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(SVC, CHR_WEIGHT)
        setNotifyOn(SVC, CHR_BODY)
        setNotifyOn(SVC, CHR_UPLD)
        logD("Connected to device")

        // Restore password persisted from a previous pairing session
        password = settingsGetInt(KEY_PASSWORD, -1).takeIf { it != -1 }
        pairing = false
        slotAcked = false
        lastTS = null
        lastWeight = null
        pendingUser = null
        freeSlots.clear()
        slotNames.clear()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_UPLD     -> onUpload(data)
            CHR_WEIGHT   -> onWeight(data)
            CHR_BODY     -> onBody(data, user)
            else         -> logW("Unknown characteristic: $characteristic")
        }
    }

    override suspend fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any
    ) {
        if (interactionType != UserInteractionType.CHOOSE_USER) return
        val slot = (feedbackData as? Int)?.takeIf { it in 1..LAST_SLOT } ?: run {
            logW("Ignoring CHOOSE_USER feedback $feedbackData")
            return
        }
        val user = pendingUser ?: currentAppUser()
        pendingUser = null
        logD("User chose slot $slot")
        registerIntoSlot(slot, user)
    }


    // --- Upload (device → host) processing -------------------------------------

    private fun onUpload(data: ByteArray) {
        if (data.isEmpty()) return
        logD("onUpload (${data.size} bytes)")
        val command = data[0]
        when (command) {
            CMD_PASSWORD     -> onPassword(data)
            CMD_CHALLENGE    -> onChallenge(data)
            CMD_PROFILE_ECHO -> onProfileEcho()
            CMD_SLOT_STATUS  -> onSlotStatus(data)
        }
    }

    private fun onPassword(data: ByteArray) {
        if (data.size < 5) {
            logE("Password data too short (${data.size} bytes)")
            return
        }
        val pw = ConverterUtils.fromSignedInt32Le(data, 1)
        password = pw
        settingsPutInt(KEY_PASSWORD, pw)
        logD("Password received")

        userInfo(R.string.bluetooth_scale_trisa_success_pairing)
        pairing = true
        // Broadcast ID must be set before the scale accepts further commands
        logD("Sending broadcast ID")
        writeCommand(CMD_BROADCAST, BROADCAST_ID)
    }

    private fun onChallenge(data: ByteArray) {
        if (data.size < 5) {
            logW("Challenge data too short: ${data.size} bytes")
            return
        }
        val pw = password ?: run {
            logW("No password available for challenge response")
            userWarn(R.string.bluetooth_scale_trisa_message_not_paired_instruction)
            requestDisconnect()
            return
        }
        val challenge = ConverterUtils.fromSignedInt32Le(data, 1)
        writeCommand(CMD_CHALLENGE_RESPONSE, challenge xor pw)

        if (!pairing) {
            logD("Already paired: send profile + time directly (scale skips slot negotiation)")
            writeProfile(settingsGetInt(KEY_SLOT, DEFAULT_SLOT), currentAppUser())
            writeCommand(CMD_TIME, javaTimeToDevice(System.currentTimeMillis()))
        }
    }

    private fun onProfileEcho() {
        // Scale confirms the user profile; we signal that setup is complete.
        writeCommand(CMD_ENABLE_DISCONNECT)
    }

    private fun onSlotStatus(data: ByteArray) {
        // Scale lists its 8 user slots
        if (data.size < 18) {
            logW("Slot status data too short: ${data.size} bytes")
            return
        }
        val slot = data[1].toInt() and 0xFF

        val occupied = (2 until data.size).any { i ->
            val b = data[i].toInt() and 0xFF
            b != 0x00 && b != 0x20
        }
        if (!occupied) freeSlots.add(slot)
        slotNames[slot] = parseSlotName(data)
        logD("Slot $slot ${if (occupied) "occupied" else "free"}")

        if (slot != LAST_SLOT || slotAcked) return
        slotAcked = true
        val user = currentAppUser()

        // A slot chosen in an earlier session is reused without asking again.
        val stored = settingsGetInt(KEY_SLOT, -1)
        if (stored in 1..LAST_SLOT) {
            logD("Reusing stored slot $stored")
            registerIntoSlot(stored, user)
            return
        }

        // First pairing: ask which scale user this is. Guessing is wrong too often. A brand new
        // scale has eight empty slots and nothing to match on, and a second-hand one may be full
        // of a previous owner's profiles that the user legitimately wants to take over.
        pendingUser = user
        logD("Presenting slot choice dialog")
        presentSlotChoice(user)
    }

    // --- Frame parsing ---------------------------------------------------------

    private fun onWeight(data: ByteArray) {
        if (data.size < 20) {
            logW("Weight data too short: ${data.size} bytes")
            return
        }
        if (data[0] != OP_WEIGHT.toByte()) {
            logW("Weight frame has wrong opcode")
            return
        }

        // 0x1F frame layout:
        //   off 0:       opcode 0x1F
        //   off 1-2:     weight (LE uint16, /100 = kg)
        //   off 5-8:     device timestamp (LE int32)
        lastWeight = ConverterUtils.fromUnsignedInt16Le(data, 1) / 100f
        lastTS = ConverterUtils.fromSignedInt32Le(data, 5)
    }

    private fun onBody(data: ByteArray, user: ScaleUser) {
        if (data.size < 20) {
            logW("Body data too short: ${data.size} bytes")
            return
        }
        if (data[0] != OP_BODY.toByte()) {
            logW("Body frame has wrong opcode")
            return
        }

        // 0x7F frame layout:
        //   off 0:       opcode 0x7F
        //   off 1-4:     device timestamp (LE int32, matches paired 0x1F)
        //   off 5:       (0x01 ?)
        //   off 8-9:     fat %     (if hi nibble == 0xF)
        //   off 10-11:   water %   (if hi nibble == 0xF)
        //   off 14-15:   muscle %  (if hi nibble == 0xF)
        //   off 16-17:   bone %    (if hi nibble == 0xF)

        val fat = parseBodyComp(data, 8)
        val water = parseBodyComp(data, 10)
        val muscle = parseBodyComp(data, 14)
        val bone = parseBodyComp(data, 16)

        if (fat == null && water == null && muscle == null && bone == null) {
            logW("No valid body composition data in frame")
            return
        }

        val ts = ConverterUtils.fromSignedInt32Le(data, 1)
        if (lastTS == null || (lastTS != ts && lastTS != ts + ALT_TS_OFFSET)) {
            logW("Timestamps don't match: $ts; $lastTS")
            return
        }

        val weight = lastWeight
        if (weight == null || weight <= 0f) {
            logW("No valid weight available for body composition")
            return
        }

        val m = ScaleMeasurement().apply {
            dateTime = Date(deviceTimeToJava(lastTS!!))
            this.weight = weight
        }
        fat?.let { m.fat = it }
        water?.let { m.water = it }
        muscle?.let { m.muscle = it }
        bone?.let { m.bone = it }
        publish(m)
    }

    // --- Download (host → device) helpers --------------------------------------

    private fun writeProfile(slot: Int, user: ScaleUser) {
        logD("Writing user profile in slot $slot")
        val b = ByteArray(14) { 0x00.toByte() }
        b[0] = 0xDF.toByte()
        b[1] = slot.toByte()
        b[2] = getGenderByte(user)
        b[3] = user.age.toByte()
        b[4] = user.bodyHeight.toInt().toByte()
        b[5] = 0xE0.toByte()
        b[6] = getActivityByte(user)
        ConverterUtils.toInt16Le(b, 8, (user.initialWeight * 100).toInt())
        b[11] = 0xFE.toByte()
        writeCommand(OP_WRITE_PROFILE, b)
    }

    private fun getGenderByte(user: ScaleUser): Byte {
        if (user.gender.isMale()) {
            return if (user.activityLevel <= ActivityLevel.MODERATE) 0x01 else 0x03
        } else {
            return if (user.activityLevel <= ActivityLevel.MODERATE) 0x02 else 0x04
        }
    }

    private fun getActivityByte(user: ScaleUser): Byte {
        return when (user.activityLevel) {
            ActivityLevel.SEDENTARY -> 0x00
            ActivityLevel.MILD -> 0x00
            ActivityLevel.MODERATE -> 0x00
            ActivityLevel.HEAVY -> 0x01
            ActivityLevel.EXTREME -> 0x02
        }
    }

    private fun parseBodyComp(data: ByteArray, off: Int): Float? {
        // Two bytes: hi nibble of second byte must be 0xF to indicate valid data
        if (off + 1 >= data.size) return null
        val lo = data[off].toInt() and 0xFF
        val hi = data[off + 1].toInt() and 0xFF
        return if (hi and 0xF0 == 0xF0) ((hi and 0x0F) shl 8 or lo) / 10f else null
    }

    private fun parseSlotName(frame: ByteArray): String {
        if (frame.size <= 2) return ""
        return frame.copyOfRange(2, minOf(frame.size, 2 + NAME_LENGTH))
            .map { (it.toInt() and 0xFF).toChar() }
            .filter { it.code in 0x20..0x7E }
            .joinToString("")
            .trim()
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
        logD("Asking which slot to claim (suggesting $suggested)")
        requestUserInteraction(UserInteractionType.CHOOSE_USER, Pair(labels.toTypedArray(), ids))
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
    private fun chooseSlot(
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
     * Claim [slot] for this openScale user and close out setup. Writing the profile here is what
     * corrects a slot still holding a stale height/age, which is what the scale computes body
     * composition from.
     */
    private fun registerIntoSlot(slot: Int, user: ScaleUser) {
        settingsPutInt(KEY_SLOT, slot)
        logD("Registering user into slot $slot")
        writeAddUser(slot, user)
        writeProfile(slot, user)
        writeCommand(CMD_TIME, javaTimeToDevice(System.currentTimeMillis()))
        writeCommand(CMD_ENABLE_DISCONNECT)
        logD("User registration complete for slot $slot")
    }

    /**
     * 0x03 registers a user in a scale-side slot: `[opcode][slot][16-byte name][0000]`. The name is
     * truncated to 16 characters and right-padded with spaces. It mirrors the shape of the
     * 0x83 frame that prompts it, but this is a registration, not an acknowledgement.
     */
    private fun writeAddUser(slot: Int, user: ScaleUser) {
        val name = ByteArray(NAME_LENGTH) { SPACE }
        // The slot list is plain ASCII; anything else is replaced.
        user.userName.take(NAME_LENGTH).replace("[^\\x00-\\x7F]".toRegex(), "-").toByteArray(StandardCharsets.ISO_8859_1).copyInto(name)
        writeCommand(CMD_ADD_USER, byteArrayOf(slot.toByte()) + name)
    }

    private fun writeCommand(opcode: Byte) {
        writeTo(SVC, CHR_DNLD, byteArrayOf(opcode), withResponse = true)
    }

    private fun writeCommand(opcode: Byte, payload: ByteArray) {
        writeTo(SVC, CHR_DNLD, byteArrayOf(opcode) + payload, withResponse = true)
    }

    private fun writeCommand(opcode: Byte, arg: Int) {
        val b = ByteArray(5).also {
            it[0] = opcode
            ConverterUtils.toInt32Le(it, 1, arg.toLong())
        }
        writeTo(SVC, CHR_DNLD, b, withResponse = true)
    }

    // --- Timestamp conversion --------------------------------------------------

    internal fun javaTimeToDevice(ms: Long): Int {
        return (((ms + 500) / 1000) - TS_OFFSET).toInt()
    }

    internal fun deviceTimeToJava(s: Int): Long {
        return 1000L * (TS_OFFSET + s.toLong())
    }
}
