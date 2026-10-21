/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Test

class BodyPod2HandlerTest {
    @Test
    fun `builds documented user profile and checksum`() {
        val user = ScaleUser(bodyHeight = 173f)
        val packet = BodyPod2Handler.buildUserProfile(user)

        assertThat(packet[0]).isEqualTo(0xFE.toByte())
        assertThat(packet[1]).isEqualTo(0x01.toByte())
        assertThat(packet[2]).isEqualTo(0x01.toByte())
        assertThat(packet[3]).isEqualTo(0x00.toByte())
        assertThat(packet[4]).isEqualTo(0xAD.toByte())
        assertThat(packet[6]).isEqualTo(0x01.toByte())
        assertThat(packet[7]).isEqualTo(xor(packet, 1))
    }

    @Test
    fun `advertises body composition capability without claiming implementation`() {
        val support = BodyPod2Handler().supportFor(device("Body Pod 2", uuid16(0xFFF0)))!!

        assertThat(support.capabilities).contains(DeviceCapability.BODY_COMPOSITION)
        assertThat(support.implemented).doesNotContain(DeviceCapability.BODY_COMPOSITION)
    }

    @Test
    fun `validates CF and BE packet checksums`() {
        // CF: stable weight 0x2451 (92.97 kg); BE: two-frequency segment impedances.
        val cf = hex("cf 00 80 51 24 00 00 00 00 01 3b")
        val be = hex("be 00 f7 0c 9d 0c 0e 01 03 07 11 09 c7")

        assertThat(BodyPod2Handler.validChecksum(cf)).isTrue()
        assertThat(BodyPod2Handler.uint16le(cf, 3)).isEqualTo(0x2451)
        assertThat(BodyPod2Handler.validChecksum(be)).isTrue()
        assertThat(BodyPod2Handler.validChecksum(be.copyOf().also { it[1] = 2 })).isFalse()
    }

    @Test
    fun `publishes both frequency segment impedances as disabled device values`() {
        val setup = attachedHandler()
        val dataCharacteristic = uuid16(0xFFF4)
        setup.handler.handleConnected(setup.user)
        setup.handler.handleNotification(dataCharacteristic, hex("cf 00 80 51 24 00 00 00 00 01 3b"))
        setup.handler.handleNotification(dataCharacteristic, hex("be 00 f7 0c 9d 0c 0e 01 03 07 11 09 c7"))
        setup.handler.handleNotification(dataCharacteristic, hex("be 01 f7 0c 9d 0c 0e 01 03 07 11 09 c6"))

        val measurement = setup.callbacks.published.single()
        val segmentals = measurement.values.filterKeys { it.identity.startsWith("ble.segmental.impedance.") }
        assertThat(segmentals).hasSize(10)
        assertThat(segmentals.keys.all { !it.defaultEnabled }).isTrue()
        assertThat(value(segmentals, "ble.segmental.impedance.low.right_arm")).isWithin(0.001f).of(331.9f)
        assertThat(value(segmentals, "ble.segmental.impedance.high.left_leg")).isWithin(0.001f).of(232.1f)
    }

    private fun value(values: Map<MeasurementType.Key<*>, Any>, identity: String): Float =
        (values.entries.single { it.key.identity == identity }.value as Ohm).value

    private fun attachedHandler(): Setup {
        val handler = BodyPod2Handler()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NoopTransport(),
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(bodyHeight = 173f)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, callbacks, ScaleUser(bodyHeight = 173f))
    }

    private data class Setup(
        val handler: BodyPod2Handler,
        val callbacks: CapturingCallbacks,
        val user: ScaleUser,
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

    private fun xor(packet: ByteArray, start: Int = 0): Byte {
        var result = 0
        for (i in start until packet.lastIndex) result = result xor (packet[i].toInt() and 0xFF)
        return result.toByte()
    }
}
