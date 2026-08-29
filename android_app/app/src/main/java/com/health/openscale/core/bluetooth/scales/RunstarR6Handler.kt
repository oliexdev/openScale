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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Runstar R6 smart scale.
 *
 * Same FFB0 frame family as [RobiS9Handler] — 20-byte frames
 * `[seq][len][00][type][payload…][chk]`, trailer checksum `sum(bytes[3..18]) & 0x1F`
 * (verified across 38 frames, zero mismatches):
 *  - Service 0xFFB0
 *  - 0xFFB1 write     — app -> scale
 *  - 0xFFB2 notify    — live weight stream (0xA2)
 *  - 0xFFB3 indicate  — device info (0xA1), ack (0xA0), offline history (0xA4), final
 *                        result (0xA3)
 *
 * The scale queues weigh-ins taken while disconnected and replays them as 0xA4 on the
 * next connect, before the live 0xA3. Acking (0xB0) retires the entry on the scale —
 * unacked entries keep reappearing on later connects, so every 0xA4 must be acked. 0xA4
 * only carries a timestamp + weight, no impedance/heart rate.
 *
 * Despite the name this is a different, incompatible protocol from [RunstarR5Handler].
 */
class RunstarR6Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write (ack / future handshake)
    private val CHAR_LIVE: UUID = uuid16(0xFFB2)    // notify (live A2 frames)
    private val CHAR_RESULT: UUID = uuid16(0xFFB3)  // indicate (A1 info / A0 ack / A3 result / A4 history)

    private var lastPreviewWeightKg = -1f
    private var lastLockedWeightKg: Float? = null
    private var lastPublishedWeightRaw: Int? = null
    private var outgoingSeq = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Claim strictly by advertised name — openScale only sees the name (not the
        // characteristic list) before connecting.
        if (name != "runstar-r6" && !name.startsWith("runstar-r6")) return null

        return DeviceSupport(
            displayName = "Runstar R6",
            // Publishes raw impedance/heart rate but derives no fat/water/muscle, so
            // BODY_COMPOSITION is advertised but not in `implemented`.
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.HISTORY_READ
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        lastPreviewWeightKg = -1f
        lastLockedWeightKg = null
        lastPublishedWeightRaw = null
        outgoingSeq = 0

        // Subscribe live first, then result — mirrors RobiS9Handler's subscription order
        // for the same FFB0 family.
        setNotifyOn(SERVICE, CHAR_LIVE)
        setNotifyOn(SERVICE, CHAR_RESULT)

        // No BA/BB handshake: confirmed unnecessary across multiple field tests, the scale
        // streams A2 and delivers the A3 result without one.
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.size != 20) return
        if ((data[2].toInt() and 0xFF) != 0x00) return
        if (!isChecksumValid(data)) {
            logD("Runstar R6 checksum mismatch ${data.toHexPreview(20)}")
            return
        }

        val seq = data[0].toInt() and 0xFF
        when (data[3].toInt() and 0xFF) {
            TYPE_LIVE_WEIGHT -> handleLiveWeight(data)
            TYPE_DEVICE_INFO -> {
                logD("Runstar R6 device info ${data.toHexPreview(20)}")
                sendAck(seq)
            }
            TYPE_ACK -> logD("Runstar R6 ack ${data.toHexPreview(20)}")
            TYPE_FINAL_RESULT -> handleFinalResult(data, seq)
            TYPE_HISTORY_ENTRY -> handleHistoryEntry(data, seq)
            else -> logD(
                "Runstar R6 unhandled frame type=0x${String.format("%02X", data[3])} " +
                    data.toHexPreview(20)
            )
        }
    }

    /**
     * 0xA2 live weight frame: byte4 = state (0x01 measuring, 0x02 settling, 0x04 locked),
     * bytes 6..8 = weight, u24 BE grams. Never publishes — only the 0xA3 result is
     * authoritative — but surfaces a throttled progress message while measuring/settling.
     */
    private fun handleLiveWeight(data: ByteArray) {
        val state = data[4].toInt() and 0xFF
        val weightKg = u24be(data, 6) / 1000.0f

        when (state) {
            STATE_MEASURING, STATE_SETTLING -> {
                if (abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
                    userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
                    lastPreviewWeightKg = weightKg
                }
            }
            STATE_LOCKED -> lastLockedWeightKg = weightKg
        }
    }

    /**
     * 0xA3 final result frame: byte4 = status (0x00 = OK), bytes 5..7 = weight (u24 BE
     * grams), byte8 = heart rate (bpm), bytes 9..10 = impedance (u16 BE Ohm).
     */
    private fun handleFinalResult(data: ByteArray, seq: Int) {
        logI("Runstar R6 final result ${data.toHexPreview(20)}")

        val grams = u24be(data, 5)
        if (lastPublishedWeightRaw == grams) {
            logD("Runstar R6 duplicate final result raw=$grams, skipping publish")
            return
        }

        val heartRateRaw = data[8].toInt() and 0xFF
        val impedanceRaw = u16be(data, 9)

        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            weight = grams / 1000.0f
            if (heartRateRaw != 0) heartRate = heartRateRaw
            if (impedanceRaw != 0) impedance = impedanceRaw.toDouble()
        }
        publish(measurement)
        lastPublishedWeightRaw = grams
        sendAck(seq)

        // No requestDisconnect(): the scale hangs up on its own a few seconds after the
        // result (every capture: HCI reason 19, remote-initiated). Forcing it here would
        // risk cutting off 0xA4 history entries still pending on this connection.
    }

    /**
     * 0xA4 history entry: bytes 4..7 = Unix timestamp (u32 BE, seconds), bytes 9..11 =
     * weight (u24 BE grams). No dedup — acking retires the entry on the scale (see class
     * doc), so a resend only happens if the ack itself is lost.
     */
    private fun handleHistoryEntry(data: ByteArray, seq: Int) {
        val epochSeconds = u32be(data, 4)
        val grams = u24be(data, 9)
        val entryDate = Date(epochSeconds * 1000L)
        logI("Runstar R6 history entry seq=$seq date=$entryDate weight=${grams / 1000.0f}kg")

        publish(ScaleMeasurement().apply {
            dateTime = entryDate
            weight = grams / 1000.0f
        })
        sendAck(seq)
    }

    /** Ack a scale indication: 0xB0, payload = [seq being acked][0x00]. */
    private fun sendAck(seq: Int) {
        val frame = buildFrame(TYPE_ACK_OUT, byteArrayOf((seq and 0xFF).toByte(), 0x00))
        writeTo(SERVICE, CHAR_WRITE, frame, withResponse = true)
    }

    /**
     * Build a 20-byte outgoing frame: `[seq][len][00][type][payload…][chk]`, where `len`
     * covers TYPE + payload (i.e. `payload.size + 1`) and `chk` is [computeChecksum]. Uses
     * and advances [outgoingSeq]. Kept general so a future handshake can reuse it.
     */
    private fun buildFrame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 15) { "payload too large for a 20-byte frame" }
        val frame = ByteArray(20)
        frame[0] = (outgoingSeq and 0xFF).toByte()
        outgoingSeq = (outgoingSeq + 1) and 0xFF
        frame[1] = ((payload.size + 1) and 0xFF).toByte()
        frame[2] = 0x00
        frame[3] = (type and 0xFF).toByte()
        for (i in payload.indices) {
            frame[4 + i] = payload[i]
        }
        frame[19] = computeChecksum(frame).toByte()
        return frame
    }

    /** `sum(bytes[3..18]) & 0x1F` — see class doc for how this was verified. */
    private fun computeChecksum(frame: ByteArray): Int {
        var sum = 0
        for (i in 3..18) {
            sum += frame[i].toInt() and 0xFF
        }
        return sum and 0x1F
    }

    private fun isChecksumValid(frame: ByteArray): Boolean =
        (frame[19].toInt() and 0xFF) == computeChecksum(frame)

    private fun u24be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun u32be(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    companion object {
        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_ACK = 0xA0
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_HISTORY_ENTRY = 0xA4
        private const val TYPE_ACK_OUT = 0xB0

        private const val STATE_MEASURING = 0x01
        private const val STATE_SETTLING = 0x02
        private const val STATE_LOCKED = 0x04
    }
}
