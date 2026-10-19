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
package com.health.openscale.core.facade

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.WeightUnit
import org.junit.Test

/** Unit tests for [scaleUnitFor]: which weight unit scale handlers get in ScaleUser.scaleUnit. */
class ScaleUnitForTest {

    private fun weight(unit: UnitType) =
        MeasurementType(identity = MeasurementType.WEIGHT.identity, unit = unit)

    @Test
    fun `follows the unit of the Weight measurement type`() {
        assertThat(scaleUnitFor(listOf(weight(UnitType.KG)))).isEqualTo(WeightUnit.KG)
        assertThat(scaleUnitFor(listOf(weight(UnitType.LB)))).isEqualTo(WeightUnit.LB)
        assertThat(scaleUnitFor(listOf(weight(UnitType.ST)))).isEqualTo(WeightUnit.ST)
    }

    @Test
    fun `ignores other mass types`() {
        // LBM and BONE are masses too, but only Weight carries the user's display choice.
        val types = listOf(
            MeasurementType(identity = MeasurementType.LBM.identity, unit = UnitType.LB),
            MeasurementType(identity = MeasurementType.BONE.identity, unit = UnitType.ST),
            weight(UnitType.KG),
        )
        assertThat(scaleUnitFor(types)).isEqualTo(WeightUnit.KG)
    }

    @Test
    fun `falls back to kg without a usable Weight type`() {
        assertThat(scaleUnitFor(emptyList())).isEqualTo(WeightUnit.KG)
        assertThat(scaleUnitFor(listOf(weight(UnitType.NONE)))).isEqualTo(WeightUnit.KG)
        assertThat(
            scaleUnitFor(listOf(MeasurementType(identity = MeasurementType.BODY_FAT.identity, unit = UnitType.PERCENT)))
        ).isEqualTo(WeightUnit.KG)
    }
}
