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
import org.junit.Test

class HealthKeep280HandlerTest {

    @Test
    fun testFinalMeasurementParsing() {
        // Payload reale catturato da nRF Connect durante la pesata:
        // Peso: 116.40 kg (0x01C6B0), Battito: 68 bpm (0x44), Impedenza: 357 Ohm (0x0165)
        val data = byteArrayOf(
            0xCF.toByte(), 0x08, 0x00, 0xA3.toByte(), 0x00,
            0x01, 0xC6.toByte(), 0xB0.toByte(),
            0x44,
            0x01, 0x65,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04
        )

        // Verifica comando BIA finale (0xA3)
        val cmd = data[3].toInt() and 0xFF
        assertThat(cmd).isEqualTo(0xA3)

        // Estrazione e verifica peso (usando la sintassi corretta di Truth per i float)
        val weightRaw = ((data[5].toInt() and 0xFF) shl 16) or
                        ((data[6].toInt() and 0xFF) shl 8) or
                        (data[7].toInt() and 0xFF)
        val weight = weightRaw / 1000.0f
        assertThat(weight).isWithin(0.01f).of(116.40f)

        // Estrazione e verifica frequenza cardiaca (Heart Rate)
        val heartRate = data[8].toInt() and 0xFF
        assertThat(heartRate).isEqualTo(68)

        // Estrazione e verifica impedenza corporea (BIA)
        val impedance = ((data[9].toInt() and 0xFF) shl 8) or (data[10].toInt() and 0xFF)
        assertThat(impedance).isEqualTo(357)
    }
}
