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
 * Claiming tests for [XiaomiS200Handler]. The decrypt/parse core is covered on its own, without
 * needing an Android `ScanResult`, by `XiaomiS200LibTest` — the same split the S400/S800 drivers
 * use, since building a real `android.bluetooth.le.ScanResult` isn't exercised anywhere in this
 * test tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XiaomiS200HandlerTest {

    private val serviceUuidFe95 = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    private fun device(
        name: String,
        serviceData: Map<UUID, ByteArray> = emptyMap(),
    ) = ScannedDeviceInfo(
        name = name,
        address = "D0:7B:6F:27:D7:29",
        rssi = -50,
        serviceUuids = emptyList(),
        manufacturerData = null,
        serviceData = serviceData,
    )

    /** Real idle-beacon 0xFE95 payload (product id 0x4DCB) from xiaomi-ble issue #263. */
    private fun serviceDataFor(productIdLE: ByteArray) =
        mapOf(serviceUuidFe95 to (byteArrayOf(0x10, 0x5b) + productIdLE + byteArrayOf(0, 0, 0, 0, 0, 0, 0)))

    @Test
    fun `claims a device by its real-world advertised name`() {
        val handler = XiaomiS200Handler()
        assertThat(handler.supportFor(device("Xiaomi Scale S200 ABC6"))).isNotNull()
        assertThat(handler.supportFor(device("xiaomi scale s200 abc6"))).isNotNull()
        assertThat(handler.supportFor(device("MJTZC02YM"))).isNotNull()
    }

    @Test
    fun `claims a device by its FE95 product id when the name does not match`() {
        val handler = XiaomiS200Handler()
        // 0x4DCB LE = cb 4d
        val support = handler.supportFor(device("Unnamed", serviceDataFor(byteArrayOf(0xcb.toByte(), 0x4d))))
        assertThat(support).isNotNull()
    }

    @Test
    fun `does not claim an unrelated device`() {
        val handler = XiaomiS200Handler()
        assertThat(handler.supportFor(device("Scale Up"))).isNull()
        assertThat(handler.supportFor(device("MIJIA SCALE S800"))).isNull()
        // A foreign product id under the same 0xFE95 service (e.g. the S400's).
        assertThat(handler.supportFor(device("Unnamed", serviceDataFor(byteArrayOf(0xd9.toByte(), 0x30))))).isNull()
    }

    @Test
    fun `saved device snapshot remains identifiable without service data`() {
        assertThat(XiaomiS200Handler().supportFor(device("Xiaomi Scale S200 ABC6"))).isNotNull()
    }

    @Test
    fun `declares weight-only, broadcast-only support with no capabilities`() {
        // The S200 has no impedance electrodes (no body composition) and, unlike GATT scales,
        // cannot stream a live/in-progress reading over broadcast — its object only ever appears
        // with a finalized weight. Declaring LIVE_WEIGHT_STREAM would be misleading, not merely
        // "supported but unimplemented".
        val support = XiaomiS200Handler().supportFor(device("Xiaomi Scale S200 ABC6"))!!
        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        assertThat(support.implemented).isEmpty()
        assertThat(support.capabilities).isEmpty()
    }
}
