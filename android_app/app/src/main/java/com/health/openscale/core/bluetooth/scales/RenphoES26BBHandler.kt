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

import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.Date
import java.util.UUID
import kotlin.math.min
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

/**
 * RENPHO ES-26BB-B GATT handler (legacy: BluetoothES26BBB).
 *
 * Also drives the solar-powered Elis Solar scales, which advertise the model id
 * R-A012 or R-A020 (see the RENPHO manual "solar-powered smart scale") and stream
 * the same 0x55aa "basic flavor" frames over the same 0x1A10 service.
 *
 * Service: 0x1A10
 *  - Notify: 0x2A10  (measurements, state)
 *  - Write : 0x2A11  (commands)
 *
 * Frame format: `55 AA [cmd] [len u16be] [payload] [checksum]`, total size = 6 + len;
 * checksum = sum of all preceding bytes & 0xFF (verified against ES-CS20MB1, R-A012,
 * ES-26BB-B and ESCS20MB2 captures, see renpho-escs20m/x55aa/protocol.py).
 *   cmd 0x14 -> live measurement, payload = [status, weight u32be (0.01 kg), resistance u16be]
 *   cmd 0x15 -> offline record,   payload = [weight u32be, resistance u16be, seconds u32be, …]
 *   cmd 0x11 -> device status,    payload = [power, display unit, byte2, stored count, flag]
 *   cmd 0x90 -> display unit,     payload = [unit, 0, mode, 0]
 *   Status byte: 0x00/0x10 = settling, 0x01/0x11 = final (low nibble 1 = final).
 */
class RenphoES26BBHandler : ScaleDeviceHandler() {

    companion object {
        /** One decoded 0x14 live measurement frame. */
        data class LiveFrame(val weightX100: Long, val resistance: Int) {
            val weightKg: Float get() = weightX100 / 100f
        }

        /** One decoded 0x15 offline record. */
        data class OfflineRecord(val weightX100: Long, val resistance: Int, val secondsAgo: Long) {
            val weightKg: Float get() = weightX100 / 100f
        }

        /**
         * Decode a 0x14 live/final packet. Only final frames are returned (status byte 0x01
         * or 0x11 = final; 0x00/0x10 = settling / BIA zero-current pass); null otherwise.
         */
        fun parseLiveFrame(data: ByteArray): LiveFrame? {
            if (data.size < 12) return null
            val type = data[5]
            val isFinal = (type == 0x01.toByte() || type == 0x11.toByte())
            if (!isFinal) return null
            val weightX100 = ConverterUtils.fromUnsignedInt32Be(data, 6) // kg * 100
            val resistance = ConverterUtils.fromUnsignedInt16Be(data, 10)
            return LiveFrame(weightX100, resistance)
        }

        /** Decode a 0x15 offline packet: weight, resistance and seconds since the measurement. */
        fun parseOfflineRecord(data: ByteArray): OfflineRecord? {
            if (data.size < 15) return null
            val weightX100 = ConverterUtils.fromUnsignedInt32Be(data, 5)   // kg * 100
            val resistance = ConverterUtils.fromUnsignedInt16Be(data, 9)
            val secondsAgo = ConverterUtils.fromUnsignedInt32Be(data, 11)
            return OfflineRecord(weightX100, resistance, secondsAgo)
        }

        // --- Body composition (renpho-escs20m algorithm 0x04, non-athlete) ---------------
        //
        // All formulas ported from renpho-escs20m/body_metrics.py (commit 5b74ab4).
        // They approximate the proprietary Renpho native library (libICBodyFatAlgorithms.so)
        // used by the official app for 4-electrode scales that stream a single impedance
        // value over the 0x55aa basic-flavor protocol (ES-26BB-B, R-A012, R-A016, R-A020).
        //
        // Parameters: weight (kg), height (m), age (years), sex, resistance (Ω).
        // All outputs are clamped to physiologically plausible ranges.

        private fun clamp(v: Float, lo: Float, hi: Float) = maxOf(lo, minOf(hi, v))

        /** Body fat % — linear regression on BMI, age and impedance. */
        fun bodyFatPercent(weightKg: Float, heightM: Float, age: Int, sexIsMale: Float, resistance: Int): Float {
            val bmi = weightKg / (heightM * heightM)
            return if (sexIsMale != 0f)
                clamp(1.524f * bmi + 0.103f * age - 21.992f - 500f / resistance, 1f, 60f)
            else
                clamp(1.545f * bmi + 0.097f * age - 12.689f - 500f / resistance, 1f, 60f)
        }

        /** Water % — derived from body fat via sex-specific linear coefficients. */
        fun waterPercent(bf: Float, sexIsMale: Float) = clamp(
            if (sexIsMale != 0f) 72.202f - 0.72223f * bf else 68.651f - 0.68725f * bf, 20f, 80f)

        /** Skeletal muscle % — derived from body fat. */
        fun skeletalMusclePercent(bf: Float, sexIsMale: Float) = clamp(
            if (sexIsMale != 0f) 64.713f - 0.65508f * bf else 58.390f - 0.58654f * bf, 17.5f, 70f)

        /** Bone mass (kg) — weight minus soft-lean mass minus fat mass. */
        fun boneMass(weightKg: Float, bf: Float, sexIsMale: Float): Float {
            val softLeanPct = clamp(
                if (sexIsMale != 0f) 94.992f - 0.94969f * bf else 93.988f - 0.93960f * bf, 0f, 100f)
            val softLeanKg = clamp(weightKg * softLeanPct / 100f, 3.75f, 110f)
            val bfKg = bf * weightKg / 100f
            return clamp(weightKg - softLeanKg - bfKg, 1f, 7f)
        }

        /** Protein % — derived from body fat. */
        fun proteinPercent(bf: Float, sexIsMale: Float) = clamp(
            if (sexIsMale != 0f) 22.787f - 0.22735f * bf else 25.340f - 0.30245f * bf, 5f, 24f)
    }

