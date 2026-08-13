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
package com.health.openscale.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.Trend
import com.health.openscale.testutil.Fixtures
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests [MeasurementEnricher.enrichWithDifferences] — the per-type diff/trend logic shown next to
 * each value. The method is pure; Robolectric is only used to build the (unused-by-this-method)
 * SettingsFacade dependency. Measurements are passed newest-first (the production ordering).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeasurementEnricherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun enricher(): MeasurementEnricher {
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(context.cacheDir, "enricher-${System.nanoTime()}.preferences_pb"),
        )
        return MeasurementEnricher(settings, TrendCalculator())
    }

    @Test
    fun enrichWithDifferences_computesDeltaAndTrend_vsPreviousMeasurement() {
        val weight = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)
        val newest = Fixtures.mwv(2, Fixtures.ts(2025, 1, 2), listOf(Fixtures.valueWithType(weight, 72f, 2)))
        val older = Fixtures.mwv(1, Fixtures.ts(2025, 1, 1), listOf(Fixtures.valueWithType(weight, 70f, 1)))

        // production passes measurements newest-first
        val result = enricher().enrichWithDifferences(listOf(newest, older), listOf(weight))

        assertThat(result).hasSize(2)
        // newest (index 0) diffs against the older one
        assertThat(result[0].difference).isWithin(1e-3f).of(2f)
        assertThat(result[0].trend).isEqualTo(Trend.UP)
        // oldest has no predecessor
        assertThat(result[1].difference).isNull()
        assertThat(result[1].trend).isEqualTo(Trend.NOT_APPLICABLE)
    }

    @Test
    fun enrichWithDifferences_skipsDisabledTypes() {
        val disabled = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT, enabled = false)
        val mwv = Fixtures.mwv(1, Fixtures.ts(2025, 1, 1), listOf(Fixtures.valueWithType(disabled, 70f, 1)))

        val result = enricher().enrichWithDifferences(listOf(mwv), listOf(disabled))

        assertThat(result).isEmpty()
    }

    @Test
    fun enrichWithDifferences_sortsByDisplayOrder() {
        val late = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT, displayOrder = 5)
        val early = Fixtures.type(id = 2, key = MeasurementTypeKey.BODY_FAT, displayOrder = 1)
        val mwv = Fixtures.mwv(
            1, Fixtures.ts(2025, 1, 1),
            listOf(Fixtures.valueWithType(late, 70f, 1), Fixtures.valueWithType(early, 20f, 1)),
        )

        val result = enricher().enrichWithDifferences(listOf(mwv), listOf(late, early))

        assertThat(result.map { it.currentValue.type.id }).containsExactly(2, 1).inOrder()
    }
}
