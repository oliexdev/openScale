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
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import androidx.core.util.isEmpty

/**
 * AAAxHandler
 *
 * Broadcast-only handler for the “AAA002 / AAA007 / AAA013” family that encodes weight
 * in manufacturer data using a simple XOR mask (from companyId high byte) and a 5-bit checksum.
 *
 * Frame format (manufacturer data, length >= 12):
 *  - bytes 0..5 : advertiser's MAC (ignored)
 *  - bytes 6..11: payload XOR-encoded with key K = (companyId >> 8) & 0xFF
 *      payload[0..3] : 32-bit value, big-endian:
 *                      [ state:1 | reserved:13 | grams:18 ]
 *                      state = 1 means “final/stabilized”
 *      payload[4]    : type (0xAD = weight, 0xA6 = impedance)
 *      payload[5]    : checksum nibble (compare only lower 5 bits)
 *
 * Checksum: sum(payload[0..4]) & 0x1F must equal (payload[5] & 0x1F).
 *
 * We publish a measurement once when a *final* 0xAD frame is seen, then ask the adapter
 * to stop scanning for this session.
 */
class AAAxHandler : ScaleDeviceHandler() {

    // We only publish one measurement per session to avoid duplicates
    private var publishedOnce = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim()
        val isAaaFamily = name == "AAA002" || name == "AAA007" || name == "AAA013"

        // If the name is recognized, we advertise support immediately.
        if (isAaaFamily) {
            val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
            return DeviceSupport(
                displayName = "AAA-series Broadcast Scale (${name})",
                capabilities = caps,
                implemented = caps,
                linkMode = LinkMode.BROADCAST_ONLY
            )
        }

        // Otherwise, we can try to be permissive and accept broadcast-only devices that
        // carry a single manufacturer record of length >= 12 (quick heuristic). If your
        // scanner fills manufacturerData in ScannedDeviceInfo, you can probe it here.

        // No positive signal → not our device.
        return null
    }

    override fun onAdvertisement(result: ScanResult, user: com.health.openscale.core.bluetooth.data.ScaleUser): BroadcastAction {
        if (publishedOnce) return BroadcastAction.CONSUMED_STOP

        val record = result.scanRecord ?: return BroadcastAction.IGNORED
        val m = record.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        if (m.isEmpty()) return BroadcastAction.IGNORED

        // Try each manufacturer entry until one parses
        for (i in 0 until m.size()) {
            val companyId = m.keyAt(i) // 16-bit company id
            val data = m.valueAt(i) ?: continue
            if (data.size < 12) continue

            // XOR key is the high byte of companyId (matches legacy implementation)
            val xorKey = ((companyId ushr 8) and 0xFF).toByte()

            // Deobfuscate last 6 bytes
            val payload = ByteArray(6)
            for (p in 0 until 6) {
                payload[p] = (data[6 + p].toInt() xor xorKey.toInt()).toByte()
            }

            // 5-bit checksum over payload[0..4]
            var chk = 0
            for (p in 0 until 5) chk += payload[p].toInt() and 0xFF
            if ((chk and 0x1F) != (payload[5].toInt() and 0x1F)) {
                logD("AAAx: checksum mismatch (sum=${chk and 0x1F}, got=${payload[5].toInt() and 0x1F})")
                continue
            }

            when (payload[4].toUByte().toInt()) {
                0xAD -> { // weight
                    val value = ((payload[0].toLong() and 0xFF) shl 24) or
                            ((payload[1].toLong() and 0xFF) shl 16) or
                            ((payload[2].toLong() and 0xFF) shl 8)  or
                            ((payload[3].toLong() and 0xFF) shl 0)

                    val stateFinal = ((value ushr 31) and 0x1L) != 0L
                    val grams = (value and 0x3FFFF).toInt() // 18 bits

                    logD("AAAx: weight frame grams=$grams stateFinal=$stateFinal")

                    if (stateFinal && grams > 0) {
                        val kg = grams / 1000f
                        publish(
                            ScaleMeasurement().apply {
                                dateTime = Date()
                                weight = kg
                            }
                        )
                        publishedOnce = true
                        return BroadcastAction.CONSUMED_STOP
                    } else {
                        // intermediate stream → keep scanning
                        return BroadcastAction.CONSUMED_KEEP_SCANNING
                    }
                }
                0xA6 -> {
                    // Impedance frame — protocol known but not implemented here
                    logD("AAAx: impedance frame seen (ignored)")
                    return BroadcastAction.CONSUMED_KEEP_SCANNING
                }
                else -> {
                    // Unknown frame type; log once and ignore
                    logD("AAAx: unsupported frame type=0x${String.format("%02X", payload[4])}")
                }
            }
        }

        return BroadcastAction.IGNORED
    }
}
