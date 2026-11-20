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

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date

/**
 * **Grifema GA2001** "Smart Body Fat scale" — a broadcast-only body-composition scale.
 *
 * Sold under the Grifema brand (the name is printed on the glass, above the lower electrodes); the
 * plate on the back reads "Smart Body Fat scale / Model: GA2001". It is a square glass platform
 * with four round electrode discs. Inside it is a Senssun/Camry "Broad Fat Scale DC" board: the
 * vendor SDK names this family `IF_B7` (`BroadFatScaleDC(5, "IF_B7")` in
 * `senssun.blelib.model.BleDevice`), which is the name the device advertises as. It **never
 * accepts a GATT connection** — a connect attempt times out — and reports every measurement inside
 * its advertisement, so this handler is a [LinkMode.BROADCAST_ONLY] one: it reads the manufacturer
 * record and never touches GATT.
 *
 * ## Advertisement
 *
 * The measurement record is the manufacturer-specific one under company id `0x0100`, 17 bytes
 * after the id:
 *
 * - `[0..2]`   : header `02 03 11` — constant in every frame observed
 * - `[3..8]`   : the device's own MAC, **big-endian** (matches the advertised address)
 * - `[9]`      : frame marker `01` — constant; the first byte covered by the checksum
 * - `[10..11]` : weight, **big-endian** 16-bit, kilogram × 100 (e.g. `2B C0` = 11200 = 112.00 kg)
 * - `[12..13]` : bioelectrical impedance, **big-endian** 16-bit, ohms (`0000` = not measured)
 * - `[14]`     : flags — `0x01` during the weight phase, `0xA1` once the scale runs its BIA pass
 * - `[15]`     : a sequence counter, ~6 per second; not measurement data
 * - `[16]`     : checksum = `sum(payload[9..15]) and 0xFF`
 *
 * Verified against 60+ frames captured live from one device. Every frame checksums, the header and
 * the marker never change, and the embedded MAC always equals the advertised address.
 *
 * ## When to publish
 *
 * The scale streams ~6 advertisements a second while someone stands on it, so a single frame is
 * not a measurement, and **the weight field is never stable for long**: in the captures it
 * oscillates by up to 1 kg between consecutive packets (`112.3 → 111.5 → 111.8 → 112.1 → 112.4`),
 * which is a load cell reading a person who is not perfectly motionless. Requiring one exact value
 * to repeat therefore never fires, and reading one packet reports a 113 kg spike for a 111.6 kg
 * person. So the handler keeps a rolling window of the last few readings and publishes their
 * **median**, which reproduces the number the scale's own display locks on to.
 *
 * Two things make a measurement complete:
 *
 * - **BIA finished**: the scale sets the BIA flag (`0xA1`) about 15 s after someone steps on, and
 *   only then reports its answer for the impedance. Publishing needs that answer to repeat, i.e.
 *   one value, and the weight window to be settled (spread within [MAX_WEIGHT_SPREAD_KG]). When
 *   the answer is `0 Ω` the BIA pass completed with a broken circuit — standing on the electrodes
 *   through footwear — so the weight is stored without composition, the same reading [AiLinkBroadcastHandler]
 *   gives for "BIA pass failed with 0 Ω".
 * - **The user stepped off**: [MIN_ZERO_LOAD_PACKETS] consecutive no-load frames end the
 *   weigh-in. What the scale measured is published then, so a weighing that ends before the BIA
 *   phase — stepping off early — still stores the weight, just without composition.
 *
 * Body composition is **not** taken from the vendor's proprietary algorithm: it needs the scale's
 * 4-electrode 50 kHz / 100 kHz calibration values, which never appear in the broadcast. Like the
 * other impedance scales in openScale, the raw impedance is stored and composition is derived with
 * [StandardImpedanceLib], so openScale's numbers will not match the vendor app's.
 */
class IfB7Handler : ScaleDeviceHandler() {

    companion object {
        /** Senssun's company id in the advertisement; the measurement record lives under it. */
        private const val MANUFACTURER_ID = 0x0100

        /** Bytes the layout documented above needs; shorter records are not a measurement. */
        private const val PAYLOAD_SIZE = 17

        /** Constant frame header `02 03 11`. */
        private val HEADER = byteArrayOf(0x02, 0x03, 0x11)

        /** Constant marker `01`, the first checksummed byte. */
        private const val FRAME_MARKER = 0x01

        /** Flag set in every frame that reports a weight. */
        private const val FLAG_WEIGHT_REPORTED = 0x01

        /** Flag the scale sets when it runs its BIA pass; only then is the impedance real. */
        private const val FLAG_BIA_PHASE = 0x80

        /** Forwarded readings kept for the median. The load cell jitters, so the median of the
         * last readings is what the scale's display settles on. */
        private const val MAX_WEIGHT_WINDOW = 5

        /** Readings needed before a median can be called a settled weight. The adapter forwards
         * about one packet a second, so this is a few seconds of standing on the scale. */
        private const val MIN_LOAD_PACKETS = 4

        /** The BIA pass reports one answer; the impedance has to repeat to be that answer. A zero
         * impedance that repeats is the pass finishing with a broken circuit, not a reading. */
        private const val MIN_STABLE_IMPEDANCE_PACKETS = 2

        /** Consecutive no-load frames that mean the user has stepped off. A single zero appears
         * mid-weigh-in when someone shifts weight, so one zero is not the end of a weighing. */
        private const val MIN_ZERO_LOAD_PACKETS = 3

        /** The window is settled when its readings lie within this span. Captured jitter is up to
         * 1 kg between packets, so anything tighter never fires. */
        private const val MAX_WEIGHT_SPREAD_KG = 1.0f

        /** Guard against nonsense decodes; the hardware is a ~180 kg consumer scale. */
        private const val WEIGHT_MIN_KG = 1.0f
        private const val WEIGHT_MAX_KG = 300.0f
    }

