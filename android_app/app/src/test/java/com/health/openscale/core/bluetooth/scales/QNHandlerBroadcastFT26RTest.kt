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
import com.google.common.truth.Truth.assertThat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire-format tests for the QN "AABB" broadcast advertisement, using frames captured
 * from physical hardware:
 * - FITINDEX FT-26R-W (weight-only unit without BIA)
 * - Renpho ES-26M-W (smart scale with BIA body composition)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerBroadcastFT26RTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Manufacturer-specific payload as ScanRecord hands it to the handler. */
    private fun payload(frame: String): ByteArray = hex(frame)

    private fun parse(frame: String) =
        QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, payload(frame))

    // Session 1 (FT-26R) — display read 155.0 lb (70.30 kg) at the end.
    private val moving1  = "aabb00005e0053016d000000ffffff1400671b510e032100" // 70.15
    private val moving2  = "aabb00005e0053016e000000ffffff14007b1b510e032300" // 70.35
    private val settled1 = "aabb00005e00530172000000ffffff1500761b510e032300" // 70.30
    private val settled2 = "aabb00005e0053017b000000ffffff1500761b510e032300" // 70.30

    // Session 2 (FT-26R) — independent capture, settles at 70.35 kg.
    private val moving3  = "aabb00005e005301ef000000ffffff14007b1b510e034900"
    private val settled3 = "aabb00005e005301f0000000ffffff15007b1b510e034400"

    /** Idle advertisement: bit 0 is set, but the weight field is zero. */
    private val idle     = "aabb00005e00530100f09f6affffff15a00000510e03500f"

    // Renpho ES-26M-W captured hardware frames (anonymized to 70.00 kg / 500.0 Ohm):
    private val renphoMoving = "aabb00005e005301fd03a56affffff02a06c1b8813038c12" // 69.80 kg moving (0x1B6C)
    private val renphoSettledWeight = "aabb00005e0053010504a56affffff23a0581b000003b312" // 70.00 kg settled weight
    private val renphoSettledBia = "aabb00005e0053010504a56affffff23a0581b881303b312" // 70.00 kg, 500.0 Ohm BIA (0x1388)

    // ---- Wire format ---------------------------------------------------------

    @Test
    fun `weight decodes to the value shown on the scale display`() {
        // 155.0 lb == 70.307 kg; the scale reports in 0.05 kg steps.
        assertThat(parse(settled1)!!.weightKg).isWithin(0.01f).of(70.30f)
        assertThat(parse(settled2)!!.weightKg).isWithin(0.01f).of(70.30f)
        assertThat(parse(settled3)!!.weightKg).isWithin(0.01f).of(70.35f)
    }

    @Test
    fun `FT-26R never sets the bit 5 stable flag`() {
        listOf(moving1, moving2, settled1, settled2, moving3, settled3, idle).forEach {
            val status = payload(it)[15].toInt() and 0xFF
            assertThat(status and QnBroadcastAdv.FLAG_STABLE_BIT).isEqualTo(0)
        }
    }

    @Test
    fun `status latches from 0x14 to 0x15 when the weight settles on FT-26R`() {
        assertThat(parse(moving1)!!.statusByte).isEqualTo(0x14)
        assertThat(parse(moving2)!!.statusByte).isEqualTo(0x14)
        assertThat(parse(settled1)!!.statusByte).isEqualTo(0x15)
        assertThat(parse(settled2)!!.statusByte).isEqualTo(0x15)
    }

    // ---- Stable detection: these fail if bit-0 support is removed ------------

    @Test
    fun `settled frames are reported as stable`() {
        assertThat(parse(settled1)!!.stable).isTrue()
        assertThat(parse(settled2)!!.stable).isTrue()
        assertThat(parse(settled3)!!.stable).isTrue()
        assertThat(parse(renphoSettledWeight)!!.stable).isTrue()
        assertThat(parse(renphoSettledBia)!!.stable).isTrue()
    }

    @Test
    fun `in-progress frames are not reported as stable`() {
        assertThat(parse(moving1)!!.stable).isFalse()
        assertThat(parse(moving2)!!.stable).isFalse()
        assertThat(parse(moving3)!!.stable).isFalse()
        assertThat(parse(renphoMoving)!!.stable).isFalse()
    }

    // ---- Guards --------------------------------------------------------------

    @Test
    fun `the zero-weight idle advertisement is rejected outright`() {
        // It sets bit 0, so without the weight-range check it would publish 0 kg.
        assertThat(payload(idle)[15].toInt() and QnBroadcastAdv.FLAG_STABLE_BIT_FT26R)
            .isNotEqualTo(0)
        assertThat(parse(idle)).isNull()
    }

    @Test
    fun `bit 0 is only honoured for payloads carrying matching signature`() {
        // Unknown model payload with signature zeroed out: bit 0 (0x15) must not be treated as stable
        val foreign = payload(settled1).copyOf().also {
            it[12] = 0x00; it[13] = 0x00; it[14] = 0x00
            it[19] = 0x00; it[20] = 0x00; it[21] = 0x00
        }
        val frame = QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, foreign)!!

        assertThat(frame.isFt26rFamily).isFalse()
        assertThat(frame.isBiaCapable).isFalse()
        assertThat(frame.statusByte).isEqualTo(0x15)
        assertThat(frame.stable).isFalse() // guarded against unrecognised AABB models
    }

    @Test
    fun `bit 5 still marks a stable measurement on non-FT-26R payloads`() {
        val other = payload(settled1).copyOf().also {
            it[12] = 0x00; it[13] = 0x00; it[14] = 0x00
            it[19] = 0x00; it[20] = 0x00; it[21] = 0x00
            it[15] = 0x20.toByte() // original stable encoding
        }
        val frame = QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, other)!!

        assertThat(frame.isFt26rFamily).isFalse()
        assertThat(frame.stable).isTrue()
    }

    @Test
    fun `malformed payloads are rejected`() {
        assertThat(QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, null)).isNull()
        assertThat(QnBroadcastAdv.parse(0x1234, payload(settled1))).isNull()          // wrong company
        assertThat(QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, hex("aabb00"))).isNull() // short
        val badMagic = payload(settled1).copyOf().also { it[0] = 0x00 }
        assertThat(QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, badMagic)).isNull()
    }

    // ---- End-to-end through the real handler ---------------------------------

    /** Builds a real ScanRecord so the handler runs its own extraction path. */
    private fun scanRecordOf(frame: String): ScanRecord {
        val payload = hex(frame)
        val adv = byteArrayOf(0x02, 0x01, 0x06) +                       // flags
                byteArrayOf((payload.size + 3).toByte(), 0xFF.toByte(), // len, type
                    0xFF.toByte(), 0xFF.toByte()) +                     // company 0xFFFF
                payload
        val m = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        m.isAccessible = true
        return m.invoke(null, adv) as ScanRecord
    }

    @Test
    fun `handler reads a settled frame out of a real ScanRecord`() {
        val record = scanRecordOf(settled1)
        val data = record.getManufacturerSpecificData(QnBroadcastAdv.COMPANY_ID)

        assertThat(data).isNotNull()
        val frame = QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, data)!!
        assertThat(frame.stable).isTrue()
        assertThat(frame.weightKg).isWithin(0.01f).of(70.30f)
    }

    @Test
    fun `parses Renpho ES-26M-W frame with impedance`() {
        val frame = parse(renphoSettledBia)
        assertThat(frame).isNotNull()
        assertThat(frame!!.stable).isTrue()
        assertThat(frame.isBiaCapable).isTrue()
        assertThat(frame.weightKg).isWithin(0.01f).of(70.00f)
        assertThat(frame.impedanceOhm).isNotNull()
        assertThat(frame.impedanceOhm!!).isWithin(0.01f).of(500.0f)
    }

    @Test
    fun `supportFor declares BODY_COMPOSITION only for BIA capable scales`() {
        val ftRecord = scanRecordOf(settled1)
        val ftInfo = com.health.openscale.core.service.ScannedDeviceInfo(
            name = "",
            address = "00:00:5E:00:53:01",
            rssi = -60,
            serviceUuids = emptyList(),
            manufacturerData = android.util.SparseArray<ByteArray>().apply {
                put(QnBroadcastAdv.COMPANY_ID, ftRecord.getManufacturerSpecificData(QnBroadcastAdv.COMPANY_ID)!!)
            }
        )
        val ftSupport = QNHandlerBroadcast().supportFor(ftInfo)
        assertThat(ftSupport).isNotNull()
        assertThat(ftSupport!!.capabilities).doesNotContain(DeviceCapability.BODY_COMPOSITION)

        val renphoRecord = scanRecordOf(renphoSettledBia)
        val renphoInfo = com.health.openscale.core.service.ScannedDeviceInfo(
            name = "",
            address = "00:00:5E:00:53:01",
            rssi = -60,
            serviceUuids = emptyList(),
            manufacturerData = android.util.SparseArray<ByteArray>().apply {
                put(QnBroadcastAdv.COMPANY_ID, renphoRecord.getManufacturerSpecificData(QnBroadcastAdv.COMPANY_ID)!!)
            }
        )
        val renphoSupport = QNHandlerBroadcast().supportFor(renphoInfo)
        assertThat(renphoSupport).isNotNull()
        assertThat(renphoSupport!!.capabilities).contains(DeviceCapability.BODY_COMPOSITION)
    }
}
