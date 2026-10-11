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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

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
    private var lastPublishedWeightRaw: Int? = null
    private var outgoingSeq = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Claim strictly by advertised name — openScale only sees the name (not the
        // characteristic list) before connecting.
        if (name != "runstar-r6" && !name.startsWith("runstar-r6")) return null

        return DeviceSupport(
            displayName = "Runstar R6",
            // The scale reports only weight, heart rate and raw impedance; fat/water/muscle
            // are derived from the impedance via StandardImpedanceLib, as in
            // VitafitVT701Handler and EtekcityESF551Handler.
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.HISTORY_READ
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        lastPreviewWeightKg = -1f
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
            TYPE_FINAL_RESULT -> handleFinalResult(data, seq, user)
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
     * The 0x04 locked weight is ignored for the same reason.
     */
    private fun handleLiveWeight(data: ByteArray) {
        val state = data[4].toInt() and 0xFF
        if (state != STATE_MEASURING && state != STATE_SETTLING) return

        val weightKg = u24be(data, 6) / 1000.0f
        if (abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            lastPreviewWeightKg = weightKg
        }
    }

    private fun handleFinalResult(data: ByteArray, seq: Int, user: ScaleUser) {
        logI("Runstar R6 final result ${data.toHexPreview(20)}")

        val result = decodeFinalResult(data) ?: return
        if (result.status != STATUS_OK) {
            // Never seen in any capture, so its meaning is unknown — log it rather than
            // discard a weigh-in on a guess.
            logW("Runstar R6 final result status=0x${String.format("%02X", result.status)}")
        }
        if (lastPublishedWeightRaw == result.grams) {
            logD("Runstar R6 duplicate final result raw=${result.grams}, skipping publish")
            return
        }

        val weightKg = result.grams / 1000.0f
        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (result.heartRate != 0) this[MeasurementType.HEART_RATE] = Bpm(result.heartRate)
            // Keep the raw value even when it is out of range below, so a later recompute
            // can still use it.
            if (result.impedanceOhm != 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(result.impedanceOhm.toFloat())
            }
        }

        applyBodyComposition(measurement, weightKg, result.impedanceOhm, user)

        publish(measurement)
        lastPublishedWeightRaw = result.grams
        sendAck(seq)

        // No requestDisconnect(): the scale hangs up on its own a few seconds after the
        // result (every capture: HCI reason 19, remote-initiated). Forcing it here would
        // risk cutting off 0xA4 history entries still pending on this connection.
    }

    /**
     * Derives body composition from the raw impedance, like [VitafitVT701Handler] and
     * [EtekcityESF551Handler] do for their weight-plus-impedance-only scales.
     *
     * Logs the impedance and every derived value: the impedance is never shown in the UI
     * (the IMPEDANCE measurement type is internal and disabled), so the exported log file
     * is the only way to check that this scale's raw value is on the scale the formulas
     * expect — see [IMPEDANCE_MIN]/[IMPEDANCE_MAX]. The user's height, age and gender are
     * deliberately left out: log files are meant to be pasted into bug reports.
     */
    private fun applyBodyComposition(
        measurement: ScaleMeasurement,
        weightKg: Float,
        impedanceOhm: Int,
        user: ScaleUser
    ) {
        if (impedanceOhm !in IMPEDANCE_MIN..IMPEDANCE_MAX) {
            logW(
                "Runstar R6 impedance ${impedanceOhm}Ω outside the plausible " +
                    "$IMPEDANCE_MIN..${IMPEDANCE_MAX}Ω range, skipping body composition"
            )
            return
        }
        // heightM divides in bmi and multiplies into h2rCoeff; without a height every
        // derived value would be meaningless or infinite.
        if (user.bodyHeight <= 0f) {
            logW("Runstar R6 no body height set for the current user, skipping body composition")
            return
        }

        val lib = StandardImpedanceLib(
            gender = user.gender,
            age = user.age,
            weightKg = weightKg.toDouble(),
            heightM = user.bodyHeight / 100.0,
            impedance = impedanceOhm.toDouble(),
        )
        measurement[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
        measurement[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
        measurement[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
        measurement[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
        measurement[MeasurementType.LBM] = Kg(lib.fatFreeMassKg.toFloat())
        measurement[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat())

        logI(
            "Runstar R6 body composition (StandardImpedanceLib, impedance=${impedanceOhm}Ω): " +
                "fat=${lib.totalFatPercentage}% water=${lib.totalBodyWaterPercentage}% " +
                "muscle=${lib.skeletalMusclePercentage}% bone=${lib.boneMassKg}kg " +
                "lbm=${lib.fatFreeMassKg}kg bmr=${lib.basalMetabolicRate}kcal"
        )
    }

    /**
     * 0xA4 history entry: bytes 4..7 = Unix timestamp (u32 BE, seconds), bytes 9..11 =
     * weight (u24 BE grams). No dedup — acking retires the entry on the scale (see class
     * doc), so a resend only happens if the ack itself is lost.
     */
    private fun handleHistoryEntry(data: ByteArray, seq: Int) {
        val epochSeconds = u32be(data, 4)
        val grams = u24be(data, 9)
        val entryDate = plausibleDate(epochSeconds)
        logI("Runstar R6 history entry seq=$seq date=$entryDate weight=${grams / 1000.0f}kg")

        publish(ScaleMeasurement().apply {
            dateTime = entryDate
            this[MeasurementType.WEIGHT] = Kg(grams / 1000.0f)
        })
        sendAck(seq)
    }

    /**
     * History timestamps are Unix seconds from the scale's own clock, which can be unset
     * or wrong. Falls back to now rather than dropping the entry, as [AfuB1Handler] and
     * [YunmaiHandler] do: acking retires the entry on the scale, so a rejected reading is
     * lost for good, while a wrong date can still be corrected by the user.
     */
    private fun plausibleDate(epochSeconds: Long): Date {
        val millis = epochSeconds * 1000L
        if (millis in EARLIEST_PLAUSIBLE_MILLIS..(System.currentTimeMillis() + ONE_DAY_MILLIS)) {
            return Date(millis)
        }
        logW("Runstar R6 implausible history timestamp ${epochSeconds}s, using current time")
        return Date()
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

    companion object {
        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_ACK = 0xA0
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_HISTORY_ENTRY = 0xA4
        private const val TYPE_ACK_OUT = 0xB0

        private const val STATE_MEASURING = 0x01
        private const val STATE_SETTLING = 0x02

        private const val STATUS_OK = 0x00

        /** 2000-01-01T00:00:00Z — same floor as [AfuB1Handler]'s history guard. */
        private const val EARLIEST_PLAUSIBLE_MILLIS = 946_684_800_000L
        private const val ONE_DAY_MILLIS = 86_400_000L

        /**
         * Plausible whole-body impedance range in Ohm. StandardImpedanceLib's own class doc
         * puts a normal-BMI 180cm male at roughly 500 ± 100 Ω and warns its formulas don't
         * hold far outside that; anything beyond this window means the scale reports on a
         * different scale than assumed, and fabricated values would be worse than none.
         */
        internal const val IMPEDANCE_MIN = 200
        internal const val IMPEDANCE_MAX = 1200

        /** Fields of a 0xA3 final-result frame. */
        internal data class FinalResult(
            val status: Int,
            val grams: Int,
            val heartRate: Int,
            val impedanceOhm: Int,
        )

        /**
         * Decode a 0xA3 final result: byte4 = status (0x00 = OK), bytes 5..7 = weight (u24
         * BE grams), byte8 = heart rate (bpm), bytes 9..10 = impedance (u16 BE Ohm).
         *
         * Returns `null` for anything that is not a well-formed A3 frame.
         */
        internal fun decodeFinalResult(data: ByteArray): FinalResult? {
            if (data.size != 20) return null
            if ((data[2].toInt() and 0xFF) != 0x00) return null
            if ((data[3].toInt() and 0xFF) != TYPE_FINAL_RESULT) return null
            if (!isChecksumValid(data)) return null

            return FinalResult(
                status = data[4].toInt() and 0xFF,
                grams = u24be(data, 5),
                heartRate = data[8].toInt() and 0xFF,
                impedanceOhm = u16be(data, 9),
            )
        }

        /** `sum(bytes[3..18]) & 0x1F` — see class doc for how this was verified. */
        internal fun computeChecksum(frame: ByteArray): Int {
            var sum = 0
            for (i in 3..18) {
                sum += frame[i].toInt() and 0xFF
            }
            return sum and 0x1F
        }

        internal fun isChecksumValid(frame: ByteArray): Boolean =
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
    }
}
