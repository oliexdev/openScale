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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.libs.Hn300t2Session.Action
import java.util.Calendar
import java.util.Date
import org.junit.Test

class OmronHn300t2SessionTest {

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun localDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Date =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.time

    private fun parseType(b: ByteArray): Int = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)

    /** Builds a valid READ response frame carrying [data] for [address]. */
    private fun readResponse(address: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(
            (data.size + 8).toByte(), 0x81.toByte(), 0x00,
            (address shr 8).toByte(), address.toByte(), data.size.toByte()
        ) + data + byteArrayOf(0x00)
        return body + OmronWlpFrame.bcc(body)
    }

    private fun show(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

    private class Replay(val writes: List<ByteArray>, val actions: List<Action>)

    private fun replay(name: String): Replay {
        val t = Hn300t2Fixture.load(name)
        val s = Hn300t2Session(token = t.handshakeWrite.copyOfRange(1, 5))
        val out = mutableListOf<ByteArray>()
        val all = mutableListOf<Action>()
        lateinit var take: (List<Action>) -> Unit
        take = { actions -> actions.forEach {
            all += it
            when (it) {
                is Action.WriteHandshake -> out += it.bytes
                is Action.WriteCommand -> out += it.bytes
                is Action.Publish -> take(s.finish())
                else -> Unit
            }
        } }
        take(s.start())
        take(s.onHandshakeNotify(t.handshakeNotify))
        t.wldNotifies.filterNot { parseType(it) == OmronWlpFrame.TYPE_WRITE }.forEach { take(s.onNotify(it)) }
        return Replay(out, all)
    }

    @Test
    fun `read-only replay of the weigh-in emits the handshake and six WLD frames in trace order`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val expected = listOf(t.handshakeWrite) + t.wldWrites.filterNot { it[2] == 0xC0.toByte() }
        assertThat(replay("frames-weigh-in").writes.map(::show)).containsExactlyElementsIn(expected.map(::show)).inOrder()
    }

    @Test
    fun `read-only replay of the pairing trace reads no record`() {
        val t = Hn300t2Fixture.load("frames-pairing")
        val expected = listOf(t.handshakeWrite) + t.wldWrites.filterNot { it[2] == 0xC0.toByte() }
        val r = replay("frames-pairing")
        assertThat(r.writes.map(::show)).containsExactlyElementsIn(expected.map(::show)).inOrder()
        assertThat(r.actions).contains(Action.NoNewRecord)
        assertThat(r.actions.last()).isEqualTo(Action.Done)
    }

    @Test
    fun `a read-only session never emits a commit write`() {
        for (name in listOf("frames-weigh-in", "frames-pairing")) {
            replay(name).writes.drop(1).forEach { assertThat(it[2]).isNotEqualTo(0xC0.toByte()) }
        }
    }

    @Test
    fun `weigh-in replay publishes the record from slot 0 with the synthetic weight`() {
        val published = replay("frames-weigh-in").actions.filterIsInstance<Action.Publish>()
        assertThat(published).hasSize(1)
        assertThat(published[0].slot).isEqualTo(0)
        assertThat(published[0].record.weightRaw).isEqualTo(1600)
        assertThat(published[0].record.timestamp).isEqualTo(localDate(2026, 11, 4, 3, 17, 34))
        assertThat(replay("frames-weigh-in").actions.last()).isEqualTo(Action.Done)
    }

    @Test
    fun `handshake echo mismatch fails before any WLD write`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start()
        assertThat(s.onHandshakeNotify(hex("91 00 00 00 00 00")))
            .containsExactly(Action.Fail("handshake echo mismatch"))
    }

    @Test
    fun `an unexpected response type fails the session`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        val end = t.wldNotifies.first { parseType(it) == OmronWlpFrame.TYPE_END }
        assertThat(s.onNotify(end)).containsExactly(Action.Fail("unexpected response"))
    }

    @Test
    fun `a truncated frame is ignored and the phase does not change`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        val before = s.phase
        assertThat(s.onNotify(hex("18 81 00 01 a0"))).isEmpty()
        assertThat(s.phase).isEqualTo(before)
    }

    @Test
    fun `every fixture notify parses with a valid checksum`() {
        for (name in listOf("frames-weigh-in", "frames-pairing")) {
            Hn300t2Fixture.load(name).wldNotifies.forEach {
                assertThat(OmronWlpFrame.parseResponse(it)).isNotNull()
            }
        }
    }

    @Test
    fun `unsent record with a zero pointer ends with NoNewRecord and reads no record`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        val n = t.wldNotifies
        s.onNotify(n[0])
        s.onNotify(readResponse(0x01A0, hex("00 00 00 00 01 00 00 00 00 00 00 00")))
        s.onNotify(n[2])
        val actions = s.onNotify(n[3])
        assertThat(actions).hasSize(2)
        assertThat(actions[0]).isEqualTo(Action.NoNewRecord)
        assertThat((actions[1] as Action.WriteCommand).bytes).isEqualTo(OmronWlpFrame.endTransmission())
    }

    @Test
    fun `finish is a no-op outside AWAITING_FINISH`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        assertThat(s.finish()).isEmpty()
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        assertThat(s.finish()).isEmpty()
        val notifies = t.wldNotifies.filterNot { parseType(it) == OmronWlpFrame.TYPE_WRITE }
        val actions = notifies.dropLast(1).flatMap { s.onNotify(it) }
        assertThat(actions.last()).isInstanceOf(Action.Publish::class.java)
        assertThat(s.phase).isEqualTo("AWAITING_FINISH")
        assertThat(s.finish()).hasSize(1)
        assertThat(s.phase).isEqualTo("ENDING")
        assertThat(s.finish()).isEmpty()
    }

    @Test
    fun `recordAddress rejects a negative index and an out-of-range slot`() {
        assertThat(OmronHn300t2Lib.recordAddress(0, -1)).isNull()
        assertThat(OmronHn300t2Lib.recordAddress(4, 0)).isNull()
    }

    private fun finished(): Triple<Hn300t2Session, Hn300t2Fixture.Trace, List<Action>> {
        val t = Hn300t2Fixture.load("frames-pairing")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        val last = t.wldNotifies.filterNot { parseType(it) == OmronWlpFrame.TYPE_WRITE }
            .flatMap { s.onNotify(it) }
        return Triple(s, t, last)
    }

    @Test
    fun `a notify after Done produces no actions`() {
        val (s, t, actions) = finished()
        assertThat(actions.last()).isEqualTo(Action.Done)
        val phase = s.phase
        assertThat(s.onNotify(t.wldNotifies.last())).isEmpty()
        assertThat(s.onHandshakeNotify(t.handshakeNotify)).isEmpty()
        assertThat(s.phase).isEqualTo(phase)
    }

    @Test
    fun `a notify after Fail produces no actions`() {
        val t = Hn300t2Fixture.load("frames-pairing")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start()
        assertThat(s.onHandshakeNotify(hex("91 00 00 00 00 00"))).hasSize(1)
        assertThat(s.onNotify(t.wldNotifies[0])).isEmpty()
        assertThat(s.onHandshakeNotify(t.handshakeNotify)).isEmpty()
        assertThat(s.phase).isEqualTo("FAILED")
    }

    @Test
    fun `a non-zero result byte fails the session`() {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        s.onNotify(t.wldNotifies[0])
        val ok = readResponse(0x01A0, ByteArray(12))
        val body = ok.copyOf(ok.size - 1).also { it[it.size - 1] = 0x01 }
        val bad = body + OmronWlpFrame.bcc(body)
        assertThat(s.onNotify(bad)).containsExactly(Action.Fail("non-zero result 0x01"))
    }

    private fun afterOpener(): Pair<Hn300t2Session, Hn300t2Fixture.Trace> {
        val t = Hn300t2Fixture.load("frames-weigh-in")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        s.onNotify(t.wldNotifies[0])
        return s to t
    }

    @Test
    fun `an undecodable record timestamp warns and ends with NoNewRecord`() {
        val (s, t) = afterOpener()
        val n = t.wldNotifies
        s.onNotify(n[1]); s.onNotify(n[2]); s.onNotify(n[3])
        assertThat(s.phase).isEqualTo("READ_RECORD")
        val actions = s.onNotify(readResponse(0x03E0, ByteArray(16) { 0xFF.toByte() }))
        assertThat(actions).hasSize(3)
        assertThat(actions[0]).isEqualTo(Action.Warn("record timestamp invalid"))
        assertThat(actions[1]).isEqualTo(Action.NoNewRecord)
        assertThat((actions[2] as Action.WriteCommand).bytes).isEqualTo(OmronWlpFrame.endTransmission())
    }

    @Test
    fun `unsent counts that a read-only session skips are reported`() {
        for (block in listOf("13 00 00 00 02 00 00 00 00 13 00 00", "13 00 00 00 01 01 00 00 00 13 00 00")) {
            val (s, _) = afterOpener()
            val actions = s.onNotify(readResponse(0x01A0, hex(block)))
            assertThat(actions[0]).isInstanceOf(Action.Warn::class.java)
            assertThat((actions[0] as Action.Warn).message).startsWith("unsent counts ")
            assertThat(actions.last()).isInstanceOf(Action.WriteCommand::class.java)
        }
    }

    @Test
    fun `the fixtures report no skipped unsent counts`() {
        for (name in listOf("frames-weigh-in", "frames-pairing")) {
            assertThat(replay(name).actions.filterIsInstance<Action.Warn>()).isEmpty()
        }
    }

    @Test
    fun `isTerminal is true only after Done or Fail`() {
        val t = Hn300t2Fixture.load("frames-pairing")
        val s = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        assertThat(s.isTerminal).isFalse()
        s.start(); s.onHandshakeNotify(t.handshakeNotify)
        assertThat(s.isTerminal).isFalse()
        assertThat(finished().first.isTerminal).isTrue()
        val f = Hn300t2Session(t.handshakeWrite.copyOfRange(1, 5))
        f.start(); f.onHandshakeNotify(hex("91 00 00 00 00 00"))
        assertThat(f.isTerminal).isTrue()
    }
}
