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
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.bluetooth.libs.Wla25BodyComposition
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.WeightUnit

/**
 * Runstar R6 smart scale, the Hoffen HO-36 (ICOMON model FG2210LB, Fitdays app), and the
 * Fitdays FG2202WB (an 8-electrode "Smart Body fat Scale").
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
 * The HO-36 speaks the same protocol (checksum verified across 422 unique frames from HCI
 * snoops of the Fitdays app and of this handler) with two differences seen so far:
 *  - it sets 0x68 in the top byte of every 24-bit weight field — `68 24 ea` for 9.45 kg,
 *    `68 00 00` with the scale empty. [WEIGHT_MASK] clears those bits for every scale
 *    this handler serves.
 *  - it only measures impedance after the app has sent a 0xBA user frame (see
 *    [encodeHoffenUserInfo]); without one, 0xA3 carries impedance 0 and the scale's own
 *    display shows weight only.
 *
 * The FG2202WB speaks the same protocol with two differences seen so far: 0x25 where the R6
 * sends status 00, and 0xA3/0xA4 frames that are the first of two parts (length 0x1A/0x1E).
 * The two-part 0xA3 (also sent by the Sacoma Ultra) carries ten 8-electrode impedances
 * instead of one, see [segmentalImpedances]; they feed the vendor's [Wla25BodyComposition],
 * which matched the Fitdays app's reading of a Sacoma Ultra weigh-in to within 0.2 % fat.
 * Impedance is only measured with the handlebar held; feet only, the limb values read
 * ~1 Ω and WLA25's own validity gate rejects them (weight only).
 *
 * The Lescale F4P (ICOMON model FI2019LB-C) speaks the same protocol (checksum verified
 * across every frame of six weigh-ins) with two differences seen so far:
 *  - the top three bits of the trailer byte are flags, not part of the checksum: 0x20 is
 *    set on every frame while the scale displays pounds. The flag in frames the app sends
 *    sets the display unit — a 0xBA user frame without it switched the scale to kg —
 *    so frames to the F4P carry the user's unit (see [unitFlags]).
 *  - like the HO-36, it gets the 0xBA user frame after the 0xA1 ack. With it the scale
 *    shows body composition on its display and, after the weight locks (A2 state 0x04,
 *    about 20 s), reports impedance and heart rate in 0xA3.
 *
 * Despite the name this is a different, incompatible protocol from [RunstarR5Handler].
 */
