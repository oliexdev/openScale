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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.service

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.ModernScaleAdapter
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleConnectorSaveTest {
    private lateinit var db: AppDatabase
    private lateinit var job: kotlinx.coroutines.Job
    private lateinit var settings: SettingsFacade
    private lateinit var facades: RoomTestSupport.Facades
    private lateinit var connector: BleConnector
    private lateinit var adapter: TestAdapter
    private var beforeEvent: suspend (BluetoothEvent) -> Unit = {}
    private val device = ScannedDeviceInfo("Synthetic", "00:00:00:00:00:00", -50, emptyList(), null)

    @Before
    fun setup() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        val repository = RoomTestSupport.repositoryFor(db)
        job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        settings = RoomTestSupport.settingsFacadeFor(scope, File(app.cacheDir, "ble-save-${System.nanoTime()}.preferences_pb"))
        facades = RoomTestSupport.facadesFor(app, repository, settings)
        for (id in 1..2) {
            repository.insertUser(User(id = id, name = "Synthetic $id", birthDate = 946684800000,
                gender = GenderType.MALE, heightCm = 175f, activityLevel = ActivityLevel.SEDENTARY,
                useAssistedWeighing = false))
        }
        MeasurementType.seedRows().forEach { repository.insertMeasurementType(it) }
        connector = BleConnector(scope, ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade),
            facades.measurementFacade, getCurrentUserId = { 2 })
        adapter = TestAdapter(app, settings, facades)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            adapter.getEventsFlow().collect { event ->
                beforeEvent(event)
                connector.handleBluetoothEvent(event, device)
            }
        }
    }

    @After
    fun cleanup() = runBlocking {
        connector.close()
        adapter.closeAndJoin()
        job.cancelAndJoin()
        db.close()
    }

    private fun measurement(weight: Boolean = true) = ScaleMeasurement(userId = 1, dateTime = Date(1_700_000_000_000L)).apply {
        if (weight) this[MeasurementType.WEIGHT] = Kg(80f)
    }

    private suspend fun save(value: ScaleMeasurement = measurement(), appUserId: Int = 1): Boolean {
        val completed = CompletableDeferred<Boolean>()
        adapter.publishOwned(value, appUserId) { completed.complete(it) }
        return withTimeout(10_000) { completed.await() }
    }

    @Test
    fun `explicit app owner is saved before success is reported despite a different selection`() = runBlocking {
        assertThat(save()).isTrue()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).hasSize(1)
        assertThat(facades.measurementFacade.getMeasurementsForUser(2).first()).isEmpty()
    }

    @Test
    fun `legacy user slot does not override the selected app user`() = runBlocking {
        adapter.publishLegacy(measurement().apply { userId = 7 })
        withTimeout(10_000) { facades.measurementFacade.getMeasurementsForUser(2).first { it.isNotEmpty() } }
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
        assertThat(facades.measurementFacade.getMeasurementsForUser(2).first()).hasSize(1)
    }

    @Test
    fun `an explicit profile owner is not reassigned by weight matching`() = runBlocking {
        assertThat(save(appUserId = 2)).isTrue()
        settings.setSmartAssignmentEnabled(true)
        assertThat(save()).isTrue()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).hasSize(1)
        assertThat(facades.measurementFacade.getMeasurementsForUser(2).first()).hasSize(1)
    }

    @Test
    fun `a reading ignored by smart assignment is not reported as saved`() = runBlocking {
        assertThat(save(appUserId = 2)).isTrue()
        settings.setSmartAssignmentEnabled(true)
        settings.setSmartAssignmentTolerancePercent(1)
        settings.setSmartAssignmentIgnoreOutsideTolerance(true)
        val weightType = facades.measurementFacade.getAllMeasurementTypes().first()
            .single { it.identity == MeasurementType.WEIGHT.identity }
        val saved = facades.measurementFacade.saveMeasurementFromBleDevice(
            Measurement(userId = 1),
            listOf(MeasurementValue(measurementId = 0, typeId = weightType.id, floatValue = 140f)),
        )
        assertThat(saved).isFalse()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
    }

    @Test
    fun `missing owner fails without falling back to the selected user`() = runBlocking {
        assertThat(save(appUserId = 999)).isFalse()
        assertThat(facades.measurementFacade.getMeasurementsForUser(2).first()).isEmpty()
    }

    @Test
    fun `empty measurements fail without creating a record`() = runBlocking {
        assertThat(save(measurement(weight = false))).isFalse()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
    }

    @Test
    fun `preparation errors report failure without creating a record`() = runBlocking {
        val invalid = measurement().apply { values[MeasurementType.HEART_RATE] = "invalid" }
        assertThat(save(invalid)).isFalse()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
    }

    @Test
    fun `confirmed publication waits for a full event queue`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        beforeEvent = { if (it is BluetoothEvent.DeviceMessage) { entered.complete(Unit); resume.await() } }
        assertThat(adapter.message()).isTrue()
        withTimeout(10_000) { entered.await() }
        var buffered = 0
        while (adapter.message() && buffered < 20) buffered++
        assertThat(buffered).isAtLeast(8)
        val completed = CompletableDeferred<Boolean>()
        adapter.publishOwned(measurement(), 1) { completed.complete(it) }
        assertThat(completed.isCompleted).isFalse()
        resume.complete(Unit)
        assertThat(withTimeout(10_000) { completed.await() }).isTrue()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).hasSize(1)
    }

    @Test
    fun `exceptions from save callbacks do not escape the publisher`() = runBlocking {
        val received = CompletableDeferred<BluetoothEvent.MeasurementReceived>()
        val resume = CompletableDeferred<Unit>()
        beforeEvent = { if (it is BluetoothEvent.MeasurementReceived) { received.complete(it); resume.await() } }
        var calls = 0
        adapter.publishOwned(measurement(), 1) { calls++; error("Synthetic callback failure") }
        val event = withTimeout(10_000) { received.await() }
        try {
            assertThat(runCatching { event.onSaved!!(true) }.isSuccess).isTrue()
            event.onSaved!!(false)
            assertThat(calls).isEqualTo(1)
        } finally {
            resume.complete(Unit)
        }
    }

    @Test
    fun `cancelling a queued publication reports failure without saving it`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        beforeEvent = { if (it is BluetoothEvent.DeviceMessage) { entered.complete(Unit); resume.await() } }
        assertThat(adapter.message()).isTrue()
        withTimeout(10_000) { entered.await() }
        var buffered = 0
        while (adapter.message() && buffered < 20) buffered++
        assertThat(buffered).isAtLeast(8)
        val completed = CompletableDeferred<Boolean>()
        adapter.publishOwned(measurement(), 1) { completed.complete(it) }
        assertThat(completed.isCompleted).isFalse()
        try {
            adapter.closeAndJoin()
            assertThat(withTimeout(10_000) { completed.await() }).isFalse()
            assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
        } finally {
            resume.complete(Unit)
        }
    }

    @Test
    fun `a closed adapter cannot publish another confirmed reading`() = runBlocking {
        adapter.closeAndJoin()
        val drained = CompletableDeferred<Unit>()
        var readings = 0
        beforeEvent = { event ->
            when (event) {
                is BluetoothEvent.MeasurementReceived -> readings++
                is BluetoothEvent.DeviceMessage -> drained.complete(Unit)
                else -> Unit
            }
        }
        assertThat(save()).isFalse()
        assertThat(adapter.message()).isTrue()
        withTimeout(10_000) { drained.await() }
        assertThat(readings).isEqualTo(0)
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
    }

    @Test
    fun `a cancelled handoff is rejected before persistence starts`() = runBlocking {
        val received = CompletableDeferred<BluetoothEvent.MeasurementReceived>()
        val resume = CompletableDeferred<Unit>()
        beforeEvent = { if (it is BluetoothEvent.MeasurementReceived) { received.complete(it); resume.await() } }
        val completed = CompletableDeferred<Boolean>()
        adapter.publishOwned(measurement(), 1) { completed.complete(it) }
        val event = withTimeout(10_000) { received.await() }
        try {
            event.onSaved!!(false)
            assertThat(completed.await()).isFalse()
            val existingJobs = job.children.toSet()
            connector.handleBluetoothEvent(event, device)
            job.children.filter { it !in existingJobs }.forEach { it.join() }
            assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).isEmpty()
        } finally {
            resume.complete(Unit)
        }
    }

    @Test
    fun `repeated history completes saving without duplicating the record`() = runBlocking {
        assertThat(save()).isTrue()
        assertThat(save()).isTrue()
        assertThat(facades.measurementFacade.getMeasurementsForUser(1).first()).hasSize(1)
    }

    @Test
    fun `profile readings ignore an unrelated assisted weighing reference`() = runBlocking {
        assertThat(save(appUserId = 2)).isTrue()
        val reference = facades.userFacade.observeAllUsers().first().single { it.id == 2 }
        facades.measurementFacade.setPendingReferenceUserForBle(reference)
        assertThat(save(measurement().apply { this[MeasurementType.WEIGHT] = Kg(85f) })).isTrue()
        val stored = facades.measurementFacade.getMeasurementsForUser(1).first().single()
        val weight = stored.values.single { it.type.identity == MeasurementType.WEIGHT.identity }.value.floatValue
        assertThat(weight).isEqualTo(85f)
    }

    @Test
    fun `legacy assisted weighing still subtracts the reference weight`() = runBlocking {
        assertThat(save(appUserId = 1)).isTrue()
        val reference = facades.userFacade.observeAllUsers().first().single { it.id == 1 }
        facades.measurementFacade.setPendingReferenceUserForBle(reference)
        adapter.publishLegacy(measurement().apply { this[MeasurementType.WEIGHT] = Kg(85f) })
        val stored = withTimeout(10_000) {
            facades.measurementFacade.getMeasurementsForUser(2).first { readings ->
                readings.singleOrNull()?.values?.any { it.type.identity == MeasurementType.WEIGHT.identity } == true
            }.single()
        }
        val weight = stored.values.single { it.type.identity == MeasurementType.WEIGHT.identity }.value.floatValue
        assertThat(weight).isEqualTo(5f)
    }

    private class TestAdapter(app: Application, settings: SettingsFacade, facades: RoomTestSupport.Facades) : ModernScaleAdapter(
        app, settings, facades.measurementFacade, facades.userFacade,
        object : ScaleDeviceHandler() { override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? = null },
    ) {
        override fun doConnect(address: String, selectedUser: ScaleUser) = Unit
        override fun doDisconnect() = Unit
        @Composable override fun DeviceConfigurationUi() = Unit
        fun publishOwned(value: ScaleMeasurement, userId: Int, onSaved: (Boolean) -> Unit) =
            appCallbacks.onPublish(value, userId, onSaved)
        fun publishLegacy(value: ScaleMeasurement) = appCallbacks.onPublish(value)
        fun message() = _events.tryEmit(BluetoothEvent.DeviceMessage("Synthetic", "unknown"))
        suspend fun closeAndJoin() {
            val job = scope.coroutineContext[kotlinx.coroutines.Job]
            close()
            job?.join()
        }
    }
}
