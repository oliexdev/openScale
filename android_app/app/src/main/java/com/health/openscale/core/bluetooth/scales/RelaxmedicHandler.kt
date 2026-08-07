/*
 * openScale
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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.Wla25BodyComposition
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs

/**
 * Relaxmedic body-composition scale (Fitdays app, icomon protocol version 107).
 *
 * Shares service 0xFFB0 and the 20-byte frame family with [RobiS9Handler] and
 * [RunstarR6Handler], but differs from both in two ways that matter:
 *
 *  - **Frames fragment.** Byte 2 is a fragment index, not a constant zero. The
 *    0xA3 result spans two frames and every fragment contributes `bytes[3..18]`
 *    to the reassembled message — so the byte after the fragment index is
 *    payload, not a header byte to skip.
 *  - **The profile is generated, not replayed.** [RobiS9Handler] replays a
 *    captured handshake because its timestamp and token could not be
 *    regenerated. The 0xBA profile this scale wants is fully understood, so it
 *    is built here from the openScale user.
 *
 * The scale gates its own display on receiving a valid 0xBA profile, and
 * **flags1 carries sex and age** — the only place they are sent. Getting that
 * byte wrong is silent: the scale accepts the frame and computes for the wrong
 * person.
 *
 * Body composition is computed locally by [Wla25BodyComposition]; the scale
 * transmits weight and impedances but never its own derived values.
 *
 * Frame layout, both directions:
 * ```
 * [seq][len][fragment][payload…][checksum]
 * ```
 * `len` is the reassembled payload length including the command byte, and the
 * trailing byte is `sum(bytes[3..18]) & 0x1F`. Writes with a wrong checksum are
 * dropped silently.
 */
