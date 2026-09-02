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
package com.health.openscale.core.service

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for a crash introduced by 39a0c5c ("BLE handlers can contribute custom
 * measurement types", #1481/#1484): [BleConnector] formatted the "measurement saved" snackbar
 * with `measurementData[MeasurementType.WEIGHT] ?: 0f`, which is a [Kg]? (not a Float?) whenever
 * a measurement actually has a weight — the common case. Formatting a value class directly
 * against the `%1$.1f` format specifier in `bluetooth_connector_measurement_saved` throws
 * `java.util.IllegalFormatConversionException: f != com.health.openscale.core.data.Kg` at
 * `Resources.getString`, crashing the app on every successful weigh-in on every scale.
 *
 * This test exercises the exact call ([Resources.getString] with a format arg list) rather than
 * constructing the full [BleConnector] (which needs a live [ScaleCommunicator] and DB facades),
 * since that's the boundary that actually threw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleConnectorSnackbarTest {

    @Test
    fun `measurement-saved snackbar formats a real weight without throwing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val measurement = ScaleMeasurement().apply {
            this[MeasurementType.WEIGHT] = Kg(72.4f)
        }

        // This is exactly what BleConnector.lastSavedArgs builds and AppNavigation's snackbar
        // collector later passes to Resources.getString(resId, *args) — unwrap to the raw Float
        // the "%1$.1f kg" format specifier expects, not the Kg value class itself.
        val weightArg = measurement[MeasurementType.WEIGHT]?.value ?: 0f
        val formatted = context.getString(R.string.bluetooth_connector_measurement_saved, weightArg, "Test Scale")

        assertThat(formatted).contains("72.4")
    }
}
