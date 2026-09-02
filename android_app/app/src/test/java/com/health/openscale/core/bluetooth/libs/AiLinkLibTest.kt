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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [AiLinkLib].
 *
 * [AiLinkLib] was reverse-engineered from an AiLink "EL1" broadcast scale (vendor app
 * `com.pingwang.elink`) and verified against live advertisements from that hardware. The
 * fixtures below are **synthetic**: each one was produced by running the protocol's own TEA
 * encryption over a chosen payload, so they still exercise the real key derivation, the real
 * cipher and the real checksum — but they carry invented weights and an invented MAC rather than
 * anyone's health data.
 *
 * Every fixture is laid out exactly as `ScanRecord.getManufacturerSpecificData(0x0301)` returns
 * it, i.e. with the two company-id bytes already stripped by Android.
 */
class AiLinkLibTest {

    /** Company id as Android reports it; really AiLink's CID (0x01) and VID (0x03). */
    private val COMPANY_ID = 0x0301

    private fun adv(hex: String) = hex.replace(" ", "").chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()

    /** status 0xFF (complete), 70.0 kg, impedance 500 — the frame the scale latches and repeats. */
    private val COMPLETED = adv("01 5f4e3d2c1b0a 8f 10e69cf7cb452cccffff")

    /** status 0xFF (complete) from an earlier step-on, 68.4 kg. */
    private val COMPLETED_684 = adv("01 5f4e3d2c1b0a 7e 945ab8103c0b750effff")

    /** status 0x00 (measuring) while a 70.0 kg reading is still live. */
    private val MEASURING = adv("01 5f4e3d2c1b0a 33 31c5f84954a1b455ffff")

    /** status 0x00 with no load: raw weight 0 and the flags' low bit clear. */
    private val IDLE = adv("01 5f4e3d2c1b0a 81 cd9244ffe6049c5bffff")

    /** Complete, but the scale's impedance pass failed and it reported 0 ohms. */
    private val NO_IMPEDANCE = adv("01 5f4e3d2c1b0a f9 95fd662927ee4283ffff")

    // --- Key derivation --------------------------------------------------------

    @Test
    fun derives_the_broadcast_tea_key_from_cid_vid_pid() {
        // Recovered from Java_com_pinwang_ailinkble_AiLinkPwdUtil_decryptBroadcast:
        // k0 = pid + 0x41493000, k1 = vid + 0x4C327900, k2 = cid + 0x31783100, k3 = 0x306C6531
        assertThat(AiLinkLib.broadcastKey(cid = 1, vid = 3, pid = 1).toList())
            .containsExactly(0x41493001, 0x4C327903, 0x31783101, 0x306C6531)
            .inOrder()
    }

    @Test
    fun subtracts_0xffff_from_ids_at_or_above_0x10000() {
        // The native code folds the app's 0x1xxxx device types back into a small number
        // (e.g. type 65537 -> 2) before adding the constants.
        assertThat(AiLinkLib.broadcastKey(cid = 65537, vid = 3, pid = 1)[2])
            .isEqualTo(0x31783100 + 2)
    }

    // --- Decoding --------------------------------------------------------------

    @Test
    fun decodes_the_completed_measurement() {
        val b = AiLinkLib.parse(COMPANY_ID, COMPLETED)!!

        assertThat(b.macAddress).isEqualTo("0A:1B:2C:3D:4E:5F")
        assertThat(b.status).isEqualTo(AiLinkLib.STATUS_COMPLETE)
        assertThat(b.sequence).isEqualTo(42)
        assertThat(b.weightUnit).isEqualTo(AiLinkLib.UNIT_KG)
        assertThat(b.decimals).isEqualTo(1)
        assertThat(b.isNegative).isFalse()
        assertThat(b.rawWeight).isEqualTo(700)
        assertThat(b.weightKg).isWithin(0.001f).of(70.0f)
        assertThat(b.impedance).isEqualTo(500)
        assertThat(b.algorithm).isEqualTo(1)
        assertThat(b.isComplete).isTrue()
        assertThat(b.isUsable).isTrue()
    }

