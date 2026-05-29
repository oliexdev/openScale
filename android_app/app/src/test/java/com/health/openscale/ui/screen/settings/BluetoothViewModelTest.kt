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
package com.health.openscale.ui.screen.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.testutil.FakeBluetoothFacade
import com.health.openscale.testutil.MainDispatcherRule
import com.health.openscale.ui.shared.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakeBluetoothFacade
    private lateinit var vm: BluetoothViewModel

    @Before
    fun setUp() {
        fake = FakeBluetoothFacade()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "btvm-${System.nanoTime()}.preferences_pb") },
        )
        vm = BluetoothViewModel(fake, SettingsFacadeImpl(dataStore))
    }

    @After
    fun tearDown() {
        // onCleared() is protected/framework-called; nothing to release here for the fake.
    }

    private fun device(addr: String = "AA:BB:CC:DD:EE:FF") =
        ScannedDeviceInfo(name = "Dev", address = addr, rssi = -50, serviceUuids = emptyList(), manufacturerData = null)

    // ---- scan gate --------------------------------------------------------------------------

    @Test
    fun requestStartDeviceScan_whenBluetoothDisabled_doesNotScan() {
        fake.bluetoothEnabled = false
        vm.requestStartDeviceScan()
        assertThat(fake.startScanDurationMs).isNull()
    }

    @Test
    fun requestStartDeviceScan_whenEnabled_startsScanWithConfiguredDuration() {
        fake.bluetoothEnabled = true
        vm.requestStartDeviceScan()
        assertThat(fake.startScanDurationMs).isEqualTo(20_000L)
    }

    // ---- delegation -------------------------------------------------------------------------

    @Test
    fun delegationMethods_callThroughToFacade() {
        vm.requestStopDeviceScan(); assertThat(fake.stopScanCalled).isTrue()
        vm.connectToSavedDevice(); assertThat(fake.connectCalled).isTrue()
        vm.disconnectDevice(); assertThat(fake.disconnectCalled).isTrue()
        vm.removeSavedDevice(); assertThat(fake.removeSavedDeviceCalled).isTrue()
        vm.clearAllErrors(); assertThat(fake.clearErrorsCalled).isTrue()
        vm.clearPendingUserInteraction(); assertThat(fake.clearPendingCalled).isTrue()
        vm.setSavedTuning(TuningProfile.Aggressive); assertThat(fake.lastSavedTuning).isEqualTo(TuningProfile.Aggressive)

        val dev = device()
        vm.saveDeviceAsPreferred(dev); assertThat(fake.savedAsPreferred).isEqualTo(dev)

        vm.provideUserInteractionFeedback(BluetoothEvent.UserInteractionType.ENTER_CONSENT, 1234)
        assertThat(fake.feedback).isEqualTo(BluetoothEvent.UserInteractionType.ENTER_CONSENT to 1234)
    }

    @Test
    fun isBluetoothEnabled_reflectsFacade() {
        fake.bluetoothEnabled = false
        assertThat(vm.isBluetoothEnabled()).isFalse()
        fake.bluetoothEnabled = true
        assertThat(vm.isBluetoothEnabled()).isTrue()
    }

    // ---- state passthrough ------------------------------------------------------------------

    @Test
    fun stateFlows_passThroughFromFacade() {
        val dev = device()
        fake.isScanning.value = true
        fake.scannedDevices.value = listOf(dev)
        fake.connectionError.value = "boom"

        assertThat(vm.isScanning.value).isTrue()
        assertThat(vm.scannedDevices.value).containsExactly(dev)
        assertThat(vm.connectionError.value).isEqualTo("boom")
    }

    // ---- snackbar mapping -------------------------------------------------------------------

    @Test
    fun connectorSnackbarEvents_areForwarded() = runTest(mainRule.dispatcher.scheduler) {
        val received = mutableListOf<SnackbarEvent>()
        val job = launch { vm.snackbarEvents.collect { received.add(it) } }
        advanceUntilIdle() // let the VM's internal collector + our collector subscribe

        fake.snackbarEventsFromConnector.emit(SnackbarEvent(messageResId = R.string.bluetooth_must_be_enabled_for_scan))
        advanceUntilIdle()

        job.cancel()
        assertThat(received).hasSize(1)
    }

    @Test
    fun requestStartDeviceScan_whenDisabled_emitsSnackbar() = runTest(mainRule.dispatcher.scheduler) {
        fake.bluetoothEnabled = false
        val received = mutableListOf<SnackbarEvent>()
        val job = launch { vm.snackbarEvents.collect { received.add(it) } }
        advanceUntilIdle()

        vm.requestStartDeviceScan()
        advanceUntilIdle()

        job.cancel()
        assertThat(received.map { it.messageResId })
            .contains(R.string.bluetooth_must_be_enabled_for_scan)
    }
}