class RelaxmedicHandler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write  (profile, acks)
    private val CHAR_LIVE: UUID = uuid16(0xFFB2)    // notify (live A2 weight)
    private val CHAR_RESULT: UUID = uuid16(0xFFB3)  // indicate (A1 info, A3 result, A0)

    private var outgoingSeq = 0
    private var lastPreviewWeightKg = -1f
    private var lastPublishedGrams: Int? = null

    /** Reassembly state for the current multi-fragment message. */
    private var stream = ByteArray(0)
    private var expectedLength = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Other 0xFFB0 families claim by their own names; never take theirs.
        if (name.startsWith("swan") || name == "icomon" || name == "yg") return null
        if (!name.startsWith("relaxmedic")) return null

        return DeviceSupport(
            displayName = "Relaxmedic",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.USER_SYNC
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.USER_SYNC
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        outgoingSeq = 0
        lastPreviewWeightKg = -1f
        lastPublishedGrams = null
        stream = ByteArray(0)
        expectedLength = 0

        setNotifyOn(SERVICE, CHAR_LIVE)
        setNotifyOn(SERVICE, CHAR_RESULT)

        sendUserProfile(user)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.size != FRAME_SIZE) return
        if (!isChecksumValid(data)) {
            logD("Relaxmedic checksum mismatch, dropping frame")
            return
        }

        val message = reassemble(data) ?: return
        val command = message[0].toInt() and 0xFF
        val payload = message.copyOfRange(1, message.size)

        when (command) {
            TYPE_LIVE_WEIGHT -> handleLiveWeight(payload)
            TYPE_FINAL_RESULT -> handleFinalResult(payload, data[0].toInt() and 0xFF, user)
            TYPE_DEVICE_INFO -> logD("Relaxmedic device info, ${payload.size} byte payload")
            TYPE_ACK_IN -> sendAck(data[0].toInt() and 0xFF)
            else -> logD("Relaxmedic unhandled command 0x${"%02X".format(command)}")
        }
    }

    /**
     * Accumulate fragments until a whole message is present.
     *
     * Every fragment contributes `bytes[3..18]`; on fragment 0 the first of
     * those is the command byte, so a complete message is `len + 1` bytes.
     */
    private fun reassemble(frame: ByteArray): ByteArray? {
        val length = frame[1].toInt() and 0xFF
        val fragment = frame[2].toInt() and 0xFF
        val chunk = frame.copyOfRange(3, 19)

        if (fragment == 0) {
            stream = chunk
            expectedLength = length
        } else {
            if (stream.isEmpty()) return null      // continuation without a start
            stream += chunk
        }

        if (stream.size < expectedLength + 1) return null
        val message = stream.copyOfRange(0, expectedLength + 1)
        stream = ByteArray(0)
        expectedLength = 0
        return message
    }

    /**
     * 0xA2 live weight. Payload: `[state][packed u32 BE][heart rate][mode]`.
     * Never published — only the 0xA3 result is authoritative.
     */
    private fun handleLiveWeight(payload: ByteArray) {
        if (payload.size < 5) return
        val weightKg = (u32be(payload, 1) and WEIGHT_MASK) / 1000.0f
        if (abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            lastPreviewWeightKg = weightKg
        }
    }

    /**
     * 0xA3 settled reading.
     *
     * Payload: a big-endian `u32` whose low 18 bits are grams, then two unused
     * bytes, then the impedances as big-endian `u16` from offset 5, each in
     * tenths of an ohm. They run to the end of the payload — ten of them on this
     * scale, in two groups of five, each group beginning with a small value.
     *
     * The scale repeats this frame several times; only the first is published.
     */
    private fun handleFinalResult(payload: ByteArray, seq: Int, user: ScaleUser) {
        if (payload.size < 5) return

        val grams = u32be(payload, 0) and WEIGHT_MASK
        if (lastPublishedGrams == grams) {
            sendAck(seq)
            return
        }

        val imps = DoubleArray((payload.size - 5) / 2) { i ->
            u16be(payload, 5 + 2 * i) / 10.0
        }

        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            weight = grams / 1000.0f
            if (imps.isNotEmpty()) impedance = imps[0]
        }

        val result = Wla25BodyComposition.compute(
            heightCm = user.bodyHeight.toInt(),
            rawWeightKg = measurement.weight.toDouble(),
            imps = imps
        )

        if (result != null) {
            measurement.apply {
                weight = result.weightKg
                fat = result.fat
                water = result.water
                muscle = result.musclePercent
                bone = result.boneKg
                visceralFat = result.visceralFat.toFloat()
                protein = result.protein
                bmr = result.bmrKcal.toFloat()
                lbm = result.lbmKg
            }
        } else {
            // The impedances failed the algorithm's validity gate; the weight is
            // still good, so publish that rather than nothing.
            logW("Relaxmedic impedances rejected (${imps.size} values), weight only")
        }

        publish(measurement)
        lastPublishedGrams = grams
        sendAck(seq)
    }

    /**
     * 0xBA user profile.
     *
     * ```
     * u8  0xBA
     * u32 unix time, big-endian
     * u16 UTC offset in minutes; bit 0x8000 marks a negative offset
     * u32 user id
     * u8  height in cm
     * u16 profile weight * 100
     * u8  flags1   = (sex shl 7) or (age and 0x7F)
     * u8  flags2   feature bits
     * u8  trailing
     * ```
     *
     * `flags2` and `trailing` are the values the vendor app sends and the scale
     * accepts. Their bit assignments are only partly understood, so they are
     * kept verbatim rather than derived.
     */
    private fun sendUserProfile(user: ScaleUser) {
        val heightCm = user.bodyHeight.toInt()
        val age = user.age
        val sexBit = if (user.gender.isMale()) 1 else 0
        val flags1 = (sexBit shl 7) or (age and 0x7F)

        val nowSeconds = System.currentTimeMillis() / 1000L
        val offsetMinutes = TimeZone.getDefault()
            .getOffset(System.currentTimeMillis()) / 60000
        val encodedOffset = (abs(offsetMinutes) and 0x7FFF)
            .let { if (offsetMinutes < 0) it or 0x8000 else it }

        // The profile's stored weight, not a live reading; the scale only needs
        // it to be plausible.
        val profileWeight = (user.initialWeight.takeIf { it > 0f } ?: 60.0f)
        val weightHundredths = (profileWeight * 100.0f).toInt()

        val payload = ByteArray(15)
        payload[0] = TYPE_USER_PROFILE.toByte()
        putU32be(payload, 1, nowSeconds)
        putU16be(payload, 5, encodedOffset)
        putU32be(payload, 7, 0L)                     // user id
        payload[11] = (heightCm and 0xFF).toByte()
        putU16be(payload, 12, weightHundredths)
        payload[14] = (flags1 and 0xFF).toByte()

        // flags2 and trailing continue past the 15 bytes carried here; the frame
        // builder zero-pads, so append them explicitly.
        val full = payload + byteArrayOf(FLAGS2.toByte(), TRAILING.toByte())
        writeTo(SERVICE, CHAR_WRITE, buildFrame(full), withResponse = true)
        logD("Relaxmedic profile sent: ${heightCm}cm, age $age, sex $sexBit")
    }

    /** 0xB0 — acknowledge a packet the scale sent. */
    private fun sendAck(seq: Int) {
        writeTo(
            SERVICE, CHAR_WRITE,
            buildFrame(byteArrayOf(TYPE_ACK_OUT.toByte(), (seq and 0xFF).toByte(), 0x00)),
            withResponse = true
        )
    }

    /**
     * Wrap a message (command byte first) in a 20-byte frame.
     *
     * `len` counts the payload after the command byte. Only single-fragment
     * writes are needed — everything this handler sends fits.
     */
    private fun buildFrame(message: ByteArray): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = (outgoingSeq and 0xFF).toByte()
        outgoingSeq = (outgoingSeq + 1) and 0xFF
        frame[1] = ((message.size - 1) and 0xFF).toByte()
        frame[2] = 0x00
        // A frame carries 16 payload bytes. The 0xBA profile is one byte longer,
        // and the vendor app simply lets the trailing byte fall off -- the
        // captured frame the scale accepts ends `... 95 2f 04`, with `len` still
        // counting the untruncated length. Reproduced rather than corrected,
        // because this is the form known to work on hardware.
        val carried = minOf(message.size, FRAME_SIZE - 4)
        message.copyInto(frame, 3, 0, carried)
        frame[19] = computeChecksum(frame).toByte()
        return frame
    }

    /** `sum(bytes[3..18]) & 0x1F`. Writes failing it are dropped silently. */
    private fun computeChecksum(frame: ByteArray): Int {
        var sum = 0
        for (i in 3..18) sum += frame[i].toInt() and 0xFF
        return sum and 0x1F
    }

    private fun isChecksumValid(frame: ByteArray): Boolean =
        (frame[19].toInt() and 0xFF) == computeChecksum(frame)

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun putU16be(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU32be(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    companion object {
        private const val FRAME_SIZE = 20

        private const val TYPE_ACK_IN = 0xA0
        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_ACK_OUT = 0xB0
        private const val TYPE_USER_PROFILE = 0xBA

        /** The packed weight word carries grams in its low 18 bits. */
        private const val WEIGHT_MASK = 0x3FFFF

        /** Feature bits and trailer, verbatim from a capture the scale accepted. */
        private const val FLAGS2 = 0x2F
        private const val TRAILING = 0x0F
    }
}
