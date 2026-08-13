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

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.UUID

/**
 * ScaleupHandler
 *
 * Broadcast-only handler for Scaleup-protocol smart scales that advertise weight
 * and impedance in non-connectable BLE Manufacturer Specific Data.
 *
 * Raw AD layout (after type byte 0xFF, 11 bytes):
 *   [flag:1][weight:2 BE][impedance:2 BE][MAC:6]
 *
 * Android ScanRecord consumes the first two bytes as a little-endian company ID:
 *   key   = (weightMSB << 8) | flag
 *   data  = [weightLSB, impedanceMSB, impedanceLSB, MAC×6]  (9 bytes)
 *
 * Flag values:
 *   0xD0 – measuring (weight visible, impedance zero)
 *   0xE0 – stabilized (final weight, impedance present)
 *
 * Weight:  big-endian uint16, unit 0.01 kg (10 g)
 * Impedance: big-endian uint16, unit unknown
 *
 * Operated by [BroadcastScaleAdapter]; GATT hooks are intentional no-ops.
 */
class ScaleupHandler : ScaleDeviceHandler() {

    companion object {
        private const val FLAG_MEASURING  = 0xD0
        private const val FLAG_STABLE     = 0xE0
        private const val MIN_DATA_LEN    = 9
        private const val WEIGHT_MIN_KG   = 0.5f
        private const val WEIGHT_MAX_KG   = 300f
    }

    private var hasPublished = false

    // ── Device identification ─────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim()
        if (name.equals("da", ignoreCase = true)) {
            return deviceSupport
        }

        val m = device.manufacturerData ?: return null
        for (i in 0 until m.size()) {
            val key = m.keyAt(i)
            val flag = key and 0xFF
            if (flag == FLAG_MEASURING || flag == FLAG_STABLE) {
                return deviceSupport
            }
        }
        return null
    }

    // ── GATT hooks (intentional no-ops) ──────────────────────────────────────

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
    }

    // ── Broadcast reception ───────────────────────────────────────────────────

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val msd = result.scanRecord?.manufacturerSpecificData
            ?: return BroadcastAction.IGNORED

        for (i in 0 until msd.size()) {
            val key = msd.keyAt(i)
            val data = msd.valueAt(i) ?: continue
            if (data.size < MIN_DATA_LEN) continue

            val flag = key and 0xFF
            if (flag != FLAG_MEASURING && flag != FLAG_STABLE) continue

            // New measurement cycle (0xD0) resets the session guard
            if (flag == FLAG_MEASURING) {
                if (hasPublished) LogManager.d(TAG, "new measurement cycle, resetting session guard")
                hasPublished = false
            }
            if (hasPublished) {
                LogManager.d(TAG, "already published this session → CONSUMED_STOP")
                return BroadcastAction.CONSUMED_STOP
            }

            val weightRaw = ((key shr 8) shl 8) or (data[0].toInt() and 0xFF)
            val weightKg = weightRaw / 100.0f

            if (weightKg < WEIGHT_MIN_KG || weightKg > WEIGHT_MAX_KG) {
                LogManager.d(TAG, "weight ${"%.2f".format(weightKg)} kg out of range; discarded")
                continue
            }

            if (flag == FLAG_MEASURING) {
                LogManager.d(TAG, "measuring ${"%.2f".format(weightKg)} kg")
                return BroadcastAction.CONSUMED_KEEP_SCANNING
            }

            val impedanceRaw = ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)

            LogManager.i(TAG, "stable ${"%.2f".format(weightKg)} kg, impedance=$impedanceRaw → publish")

            publish(ScaleMeasurement().apply {
                userId   = user.id
                weight   = weightKg
                dateTime = Date()
            })

            hasPublished = true
            return BroadcastAction.CONSUMED_STOP
        }

        return BroadcastAction.IGNORED
    }

    private val deviceSupport = DeviceSupport(
        displayName  = "Scale Up",
        capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
        implemented  = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
        linkMode     = LinkMode.BROADCAST_ONLY
    )
}
