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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.Trend
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.testutil.Fixtures
import org.junit.Test
import java.util.Locale

class MeasurementComparisonUseCaseTest {

    private val useCase = MeasurementComparisonUseCase()

    private val weightType = MeasurementType(
        id = 1,
        identity = "builtin.weight",
        unit = UnitType.KG,
        inputType = InputFieldType.FLOAT,
        displayOrder = 1,
        isEnabled = true,
    )

    private val fatType = MeasurementType(
        id = 2,
        identity = "builtin.body_fat",
        unit = UnitType.PERCENT,
        inputType = InputFieldType.FLOAT,
        displayOrder = 2,
        isEnabled = true,
    )

    private val muscleType = MeasurementType(
        id = 3,
        identity = "builtin.muscle",
        unit = UnitType.PERCENT,
        inputType = InputFieldType.FLOAT,
        displayOrder = 3,
        isEnabled = true,
    )

    private val waterType = MeasurementType(
        id = 4,
        identity = "builtin.water",
        unit = UnitType.PERCENT,
        inputType = InputFieldType.FLOAT,
        displayOrder = 4,
        isEnabled = true,
    )

    private val noteType = MeasurementType(
        id = 5,
        identity = "builtin.comment",
        unit = UnitType.NONE,
        inputType = InputFieldType.TEXT,
        displayOrder = 5,
        isEnabled = true,
    )

    private val allTypes = listOf(weightType, fatType, muscleType, waterType, noteType)

