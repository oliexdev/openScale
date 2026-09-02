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
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.testutil.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MeasurementFilterUseCasesTest {

    private val useCase = MeasurementFilterUseCases()
    private val weight = Fixtures.type(id = 1, identity = MeasurementType.WEIGHT.identity)
    private val fat = Fixtures.type(id = 2, identity = MeasurementType.BODY_FAT.identity)

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

    /** Days 1..5 of April 2025, at noon each. */
    private val fiveDays = (1..5).map { emWith(it, weight) }

    private suspend fun timeFilteredIds(start: Long?, end: Long?): List<Int> =
        useCase.getTimeFiltered(flowOf(fiveDays), start, end)
            .first()
            .map { it.measurementWithValues.measurement.id }

    @Test
    fun getTimeFiltered_bothBoundsNull_returnsAll() = runTest {
        assertThat(timeFilteredIds(null, null)).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun getTimeFiltered_openEnd_keepsEverythingFromTheStartOnwards() = runTest {
        assertThat(timeFilteredIds(Fixtures.ts(2025, 4, 3, hour = 0), null))
            .containsExactly(3, 4, 5).inOrder()
    }

    @Test
    fun getTimeFiltered_openStart_keepsEverythingUpToTheEnd() = runTest {
        assertThat(timeFilteredIds(null, Fixtures.ts(2025, 4, 3, hour = 23)))
            .containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun getTimeFiltered_bothBounds_keepsTheEnclosedRange() = runTest {
        assertThat(
            timeFilteredIds(
                Fixtures.ts(2025, 4, 2, hour = 0),
                Fixtures.ts(2025, 4, 4, hour = 23),
            )
        ).containsExactly(2, 3, 4).inOrder()
    }

    @Test
    fun getTimeFiltered_boundsAreInclusive() = runTest {
        val exactly = Fixtures.ts(2025, 4, 3)
        assertThat(timeFilteredIds(exactly, exactly)).containsExactly(3)
    }
}
