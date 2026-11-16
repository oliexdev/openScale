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
import com.health.openscale.core.bluetooth.ScaleCatalog
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives [EufyT9140Handler] with the notification sequence of capture 2 from openScale issue
 * #772 (EufyLife app showed 79.9 kg), verbatim and in order, plus synthetic edge cases.
 */
class EufyT9140HandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val notify: UUID = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private val write: UUID = UUID.fromString("4143f6b1-5300-4900-4700-414943415245")

    /** Capture 2, everything the scale sent after the app's three writes. */
    private val capture2 = listOf(
        "ac02fe070000ccd1",                         // time sync ok
        "ac02fe06fe00ccce",                         // unit ok
        "ac02fe010000cfce",                         // history start
        "ac02ff42333d61031f02286c283d6102fb0239",   // 2 stored records
        "ac02fe020000cfcf",                         // history end
        "ac02fe1cff00cce5",
        "ac0200320000ce00", "ac0202b20000ce82", "ac02030e0000cedf", "ac0203230000cef4",
        "ac02031f0000cef0", "ac02031f0000cef0", "ac02031f0000cef0", "ac02031f0000cef0",
        "ac02031f0000caec",                         // stable 79.9
        "ac02fd000000cbc8ac02031f0000caec",
        "ac02fd01022bcbf6ac02031f0000caec",         // impedance 555
        "ac02f16b333d61ccf9ac02031f0000caec",       // measurement time
        "ac02fe100000ccdaac02031f0000caec",         // finished
        "ac02fe6b333d61031f022b0000000000000000",   // final record
        "ac02fe1cff00cce5", "ac02fe1cff00cce5", "ac02fe1a0000cce4",
    )

    private fun epochDate(epoch: Long) = Date(epoch * 1000)

    @Test
    fun `claims only the eufy T9140 name`() {
        val h = EufyT9140Handler()
        assertThat(h.supportFor(ScaleCatalog.device("eufy T9140"))).isNotNull()
        assertThat(h.supportFor(ScaleCatalog.device("eufy T9120"))).isNull() // LeFu variant, OneByoneHandler
        assertThat(h.supportFor(ScaleCatalog.device("eufy T9146"))).isNull()
    }

    @Test
    fun `connect sequence repeats the vendor app writes`() {
        val (_, rig) = connected()
        assertThat(rig.notifies).containsExactly(notify)
        assertThat(rig.writes.map { it.first }).containsExactly(write, write, write)
        val w = rig.writes.map { it.second }
        assertThat(w[0].copyOfRange(0, 3)).isEqualTo(hex("ac02f2"))
        assertThat(w[1]).isEqualTo(hex("ac02fe060000ccd0"))
        assertThat(w[2]).isEqualTo(hex("ac02ff000000cfce"))
    }

    @Test
    fun `capture 2 publishes two stored weigh-ins and one live weigh-in`() {
        val (handler, rig) = connected()
        capture2.forEach { handler.handleNotification(notify, hex(it)) }
        handler.handleDisconnected()

        assertThat(rig.published).hasSize(3)
        val (stored1, stored2, live) = rig.published

        assertThat(stored1.dateTime).isEqualTo(epochDate(0x613D3342))
        assertThat(stored1[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(stored1[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(552f)
        assertThat(stored2.dateTime).isEqualTo(epochDate(0x613D286C))
        assertThat(stored2[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(76.3f)

        assertThat(live.dateTime).isEqualTo(epochDate(0x613D336B))
        assertThat(live[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(live[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(555f)
        assertThat(live[MeasurementType.BODY_FAT]).isNotNull()
        assertThat(live[MeasurementType.WATER]).isNotNull()
        assertThat(rig.disconnects).isEqualTo(1)
    }

    @Test
    fun `without a final record the streamed values are published on power off`() {
        // Synthetic: capture 2 minus the final record.
        val (handler, rig) = connected()
        capture2.filterNot { it.startsWith("ac02fe6b") }.forEach { handler.handleNotification(notify, hex(it)) }

        val live = rig.published.last()
        assertThat(live[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(live[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(555f)
        assertThat(live.dateTime).isEqualTo(epochDate(0x613D336B))
    }

    @Test
    fun `no impedance publishes weight only`() {
        // Synthetic final record with impedance 0, e.g. weighed in socks.
        val (handler, rig) = connected()
        handler.handleNotification(notify, hex("ac02fe6b333d61031f00000000000000000000"))
        val m = rig.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(m[MeasurementType.IMPEDANCE]).isNull()
        assertThat(m[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `stored records with an unset clock are dropped`() {
        // Synthetic history record at epoch 0x00001000 (1970), next to a valid one.
        val (handler, rig) = connected()
        handler.handleNotification(notify, hex("ac02ff00100000031f022842333d61031f0228"))
        assertThat(rig.published.map { it.dateTime }).containsExactly(epochDate(0x613D3342))
    }

    // --- Harness ---------------------------------------------------------------

    private fun connected(): Pair<EufyT9140Handler, Rig> {
        val user = ScaleUser(
            id = 3,
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -35) }.time,
            bodyHeight = 190.5f,
            gender = GenderType.MALE,
        )
        val handler = EufyT9140Handler()
        val rig = Rig(user)
        handler.attach(rig, rig, rig, rig, CoroutineScope(EmptyCoroutineContext))
        handler.handleConnected(user)
        return handler to rig
    }

    private class Rig(private val user: ScaleUser) :
        ScaleDeviceHandler.Transport, ScaleDeviceHandler.Callbacks,
        ScaleDeviceHandler.DriverSettings, ScaleDeviceHandler.DataProvider {
        val published = mutableListOf<ScaleMeasurement>()
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        val notifies = mutableListOf<UUID>()
        var disconnects = 0

        override fun setNotifyOn(service: UUID, characteristic: UUID) { notifies += characteristic }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += characteristic to payload
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() { disconnects++ }
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true

        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"

        override fun getInt(key: String, default: Int): Int = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit

        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
