/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.utils

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasureUnit
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import org.junit.Test

class ConverterUtilsTest {

    private companion object {
        const val EPS = 1e-2f
    }

    // ---- weight ---------------------------------------------------------------------------------

    @Test
    fun kg_isPassthrough() {
        assertThat(ConverterUtils.toKilogram(72.5f, WeightUnit.KG)).isWithin(EPS).of(72.5f)
        assertThat(ConverterUtils.fromKilogram(72.5f, WeightUnit.KG)).isWithin(EPS).of(72.5f)
    }

    @Test
    fun lb_roundTripsThroughKg() {
        val kg = ConverterUtils.toKilogram(160f, WeightUnit.LB)
        assertThat(kg).isWithin(EPS).of(72.5747f)
        assertThat(ConverterUtils.fromKilogram(kg, WeightUnit.LB)).isWithin(EPS).of(160f)
    }

    @Test
    fun st_roundTripsThroughKg() {
        val kg = ConverterUtils.toKilogram(11f, WeightUnit.ST)
        assertThat(ConverterUtils.fromKilogram(kg, WeightUnit.ST)).isWithin(EPS).of(11f)
    }

    // ---- length ---------------------------------------------------------------------------------

    @Test
    fun inch_roundTripsThroughCentimeter() {
        val cm = ConverterUtils.toCentimeter(10f, MeasureUnit.INCH)
        assertThat(cm).isWithin(EPS).of(25.4f)
        assertThat(ConverterUtils.fromCentimeter(cm, MeasureUnit.INCH)).isWithin(EPS).of(10f)
    }

    // ---- stone/pound decomposition --------------------------------------------------------------

    @Test
    fun decimalStToStLb_basic() {
        assertThat(ConverterUtils.decimalStToStLb(0.0)).isEqualTo(0 to 0)
        assertThat(ConverterUtils.decimalStToStLb(1.0)).isEqualTo(1 to 0)
        assertThat(ConverterUtils.decimalStToStLb(2.5)).isEqualTo(2 to 7)
    }

    @Test
    fun decimalStToStLb_normalizesCarryWhenPoundsRoundToFourteen() {
        // 1.99 st = 27.86 lb -> 1 st + round(13.86)=14 lb -> normalized to 2 st 0 lb
        assertThat(ConverterUtils.decimalStToStLb(1.99)).isEqualTo(2 to 0)
    }

    @Test
    fun stLbToStDecimal_roundTrip() {
        assertThat(ConverterUtils.stLbToStDecimal(2, 7)).isWithin(1e-6).of(2.5)
        val (st, lb) = ConverterUtils.decimalStToStLb(10.5)
        assertThat(ConverterUtils.stLbToStDecimal(st, lb)).isWithin(1e-6).of(10.5)
    }

    // ---- convertFloatValueUnit ------------------------------------------------------------------

    @Test
    fun convertFloatValueUnit_sameUnitIsPassthrough() {
        assertThat(ConverterUtils.convertFloatValueUnit(50f, UnitType.KG, UnitType.KG)).isEqualTo(50f)
    }

    @Test
    fun convertFloatValueUnit_kgToLb() {
        assertThat(ConverterUtils.convertFloatValueUnit(72.5747f, UnitType.KG, UnitType.LB))
            .isWithin(EPS).of(160f)
    }

    @Test
    fun convertFloatValueUnit_unsupportedConversionReturnsOriginal() {
        // percent has no conversion to kg -> original value returned unchanged
        assertThat(ConverterUtils.convertFloatValueUnit(42f, UnitType.PERCENT, UnitType.KG)).isEqualTo(42f)
    }

    // ---- percentage-or-mass body composition ---------------------------------------------------

    @Test
    fun compositionConversion_proteinPercentToKg_usesBodyWeight() {
        val converted = ConverterUtils.convertPercentageOrMassCompositionUnit(
            value = 12.8f,
            fromUnit = UnitType.PERCENT,
            toUnit = UnitType.KG,
            bodyWeightKg = 85.55f,
        )

        assertThat(converted).isNotNull()
        assertThat(converted!!).isWithin(1e-4f).of(10.9504f)
    }

