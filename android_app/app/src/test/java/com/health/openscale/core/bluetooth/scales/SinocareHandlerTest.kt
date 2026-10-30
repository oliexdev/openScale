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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SinocareHandlerTest {

    @Test
    @Suppress("DEPRECATION")
    fun `accepts CW286 advertisement using alternate manufacturer id`() {
        // Captured CW286 manufacturer payload, wrapped in an AD structure (14 FF 00 00).
        // Payload bytes 0..5: address zeroed, outside the checksum range.
        // Bytes 9..10: 06 1D -> 7430 -> 74.30 kg; XOR of bytes 6..15: D4 (byte 16).
        val advertisement = "14FF0000000000000000CE0000061D0000000001D4"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val result = scanResult(advertisement)
        val handler = SinocareHandler()
        val user = ScaleUser()

        // Nine identical readings are required for a stable measurement.
        repeat(8) {
            assertThat(handler.onAdvertisement(result, user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(handler.onAdvertisement(result, user)).isEqualTo(BroadcastAction.CONSUMED_STOP)
    }

    // --- IFIT ---------------------------------------------------------------------------------
    //
    // The "IFIT" (vendor app "Unique Health", a Lefu OEM broadcast scale) sends the same frame as
    // the Sinocare: company id 0x0000, then the advertiser address, the signature CE 01 02, the
    // weight little-endian in 0.01 kg, and the XOR check byte. The vectors below are verbatim
    // frames from a BTSnoop capture of one weigh-in (display: 85.9 kg), of the same 5.3 kg object
    // in kg and in lb mode, and of the idle period after stepping off.

    /** The scale advertises from this fixed, obviously synthetic address; the frame echoes it. */
    private val ifitAddress = "00:00:00:00:00:83"
    private val ifitHead = "0000 000000000083 CE0102"

    private val settling = listOf(
        ifitBlock("3025 0000 00 00 01 D9"), // 95.20 kg, first frame after stepping on
        ifitBlock("221F 0000 00 00 01 F1"), // 79.70 kg
        ifitBlock("3E21 0000 00 00 01 D3"), // 85.10 kg
    )
    private val locked = ifitBlock("8E21 0000 00 00 01 63")   // 85.90 kg, repeated while locked
    private val final = ifitBlock("8E21 FFFF 00 00 00 62")    // 85.90 kg, end-of-measurement frame
    private val idle = ifitBlock("0000 0000 00 00 01 CC")     // nothing on the platform
    private val objectKg = ifitBlock("1202 0000 00 00 01 DC") // 5.30 kg, display in kg
    private val objectLb = ifitBlock("1202 0000 00 01 01 DD") // 5.30 kg, display in lb (11.6 lb)

    @Test
    fun `claims the IFIT by name as a broadcast-only device with its own catalog row`() {
        val support = SinocareHandler().supportFor(scanned("IFIT"))!!

        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        assertThat(support.displayName).isEqualTo("IFIT")
        assertThat(SinocareHandler().supportFor(scanned("Weight Scale"))!!.displayName).isEqualTo("Sinocare")
        assertThat(SinocareHandler().supportFor(scanned("ifit"))).isNotNull()
    }

    @Test
    fun `leaves other scales alone`() {
        assertThat(SinocareHandler().supportFor(scanned("QN-Scale"))).isNull()
        assertThat(SinocareHandler().supportFor(scanned("IFIT-2"))).isNull()
    }

    @Test
    fun `publishes the locked weight of a captured IFIT weigh-in once, after nine identical frames`() {
        val setup = attached()
        val user = ScaleUser()

        for (frame in settling) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame, ifitAddress), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        repeat(8) {
            assertThat(setup.handler.onAdvertisement(scanResult(locked, ifitAddress), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.handler.onAdvertisement(scanResult(locked, ifitAddress), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().weightKg()).isWithin(0.001f).of(85.90f)
    }

    @Test
    fun `the end-of-measurement frame continues the streak of the locked weight`() {
        val setup = attached()
        val user = ScaleUser()

        repeat(5) { setup.handler.onAdvertisement(scanResult(locked, ifitAddress), user) }
        repeat(3) {
            assertThat(setup.handler.onAdvertisement(scanResult(final, ifitAddress), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.handler.onAdvertisement(scanResult(final, ifitAddress), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published.single().weightKg()).isWithin(0.001f).of(85.90f)
    }

    @Test
    fun `idle zero-weight frames are never published`() {
        val setup = attached()
        val user = ScaleUser()

        repeat(20) {
            assertThat(setup.handler.onAdvertisement(scanResult(idle, ifitAddress), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun `a weigh-in in lb mode still publishes kilograms`() {
        val setup = attached()
        val user = ScaleUser()

        repeat(9) { setup.handler.onAdvertisement(scanResult(objectLb, ifitAddress), user) }

        assertThat(setup.callbacks.published.single().weightKg()).isWithin(0.001f).of(5.30f)
    }

    @Test
    fun `the same object in kg mode decodes to the same weight`() {
        val setup = attached()
        val user = ScaleUser()

        repeat(9) { setup.handler.onAdvertisement(scanResult(objectKg, ifitAddress), user) }

        assertThat(setup.callbacks.published.single().weightKg()).isWithin(0.001f).of(5.30f)
    }

    @Test
    fun `a frame with a corrupted check byte is ignored`() {
        val setup = attached()
        val user = ScaleUser()
        val corrupted = locked.copyOf().also { it[16] = (it[16] + 1).toByte() }

        repeat(9) {
            assertThat(setup.handler.onAdvertisement(scanResult(corrupted, ifitAddress), user))
                .isEqualTo(BroadcastAction.IGNORED)
        }

        assertThat(setup.callbacks.published).isEmpty()
    }

    // --- Helpers --------------------------------------------------------------------------------

    /**
     * A full manufacturer-data block of the IFIT: the fixed head (company id, address, signature)
     * followed by the seven data bytes and the check byte, written the way they were captured.
     */
    private fun ifitBlock(dataAndCheck: String): ByteArray = hex("$ifitHead $dataAndCheck")

    /** Wraps a manufacturer block in a manufacturer-specific AD structure (type 0xFF). */
    private fun advertisement(manufacturerBlock: ByteArray): ByteArray =
        byteArrayOf((manufacturerBlock.size + 1).toByte(), 0xFF.toByte()) + manufacturerBlock

    /** Parses [adStructures] with Android's own parser, which is hidden from the public SDK. */
    @Suppress("DEPRECATION")
    private fun scanResult(adStructures: ByteArray, address: String = "00:11:22:33:44:55"): ScanResult {
        val bytes = if (adStructures.size > 1 && adStructures[1] == 0xFF.toByte()) adStructures else advertisement(adStructures)
        val parseMethod = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        parseMethod.isAccessible = true
        val record = parseMethod.invoke(null, bytes) as ScanRecord
        return ScanResult(ShadowBluetoothDevice.newInstance(address), record, -50, 0L)
    }

    private fun scanned(name: String) = ScannedDeviceInfo(
        name = name,
        address = ifitAddress,
        rssi = -60,
        serviceUuids = emptyList(),
        manufacturerData = null,
    )

    private fun ScaleMeasurement.weightKg(): Float = this[MeasurementType.WEIGHT]!!.value

    private class Setup(val handler: SinocareHandler, val callbacks: CapturingCallbacks)

    private fun attached(): Setup {
        val handler = SinocareHandler()
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