    private val SVC get() = uuid16(0x1A10)
    private val CHR_NOTIFY get() = uuid16(0x2A10)
    private val CHR_WRITE get() = uuid16(0x2A11)

    // Start/enable stream “magic” from legacy implementation (fixed, includes checksum)
    private val START_CMD = byteArrayOf(
        0x55, 0xAA.toByte(), 0x90.toByte(), 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x94.toByte()
    )

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim()
        val displayName = when {
            name.equals("ES-26BB-B", ignoreCase = true) -> "RENPHO ES-26BB-B"
            name.equals("R-A012", ignoreCase = true) -> "RENPHO Elis Solar (R-A012)"
            name.equals("R-A020", ignoreCase = true) -> "RENPHO Elis Solar (R-A020)"
            else -> return null
        }

        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ
        )
        // Implemented today: live + offline read + body composition from impedance
        val implemented = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ
        )

        return DeviceSupport(
            displayName = displayName,
            capabilities = capabilities,
            implemented = implemented,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logD("onConnected -> enable notify & send start command")
        setNotifyOn(SVC, CHR_NOTIFY)
        writeTo(SVC, CHR_WRITE, START_CMD, withResponse = true)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY) return
        if (data.isEmpty()) return

        val hex = data.toHexPreview(48)
        logD("notify action=${String.format("%02X", data.getOrNull(2) ?: -1)} $hex")

        if (!isChecksumValid(data)) {
            logD("checksum invalid -> drop frame")
            return
        }

        when (data[2].toInt() and 0xFF) {
            0x14 -> handleLiveMeasurement(data)                // final/realtime, we only save finals
            0x15 -> handleOfflineMeasurement(data)             // includes timestamp delta
            0x11 -> parseScaleInfo(data)                       // power/unit/storedCount/flag
            0x10 -> parseOpCallback(data)                      // success/failure of a prior op
            else -> logD("unknown action=${String.format("%02X", data[2])}")
        }
    }

    // --- Parsers ---------------------------------------------------------------

    /** 0x14 live/final packet. Save only finals (type 0x01 or 0x11). */
    private fun handleLiveMeasurement(data: ByteArray) {
        val frame = parseLiveFrame(data)
        if (frame == null) {
            logD("live measurement (non-final or too short) ignored")
            return
        }

        logD("final weight=${frame.weightKg}kg, impedance=${frame.resistance}")
        saveMeasurement(frame.weightX100, frame.resistance, timestampMs = null)
    }

    /** 0x15 offline packet. Includes seconds elapsed since measurement. */
    private fun handleOfflineMeasurement(data: ByteArray) {
        val record = parseOfflineRecord(data)
        if (record == null) {
            logD("offline measurement too short -> ignored")
            return
        }

        val ts = System.currentTimeMillis() - record.secondsAgo * 1000L
        logD("offline weight=${record.weightKg}kg, impedance=${record.resistance}, ts=$ts")
        saveMeasurement(record.weightX100, record.resistance, ts)

        acknowledgeOfflineMeasurement()
    }

    /** 0x11 device-status frame: power, display unit, stored-record count and an unknown flag. */
    private fun parseScaleInfo(data: ByteArray) {
        if (data.size < 10) return
        val power = data[5].toInt() and 0xFF      // 1=on, 0=shutting down
        val unit = data[6].toInt() and 0xFF       // 1=kg (others unknown)
        val byte2 = data[7].toInt() and 0xFF      // unattributed; reads 1 on captured units
        val storedCount = data[8].toInt() and 0xFF
        val flag = data[9].toInt() and 0xFF       // unattributed; not a battery level

        logD("scale info: power=$power unit=$unit byte2=$byte2 storedCount=$storedCount flag=$flag")
    }

    /** 0x10 generic callback for some operation. */
    private fun parseOpCallback(data: ByteArray) {
        val ok = data.getOrNull(5) == 0x01.toByte()
        logD(if (ok) "operation success" else "operation failure")
    }

    // --- I/O helpers -----------------------------------------------------------

    private fun acknowledgeOfflineMeasurement() {
        // payload = 55 AA 95 00 01 01 <sum>
        val p = byteArrayOf(0x55, 0xAA.toByte(), 0x95.toByte(), 0x00, 0x01, 0x01, 0x00)
        p[p.lastIndex] = sumChecksum(p, 0, p.size - 1)
        writeTo(SVC, CHR_WRITE, p, withResponse = true)
        logD("offline measurement ack sent")
    }

    private fun saveMeasurement(weightX100: Long, resistance: Int, timestampMs: Long?) {
        val weightKg = weightX100 / 100f
        val m = ScaleMeasurement().apply {
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (timestampMs != null) dateTime = Date(timestampMs)
            if (resistance > 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(resistance.toFloat())
                // Body composition: renpho-escs20m algorithm 0x04 (non-athlete).
                val user = currentAppUser()
                val isMale = user.gender.isMale()
                val sexF = if (isMale) 1f else 0f
                val heightM = user.bodyHeight / 100f
                val fat = bodyFatPercent(weightKg, heightM, user.age, sexF, resistance)
                this[MeasurementType.BODY_FAT] = Percent(fat)
                this[MeasurementType.WATER] = Percent(waterPercent(fat, sexF))
                this[MeasurementType.MUSCLE] = Percent(skeletalMusclePercent(fat, sexF))
                this[MeasurementType.BONE] = Kg(boneMass(weightKg, fat, sexF))
                this[MeasurementType.PROTEIN] = Percent(proteinPercent(fat, sexF))
                this[MeasurementType.LBM] = Kg(weightKg * (100f - fat) / 100f)
            }
        }
        publish(m)
    }

    // --- Checksums & utils -----------------------------------------------------

    /** Last byte is checksum = sum(all previous) & 0xFF. */
    private fun isChecksumValid(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val expected = data.last()
        val computed = sumChecksum(data, 0, data.size - 1)
        return expected == computed
    }

    private fun sumChecksum(src: ByteArray, start: Int, endExclusive: Int): Byte {
        var sum = 0
        val end = min(endExclusive, src.size)
        for (i in start until end) sum = (sum + (src[i].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }
}
