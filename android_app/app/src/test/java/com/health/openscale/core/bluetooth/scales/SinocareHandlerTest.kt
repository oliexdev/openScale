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

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleUser
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SinocareHandlerTest {
    @Test
    @Suppress("DEPRECATION")
    fun `accepts CW286 advertisement using alternate manufacturer id`() {
        // Captured CW286 manufacturer payload, wrapped in an AD structure (14 FF 00 00).
        // Payload bytes 0..5: address zeroed, outside the checksum range.
        // Bytes 9..10: 06 1D -> 7430 -> 74.30 kg; XOR of bytes 6..15: D4 (byte 16).
        val advertisement = "14FF0000000000000000CE0000061D0000000001D4"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Android's parser is hidden from the public SDK.
        val parseMethod = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        parseMethod.isAccessible = true
        val record = parseMethod.invoke(null, advertisement) as ScanRecord
        val result = ScanResult(ShadowBluetoothDevice.newInstance("00:11:22:33:44:55"), record, -50, 0L)
        val handler = SinocareHandler()
        val user = ScaleUser()

        // Nine identical readings are required for a stable measurement.
        repeat(8) {
            assertThat(handler.onAdvertisement(result, user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(handler.onAdvertisement(result, user)).isEqualTo(BroadcastAction.CONSUMED_STOP)
    }
}
