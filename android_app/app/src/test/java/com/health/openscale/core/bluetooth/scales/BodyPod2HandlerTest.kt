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
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Test

class BodyPod2HandlerTest {
    // Frames rebuilt from the values of a real barefoot weigh-in posted in PR #1537.
    private val stableWeight = hex("cf 00 80 8e 21 00 00 00 00 01 e1")
    private val lowSegments = hex("be 00 02 0c 29 0c 0c 01 75 09 02 0a ec")
    private val highSegments = hex("be 01 74 0a c3 0a f5 00 8f 08 f7 08 85")
    private val dataCharacteristic = uuid16(0xFFF4)

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
        assertThat(BodyPod2Handler.validChecksum(stableWeight)).isTrue()
        assertThat(BodyPod2Handler.uint16le(stableWeight, 3)).isEqualTo(8590)
        assertThat(BodyPod2Handler.validChecksum(lowSegments)).isTrue()
        assertThat(BodyPod2Handler.validChecksum(lowSegments.copyOf().also { it[1] = 2 })).isFalse()
    }

    @Test
    fun `publishes the whole-body impedance of both frequencies`() {
        val setup = attachedHandler()
        setup.handler.handleConnected(setup.user)
        setup.handler.handleNotification(dataCharacteristic, stableWeight)
        setup.handler.handleNotification(dataCharacteristic, lowSegments)
        setup.handler.handleNotification(dataCharacteristic, highSegments)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(85.9f)
        assertThat(measurement[MeasurementType.IMPEDANCE_LOW]?.value).isWithin(0.01f).of(585.3f)
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isWithin(0.01f).of(520.35f)
    }

    @Test
    fun `publishes the weight without impedance when it is implausible`() {
        val setup = attachedHandler()
        setup.handler.handleConnected(setup.user)
        setup.handler.handleNotification(dataCharacteristic, stableWeight)
        setup.handler.handleNotification(dataCharacteristic, hex("be 00 00 00 00 00 00 00 00 00 00 00 be"))
        setup.handler.handleNotification(dataCharacteristic, hex("be 01 00 00 00 00 00 00 00 00 00 00 bf"))

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(85.9f)
        assertThat(measurement[MeasurementType.IMPEDANCE]).isNull()
        assertThat(measurement[MeasurementType.IMPEDANCE_LOW]).isNull()
    }

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
