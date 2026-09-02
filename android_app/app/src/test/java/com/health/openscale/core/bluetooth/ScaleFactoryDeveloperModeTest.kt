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
package com.health.openscale.core.bluetooth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.scales.DebugGattHandler
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Developer mode used to be encoded by renaming the saved device to `"Debug"`, which let the
 * diagnostic handler win by name match and destroyed the scale's identity in the process
 * (issue #1478). It is a setting of its own now, and these tests pin the routing that replaces it:
 * the registry decides while the mode is off, the diagnostic handler wins while it is on — for
 * known and unknown scales alike — and the device name is never consulted.
 *
 * A broadcast-only scale makes the override observable without reaching into the adapter: its
 * normal route is a [com.health.openscale.core.bluetooth.scales.BroadcastScaleAdapter], while the
 * GATT-based diagnostic handler yields a
 * [com.health.openscale.core.bluetooth.scales.GattScaleAdapter].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleFactoryDeveloperModeTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Broadcast-only scale (see `EufyC20Handler`), used to tell the two routes apart. */
    private val BROADCAST_SCALE = "EUFY C20"

    /** Name-matched GATT scale — the one from issue #1478. */
    private val GATT_SCALE = "SANITAS SBF70"

    /** A factory wired to an isolated DataStore, so the developer-mode flag can be flipped per test. */
    private fun factoryWithSettings(): Pair<ScaleFactory, SettingsFacade> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = RoomTestSupport.settingsFacadeFor(
            scope,
            File(app.cacheDir, "developer-mode-${System.nanoTime()}.preferences_pb"),
        )
        val repo = RoomTestSupport.repositoryFor(RoomTestSupport.inMemory(app))
        val facades = RoomTestSupport.facadesFor(app, repo, settings)

        return ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade) to settings
    }

    private fun ScaleFactory.adapterFor(name: String): String? =
        createCommunicator(ScaleCatalog.device(name))?.javaClass?.simpleName

    @Test
    fun `developer mode off keeps the registry in charge`() {
        val (factory, _) = factoryWithSettings()

        assertThat(factory.adapterFor(GATT_SCALE)).isEqualTo("GattScaleAdapter")
        assertThat(factory.adapterFor(BROADCAST_SCALE)).isEqualTo("BroadcastScaleAdapter")
    }

    @Test
    fun `developer mode routes every scale to the diagnostic handler`() = runTest {
        val (factory, settings) = factoryWithSettings()
        settings.setDeveloperModeEnabled(true)

        // The broadcast scale switching adapters is the proof that the override, not the registry,
        // picked the handler.
        assertThat(factory.adapterFor(BROADCAST_SCALE)).isEqualTo("GattScaleAdapter")
        assertThat(factory.adapterFor(GATT_SCALE)).isEqualTo("GattScaleAdapter")
    }

    @Test
    fun `developer mode leaves the reported driver of the saved scale untouched`() = runTest {
        val (factory, settings) = factoryWithSettings()
        val before = factory.getDeviceSupportFor(ScaleCatalog.device(GATT_SCALE))

        settings.setDeveloperModeEnabled(true)

        assertThat(before?.displayName).isEqualTo("Sanitas SBF70 / SilverCrest SBF75 / Crane")
        assertThat(factory.getDeviceSupportFor(ScaleCatalog.device(GATT_SCALE))?.displayName)
            .isEqualTo(before?.displayName)
    }

    @Test
    fun `developer mode also reaches a scale no handler supports`() = runTest {
        val (factory, settings) = factoryWithSettings()
        val unknown = "NoSuchScale-XYZ"
        assertThat(factory.adapterFor(unknown)).isNull()

        settings.setDeveloperModeEnabled(true)

        assertThat(factory.adapterFor(unknown)).isEqualTo("GattScaleAdapter")
    }

    @Test
    fun `the diagnostic handler never claims a device by name`() {
        val debug = DebugGattHandler()

        assertThat(debug.supportFor(ScaleCatalog.device("Debug"))).isNull()
        assertThat(debug.supportFor(ScaleCatalog.device("debug"))).isNull()
        // …and it is no longer part of the ordered registry either.
        assertThat(ScaleFactory.createHandlers().map { it.javaClass.simpleName })
            .doesNotContain(DebugGattHandler::class.java.simpleName)
    }
}
