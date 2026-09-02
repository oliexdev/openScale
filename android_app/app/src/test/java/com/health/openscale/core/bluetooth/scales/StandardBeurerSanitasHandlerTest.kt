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
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The BF1000 reports segmental fat and muscle on Beurer-private characteristics, separate
 * from the standard weight/body-composition packets. These tests drive both halves through
 * the handler and assert that the private values reach the published measurement.
 *
 * Packet payloads are the vectors already asserted in BeurerBf1000LibTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandardBeurerSanitasHandlerTest {

    private val chrWeightMeasurement = uuid16(0x2A9D)
    private val chrSegmentalFat = uuid16(0x0009)
    private val chrSegmentalMuscle = uuid16(0x000A)

    @Test
    fun `attaches BF1000 segmental values to the published measurement`() {
        val setup = attachedHandler("Beurer BF1000")

        setup.handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        setup.handler.handleNotification(chrSegmentalMuscle, segmentalMusclePacket())
        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()

        val published = setup.callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(72.5f)

        val segmentals = published.values.keys
            .filter { it.identity.startsWith("ble.segmental.") }
            .associate { it.identity to (published.values[it] as Percent).value }

        val expected = mapOf(
            "ble.segmental.fat.left_arm" to 12.4f,
            "ble.segmental.fat.right_arm" to 12.9f,
            "ble.segmental.fat.torso" to 21.3f,
            "ble.segmental.fat.left_leg" to 18.7f,
            "ble.segmental.fat.right_leg" to 18.9f,
            "ble.segmental.muscle.left_arm" to 33.1f,
            "ble.segmental.muscle.right_arm" to 33.6f,
            "ble.segmental.muscle.torso" to 45.2f,
            "ble.segmental.muscle.left_leg" to 41.8f,
            "ble.segmental.muscle.right_leg" to 42.0f,
        )
        assertThat(segmentals.keys).containsExactlyElementsIn(expected.keys)
        expected.forEach { (identity, percent) ->
            assertThat(segmentals.getValue(identity)).isWithin(1e-3f).of(percent)
        }
    }

    @Test
    fun `visceral fat rides the predefined key, not a device-specific one`() {
        val setup = attachedHandler("Beurer BF1000")

        setup.handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()

        val published = setup.callbacks.published.single()
        assertThat(published[MeasurementType.VISCERAL_FAT]!!).isWithin(1e-3f).of(0.8f)
        assertThat(published.values.keys.map { it.identity })
            .doesNotContain("ble.segmental.visceral_fat")
    }

    @Test
    fun `the buffer is cleared, so the next weigh-in does not inherit stale values`() {
        val setup = attachedHandler("Beurer BF1000")

        setup.handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()
        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()

        assertThat(setup.callbacks.published).hasSize(2)
        assertThat(setup.callbacks.published[0].values.keys.any { it.identity.startsWith("ble.") }).isTrue()
        assertThat(setup.callbacks.published[1].values.keys.any { it.identity.startsWith("ble.") }).isFalse()
    }

    @Test
    fun `other Beurer models are untouched by the BF1000 path`() {
        val setup = attachedHandler("Beurer BF105")

        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()

        val published = setup.callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(72.5f)
        assertThat(published.values.keys.none { it.identity.startsWith("ble.") }).isTrue()
    }

    @Test
    fun `each segment seeds with a distinguishable icon and colour`() {
        val setup = attachedHandler("Beurer BF1000")
        setup.handler.handleNotification(chrSegmentalFat, segmentalFatPacket())
        setup.handler.handleNotification(chrSegmentalMuscle, segmentalMusclePacket())
        setup.handler.handleNotification(chrWeightMeasurement, weightPacket())
        setup.handler.handleDisconnected()

        val keys = setup.callbacks.published.single().values.keys
            .filter { it.identity.startsWith("ble.segmental.") }

        val fat = keys.filter { it.identity.startsWith("ble.segmental.fat.") }
        val muscle = keys.filter { it.identity.startsWith("ble.segmental.muscle.") }
        // Colour separates the groups and the segments; the whole-body anchors stay reserved.
        assertThat(fat.map { it.defaultColor }).containsNoDuplicates()
        assertThat(muscle.map { it.defaultColor }).containsNoDuplicates()
        assertThat(fat.map { it.defaultColor }.intersect(muscle.map { it.defaultColor }.toSet())).isEmpty()
        assertThat(keys.map { it.defaultColor })
            .containsNoneOf(MeasurementType.BODY_FAT.defaultColor, MeasurementType.MUSCLE.defaultColor)
        // The icon carries the body part; unit is what both packets report.
        assertThat(keys.none { it.defaultIcon == MeasurementTypeIcon.IC_DEFAULT }).isTrue()
        assertThat(keys.all { it.wireUnit == UnitType.PERCENT }).isTrue()
        assertThat(keys.none { it.defaultPinned }).isTrue()
    }

    // --- helpers ---------------------------------------------------------------

    /** Standard Weight Scale 2A9D: flags 0x00 (kg, nothing optional) + 72.5 kg / 0.005. */
    private fun weightPacket(): ByteArray {
        val raw = (72.5f / 0.005f).toInt()
        return byteArrayOf(0x00, (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
    }

    /** 0x7E marker, visceral in tenths, then five fat percentages in tenths, LE. */
    private fun segmentalFatPacket(): ByteArray =
        byteArrayOf(0x7E, 8) + tenths(12.4f, 12.9f, 21.3f, 18.7f, 18.9f)

    /** 0x3E marker, then five muscle percentages in tenths, LE. */
    private fun segmentalMusclePacket(): ByteArray =
        byteArrayOf(0x3E) + tenths(33.1f, 33.6f, 45.2f, 41.8f, 42.0f)

    private fun tenths(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val raw = Math.round(v * 10f)
            out[i * 2] = (raw and 0xFF).toByte()
            out[i * 2 + 1] = ((raw shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun attachedHandler(deviceName: String): Setup {
        val handler = StandardBeurerSanitasHandler()
        assertThat(handler.supportFor(device(deviceName))).isNotNull()

        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NoopTransport(),
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user()),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, callbacks)
    }

    private fun user(): ScaleUser {
        val birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -30) }.time
        return ScaleUser(id = 7, birthday = birthday, bodyHeight = 180f, gender = GenderType.MALE)
    }

    private fun device(name: String) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = emptyList(),
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private class Setup(
        val handler: StandardBeurerSanitasHandler,
        val callbacks: CapturingCallbacks,
    )

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class NoopTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = true
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