    @Test
    fun compositionConversion_skeletalMusclePercentRoundTripsThroughKg() {
        val massKg = ConverterUtils.convertPercentageOrMassCompositionUnit(
            value = 38.6f,
            fromUnit = UnitType.PERCENT,
            toUnit = UnitType.KG,
            bodyWeightKg = 85.55f,
        )
        val percent = ConverterUtils.convertPercentageOrMassCompositionUnit(
            value = massKg!!,
            fromUnit = UnitType.KG,
            toUnit = UnitType.PERCENT,
            bodyWeightKg = 85.55f,
        )

        assertThat(massKg).isWithin(1e-4f).of(33.0223f)
        assertThat(percent).isNotNull()
        assertThat(percent!!).isWithin(1e-4f).of(38.6f)
    }

    @Test
    fun compositionConversion_percentToLbAndStone_matchesKgConversion() {
        val massKg = 10.9504f
        val massLb = ConverterUtils.convertPercentageOrMassCompositionUnit(
            12.8f, UnitType.PERCENT, UnitType.LB, 85.55f
        )
        val massSt = ConverterUtils.convertPercentageOrMassCompositionUnit(
            12.8f, UnitType.PERCENT, UnitType.ST, 85.55f
        )

        assertThat(massLb).isNotNull()
        assertThat(massLb!!).isWithin(1e-4f)
            .of(ConverterUtils.convertFloatValueUnit(massKg, UnitType.KG, UnitType.LB))
        assertThat(massSt).isNotNull()
        assertThat(massSt!!).isWithin(1e-4f)
            .of(ConverterUtils.convertFloatValueUnit(massKg, UnitType.KG, UnitType.ST))
    }

    @Test
    fun compositionConversion_rejectsMissingWeightAndUnsupportedUnits() {
        assertThat(
            ConverterUtils.convertPercentageOrMassCompositionUnit(
                12.8f, UnitType.PERCENT, UnitType.KG, null
            )
        ).isNull()
        assertThat(
            ConverterUtils.convertPercentageOrMassCompositionUnit(
                12.8f, UnitType.PERCENT, UnitType.CM, 85.55f
            )
        ).isNull()
    }

    @Test
    fun compositionConversion_samePercentUnitDoesNotRequireBodyWeight() {
        assertThat(
            ConverterUtils.convertPercentageOrMassCompositionUnit(
                12.8f, UnitType.PERCENT, UnitType.PERCENT, null
            )
        ).isEqualTo(12.8f)
    }

    @Test
    fun percentageOrMassCompositionKeys_includeExtendedMetricsButNotLeanSoftTissue() {
        val expected = listOf(
            MeasurementTypeKey.BODY_FAT,
            MeasurementTypeKey.WATER,
            MeasurementTypeKey.MUSCLE,
            MeasurementTypeKey.ECW,
            MeasurementTypeKey.ICW,
            MeasurementTypeKey.PROTEIN,
            MeasurementTypeKey.SKELETAL_MUSCLE,
            MeasurementTypeKey.SUBCUTANEOUS_FAT,
        )

        expected.forEach {
            assertThat(ConverterUtils.isPercentageOrMassComposition(it)).isTrue()
        }
        assertThat(ConverterUtils.isPercentageOrMassComposition(MeasurementTypeKey.LEAN_SOFT_TISSUE))
            .isFalse()
    }

    // ---- sanitizeDigits -------------------------------------------------------------------------

    @Test
    fun sanitizeDigits_filtersAndTruncates() {
        assertThat(ConverterUtils.sanitizeDigits("12a34", 10)).isEqualTo("1234")
        assertThat(ConverterUtils.sanitizeDigits("abc", 5)).isEqualTo("")
        assertThat(ConverterUtils.sanitizeDigits("123456", 3)).isEqualTo("123")
    }
}
