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
import com.health.openscale.R
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.QardioBaseLib
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.CHR_BATTERY_LEVEL
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.CHR_ENGINEERING
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.CHR_RESULT
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.CHR_STATE
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.CHR_USER_CONFIG
import com.health.openscale.core.bluetooth.scales.QardioBaseHandler.Companion.SERVICE
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.GregorianCalendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

// Robolectric for the real org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QardioBaseHandlerTest {

    private val settings = InMemorySettings()
    private val user = ScaleUser(
        id = 7,
        userName = "wedsa5",
        birthday = GregorianCalendar(1990, 0, 1).time,
        bodyHeight = 180f,
        gender = GenderType.MALE,
    )

    @Test
    fun `claims the scale by name or vendor service only`() {
        val handler = QardioBaseHandler()
        assertThat(handler.supportFor(device("QardioBase"))).isNotNull()
        assertThat(handler.supportFor(device("Scale", SERVICE))).isNotNull()
        assertThat(handler.supportFor(device("QardioArm"))).isNull()
    }

    @Test
    fun `first connection registers the profile and leaves configuration mode`() {
        val s = connect()
        assertThat(s.transport.notified).containsExactly(CHR_STATE, CHR_ENGINEERING)
        assertThat(s.transport.writes.first().copyOfRange(0, 3)).isEqualTo(hex("00 00 0c"))
        assertThat(s.transport.writes.map { it.h() }).contains((QardioBaseLib.configMode(true)).h())

        s.state(1)
        assertThat(s.transport.reads).contains(CHR_USER_CONFIG)

        s.transport.writes.clear()
        s.notify(CHR_USER_CONFIG, """{"users":[{"id":"11"}]}""")
        val payload = s.ackAllFrames()

        val add = JSONObject(payload).getJSONObject("add")
        assertThat(add.getString("id")).isEqualTo(ownScaleId().toString())
        assertThat(add.getString("name")).isEqualTo("wedsa5")
        assertThat(add.getString("height")).isEqualTo("180")

        val epilogue = s.transport.writes
        assertThat(epilogue[0].copyOfRange(0, 4)).isEqualTo(hex("00 00 10 00"))
        assertThat(epilogue[1].copyOfRange(0, 3)).isEqualTo(hex("00 00 0c"))
        assertThat(epilogue[2]).isEqualTo(QardioBaseLib.configMode(false))
        assertThat(s.callbacks.infos).contains(R.string.bt_info_register_new_user_started)
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_updated)
    }

    @Test
    fun `known profile is rewritten in place when the app profile changed`() {
        registered()
        val s = connect(user.copy(bodyHeight = 182f))
        assertThat(s.transport.writes.map { it.h() }).contains((QardioBaseLib.configMode(true)).h())

        s.state(1)
        s.transport.writes.clear()
        s.notify(CHR_USER_CONFIG, """{"users":[{"id":"11"},{"id":"${ownScaleId()}"}]}""")
        val users = JSONObject(s.ackAllFrames()).getJSONArray("users")
        assertThat(users.length()).isEqualTo(2)
        assertThat(users.getJSONObject(0).length()).isEqualTo(0)
        assertThat(users.getJSONObject(1).getString("height")).isEqualTo("182")
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_updating)
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_updated)
        assertThat(s.callbacks.infos).doesNotContain(R.string.bt_info_register_new_user_started)
    }

    @Test
    fun `up to date profile skips configuration mode and publishes own result`() {
        registered()
        val s = connect()
        assertThat(s.transport.writes.map { it.h() }).doesNotContain((QardioBaseLib.configMode(true)).h())
        s.notify(CHR_USER_CONFIG, """{"users":[{"id":"${ownScaleId()}"}]}""")
        assertThat(s.transport.writes.map { it.h() }).doesNotContain((QardioBaseLib.configMode(true)).h())

        assertThat(s.transport.reads.count { it == CHR_RESULT }).isEqualTo(1) // at connect
        s.state(3)
        s.state(6)
        s.state(6)
        assertThat(s.transport.reads.count { it == CHR_RESULT }).isEqualTo(2)
        s.notify(CHR_RESULT, result(id = "r1", userId = ownScaleId().toLong()))

        val m = s.callbacks.published.single()
        assertThat(s.transport.disconnects).isEqualTo(1)
        assertThat(m.userId).isEqualTo(7)
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(80f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(500f)
        // Exactly what the scale reported; nothing computed (lean mass is openScale's job).
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isWithin(1e-4f).of(20f)
        assertThat(m[MeasurementType.WATER]!!.value).isWithin(1e-4f).of(55f)
        assertThat(m[MeasurementType.MUSCLE]!!.value).isWithin(1e-4f).of(38f)
        assertThat(m[MeasurementType.BONE]!!.value).isWithin(1e-4f).of(3f)
        assertThat(m[MeasurementType.LBM]).isNull()

        // The same weigh-in read again (state notified twice) is not saved twice.
        s.notify(CHR_RESULT, result(id = "r1", userId = ownScaleId().toLong()))
        assertThat(s.callbacks.published).hasSize(1)
    }

    @Test
    fun `result read at connect is published when new but never warns or disconnects`() {
        registered()
        val s = connect()
        assertThat(s.transport.reads).contains(CHR_RESULT)
        s.notify(CHR_RESULT, result(id = "old1", userId = 999L, name = "partner"))
        assertThat(s.callbacks.warns).isEmpty()

        val s2 = connect()
        s2.notify(CHR_RESULT, result(id = "old2", userId = ownScaleId().toLong()))
        assertThat(s2.callbacks.published).hasSize(1)
        assertThat(s2.transport.disconnects).isEqualTo(0)
    }

    @Test
    fun `battery level is read at connect and only a low level warns`() {
        registered()
        val s = connect()
        assertThat(s.transport.reads).contains(CHR_BATTERY_LEVEL)
        s.notify(CHR_BATTERY_LEVEL, byteArrayOf(80))
        assertThat(s.callbacks.warns).isEmpty()
        s.notify(CHR_BATTERY_LEVEL, byteArrayOf(8))
        assertThat(s.callbacks.warns).containsExactly(R.string.bt_warn_low_battery)
    }

    @Test
    fun `result for another scale user is not saved`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, result(id = "r2", userId = 999L, name = "partner"))
        assertThat(s.callbacks.published).isEmpty()
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_other_user)
        assertThat(s.callbacks.warnArgs.last().toList()).containsExactly("80.0 kg", "partner").inOrder()
        assertThat(s.transport.disconnects).isEqualTo(1)
    }

    @Test
    fun `state 6 seen first at connect is not read again`() {
        registered()
        val s = connect()
        s.state(6)
        assertThat(s.transport.reads.count { it == CHR_RESULT }).isEqualTo(1) // the read at connect
    }

    @Test
    fun `old guest result read at connect is neither held nor warned about`() {
        registered()
        val s = connect()
        s.state(6)
        val old = "1001" + (System.currentTimeMillis() / 1000 - 3600)
        s.notify(CHR_RESULT, """{"id":"$old","weight":"80.0","z":"500"}""")
        assertThat(s.callbacks.infos).doesNotContain(R.string.bt_info_qardio_guest)
        s.handler.handleDisconnected()
        assertThat(s.callbacks.warns).isEmpty()
        assertThat(s.callbacks.published).isEmpty()
    }

    @Test
    fun `guest result read at connect right after the weigh-in is held like a live one`() {
        registered()
        val s = connect()
        s.state(6)
        val recent = "1001" + (System.currentTimeMillis() / 1000 - 10)
        s.notify(CHR_RESULT, """{"id":"$recent","weight":"80.0","z":"500"}""")
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_guest)
    }

    @Test
    fun `B100 result read at connect is saved when the first state is 6`() {
        registered()
        val b100 = """{"weight":"76.0","z":"480","fat":"21.5"}"""
        val late = connect()
        // The weigh-in finished before the phone connected: only the read at connect sees it.
        late.state(6)
        late.notify(CHR_RESULT, b100)
        assertThat(late.callbacks.published).hasSize(1)
        // Read again in the same wake-up: not saved twice.
        late.notify(CHR_RESULT, b100)
        assertThat(late.callbacks.published).hasSize(1)

        // The same values on another wake-up are a new weigh-in (same weight, another day).
        val nextDay = connect()
        nextDay.state(6)
        nextDay.notify(CHR_RESULT, b100)
        assertThat(nextDay.callbacks.published).hasSize(1)
    }

    @Test
    fun `B100 result read at connect is skipped when the first state is not 6`() {
        registered()
        for (first in listOf(0, 3)) {
            val s = connect()
            s.state(first)
            // Still holding yesterday's weigh-in.
            s.notify(CHR_RESULT, """{"weight":"76.0","z":"480","fat":"21.5"}""")
            assertThat(s.callbacks.published).isEmpty()
            // Today's arrives as a STATE 6 change.
            s.state(6)
            s.notify(CHR_RESULT, """{"weight":"75.8","z":"482","fat":"21.4"}""")
            assertThat(s.callbacks.published).hasSize(1)
        }
    }

    @Test
    fun `a second guest weigh-in resolves the held one instead of dropping it`() {
        registered()
        settings.putInt("saveGuest", 1)
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"id":"g4","weight":"80.0","z":"500"}""")
        s.notify(CHR_RESULT, """{"id":"g5","weight":"81.0","z":"510"}""")
        assertThat(s.callbacks.published.single()[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(80f)
        s.handler.handleDisconnected()
        assertThat(s.callbacks.published).hasSize(2)
    }

    @Test
    fun `another user's weigh-in does not discard a held guest weigh-in`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"id":"g6","weight":"80.0","z":"500"}""")
        s.notify(CHR_RESULT, result(id = "r7", userId = 999L, name = "partner"))
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_guest_not_saved)
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_other_user)
    }

    @Test
    fun `result without user fields (B100) is saved for the current user`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"weight":"76.0","z":"0","fat":"0"}""")
        assertThat(s.callbacks.published.single()[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(76f)
        assertThat(s.callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `guest weigh-in is held and replaced when the user assigns it on the scale`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"id":"g1","weight":"80.0","z":"500"}""")
        assertThat(s.callbacks.published).isEmpty()
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_guest)
        assertThat(s.transport.disconnects).isEqualTo(0)

        // Same weigh-in, now assigned to us.
        s.notify(CHR_RESULT, result(id = "g1", userId = ownScaleId().toLong()))
        val m = s.callbacks.published.single()
        assertThat(m[MeasurementType.MUSCLE]!!.value).isWithin(1e-4f).of(38f)
        assertThat(s.transport.disconnects).isEqualTo(1)

        // Disconnecting afterwards does not save the guest copy as well.
        s.handler.handleDisconnected()
        assertThat(s.callbacks.published).hasSize(1)
    }

    @Test
    fun `unassigned guest weigh-in is not saved by default, like the official app`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"id":"g3","weight":"80.0","z":"500"}""")
        s.handler.handleDisconnected()
        assertThat(s.callbacks.published).isEmpty()
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_guest_not_saved)
    }

    @Test
    fun `held guest weigh-in is saved on disconnect when guest saving is enabled`() {
        registered()
        settings.putInt("saveGuest", 1)
        val s = connect()
        s.state(3)
        s.state(6)
        s.notify(CHR_RESULT, """{"id":"g2","weight":"80.0","z":"500"}""")
        assertThat(s.callbacks.published).isEmpty()
        s.handler.handleDisconnected()
        val m = s.callbacks.published.single()
        // A guest weigh-in: the scale computed no body composition, and neither do we.
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-4f).of(80f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-4f).of(500f)
        assertThat(m[MeasurementType.BODY_FAT]).isNull()
        assertThat(m[MeasurementType.MUSCLE]).isNull()
    }

    @Test
    fun `select on connect is off by default and sends the own profile id when enabled`() {
        registered()
        val off = connect()
        assertThat(off.transport.writes.none { it.size > 2 && it[2].toInt() == 0x14 }).isTrue()

        settings.putInt("selectOnConnect", 1)
        val on = connect()
        assertThat(on.transport.writes.map { it.h() }).contains(QardioBaseLib.selectUser(ownScaleId()).h())
    }

    @Test
    fun `select on connect follows a fresh registration`() {
        settings.putInt("selectOnConnect", 1)
        val s = connect()
        assertThat(s.transport.writes.none { it.size > 2 && it[2].toInt() == 0x14 }).isTrue()
        s.state(1)
        s.notify(CHR_USER_CONFIG, """{"users":[]}""")
        s.ackAllFrames()
        assertThat(s.transport.writes.last().h()).isEqualTo(QardioBaseLib.selectUser(ownScaleId()).h())
    }

    @Test
    fun `profile missing from the scale triggers registration again`() {
        registered()
        val s = connect()
        s.notify(CHR_USER_CONFIG, """{"users":[{"id":"11"}]}""")
        assertThat(s.transport.writes.map { it.h() }).contains((QardioBaseLib.configMode(true)).h())
    }

    @Test
    fun `full scale aborts registration with a warning`() {
        val s = connect()
        s.state(1)
        s.notify(CHR_USER_CONFIG, """{"users":[{"id":"1"},{"id":"2"}]}""")
        s.transport.writes.clear()
        s.notify(CHR_ENGINEERING, hex("00 00 04 fe"))
        assertThat(s.transport.writes.map { it.h() }).containsExactly((QardioBaseLib.configMode(false)).h())
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_users_full)
    }

    @Test
    fun `pending reset wipes the scale and the next connection registers again`() {
        registered()
        settings.putInt("scaleImpedance", 1)
        settings.putInt("scaleHaptic", 0)
        settings.putInt("pendingReset", 1)
        val s = connect()
        assertThat(s.transport.writes.map { it.h() }).contains((QardioBaseLib.configMode(true)).h())

        s.transport.writes.clear()
        s.state(1)
        assertThat(s.transport.writes[0].copyOfRange(0, 4)).isEqualTo(hex("00 00 10 01"))
        assertThat(s.transport.writes[1]).isEqualTo(QardioBaseLib.reset())

        s.transport.writes.clear()
        s.notify(CHR_ENGINEERING, hex("00 00 11 00"))
        assertThat(settings.getInt("pendingReset", -1)).isEqualTo(0)
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_reset_done)
        // Reset only: nothing is registered in this connection, so the scale can be given away.
        assertThat(s.transport.writes).isEmpty()
        assertThat(s.transport.disconnects).isEqualTo(1)

        // Next connection: registers again and puts back the settings the reset restored.
        val again = connect()
        assertThat(again.transport.writes.map { it.h() }).contains(QardioBaseLib.configMode(true).h())
        again.state(1)
        again.transport.writes.clear()
        again.notify(CHR_USER_CONFIG, """{"z":"0","haptic":"1","users":[]}""")
        val o = JSONObject(again.ackAllFrames())
        assertThat(o.has("add")).isTrue()
        assertThat(o.getString("z")).isEqualTo("1")
        assertThat(o.getString("haptic")).isEqualTo("0")
        assertThat(settings.getInt("pendingImpedance", -1)).isEqualTo(-1)
        assertThat(settings.getInt("pendingHaptic", -1)).isEqualTo(-1)
    }

    @Test
    fun `legacy reset clears the user and wifi configs even without acks`() {
        registered()
        settings.putInt("pendingReset", 2)
        val s = connect()
        s.transport.writes.clear()
        s.state(1)
        assertThat(s.transport.writes.single()).isEqualTo(hex("00 00 04 20 00"))
        s.notify(CHR_ENGINEERING, hex("00 00 04 00"))
        assertThat(s.transport.writes.last()).isEqualTo(hex("00 00 04 10 00"))
        // No ack for the Wi-Fi clear: the unique id still follows, then the reset is done.
        waitUntil(5_000) { s.transport.writes.last().copyOfRange(0, 4).contentEquals(hex("00 00 10 01")) }
        // Not at once: disconnecting would drop the queued unique id write.
        assertThat(s.transport.disconnects).isEqualTo(0)
        waitUntil { s.transport.disconnects == 1 }
        assertThat(settings.getInt("pendingReset", -1)).isEqualTo(0)
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_reset_done)
    }

    @Test
    fun `failed user list read is retried and never guessed as an empty list`() {
        registered()
        settings.putInt("generation", 99) // profile outdated -> configuration mode
        val s = connect()
        s.state(1)
        s.transport.reads.clear()
        // A failed GATT read is delivered as garbage.
        s.notify(CHR_USER_CONFIG, byteArrayOf(0))
        assertThat(s.transport.writes.none { it.size > 5 && it[2].toInt() == 0x04 }).isTrue()
        assertThat(s.transport.reads).contains(CHR_USER_CONFIG)

        s.notify(CHR_USER_CONFIG, byteArrayOf(0))
        s.notify(CHR_USER_CONFIG, byteArrayOf(0))
        assertThat(s.transport.writes.none { it.size > 5 && it[2].toInt() == 0x04 }).isTrue()
        assertThat(s.transport.writes.map { it.h() }).contains(QardioBaseLib.configMode(false).h())
        assertThat(s.callbacks.warns).contains(R.string.bt_warn_qardio_profile_timeout)
    }

    @Test
    fun `profile write keeps the scale's vibration setting`() {
        val s = connect()
        s.state(1)
        s.notify(CHR_USER_CONFIG, """{"z":"1","haptic":"0","users":[]}""")
        val o = JSONObject(s.ackAllFrames())
        assertThat(o.getString("haptic")).isEqualTo("0")
        assertThat(o.getString("z")).isEqualTo("1")
    }

    @Test
    fun `pending scale-wide change is applied once with the next profile write`() {
        registered()
        settings.putInt("pendingHaptic", 0)
        val s = connect()
        // Profile is up to date, but the pending change needs configuration mode.
        assertThat(s.transport.writes.map { it.h() }).contains(QardioBaseLib.configMode(true).h())
        s.state(1)
        s.transport.writes.clear()
        s.notify(CHR_USER_CONFIG, """{"z":"1","haptic":"1","users":[{"id":"${ownScaleId()}"}]}""")
        val o = JSONObject(s.ackAllFrames())
        assertThat(o.getString("haptic")).isEqualTo("0")
        assertThat(o.getString("z")).isEqualTo("1") // not requested: the scale's value is kept
        assertThat(o.getJSONArray("users").length()).isEqualTo(1) // rewrites our slot, no new user
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_updating)
        assertThat(s.callbacks.infos).contains(R.string.bt_info_qardio_updated)
        assertThat(s.callbacks.infos).doesNotContain(R.string.bt_info_register_new_user_started)

        assertThat(settings.getInt("pendingHaptic", -1)).isEqualTo(-1)
        assertThat(settings.getInt("scaleHaptic", -1)).isEqualTo(0)
        assertThat(settings.getInt("scaleImpedance", -1)).isEqualTo(1)

        // Next connection: nothing pending, no configuration mode.
        val again = connect()
        assertThat(again.transport.writes.map { it.h() }).doesNotContain(QardioBaseLib.configMode(true).h())
    }

    @Test
    fun `switches mirror what the scale reports on every connection`() {
        registered()
        val s = connect()
        s.notify(CHR_USER_CONFIG, """{"z":"0","haptic":"1","users":[{"id":"${ownScaleId()}"}]}""")
        assertThat(settings.getInt("scaleImpedance", -1)).isEqualTo(0)
        assertThat(settings.getInt("scaleHaptic", -1)).isEqualTo(1)
    }

    @Test
    fun `failed result read after state 6 is retried once`() {
        registered()
        val s = connect()
        s.state(3)
        s.state(6)
        s.transport.reads.clear()
        s.notify(CHR_RESULT, byteArrayOf(0))
        waitUntil { s.transport.reads.contains(CHR_RESULT) }
        s.notify(CHR_RESULT, result(id = "r9", userId = ownScaleId().toLong()))
        assertThat(s.callbacks.published).hasSize(1)
    }

    @Test
    fun `unacknowledged profile chunk aborts registration instead of hanging`() {
        val s = connect()
        s.state(1)
        s.notify(CHR_USER_CONFIG, """{"users":[]}""")
        assertThat(s.transport.writes.any { it.size > 5 && it[2].toInt() == 0x04 }).isTrue()
        s.transport.writes.clear()

        waitUntil(5_000) { s.callbacks.warns.contains(R.string.bt_warn_qardio_profile_timeout) }
        assertThat(s.transport.writes.map { it.h() }).contains(QardioBaseLib.configMode(false).h())
    }

    // --- Harness --------------------------------------------------------------------------------

    /** The handler's timers run on a real dispatcher. */
    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
            Thread.sleep(20)
        }
    }

    private fun ByteArray.h(): String = joinToString("") { "%02x".format(it) }

    private fun ownScaleId(): Int = settings.getInt("scaleUserId/7", -1)

    /** Runs a first session to completion so the profile counts as stored on the scale. */
    private fun registered() {
        val s = connect()
        s.state(1)
        s.notify(CHR_USER_CONFIG, """{"users":[]}""")
        s.ackAllFrames()
    }

    private fun result(id: String, userId: Long, name: String = "wedsa5") =
        """{"id":"$id","weight":"80.0","z":"500","fat":"20","tbw":"55","bmc":"3","mt":"14","sm":"38","user":"$name","userid":"$userId"}"""


    private fun connect(u: ScaleUser = user): Session {
        val handler = QardioBaseHandler()
        val transport = RecordingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(transport, callbacks, settings, FixedDataProvider(u), CoroutineScope(EmptyCoroutineContext))
        handler.handleConnected(u)
        return Session(handler, transport, callbacks)
    }

    private class Session(
        val handler: QardioBaseHandler,
        val transport: RecordingTransport,
        val callbacks: CapturingCallbacks,
    ) {
        fun notify(chr: UUID, data: ByteArray) = handler.handleNotification(chr, data)
        fun notify(chr: UUID, json: String) = notify(chr, json.toByteArray())
        fun state(value: Int) = notify(CHR_STATE, byteArrayOf(value.toByte()))

        /** Acks every long-write frame as the scale would and returns the reassembled JSON. */
        fun ackAllFrames(): String {
            val payload = mutableListOf<Byte>()
            while (true) {
                val frame = transport.writes.lastOrNull { it.size > 5 && it[2].toInt() == 0x04 } ?: break
                transport.writes.remove(frame)
                payload += frame.drop(5)
                notify(CHR_ENGINEERING, hex("00 00 04 00"))
            }
            return String(payload.toByteArray())
        }
    }

    private class RecordingTransport : ScaleDeviceHandler.Transport {
        val notified = mutableListOf<UUID>()
        val writes = mutableListOf<ByteArray>()
        val reads = mutableListOf<UUID>()
        var disconnects = 0
        override fun setNotifyOn(service: UUID, characteristic: UUID) { notified += characteristic }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            if (characteristic == CHR_ENGINEERING) writes += payload
        }
        override fun read(service: UUID, characteristic: UUID) { reads += characteristic }
        override fun disconnect() { disconnects++ }
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val infos = mutableListOf<Int>()
        val warns = mutableListOf<Int>()
        val warnArgs = mutableListOf<Array<out Any>>()
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun onInfo(resId: Int, vararg args: Any) { infos += resId }
        override fun onWarn(resId: Int, vararg args: Any) { warns += resId; warnArgs += args }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
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
