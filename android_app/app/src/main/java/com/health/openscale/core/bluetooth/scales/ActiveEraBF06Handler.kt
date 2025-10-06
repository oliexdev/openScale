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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.pow

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
    private var hrBpm: Int? = null

    // --- Capability declaration ----------------------------------------------
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Match by name heuristics and/or advertised service UUID
        val name = device.name.lowercase()
        val hasSvc = device.serviceUuids?.any { it == SERVICE } == true
        if (
            hasSvc ||
            name.contains("AE BS-06".lowercase())
        ) {
            return DeviceSupport(
                displayName = "Active Era BF-06",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION, // we compute via simple BIA when possible
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
        return null
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
        hrBpm = null
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
        pkt[pkt.lastIndex] = sumChecksum(pkt, from = 2, toExclusive = pkt.size - 3)
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
            ensurePending().weight = weightKg

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

        if (impedanceOhm > 0.0 && stableWeightKg > 0f) {
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
                lbm = ffm.toFloat()
                fat = fatPct.toFloat()
                // Optional: rough water/muscle estimates could be added if desired
            }
        }
        maybePublishIfComplete()
    }

    /** D7: heart rate. */
    private fun handleHeartRate(pkt: ByteArray) {
        hrBpm = pkt[0x03].toInt() and 0xFF
        logI("Heart rate: ${hrBpm} bpm")
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

        val needHr = supportsHR
        val haveHr = hrBpm != null

        if (needHr && !haveHr) return

        val m = ensurePending().apply {
            // If we received balance or HR and you want to store them, extend ScaleMeasurement accordingly.
            // For now we publish standard fields.
            dateTime = java.util.Date() // now
        }
        publish(m)
        logI("published measurement (weight=${m.weight}, fat=${m.fat}, lbm=${m.lbm})")
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

    /** Legacy "sumChecksum": sum of bytes in [from, toExclusive) truncated to 8-bit. */
    private fun sumChecksum(data: ByteArray, from: Int, toExclusive: Int): Byte {
        var sum = 0
        for (i in from until toExclusive) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
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
}
