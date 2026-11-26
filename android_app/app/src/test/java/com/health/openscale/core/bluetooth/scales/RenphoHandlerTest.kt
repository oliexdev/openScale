package com.health.openscale.core.bluetooth.scales

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.MeasurementType
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RenphoHandlerTest {
    private val central = BluetoothCentralManager(
        ApplicationProvider.getApplicationContext<Context>(), object : BluetoothCentralManagerCallback() {},
        Handler(Looper.getMainLooper()),
    )
    private val scale = device("QN-Scale", uuid16(0x181D))
    private val weight = uuid16(0x2A9D)
    private val composition = uuid16(0x2A9C)

    @After fun close() = central.close()

    @Test
    fun `captured weigh-in is saved exactly once`() = runTest {
        val link = connect(this)
        assertThat(link.subscriptions).containsExactly(
            uuid16(0x181C) to uuid16(0x2A9F), uuid16(0x181D) to weight, uuid16(0x181B) to composition,
        )
        // ES-WBE28 notifications from KristianP26/ble-scale-sync issue 267, frames 1374 and 1477.
        val weightFrame = hex("4e6a07ea0707040c1a06aa00000000")
        val bodyFrame = hex("0c02f901f70144000dec00a500290091020600")
        link.handler.handleNotification(weight, weightFrame)
        assertThat(link.saved).isEmpty()
        link.handler.supportFor(device("RENPHO-SCALE").copy(address = "C0:FF:EE:12:34:57"))
        link.handler.handleNotification(composition, bodyFrame)
        val reading = link.saved.single()
        assertThat(reading[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(94.9f)
        assertThat(reading.dateTime!!.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString())
            .isEqualTo("2026-07-04")

        link.handler.handleNotification(composition, bodyFrame)
        link.handler.handleNotification(weight, weightFrame.copyOf().apply { this[0] = 0x2E })
        link.handler.handleDisconnected()
        advanceUntilIdle()
        assertThat(link.saved).hasSize(1)
    }

    @Test
    fun `weight alone in pounds is saved after silence`() = runTest {
        val link = connect(this)
        link.handler.handleNotification(weight, hex("4fdc05"))
        assertThat(link.saved).isEmpty()
        advanceUntilIdle()
        assertThat(link.saved.single()[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(68.039f)
    }

    private fun connect(scope: CoroutineScope): TestLink {
        val link = TestLink(central.getPeripheral(scale.address))
        assertThat(link.handler.supportFor(scale)?.displayName).isEqualTo("RENPHO ES-WBE28 (QN-Scale)")
        link.handler.attach(link, link, link, link, scope)
        link.handler.handleConnected(link.currentUser())
        return link
    }

    private class TestLink(private val peripheral: BluetoothPeripheral) :
        ScaleDeviceHandler.Transport, ScaleDeviceHandler.Callbacks,
        ScaleDeviceHandler.DataProvider, ScaleDeviceHandler.DriverSettings {
        val handler = RenphoHandler()
        val saved = mutableListOf<ScaleMeasurement>()
        val subscriptions = mutableListOf<Pair<UUID, UUID>>()
        private val user = ScaleUser(bodyHeight = 173f)
        override fun getPeripheral() = peripheral
        override fun setNotifyOn(service: UUID, characteristic: UUID) { subscriptions += service to characteristic }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override fun onPublish(measurement: ScaleMeasurement) { saved += measurement.snapshot() }
        override fun onError(resId: Int, t: Throwable?, vararg args: Any) { throw AssertionError(t) }
        override fun resolveString(resId: Int, vararg args: Any) = resId.toString()
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?) = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }
}
