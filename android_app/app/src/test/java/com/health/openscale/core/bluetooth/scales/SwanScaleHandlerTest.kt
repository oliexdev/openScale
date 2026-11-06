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

import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.manufacturerData
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import com.health.openscale.core.bluetooth.ScaleCatalog.hex

/**
 * Unit tests for [SwanScaleHandler]. Claiming requires the advertised name plus the
 * 0x02AC manufacturer signature; the captured frames use 0.1 kg units, so the handler
 * must publish 10x what the generic [MGBHandler] streaming path would compute.
 * Frame vectors come from a real device session, checksums hand-verified as in
 * `MGBHandlerStreamingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwanScaleHandlerTest {

    private val SVC_FFB0 = uuid16(0xFFB0)
    private val CHR_CTRL = uuid16(0xFFB2)

    // Company id observed on the device's real advertisement.
    private val SWAN_MFR_ID = 0x02AC

    // Real capture vectors (checksums verified on-device).
    private val FINAL_WEIGHT_FRAME = hex("AC 02 03 2A 00 00 CA F7")   // raw 810 -> 81.0 kg
    private val IMPEDANCE_FRAME = hex("AC 02 FD 01 02 05 CB D0")      // 517 ohms

    private fun deviceWith(
        vararg services: UUID,
        name: String = "SWAN",
        manufacturerData: SparseArray<ByteArray>? = null,
    ): ScannedDeviceInfo =
        ScannedDeviceInfo(
            name = name,
            address = "A4:C1:38:AF:15:25",
            rssi = -50,
            serviceUuids = services.toList(),
            manufacturerData = manufacturerData,
        )

    @Test
    fun `claims swan advertisements carrying the 0x02AC signature, whatever services they list`() {
        val handler = SwanScaleHandler()
        val signature = manufacturerData(SWAN_MFR_ID to hex("12 a2 91 ec b3 03"))

        assertThat(handler.supportFor(deviceWith(manufacturerData = signature))).isNotNull()
        assertThat(handler.supportFor(deviceWith(SVC_FFB0, manufacturerData = signature))).isNotNull()
        assertThat(handler.supportFor(deviceWith(SVC_FFB0, manufacturerData = signature))!!.displayName)
            .isEqualTo("SWAN")
    }

    @Test
    fun `leaves swan-named scales without the signature to the generic MGB path`() {
        val handler = SwanScaleHandler()

        // No manufacturer data at all, or another company's: neither belongs to this OEM.
        assertThat(handler.supportFor(deviceWith(SVC_FFB0))).isNull()
        assertThat(handler.supportFor(deviceWith(SVC_FFB0, manufacturerData = manufacturerData(0x06D5 to hex("01")))))
            .isNull()
    }

    @Test
    fun `does not claim other vendors carrying the same signature`() {
        val signature = manufacturerData(SWAN_MFR_ID to hex("12 a2 91 ec b3 03"))
        assertThat(SwanScaleHandler().supportFor(deviceWith(SVC_FFB0, name = "NotASwan", manufacturerData = signature)))
            .isNull()
    }

    @Test
    fun `publishes 81_0 kg and 517 ohm from the captured frames`() {
        val (handler, published) = attachToSwan()

        handler.handleNotification(CHR_CTRL, hex("AC 02 03 29 00 00 CE FA")) // live, ignored
        assertThat(p.published).isEmpty()

        handler.handleNotification(CHR_CTRL, FINAL_WEIGHT_FRAME)
        assertThat(p.published).isEmpty() // waiting for impedance

        handler.handleNotification(CHR_CTRL, IMPEDANCE_FRAME)

        val m = p.published.single()
        assertThat(m[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(81.0f)
        assertThat(m[MeasurementType.IMPEDANCE]?.value).isWithin(0.01f).of(517f)
    }

    @Test
    fun `generic MGBHandler keeps publishing deci-gram-scaled weights unchanged`() {
        val (handler, _) = attachToSwan(handler = MGBHandler())

        handler.handleNotification(CHR_CTRL, hex("AC 02 26 B6 00 00 CA A6")) // Dr Trust vector
        handler.handleNotification(CHR_CTRL, IMPEDANCE_FRAME)

        val m = p.published.single()
        assertThat(m[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(99.10f)
    }

    private fun attachToSwan(handler: MGBHandler = SwanScaleHandler()): Pair<MGBHandler, Publisher> {
        val user = ScaleUser(
            id = 1,
            birthday = Date(0),
            bodyHeight = 181f,
            gender = GenderType.MALE,
        )
        p = Publisher()
        handler.attach(
            transport = RecordingTransport(),
            callbacks = object : ScaleDeviceHandler.Callbacks {
                override fun onPublish(measurement: ScaleMeasurement) {
                    p.published += measurement
                }

                override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
            },
            settings = object : ScaleDeviceHandler.DriverSettings {
                override fun getInt(key: String, default: Int): Int = default
                override fun putInt(key: String, value: Int) = Unit
                override fun getString(key: String, default: String?): String? = default
                override fun putString(key: String, value: String) = Unit
                override fun remove(key: String) = Unit
            },
            data = object : ScaleDeviceHandler.DataProvider {
                override fun currentUser(): ScaleUser = user
                override fun usersForDevice(): List<ScaleUser> = listOf(user)
                override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
            },
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        handler.handleConnected(user)
        return handler to p
    }

    private lateinit var p: Publisher

    private class Publisher {
        val published = mutableListOf<ScaleMeasurement>()
    }

    private class RecordingTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = true
    }
}
