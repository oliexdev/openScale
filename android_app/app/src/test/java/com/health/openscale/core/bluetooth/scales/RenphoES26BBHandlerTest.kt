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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import org.junit.Test

/**
 * Unit tests for [RenphoES26BBHandler]: support matching for the ES-26BB-B and the solar
 * Elis Solar models (R-A012 / R-A020), plus the 0x55aa frame decoders.
 *
 * Frame layout (verified against renamed ES-CS20MB1/R-A012/ES-26BB-B/ESCS20MB2 captures,
 * see renpho-escs20m/x55aa/protocol.py):
 *   55 AA [cmd] [len u16be] [payload] [checksum], checksum = sum of all preceding bytes & 0xFF.
 *   cmd 0x14 payload: [status, weight u32be (0.01 kg), resistance u16be (Ω)].
 *   cmd 0x15 payload: [weight u32be, resistance u16be, seconds u32be, reserved].
 * Status low nibble: 1 = final (0x11 = final in BIA zero-current mode).
 */
class RenphoES26BBHandlerTest {

    @Test
    fun `matches ES-26BB-B, Elis Solar R-A012 and R-A020`() {
        assertThat(RenphoES26BBHandler().supportFor(device("ES-26BB-B"))).isNotNull()
        assertThat(RenphoES26BBHandler().supportFor(device("R-A012"))).isNotNull()
        assertThat(RenphoES26BBHandler().supportFor(device("R-A020"))).isNotNull()
    }

    @Test
    fun `rejects other R-A0xx models`() {
        // R-A033 (Elis 1C) uses a different GATT layout and must not be claimed here.
        assertThat(RenphoES26BBHandler().supportFor(device("R-A033"))).isNull()
        assertThat(RenphoES26BBHandler().supportFor(device("R-A001"))).isNull()
    }

    @Test
    fun `decodes final 0x14 frame`() {
        // 55 AA 14 00 07 | 01 00 00 1D 83 01 F4 | B0 — status 0x01 (final).
        // weight u32be 0x00001D83 = 7555 → 75.55 kg; resistance u16be 0x01F4 = 500 Ω.
        val frame = RenphoES26BBHandler.parseLiveFrame(
            hex("55 AA 14 00 07 01 00 00 1D 83 01 F4 B0")
        )
        assertThat(frame).isNotNull()
        assertThat(frame!!.weightKg).isWithin(1e-4f).of(75.55f)
        assertThat(frame.resistance).isEqualTo(500)
    }

    @Test
    fun `decodes final 0x14 frame in BIA zero-current mode`() {
        // status 0x11 = final while the bioimpedance pass is skipped (resistance 0).
        val frame = RenphoES26BBHandler.parseLiveFrame(
            hex("55 AA 14 00 07 11 00 00 1D 83 00 00 CB")
        )
        assertThat(frame).isNotNull()
        assertThat(frame!!.weightKg).isWithin(1e-4f).of(75.55f)
        assertThat(frame.resistance).isEqualTo(0)
    }

    @Test
    fun `ignores non-final 0x14 frames`() {
        // Same payload with status 0x00 (still settling) — must not be published.
        val frame = RenphoES26BBHandler.parseLiveFrame(
            hex("55 AA 14 00 07 00 00 00 1D 83 01 F4 AF")
        )
        assertThat(frame).isNull()
    }

    @Test
    fun `rejects short 0x14 frames`() {
        assertThat(RenphoES26BBHandler.parseLiveFrame(ByteArray(10))).isNull()
        assertThat(RenphoES26BBHandler.parseLiveFrame(ByteArray(0))).isNull()
    }

    @Test
    fun `decodes offline 0x15 record`() {
        // 55 AA 15 00 0C | 00 00 1D 83 01 F4 00 00 00 1E 00 00 | D3 — weight 75.55 kg,
        // resistance 500 Ω, measured 30 s ago (12-byte payload, reserved trailing bytes).
        val record = RenphoES26BBHandler.parseOfflineRecord(
            hex("55 AA 15 00 0C 00 00 1D 83 01 F4 00 00 00 1E 00 00 D3")
        )
        assertThat(record).isNotNull()
        assertThat(record!!.weightKg).isWithin(1e-4f).of(75.55f)
        assertThat(record.resistance).isEqualTo(500)
        assertThat(record.secondsAgo).isEqualTo(30L)
    }

    @Test
    fun `body fat formula matches renpho-escs20m algorithm 0x04`() {
        // Female, 54.7 kg, 167 cm, age 30, resistance 385 Ω → expected ~19.2%
        val bf = RenphoES26BBHandler.bodyFatPercent(54.7f, 1.67f, 30, false, 385)
        assertThat(bf).isWithin(0.2f).of(19.2f)
    }

    @Test
    fun `body fat formula for male`() {
        // Male, 75 kg, 180 cm, age 35, resistance 500 Ω → expected ~15.9%
        val bf = RenphoES26BBHandler.bodyFatPercent(75f, 1.80f, 35, true, 500)
        assertThat(bf).isWithin(0.2f).of(15.9f)
    }

    private fun hex(s: String): ByteArray =
        s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}