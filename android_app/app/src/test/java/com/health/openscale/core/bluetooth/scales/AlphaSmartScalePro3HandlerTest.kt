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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.AlphaSmartScalePro3Lib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Session tests for [AlphaSmartScalePro3Handler]: device matching, the FitDays handshake and what
 * gets published for the captured final-result and stored-record frames.
 */
class AlphaSmartScalePro3HandlerTest {

    private val serviceFfb0: UUID = uuid16(0xFFB0)
    private val charWrite: UUID = uuid16(0xFFB1)
    private val charNotify: UUID = uuid16(0xFFB2)

    // Captured frames (see AlphaSmartScalePro3LibTest).
    private val liveSettling = hex("AC 27 00 69 5B 8A 02 00 30 01 00 00 00 00 00 00 00 24 D5 1A")
    private val finalWithImpedance = hex("AC 27 01 00 01 D5 01 80 69 5B 8A 00 00 00 00 00 00 24 D6 00")
    private val finalWithoutImpedance = hex("AC 27 01 00 00 00 01 80 69 5C 98 00 00 00 00 00 00 24 D6 19")
    private val storedRecord = hex("AC 27 00 6A B0 4E E1 80 68 18 F6 00 00 00 01 00 00 24 D8 1C")

    // --- Matching --------------------------------------------------------------

    @Test
    fun `claims MY_SCALE advertising service FFB0`() {
        val support = AlphaSmartScalePro3Handler().supportFor(device("MY_SCALE", serviceFfb0))

        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("ALPHA Smart Scale PRO 3")
        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
        assertThat(support.implemented).doesNotContain(DeviceCapability.BODY_COMPOSITION)
    }

    @Test
    fun `matches the name case-insensitively but never without the service`() {
        val handler = AlphaSmartScalePro3Handler()

        assertThat(handler.supportFor(device("my_scale", serviceFfb0))).isNotNull()
        assertThat(handler.supportFor(device("MY_SCALE"))).isNull()
    }

    @Test
    fun `leaves the other FFB0 scales to their own handlers`() {
        val handler = AlphaSmartScalePro3Handler()

        listOf("swan", "SSW532", "relaxmedic", "FITTRACK Dara", "BIA SCALE").forEach { name ->
            assertThat(handler.supportFor(device(name, serviceFfb0))).isNull()
        }
    }

    @Test
    fun `is registered ahead of MGBHandler, which would otherwise claim the scale`() {
        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }

        assertThat(order.indexOf("AlphaSmartScalePro3Handler")).isLessThan(order.indexOf("MGBHandler"))
        assertThat(MGBHandler().supportFor(device("MY_SCALE", serviceFfb0))).isNotNull()
    }

    // --- Handshake -------------------------------------------------------------

    @Test
    fun `connect enables notifications and writes profile, session start, profile`() {
        val setup = attachedHandler(femaleUser(heightCm = 165f, ageYears = 31))

        setup.handler.handleConnected(setup.user)

        assertThat(setup.transport.notifications).containsExactly(serviceFfb0 to charNotify)
        assertThat(setup.transport.writes.map { it.characteristic }).containsExactly(charWrite, charWrite, charWrite)

        val profile = setup.transport.writes[0].payload
        assertThat(profile[18].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.WIRE_PROFILE)
        assertThat(profile[9].toInt() and 0xFF).isEqualTo(165)
        assertThat(profile[12].toInt() and 0xFF).isEqualTo(31)
        assertThat(profile[13].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.SEX_FEMALE)
        assertThat(profile[19].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.phoneChecksum(profile))

        assertThat(setup.transport.writes[1].payload).isEqualTo(AlphaSmartScalePro3Lib.buildSessionStart())
        assertThat(setup.transport.writes[2].payload[18].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.WIRE_PROFILE)
    }

    @Test
    fun `profile carries the male sex code for a male user`() {
        val user = femaleUser(heightCm = 180f, ageYears = 40).apply { gender = GenderType.MALE }
        val setup = attachedHandler(user)

        setup.handler.handleConnected(user)

        assertThat(setup.transport.writes[0].payload[13].toInt() and 0xFF).isEqualTo(AlphaSmartScalePro3Lib.SEX_MALE)
    }

    // --- Publishing ------------------------------------------------------------

    @Test
    fun `live frames publish nothing`() {
        val setup = connectedHandler()

        setup.handler.handleNotification(charNotify, liveSettling)

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun `final result publishes weight and impedance and is acknowledged`() {
        val setup = connectedHandler()

        setup.handler.handleNotification(charNotify, finalWithImpedance)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.userId).isEqualTo(setup.user.id)
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(88.97f)
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isWithin(1e-4f).of(469f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT, MeasurementType.IMPEDANCE)
        assertThat(setup.transport.writes.last().payload)
            .isEqualTo(hex("AC 27 04 D6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF B9"))
    }

    @Test
    fun `final result without impedance publishes the weight only`() {
        val setup = connectedHandler()

        setup.handler.handleNotification(charNotify, finalWithoutImpedance)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(89.24f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT)
    }

    @Test
    fun `a repeated final result in the same session is acknowledged but not published twice`() {
        val setup = connectedHandler()

        setup.handler.handleNotification(charNotify, finalWithImpedance)
        setup.handler.handleNotification(charNotify, finalWithImpedance)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.transport.writes.count { it.payload[2].toInt() == AlphaSmartScalePro3Lib.CMD_ACK }).isEqualTo(2)
    }

    @Test
    fun `stored record publishes the weight at its own timestamp and is acknowledged`() {
        val setup = connectedHandler()

        setup.handler.handleNotification(charNotify, storedRecord)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(6.39f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT)
        assertThat(measurement.dateTime?.time).isEqualTo(1_789_939_425L * 1000L)
        assertThat(setup.transport.writes.last().payload)
            .isEqualTo(hex("AC 27 04 D8 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF BB"))
    }

    @Test
    fun `frames from other characteristics and corrupt frames are ignored`() {
        val setup = connectedHandler()
        val corrupt = finalWithImpedance.copyOf().also { it[19] = 0x01 }

        setup.handler.handleNotification(charWrite, finalWithImpedance)
        setup.handler.handleNotification(charNotify, corrupt)

        assertThat(setup.callbacks.published).isEmpty()
    }

    // --- Harness ---------------------------------------------------------------

    private fun connectedHandler(): Setup {
        val setup = attachedHandler(femaleUser(heightCm = 165f, ageYears = 31))
        setup.handler.handleConnected(setup.user)
        setup.transport.writes.clear()
        return setup
    }

    private fun attachedHandler(user: ScaleUser): Setup {
        val handler = AlphaSmartScalePro3Handler()
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, transport, callbacks, user)
    }

    private fun femaleUser(heightCm: Float, ageYears: Int): ScaleUser =
        ScaleUser(
            id = 7,
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -ageYears); add(Calendar.DAY_OF_YEAR, -1) }.time,
            bodyHeight = heightCm,
            gender = GenderType.FEMALE,
        )

    private data class Setup(
        val handler: AlphaSmartScalePro3Handler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
        val user: ScaleUser,
    )

    private data class Write(val service: UUID, val characteristic: UUID, val payload: ByteArray)

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<Write>()
        val notifications = mutableListOf<Pair<UUID, UUID>>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += Write(service, characteristic, payload.copyOf())
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        override fun getInt(key: String, default: Int): Int = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
