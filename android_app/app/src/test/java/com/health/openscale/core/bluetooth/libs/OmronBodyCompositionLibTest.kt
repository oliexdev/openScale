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
import java.util.Calendar

/**
 * Unit tests for the Omron WLC record decoder.
 *
 * The vectors are records assembled field by field from Omron's own bit-level record description,
 * independently of the decoder under test: weight 72.40 kg, body fat 23.5 %, skeletal muscle
 * 31.2 %, BMI 23.7, BMR 1520 kcal, body age 34, measured 2024-03-17 07:45:12. They exercise the
 * fields that share a byte with a neighbour, which is where an off-by-one in the bit positions
 * would show up: body fat with visceral fat, BMR with its classification, and the day/hour pair
 * that straddles bytes 12 and 13.
 */
class OmronBodyCompositionLibTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 48-byte HBF-702T record; visceral fat is stored in half levels (raw 16 → level 8.0). */
    private val record702t = hex(
        "00 00 3a d0 5f 02 4e 18 3b 6d 22 33 89 cc 00 00" +
            "00 00 00 00 00 00 00 00 00 00 5a 80 00 00 00 00" +
            "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
    )

    /** 32-byte HBF-227T record; visceral fat is stored in whole levels (raw 8 → level 8). */
    private val record32 = hex(
        "00 00 3a c8 5f 02 4e 18 3b 6d 22 33 89 cc 00 00" +
            "00 00 00 00 00 00 00 00 00 00 5a 80 00 00 00 00"
    )

    private fun calendarOf(record: OmronBodyCompositionLib.Record) =
        Calendar.getInstance().apply { time = record.timestamp }

    @Test
    fun `decodes every field of an HBF-702T record`() {
        val decoded = OmronBodyCompositionLib.decodeRecord(
            record702t, OmronBodyCompositionLib.PROFILE_HBF_702T
        )

        assertThat(decoded).isNotNull()
        with(decoded!!) {
            assertThat(weightKg).isWithin(1e-4f).of(72.40f)
            assertThat(bodyFatPercent!!).isWithin(1e-4f).of(23.5f)
            assertThat(skeletalMusclePercent!!).isWithin(1e-4f).of(31.2f)
            assertThat(bmi!!).isWithin(1e-4f).of(23.7f)
            assertThat(bmrKcal).isEqualTo(1520)
            assertThat(visceralFatLevel!!).isWithin(1e-4f).of(8.0f)
            assertThat(bodyAgeYears).isEqualTo(34)
        }
    }

    @Test
    fun `reassembles the timestamp scattered across bytes 7 9 and 11 to 13`() {
        val decoded = OmronBodyCompositionLib.decodeRecord(
            record702t, OmronBodyCompositionLib.PROFILE_HBF_702T
        )!!

        with(calendarOf(decoded)) {
            assertThat(get(Calendar.YEAR)).isEqualTo(2024)
            assertThat(get(Calendar.MONTH)).isEqualTo(Calendar.MARCH)
            assertThat(get(Calendar.DAY_OF_MONTH)).isEqualTo(17)
            assertThat(get(Calendar.HOUR_OF_DAY)).isEqualTo(7)
            assertThat(get(Calendar.MINUTE)).isEqualTo(45)
            assertThat(get(Calendar.SECOND)).isEqualTo(12)
        }
    }

    @Test
    fun `the 32 byte models report visceral fat in whole levels`() {
        val decoded = OmronBodyCompositionLib.decodeRecord(
            record32, OmronBodyCompositionLib.PROFILE_HBF_32
        )

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.visceralFatLevel!!).isWithin(1e-4f).of(8.0f)
        // The remaining fields sit at the same offsets as on the 48-byte record.
        assertThat(decoded.weightKg).isWithin(1e-4f).of(72.40f)
        assertThat(decoded.bodyFatPercent!!).isWithin(1e-4f).of(23.5f)
    }

    @Test
    fun `body age is suppressed on the variants that do not measure it`() {
        val withAge = OmronBodyCompositionLib.decodeRecord(
            record32, OmronBodyCompositionLib.PROFILE_HBF_32
        )!!
        val withoutAge = OmronBodyCompositionLib.decodeRecord(
            record32, OmronBodyCompositionLib.PROFILE_HBF_32_NO_BODY_AGE
        )!!

        assertThat(withAge.bodyAgeYears).isEqualTo(34)
        assertThat(withoutAge.bodyAgeYears).isNull()
    }

    @Test
    fun `an unwritten ring buffer slot decodes to nothing`() {
        val erased = ByteArray(48) { 0xFF.toByte() }
        val zeroed = ByteArray(48)

        assertThat(OmronBodyCompositionLib.decodeRecord(erased, OmronBodyCompositionLib.PROFILE_HBF_702T)).isNull()
        assertThat(OmronBodyCompositionLib.decodeRecord(zeroed, OmronBodyCompositionLib.PROFILE_HBF_702T)).isNull()
    }

    @Test
    fun `a record with no weight is not a measurement`() {
        val noWeight = record702t.copyOf().also { it[26] = 0; it[27] = 0 }

        assertThat(OmronBodyCompositionLib.decodeRecord(noWeight, OmronBodyCompositionLib.PROFILE_HBF_702T))
            .isNull()
    }

    @Test
    fun `a record carrying an impossible date is rejected`() {
        // Month 15 cannot occur; byte 11 holds the month in its low nibble.
        val badMonth = record702t.copyOf().also { it[11] = 0x3F }

        assertThat(OmronBodyCompositionLib.decodeRecord(badMonth, OmronBodyCompositionLib.PROFILE_HBF_702T))
            .isNull()
    }

    @Test
    fun `a record shorter than the profile is rejected`() {
        assertThat(OmronBodyCompositionLib.decodeRecord(record32, OmronBodyCompositionLib.PROFILE_HBF_702T))
            .isNull()
    }

    @Test
    fun `unmeasured optional values come back as null rather than zero`() {
        // Clear body fat and visceral fat (byte pair 2-3) and the BMR/classification pair (4-5).
        val sparse = record702t.copyOf().also {
            it[2] = 0; it[3] = 0; it[4] = 0; it[5] = 0
        }

        val decoded = OmronBodyCompositionLib.decodeRecord(
            sparse, OmronBodyCompositionLib.PROFILE_HBF_702T
        )!!

        assertThat(decoded.bodyFatPercent).isNull()
        assertThat(decoded.visceralFatLevel).isNull()
        assertThat(decoded.bmrKcal).isNull()
        assertThat(decoded.weightKg).isWithin(1e-4f).of(72.40f)
    }

    @Test
    fun `slot addresses follow the per-model record stride`() {
        with(OmronBodyCompositionLib.PROFILE_HBF_702T) {
            assertThat(recordAddress(0, 0)).isEqualTo(0x02C0)
            assertThat(recordAddress(0, 29)).isEqualTo(0x02C0 + 29 * 48)
            assertThat(recordAddress(3, 0)).isEqualTo(0x1430)
            assertThat(userSlotCount).isEqualTo(4)
        }
        with(OmronBodyCompositionLib.PROFILE_HBF_32) {
            assertThat(recordAddress(1, 0)).isEqualTo(0x06A0)
            // Each slot is allocated one record more than the ring buffer uses.
            assertThat(recordAddress(1, 0) - recordAddress(0, 0)).isEqualTo(31 * 32)
        }
    }
}
