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
package com.health.openscale.core.usecase

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.testutil.Fixtures
import org.junit.Test

class MeasurementFilterUseCasesTest {

    private val useCase = MeasurementFilterUseCases()
    private val weight = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)
    private val fat = Fixtures.type(id = 2, key = MeasurementTypeKey.BODY_FAT)

    private fun emWith(measurementId: Int, vararg types: MeasurementType): EnrichedMeasurement =
        Fixtures.enriched(
            Fixtures.mwv(
                measurementId = measurementId,
                timestamp = Fixtures.ts(2025, 4, measurementId),
                values = types.map { Fixtures.valueWithType(it, 50f, measurementId) },
            )
        )

    @Test
    fun filterByTypes_emptySet_returnsAll() {
        val input = listOf(emWith(1, weight), emWith(2, fat))
        assertThat(useCase.filterByTypes(input, emptySet())).isEqualTo(input)
    }

    @Test
    fun filterByTypes_noMatch_returnsEmpty() {
        val input = listOf(emWith(1, weight))
        assertThat(useCase.filterByTypes(input, setOf(999))).isEmpty()
    }

    @Test
    fun filterByTypes_keepsOnlyMeasurementsContainingSelectedType() {
        val input = listOf(emWith(1, weight), emWith(2, fat))
        val out = useCase.filterByTypes(input, setOf(fat.id))
        assertThat(out).hasSize(1)
        assertThat(out[0].measurementWithValues.measurement.id).isEqualTo(2)
    }
}
