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

import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Device-matching tests for [AiLinkBroadcastHandler].
 *
 * Because the payload is encrypted, the only honest fingerprint is the F0A0 broadcast service
 * plus a manufacturer record that actually decrypts — so these tests pin both halves of that
 * fingerprint, and in particular that the handler stays quiet for devices it cannot decode.
 *
 * The fixture is synthetic: it was built by running the protocol's own TEA encryption over an
 * invented 70.0 kg reading, so it is byte-for-byte valid without containing real health data.
 *
 * Robolectric supplies a working [SparseArray]; the decoding itself is covered by `AiLinkLibTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiLinkBroadcastHandlerTest {

    private val AILINK_SERVICE: UUID =
        UUID.fromString("0000f0a0-0000-1000-8000-00805f9b34fb")

    /** Really CID 0x01 + VID 0x03, which Android reports as a company id. */
    private val COMPANY_ID = 0x0301

    /** A completed measurement (status 0xFF, 70.0 kg, impedance 500). */
    private val RECORD = hex("01 5f4e3d2c1b0a 8f 10e69cf7cb452cccffff")

    private fun hex(s: String) = s.replace(" ", "").chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()

    private fun advertisement(
        services: List<UUID> = listOf(AILINK_SERVICE),
        manufacturerData: List<Pair<Int, ByteArray>> = listOf(COMPANY_ID to RECORD),
    ) = ScannedDeviceInfo(
        name = "EL1",
        address = "0A:1B:2C:3D:4E:5F",
        rssi = -85,
        serviceUuids = services,
        manufacturerData = SparseArray<ByteArray>().apply {
            manufacturerData.forEach { (id, data) -> put(id, data) }
        },
    )

    @Test
    fun claims_a_real_ailink_broadcast_advertisement() {
        assertThat(AiLinkBroadcastHandler().supportFor(advertisement())).isNotNull()
    }

    @Test
    fun reports_the_device_as_broadcast_only() {
        val support = AiLinkBroadcastHandler().supportFor(advertisement())!!

        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        assertThat(support.implemented).contains(DeviceCapability.LIVE_WEIGHT_STREAM)
    }

    /**
     * The scale measures impedance for real, so body composition is derived from it.
     *
     * Confirmed on hardware with a controlled experiment: barefoot the scale completed reporting
     * 500 ohms, while standing on it through footwear it ran the same BIA phase, detected the
     * broken circuit and completed with `status 3 (impedance failed)` and 0 ohms. A device
     * emitting a hardcoded constant could not distinguish those two cases.
     */
    @Test
    fun claims_body_composition_because_impedance_is_really_measured() {
        val support = AiLinkBroadcastHandler().supportFor(advertisement())!!

        assertThat(support.implemented).contains(DeviceCapability.BODY_COMPOSITION)
    }

    @Test
    fun ignores_a_device_without_the_ailink_broadcast_service() {
        // Same payload, but no F0A0: not an AiLink broadcast scale.
        assertThat(AiLinkBroadcastHandler().supportFor(advertisement(services = emptyList())))
            .isNull()
    }

    @Test
    fun ignores_a_record_whose_checksum_does_not_validate() {
        val corrupted = RECORD.copyOf().also { it[7] = (it[7] + 1).toByte() }

        assertThat(
            AiLinkBroadcastHandler()
                .supportFor(advertisement(manufacturerData = listOf(COMPANY_ID to corrupted)))
        ).isNull()
    }

    @Test
    fun ignores_an_advertisement_with_no_manufacturer_data() {
        val noMfr = ScannedDeviceInfo(
            name = "EL1",
            address = "0A:1B:2C:3D:4E:5F",
            rssi = -85,
            serviceUuids = listOf(AILINK_SERVICE),
            manufacturerData = null,
        )

        assertThat(AiLinkBroadcastHandler().supportFor(noMfr)).isNull()
    }

    @Test
    fun ignores_an_unrelated_vendors_record_on_the_same_service() {
        assertThat(
            AiLinkBroadcastHandler()
                .supportFor(advertisement(manufacturerData = listOf(0x00FF to ByteArray(18))))
        ).isNull()
    }
}
