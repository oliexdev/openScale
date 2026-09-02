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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

/**
 * Active Era BF-06 (aka "BS-06") GATT handler.
 *
 * Protocol highlights:
 * - Service 0xFFB0
 * - Write characteristic 0xFFB1 (config / commands)
 * - Notify characteristic 0xFFB2 (data stream)
 * - All packets are 20 bytes, start with MAGIC 0xAC, packet type at index 0x12.
 *
 * Packets:
 *  D5 = live weight (flags indicate "stabilized")
 *  D0 = balance measurement (L/R)
 *  D6 = "ADC"/impedance report (may need formula tweak)
 *  D7 = heart rate
 *  D8 = historical record
 */
class ActiveEraBF06Handler : ScaleDeviceHandler() {

    companion object {
        private const val DEVICE_NAME = "AE BS-06"
    }

    // --- GATT UUIDs -----------------------------------------------------------
    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHR_WRITE: UUID = uuid16(0xFFB1)
    private val CHR_NOTIFY: UUID = uuid16(0xFFB2)

    // Packet framing
    private val MAGIC: Byte = 0xAC.toByte()
    private val DEVICE_TYPE: Byte = 0x27

    // Session state
    private var weightStabilized = false
    private var stableWeightKg = 0.0f

    private var balanceStabilized = false
    private var stableBalanceLeftPct = 0.0f

    private var supportsHR = false
    private var supportsPH = false
    private var impedanceOhm: Double = 0.0

    private var pending: ScaleMeasurement? = null

