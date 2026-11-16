/*
 * openScale
 * Copyright (C) 2026 nadelacruz
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
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.OmronHn300t2Lib.MeasurementRecord
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import com.health.openscale.core.bluetooth.libs.Hn300t2Fixture
import com.health.openscale.core.bluetooth.libs.OmronWlpFrame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class OmronHn300t2HandlerTest {

    private val ts = Date(1_700_000_000_000L)
    private val record = MeasurementRecord(ts, 1600)

    private class Rig(
        val handler: OmronHn300t2Handler,
        val settings: MemSettings,
        val callbacks: Calls,
        val data: Users,
        val wire: Wire,
        val scope: TestScope
    )

    private class MemSettings : ScaleDeviceHandler.DriverSettings {
        val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key) }
    }

    private class Calls : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val infos = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        override fun onError(resId: Int, t: Throwable?, vararg args: Any) { errors += resId }
        val interactions = mutableListOf<Pair<UserInteractionType, Any?>>()
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement }
        override fun onInfo(resId: Int, vararg args: Any) { infos += resId }
        override fun onUserInteractionRequired(interactionType: UserInteractionType, data: Any?) {
            interactions += interactionType to data
        }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class Users(
        val users: List<ScaleUser>,
        val last: Map<Int, Date> = emptyMap()
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser() = users.firstOrNull() ?: ScaleUser()
        override fun usersForDevice() = users
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? =
            last[userId]?.let { ScaleMeasurement(userId, it) }
    }

    private class Wire(val missing: Set<UUID> = emptySet()) : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        var disconnects = 0
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += characteristic to payload
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() { disconnects++ }
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = characteristic !in missing
    }

    private fun user(id: Int, name: String) = ScaleUser(id = id, userName = name)

    private fun rig(
        users: List<ScaleUser>,
        last: Map<Int, Date> = emptyMap(),
        seen: String? = null,
        bonded: Boolean = true,
        missing: Set<UUID> = emptySet()
    ): Rig {
        val settings = MemSettings().also { s -> seen?.let { s.strings["hn300t2_seen"] = it } }
        val calls = Calls()
        val data = Users(users, last)
        val handler = OmronHn300t2Handler(bondedOverride = { bonded })
        val wire = Wire(missing)
        val scope = TestScope(StandardTestDispatcher())
        handler.attach(wire, calls, settings, data, scope)
        return Rig(handler, settings, calls, data, wire, scope)
    }

    private val two = listOf(user(3, "A"), user(5, "B"))

    @Test
    fun `a record whose timestamp was seen is dropped with the no-new-measurements notice`() {
        val r = rig(two, seen = "1,${ts.time / 1000}")
        r.handler.handleRecord(record, 0)
        assertThat(r.callbacks.interactions).isEmpty()
        assertThat(r.callbacks.published).isEmpty()
        assertThat(r.callbacks.infos).containsExactly(R.string.bt_info_hn300t2_no_new_measurements)
    }

    @Test
    fun `zero users drops the record with the no-user notice`() {
        val r = rig(emptyList())
        r.handler.handleRecord(record, 0)
        assertThat(r.callbacks.published).isEmpty()
        assertThat(r.callbacks.interactions).isEmpty()
        assertThat(r.callbacks.infos).containsExactly(R.string.bt_info_hn300t2_no_user)
        assertThat(r.settings.strings["hn300t2_seen"]).isNull()
    }

    // ----- full session through handleConnected / handleNotification -----

    private fun Rig.endWrites() = wire.writes.count {
        it.first == OmronHn300t2Handler.CHR_WRITE && it.second.contentEquals(OmronWlpFrame.endTransmission())
    }

    /** Connects and answers the handshake with the fixture's echo carrying this session's token. */
    private fun Rig.connectAndEcho(trace: Hn300t2Fixture.Trace) {
        handler.handleConnected(users0())
        val token = wire.writes.last { it.first == OmronHn300t2Handler.CHR_UNLOCK }.second.copyOfRange(1, 5)
        val echo = trace.handshakeNotify.copyOf().also { token.copyInto(it, 2) }
        handler.handleNotification(OmronHn300t2Handler.CHR_UNLOCK, echo)
    }

    /** The trace's replies to the read-only client's commands (commit-write replies removed). */
    private fun replies(trace: Hn300t2Fixture.Trace) =
        trace.wldNotifies.filter { frameType(it) != OmronWlpFrame.TYPE_WRITE }

    /** Runs the weigh-in fixture up to (not including) the final END reply. Returns that reply. */
    private fun Rig.driveToRecord(): ByteArray {
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        connectAndEcho(trace)
        val replies = replies(trace)
        replies.dropLast(1).forEach { handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, it) }
        return replies.last()
    }

    /** Runs a whole fixture session through the handler, END reply included. */
    private fun Rig.driveAll(name: String) {
        val trace = Hn300t2Fixture.load(name)
        connectAndEcho(trace)
        replies(trace).forEach { handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, it) }
    }

    private fun Rig.commandWrites() = wire.writes.filter { it.first == OmronHn300t2Handler.CHR_WRITE }

    private fun frameType(b: ByteArray) = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)

    private fun users0() = ScaleUser(id = 3, userName = "A")


    @Test
    fun `a record older than the only user's last measurement is dropped`() {
        val r = rig(listOf(user(3, "A")), last = mapOf(3 to Date(ts.time + 1000)))
        r.handler.handleRecord(record, 0)
        assertThat(r.callbacks.published).isEmpty()
        assertThat(r.callbacks.infos).containsExactly(R.string.bt_info_hn300t2_no_new_measurements)
    }

    @Test
    fun `a new record is attributed to the current app user`() {
        val r = rig(two.reversed()) // currentUser() is the first entry: user 5
        r.handler.handleRecord(record, 0)
        val m = r.callbacks.published.single()
        assertThat(m.userId).isEqualTo(5)
        assertThat(m.dateTime).isEqualTo(ts)
        assertThat(m[MeasurementType.WEIGHT]).isEqualTo(Kg(80.0f))
        assertThat(r.callbacks.interactions).isEmpty()
        assertThat(r.settings.strings["hn300t2_seen"]).isEqualTo("${ts.time / 1000}")
    }

    @Test
    fun `accepted limitation - a record newer for one user but older for another is attributed to the current user`() {
        val r = rig(two, last = mapOf(3 to Date(ts.time + 1000), 5 to Date(ts.time - 1000)))
        r.handler.handleRecord(record, 0)
        assertThat(r.callbacks.published.single().userId).isEqualTo(3)
    }

    @Test
    fun `the session ends right after the record`() {
        val r = rig(two)
        r.driveToRecord()
        assertThat(r.endWrites()).isEqualTo(1)
        assertThat(r.callbacks.interactions).isEmpty()
        assertThat(r.callbacks.published).hasSize(1)
    }

    // ----- failure paths through handleConnected / handleNotification -----

    private val timeout = OmronHn300t2Handler.RESPONSE_TIMEOUT_MS

    @Test
    fun `the watchdog ends a stalled session with the timeout error and a disconnect`() {
        val r = rig(two)
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        r.connectAndEcho(trace)
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, replies(trace)[0])
        r.scope.advanceTimeBy(timeout - 1)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).isEmpty()
        r.scope.advanceTimeBy(2)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_timeout)
        assertThat(r.wire.disconnects).isEqualTo(1)
        r.handler.handleDisconnected() // the timeout is the only message
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_timeout)
    }

    @Test
    fun `every handled notify re-arms the watchdog`() {
        val r = rig(two)
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        r.connectAndEcho(trace)
        r.scope.advanceTimeBy(timeout - 1000)
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, replies(trace)[0])
        r.scope.advanceTimeBy(timeout - 1000)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).isEmpty()
        r.scope.advanceTimeBy(1001)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_timeout)
    }

    @Test
    fun `a handshake echo mismatch reports the handshake error before any WLD write`() {
        val r = rig(two)
        r.handler.handleConnected(users0())
        r.handler.handleNotification(OmronHn300t2Handler.CHR_UNLOCK, ByteArray(64).also { it[0] = 0x91.toByte() })
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_handshake)
        assertThat(r.wire.disconnects).isEqualTo(1)
        assertThat(r.commandWrites()).isEmpty()
    }

    @Test
    fun `a failure after the handshake reports the transfer error`() {
        val r = rig(two)
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        r.connectAndEcho(trace)
        // the END reply where the opener's reply is expected
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, replies(trace).last())
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_transfer)
        assertThat(r.wire.disconnects).isEqualTo(1)
        r.handler.handleDisconnected()
        assertThat(r.callbacks.errors).hasSize(1)
    }

    @Test
    fun `a missing characteristic reports the service-missing error and writes nothing`() {
        val r = rig(two, missing = setOf(OmronHn300t2Handler.CHR_NOTIFY))
        r.handler.handleConnected(users0())
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_service_missing)
        assertThat(r.wire.disconnects).isEqualTo(1)
        assertThat(r.wire.writes).isEmpty()
    }

    @Test
    fun `an unbonded link at the handshake echo is refused before the session opener`() {
        val r = rig(two, bonded = false)
        r.connectAndEcho(Hn300t2Fixture.load("frames-weigh-in"))
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_bond_required)
        assertThat(r.wire.disconnects).isEqualTo(1)
        assertThat(r.commandWrites()).isEmpty()
        r.handler.handleDisconnected()
        assertThat(r.callbacks.errors).hasSize(1)
    }

    @Test
    fun `the pairing trace ends with the no-new-measurements notice`() {
        val r = rig(two)
        r.driveAll("frames-pairing")
        assertThat(r.callbacks.infos).containsExactly(R.string.bt_info_hn300t2_no_new_measurements)
        assertThat(r.callbacks.published).isEmpty()
        assertThat(r.callbacks.errors).isEmpty()
        assertThat(r.endWrites()).isEqualTo(1)
        assertThat(r.wire.disconnects).isEqualTo(1)
        r.handler.handleDisconnected() // a finished session disconnects without an error
        assertThat(r.callbacks.errors).isEmpty()
    }

    @Test
    fun `the same weigh-in record in two consecutive sessions is handled once`() {
        val r = rig(two)
        r.driveAll("frames-weigh-in")
        r.handler.handleDisconnected()
        assertThat(r.callbacks.published).hasSize(1)
        r.driveAll("frames-weigh-in")
        r.handler.handleDisconnected()
        assertThat(r.callbacks.published).hasSize(1)
        assertThat(r.callbacks.infos).containsExactly(R.string.bt_info_hn300t2_no_new_measurements)
        assertThat(r.callbacks.errors).isEmpty()
        assertThat(r.endWrites()).isEqualTo(2)
    }

    @Test
    fun `a full-length frame with a corrupted BCC is dropped and logged and the watchdog still fires`() {
        val r = rig(two)
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        r.connectAndEcho(trace)
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, replies(trace)[0])
        val writesBefore = r.commandWrites().size
        val good = replies(trace)[1]
        val corrupt = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        r.scope.advanceTimeBy(timeout - 1000)
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, corrupt)
        assertThat(r.commandWrites()).hasSize(writesBefore)
        assertThat(r.callbacks.errors).isEmpty()
        assertThat(ShadowLog.getLogs().any { "dropped frame" in it.msg }).isTrue()
        // the dropped frame did not re-arm the watchdog
        r.scope.advanceTimeBy(1001)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_timeout)
        assertThat(r.wire.disconnects).isEqualTo(1)
    }

    @Test
    fun `a link drop mid-session reports the transfer error`() {
        val r = rig(two)
        val trace = Hn300t2Fixture.load("frames-weigh-in")
        r.connectAndEcho(trace)
        r.handler.handleNotification(OmronHn300t2Handler.CHR_NOTIFY, replies(trace)[0])
        r.handler.handleDisconnected()
        assertThat(r.callbacks.errors).containsExactly(R.string.bt_error_hn300t2_transfer)
        r.scope.advanceTimeBy(timeout * 2)
        r.scope.runCurrent()
        assertThat(r.callbacks.errors).hasSize(1) // the watchdog was cancelled
    }

    @Test
    fun `support declares history read only with the Balanced tuning profile`() {
        val support = OmronHn300t2Handler().supportFor(
            ScannedDeviceInfo(
                name = "BLEsmart_${OmronHn300t2Handler.ADVERTISED_ID}0080E1A2B3C4",
                address = "C0:FF:EE:12:34:56",
                rssi = -50,
                serviceUuids = listOf(OmronHn300t2Handler.SVC_OMRON),
                manufacturerData = null,
            )
        )!!
        assertThat(support.capabilities).containsExactly(DeviceCapability.HISTORY_READ)
        assertThat(support.tuningProfile).isEqualTo(TuningProfile.Balanced)
    }
}