class RunstarR6Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val SERVICE_FG2202WB: UUID = uuid16(0x1530) // advertised next to 0xFFB0, see supportFor
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write (ack / future handshake)
    private val CHAR_LIVE: UUID = uuid16(0xFFB2)    // notify (live A2 frames)
    private val CHAR_RESULT: UUID = uuid16(0xFFB3)  // indicate (A1 info / A0 ack / A3 result / A4 history)

    private var lastPreviewWeightKg = -1f
    private var lastPublishedWeightRaw: Int? = null
    private var outgoingSeq = 0
    /** Fitdays sends these the 0xBA user frame after the 0xA1 ack; see [sendHoffenUserInfo]. */
    private var sendsUserInfo = false
    private var isLescale = false
    /** Trailer flag bits for outgoing frames; only the F4P gets any (see [unitFlags]). */
    private var outgoingFlags = 0
    /** Part 1 of a two-part 0xA3, held until part 2 arrives. */
    private var pendingResultPart1: ByteArray? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Claim strictly by advertised name — openScale only sees the name (not the
        // characteristic list) before connecting.
        val displayName = when {
            name == "runstar-r6" || name.startsWith("runstar-r6") -> "Runstar R6"
            // The HO-36 advertises as "HOFFEN" with 0xFFB0 (and 0x1530) — MGBHandler would
            // otherwise claim it by service.
            name == "hoffen" && SERVICE in device.serviceUuids -> "Hoffen HO-36"
            // The FG2202WB advertises as "MY_SCALE", like the ALPHA Smart Scale PRO 3 that
            // TaylorBIAHandler serves — but only the FG2202WB advertises 0x1530 next to 0xFFB0,
            // and TaylorBIAHandler leaves it alone for that.
            name == "my_scale" && SERVICE in device.serviceUuids &&
                SERVICE_FG2202WB in device.serviceUuids -> "Fitdays FG2202WB"
            // The Sacoma Ultra (8 electrodes + handle, Fitdays/Sacoma app) advertises the
            // FG2202WB's 0xFFB0 + 0x1530 pair under its own name.
            name == SACOMA_NAME.lowercase(Locale.ROOT) && SERVICE in device.serviceUuids &&
                SERVICE_FG2202WB in device.serviceUuids -> "Sacoma Ultra"
            name == LESCALE_F4P_NAME.lowercase(Locale.ROOT) -> "Lescale F4P"
            else -> return null
        }

        return DeviceSupport(
            displayName = displayName,
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
        val name = getPeripheral()?.name
        isLescale = name.equals(LESCALE_F4P_NAME, ignoreCase = true)
        sendsUserInfo = listOf(HOFFEN_NAME, LESCALE_F4P_NAME, SACOMA_NAME)
            .any { name.equals(it, ignoreCase = true) }
        outgoingFlags = if (isLescale) unitFlags(user.scaleUnit) else 0
        pendingResultPart1 = null

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
        if ((data[2].toInt() and 0xFF) == 0x01) {
            // Part 2 of a two-part 0xA3; same seq and checksum rule as part 1.
            val part1 = pendingResultPart1 ?: return
            pendingResultPart1 = null
            val valid = data[0] == part1[0] && isChecksumValid(data)
            if (!valid) logW("Runstar R6 bad A3 part 2 ${data.toHexPreview(20)}, weight only")
            handleFinalResult(part1, part1[0].toInt() and 0xFF, user,
                if (valid) segmentalImpedances(part1, data) else null)
            return
        }
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
                // Fitdays sends the HO-36's user frame right after this ack; the F4P and
                // Sacoma Ultra accept the same frame (answer 0xA0 01) and only then show
                // body composition on their display.
                if (sendsUserInfo) sendHoffenUserInfo(user)
            }
            TYPE_ACK -> logD("Runstar R6 ack ${data.toHexPreview(20)}")
            TYPE_FINAL_RESULT ->
                if ((data[1].toInt() and 0xFF) > SINGLE_FRAME_LEN) pendingResultPart1 = data
                else handleFinalResult(data, seq, user, null)
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

        val weightKg = weightGrams(data, 6) / 1000.0f
        if (abs(weightKg - lastPreviewWeightKg) >= 0.05f) {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
            lastPreviewWeightKg = weightKg
        }
    }

    /** [segmental]: the ten impedances of a two-part 0xA3, or null for a single-frame one. */
    private fun handleFinalResult(data: ByteArray, seq: Int, user: ScaleUser, segmental: DoubleArray?) {
        logI("Runstar R6 final result ${data.toHexPreview(20)}")

        val result = decodeFinalResult(data) ?: return
        if (result.status !in OK_STATUSES) {
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
            // Only a plausible impedance is stored: the scales report fixed placeholders when
            // they measured none (0 on the HO-36, 50 Ω on the FG2202WB feet only), and those
            // in the history would be worse than nothing.
            // In a two-part frame bytes 9..10 are the first segmental value, not an impedance.
            if (segmental == null && result.impedanceOhm in IMPEDANCE_MIN..IMPEDANCE_MAX) {
                this[MeasurementType.IMPEDANCE] = Ohm(result.impedanceOhm.toFloat())
            }
        }

        if (segmental == null) applyBodyComposition(measurement, weightKg, result.impedanceOhm, user)
        else applySegmentalBodyComposition(measurement, weightKg, segmental, user)

        publish(measurement)
        lastPublishedWeightRaw = result.grams
        sendAck(seq)

        // No requestDisconnect(): the scale hangs up on its own a few seconds after the
        // result (every capture: HCI reason 19, remote-initiated). Forcing it here would
        // risk cutting off 0xA4 history entries still pending on this connection.
    }

    /** Body composition from the ten 8-electrode impedances, via the vendor's WLA25. */
    private fun applySegmentalBodyComposition(
        measurement: ScaleMeasurement,
        weightKg: Float,
        imps: DoubleArray,
        user: ScaleUser
    ) {
        logI("Runstar R6 segmental impedances (Ω): ${imps.joinToString()}")
        if (user.bodyHeight <= 0f) {
            logW("Runstar R6 no body height set for the current user, skipping body composition")
            return
        }
        val r = Wla25BodyComposition.compute(user.bodyHeight.toInt(), weightKg.toDouble(), imps)
        if (r == null) {
            logW("Runstar R6 segmental impedances rejected (no handlebar contact?), weight only")
            return
        }
        measurement[MeasurementType.BODY_FAT] = Percent(r.fat)
        measurement[MeasurementType.WATER] = Percent(r.water)
        // MUSCLE is skeletal muscle in openScale (Fitdays' "Skeletal Muscle"); Fitdays' broader
        // "Muscle Rate" (fat-free mass minus bone, ~80 %) goes to TOTAL_MUSCLE, as in KeepS3Handler.
        measurement[MeasurementType.MUSCLE] = Percent(r.skeletalMuscle)
        measurement[TOTAL_MUSCLE] = Percent(r.musclePercent)
        measurement[MeasurementType.BONE] = Kg(r.boneKg)
        measurement[MeasurementType.VISCERAL_FAT] = r.visceralFat.toFloat()
        measurement[MeasurementType.PROTEIN] = Percent(r.protein)
        val sex = if (user.gender.isMale()) Wla25BodyComposition.SEX_MALE else Wla25BodyComposition.SEX_FEMALE
        Wla25BodyComposition.bodyAge(user.age, r.fat.toDouble(), sex)
            .takeIf { it in 10..99 }?.let { measurement[METABOLIC_AGE] = it }
        // BMR and LBM are left to openScale's derived values (wiki: "do not compute what
        // openScale already derives").
        logI("Runstar R6 body composition (WLA25): $r")
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
        val grams = weightGrams(data, 9)
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

    private fun sendHoffenUserInfo(user: ScaleUser) {
        val heightCm = user.bodyHeight.roundToInt()
        if (heightCm !in 1..255) {
            // The frame carries the height in one byte; without one there is nothing
            // truthful to send, and body composition would be skipped anyway.
            logW("Runstar R6 no usable body height, not sending the user frame")
            return
        }
        val payload = encodeHoffenUserInfo(
            epochSeconds = System.currentTimeMillis() / 1000L,
            heightCm = heightCm,
            age = user.age,
            isMale = user.gender.isMale(),
        )
        writeTo(SERVICE, CHAR_WRITE, buildFrame(TYPE_USER_INFO_OUT, payload), withResponse = true)
    }

    /** Ack a scale indication: 0xB0, payload = [seq being acked][0x00]. */
    private fun sendAck(seq: Int) {
        val frame = buildFrame(TYPE_ACK_OUT, byteArrayOf((seq and 0xFF).toByte(), 0x00))
        writeTo(SERVICE, CHAR_WRITE, frame, withResponse = true)
    }

    /**
     * Build a 20-byte outgoing frame: `[seq][len][00][type][payload…][chk]`, where `len`
     * covers TYPE + payload (i.e. `payload.size + 1`) and `chk` is [computeChecksum] with
     * [outgoingFlags] OR-ed into its top bits. Uses and advances [outgoingSeq]. Kept general
     * so a future handshake can reuse it.
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
        frame[19] = (computeChecksum(frame) or outgoingFlags).toByte()
        return frame
    }

    companion object {
        /** Shared identities with KeepS3/QN/Picooc and Relaxmedic, so histories stay continuous. */
        val TOTAL_MUSCLE = MeasurementType.devicePercent(
            "total_muscle", R.string.measurement_type_total_muscle,
            icon = MeasurementTypeIcon.IC_M_WORKOUT, color = 0xFF2E7D32.toInt()
        )
        val METABOLIC_AGE = MeasurementType.deviceInt(
            "metabolic_age", R.string.measurement_type_metabolic_age,
            icon = MeasurementTypeIcon.IC_M_TIMER, color = 0xFF795548.toInt()
        )

        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_ACK = 0xA0
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_HISTORY_ENTRY = 0xA4
        private const val TYPE_ACK_OUT = 0xB0
        private const val TYPE_USER_INFO_OUT = 0xBA

        /** Advertised name of the Hoffen HO-36. */
        private const val HOFFEN_NAME = "HOFFEN"
        private const val SACOMA_NAME = "SACOMA SCALE"

        /** Advertised name of the Lescale F4P. */
        private const val LESCALE_F4P_NAME = "Lescale F4P"

        private const val STATE_MEASURING = 0x01
        private const val STATE_SETTLING = 0x02

        /** 00 on the R6; 0x25 on every FG2202WB and Sacoma Ultra weigh-in. */
        private val OK_STATUSES = setOf(0x00, 0x25)

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
                grams = weightGrams(data, 5),
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

        /**
         * Compares the low five bits only: the Lescale F4P uses the top three bits of the
         * trailer as flags (0x20 = pounds). The other scales leave them clear.
         */
        internal fun isChecksumValid(frame: ByteArray): Boolean =
            (frame[19].toInt() and 0x1F) == computeChecksum(frame)

        /** Trailer flag the Lescale F4P reads as "display pounds". */
        internal const val FLAG_UNIT_LB = 0x20

        /**
         * Trailer flags that make the F4P display [unit]. Stones were never tested and map to
         * kg rather than a guessed bit.
         */
        internal fun unitFlags(unit: WeightUnit): Int =
            if (unit == WeightUnit.LB) FLAG_UNIT_LB else 0x00

        /**
         * Low 19 bits of a 24-bit weight field (up to 524 kg). The HO-36 sets 0x68 in the
         * top byte (bits 19, 21 and 22) on every frame, loaded or empty; the R6 captures
         * leave them clear, and none of them can be part of a real weight.
         */
        internal const val WEIGHT_MASK = 0x07FFFF

        /** u24 BE weight in grams at [offset], with the HO-36's flag bits cleared. */
        internal fun weightGrams(data: ByteArray, offset: Int): Int =
            u24be(data, offset) and WEIGHT_MASK

        /**
         * Payload of the HO-36's 0xBA user frame (15 bytes, after the type byte):
         *
         * `[time u32 BE][01][4A 00 00 00 00][height cm][00 00][sex 0x80 | age][2F]`
         *
         * Time is Unix seconds. Height, age and the sex bit were matched against three known
         * profiles (female and male) in HCI snoops of the Fitdays app. The bytes written as
         * constants are copied from those captures and their meaning is unknown: they were
         * the same for all three profiles. The one exception is `00 00` after the height,
         * which read `17 70` in a frame Fitdays sends first with a stale profile before
         * the current one.
         */
        internal fun encodeHoffenUserInfo(
            epochSeconds: Long,
            heightCm: Int,
            age: Int,
            isMale: Boolean,
        ): ByteArray {
            val sexAge = (if (isMale) 0x80 else 0x00) or age.coerceIn(0, 0x7F)
            return byteArrayOf(
                (epochSeconds ushr 24).toByte(),
                (epochSeconds ushr 16).toByte(),
                (epochSeconds ushr 8).toByte(),
                epochSeconds.toByte(),
                0x01, 0x4A, 0x00, 0x00, 0x00, 0x00,
                heightCm.toByte(),
                0x00, 0x00,
                sexAge.toByte(),
                0x2F,
            )
        }

        private fun u24be(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 16) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)

        /** A frame's length byte counts the type byte plus up to 15 payload bytes. */
        private const val SINGLE_FRAME_LEN = 0x10

        /**
         * The ten impedances of a two-part 0xA3, in Ω, in [Wla25BodyComposition]'s order:
         * u16 BE (0.1 Ω) at part 1 bytes 9..18, then part 2 bytes 3..12 — two groups of
         * five, each led by the small trunk value (~20 Ω), then four limb values (~250-370 Ω)
         * on a Sacoma Ultra with the handlebar held.
         */
        private fun segmentalImpedances(part1: ByteArray, part2: ByteArray): DoubleArray {
            val bytes = part1.copyOfRange(9, 19) + part2.copyOfRange(3, 13)
            return DoubleArray(10) { u16be(bytes, 2 * it) / 10.0 }
        }

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
