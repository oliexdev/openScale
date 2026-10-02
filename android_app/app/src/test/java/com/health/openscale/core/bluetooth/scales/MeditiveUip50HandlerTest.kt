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
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import org.junit.Test

class MeditiveUip50HandlerTest {

    private val serviceFfb0 = uuid16(0xFFB0)

    // Verbatim frames from btsnoop_hci_202610021458.cfa.
    private val stableWeight = hex("AC 27 80 6D 8F A6 00 00 00 00 00 00 00 00 00 00 00 03 D5 1A")
    private val finalImpedance = hex("AC 27 01 00 02 14 00 00 00 00 00 00 00 00 00 00 00 03 D6 10")

    @Test
    fun `claims only the named Meditive device with service FFB0`() {
        val handler = MeditiveUip50Handler()

        val support = handler.supportFor(device("meditive", serviceFfb0))!!
        assertThat(support.displayName).isEqualTo("Meditive Smart Scale UIP-50")
        assertThat(support.implemented).containsExactly(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
            DeviceCapability.TIME_SYNC,
        )
        assertThat(handler.supportFor(device("meditive"))).isNull()
        assertThat(handler.supportFor(device("other scale", serviceFfb0))).isNull()
    }

    @Test
    fun `decodes settled 102 kilogram live weight`() {
        assertThat(MeditiveUip50Handler.parse(stableWeight))
            .isEqualTo(MeditiveUip50Handler.Frame.LiveWeight(102_182, locked = true))
    }

    @Test
    fun `decodes impedance-only final frame`() {
        assertThat(MeditiveUip50Handler.parse(finalImpedance))
            .isEqualTo(MeditiveUip50Handler.Frame.FinalResult(weightGrams = 0, impedanceOhms = 532))
    }
}
