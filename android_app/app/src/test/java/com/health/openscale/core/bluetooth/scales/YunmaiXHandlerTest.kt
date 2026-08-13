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
 * Tests for [YunmaiXAdv] frame parsing and [YunmaiXHandler.supportFor] matching.
 *
 * Fixtures are synthetic frames built to the reverse-engineered YMBS-M268 layout via
 * [frame] (matching the shape observed on real hardware), not personal captures. The
 * frames use a placeholder device address; Android parses the first two AD bytes as the
 * manufacturer id, so [frame] returns the remaining 14-byte payload.
 *
 * Robolectric is required for android.util.SparseArray in ScannedDeviceInfo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YunmaiXHandlerTest {

    // Placeholder device: the advertisement echoes the MAC byte-reversed, and the last two
    // bytes become Android's manufacturer id.
    private val ADDRESS = "C0:FF:EE:12:34:56"
    private val MFR_ID = 0x3456 // (0x34 << 8) or 0x56

    /**
     * Build a 14-byte manufacturer payload (as split by Android) for the placeholder device.
     *
     * @param state       0x01 live, 0x02 stable, 0x03 final
     * @param weightRaw   weight in units of 0.01 kg (u16 BE)
     * @param impedance   impedance in Ω (u16 BE); 0 until the final frame
     * @param sessionByte the per-weigh-in byte at offset [7]; varies between sessions
     */
    private fun frame(state: Int, weightRaw: Int, impedance: Int = 0, sessionByte: Int = 0x04): ByteArray {
        val body = byteArrayOf(
            0x12.toByte(), 0xEE.toByte(), 0xFF.toByte(), 0xC0.toByte(), // MAC bytes 2..5, reversed
            0x0B, 0x74, 0x17,                                           // signature
            sessionByte.toByte(),
            state.toByte(),
            (weightRaw shr 8).toByte(), weightRaw.toByte(),
            (impedance shr 8).toByte(), impedance.toByte()
        )
        var xor = 0
        for (i in 7..12) xor = xor xor (body[i].toInt() and 0xFF)
        return body + xor.toByte()
    }

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun device(
        payload: ByteArray? = null,
        mfrId: Int = MFR_ID,
        address: String = ADDRESS,
        vararg services: Int
    ) = ScannedDeviceInfo(
        name = "",
        address = address,
        rssi = -50,
        serviceUuids = services.map { uuid16(it) },
        manufacturerData = payload?.let {
            SparseArray<ByteArray>().apply { put(mfrId, it) }
        },
    )

    // --- Frame parsing --------------------------------------------------------

    @Test
    fun `parses live frame`() {
        val f = YunmaiXAdv.parse(MFR_ID, frame(0x01, weightRaw = 7955), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_LIVE)
        assertThat(f.weightKg).isWithin(1e-3f).of(79.55f)
        assertThat(f.impedanceOhm).isEqualTo(0)
    }

    @Test
    fun `parses stable frame`() {
        val f = YunmaiXAdv.parse(MFR_ID, frame(0x02, weightRaw = 8000), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_STABLE)
        assertThat(f.weightKg).isWithin(1e-3f).of(80.00f)
    }

    @Test
    fun `parses final frame with impedance`() {
        val f = YunmaiXAdv.parse(MFR_ID, frame(0x03, weightRaw = 8000, impedance = 500), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_FINAL)
        assertThat(f.weightKg).isWithin(1e-3f).of(80.00f)
        assertThat(f.impedanceOhm).isEqualTo(500)
    }

    @Test
    fun `parses final frame regardless of the per-session byte`() {
        // byte[7] varies between weigh-ins and must not be treated as a constant.
        val f = YunmaiXAdv.parse(MFR_ID, frame(0x03, weightRaw = 8000, impedance = 500, sessionByte = 0x10), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_FINAL)
        assertThat(f.weightKg).isWithin(1e-3f).of(80.00f)
        assertThat(f.impedanceOhm).isEqualTo(500)
    }

    @Test
    fun `parses idle frame as zero weight`() {
        val f = YunmaiXAdv.parse(MFR_ID, frame(0x01, weightRaw = 0), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_LIVE)
        assertThat(f.weightKg).isEqualTo(0f)
    }

    @Test
    fun `rejects corrupted checksum`() {
        val corrupted = frame(0x03, weightRaw = 8000, impedance = 500).also { it[9] = 0x1C } // weight changed, checksum stale
        assertThat(YunmaiXAdv.parse(MFR_ID, corrupted, ADDRESS)).isNull()
    }

    @Test
    fun `rejects wrong signature`() {
        val wrongSig = frame(0x03, weightRaw = 8000, impedance = 500).also { it[5] = 0x75 }
        assertThat(YunmaiXAdv.parse(MFR_ID, wrongSig, ADDRESS)).isNull()
    }

    @Test
    fun `rejects frame whose MAC echo does not match the device address`() {
        assertThat(YunmaiXAdv.parse(MFR_ID, frame(0x03, weightRaw = 8000, impedance = 500), "AA:BB:CC:DD:EE:FF")).isNull()
    }

    @Test
    fun `rejects wrong payload size`() {
        assertThat(YunmaiXAdv.parse(MFR_ID, frame(0x03, weightRaw = 8000).copyOf(13), ADDRESS)).isNull()
    }

    // --- Device matching --------------------------------------------------------

    @Test
    fun `claims nameless device advertising a valid measurement frame`() {
        assertThat(YunmaiXHandler().supportFor(device(frame(0x03, weightRaw = 8000, impedance = 500)))).isNotNull()
    }

    @Test
    fun `claims nameless device advertising an idle frame`() {
        assertThat(YunmaiXHandler().supportFor(device(frame(0x01, weightRaw = 0)))).isNotNull()
    }

    @Test
    fun `claims saved-device snapshot via service uuid 0x1320`() {
        // Snapshots carry no manufacturer data, only the advertised service UUIDs.
        assertThat(YunmaiXHandler().supportFor(device(null, MFR_ID, ADDRESS, 0x1320))).isNotNull()
    }

    @Test
    fun `does not claim device with foreign manufacturer data`() {
        // Same length, invalid signature/checksum (e.g. a wearable's frame)
        val foreign = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)
        assertThat(YunmaiXHandler().supportFor(device(foreign))).isNull()
    }

    @Test
    fun `does not claim device without manufacturer data or 0x1320 service`() {
        assertThat(YunmaiXHandler().supportFor(device(null, MFR_ID, ADDRESS, 0x180D, 0x180F))).isNull()
    }

    @Test
    fun `does not claim valid frame relocated to another device address`() {
        // Replayed/echoed frame on a different MAC must not match.
        assertThat(YunmaiXHandler().supportFor(device(frame(0x03, weightRaw = 8000, impedance = 500), address = "AA:BB:CC:DD:EE:FF"))).isNull()
    }
}