    // --- Session state ------------------------------------------------------------------------

    /** Last forwarded weight readings, raw (kg × 100), oldest first. */
    private val weightWindow = ArrayDeque<Int>()

    /** Impedance of the last BIA frame, and how often that value has been reported. */
    private var lastImpedance = 0
    private var stableImpedanceCount = 0

    /** Consecutive forwarded frames that reported no load. */
    private var zeroLoadStreak = 0

    /** Publishing is single-shot per session: the scale keeps streaming the final reading. */
    private var hasPublished = false

    // --- Device identification ----------------------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Exact vendor family name. The sibling `IF_B7A` in the same vendor SDK is deliberately not
        // matched: its frames were never captured, so its grammar is only assumed, and openScale
        // should not publish a catalog row for a device nobody tested. The name is not enough on
        // its own: the record under our company id has to decode as a real frame (header, marker,
        // checksum, embedded MAC).
        if (!device.name.equals("IF_B7", ignoreCase = true)) return null

        val record = device.manufacturerData?.get(MANUFACTURER_ID)
        if (record == null) {
            logD("No manufacturer record under 0x0100, ignoring device")
            return null
        }
        if (parseFrame(record, device.address) == null) return null

        return DeviceSupport(
            // The marketed brand and model (printed on the glass and on the plate on the back) come
            // first, because that is what the buyer sees on the device; the name it advertises as
            // follows, which is the name the user has to tap in the scan list.
            displayName = "Grifema GA2001 / Senssun IF_B7",
            capabilities = setOf(DeviceCapability.BODY_COMPOSITION),
            implemented = setOf(DeviceCapability.BODY_COMPOSITION),
            linkMode = LinkMode.BROADCAST_ONLY,
            // The scale streams ~6 advertisements a second, and its BIA pass starts ~15 s after
            // someone steps on, so a long scan window is what covers a whole weigh-in.
            tuningProfile = TuningProfile.Conservative,
        )
    }

    override fun onConnected(user: ScaleUser) = reset()

    override fun onDisconnected() = reset()

    // --- Broadcast reception ------------------------------------------------------------------

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        if (hasPublished) return BroadcastAction.IGNORED

        val record = result.scanRecord?.manufacturerSpecificData?.get(MANUFACTURER_ID)
        val frame = record?.let { parseFrame(it, result.device.address) }
        if (frame == null) return BroadcastAction.IGNORED

        val (weightRaw, impedanceRaw, flags, sequence) = frame
        logD("frame: w=${weightRaw / 100f}kg imp=${impedanceRaw}Ω flags=0x%02X seq=$sequence".format(flags))

        if (weightRaw <= 0) {
            zeroLoadStreak++
            if (zeroLoadStreak >= MIN_ZERO_LOAD_PACKETS) {
                // End of the weigh-in. Publish what the scale measured, so a weighing that ended
                // before the BIA pass still stores the weight.
                if (weightWindow.size >= MIN_LOAD_PACKETS)
                    return publishMeasurement(medianKg(), user, "weigh-in ended")
                reset()
            }
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }
        zeroLoadStreak = 0

        val weightKg = weightRaw / 100f
        if (weightKg < WEIGHT_MIN_KG || weightKg > WEIGHT_MAX_KG) {
            logW("implausible weight %.2fkg (raw=%d); restarting window".format(weightKg, weightRaw))
            weightWindow.clear()
            lastImpedance = 0
            stableImpedanceCount = 0
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        weightWindow.addLast(weightRaw)
        if (weightWindow.size > MAX_WEIGHT_WINDOW) weightWindow.removeFirst()

        // Only frames of the BIA phase carry an impedance the scale measured. A repeated 0 Ω is the
        // pass finishing with a broken circuit — footwear on the electrodes — which is an answer as
        // well: it ends the weigh-in as a weight-only measurement.
        if ((flags and FLAG_BIA_PHASE) != 0) {
            if (impedanceRaw == lastImpedance) stableImpedanceCount++
            else {
                lastImpedance = impedanceRaw
                stableImpedanceCount = 1
            }
        }

        val spreadKg = (weightWindow.max() - weightWindow.min()) / 100f
        val settled = weightWindow.size >= MIN_LOAD_PACKETS && spreadKg <= MAX_WEIGHT_SPREAD_KG

        if (stableImpedanceCount >= MIN_STABLE_IMPEDANCE_PACKETS && settled)
            return publishMeasurement(
                medianKg(),
                user,
                if (lastImpedance > 0) "BIA phase completed" else "BIA pass finished with no circuit",
            )

        return BroadcastAction.CONSUMED_KEEP_SCANNING
    }

    /**
     * Builds and publishes one measurement, and stops the session.
     *
     * @param weightKg median of the recorded weight window
     * @param user app user the measurement is stored for
     * @param reason what ended the weigh-in, for the log
     */
    private fun publishMeasurement(weightKg: Float, user: ScaleUser, reason: String): BroadcastAction {
        val impedance = lastImpedance
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
        }

        // Height and age are inputs of every body-composition equation, and ScaleUser defaults
        // bodyHeight to -1: without them the percentages would be numbers out of a garbage input.
        if (impedance > 0 && user.bodyHeight > 0f && user.age > 0) {
            measurement[MeasurementType.IMPEDANCE] = Ohm(impedance.toFloat())

            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = impedance.toDouble(),
            )

            measurement[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
            measurement[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
            measurement[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
            measurement[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
            measurement[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat())
            measurement[MeasurementType.LBM] = Kg(lib.fatFreeMassKg.toFloat())
        } else {
            logD("no BIA impedance recorded (impedance=$impedance); publishing weight only")
        }

        logI("publishing weight=%.2fkg impedance=%dΩ — $reason".format(weightKg, impedance))
        hasPublished = true
        publish(measurement)
        return BroadcastAction.CONSUMED_STOP
    }

    /**
     * A decoded measurement advertisement.
     *
     * @param weightRaw weight field, kilogram × 100
     * @param impedanceRaw impedance field, ohms (0 = the scale did not measure)
     * @param flags flag byte of the frame
     * @param sequence packet counter, kept for the log
     */
    data class Frame(val weightRaw: Int, val impedanceRaw: Int, val flags: Int, val sequence: Int)

    /**
     * Validates one manufacturer record and decodes it.
     *
     * @param record manufacturer payload under [MANUFACTURER_ID] (the company id is stripped by
     *   Android, so index 0 is the first byte after it).
     * @param address advertised device address; the frame embeds the sender's MAC and must agree.
     * @return the decoded [Frame], or null when the record is not an IF_B7 measurement frame.
     */
    private fun parseFrame(record: ByteArray, address: String): Frame? {
        if (record.size != PAYLOAD_SIZE) {
            logD("IF_B7: manufacturer record is ${record.size} bytes, not $PAYLOAD_SIZE")
            return null
        }
        if (record[0] != HEADER[0] || record[1] != HEADER[1] || record[2] != HEADER[2]) {
            logD("IF_B7: header is not 02 03 11")
            return null
        }
        if (record[9].toInt() and 0xFF != FRAME_MARKER) {
            logD("IF_B7: frame marker 0x01 missing")
            return null
        }

        val mac = "%02X:%02X:%02X:%02X:%02X:%02X".format(
            record[3], record[4], record[5], record[6], record[7], record[8])
        if (!mac.equals(address, ignoreCase = true)) {
            logD("IF_B7: embedded MAC $mac does not match the advertised address $address")
            return null
        }

        val weightRaw = be16(record[10], record[11])
        val impedanceRaw = be16(record[12], record[13])
        val flags = record[14].toInt() and 0xFF
        val sequence = record[15].toInt() and 0xFF

        if ((flags and FLAG_WEIGHT_REPORTED) == 0) {
            logD("IF_B7: frame does not report a weight (flags=0x%02X)".format(flags))
            return null
        }

        val checksum = (record[9].toInt() + record[10].toInt() + record[11].toInt() +
                record[12].toInt() + record[13].toInt() + flags + sequence) and 0xFF
        if (checksum != record[16].toInt() and 0xFF) {
            logD("IF_B7: checksum mismatch (computed %02X, frame has %02X)"
                .format(checksum, record[16].toInt() and 0xFF))
            return null
        }

        return Frame(weightRaw, impedanceRaw, flags, sequence)
    }

    /** Big-endian 16-bit. */
    private fun be16(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    /** Median of the recorded weight window, in kg. The median is what makes the jitter read as
     * the number the scale's display settles on. */
    private fun medianKg(): Float {
        val sorted = weightWindow.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] / 100f
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 200f
    }

    private fun reset() {
        weightWindow.clear()
        lastImpedance = 0
        stableImpedanceCount = 0
        zeroLoadStreak = 0
        hasPublished = false
    }
}
