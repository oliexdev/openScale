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
import com.health.openscale.core.bluetooth.libs.EtekcityLib
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

// Based on https://github.com/ronnnnnnnnnnnnn/etekcity_esf551_ble

/**
 * Etekcity ESF-551 scale handler
 */
class EtekcityESF551Handler : ScaleDeviceHandler() {

    companion object {
        private val SCALE_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val WEIGHT_CHARACTERISTIC_NOTIFY = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val ALIRO_CHARACTERISTIC = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        private val DEVICE_INFORMATION_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val HW_REVISION_STRING_CHARACTERISTIC = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        private val SW_REVISION_STRING_CHARACTERISTIC = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.startsWith("Etekcity Smart Fitness Scale", ignoreCase = true)) {
            return null
        }

        return DeviceSupport(
            displayName = device.name,
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.UNIT_CONFIG
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.UNIT_CONFIG
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logI("ESF-551 connected, starting setup sequence")

        setUnit(user.scaleUnit)

        setNotifyOn(SCALE_SERVICE, WEIGHT_CHARACTERISTIC_NOTIFY)
        logD("Enabled notifications on weight characteristic")

        userInfo(R.string.bt_info_waiting_for_measurement)
    }

    override fun onDisconnected() {
        logI("ESF-551 disconnected")
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return

        if (characteristic == WEIGHT_CHARACTERISTIC_NOTIFY) {
            val measurement = parsePayload(data, user)
            if (measurement != null) {
                publish(measurement)
                requestDisconnect()
            }
            return
        }

        logD("Notify $characteristic len: ${data.size} data: ${data.toHexPreview(32)}")
    }

    private fun parsePayload(data: ByteArray, user: ScaleUser): ScaleMeasurement? {
        if (data.size != 22 ||
            data[0] != 0xa5.toByte() ||
            data[1] != 0x02.toByte() ||
            data[3] != 0x10.toByte() ||
            data[4] != 0x00.toByte() ||
            data[6] != 0x01.toByte() ||
            data[7] != 0x61.toByte() ||
            data[8] != 0xa1.toByte() ||
            data[9] != 0x00.toByte()
        ) {
            logD("Invalid frame: len: ${data.size} data: ${data.toHexPreview(32)}")
            return null
        }

        val weightRaw = data[10].toUInt() or data[11].toUInt().shl(8) or data[12].toUInt().shl(16)
        val weightKg = weightRaw.toInt() / 1000.0
        val impedance = (data[13].toUInt() or data[14].toUInt().shl(8)).toDouble()
//        val displayUnit = WeightUnit.fromInt(data[21].toInt())
        val measurement = ScaleMeasurement(
            userId = user.id,
            dateTime = Date(),
            weight = weightKg.toFloat(),
            impedance = impedance,
        )

        if (impedance > 0) {
            val lib = EtekcityLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg,
                heightM = user.bodyHeight / 100.0,
                impedance = impedance,
            )
            measurement.fat = lib.bodyFatPercentage.toFloat()
            measurement.water = lib.water.toFloat()
            measurement.muscle = lib.skeletalMusclePercentage.toFloat()
            measurement.visceralFat = lib.visceralFat.toFloat()
            measurement.bone = lib.boneMass.toFloat()
            measurement.bmr = lib.basalMetabolicRate.toFloat()

            // TODO: Add other measurements once supported
//            measurement.fatFreeWeight = lib.fatFreeWeight.toFloat()
//            measurement.subcutaneousFat = lib.subcutaneousFat.toFloat()
//            measurement.muscleMass = lib.muscleMass.toFloat()
//            measurement.proteinPercentage = lib.proteinPercentage.toFloat()
//            measurement.weightScore = lib.weightScore
//            measurement.fatScore = lib.fatScore
//            measurement.bmiScore = lib.bmiScore
//            measurement.healthScore = lib.healthScore
//            measurement.metabolicAge = lib.metabolicAge
        }

        if (data[20] == 1.toByte() && impedance > 0) {
            logD("Final measurement: $measurement")
            return measurement
        }

        return null
    }

    private fun setUnit(unit: WeightUnit) {
        val unitByte = unit.toInt().toByte()
        val cmd = byteArrayOf(
            0xa5.toByte(),
            0x22,
            0x03,
            0x05,
            0x00,
            (43 - unitByte).toByte(),
            0x01,
            0x63,
            0xa1.toByte(),
            0x00,
            unitByte
        )
        writeTo(SCALE_SERVICE, ALIRO_CHARACTERISTIC, cmd, withResponse = false)
        logD("Unit update command sent: $unit")
    }
}
