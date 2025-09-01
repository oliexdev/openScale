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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.UUID
import kotlin.math.max

/**
 * SinocareHandler
 * ---------------------
 * Broadcast-only handler for Sinocare "Weight Scale" devices that transmit measurements
 * in manufacturer data advertisements. No GATT connection is used.
 *
 * Frame notes (derived from legacy implementation):
 * - Manufacturer ID: 0xFF64
 * - Manufacturer data length must be > 16
 * - Checksum: XOR of bytes [6..15] (inclusive) must equal data[16]
 * - Weight: 16-bit: MSB at index 10, LSB at index 9; unit = kg/100
 *
 * Stability heuristic:
 * - The same raw weight must be seen WEIGHT_TRIGGER_THRESHOLD consecutive times
 *   before we consider it final and publish the measurement.
 */
class SinocareHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "SinocareHandler"

        private const val MANUFACTURER_DATA_ID = 0xFF64
        private const val WEIGHT_MSB = 10
        private const val WEIGHT_LSB = 9
        private const val CHECKSUM_INDEX = 16

        // Number of consecutive identical samples required to mark as "stable"
        private const val WEIGHT_TRIGGER_THRESHOLD = 9
    }

    // Broadcast session state (reset per session by adapter via new instance or detach/attach)
    private var lastSeenWeight: Int = 0
    private var repeatCount: Int = 0

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Recognize by advertised name and/or manufacturer data presence
        val hasMsd = device.manufacturerData?.get(MANUFACTURER_DATA_ID) != null
        val nameOk = device.name?.equals("Weight Scale", ignoreCase = true) == true
        if (!hasMsd && !nameOk) return null

        return DeviceSupport(
            displayName = "Sinocare",
            capabilities = emptySet(),              // broadcast weight only
            implemented = emptySet(),
            bleTuning = null,
            linkMode = LinkMode.BROADCAST_ONLY      // critical: broadcast flow
        )
    }

    /**
     * Handle a single advertisement; return a BroadcastAction to steer the adapter.
     */
    override fun onAdvertisement(scanResult: ScanResult, user: com.health.openscale.core.bluetooth.data.ScaleUser): BroadcastAction {
        val msd: SparseArray<ByteArray> = scanResult.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        val data = msd.get(MANUFACTURER_DATA_ID) ?: return BroadcastAction.IGNORED

        // Sanity: need at least up to checksum index
        if (data.size <= CHECKSUM_INDEX) return BroadcastAction.IGNORED

        // Verify XOR checksum over [6..15], must equal data[16]
        var checksum: Byte = 0x00
        for (i in 6 until CHECKSUM_INDEX) {
            checksum = (checksum.toInt() xor (data[i].toInt() and 0xFF)).toByte()
        }
        val expected = data[CHECKSUM_INDEX]
        if (expected != checksum) {
            LogManager.d(TAG, String.format("Checksum error, got %02X, expected %02X", expected.toInt() and 0xFF, checksum.toInt() and 0xFF))
            return BroadcastAction.IGNORED
        }

        // Parse raw 16-bit weight (kg * 100)
        if (data.size <= max(WEIGHT_MSB, WEIGHT_LSB)) return BroadcastAction.IGNORED
        var weightRaw = (data[WEIGHT_MSB].toInt() and 0xFF)
        weightRaw = (weightRaw shl 8) or (data[WEIGHT_LSB].toInt() and 0xFF)

        if (weightRaw <= 0) {
            // Ignore zero/invalid frames; keep listening
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        if (weightRaw != lastSeenWeight) {
            // New value observed: reset streak
            lastSeenWeight = weightRaw
            repeatCount = 1
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // Same value as last time: count streak
        repeatCount += 1
        if (repeatCount >= WEIGHT_TRIGGER_THRESHOLD) {
            // Consider stabilized -> publish and stop
            val weightKg = weightRaw / 100.0f

            val m = ScaleMeasurement().apply {
                weight = weightKg
                // dateTime left as "now" by default if ScaleMeasurement handles it, or set explicitly:
                // dateTime = Date(System.currentTimeMillis())
                // Optionally attach userId if your model requires it:
                // userId = user.id
            }
            publish(m)
            // Let the adapter stop scanning and emit BroadcastComplete
            return BroadcastAction.CONSUMED_STOP
        }

        return BroadcastAction.CONSUMED_KEEP_SCANNING
    }

    override fun onDisconnected() {
        // Reset session state to avoid carrying streaks between sessions
        lastSeenWeight = 0
        repeatCount = 0
    }
}
