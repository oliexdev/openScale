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
import com.health.openscale.core.bluetooth.libs.YunmaiLib
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Yunmai (SE/Mini) – minimal, event-driven implementation.
 * The base class handles sequencing/IO; this class only knows packets & parsing.
 */
class YunmaiHandler(
    private val isMini: Boolean = true // Mini sends fat sometimes inline; SE usually needs calc
) : ScaleDeviceHandler() {
    private var lastMeasurement : ScaleMeasurement? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        val matches = when {
            isMini  -> name.startsWith("YUNMAI-SIGNAL") || name.startsWith("YUNMAI-ISM")
            else    -> name.startsWith("YUNMAI-ISSE")
        }
        if (!matches) return null

        // Known from reverse engineering:
        val caps = buildSet {
            add(DeviceCapability.BODY_COMPOSITION)
            add(DeviceCapability.TIME_SYNC)
            add(DeviceCapability.USER_SYNC)
            add(DeviceCapability.HISTORY_READ)
            add(DeviceCapability.UNIT_CONFIG)
        }

        // What we currently implement in our handler (start klein und ehrlich):
        val impl = buildSet {
            add(DeviceCapability.BODY_COMPOSITION)
            // TIME_SYNC / USER_SYNC / HISTORY_READ can be added as we implement them
        }

        return DeviceSupport(
            displayName = if (isMini) "Yunmai Mini" else "Yunmai SE",
            capabilities = caps,
            implemented = impl,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // GATT layout used by Yunmai SE/Mini
    private val SVC_MEAS: UUID = uuid16(0xFFE0)
    private val CHR_MEAS: UUID = uuid16(0xFFE4)
    private val SVC_CMD:  UUID = uuid16(0xFFE5)
    private val CHR_CMD:  UUID = uuid16(0xFFE9)

    override fun onConnected(user: ScaleUser) {
        // 1) Send user profile
        writeTo(SVC_CMD, CHR_CMD, buildUserPacket(user))

        // 2) Send current time (seconds since epoch, BE)
        writeTo(SVC_CMD, CHR_CMD, buildSetTimePacket())

        // 3) Enable notifications for measurement data
        setNotifyOn(SVC_MEAS, CHR_MEAS)

        // 4) Start measurement and ask user to step on scale
        writeTo(SVC_CMD, CHR_CMD, MAGIC_START)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_MEAS) return

        // Yunmai marks final frame with data[3] == 0x02
        if (data[3] != 0x02.toByte()) {
            // live/unstable updates – ignore for now
            return
        }

        val measurement = parseFinal(user, data) ?: run {
            logW("Could not parse final Yunmai frame")
            return
        }

        if (lastMeasurement != null && isDuplicateMeasurement(measurement, lastMeasurement!!)) {
            logI("Duplicate measurement skipped: weight=${measurement.weight}kg, fat=${measurement.fat}")
            return
        }

        publish(measurement) // base will take it from here
        lastMeasurement = measurement
        logI("Measurement published: weight=${measurement.weight} kg, fat=${measurement.fat}")
    }

    // --- Packet builders ------------------------------------------------------

    private fun buildUserPacket(user: ScaleUser): ByteArray {
        // Yunmai expects: 0D 12 10 01 00 00  [uid_hi uid_lo] [height] [sex] [age] 55 5A 00 00 [unit] [activity] [xor]
        val uid16 = (user.id.takeIf { it > 0 } ?: 1) and 0xFFFF
        val uidBe = ConverterUtils.toInt16Be(uid16)

        val sex: Byte = if (user.gender.isMale()) 0x01 else 0x02
        // Stones are sent as LB on the device; vendor app converts later.
        val unit: Byte = if (user.scaleUnit == WeightUnit.KG) 0x01 else 0x02
        val activity: Byte = YunmaiLib.toYunmaiActivityLevel(user.activityLevel).toByte()

        val payload = byteArrayOf(
            0x0D, 0x12, 0x10, 0x01, 0x00, 0x00,
            uidBe[0], uidBe[1],
            user.bodyHeight.toInt().toByte(),
            sex,
            user.age.toByte(),
            0x55, 0x5A, 0x00, 0x00,
            unit, activity,
            0x00 // checksum placeholder
        )
        payload[payload.lastIndex] = xorChecksum(payload, start = 1, endExclusive = payload.lastIndex)
        return payload
    }

    private fun buildSetTimePacket(): ByteArray {
        // 0D 0D 11 [unix_time_be(4)] 00 00 00 00 00 00 [xor]
        val unixBe = ConverterUtils.toInt32Be(System.currentTimeMillis() / 1000L)
        val payload = byteArrayOf(
            0x0D, 0x0D, 0x11,
            unixBe[0], unixBe[1], unixBe[2], unixBe[3],
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        // Extend with checksum byte
        val withCrc = payload + 0x00
        withCrc[withCrc.lastIndex] = xorChecksum(withCrc, start = 1, endExclusive = withCrc.lastIndex)
        return withCrc
    }

    // --- Parser ---------------------------------------------------------------

    private fun parseFinal(user: ScaleUser, frame: ByteArray): ScaleMeasurement? {
        // Timestamp (BE u32) at offset 5; weight (BE u16)/100 at offset 13
        var tsMillis = ConverterUtils.fromUnsignedInt32Be(frame, 5) * 1000L
        val weightKg = ConverterUtils.fromUnsignedInt16Be(frame, 13) / 100.0f

        if (weightKg <= 0f || !weightKg.isFinite()) return null

        val earliestPlausibleTimestamp = 315532800000L // Corresponds to Jan 1, 1980 00:00:00 UTC

        if (tsMillis < earliestPlausibleTimestamp) {
            val invalidDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(tsMillis))
            logW("Implausible timestamp received from scale ($invalidDateStr), falling back to current system time.")
            tsMillis = System.currentTimeMillis()
        }

        val m = ScaleMeasurement().apply {
            dateTime = Date(tsMillis)
            weight = weightKg
        }

        if (isMini) {
            // Mini: resistance at 15; sometimes fat included at 17
            val resistance = ConverterUtils.fromUnsignedInt16Be(frame, 15)
            val protocolVer = frame[1].toInt() and 0xFF

            val sexInt = if (user.gender.isMale()) 1 else 0
            val yunmai = YunmaiLib(sexInt, user.bodyHeight, user.activityLevel)

            val fatPct: Float = if (protocolVer >= 0x1E) {
                // Embedded fat percentage (BE u16)/100
                ConverterUtils.fromUnsignedInt16Be(frame, 17) / 100.0f
            } else {
                yunmai.getFat(user.age, weightKg, resistance)
            }

            if (fatPct > 0f && fatPct.isFinite()) {
                m.fat = fatPct
                m.muscle = yunmai.getMuscle(fatPct)
                m.water = yunmai.getWater(fatPct)
                m.bone = yunmai.getBoneMass(m.muscle, weightKg)
                m.lbm = yunmai.getLeanBodyMass(weightKg, fatPct)
                m.visceralFat = yunmai.getVisceralFat(fatPct, user.age)
            } else {
                logW("Body fat is zero/invalid (prot=$protocolVer, R=$resistance)")
            }
        }

        return m
    }

    // --- Utils ----------------------------------------------------------------

    private fun xorChecksum(bytes: ByteArray, start: Int, endExclusive: Int): Byte {
        var acc = 0
        for (i in start until endExclusive) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc.toByte()
    }

    private fun isDuplicateMeasurement(new: ScaleMeasurement, existing: ScaleMeasurement): Boolean {
        val timeThresholdMs = 2000 // 2 sec tolerance
        val valueTolerance = 0.01  // 10 g tolerance

        val newTime = new.dateTime?.time ?: 0L
        val existingTime = existing.dateTime?.time ?: 0L
        val timeDiff = abs(newTime - existingTime)
        if (timeDiff > timeThresholdMs) return false

        if (abs(new.weight - existing.weight) > valueTolerance) return false
        if (abs(new.fat - existing.fat) > valueTolerance) return false
        if (abs(new.water - existing.water) > valueTolerance) return false
        if (abs(new.muscle - existing.muscle) > valueTolerance) return false
        if (abs(new.bone - existing.bone) > valueTolerance) return false
        if (abs(new.visceralFat - existing.visceralFat) > valueTolerance) return false

        return true
    }

    private val MAGIC_START = byteArrayOf(0x0D, 0x05, 0x13, 0x00, 0x16)
}
