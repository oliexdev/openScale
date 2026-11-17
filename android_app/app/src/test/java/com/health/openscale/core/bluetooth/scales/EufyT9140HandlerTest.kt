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
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.WeightUnit
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Drives [EufyT9140Handler] with verbatim notification sequences: capture 2 from openScale issue
 * #772 (2021 firmware, service 4143f6b0, EufyLife app showed 79.9 kg) and three capture_scale.py
 * runs on a 2026-firmware unit (service 4143f7b0), plus synthetic edge cases.
 */
class EufyT9140HandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val notify: UUID = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private val write: UUID = UUID.fromString("4143f6b1-5300-4900-4700-414943415245")
    private val notifyF7: UUID = UUID.fromString("4143f7b2-5300-4900-4700-414943415245")
    private val writeF7: UUID = UUID.fromString("4143f7b1-5300-4900-4700-414943415245")
    private val serviceF7: UUID = UUID.fromString("4143f7b0-5300-4900-4700-414943415245")
    private val serviceF6: UUID = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")

    /** 2026 unit, run A (read-only): two weigh-ins in one connection (display 50.2 lb, 51.3 lb). */
    private val runA = listOf(
        "ac0200e70000ceb5", "ac0200e60000ceb4", "ac0200e40000ceb2", "ac0200e40000ceb2",
        "ac02f423100bccfe", "ac02f723152acc25",
        "ac0200e40000caae",
        "ac02fd0102decba9ac0200e40000caae",          // impedance 734
        "ac02f1d38dfa6acc81ac0200e40000caae",        // 05:00:03Z
        "ac02fe100000ccda",
        "ac0201430000ce12", "ac0200f20000cec0", "ac0200ed0000cebb", "ac0200e90000ceb7",
        "ac02f423100bccfe", "ac02f723152acc25",
        "ac0200e90000cab3",
        "ac02fd010395cb61ac0200e90000cab3",          // impedance 917
        "ac02f1098efa6accb8ac0200e90000cab3",        // 05:00:57Z
        "ac02fe100000ccda",
    )

    /**
     * 2026 unit, run C: the result that woke the scale (21.9 kg) arrives right after subscribing,
     * before any write; then the acks and two stored offline weigh-ins.
     */
    private val runC = listOf(
        "ac02f423100bccfe", "ac02f723152acc25",
        "ac0200db0000caa5",
        "ac02fd010226cbf1ac0200db0000caa5",          // impedance 550
        "ac02f1dc90fa6acc8dac0200db0000caa5",        // 05:13:00Z
        "ac02fe100000ccda",
        "ac02fe070000ccd1", "ac02fe06fe00ccce",
        "ac02fe010000cfce",
        "ac02ff9490fa6a00ec0228a290fa6a00e00245",    // 05:11:48Z 23.6 kg, 05:12:02Z 22.4 kg
        "ac02fe020000cfcf",
    )

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
        val (_, rig) = connected(f7Present = false)
        assertThat(rig.notifies).containsExactly(notify)
        assertThat(rig.writes.map { it.first }).containsExactly(write, write, write)
        val w = rig.writes.map { it.second }
        assertThat(w[0].copyOfRange(0, 3)).isEqualTo(hex("ac02f2"))
        assertThat(w[1]).isEqualTo(hex("ac02fe060000ccd0"))
        assertThat(w[2]).isEqualTo(hex("ac02ff000000cfce"))
    }

    @Test
    fun `2026 units are driven on the f7b0 service`() {
        val (_, rig) = connected(f7Present = true)
        assertThat(rig.notifies).containsExactly(notifyF7)
        assertThat(rig.writes.map { it.first }).containsExactly(writeF7, writeF7, writeF7)
    }

    @Test
    fun `unverified display units are not sent`() {
        // Only kg (0) has been seen acknowledged; for lb/st the scale keeps its own setting.
        for (unit in listOf(WeightUnit.LB, WeightUnit.ST)) {
            val (_, rig) = connected(unit = unit)
            assertThat(rig.writes).hasSize(2)
            assertThat(rig.writes[0].second.copyOfRange(0, 3)).isEqualTo(hex("ac02f2"))
            assertThat(rig.writes[1].second).isEqualTo(hex("ac02ff000000cfce"))
        }
    }

    @Test
    fun `live weight and unit config are not claimed as implemented`() {
        val support = EufyT9140Handler().supportFor(ScaleCatalog.device("eufy T9140"))!!
        assertThat(support.implemented).containsNoneOf(
            DeviceCapability.LIVE_WEIGHT_STREAM, DeviceCapability.UNIT_CONFIG,
        )
    }

    @Test
    fun `capture 2 publishes two stored weigh-ins and one live weigh-in`() {
        val (handler, rig) = connected(f7Present = false)
        capture2.forEach { handler.handleNotification(notify, hex(it)) }
        handler.handleDisconnected()

        assertThat(rig.published).hasSize(3)
        val (stored1, stored2, live) = rig.published

        assertThat(stored1.dateTime).isEqualTo(epochDate(0x613D3342))
        assertThat(stored1[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(stored1[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(552f)
        assertThat(stored2.dateTime).isEqualTo(epochDate(0x613D286C))
        assertThat(stored2[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(76.3f)

        // Published on "measurement finished"; the final record that follows is the same weigh-in.
        assertThat(live.dateTime).isEqualTo(epochDate(0x613D336B))
        assertThat(live[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(79.9f)
        assertThat(live[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(555f)
        assertThat(live[MeasurementType.BODY_FAT]).isNotNull()
        assertThat(live[MeasurementType.WATER]).isNotNull()
        assertThat(rig.disconnects).isEqualTo(0) // the scale ends the connection itself
    }

    @Test
    fun `a final record alone is published`() {
        // Synthetic: only the #772 final record, nothing streamed before it.
        val (handler, rig) = connected()
        handler.handleNotification(notify, hex("ac02fe6b333d61031f022b0000000000000000"))
        val m = rig.published.single()
        assertThat(m.dateTime).isEqualTo(epochDate(0x613D336B))
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(555f)
    }

    @Test
    fun `run A publishes both weigh-ins of one connection`() {
        val (handler, rig) = connected()
        runA.forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleDisconnected()

        assertThat(rig.published.map { it.dateTime })
            .containsExactly(epochDate(0x6AFA8DD3), epochDate(0x6AFA8E09)).inOrder()
        assertThat(rig.published.map { it[MeasurementType.WEIGHT]!!.value }).containsExactly(22.8f, 23.3f).inOrder()
        assertThat(rig.published.map { it[MeasurementType.IMPEDANCE]!!.value }).containsExactly(734f, 917f).inOrder()
    }

    @Test
    fun `run C publishes the pending result and both stored weigh-ins`() {
        val (handler, rig) = connected()
        runC.forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleDisconnected()

        assertThat(rig.published.map { it.dateTime }).containsExactly(
            epochDate(0x6AFA90DC), epochDate(0x6AFA9094), epochDate(0x6AFA90A2),
        ).inOrder()
        assertThat(rig.published.map { it[MeasurementType.WEIGHT]!!.value }).containsExactly(21.9f, 23.6f, 22.4f).inOrder()
        assertThat(rig.published.map { it[MeasurementType.IMPEDANCE]!!.value }).containsExactly(550f, 552f, 581f).inOrder()
    }

    @Test
    fun `a stored copy of a weigh-in already published live is skipped`() {
        // Synthetic: run C's live result, then a history record of the same weigh-in (1 s apart).
        val (handler, rig) = connected()
        runC.take(6).forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleNotification(notifyF7, hex("ac02ffdd90fa6a00db02260000000000000000"))
        assertThat(rig.published).hasSize(1)
    }

    @Test
    fun `without a finished status the streamed values are published on disconnect`() {
        // Synthetic: run A's first weigh-in cut off before "measurement finished".
        val (handler, rig) = connected()
        runA.take(9).forEach { handler.handleNotification(notifyF7, hex(it)) }
        assertThat(rig.published).isEmpty()
        handler.handleDisconnected()
        assertThat(rig.published.single()[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(22.8f)
    }

    @Test
    fun `an unfinished weigh-in without a scale time is dropped on disconnect`() {
        // Synthetic: run A's first weigh-in cut off after the impedance, before the F1 time.
        val (handler, rig) = connected()
        runA.take(8).forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleDisconnected()
        assertThat(rig.published).isEmpty()
    }

    @Test
    fun `an abandoned weigh-in does not lend its impedance or time to the next one`() {
        // Synthetic: run A's first weigh-in up to its F1 time, the user steps off and on again
        // (live frame), and the second weigh-in finishes without impedance or time.
        val (handler, rig) = connected()
        runA.take(9).forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleNotification(notifyF7, hex("ac0201430000ce12"))   // live 32.3
        handler.handleNotification(notifyF7, hex("ac0200e90000cab3"))   // stable 23.3
        handler.handleNotification(notifyF7, hex("ac02fe100000ccda"))   // finished
        val m = rig.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(23.3f)
        assertThat(m[MeasurementType.IMPEDANCE]).isNull()
        assertThat(m.dateTime).isNotEqualTo(epochDate(0x6AFA8DD3))
    }

    @Test
    fun `a live result without a scale time still matches its stored copy`() {
        // Synthetic: run A's first weigh-in without its F1 frame, so it is saved with the phone's
        // clock; then the scale (clock synced at connect) offers the same weigh-in, 22.8 kg and
        // 734 ohm, stamped 20 s earlier, from its history.
        val (handler, rig) = connected()
        (runA.take(8) + "ac02fe100000ccda").forEach { handler.handleNotification(notifyF7, hex(it)) }
        assertThat(rig.published).hasSize(1)
        handler.handleNotification(notifyF7, historyFrame(System.currentTimeMillis() / 1000 - 20, 228, 734))
        assertThat(rig.published).hasSize(1)
    }

    @Test
    fun `a live result already imported from history is skipped`() {
        // Synthetic: the history record of run A's first weigh-in arrives before its live result.
        val (handler, rig) = connected()
        handler.handleNotification(notifyF7, hex("ac02ffd38dfa6a00e402de0000000000000000"))
        runA.take(10).forEach { handler.handleNotification(notifyF7, hex(it)) }
        handler.handleDisconnected()
        assertThat(rig.published.single().dateTime).isEqualTo(epochDate(0x6AFA8DD3))
    }

    @Test
    fun `a stored copy of a weigh-in saved with phone time last connection is skipped`() {
        // The last saved measurement is run A's first weigh-in, saved 30 s off the scale's time.
        val last = ScaleMeasurement().apply {
            userId = 3
            dateTime = epochDate(0x6AFA8DD3 + 30)
            this[MeasurementType.WEIGHT] = Kg(22.8f)
            this[MeasurementType.IMPEDANCE] = Ohm(734f)
        }
        val (handler, rig) = connected(lastWeights = mapOf(3 to last))
        handler.handleNotification(notifyF7, hex("ac02ffd38dfa6a00e402de0000000000000000"))
        assertThat(rig.published).isEmpty()
    }

    @Test
    fun `stored records closer to another user's weight are not imported`() {
        // Synthetic history: 23.6 kg (this user, last 23.0 kg) and 84.0 kg (other user, last 84.5 kg).
        val other = ScaleUser(id = 4, gender = GenderType.FEMALE)
        val (handler, rig) = connected(
            otherUsers = listOf(other),
            lastWeights = mapOf(3 to weightOnly(3, 23.0f), 4 to weightOnly(4, 84.5f)),
        )
        handler.handleNotification(notifyF7, hex("ac02ff9490fa6a00ec0228a290fa6a03480228"))
        assertThat(rig.published.map { it[MeasurementType.WEIGHT]!!.value }).containsExactly(23.6f)
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

    /** A `FF` history notification holding one record and an empty slot. */
    private fun historyFrame(epoch: Long, deciKg: Int, ohm: Int): ByteArray {
        val b = ByteArray(19)
        b[0] = 0xAC.toByte(); b[1] = 0x02; b[2] = 0xFF.toByte()
        for (i in 0 until 4) b[3 + i] = (epoch shr (8 * i)).toByte()
        b[7] = (deciKg shr 8).toByte(); b[8] = deciKg.toByte()
        b[9] = (ohm shr 8).toByte(); b[10] = ohm.toByte()
        return b
    }

    private fun weightOnly(userId: Int, kg: Float) = ScaleMeasurement().apply {
        this.userId = userId
        dateTime = epochDate(0x6AF00000)
        this[MeasurementType.WEIGHT] = Kg(kg)
    }

    private fun connected(
        f7Present: Boolean = true,
        unit: WeightUnit = WeightUnit.KG,
        otherUsers: List<ScaleUser> = emptyList(),
        lastWeights: Map<Int, ScaleMeasurement> = emptyMap(),
    ): Pair<EufyT9140Handler, Rig> {
        val user = ScaleUser(
            id = 3,
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -35) }.time,
            bodyHeight = 190.5f,
            gender = GenderType.MALE,
            scaleUnit = unit,
        )
        val handler = EufyT9140Handler()
        val rig = Rig(user, setOf(if (f7Present) serviceF7 else serviceF6), listOf(user) + otherUsers, lastWeights)
        handler.attach(rig, rig, rig, rig, CoroutineScope(EmptyCoroutineContext))
        handler.handleConnected(user)
        return handler to rig
    }

    private class Rig(
        private val user: ScaleUser,
        private val services: Set<UUID>,
        private val users: List<ScaleUser>,
        private val last: Map<Int, ScaleMeasurement>,
    ) :
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
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = service in services

        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"

        override fun getInt(key: String, default: Int): Int = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit

        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = users
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = last[userId]
    }
}
