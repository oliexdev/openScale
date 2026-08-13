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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

/**
 * iHealth HS3 (HS33FA4A) classic SPP handler.
 *
 * Transport: CLASSIC_SPP
 * Stream protocol (observed in legacy driver):
 *  - Weight packet header: A0 09 A6 28, then 5 don't-care bytes, then 2 weight bytes.
 *  - Time packet header:   A0 09 A6 33 (ignored).
 *  - Weight bytes are encoded as hex digits; the decimal point sits before the last *nibble*.
 *    Example: bytes 0x12 0x34 -> "123.4" kg.
 *
 * We keep a small state machine to find headers in the raw SPP byte stream.
 */
class IHealthHS3Handler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // iHealth advertises as "iHealth HS3..." in most cases
        val n = device.name.uppercase()
        if (!n.startsWith("IHEALTH HS3")) return null

        val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        return DeviceSupport(
            displayName = "iHealth HS3",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CLASSIC_SPP
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Nothing to configure on SPP; just tell UI to step on the scale
        userInfo(com.health.openscale.R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CLASSIC_DATA_UUID || data.isEmpty()) return
        feedStream(data)
    }

    // -------------------------------------------------------------------------
    // Stream parser (state machine)
    // -------------------------------------------------------------------------

    // States:
    // 0: seek A0
    // 1: expect 09
    // 2: expect A6
    // 3: expect type (28=weight, 33=time, else reset)
    // 4: skip 5 bytes (don't care)
    // 5: read weight hi
    // 6: read weight lo -> parse/publish -> reset
    private var state = 0
    private var skipRemain = 0
    private var wHi: Byte = 0

    // De-duplication: identical weight within 60s is dropped (matches legacy)
    private var lastW0: Byte = 0
    private var lastW1: Byte = 0
    private var lastWeighedMs: Long = 0
    private val maxTimeDiffMs = 60_000L

    private fun feedStream(chunk: ByteArray) {
        for (b in chunk) {
            when (state) {
                0 -> {
                    if (b == 0xA0.toByte()) state = 1
                }
                1 -> {
                    state = if (b == 0x09.toByte()) 2 else if (b == 0xA0.toByte()) 1 else 0
                }
                2 -> {
                    state = if (b == 0xA6.toByte()) 3 else 0
                }
                3 -> { // type
                    when (b) {
                        0x28.toByte() -> { // weight packet
                            skipRemain = 5
                            state = 4
                        }
                        0x33.toByte() -> { // time packet; ignore
                            state = 0
                        }
                        else -> state = 0
                    }
                }
                4 -> { // skip 5 bytes
                    if (--skipRemain <= 0) state = 5
                }
                5 -> { // weight hi
                    wHi = b
                    state = 6
                }
                6 -> { // weight lo -> parse
                    val wLo = b
                    if (!isDuplicate(wHi, wLo)) {
                        parseAndPublishWeight(wHi, wLo)
                        lastW0 = wHi
                        lastW1 = wLo
                        lastWeighedMs = System.currentTimeMillis()
                    } else {
                        logD("Duplicate weight within window; dropped")
                    }
                    state = 0
                }
            }
        }
    }

    private fun isDuplicate(hi: Byte, lo: Byte): Boolean {
        val now = System.currentTimeMillis()
        return hi == lastW0 && lo == lastW1 && (now - lastWeighedMs) < maxTimeDiffMs
    }

    /**
     * Legacy driver formed weight by hex-string of the two bytes and inserting
     * a decimal before the last nibble. Example: 0x12 0x34 -> "123.4".
     */
    private fun parseAndPublishWeight(hi: Byte, lo: Byte) {
        val hex = String.format("%02X%02X", hi.toInt() and 0xFF, lo.toInt() and 0xFF)
        val weightStr = hex.dropLast(1) + "." + hex.takeLast(1)
        val weight = weightStr.toFloatOrNull()
        if (weight == null) {
            logW("Failed to parse weight from hex '$hex'")
            return
        }
        val m = ScaleMeasurement().apply {
            dateTime = Date()
            this.weight = weight
        }
        logD("Parsed weight: $weight kg")
        publish(m)
    }
}
