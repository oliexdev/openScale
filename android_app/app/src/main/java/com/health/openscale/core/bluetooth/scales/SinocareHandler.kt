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
import android.util.SparseArray
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import kotlin.math.max
import com.health.openscale.core.data.Kg

/**
 * SinocareHandler
 * ---------------------
 * Broadcast-only handler for Sinocare "Weight Scale" devices that transmit measurements
 * in manufacturer data advertisements. No GATT connection is used.
 *
 * Frame notes (derived from legacy implementation):
 * - Manufacturer ID observerd: 0xFF64 and 0x0000
 * - Manufacturer data length must be > 16
 * - Checksum: XOR of bytes [6..15] (inclusive) must equal data[16]
 * - Weight: 16-bit: MSB at index 10, LSB at index 9; unit = kg/100
 *
 * Stability heuristic:
 * - The same raw weight must be seen WEIGHT_TRIGGER_THRESHOLD consecutive times
 *   before we consider it final and publish the measurement.
 *
 * The "IFIT" (vendor app "Unique Health"; a Lefu OEM broadcast scale, which Lefu's own device
 * table files as a weight-only "V3 broadcast" model) sends this very frame under manufacturer id
 * 0x0000, so it is claimed here by name. A BTSnoop capture of a full weigh-in showed, beyond what
 * this handler decodes: bytes 6..8 are the signature CE 01 02, bytes 11..12 switch from 00 00 to
 * FF FF and byte 15 from 01 to 00 in an end-of-measurement frame that repeats the locked weight
 * for about a second, byte 14 is the display unit (00 kg, 01 lb) while the weight itself always
 * stays in kg, and zero-weight frames follow for about a minute after stepping off. The locked
 * weight repeats for 4-5 s before the end frame, so the streak heuristic above is sufficient.
 */
class SinocareHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "SinocareHandler"

        private const val MANUFACTURER_DATA_ID = 0xFF64
        private const val MANUFACTURER_DATA_ID_ALT = 0x0000
        /** Advertised name of the Lefu-made IFIT, which speaks the same frame under id 0x0000. */
        private const val IFIT_NAME = "IFIT"
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
        // Name only: other scales (e.g. Eufy P2 Pro) also advertise manufacturer id 0xFF64.
        val displayName = when {
            device.name.equals("Weight Scale", ignoreCase = true) -> "Sinocare"
            device.name.equals(IFIT_NAME, ignoreCase = true) -> IFIT_NAME
            else -> return null
        }

        return DeviceSupport(
            displayName = displayName,
            capabilities = emptySet(),              // broadcast weight only
            implemented = emptySet(),
            linkMode = LinkMode.BROADCAST_ONLY      // critical: broadcast flow
        )
    }

    /**
     * Handle a single advertisement; return a BroadcastAction to steer the adapter.
     */
    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val msd: SparseArray<ByteArray> = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        val data = msd.get(MANUFACTURER_DATA_ID) ?: msd.get(MANUFACTURER_DATA_ID_ALT) ?: return BroadcastAction.IGNORED

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
                this[MeasurementType.WEIGHT] = Kg(weightKg)
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
