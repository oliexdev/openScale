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
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

// Based on https://github.com/ronnnnnnnnnnnnn/etekcity_esf551_ble

/**
 * Etekcity Fit 8S scale handler
 *
 * The device supports Broadcasts only. It puts data in ManufacturerSpecific data
 * advertisement with key `0x06D0` for "Etekcity Corporation". It also adds `SCALE_SERIVCE` 
 * uuid defined below.
 *
 * Data structure (from ronnnnnnnnnnnnn code):
 * - `[0]`    : header byte (`0x01`) (ignored)
 * - `[1:7]`   : device MAC address, little-endian (ignored)
 * - `[7:10]`  : unknown (ignored)
 * - `[10:13]` : weight in grams, 3-byte little-endian int
 * - `[13:15]` : bioelectrical impedance in ohms, 2-byte little-endian int (0 = not measured)
 * - `[15]`   : stability flag (`0x01` = stable reading)
 * - `[16]`   : display unit (`0x00`=kg, `0x01`=lb, `0x02`=st) 
 *                  (ignored since incoming data always in grams 
 *                   and no way to change device settings remotely)
 * - `[17:20]` : unknown/constant (ignored)
 * 
 */
class EtekcityFit8SHandler : ScaleDeviceHandler() {
    companion object {
        private const val MANUFACTURER_ID = 0x06D0
        private val SCALE_SERVICE = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")

        /** Bytes the layout documented above needs; shorter records are not a measurement. */
        private const val PAYLOAD_SIZE = 20
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // device return data in manufacturer data with MANUFACTURER_ID key
        if (device.manufacturerData == null || !device.manufacturerData.contains(MANUFACTURER_ID)) {
            logD("No manufacturer data or manufacturer id is incorrect, ignoring device")
            return null
        }

        // all messages from device have service UUID 16: 0xffd0
        if (!device.serviceUuids.contains(SCALE_SERVICE)) {
            logD("Does not contain correct service uuid, ignoring device")
            return null
        }

        return DeviceSupport(
            displayName = "Etekcity Fit 8S",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            linkMode = LinkMode.BROADCAST_ONLY,
        )
    }
    
    
    /**
     * Handle a single advertisement; return a BroadcastAction to steer the adapter.
     */
    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val msd = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED

        // Only the record under our own company id carries the layout documented above; any other
        // record in the same advertisement is a different vendor's data and must not be parsed.
        val payload = msd.get(MANUFACTURER_ID) ?: return BroadcastAction.IGNORED

        if (payload.size < PAYLOAD_SIZE) {
            logD("Manufacturer record too short (${payload.size} bytes), ignoring")
            return BroadcastAction.IGNORED
        }

        if ((payload[15].toInt() and 0xFF) != 1) {
            logD("Measurement not stable yet. Continuing...")

            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        logD("Measurement stabilized.")

        val weightGrams: Int = (payload[10].toInt() and 0xFF) or
                    ((payload[11].toInt() and 0xFF) shl 8) or
                    ((payload[12].toInt() and 0xFF) shl 16)

        if (weightGrams <= 0) {
            // invalid weight
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        val weightKg = weightGrams.toFloat() / 1000.0f

        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = weightKg
        }

        val impedance = (payload[13].toInt() and 0xFF) or
                ((payload[14].toInt() and 0xFF) shl 8)

        if (impedance != 0) {
            m.impedance = impedance.toDouble()

            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = m.weight.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = m.impedance
            )

            m.fat = lib.totalFatPercentage.toFloat()
            m.water = lib.totalBodyWaterPercentage.toFloat()
            m.muscle = lib.skeletalMusclePercentage.toFloat()
            m.bone = lib.boneMassKg.toFloat()
            m.bmr = lib.basalMetabolicRate.toFloat()
            m.lbm = lib.fatFreeMassKg.toFloat()
        } else {
            logD("Impedance is missing or zero. Publishing weight only")
        }

        publish(m)
        return BroadcastAction.CONSUMED_STOP
    }
}
