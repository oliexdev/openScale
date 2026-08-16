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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Matching tests for [HoffenBbs8107Handler], which drives a Chipsea "WeChat scale" firmware sold
 * under more than one brand.
 *
 * Fixtures are synthetic advertisements built from the names these scales are documented to
 * advertise, not personal captures.
 *
 * Robolectric is required for android.util.SparseArray in ScannedDeviceInfo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoffenBbs8107HandlerTest {

    /** The generic WeChat service these scales advertise alongside the 0xFFB0 they talk on. */
    private val SERVICE_FEE7 = uuid16(0xFEE7)

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun device(name: String, vararg services: UUID) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services.toList(),
        manufacturerData = null,
    )

    @Test
    fun `claims the Hoffen BBS-8107`() {
        val support = HoffenBbs8107Handler().supportFor(device("Hoffen BS-8107", SERVICE_FEE7))
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("Hoffen BBS-8107")
    }

    @Test
    fun `claims the ProfiCare PC-PW 3008 BT rebrand under its own name`() {
        val support = HoffenBbs8107Handler().supportFor(device("PC-PW 3008 BT", SERVICE_FEE7))
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("ProfiCare PC-PW 3008 BT")
        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
    }

    @Test
    fun `matches regardless of case and surrounding whitespace`() {
        assertThat(HoffenBbs8107Handler().supportFor(device("pc-pw 3008 bt"))).isNotNull()
        assertThat(HoffenBbs8107Handler().supportFor(device(" PC-PW 3008 BT "))).isNotNull()
    }

    @Test
    fun `implements everything it claims`() {
        val support = HoffenBbs8107Handler().supportFor(device("PC-PW 3008 BT"))!!
        assertThat(support.implemented).isEqualTo(support.capabilities)
        assertThat(support.capabilities).contains(DeviceCapability.BODY_COMPOSITION)
    }

    @Test
    fun `does not claim other devices advertising the generic WeChat service`() {
        // 0xFEE7 is used by all sorts of unrelated hardware; the name is what decides.
        assertThat(HoffenBbs8107Handler().supportFor(device("MI Band 5", SERVICE_FEE7))).isNull()
        assertThat(HoffenBbs8107Handler().supportFor(device("", SERVICE_FEE7))).isNull()
    }

    @Test
    fun `does not claim a similarly named but different model`() {
        assertThat(HoffenBbs8107Handler().supportFor(device("PC-PW 3007 BT"))).isNull()
        assertThat(HoffenBbs8107Handler().supportFor(device("PC-PW 3008"))).isNull()
    }
}
