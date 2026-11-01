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
package com.health.openscale.core.bluetooth.scales

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.S400HydrationShift
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.testutil.Fixtures
import com.health.openscale.testutil.RoomTestSupport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviousMeasurementMappingTest {
    private lateinit var db: AppDatabase
    private lateinit var settingsJob: Job
    private lateinit var adapter: TestAdapter

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        settingsJob = SupervisorJob()
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(settingsJob + Dispatchers.IO),
            File(app.cacheDir, "previous-mapping-" + System.nanoTime() + ".preferences_pb"),
        )
        val facades = RoomTestSupport.facadesFor(app, RoomTestSupport.repositoryFor(db), settings)
        adapter = TestAdapter(app, settings, facades)
    }

    @After
    fun tearDown() = runBlocking {
        adapter.closeAndJoin()
        settingsJob.cancelAndJoin()
        db.close()
    }

    private class TestAdapter(
        app: Application,
        settings: SettingsFacade,
        facades: RoomTestSupport.Facades,
    ) : ModernScaleAdapter(
        app, settings, facades.measurementFacade, facades.userFacade,
        object : ScaleDeviceHandler() {
            override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? = null
        },
    ) {
        override val isConnecting get() = _isConnecting
        override val isConnected get() = _isConnected
        override fun doConnect(address: String, selectedUser: ScaleUser) = Unit
        override fun doDisconnect() = Unit
        @Composable override fun DeviceConfigurationUi() = Unit

        fun mapSaved(value: MeasurementWithValues?): ScaleMeasurement? = mapMeasurement(value)

        suspend fun closeAndJoin() {
            val job = scope.coroutineContext[Job]
            close()
            job?.join()
        }
    }

    private fun previous(high: Float? = 460f, low: Float? = 510f): MeasurementWithValues {
        val keys = listOf(MeasurementType.WEIGHT, MeasurementType.IMPEDANCE, MeasurementType.IMPEDANCE_LOW)
        val raw = listOf(75f, high, low)
        val types = MeasurementType.seedRows().associateBy { it.identity }
        val values = keys.mapIndexedNotNull { index, key ->
            raw[index]?.let { value ->
                val type = types.getValue(key.identity).copy(id = index + 1)
                Fixtures.valueWithType(type, value, measurementId = 10)
            }
        }
        return Fixtures.mwv(measurementId = 10, timestamp = 1000L, userId = 7, values = values)
    }

    @Test
    fun `previous snapshot retains both impedance bands`() {
        val snapshot = adapter.mapSaved(previous())!!
        assertThat(snapshot[MeasurementType.IMPEDANCE_LOW]?.value).isEqualTo(510f)
        assertThat(snapshot[MeasurementType.IMPEDANCE]?.value).isEqualTo(460f)
        assertThat(snapshot[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(snapshot.userId).isEqualTo(7)
        assertThat(snapshot.dateTime?.time).isEqualTo(1000L)
    }

    @Test
    fun `S400 can calculate decreases zero and increases from the previous snapshot`() {
        val snapshot = adapter.mapSaved(previous())!!
        for (currentLow in listOf(500f, 510f, 520f)) {
            val shift = S400HydrationShift.shiftPct(
                currentLow = currentLow, currentHigh = 460f,
                previousLow = snapshot[MeasurementType.IMPEDANCE_LOW]?.value,
                previousHigh = snapshot[MeasurementType.IMPEDANCE]?.value,
            )
            val expected = ((currentLow / 460f) / (510f / 460f) - 1f) * 100f
            assertThat(shift).isWithin(0.0001f).of(expected)
        }
    }

    @Test
    fun `an absent band stays absent and prevents an invented shift`() {
        val snapshot = adapter.mapSaved(previous(high = null))!!
        assertThat(snapshot[MeasurementType.IMPEDANCE]).isNull()
        assertThat(S400HydrationShift.shiftPct(
            currentLow = 500f, currentHigh = 460f,
            previousLow = snapshot[MeasurementType.IMPEDANCE_LOW]?.value,
            previousHigh = snapshot[MeasurementType.IMPEDANCE]?.value,
        )).isNull()
    }

    @Test
    fun `no previous measurement stays absent`() {
        assertThat(adapter.mapSaved(null)).isNull()
    }
}
