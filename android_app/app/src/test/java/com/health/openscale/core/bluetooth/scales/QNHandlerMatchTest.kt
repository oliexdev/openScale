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
 * Tests for [QNHandler.supportFor] device matching, in particular the AE00 vendor-service
 * path ported from ble-scale-sync 6a12687 (#235): a QN scale that advertises the QN-only
 * ae00 service under a non-QN name (e.g. GE CS 10 G "Fit Plus") must be claimed, while the
 * ae00 service alone (no fff0/ffe0 channel this handler can drive) must NOT be claimed.
 *
 * Robolectric is required because QNHandler constructs a main-looper Handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerMatchTest {

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun device(name: String, vararg services: Int) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = services.map { uuid16(it) },
        manufacturerData = null,
    )

    @Test
    fun `claims ae00 + fff0 device with a non-QN name`() {
        // GE CS 10 G "Fit Plus": ae00 + fff0, non-QN name.
        assertThat(QNHandler().supportFor(device("Fit Plus", 0xAE00, 0xFFF0))).isNotNull()
    }

    @Test
    fun `does not claim a bare fff0 device with a non-QN name`() {
        // No ae00 and no QN-family name -> not ours (leave to other fff0 handlers).
        assertThat(QNHandler().supportFor(device("Fit Plus", 0xFFF0))).isNull()
    }

    @Test
    fun `does not claim ae00 without the fff0 or ffe0 channel`() {
        // ae00 alone: we cannot drive the device, so don't claim it.
        assertThat(QNHandler().supportFor(device("Fit Plus", 0xAE00))).isNull()
    }

    @Test
    fun `still claims the classic QN name + fff0 device`() {
        // Regression guard: the original name+service path keeps working.
        assertThat(QNHandler().supportFor(device("QN-Scale", 0xFFF0))).isNotNull()
    }
}
