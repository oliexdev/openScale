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
import com.health.openscale.core.data.GenderType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

class RyFitHandlerTest {

    @Test
    fun `profile age is clamped when connected`() {
        val setup = attachedHandler(appUser = userBornYearsAgo(30))
        val connectedUser = userBornYearsAgo(9)

        setup.handler.handleConnected(connectedUser)

        assertThat(setup.handler.profileAge()).isEqualTo(10)
    }

    @Test
    fun `c0 command uses current app user age with protocol clamp`() {
        val setup = attachedHandler(appUser = userBornYearsAgo(101))

        setup.handler.handleConnected(userBornYearsAgo(30))
        setup.transport.clearWrites()
        setup.handler.handleNotification(RyFitHandler.CHAR_UUID, byteArrayOf(0xFB.toByte(), 0xF8.toByte()))

        val c0 = setup.transport.writes.single { it.payload.first() == 0xC0.toByte() }.payload
        assertThat(c0[3].toInt() and 0xFF).isEqualTo(100)
    }

    private fun attachedHandler(appUser: ScaleUser): Setup {
        val handler = RyFitHandler()
        val transport = CapturingTransport()
        handler.attach(
            transport = transport,
            callbacks = SilentCallbacks(),
            settings = InMemorySettings(),
            data = FixedDataProvider(appUser),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, transport)
    }

    private fun RyFitHandler.profileAge(): Int {
        val field = RyFitHandler::class.java.getDeclaredField("age")
        field.isAccessible = true
        return field.getInt(this)
    }

    private fun userBornYearsAgo(years: Int): ScaleUser =
        ScaleUser(
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -years) }.time,
            bodyHeight = 175f,
            gender = GenderType.MALE,
        )

    private data class Setup(
        val handler: RyFitHandler,
        val transport: CapturingTransport,
    )

    private data class Write(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean,
    )

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<Write>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += Write(service, characteristic, payload.copyOf(), withResponse)
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        fun clearWrites() = writes.clear()
    }

    private class SilentCallbacks : ScaleDeviceHandler.Callbacks {
        override fun onPublish(measurement: ScaleMeasurement) = Unit
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
