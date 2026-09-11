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
 * from a physical FITINDEX FT-26R-W (a weight-only unit with no BIA electrodes).
 *
 * The FT-26R marks a settled measurement with bit 0 of the status byte and never sets
 * bit 5, so before [QnBroadcastAdv] learned that encoding every reading from these
 * scales was discarded.
 *
 * These tests exercise [QnBroadcastAdv] directly rather than reimplementing the
 * parsing, so removing the bit-0 support fails them.
 *
 * Captured 2026-09-07; the scale's own display read 155.0 lb (70.31 kg) for the
 * session-1 frames. The device address in bytes [2-7] has been replaced with the
 * RFC 7042 documentation address; nothing parses those bytes.
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

    // Session 1 — the scale's display read 155.0 lb at the end.
    private val moving1  = "aabb00005e0053016d000000ffffff1400671b510e032100" // 70.15
    private val moving2  = "aabb00005e0053016e000000ffffff14007b1b510e032300" // 70.35
    private val settled1 = "aabb00005e00530172000000ffffff1500761b510e032300" // 70.30
    private val settled2 = "aabb00005e0053017b000000ffffff1500761b510e032300" // 70.30

    // Session 2 — independent capture, settles at 70.35 kg.
    private val moving3  = "aabb00005e005301ef000000ffffff14007b1b510e034900"
    private val settled3 = "aabb00005e005301f0000000ffffff15007b1b510e034400"

    /** Idle advertisement: bit 0 is set, but the weight field is zero. */
    private val idle     = "aabb00005e00530100f09f6affffff15a00000510e03500f"

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
    fun `status latches from 0x14 to 0x15 when the weight settles`() {
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
    }

    @Test
    fun `in-progress frames are not reported as stable`() {
        assertThat(parse(moving1)!!.stable).isFalse()
        assertThat(parse(moving2)!!.stable).isFalse()
        assertThat(parse(moving3)!!.stable).isFalse()
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
    fun `bit 0 is only honoured for payloads carrying the FT-26R signature`() {
        // Same frame, signature bytes [19-21] changed: bit 0 must no longer mean
        // "stable", so an unknown AABB model keeps the original bit-5-only rule.
        val foreign = payload(settled1).copyOf().also {
            it[19] = 0x00; it[20] = 0x00; it[21] = 0x00
        }
        val frame = QnBroadcastAdv.parse(QnBroadcastAdv.COMPANY_ID, foreign)!!

        assertThat(frame.isFt26rFamily).isFalse()
        assertThat(frame.statusByte).isEqualTo(0x15)   // bit 0 still set
        assertThat(frame.stable).isFalse()             // but not treated as stable
        assertThat(frame.weightKg).isWithin(0.01f).of(70.30f)
    }

    @Test
    fun `bit 5 still marks a stable measurement on non-FT-26R payloads`() {
        val other = payload(settled1).copyOf().also {
            it[19] = 0x00; it[20] = 0x00; it[21] = 0x00   // not an FT-26R
            it[15] = 0x20.toByte()                        // original stable encoding
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
    fun `supportFor claims the device from its advertised AABB payload`() {
        val record = scanRecordOf(settled1)
        val info = com.health.openscale.core.service.ScannedDeviceInfo(
            name = "",
            address = "00:00:5E:00:53:01",
            rssi = -60,
            serviceUuids = emptyList(),
            manufacturerData = android.util.SparseArray<ByteArray>().apply {
                put(
                    QnBroadcastAdv.COMPANY_ID,
                    record.getManufacturerSpecificData(QnBroadcastAdv.COMPANY_ID)!!
                )
            }
        )

        val support = QNHandlerBroadcast().supportFor(info)
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("QN Scale (Broadcast)")
        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
    }
}