    @Test
    fun `compare calculates delta, percentage, and trends correctly`() {
        val t1 = Fixtures.ts(2026, 1, 1, 8)
        val t2 = Fixtures.ts(2026, 1, 15, 8) // 14 days later

        val m1 = Fixtures.mwv(
            measurementId = 101,
            timestamp = t1,
            values = listOf(
                Fixtures.valueWithType(weightType, 80.0f, 101),
                Fixtures.valueWithType(fatType, 22.0f, 101),
                Fixtures.valueWithType(muscleType, 38.0f, 101),
            )
        )

        val m2 = Fixtures.mwv(
            measurementId = 102,
            timestamp = t2,
            values = listOf(
                Fixtures.valueWithType(weightType, 78.0f, 102), // -2 kg (-2.5%)
                Fixtures.valueWithType(fatType, 20.0f, 102),   // -2 % (-9.09%)
                Fixtures.valueWithType(muscleType, 39.0f, 102), // +1 % (+2.63%)
            )
        )

        val comparison = useCase.compare(
            base = m1,
            target = m2,
            types = allTypes,
            locale = Locale.US,
        )

        assertThat(comparison.daysBetween).isEqualTo(14L)
        assertThat(comparison.items).hasSize(3)

        // Weight item checks
        val weightItem = comparison.weightItem
        assertThat(weightItem).isNotNull()
        assertThat(weightItem!!.baseValue).isEqualTo(80.0f)
        assertThat(weightItem.targetValue).isEqualTo(78.0f)
        assertThat(weightItem.difference).isEqualTo(-2.0f)
        assertThat(weightItem.percentChange).isWithin(0.01f).of(-2.5f)
        assertThat(weightItem.ratePerWeek).isWithin(0.01f).of(-1.0f)
        assertThat(weightItem.trend).isEqualTo(Trend.DOWN)
        assertThat(weightItem.isPresentInBoth).isTrue()

        // Fat item checks
        val fatItem = comparison.items.first { it.type.id == fatType.id }
        assertThat(fatItem.difference).isEqualTo(-2.0f)
        assertThat(fatItem.trend).isEqualTo(Trend.DOWN)

        // Muscle item checks
        val muscleItem = comparison.items.first { it.type.id == muscleType.id }
        assertThat(muscleItem.difference).isEqualTo(1.0f)
        assertThat(muscleItem.trend).isEqualTo(Trend.UP)
        assertThat(muscleItem.ratePerWeek).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun `compare handles same-day measurements without division by zero`() {
        val t1 = Fixtures.ts(2026, 3, 10, 8)
        val t2 = Fixtures.ts(2026, 3, 10, 20) // same day, evening

        val m1 = Fixtures.mwv(1, t1, listOf(Fixtures.valueWithType(weightType, 75.0f, 1)))
        val m2 = Fixtures.mwv(2, t2, listOf(Fixtures.valueWithType(weightType, 76.0f, 2)))

        val comparison = useCase.compare(m1, m2, allTypes, Locale.US)

        assertThat(comparison.daysBetween).isEqualTo(0L)
        val weight = comparison.weightItem!!
        assertThat(weight.difference).isEqualTo(1.0f)
        assertThat(weight.ratePerWeek).isNull()
        assertThat(weight.ratePerWeekFormatted).isNull()
        assertThat(weight.trend).isEqualTo(Trend.UP)
    }

    @Test
    fun `compare handles asymmetric metrics present in only one measurement`() {
        val t1 = Fixtures.ts(2026, 1, 1)
        val t2 = Fixtures.ts(2026, 1, 5)

        val m1 = Fixtures.mwv(
            measurementId = 1,
            timestamp = t1,
            values = listOf(
                Fixtures.valueWithType(weightType, 70.0f, 1),
                Fixtures.valueWithType(fatType, 18.0f, 1), // Only in m1
            )
        )

        val m2 = Fixtures.mwv(
            measurementId = 2,
            timestamp = t2,
            values = listOf(
                Fixtures.valueWithType(weightType, 70.0f, 2),
                Fixtures.valueWithType(waterType, 55.0f, 2), // Only in m2
            )
        )

        val comparison = useCase.compare(m1, m2, allTypes, Locale.US)
        assertThat(comparison.items).hasSize(3)

        val fat = comparison.items.first { it.type.id == fatType.id }
        assertThat(fat.baseValue).isEqualTo(18.0f)
        assertThat(fat.targetValue).isNull()
        assertThat(fat.targetFormatted).isEqualTo("-")
        assertThat(fat.difference).isNull()
        assertThat(fat.trend).isEqualTo(Trend.NOT_APPLICABLE)
        assertThat(fat.isPresentInBoth).isFalse()

        val water = comparison.items.first { it.type.id == waterType.id }
        assertThat(water.baseValue).isNull()
        assertThat(water.baseFormatted).isEqualTo("-")
        assertThat(water.targetValue).isEqualTo(55.0f)
        assertThat(water.difference).isNull()
        assertThat(water.trend).isEqualTo(Trend.NOT_APPLICABLE)
        assertThat(water.isPresentInBoth).isFalse()
    }

    @Test
    fun `compare handles text input types gracefully`() {
        val t1 = Fixtures.ts(2026, 1, 1)
        val t2 = Fixtures.ts(2026, 1, 2)

        val m1 = Fixtures.mwv(
            measurementId = 1,
            timestamp = t1,
            values = listOf(
                MeasurementValueWithType(
                    value = MeasurementValue(measurementId = 1, typeId = noteType.id, textValue = "Fasting"),
                    type = noteType,
                )
            )
        )

        val m2 = Fixtures.mwv(
            measurementId = 2,
            timestamp = t2,
            values = listOf(
                MeasurementValueWithType(
                    value = MeasurementValue(measurementId = 2, typeId = noteType.id, textValue = "Post-workout"),
                    type = noteType,
                )
            )
        )

        val comparison = useCase.compare(m1, m2, allTypes, Locale.US)
        assertThat(comparison.items).hasSize(1)

        val note = comparison.items.first()
        assertThat(note.baseFormatted).isEqualTo("Fasting")
        assertThat(note.targetFormatted).isEqualTo("Post-workout")
        assertThat(note.difference).isNull()
        assertThat(note.percentChange).isNull()
        assertThat(note.ratePerWeek).isNull()
    }

    @Test
    fun `compareChronological orders earlier measurement as base regardless of argument order`() {
        val tEarly = Fixtures.ts(2026, 1, 1)
        val tLate = Fixtures.ts(2026, 2, 1)

        val mEarly = Fixtures.mwv(1, tEarly, listOf(Fixtures.valueWithType(weightType, 80.0f, 1)))
        val mLate = Fixtures.mwv(2, tLate, listOf(Fixtures.valueWithType(weightType, 75.0f, 2)))

        // Pass late first, early second
        val comparison = useCase.compareChronological(mLate, mEarly, allTypes, Locale.US)

        assertThat(comparison.baseMeasurement.measurement.id).isEqualTo(1)
        assertThat(comparison.targetMeasurement.measurement.id).isEqualTo(2)
        assertThat(comparison.weightItem!!.difference).isEqualTo(-5.0f)
    }
}
