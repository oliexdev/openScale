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
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.manufacturerData
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Synthetic frames in the layout observed on an "AAA07A" scale (Posture FG220LB, Fitdays app):
 * company id 0xA0AC, bytes 0..5 = [ADDRESS] reversed, bytes 6..11 = payload XOR 0xA0. The reserved
 * bits (0x6C0000) are kept as captured; the weights are made up. The impedance frame is the one
 * the scale sent when no impedance could be measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AAAxHandlerTest {

    private val companyId = 0xA0AC
    private val serviceFfb0 = uuid16(0xFFB0)

    private val macEcho = "563412EEFFC0"
    private val settling = hex("$macEcho A0CD84660DA4")
    private val stable = hex("$macEcho 20CD84580DB6")
    private val impedance = hex("$macEcho A0A0A0A206A8")

    private fun device(name: String, record: ByteArray?, address: String = ADDRESS) = ScannedDeviceInfo(
        name = name,
        address = address,
        rssi = -60,
        serviceUuids = listOf(serviceFfb0),
        manufacturerData = record?.let { manufacturerData(companyId to it) },
    )

    @Test
    fun `decodes the captured weight frames`() {
        val final = AAAxHandler.decodePayload(companyId, stable)!!
        assertThat(final).isEqualTo(hex("806D24F8AD16"))
        // state bit set, 18-bit grams = 75 000
        assertThat(final[0].toInt() and 0x80).isEqualTo(0x80)
        assertThat(((final[1].toInt() and 0x03) shl 16) or ((final[2].toInt() and 0xFF) shl 8) or (final[3].toInt() and 0xFF))
            .isEqualTo(75_000)

        assertThat(AAAxHandler.decodePayload(companyId, settling)).isEqualTo(hex("006D24C6AD04"))
        assertThat(AAAxHandler.decodePayload(companyId, impedance)!![4]).isEqualTo(0xA6.toByte())
    }

    @Test
    fun `rejects a frame with a broken checksum`() {
        val corrupted = stable.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertThat(AAAxHandler.decodePayload(companyId, corrupted)).isNull()
    }

    @Test
    fun `claims an unknown name by its frame fingerprint as broadcast-only`() {
        val support = AAAxHandler().supportFor(device("AAA0ZZ", stable))!!
        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        assertThat(AAAxHandler().supportFor(device("", impedance))).isNotNull()
    }

    @Test
    fun `ignores a frame that does not echo the advertiser address`() {
        assertThat(AAAxHandler().supportFor(device("AAA0ZZ", stable, address = "11:22:33:44:55:66"))).isNull()
    }

    @Test
    fun `saved AAA07A snapshot without manufacturer data is still recognised by name`() {
        assertThat(AAAxHandler().supportFor(device("AAA07A", record = null))).isNotNull()
    }

    @Test
    fun `leaves genuine MGB scales alone`() {
        assertThat(AAAxHandler().supportFor(device("swan", record = null))).isNull()
    }

    @Test
    fun `is registered ahead of MGBHandler, which also claims the 0xFFB0 service`() {
        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }

        assertThat(order.indexOf("AAAxHandler")).isLessThan(order.indexOf("MGBHandler"))
        assertThat(MGBHandler().supportFor(device("AAA07A", stable))).isNotNull()
    }

    private companion object {
        const val ADDRESS = "C0:FF:EE:12:34:56"
    }
}
