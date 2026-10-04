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
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleCatalog.device

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

    @Test
    fun `BF500 preserves stored timestamps and composition for each record`() {
        val setup = attachedHandler("BF500")
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), composition())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(9, 1))
        setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), composition())
        setup.handler.handleDisconnected()
        val records = setup.callbacks.published
        assertThat(records).hasSize(2)
        assertThat(records.map { Calendar.getInstance().apply { time = it.dateTime!! }.get(Calendar.HOUR_OF_DAY) })
            .containsExactly(8, 9).inOrder()
        records.forEach {
            assertThat(it.userId).isEqualTo(7)
            assertThat(it[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(70f)
            assertThat(it[MeasurementType.BODY_FAT]!!.value).isWithin(0.001f).of(16.9f)
            assertThat(it[MeasurementType.WATER]!!.value).isWithin(0.001f).of(59.85714f)
        }
    }

    @Test
    fun `BF500 consecutive weight-only history records do not merge`() {
        val setup = attachedHandler("BF500")
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(9, 1))
        setup.handler.handleDisconnected()
        assertThat(setup.callbacks.published).hasSize(2)
    }

    @Test
    fun `BF500 skips unmapped user and rejects truncated composition`() {
        val setup = attachedHandler("BF500")
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 2))
        setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), composition())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(9, 1))
        setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), composition().copyOf(5))
        setup.handler.handleDisconnected()
        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `BF500 zero composition does not fabricate lean or bone mass`() {
        val setup = attachedHandler("BF500")
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"),
            byteArrayOf(0x98.toByte(), 0x03) + ByteArray(12))
        val m = setup.callbacks.published.single()
        assertThat(m[MeasurementType.LBM]).isNull()
        assertThat(m[MeasurementType.BONE]).isNull()
        assertThat(m[MeasurementType.WATER]).isNull()
    }

    @Test
    fun `BF500 advertises history only for that model`() {
        val bf500 = StandardBeurerSanitasHandler().supportFor(device("BF500"))!!
        val bf105 = StandardBeurerSanitasHandler().supportFor(device("BF105"))!!
        assertThat(bf500.implemented).contains(DeviceCapability.HISTORY_READ)
        assertThat(bf105.implemented).doesNotContain(DeviceCapability.HISTORY_READ)
    }

    @Test
    fun `BF500 rejects every truncated stored packet without crashing`() {
        for (length in 0 until storedWeight(8, 1).size) {
            val setup = attachedHandler("BF500")
            setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1).copyOf(length))
            setup.handler.handleDisconnected()
            assertThat(setup.callbacks.published).isEmpty()
        }
        for (length in 0 until composition().size) {
            val setup = attachedHandler("BF500")
            setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
            setup.handler.handleNotification(UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb"), composition().copyOf(length))
            setup.handler.handleDisconnected()
            assertThat(setup.callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
        }
    }

    @Test
    fun `standard handler retains weight then composition assembly`() {
        val setup = attachHandler(StandardWeightProfileHandler())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        assertThat(setup.callbacks.published).isEmpty()
        setup.handler.handleNotification(uuid16(0x2A9C), composition())
        setup.handler.handleDisconnected()
        val m = setup.callbacks.published.single()
        assertThat(m.userId).isEqualTo(7)
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(70f)
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isWithin(0.001f).of(16.9f)
        assertThat(m[MeasurementType.WATER]!!.value).isWithin(0.001f).of(59.85714f)
        assertThat(m[MeasurementType.LBM]!!.value).isWithin(0.001f).of(58.17f)
        assertThat(m[MeasurementType.BONE]!!.value).isWithin(0.001f).of(3.17f)
    }

    @Test
    fun `standard handler retains composition then weight assembly`() {
        val setup = attachHandler(StandardWeightProfileHandler())
        setup.handler.handleNotification(uuid16(0x2A9C), composition())
        assertThat(setup.callbacks.published).isEmpty()
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        val m = setup.callbacks.published.single()
        assertThat(m.userId).isEqualTo(7)
        assertThat(m.dateTime).isNotNull()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(70f)
        assertThat(m[MeasurementType.WATER]!!.value).isWithin(0.001f).of(59.85714f)
    }

    @Test
    fun `standard handler keeps its existing consecutive-packet merge policy`() {
        val setup = attachHandler(StandardWeightProfileHandler())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(9, 1))
        setup.handler.handleDisconnected()
        val m = setup.callbacks.published.single()
        assertThat(Calendar.getInstance().apply { time = m.dateTime!! }.get(Calendar.HOUR_OF_DAY)).isEqualTo(8)
    }

    @Test
    fun `standard handler retains unspecified user fallback and disconnect flush`() {
        val setup = attachHandler(StandardWeightProfileHandler())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 2))
        setup.handler.handleDisconnected()
        setup.handler.handleDisconnected()
        assertThat(setup.callbacks.published.single().userId).isEqualTo(0xFF)
    }

    @Test
    fun `shared decoder converts imperial weight and water mass for both assemblers`() {
        val setups = listOf(attachHandler(StandardWeightProfileHandler()), attachedHandler("BF500"))
        setups.forEach { setup ->
            // 150 lb weight, 20 percent fat, 50 lb water mass.
            setup.handler.handleNotification(chrWeightMeasurement, byteArrayOf(1, 0x98.toByte(), 0x3a))
            setup.handler.handleNotification(uuid16(0x2A9C), byteArrayOf(1, 1, 0xc8.toByte(), 0, 0x88.toByte(), 0x13))
            val m = setup.callbacks.published.single()
            assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(68.03886f)
            assertThat(m[MeasurementType.WATER]!!.value).isWithin(0.001f).of(33.33333f)
        }
    }

    @Test
    fun `shared decoder rejects invalid dates for both assemblers`() {
        listOf(attachHandler(StandardWeightProfileHandler()), attachedHandler("BF500")).forEach { setup ->
            val packet = storedWeight(8, 1).apply { this[5] = 13 }
            setup.handler.handleNotification(chrWeightMeasurement, packet)
            setup.handler.handleDisconnected()
            assertThat(setup.callbacks.published).isEmpty()
        }
    }

    @Test
    fun `BF500 rejects orphan fragments and conflicting composition timestamps`() {
        val setup = attachedHandler("BF500")
        setup.handler.handleNotification(uuid16(0x2A9C), composition())
        setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
        setup.handler.handleNotification(uuid16(0x2A9C), composition().apply { this[1] = 0x13 })
        val timestamp = storedWeight(9, 1).copyOfRange(3, 10)
        val body = composition().copyOfRange(0, 4).apply { this[0] = 0x9a.toByte() } +
            timestamp + composition().copyOfRange(4, 14)
        setup.handler.handleNotification(uuid16(0x2A9C), body)
        assertThat(setup.callbacks.published).isEmpty()
        setup.handler.handleDisconnected()
        assertThat(setup.callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `other standard-profile drivers retain mapped weight-only publication`() {
        val drivers = listOf(
            AndUC352BLEHandler() to "A&D_UC-352BLE_TEST",
            SanitasSbf72Handler() to "Sanitas SBF72",
            BeurerBF450Handler() to "Beurer BF450",
            StandardBeurerSanitasHandler() to "Beurer BF105",
        )
        drivers.forEach { (handler, name) ->
            assertThat(handler.supportFor(device(name))).isNotNull()
            val setup = attachHandler(handler)
            setup.handler.handleNotification(chrWeightMeasurement, storedWeight(8, 1))
            setup.handler.handleDisconnected()
            val m = setup.callbacks.published.single()
            assertThat(m.userId).isEqualTo(7)
            assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(70f)
            assertThat(m.dateTime).isNotNull()
        }
    }

    // Sanitized packets matching the layouts in our vendor-app capture; no consent code.
    private fun storedWeight(hour: Int, slot: Int) = byteArrayOf(
        0x0e, 0xb0.toByte(), 0x36, 0xea.toByte(), 0x07, 0x0a, 0x04,
        hour.toByte(), 0, 0, slot.toByte(), 0xe9.toByte(), 0, 0xc2.toByte(), 6,
    )
    private fun composition() = byteArrayOf(
        0x98.toByte(), 3, 0xa9.toByte(), 0, 0xb9.toByte(), 0x1a, 0x99.toByte(), 1,
        0xf8.toByte(), 0x2a, 0xbc.toByte(), 0x20, 0xfc.toByte(), 0x2b,
    )

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

        return attachHandler(handler)
    }

    private fun attachHandler(handler: StandardWeightProfileHandler): Setup {
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NoopTransport(),
            callbacks = callbacks,
            settings = InMemorySettings().apply { putInt("userMap/userIdByIndex/1", 7) },
            data = FixedDataProvider(user()),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, callbacks)
    }

    private fun user(): ScaleUser {
        val birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -30) }.time
        return ScaleUser(id = 7, birthday = birthday, bodyHeight = 180f, gender = GenderType.MALE)
    }


    private class Setup(
        val handler: StandardWeightProfileHandler,
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
