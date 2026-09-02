/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.usecase

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for [GenericValueJson], the self-describing value payload shared by the
 * sync Intent and the ContentProvider. The provider's insert AND update paths rely on
 * [GenericValueJson.parse] returning every type (incl. custom) in the user's unit and in the
 * field its input type requires, so a build -> parse round-trip must preserve the values
 * regardless of unit, input type, or predefined/custom type.
 *
 * Runs under Robolectric because [GenericValueJson] uses android's org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenericValueJsonTest {

    private companion object {
        const val EPS = 1e-3f

        // Predefined types kept in their canonical unit (no conversion on the wire).
        val weightKg = MeasurementType(id = 1, identity = MeasurementType.WEIGHT.identity, unit = UnitType.KG)
        val fatPercent = MeasurementType(id = 2, identity = MeasurementType.BODY_FAT.identity, unit = UnitType.PERCENT)
        // Predefined type in a non-canonical unit -> exercises inch<->cm conversion on the wire.
        val waistInch = MeasurementType(id = 3, identity = MeasurementType.WAIST.identity, unit = UnitType.INCH)
        // Custom types are matched by typeId (key == "CUSTOM") on parse.
        val chestCustomCm = MeasurementType(id = 50, identity = "user.chest_tape", name = "Chest tape", unit = UnitType.CM)
        val noteCustomText = MeasurementType(
            id = 60, identity = "user.note", name = "Note", unit = UnitType.NONE, inputType = InputFieldType.TEXT
        )
        // Non-FLOAT input types: each lives in a different column of MeasurementValue.
        val heartRateBpm = MeasurementType(
            id = 4, identity = MeasurementType.HEART_RATE.identity, unit = UnitType.BPM, inputType = InputFieldType.INT
        )
        val dateCustom = MeasurementType(
            id = 61, identity = "user.cycle_start", name = "Cycle start", unit = UnitType.NONE, inputType = InputFieldType.DATE
        )
        val timeCustom = MeasurementType(
            id = 62, identity = "user.bedtime", name = "Bedtime", unit = UnitType.NONE, inputType = InputFieldType.TIME
        )

        val allTypes = listOf(
            weightKg, fatPercent, waistInch, chestCustomCm, noteCustomText,
            heartRateBpm, dateCustom, timeCustom,
        )
        val typesById = allTypes.associateBy { it.id }
    }

    private fun value(typeId: Int, float: Float? = null, text: String? = null) =
        MeasurementValue(measurementId = 1, typeId = typeId, floatValue = float, textValue = text)

    /** parse() returns the type itself; these assertions only care about which type it was. */
    private fun roundTrip(values: List<MeasurementValue>): Map<Int, MeasurementValue> =
        GenericValueJson.parse(GenericValueJson.build(values, typesById), allTypes)
            .associate { (type, parsed) -> type.id to parsed }

    private fun roundTripFloats(values: List<MeasurementValue>): Map<Int, Float?> =
        roundTrip(values).mapValues { (_, parsed) -> parsed.floatValue }

    @Test
    fun roundTrip_preservesNumericValuesAcrossPredefinedCustomAndUnits() {
        val parsed = roundTripFloats(
            listOf(
                value(weightKg.id, 80.5f),      // canonical unit, exact
                value(fatPercent.id, 18.4f),    // percent, no conversion
                value(waistInch.id, 36.0f),     // inch -> cm -> inch conversion
                value(chestCustomCm.id, 42.3f), // custom type matched by typeId
            )
        )

        assertThat(parsed[weightKg.id]).isWithin(EPS).of(80.5f)
        assertThat(parsed[fatPercent.id]).isWithin(EPS).of(18.4f)
        assertThat(parsed[waistInch.id]).isWithin(EPS).of(36.0f)
        assertThat(parsed[chestCustomCm.id]).isWithin(EPS).of(42.3f)
    }

    @Test
    fun roundTrip_putsEachInputTypeInItsOwnColumn() {
        // The failure this guards: a value in the wrong column reads as *absent* downstream,
        // because every consumer reads only the column its input type designates.
        val parsed = roundTrip(
            listOf(
                value(weightKg.id, 80.5f),
                MeasurementValue(measurementId = 1, typeId = heartRateBpm.id, intValue = 65),
                value(noteCustomText.id, text = "morning weigh-in"),
                MeasurementValue(measurementId = 1, typeId = dateCustom.id, dateValue = 1_754_899_200_000L),
                MeasurementValue(measurementId = 1, typeId = timeCustom.id, dateValue = 81_000_000L),
            )
        )

        assertThat(parsed[weightKg.id]!!.floatValue).isWithin(EPS).of(80.5f)
        assertThat(parsed[weightKg.id]!!.intValue).isNull()

        assertThat(parsed[heartRateBpm.id]!!.intValue).isEqualTo(65)
        assertThat(parsed[heartRateBpm.id]!!.floatValue).isNull()

        assertThat(parsed[noteCustomText.id]!!.textValue).isEqualTo("morning weigh-in")
        assertThat(parsed[dateCustom.id]!!.dateValue).isEqualTo(1_754_899_200_000L)
        assertThat(parsed[timeCustom.id]!!.dateValue).isEqualTo(81_000_000L)
    }

    @Test
    fun parse_returnsValuesWithoutAMeasurementId() {
        // The caller owns the measurementId — parse() must not invent one.
        val parsed = roundTrip(listOf(value(weightKg.id, 80.5f)))

        assertThat(parsed[weightKg.id]!!.measurementId).isEqualTo(0)
    }

    @Test
    fun parse_skipsAnEntryThatCarriesNothingItsTypeCanHold() {
        // build() never emits "value" for a TEXT type, but a foreign producer might. There is no
        // column for it, so the entry is dropped rather than written to floatValue.
        val json = """[{"identity":"${noteCustomText.identity}","unit":"","value":42.0}]"""

        val parsed = GenericValueJson.parse(json, allTypes)

        assertThat(parsed).isEmpty()
    }

    @Test
    fun parse_ignoresTypesUnknownToTheReceiver() {
        // Built with the full type set, but parsed by a receiver that doesn't know the waist type
        // (e.g. it was removed) -> that entry is dropped instead of failing the whole payload.
        val json = GenericValueJson.build(
            listOf(value(weightKg.id, 70f), value(waistInch.id, 34f)),
            typesById,
        )
        val parsed = GenericValueJson.parse(json, allTypes.filter { it.id != waistInch.id })
            .associate { (type, value) -> type.id to value }

        assertThat(parsed).containsKey(weightKg.id)
        assertThat(parsed).doesNotContainKey(waistInch.id)
    }

    @Test
    fun `identity is the only wire identifier - stray legacy fields are ignored`() {
        // Unknown/legacy fields in the payload must not influence matching.
        val json = """[{"identity":"${weightKg.identity}","typeId":9999,"key":"CUSTOM","unit":"kg","value":70.0}]"""

        val parsed = GenericValueJson.parse(json, allTypes)

        assertThat(parsed.single().first.id).isEqualTo(weightKg.id)
    }

    @Test
    fun `custom types match across installations via identity, where typeId cannot`() {
        // The sender's typeId means nothing here; the identity does.
        val json = """[{"identity":"${chestCustomCm.identity}","typeId":12345,"key":"CUSTOM","unit":"cm","value":100.0}]"""

        val parsed = GenericValueJson.parse(json, allTypes)

        assertThat(parsed.single().first.id).isEqualTo(chestCustomCm.id)
    }

    @Test
    fun `entries without an identity are dropped - pre-v3 payloads are not a supported input`() {
        val json = """[{"typeId":${chestCustomCm.id},"key":"CUSTOM","unit":"cm","value":100.0},""" +
            """{"key":"WEIGHT","unit":"kg","value":70.0}]"""

        assertThat(GenericValueJson.parse(json, allTypes)).isEmpty()
    }

    @Test
    fun `outbound payloads are identity-only - API v3 emits no legacy pair`() {
        val json = GenericValueJson.build(listOf(value(weightKg.id, 70f)), typesById)

        assertThat(json).contains("\"identity\":\"${weightKg.identity}\"")
        assertThat(json).doesNotContain("\"key\"")
        assertThat(json).doesNotContain("\"typeId\"")
    }
}
