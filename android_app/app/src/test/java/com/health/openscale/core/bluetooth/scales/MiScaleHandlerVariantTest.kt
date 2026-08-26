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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Regression tests for variant detection on connect.
 *
 * The weight-only Mi Smart Scale 2 (XMTZC04HM) advertises "MI SCALE2" — the same
 * naming family as the Mi Body Composition Scale 2 (XMTZC05HM, "MIBFS"/"MIBCS") —
 * and even exposes the 0x1530 vendor service, so scan-time heuristics classify it
 * as V2. Its GATT table, however, only serves the Weight Scale service (0x181D);
 * there is no Body Composition service (0x181B). Prior to the GATT-based
 * re-detection, the v2 init sequence targeted 0x181B, every write/subscribe
 * failed, and the app hung in "waiting for measurement" forever.
 */
class MiScaleHandlerVariantTest {

    private val serviceBodyComp = uuid16(0x181B)
    private val serviceWeight = uuid16(0x181D)
    private val charMiHistory = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")
    private val serviceMiCfg = UUID.fromString("00001530-0000-3512-2118-0009af100700")

    @Test
    fun `MI SCALE2 name maps to v2 support at scan time`() {
        val handler = MiScaleHandler()
        val support = handler.supportFor(device("MI SCALE2"))!!
        assertThat(support.displayName).isEqualTo("Xiaomi Mi Scale v2")
    }

    @Test
    fun `weight-only XMTZC04HM is downgraded to v1 on connect and inits against 0x181D`() {
        // GATT shape of the real device: history char under 0x181D only, vendor cfg present.
        val setup = attachedHandler(
            gatt = setOf(
                serviceWeight to charMiHistory,
            )
        )
        // Scan-time classification says V2 (name match).
        setup.handler.supportFor(device("MI SCALE2", serviceMiCfg))
        setup.handler.handleConnected(syntheticUser())

        // Init sequence must target the Weight Scale service, not Body Composition.
        val touchedServices =
            (setup.transport.writes.map { it.service } + setup.transport.notifications.map { it.first })
                .toSet()
        assertThat(touchedServices).contains(serviceWeight)
        assertThat(touchedServices).doesNotContain(serviceBodyComp)
        assertThat(setup.transport.disconnectCount).isEqualTo(0)
        assertThat(setup.callbacks.errors).isEmpty()
    }

    @Test
    fun `genuine v2 with 0x181B stays v2`() {
        val setup = attachedHandler(
            gatt = setOf(
                serviceBodyComp to charMiHistory,
            )
        )
        setup.handler.supportFor(device("MIBFS", serviceMiCfg))
        setup.handler.handleConnected(syntheticUser())

        val touchedServices =
            (setup.transport.writes.map { it.service } + setup.transport.notifications.map { it.first })
                .toSet()
        assertThat(touchedServices).contains(serviceBodyComp)
        assertThat(setup.transport.disconnectCount).isEqualTo(0)
    }

    @Test
    fun `device without history characteristic under either service aborts with error`() {
        val setup = attachedHandler(gatt = emptySet())
        setup.handler.supportFor(device("MI SCALE2"))
        setup.handler.handleConnected(syntheticUser())

        assertThat(setup.transport.disconnectCount).isEqualTo(1)
        assertThat(setup.callbacks.errors).isNotEmpty()
        // Nothing should have been written to either candidate service.
        assertThat(setup.transport.writes).isEmpty()
    }

    // ----- Harness (same pattern as KeepS3HandlerTest) -----

    private class Setup(
        val handler: MiScaleHandler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
    )

    private fun attachedHandler(gatt: Set<Pair<UUID, UUID>>): Setup {
        val handler = MiScaleHandler()
        val transport = CapturingTransport(gatt)
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(syntheticUser()),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, transport, callbacks)
    }

    private fun syntheticUser(): ScaleUser {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val birthday = Calendar.getInstance().apply {
            clear()
            set(currentYear - 30, Calendar.JANUARY, 1)
        }.time
        return ScaleUser(id = 1, birthday = birthday, bodyHeight = 180f)
    }

    private fun device(name: String, vararg services: UUID) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = services.toList(),
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private data class Write(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean,
    )

    private class CapturingTransport(
        private val gatt: Set<Pair<UUID, UUID>>,
    ) : ScaleDeviceHandler.Transport {
        val notifications = mutableListOf<Pair<UUID, UUID>>()
        val writes = mutableListOf<Write>()
        var disconnectCount = 0

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }

        override fun write(
            service: UUID,
            characteristic: UUID,
            payload: ByteArray,
            withResponse: Boolean,
        ) {
            writes += Write(service, characteristic, payload.copyOf(), withResponse)
        }

        override fun read(service: UUID, characteristic: UUID) = Unit

        override fun disconnect() {
            disconnectCount++
        }

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
            (service to characteristic) in gatt
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val errors = mutableListOf<Int>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.copy()
        }

        override fun onError(resId: Int, t: Throwable?, vararg args: Any) {
            errors += resId
        }

        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key); ints.remove(key) }
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
