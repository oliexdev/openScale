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
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleCatalog.device

/**
 * Tests for [QNHandler.supportFor] device matching.
 *
 * Two independent paths matter here:
 *  - The AE00 vendor-service path ported from ble-scale-sync 6a12687 (#235): a QN scale that
 *    advertises the QN-only ae00 service under a non-QN name must be claimed, while ae00 alone
 *    (no fff0/ffe0 channel this handler can drive) must NOT be claimed.
 *  - The "fit plus" name match: a real BTSnoop capture of GE CS 10 G "Fit Plus" showed its
 *    pre-connect advertisement carries ONLY FFE0 — no AE00, no FFF0 (those are real GATT
 *    services but only discoverable post-connection) — so the AE00 relaxation above never
 *    fires for it in practice, and the name match is what actually claims the device.
 *
 * Robolectric is required because QNHandler constructs a main-looper Handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerMatchTest {


    @Test
    fun `claims ae00 + fff0 device with a non-QN name`() {
        // GE CS 10 G "Fit Plus": ae00 + fff0, non-QN name.
        assertThat(QNHandler().supportFor(device("Fit Plus", uuid16(0xAE00), uuid16(0xFFF0)))).isNotNull()
    }

    @Test
    fun `does not claim a bare fff0 device with a non-QN name`() {
        // No ae00 and no QN-family name -> not ours (leave to other fff0 handlers).
        assertThat(QNHandler().supportFor(device("Unrelated Scale", uuid16(0xFFF0)))).isNull()
    }

    @Test
    fun `claims Fit Plus advertising only FFE0 (name match, no AE00 needed)`() {
        // This file is the single home for QN device matching; QNHandlerProtocolTest covers frames.
        // GE CS 10 G "Fit Plus": confirmed via BTSnoop capture to advertise pre-connect with
        // ONLY FFE0 in its 16-bit service UUID list — AE00 and FFF0 are real GATT services but
        // only visible after connecting, so the AE00 relaxation never fires here. "fit plus" is
        // therefore matched directly as a QN-family name (#Fit-Plus-not-supported).
        assertThat(QNHandler().supportFor(device("Fit Plus", uuid16(0xFFE0)))).isNotNull()
    }

    @Test
    fun `does not claim ae00 without the fff0 or ffe0 channel`() {
        // ae00 alone: we cannot drive the device, so don't claim it.
        assertThat(QNHandler().supportFor(device("Fit Plus", uuid16(0xAE00)))).isNull()
    }

    @Test
    fun `still claims the classic QN name + fff0 device`() {
        // Regression guard: the original name+service path keeps working.
        assertThat(QNHandler().supportFor(device("QN-Scale", uuid16(0xFFF0)))).isNotNull()
    }
}
