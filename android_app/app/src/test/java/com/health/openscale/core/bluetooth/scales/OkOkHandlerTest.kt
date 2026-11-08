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
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
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
import org.robolectric.shadows.ShadowBluetoothDevice
import java.util.UUID

/**
 * Tests for the "OKOK C0" broadcast family of [OkOkHandler].
 *
 * The frame layout comes from a BTSnoop capture of an "OMIYA-C39-HW" (vendor app OKOK
 * International): company id with low byte 0xC0 and a changing high byte, then weight (BE, 0.1 kg),
 * impedance (BE, 0.1 ohm), two zero bytes, the attribute byte (bit 0 = stable) and the advertiser's
 * own MAC. The scale settles the weight, then reports the impedance, then sets the stable bit.
 * The values below are synthetic (70.0 kg, 450.0 ohm); only the layout is from the capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkOkHandlerTest {

    private val address = "0C:11:22:33:44:55"
    private val mac = "0C1122334455"

    private val idle = c0Frame("0000 0000 0000 20 $mac")
    private val settling = c0Frame("02BC 0000 0000 20 $mac")
    private val impedance = c0Frame("02BC 1194 0000 20 $mac")
    private val stable = c0Frame("02BC 1194 0000 21 $mac")

    @Test
    fun `claims a C0 frame that carries the advertiser's own MAC under any name`() {
        val support = OkOkHandler().supportFor(scanned("OMIYA-C39-HW", 0x4AC0 to stable))!!

        assertThat(support.displayName).isEqualTo("OKOK C0")
        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        // The high byte of the company id changes from frame to frame.
        assertThat(OkOkHandler().supportFor(scanned("OMIYA-C39-HW", 0xF7C0 to idle))).isNotNull()
    }

    @Test
    fun `ignores an unknown name whose C0 frame carries a different MAC`() {
        val foreign = c0Frame("02BC 1194 0000 21 AABBCCDDEEFF")

        assertThat(OkOkHandler().supportFor(scanned("OMIYA-C39-HW", 0x4AC0 to foreign))).isNull()
    }

    @Test
    fun `ignores an unknown name with a short C0 record`() {
        assertThat(OkOkHandler().supportFor(scanned("OMIYA-C39-HW", 0x4AC0 to ByteArray(6)))).isNull()
    }

    @Test
    fun `still claims whitelisted names without the MAC`() {
        assertThat(OkOkHandler().supportFor(scanned("ADV", 0x4AC0 to ByteArray(13)))).isNotNull()
    }

    @Test
    fun `publishes the weight only once the stable bit is set`() {
        val setup = attached()
        val user = ScaleUser()

        for (frame in listOf(idle, settling, impedance)) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.IGNORED)
        }
        assertThat(setup.callbacks.published).isEmpty()

        assertThat(setup.handler.onAdvertisement(scanResult(stable), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)
        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single()[MeasurementType.WEIGHT]!!.value)
            .isWithin(0.001f).of(70.0f)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun c0Frame(value: String): ByteArray = hex(value)

    private fun scanned(name: String, record: Pair<Int, ByteArray>) = ScannedDeviceInfo(
        name = name,
        address = address,
        rssi = -60,
        serviceUuids = emptyList(),
        manufacturerData = SparseArray<ByteArray>().apply { put(record.first, record.second) },
    )

    /** Wraps [frame] under company id 0x4AC0 and parses it with Android's hidden parser. */
    @Suppress("DEPRECATION")
    private fun scanResult(frame: ByteArray): ScanResult {
        val block = byteArrayOf(0xC0.toByte(), 0x4A) + frame
        val bytes = byteArrayOf((block.size + 1).toByte(), 0xFF.toByte()) + block
        val parse = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        parse.isAccessible = true
        val record = parse.invoke(null, bytes) as ScanRecord
        return ScanResult(ShadowBluetoothDevice.newInstance(address), record, -50, 0L)
    }

    private class Setup(val handler: OkOkHandler, val callbacks: CapturingCallbacks)

    private fun attached(): Setup {
        val handler = OkOkHandler()
        val callbacks = CapturingCallbacks()
        handler.attach(NoTransport(), callbacks, InMemorySettings(), FixedDataProvider(ScaleUser()), CoroutineScope(Dispatchers.Unconfined))
        return Setup(handler, callbacks)
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }

        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class NoTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = false
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun remove(key: String) {
            strings.remove(key)
            ints.remove(key)
        }
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
