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
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndUC352BLEHandlerTest {

    @Test
    fun `recognises the captured advertisement without depending on its serial suffix`() {
        val support = AndUC352BLEHandler().supportFor(device("A&D_UC-352BLE_01166D", 0x181D))

        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("A&D UC-352BLE")
        assertThat(support.capabilities).containsExactly(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
        )
        assertThat(support.implemented).isEqualTo(support.capabilities)
        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
    }

    @Test
    fun `matching is case insensitive and rejects other devices`() {
        val handler = AndUC352BLEHandler()

        assertThat(handler.supportFor(device("a&d_uc-352ble_abcdef", 0x181D))).isNotNull()
        assertThat(handler.supportFor(device("A&D_UC-355BLE_01166D", 0x181D))).isNull()
        assertThat(handler.supportFor(device("UC-352BLE", 0x181D))).isNull()
        assertThat(handler.supportFor(device("A&D_UC-352BLE_01166D"))).isNotNull()
    }

    @Test
    fun `decodes captured standard weight indications`() {
        val captured = listOf(
            // flags, weight/0.005 kg LE, year LE, month, day, hour, minute, second
            "025433ea070816082a24" to ExpectedMeasurement(65.7f, 8, 42, 36),
            "024033ea070816082b0b" to ExpectedMeasurement(65.6f, 8, 43, 11),
            "027c33ea070816082b28" to ExpectedMeasurement(65.9f, 8, 43, 40),
        )

        for ((payload, expected) in captured) {
            val callbacks = CapturingCallbacks()
            val handler = attachedHandler(callbacks)

            handler.handleNotification(uuid16(0x2A9D), payload.hexToBytes())
            handler.handleDisconnected()

            assertThat(callbacks.published).hasSize(1)
            val measurement = callbacks.published.single()
            assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(expected.weight)
            val calendar = Calendar.getInstance().apply { time = requireNotNull(measurement.dateTime) }
            assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
            assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.AUGUST)
            assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(22)
            assertThat(calendar.get(Calendar.HOUR_OF_DAY)).isEqualTo(expected.hour)
            assertThat(calendar.get(Calendar.MINUTE)).isEqualTo(expected.minute)
            assertThat(calendar.get(Calendar.SECOND)).isEqualTo(expected.second)
        }
    }

    private data class ExpectedMeasurement(val weight: Float, val hour: Int, val minute: Int, val second: Int)

    private fun attachedHandler(callbacks: CapturingCallbacks) = AndUC352BLEHandler().apply {
        attach(
            NoopTransport(), callbacks, InMemorySettings(), FixedDataProvider(),
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private class NoopTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val ints = mutableMapOf<String, Int>()
        private val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { ints.remove(key); strings.remove(key) }
    }

    private class FixedDataProvider : ScaleDeviceHandler.DataProvider {
        private val user = ScaleUser(id = 1, userName = "Test User")
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }

    private fun device(name: String, vararg services: Int) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = services.map(::uuid16),
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
