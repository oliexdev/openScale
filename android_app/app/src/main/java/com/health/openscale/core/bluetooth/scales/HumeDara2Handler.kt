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
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.UUID
import kotlin.math.roundToInt
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

/**
 * Hume Health "Dara 2.0" body-composition scale.
 *
 * NOT [FitTrackDaraHandler]: FitTrack sells an unrelated scale that is *also* marketed as
 * "Dara" and advertises with a "FITTRACK" name prefix on service 0xFFB0 with `AC 02 …`
 * framing. This device is a completely different product from a different vendor that
 * happens to share the "Dara 2.0" model name — it advertises literally as `"Dara 2.0"`
 * with no FitTrack prefix, and its own standard BLE Device Information service reports the
 * manufacturer as **"LeFu Scale"**, not FitTrack. That mismatch between the marketing name
 * ("Dara 2.0", suggesting FitTrack support already covers it) and the actual OEM hardware is
 * why this device showed "Not Supported" even after FitTrack Dara support was added in
 * 3.1.2 — see https://github.com/oliexdev/openScale/issues/1448.
 *
 * ## GATT layout
 * Confirmed via three separate Debug-mode connections and reproduced across five real
 * weigh-ins with the production handler below:
 *   Service 0xFFF0:
 *     0xFFF1 – user config WRITE (not notifiable)
 *     0xFFF4 – measurement NOTIFY
 *
 * This is the same GATT shape [ExcelvanCF36xHandler] uses for "Electronic Scale"-branded
 * units (also 0xFFF0/0xFFF1/0xFFF4, same 0xCF frame header and the same 8-byte user-config
 * write) — evidently the same underlying LeFu/Excelvan chip family under a different
 * storefront rebrand. It is kept as a **separate handler** rather than folded into
 * [ExcelvanCF36xHandler] because the measurement frame is a different, shorter shape (see
 * below) and the device name ("Dara 2.0") doesn't collide with "Electronic Scale" — matching
 * this codebase's existing pattern of one handler per exact rebrand name even within a
 * shared chip family (see ActiveEraBF06Handler, KeepS3Handler, etc. registered ahead of the
 * generic LeFu/0xFFF0 fallback in ScaleFactory).
 *
 * ## Frame format (11 bytes, reverse-engineered from five real weigh-ins)
 * `[0]=0xCF  [1..2]=impedance (BE) ÷100 [ohms]  [3..4]=weight (LE) ÷100 [kg]
 *  [5] [6] [7] [8] [9]=unexplained  [10]=XOR checksum over bytes[0..9]`
 *
 * While the reading settles, the scale streams live weight-only frames with bytes[1],[2],
 * [5],[6],[7] all zero; once locked it sends a final frame with those populated, repeated
 * twice, then disconnects itself after a short idle period. [isLockedFrame] detects that
 * transition; only the locked frame is published (dropping the live stream matches
 * [ExcelvanCF36xHandler]'s existing single-final-frame behaviour for the same chip family).
 *
 * Ground truth, all five real weigh-ins (same person, same day, same 188cm/18yr profile):
 *   `CF 0A 14 DE 21 5A 55 6F 01 00 4F` → 86.70 kg, impedance 25.80Ω (Hume app: 86.68 kg) —
 *     this first capture's impedance is implausibly low (bad/rushed foot contact); excluded
 *     from the body-composition range below, see [PLAUSIBLE_IMPEDANCE_OHMS].
 *   `CF C4 13 E8 21 E5 E7 96 00 00 45` → 86.80 kg, impedance 501.95Ω (Hume: 86.9 kg)
 *   `CF C4 13 ED 21 AC A6 AE 00 00 70` → 86.85 kg, impedance 501.95Ω (Hume: 86.85 kg, exact)
 *   `CF C4 13 F7 21 5F 55 75 00 00 B1` → 86.95 kg, impedance 501.95Ω (Hume: 86.9-87.0 kg)
 *   `CF B0 13 F2 21 70 75 B7 00 00 0D` → 86.90 kg, impedance 450.75Ω (Hume: 86.9 kg, exact)
 * Weight matches Hume's own reading within display rounding on every capture. The checksum
 * formula (XOR of all ten preceding bytes) was verified against all five frames.
 *
 * ## Body composition: [StandardImpedanceLib], not Hume's own numbers
 * Bytes[5..9] don't correlate with anything Hume's app displays — across the captures above,
 * bytes[1..2] (the impedance field) stayed effectively flat across three consecutive
 * same-session readings (501.95Ω) while Hume's displayed fat%/water%/muscle stayed frozen
 * too, but bytes[5..9] moved anyway with no matching change on screen. There isn't room in an
 * 11-byte frame for the ~13 distinct metrics Hume's app shows either, which strongly suggests
 * most of what's on screen is *computed client-side* by Hume from impedance + user profile,
 * not transmitted raw.
 *
 * So rather than guess at bytes[5..9], this handler feeds the one number we *did* verify
 * (impedance, landing at 450-502Ω across genuine readings — squarely in the normal
 * foot-to-foot BIA range) through openScale's own [StandardImpedanceLib] (generic published
 * BIA formulas, not Hume's proprietary ones). Checked against a real reading (450.75Ω, 86.9kg,
 * this device's user profile): skeletal muscle % and BMR land within ~1-2% of Hume's own display,
 * fat%/water%/lean mass within ~15% (the errors run in complementary directions — fat low,
 * water/lean high — which is internally consistent, not random noise), bone ~24% off. That's
 * an approximation, not a device reading, which is why [DeviceSupport.implemented] still
 * claims [DeviceCapability.BODY_COMPOSITION] but every value below traces back to
 * [StandardImpedanceLib] rather than a frame offset. Subcutaneous fat, visceral fat index,
 * and a "skeletal mass" distinct from skeletal muscle mass are not attempted at all: those
 * need segmental/multi-frequency BIA hardware this single whole-body impedance reading can't
 * provide, and [ScaleMeasurement] has no field for the last one regardless.
 */
