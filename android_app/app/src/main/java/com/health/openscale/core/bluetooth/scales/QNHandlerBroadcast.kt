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
 * Handler for QN-lineage scales operating in non-connectable broadcast mode
 * (ADV_NONCONN_IND, Variant 3 of the ES-CS20M family).
 *
 * These devices advertise weight-only data via BLE Manufacturer Specific Data
 * using the AABB protocol (Company ID 0xFFFF). They cannot be connected via
 * GATT and therefore never expose service UUIDs (0xFFE0 / 0xFFF0) in their
 * advertisements.
 *
 * AABB payload layout (after ScanRecord strips the 2-byte Company ID prefix,
 * so index 0 is the first application byte):
 *   [0-1]   0xAA 0xBB  magic header
 *   [2-7]   device MAC address (6 bytes, big-endian)
 *   [15]    status flags: bit 5 (0x20) = measurement stable
 *   [17-18] weight: little-endian uint16 / 100 = kg
 *
 * Body composition is not available without GATT/BIA.
 *
 * Operated by [BroadcastScaleAdapter]; all GATT hooks are intentional no-ops.
 */
class QNHandlerBroadcast : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "QNBroadcastHandler"

        private const val COMPANY_ID_QN    = 0xFFFF
        private const val MAGIC_BYTE_0     = 0xAA.toByte()
        private const val MAGIC_BYTE_1     = 0xBB.toByte()
        private const val STATUS_IDX       = 15
        private const val FLAG_STABLE_BIT  = 0x20   // bit 5 of STATUS_IDX byte
        private const val WEIGHT_IDX_LO    = 17     // little-endian LSB
        private const val WEIGHT_IDX_HI    = 18     // little-endian MSB
        private const val MIN_DATA_LEN     = 19
        private const val WEIGHT_MIN_KG    = 0.5f
        private const val WEIGHT_MAX_KG    = 300f
    }

    private var hasPublished = false

    // ── Device identification ─────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The sole reliable fingerprint for Variant 3 is the AABB magic header in
        // Manufacturer Specific Data (Company ID 0xFFFF). Name-based matching is
        // intentionally omitted: "renpho" and "qn-scale" names are also used by
        // connectable Variants 1 and 2, so a name match without the AABB payload
        // would cause this handler to shadow the GATT handlers for those devices.
        val isAabb = device.manufacturerData
            ?.get(COMPANY_ID_QN)
            ?.let { data ->
                        data[0] == MAGIC_BYTE_0 &&
                        data[1] == MAGIC_BYTE_1
            } ?: false

        if (!isAabb) return null

        return DeviceSupport(
            displayName  = "QN Scale (Broadcast)",
            capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            implemented  = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode     = LinkMode.BROADCAST_ONLY
        )
    }

    // ── GATT hooks (intentional no-ops — device is non-connectable) ───────────

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
    }

    // ── Broadcast reception ───────────────────────────────────────────────────

    /**
     * Called by [BroadcastScaleAdapter] for every incoming advertisement from
     * the target address.
     *
     * Returns:
     *   [BroadcastAction.IGNORED]              — payload missing, wrong magic, or implausible weight
     *   [BroadcastAction.CONSUMED_KEEP_SCANNING] — weight present but measurement not yet stable
     *   [BroadcastAction.CONSUMED_STOP]        — stable weight published; adapter stops scanning
     */
    override fun onAdvertisement(scanResult: ScanResult, user: ScaleUser): BroadcastAction {
        if (hasPublished) return BroadcastAction.IGNORED

        val record = scanResult.scanRecord ?: return BroadcastAction.IGNORED

        // ScanRecord.getManufacturerSpecificData() strips the 2-byte Company ID
        // prefix, so data[0] is the first application byte (0xAA magic).
        val data = record.getManufacturerSpecificData(COMPANY_ID_QN)
            ?: return BroadcastAction.IGNORED

        if (data.size < MIN_DATA_LEN) {
            LogManager.d(TAG, "AABB payload too short (${data.size} bytes); discarded")
            return BroadcastAction.IGNORED
        }

        if (data[0] != MAGIC_BYTE_0 || data[1] != MAGIC_BYTE_1) {
            LogManager.d(TAG, "AABB magic mismatch; discarded")
            return BroadcastAction.IGNORED
        }

        val stable = (data[STATUS_IDX].toInt() and FLAG_STABLE_BIT) != 0

        // Weight: little-endian uint16, unit 0.01 kg
        val rawWeight = (data[WEIGHT_IDX_LO].toInt() and 0xFF) or
                ((data[WEIGHT_IDX_HI].toInt() and 0xFF) shl 8)
        val weightKg = rawWeight / 100.0f

        if (weightKg < WEIGHT_MIN_KG || weightKg > WEIGHT_MAX_KG) {
            LogManager.w(TAG, "AABB weight out of plausible range (${"%.2f".format(weightKg)} kg); discarded")
            return BroadcastAction.IGNORED
        }

        LogManager.d(TAG, "AABB weight=${"%.2f".format(weightKg)} kg stable=$stable")

        if (!stable) return BroadcastAction.CONSUMED_KEEP_SCANNING

        val measurement = ScaleMeasurement().apply {
            userId   = user.id
            weight   = weightKg
            dateTime = Date()
        }

        LogManager.i(TAG, "AABB stable weight ${"%.2f".format(weightKg)} kg → publish")
        publish(measurement)

        hasPublished = true
        return BroadcastAction.CONSUMED_STOP
    }
}