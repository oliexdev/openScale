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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Ported from ble-scale-sync (GPL-3.0, © Kristián Partl):
 *   src/scales/robi-s9.ts — "Robi S9 (Lefu / Fitdays FFB0-new protocol)"
 *   upstream commits 4a8fda5 (#228, initial adapter) and 21b3afa (#248, weight = 3-byte BE grams).
 * Protocol reverse-engineered from the reporter's HCI snoop; weight confirmed against a
 * known-weight capture. Impedance and the scrambled body-composition frames are not decoded.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Robi S9 smart scale (Fitdays app, Lefu-style FFB0 protocol).
 *
 * Shares service 0xFFB0 with the openScale [MGBHandler] family but speaks a different
 * 20-byte frame protocol `[seq][len][00][type][payload][trailer]`:
 *  - the phone replays a captured B0/B1/B2/BD handshake on 0xFFB1,
 *  - the scale streams A2 live frames on 0xFFB2 (notify),
 *  - the final result arrives as an A3 frame on 0xFFB3 (indicate).
 *
 * Because both scales advertise 0xFFB0, [RobiS9Handler] must be registered **before**
 * [MGBHandler] in [com.health.openscale.core.bluetooth.ScaleFactory] so a "Robi …" device
 * is claimed here by name (the MGB handler claims by service and would otherwise grab it).
 *
 * The handshake is replayed verbatim: its 20-byte frames carry a trailer checksum whose
 * algorithm is not cracked plus a stale unix-timestamp + token, so regenerating them is
 * unsafe; the scale accepts the replayed frames for a weigh-in.
 */
class RobiS9Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write (handshake)
    private val CHAR_LIVE: UUID = uuid16(0xFFB2)    // notify (live A2 frames)
    private val CHAR_RESULT: UUID = uuid16(0xFFB3)  // indicate (final A3 result)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        // Swan/Icomon/YG are the MGB protocol; never claim them here.
        if (name.startsWith("swan") || name == "icomon" || name == "yg") return null
        // openScale only sees the advertised name (not the characteristic list) before
        // connecting, so — unlike the upstream adapter — we claim strictly by name.
        if (!name.contains("robi")) return null

        val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        return DeviceSupport(
            displayName = "Robi S9",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // The scale drops the link unless it receives the captured handshake and the result
        // characteristic is subscribed. node-ble enables the FFB3 indication transparently;
        // here we subscribe both notify/indicate characteristics.
        setNotifyOn(SERVICE, CHAR_LIVE)
        setNotifyOn(SERVICE, CHAR_RESULT)

        // Replay the captured handshake (seq 00..0a). The adapter paces I/O for us.
        for (hex in HANDSHAKE) {
            writeTo(SERVICE, CHAR_WRITE, hexToBytes(hex), withResponse = true)
        }

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        // Only the A3 final-result frame yields a weight; A2 live frames return null.
        val weightKg = parseFinalWeightKg(data) ?: return
        val m = ScaleMeasurement().apply {
            dateTime = Date()
            weight = weightKg
            // Impedance offset is not decoded yet (the captured A3 frame is all-zero after the
            // weight), so no body composition is published — only weight.
        }
        publish(m)
    }

    companion object {
        /**
         * Captured handshake (#228 HCI snoop, Fitdays app). Replayed verbatim to 0xFFB1 in
         * order (seq 00..0a).
         */
        val HANDSHAKE: List<String> = listOf(
            "000300b000000000000000000000000000000010",
            "011000b16a2eefa9003c01aa1e55b20f1b581403",
            "021000b16a2eefa9003c01aa1e55b20f1b581403",
            "030600b201aa1e55b20000000000000000000002",
            "040200bd09000000000000000000000000000006",
            "051000b16a2eefa9003c01aa1e55b20f1b581403",
            "061000b16a2eefa9003c01aa1e55b20f1b581403",
            "070600b201aa1e55b20000000000000000000002",
            "081000b16a2eefa9003c01aa1e55b20f1b581403",
            "090300b001000000000000000000000000000011",
            "0a0300b002000000000000000000000000000012",
        )

        /**
         * Decode the weight (kg) from a Robi S9 frame, or `null` if it is not a valid A3
         * final-result frame.
         *
         * A3 layout: `[seq][len][00][a3][flag][weight u24 BE grams][... trailer]`.
         * Weight is a 3-byte big-endian gram count at offset 5 (e.g. `01 2d c2` = 77250 g =
         * 77.25 kg, capture #248).
         */
        fun parseFinalWeightKg(frame: ByteArray): Float? {
            if (frame.size < 11) return null
            if ((frame[2].toInt() and 0xFF) != 0x00) return null
            if ((frame[3].toInt() and 0xFF) != 0xA3) return null
            val grams = ((frame[5].toInt() and 0xFF) shl 16) or
                ((frame[6].toInt() and 0xFF) shl 8) or
                (frame[7].toInt() and 0xFF)
            val kg = grams / 1000.0f
            return if (kg > 0f && kg.isFinite()) kg else null
        }

        /** Parse a hex string (even length) into bytes. */
        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
    }
}