class HumeDara2Handler : ScaleDeviceHandler() {

    companion object {
        /** Only trust the impedance-derived body-comp formula in the plausible foot-to-foot
         *  BIA range — see [StandardImpedanceLib]'s own doc comment (500 ± 100Ω for a ~180cm
         *  adult of normal BMI). The very first unit we captured read ~26Ω on a rushed/bad
         *  first contact and would have produced nonsense fat%/water% if fed through anyway. */
        private val PLAUSIBLE_IMPEDANCE_OHMS = 300.0..900.0

        /** Little-endian weight ÷100 at bytes[3..4], in kg. */
        fun weightKgFromFrame(frame: ByteArray): Float =
            ConverterUtils.fromUnsignedInt16Le(frame, 3) / 100.0f

        /** Big-endian impedance ÷100 at bytes[1..2], in ohms. */
        fun impedanceOhmsFromFrame(frame: ByteArray): Double =
            ConverterUtils.fromUnsignedInt16Be(frame, 1) / 100.0

        /** XOR of bytes[0..9]; must equal byte[10]. */
        fun checksum(frame: ByteArray): Int {
            var sum = 0
            for (i in 0..9) sum = sum xor (frame[i].toInt() and 0xFF)
            return sum and 0xFF
        }

        /** True once the scale has locked its reading (bytes[1],[2],[5],[6],[7] populate). */
        fun isLockedFrame(frame: ByteArray): Boolean =
            (frame[1].toInt() or frame[2].toInt() or frame[5].toInt() or
                    frame[6].toInt() or frame[7].toInt()) != 0
    }

    private val SVC get() = uuid16(0xFFF0)
    private val CHAR_WRITE get() = uuid16(0xFFF1)
    private val CHAR_NOTIFY get() = uuid16(0xFFF4)