    @Test
    fun decodes_an_earlier_completed_measurement() {
        val b = AiLinkLib.parse(COMPANY_ID, COMPLETED_684)!!

        assertThat(b.status).isEqualTo(AiLinkLib.STATUS_COMPLETE)
        assertThat(b.sequence).isEqualTo(12)
        assertThat(b.weightKg).isWithin(0.001f).of(68.4f)
    }

    @Test
    fun decodes_a_live_measuring_frame_but_does_not_call_it_complete() {
        val b = AiLinkLib.parse(COMPANY_ID, MEASURING)!!

        assertThat(b.status).isEqualTo(AiLinkLib.STATUS_MEASURING)
        assertThat(b.sequence).isEqualTo(26)
        assertThat(b.weightKg).isWithin(0.001f).of(70.0f)
        assertThat(b.isComplete).isFalse()
    }

    @Test
    fun treats_an_idle_frame_as_unusable() {
        val b = AiLinkLib.parse(COMPANY_ID, IDLE)!!

        assertThat(b.status).isEqualTo(AiLinkLib.STATUS_MEASURING)
        assertThat(b.rawWeight).isEqualTo(0)
        assertThat(b.weightKg).isWithin(0.001f).of(0f)
        assertThat(b.isUsable).isFalse()
    }

    /** A completed frame stays usable when the impedance pass failed; only the ohms are missing. */
    @Test
    fun decodes_a_completed_frame_that_reports_no_impedance() {
        val b = AiLinkLib.parse(COMPANY_ID, NO_IMPEDANCE)!!

        assertThat(b.isComplete).isTrue()
        assertThat(b.isUsable).isTrue()
        assertThat(b.impedance).isEqualTo(0)
        assertThat(b.weightKg).isWithin(0.001f).of(70.0f)
    }

    /** Each frame carries a rolling counter, so consecutive readings are distinguishable. */
    @Test
    fun sequence_differs_between_frames() {
        val seen = listOf(COMPLETED, COMPLETED_684, MEASURING, IDLE)
            .map { AiLinkLib.parse(COMPANY_ID, it)!!.sequence }

        assertThat(seen).containsNoDuplicates()
    }

    // --- Rejection -------------------------------------------------------------

    @Test
    fun rejects_a_frame_whose_checksum_does_not_match() {
        val corrupted = COMPLETED.copyOf().also { it[7] = (it[7] + 1).toByte() }

        assertThat(AiLinkLib.parse(COMPANY_ID, corrupted)).isNull()
    }

    @Test
    fun rejects_a_frame_that_is_too_short() {
        assertThat(AiLinkLib.parse(COMPANY_ID, COMPLETED.copyOf(17))).isNull()
    }

    /** The checksum covers the still-encrypted payload, so a flipped payload byte is caught. */
    @Test
    fun rejects_a_frame_with_a_corrupted_payload() {
        val corrupted = COMPLETED.copyOf().also { it[8] = (it[8] + 1).toByte() }

        assertThat(AiLinkLib.parse(COMPANY_ID, corrupted)).isNull()
    }

    /**
     * An all-zero record satisfies the checksum trivially (0 == 0), so the checksum alone cannot
     * prove a record is AiLink. Decrypting it yields status 0x55, which is not a status the scale
     * ever sends — that is what rejects another vendor's record.
     */
    @Test
    fun rejects_an_all_zero_record_from_another_vendor() {
        assertThat(AiLinkLib.parse(0x00FF, ByteArray(18))).isNull()
    }

    /** A frame decrypted with the wrong key lands on a nonsense status and must be rejected. */
    @Test
    fun rejects_a_frame_decrypted_with_the_wrong_device_ids() {
        assertThat(AiLinkLib.parse(0x0405, COMPLETED)).isNull()
    }
}
