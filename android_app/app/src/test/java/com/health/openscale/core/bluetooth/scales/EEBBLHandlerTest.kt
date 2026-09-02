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
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test

class EEBBLHandlerTest {

    @Test
    fun `body composition remains declared but not implemented`() {
        val support = EEBBLHandler().supportFor(device("EEBBL"))!!

        assertThat(support.capabilities).contains(DeviceCapability.BODY_COMPOSITION)
        assertThat(support.implemented).doesNotContain(DeviceCapability.BODY_COMPOSITION)
    }

    private fun device(name: String) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = emptyList(),
        manufacturerData = null,
    )
}
