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
package com.health.openscale.core.bluetooth.modern

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.health.openscale.core.bluetooth.modern.BroadcastAction.*
import com.health.openscale.core.bluetooth.modern.DeviceCapability.LIVE_WEIGHT_STREAM
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils

/**
 * Yoda1DeviceHandler
 * ------------------
 * Broadcast-only handler for the "Yoda1" scale family.
 *
 * The device does not require a GATT connection. It encodes weight in the
 * Manufacturer Specific Data (MSD) of its advertisement frames.
 *
 * Encoding (from legacy driver knowledge):
 * - bytes[0..1]  : raw weight (big-endian)
 * - byte[6]      : control flags
 *      bit0 -> stabilized flag
 *      bit2 -> unit is KG (otherwise catty/jin)
 *      bit3 -> one decimal place (otherwise extra /10 afterwards)
 */
class Yoda1Handler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Heuristic: name starts with "Yoda1" OR the MSD payload looks like Yoda1 (>= 7 bytes)
        val nameMatch = device.name.startsWith("Yoda1", ignoreCase = true)
        if (!nameMatch) return null

        return DeviceSupport(
            displayName = "Yoda1 Scale",
            capabilities = setOf(LIVE_WEIGHT_STREAM),
            implemented = setOf(LIVE_WEIGHT_STREAM),
            bleTuning = null,
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val record = result.scanRecord ?: return IGNORED
        val msd: SparseArray<ByteArray> = record.manufacturerSpecificData ?: return IGNORED
        if (msd.size() <= 0) return IGNORED
        val payload = msd.valueAt(0) ?: return IGNORED
        if (payload.size < 7) return IGNORED

        val ctrl = payload[6].toInt() and 0xFF
        val stabilized = isBitSet(ctrl, 0)
        val unitIsKg = isBitSet(ctrl, 2)
        val oneDecimal = isBitSet(ctrl, 3)

        val raw = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        var weight = if (unitIsKg) raw / 10.0f else raw / 20.0f // catty/jin conversion
        if (!oneDecimal) weight /= 10.0f

        val measurement = ScaleMeasurement().apply {
            setWeight(ConverterUtils.toKilogram(weight, user.scaleUnit))
        }

        // If not stabilized yet, keep scanning (device often sends intermediate weights).
        return if (stabilized) {
            publish(measurement)
            CONSUMED_STOP
        } else {
            CONSUMED_KEEP_SCANNING
        }
    }

    private fun isBitSet(value: Int, bit: Int): Boolean = ((value shr bit) and 0x1) == 1
}