    // --- Capability declaration ----------------------------------------------
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.equals(DEVICE_NAME, ignoreCase = true)) return null

        return DeviceSupport(
            displayName = "Active Era BF-06",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.HISTORY_READ // D8 packets
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.HISTORY_READ
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Connection lifecycle -------------------------------------------------
    override fun onConnected(user: ScaleUser) {
        logD("onConnected → set notify and push config")
        // Reset session state
        weightStabilized = false
        balanceStabilized = false
        stableWeightKg = 0f
        stableBalanceLeftPct = 0f
        supportsHR = false
        supportsPH = false
        impedanceOhm = 0.0
        pending = ScaleMeasurement()

        // Enable notifications then send configuration right away
        setNotifyOn(SERVICE, CHR_NOTIFY)
        sendConfiguration(user)

        // Hint for the user
        userInfo(R.string.bt_info_step_on_scale, 0)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY) return
        decodePacket(data, user)
    }

    override fun onDisconnected() {
        logD("onDisconnected")
    }

    // --- Protocol: TX ---------------------------------------------------------
    private fun sendConfiguration(user: ScaleUser) {
        val packet = buildConfigurationPacket(user)
        logD("→ send config ${packet.toHexPreview(20)}")
        writeTo(SERVICE, CHR_WRITE, packet, withResponse = true)
    }

    /**
     * Builds the 20-byte configuration packet.
     *
     * Layout (indexes):
     *  0  : MAGIC (0xAC)
     *  1  : DEVICE_TYPE (0x27)
     *  2..5  : Unix time (seconds, BE)
     *  6  : constant 0x04 (observed)
     *  7  : unit (0=kg,1=lb,2=st)
     *  8  : userId on scale (we fix to 0x01 for now)
     *  9  : height (cm)
     *  10..11: initial weight *100 (kg, BE)
     *  12 : age
     *  13 : gender (1=male,2=female)  // matched to legacy code
     *  14..15: target weight *100 (kg, BE)
     *  16 : 0x03 (observed)
     *  17 : 0x00 (observed)
     *  18 : 0xD0 (observed)
     *  19 : checksum (sum over [2 .. 16], i.e. indexes 2..16 inclusive)
     */
    private fun buildConfigurationPacket(user: ScaleUser): ByteArray {
        val nowSec = (System.currentTimeMillis() / 1000L)
        val time = int32be(nowSec)

        val heightCm = user.bodyHeight.coerceAtLeast(0f)
        val age = user.age.coerceAtLeast(0)
        val gender = if (user.gender.isMale()) 0x01 else 0x02

        val units = when (user.scaleUnit) {
            WeightUnit.KG -> 0
            WeightUnit.LB -> 1
            WeightUnit.ST -> 2
        }

        val initW = (ceil(user.initialWeight * 100.0)).toInt().coerceIn(0, 0xFFFF)
        val initWbe = int16be(initW)

        val targetW = if (user.goalWeight > 0f)
            (ceil(user.goalWeight * 100.0)).toInt().coerceIn(0, 0xFFFF)
        else
            initW
        val targetWbe = int16be(targetW)

        val pkt = byteArrayOf(
            MAGIC,
            DEVICE_TYPE,
            time[0], time[1], time[2], time[3],
            0x04,
            units.toByte(),
            0x01, // scale user id (fixed)
            (heightCm.toInt() and 0xFF).toByte(),
            initWbe[0], initWbe[1],
            (age and 0xFF).toByte(),
            (gender and 0xFF).toByte(),
            targetWbe[0], targetWbe[1],
            0x03,
            0x00,
            0xD0.toByte(),
            0x00 // checksum placeholder
        )
        // original legacy code summed 2..(len-3). We keep that to stay protocol-compatible.
        pkt[pkt.lastIndex] = sumChecksum(pkt, toExclusive = pkt.size - 3)
        return pkt
    }

    // --- Protocol: RX ---------------------------------------------------------
    private fun decodePacket(pkt: ByteArray, user: ScaleUser) {
        if (pkt.size != 20) {
            logD("drop: invalid length ${pkt.size}")
            return
        }
        if (pkt[0] != MAGIC) {
            logD("drop: wrong MAGIC ${String.format("%02X", pkt[0])}")
            return
        }

        val type = pkt[0x12].toInt() and 0xFF
        when (type) {
            0xD5 -> handleWeight(pkt)
            0xD0 -> handleBalance(pkt)
            0xD6 -> handleAdcImpedance(pkt, user)
            0xD7 -> handleHeartRate(pkt)
            0xD8 -> handleHistorical(pkt)
            else -> logD("unhandled packet type=0x${type.toString(16).uppercase()} ${pkt.toHexPreview(20)}")
        }
    }

    /** D5: live weight report, flags at index 0x02. */
    private fun handleWeight(pkt: ByteArray) {
        val flags = pkt[0x02]
        val stabilized = bit(flags, 8)
        supportsHR = bit(flags, 2)
        supportsPH = bit(flags, 3)

        // 24-bit BE, mask 18 bits (0..17), then /1000 → kg
        val grams18 = (u24be(pkt, 3) and 0x3FFFF)
        val weightKg = grams18 / 1000.0f

        if (stabilized && !weightStabilized) {
            weightStabilized = true
            stableWeightKg = weightKg
            logI("Stable weight: %.3f kg".format(stableWeightKg))
            sendMeasuringSnack(weightKg)
            ensurePending()[MeasurementType.WEIGHT] = Kg(weightKg)

            // We can publish later once we have HR/ADC (if provided)
            maybePublishIfComplete()
        } else if (!stabilized) {
            sendMeasuringSnack(weightKg)
        }
    }

    /** D0: balance (left/right) report. */
    private fun handleBalance(pkt: ByteArray) {
        val state = pkt[0x02].toInt() and 0xFF
        val isFinal = (state == 0x01)

        val weightL = (u16be(pkt, 3) / 100.0f)
        val pctL = (u16be(pkt, 5) / 10.0f)

        if (isFinal && !balanceStabilized) {
            balanceStabilized = true
            stableBalanceLeftPct = pctL
            logI("Stable balance: L %.1f%% / R %.1f%% (L=%.2f kg)".format(pctL, 100f - pctL, weightL))
            maybePublishIfComplete()
        }
    }

    /** D6: ADC/impedance report. */
    private fun handleAdcImpedance(pkt: ByteArray, user: ScaleUser) {
        val number = pkt[0x02].toInt() and 0xFF
        if (number != 1) {
            logD("ADC packet unsupported count=$number")
            return
        }
        var imp = u16be(pkt, 4).toDouble()
        // Same correction as legacy implementation (empirical)
        if (imp >= 1500.0) {
            imp = (((imp - 1000.0) + ((stableWeightKg * 10.0) * (-0.4))) / 0.6) / 10.0
        }
        impedanceOhm = imp
        logI("Impedance: %.1f Ω".format(impedanceOhm))

        if (impedanceOhm > 10.0 && stableWeightKg > 0f) {
            // scales report back the algorithm for calculating BIA data
            val reportedAlg = pkt[0x11].toInt()
            when (reportedAlg) {
                0x07 -> {
                    val calc = LibICBIACalculatorWLA07(stableWeightKg, user.bodyHeight.toInt(), impedanceOhm, user.age, user.gender.isMale())
                    ensurePending().apply {
                        val bodyFat = calc.bodyFatPercent.toFloat()
                        this[MeasurementType.BODY_FAT] = Percent(bodyFat)
                        this[MeasurementType.MUSCLE] = Percent(calc.musclePercent.toFloat())
                        this[MeasurementType.VISCERAL_FAT] = calc.visceralFat.toFloat()
                        this[MeasurementType.BONE] = Kg(calc.boneMass.toFloat())
                        this[MeasurementType.WATER] = Percent(calc.moisturePercent.toFloat())
                        this[MeasurementType.LBM] = Kg((stableWeightKg * (100.0 - bodyFat) / 100.0).toFloat())
                        this[MeasurementType.BMR] = Kcal(calc.bMR.toFloat())
                        this[MeasurementType.PROTEIN] = Percent(calc.protein.toFloat())

                        // Store the raw impedance so body composition can be recomputed later.
                        this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhm.toFloat())
                    }
                }
                else -> {
                    logW("unsupported alg=$reportedAlg. Fallback to estimating BIA")
                    // Simple BIA estimate (from legacy note). Replace once we have reverse-engineered vendor formulas.
                    val ffm = estimateFatFreeMass(
                        heightCm = user.bodyHeight.toInt(),
                        weightKg = stableWeightKg,
                        impedance = impedanceOhm,
                        age = user.age,
                        isMale = user.gender.isMale()
                    )
                    val fatKg = (stableWeightKg - ffm).coerceAtLeast(0.0)
                    val fatPct = if (stableWeightKg > 0) (fatKg / stableWeightKg) * 100.0 else 0.0
                    ensurePending().apply {
                        this[MeasurementType.LBM] = Kg(ffm.toFloat())
                        this[MeasurementType.BODY_FAT] = Percent(fatPct.toFloat())
                        // Store the raw impedance so body composition can be recomputed later.
                        this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhm.toFloat())
                        // Optional: rough water/muscle estimates could be added if desired
                    }
                }
            }
        }
        maybePublishIfComplete()
    }

    /** D7: heart rate. */
    private fun handleHeartRate(pkt: ByteArray) {
        val hrBpm = pkt[0x03].toInt() and 0xFF
        logI("Heart rate: $hrBpm bpm")
        ensurePending().apply {
            this[MeasurementType.HEART_RATE] = Bpm(hrBpm)
        }

        maybePublishIfComplete()
    }

    /** D8: historical record (not persisted yet, just logged). */
    private fun handleHistorical(pkt: ByteArray) {
        val ts = Instant.ofEpochSecond(u24be(pkt, 3).toLong())
        val weight = (u24be(pkt, 0x08) and 0x03FFFF) / 1000.0f
        val leftKg = u16be(pkt, 0x0B) / 100.0f
        val hr = pkt[0x0D].toInt() and 0xFF
        val adc = u16be(pkt, 0x0F).toInt()
        logI("Historical: ${ts}  weight=%.3fkg  left=%.2fkg  hr=%d  adc=%d".format(weight, leftKg, hr, adc))
        // TODO: if you want to store history, accumulate and publish here.
    }

    // --- Publication logic ----------------------------------------------------
    /**
     * Decide when to publish:
     * - weight must be stabilized
     * - if the device signals HR support, wait for D7
     * - impedance is optional; if present we publish with fat/lbm, else weight-only
     */
    private fun maybePublishIfComplete() {
        if (!weightStabilized) return

        val m = ensurePending()

        val needHr = supportsHR
        val haveHr = (m[MeasurementType.HEART_RATE]?.value ?: 0) != 0

        if (needHr && !haveHr) return

        m.apply {
            // If we received balance or HR and you want to store them, extend ScaleMeasurement accordingly.
            // For now we publish standard fields.
            dateTime = java.util.Date() // now
        }
        publish(m)
        logI("published measurement (weight=${m[MeasurementType.WEIGHT]}, fat=${m[MeasurementType.BODY_FAT]}, lbm=${m[MeasurementType.LBM]})")
        // Reset to avoid double-publishing in the same session
        pending = null
    }

    private fun sendMeasuringSnack(weightKg: Float) {
        userInfo(R.string.bluetooth_scale_info_measuring_weight, weightKg)
    }

    private fun ensurePending(): ScaleMeasurement {
        if (pending == null) pending = ScaleMeasurement()
        return pending!!
    }

    // --- Small helpers --------------------------------------------------------

    /** Legacy "sumChecksum": sum of bytes in [2, toExclusive) truncated to 8-bit. */
    private fun sumChecksum(data: ByteArray, toExclusive: Int): Byte {
        var sum = 0
        for (i in 2 until toExclusive) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    /** 16-bit unsigned big-endian → Float */
    private fun u16be(b: ByteArray, off: Int): Float {
        val v = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        return v.toFloat()
    }

    /** 24-bit unsigned big-endian → Int */
    private fun u24be(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 16) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                (b[off + 2].toInt() and 0xFF)
    }

    /** BE int16 encoder */
    private fun int16be(v: Int): ByteArray =
        byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** BE int32 encoder for seconds */
    private fun int32be(v: Long): ByteArray =
        byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )

    /** Bit test with 1-based positions (to mirror legacy isBitSet(byte, pos)). */
    private fun bit(value: Byte, pos1Based: Int): Boolean {
        val mask = 1 shl (pos1Based - 1)
        return (value.toInt() and mask) != 0
    }

    /**
     * Simple BIA estimate (as in the legacy notes)
     *   FFM = 0.36*(H²/Z) + 0.162*H + 0.289*W − 0.134*A + 4.83*G − 6.83
     * Where G=1 for male, 0 for female; H in cm, W in kg, Z in ohm, A in years.
     */
    private fun estimateFatFreeMass(
        heightCm: Int,
        weightKg: Float,
        impedance: Double,
        age: Int,
        isMale: Boolean
    ): Double {
        val G = if (isMale) 1.0 else 0.0
        val H = heightCm.toDouble()
        val W = weightKg.toDouble()
        return 0.36 * ((H.pow(2.0)) / impedance) +
                0.162 * H +
                0.289 * W -
                0.134 * age +
                4.83 * G -
                6.83
    }

    /**
     * Reverse-engineered from libICBodyFatAlgorithms.so, class
     * ICBodyFatAlgorithmWLA07 (algType 6 mapped to ICBFATypeWLA07).
     */
    private class LibICBIACalculatorWLA07 constructor(
        val weightKg: Float,
        val heightCm: Int,
        val impedanceOhm: Double,
        val age: Int,
        val isMale: Boolean
    ) {
        private val bfrRaw = bfrRaw()
        private val muscleRaw = muscleRaw()

        fun roundToOneDecimalPlace(value: Double): Double {
            var fVar2 = (value % 1.0) * 10.0
            if ((fVar2 % 1.0) > 0.5) {
                fVar2 = ceil(fVar2)
            } else {
                fVar2 = floor(fVar2)
            }
            return (value.toInt()) + (fVar2 / 10.0)
        }

        fun clamp(value: Double, min: Double, max: Double): Double {
            return min(max(value, min), max)
        }

        /** The shared 5-term regression: (height*A + weight*B + age*C + imp*D + E) / 10000  */
        fun regress(row: Int, heightCm: Int, weightKg: Double, age: Int, impedanceOhm: Double): Double = TABLE[row].let {
            (heightCm * it.heightCoef + weightKg * it.weightCoef + age * it.ageCoef + impedanceOhm * it.impCoef + it.const) / 10000.0
        }

        val bMI: Double
            get() {
                val bmi = (weightKg * 10000.0) / (heightCm * heightCm)
                return clamp(bmi, 4.0, 185.5)
            }

        /** bfr regression clamped to [5,45].  */
        fun bfrRaw(): Double {
            val raw = (regress(
                if (isMale) 1 else 0,
                heightCm,
                weightKg.toDouble(),
                age,
                impedanceOhm
            ) / weightKg) * 100.0
            if (raw <= 45.0) {
                return max(raw, 5.0)
            }
            return 45.0
        }

        val bodyFatPercent: Double
            get() = roundToOneDecimalPlace(bfrRaw)

        fun getSubcutaneousFatPercent(bfrPercent: Double): Double {
            return roundToOneDecimalPlace(bfrPercent * (-0.0002 * bfrPercent + 0.72))
        }

        /** Absolute (kg-ish) muscle mass from the row2/3 regression, with an FFM-residual correction band.  */
        fun muscleRaw(): Double {
            val bfr = clamp(bfrRaw, 5.0, 45.0)
            val muscleRegression = regress(
                if (isMale) 3 else 2,
                heightCm,
                weightKg.toDouble(),
                age,
                impedanceOhm
            ) / 10.0
            val ffmResidual = weightKg * (1.0 - bfr / 100.0) - muscleRegression
            if (ffmResidual >= 4.0) {
                return muscleRegression + ffmResidual - 4.0
            }
            if (ffmResidual > 1.0) {
                return muscleRegression
            }
            return muscleRegression + ffmResidual - 1.0
        }

        val musclePercent: Double
            get() = roundToOneDecimalPlace(muscleRaw / weightKg * 100.0)

        val boneMass: Double
            get() {
                val muscle = muscleRaw
                val bfr = clamp(bfrRaw, 5.0, 45.0)
                val residual = weightKg - (bfr * weightKg) / 100.0 - muscle
                return roundToOneDecimalPlace(clamp(residual, 1.0, 4.0))
            }

        val visceralFat: Double
            /** Visceral fat uses its own quirky round-to-nearest-5 (not 0.1) step before the final clamp.  */
            get() {
                val raw = regress(
                    if (isMale) 9 else 8,
                    heightCm,
                    weightKg.toDouble(),
                    age,
                    impedanceOhm
                ) * 10.0
                val truncated = raw.toInt()
                val base = (truncated / 10) * 10
                val rounded = if (truncated % 10 < 6) base else base + 5
                return roundToOneDecimalPlace(clamp(rounded / 10.0, 1.0, 59.0))
            }

        val bMR: Int
            get() {
                val raw =
                    regress(if (isMale) 7 else 6, heightCm, weightKg.toDouble(), age, impedanceOhm)
                return Math.round(clamp(raw, 400.0, 3500.0)).toInt()
            }

        val physicalAge: Int
            get() {
                if (age <= 14) {
                    return age
                }
                val raw = regress(
                    if (isMale) 11 else 10,
                    heightCm,
                    weightKg.toDouble(),
                    age,
                    impedanceOhm
                )
                val physicalAge = clamp(raw, Double.NEGATIVE_INFINITY, 80.0).toInt()
                return max(physicalAge, 15)
            }

        /** Shared by water% and protein%: row4/5 regression run through an FFM-residual correction band.  */
        fun moistureCorrected(): Double {
            val muscle = muscleRaw
            val musclePercent = muscle / weightKg * 100.0
            val raw = regress(
                if (isMale) 5 else 4,
                heightCm,
                weightKg.toDouble(),
                age,
                impedanceOhm
            ) / weightKg
            val residual = musclePercent - raw
            if (residual >= 32.0) {
                return musclePercent - 32.0
            }
            if (residual > 5.0) {
                return raw
            }
            return musclePercent - 5.0
        }

        val moisturePercent: Double
            get() {
                val corrected = moistureCorrected()
                return roundToOneDecimalPlace(clamp(corrected, 20.0, 85.0))
            }

        val protein: Double
            get() {
                val muscle = muscleRaw
                val waterRaw = clamp(
                    regress(
                        if (isMale) 5 else 4,
                        heightCm,
                        weightKg.toDouble(),
                        age,
                        impedanceOhm
                    ) / weightKg, 20.0, 85.0
                )
                val protein = muscle / weightKg * 100.0 - waterRaw
                return roundToOneDecimalPlace(clamp(protein, 5.0, 32.0))
            }

        val skeletalMuscleMass: Double
            get() {
                val muscle = muscleRaw
                val sexFlag = if (isMale) 1.0 else 0.0
                var raw =
                    (impedanceOhm * -0.017 + weightKg * 0.1745 + heightCm * 0.2573 + sexFlag * 2.4269) - age * 0.0161 - 20.2165
                val ratio = raw / muscle
                if (ratio >= 0.7) {
                    raw = muscle * 0.7
                } else if (ratio <= 0.45) {
                    raw = muscle * 0.45
                }
                return roundToOneDecimalPlace(raw / weightKg * 100.0)
            }

        companion object {
            private data class TableRow(
                val heightCoef: Int,
                val weightCoef: Int,
                val ageCoef: Int,
                val impCoef: Int,
                val const: Int,
            )

            private val TABLE = arrayOf(
                /* row 0: bfr, female */
                TableRow(-3332, 7509, 196, 72, 227193),
                /* row 1: bfr, male */
                TableRow(-3315, 6216, 183, 85, 225540),
                /* row 2: muscleRaw, female */
                TableRow(31860, 19340, -2060, -1320, -1645560),
                /* row 3: muscleRaw, male */
                TableRow(28670, 38940, -4080, -1235, -1576650),
                /* row 4: water/protein, female */
                TableRow(87700, 297300, 12800, -6030, 517500),
                /* row 5: water/protein, male */
                TableRow(93900, 375800, -3200, -6925, 97000),
                /* row 6: bmr, female */
                TableRow(75432, 99474, -34382, -3090, -2882821),
                /* row 7: bmr, male */
                TableRow(75037, 131523, -43376, -3486, -3117751),
                /* row 8: visceralFat, female */
                TableRow(-1651, 2628, 649, 24, 123445),
                /* row 9: visceralFat, male   */
                TableRow(-2675, 4200, 1462, 123, 139871),
                /* row 10: physicalAge, female */
                TableRow(-11165, 15784, 4615, 415, 832548),
                /* row 11: physicalAge, male   */
                TableRow(-7471, 9161, 4184, 517, 542267),
            )
        }
    }
}