    /** Last locked frame we published, to ignore the repeat the scale always sends. */
    private var lastFrame: ByteArray? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.equals("Dara 2.0", ignoreCase = true)) return null

        return DeviceSupport(
            displayName = "Hume Health Dara 2.0",
            capabilities = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG
            ),
            // Body composition is StandardImpedanceLib's estimate, not a device reading — see
            // the class doc comment for the measured accuracy against Hume's own display.
            implemented = setOf(
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.USER_SYNC,
                DeviceCapability.UNIT_CONFIG
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        lastFrame = null

        // Same 8-byte user-config write as ExcelvanCF36xHandler — same chip family.
        val cfg = buildUserConfig(user)
        writeTo(SVC, CHAR_WRITE, cfg, withResponse = true)

        setNotifyOn(SVC, CHAR_NOTIFY)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        if (data.size != 11 || data[0] != 0xCF.toByte()) {
            logD("unexpected frame len=${data.size} head=${if (data.isNotEmpty()) String.format("%02X", data[0]) else "-"}")
            return
        }

        val expectedChecksum = checksum(data)
        if ((data[10].toInt() and 0xFF) != expectedChecksum) {
            logD("checksum mismatch: got=${String.format("%02X", data[10])} want=${String.format("%02X", expectedChecksum)}")
            return
        }

        if (!isLockedFrame(data)) return // live weight stream while the scale settles

        val previous = lastFrame
        if (previous != null && previous.contentEquals(data)) return // the locked frame repeats once
        lastFrame = data.copyOf()

        publishFrame(data, user)
        requestDisconnect()
    }

    private fun publishFrame(frame: ByteArray, user: ScaleUser) {
        val weightKg = ConverterUtils.toKilogram(weightKgFromFrame(frame), user.scaleUnit)
        val impedanceOhms = impedanceOhmsFromFrame(frame)

        val m = ScaleMeasurement().apply { this[MeasurementType.WEIGHT] = Kg(weightKg) }

        if (impedanceOhms in PLAUSIBLE_IMPEDANCE_OHMS && user.bodyHeight > 0f) {
            val bia = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = impedanceOhms
            )
            m[MeasurementType.IMPEDANCE] = Ohm(impedanceOhms.toFloat())
            m[MeasurementType.BODY_FAT] = Percent(bia.totalFatPercentage.toFloat())
            m[MeasurementType.WATER] = Percent(bia.totalBodyWaterPercentage.toFloat())
            m[MeasurementType.MUSCLE] = Percent(bia.skeletalMusclePercentage.toFloat())
            m[MeasurementType.BONE] = Kg(bia.boneMassKg.toFloat())
            m[MeasurementType.BMR] = Kcal(bia.basalMetabolicRate.toFloat())
            m[MeasurementType.LBM] = Kg(bia.fatFreeMassKg.toFloat())
            logD("publish kg=${m[MeasurementType.WEIGHT]} impedance=$impedanceOhms fat=${m[MeasurementType.BODY_FAT]} water=${m[MeasurementType.WATER]} muscle=${m[MeasurementType.MUSCLE]} bone=${m[MeasurementType.BONE]} bmr=${m[MeasurementType.BMR]} lbm=${m[MeasurementType.LBM]}")
        } else {
            logD("publish kg=${m[MeasurementType.WEIGHT]} impedance=$impedanceOhms out of plausible range, skipping body composition")
        }

        publish(m)
    }

    /** Identical 8-byte config write to [ExcelvanCF36xHandler.buildUserConfig] — same chip family. */
    private fun buildUserConfig(user: ScaleUser): ByteArray {
        val sex = if (user.gender.isMale()) 0x01 else 0x00
        val activity = when (user.activityLevel) {
            ActivityLevel.SEDENTARY,
            ActivityLevel.MILD -> 0x00
            ActivityLevel.MODERATE -> 0x01
            ActivityLevel.HEAVY,
            ActivityLevel.EXTREME -> 0x02
        }
        val height = user.bodyHeight.roundToInt().coerceIn(0, 255)
        val age = user.age.coerceIn(0, 255)
        val unit = when (user.scaleUnit) {
            WeightUnit.KG -> 0x01
            WeightUnit.LB -> 0x02
            WeightUnit.ST -> 0x04
        }

        val cfg = byteArrayOf(
            0xFE.toByte(), 0x01, sex.toByte(), activity.toByte(),
            height.toByte(), age.toByte(), unit.toByte(), 0x00
        )
        var xor = 0
        for (i in 1..6) xor = xor xor (cfg[i].toInt() and 0xFF)
        cfg[7] = xor.toByte()
        return cfg
    }
}
