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
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Ground-truth regression tests for [QNHandler]'s Type-2 (0xFFF0) protocol handling, verified
 * against the decompiled Qing Niu vendor SDK (QNDecoderImpl.decodeData / CmdBuilder) and a real
 * BTSnoop capture of the official "Fit Profile" app talking to a GE CS 10 G "Fit Plus"
 * (2026-08-31, device address anonymized in the frames below).
 *
 * Four behaviors are verified against that capture, but only hold for the *captured* family —
 * which always reports protocolType 0xFF (see [QNHandler.isCapturedUniversalVariant]) — and are
 * gated accordingly so they can't change behavior for the many other QN/Yolanda/Renpho devices
 * this handler already served before this capture existed:
 *
 *  - The 0x13 config command carries the real user height/age/gender
 *    (CmdBuilder.buildCmd(0x13, scaleType, [unit, lightInterval, height, age, gender])) instead
 *    of zeros.
 *  - Every stable 0x10 weight frame is acknowledged with
 *    [0x1F, 0x05, protocolType, 0x10, checksum] (CmdBuilder.buildOverCmd).
 *  - In the ES-30M byte layout, only state==2 (not state==1) is treated as stable — state==1
 *    always carried R1=R2=0 in the capture.
 *  - r1/r2 are stored/used as tenths of an ohm (divided by 10) for impedance, since raw values
 *    landed ~10x above the ~300-1000 Ohm range TrisaBodyAnalyzeLib's formula is documented
 *    against, and the vendor SDK's own two-byte combine (MeasureDecoder.h) applies no scaling of
 *    its own — see `publishQnMeasurement`'s comment for the caveat that this is inferred, not
 *    confirmed against the vendor's own (unavailable) body-composition algorithm.
 *
 * Each gated test has a companion proving the *other* protocolTypes keep their original,
 * longer-serving behavior — including [classic factor-100 frame with weight low byte 0x00 is not
 * misclassified as ES-30M], which is a direct regression test for a real bug: an earlier version
 * of this fix used a length-byte-based "long frame" heuristic that turned out to be true for
 * essentially every well-formed frame (byte[1] is the frame's own length in this protocol), which
 * would have silently corrupted roughly 1 in 85 weigh-ins on every classic QN/Yolanda/Renpho
 * device by misreading the weight's low byte as an ES-30M state/impedance field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerProtocolTest {

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private val SVC_T2 = uuid16(0xFFF0)
    private val CHR_T2_NOTIFY = uuid16(0xFFF1)
    private val CHR_T2_WRITE = uuid16(0xFFF2)

    private fun checksum(buf: ByteArray, from: Int, toInclusive: Int): Byte {
        var s = 0
        for (i in from..toInclusive) s = (s + (buf[i].toInt() and 0xFF)) and 0xFF
        return s.toByte()
    }

    /** MM birthday chosen so `age` comes out to a known, mid-range value. */
    private fun testUser(): ScaleUser = ScaleUser(
        id = 1,
        birthday = Date(0), // 1970-01-01: comfortably inside the vendor's [6,80] clamp range
        bodyHeight = 181f,
        gender = GenderType.MALE,
    )

    private fun newHandler(
        user: ScaleUser,
        available: Set<Pair<UUID, UUID>> = setOf(SVC_T2 to CHR_T2_NOTIFY, SVC_T2 to CHR_T2_WRITE),
    ): Triple<QNHandler, CapturingTransport, CapturingCallbacks> {
        val transport = CapturingTransport(available)
        val callbacks = CapturingCallbacks()
        val handler = QNHandler()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        handler.handleConnected(user)
        return Triple(handler, transport, callbacks)
    }

    /** Classic (short) 0x12 scale-info frame: [op, len, protocolType, ..., byte10=weightScaleFlag]. */
    private fun classicScaleInfoFrame(protocolType: Byte, weightScaleFlag: Byte): ByteArray {
        val f = ByteArray(15)
        f[0] = 0x12
        f[1] = 15
        f[2] = protocolType
        f[10] = weightScaleFlag
        return f
    }

    @Test
    fun `0x13 config command carries real height, age and gender for the captured protocolType 0xFF family`() {
        val user = testUser()
        val (handler, transport, _) = newHandler(user)

        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0xFF.toByte(), weightScaleFlag = 1))

        val cfg = transport.writes.map { it.payload }.firstOrNull { it.size == 9 && it[0] == 0x13.toByte() }
        assertThat(cfg).isNotNull()
        cfg!!

        val expectedChecksum = checksum(cfg, 0, cfg.lastIndex - 1)
        assertThat(cfg[8]).isEqualTo(expectedChecksum)
        assertThat(cfg[2]).isEqualTo(0xFF.toByte()) // echoed protocol type
        assertThat(cfg[4]).isEqualTo(0x10.toByte()) // lightInterval default
        assertThat(cfg[5]).isEqualTo(181.toByte()) // height (unsigned 181 == signed -75 in two's complement)
        assertThat(cfg[6]).isEqualTo(user.age.toByte())
        assertThat(cfg[7]).isEqualTo(0x00.toByte()) // male == 0 per vendor wire encoding
    }

    @Test
    fun `0x13 config command still sends zeroed height, age and gender for a classic protocolType`() {
        // Regression guard: only the captured protocolType 0xFF family is verified to want real
        // profile bytes here. Every other QN device keeps the pre-existing (zeroed) behavior.
        val user = testUser()
        val (handler, transport, _) = newHandler(user)

        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0x07, weightScaleFlag = 1))

        val cfg = transport.writes.map { it.payload }.firstOrNull { it.size == 9 && it[0] == 0x13.toByte() }
        assertThat(cfg).isNotNull()
        cfg!!
        assertThat(cfg[5]).isEqualTo(0x00.toByte()) // height
        assertThat(cfg[6]).isEqualTo(0x00.toByte()) // age
        assertThat(cfg[7]).isEqualTo(0x00.toByte()) // gender
    }

    @Test
    fun `stable weight frame is acknowledged with 0x1F for the captured protocolType 0xFF family`() {
        val user = testUser()
        val (handler, transport, callbacks) = newHandler(user)

        // Original 0x10 format: [op, len, protoType, weightHi, weightLo, stable, r1Hi, r1Lo, r2Hi, r2Lo]
        // weightScaleFactor defaults to 100 -> raw 7000 == 70.00kg.
        val weightFrame = byteArrayOf(
            0x10, 0x0A, 0xFF.toByte(),
            ((7000 shr 8) and 0xFF).toByte(), (7000 and 0xFF).toByte(),
            0x01, // stable
            0x01, 0xF4.toByte(), // r1 = 500
            0x01, 0xF4.toByte(), // r2 = 500
        )
        handler.handleNotification(CHR_T2_NOTIFY, weightFrame)

        val expectedAck = byteArrayOf(0x1F, 0x05, 0xFF.toByte(), 0x10, 0x00)
        expectedAck[4] = checksum(expectedAck, 0, 3)
        // ByteArray has no structural equals, so compare via List<Byte> (or contentEquals)
        // rather than raw contains(), which would only match by reference identity.
        assertThat(transport.writes.map { it.payload.toList() }).contains(expectedAck.toList())

        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single()[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(70.0f)
    }

    @Test
    fun `stable weight frame is NOT acknowledged with 0x1F for a classic protocolType, but still publishes`() {
        // Regression guard: only the captured protocolType 0xFF family is verified to want this
        // ack. Every other QN device keeps the pre-existing (no-ack) behavior and must still
        // publish the weight regardless.
        val user = testUser()
        val (handler, transport, callbacks) = newHandler(user)

        val weightFrame = byteArrayOf(
            0x10, 0x0A, 0x07,
            ((7000 shr 8) and 0xFF).toByte(), (7000 and 0xFF).toByte(),
            0x01, // stable
            0x01, 0xF4.toByte(),
            0x01, 0xF4.toByte(),
        )
        handler.handleNotification(CHR_T2_NOTIFY, weightFrame)

        assertThat(transport.writes.map { it.payload[0] }).doesNotContain(0x1F.toByte())
        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single()[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(70.0f)
    }

    @Test
    fun `classic factor-100 frame with weight low byte 0x00 is not misclassified as ES-30M`() {
        // Direct regression test for the bug caught in PR review: a "long frame" heuristic based
        // on byte[1] == frame length is true for every well-formed frame in this protocol (that's
        // just how the length field works), so it can't distinguish anything. Using it as an
        // additional OR-condition alongside weightScaleFactor==10 effectively removed that guard,
        // which would misparse any classic-format weight whose low byte happens to be <= 2 (about
        // 1 in 85 real weigh-ins) as an ES-30M frame instead: e.g. 71.68kg (raw 7168 = 0x1C00) has
        // low byte 0x00, matching the ES-30M "state" field's valid range.
        val user = testUser()
        val (handler, _, callbacks) = newHandler(user)

        // Classic (non-Fit-Plus) device: protocolType 0x07, weightScaleFactor 100 (byte10 == 1).
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0x07, weightScaleFlag = 1))

        // Original format frame for 71.68kg: weightHi=0x1C, weightLo=0x00 (the low byte that would
        // wrongly look like an ES-30M "measuring" state if the format detection were broken).
        val weightFrame = byteArrayOf(
            0x10, 0x0A, 0x07,
            0x1C, 0x00, // weight = 7168 raw -> 71.68 kg
            0x01, // stable
            0x01, 0xF4.toByte(), // r1 = 500
            0x01, 0xF4.toByte(), // r2 = 500
        )
        handler.handleNotification(CHR_T2_NOTIFY, weightFrame)

        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single()[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(71.68f)
    }

    @Test
    fun `classic ES-30M device (protocolType other than 0xFF) still treats state 1 as stable`() {
        // The state==2-only tightening is scoped to the captured protocolType 0xFF family;
        // every other ES-30M-layout device keeps the original (state 1 OR 2) behavior.
        val user = testUser()
        val (handler, _, callbacks) = newHandler(user)

        // weightScaleFactor 10 (byte10 != 1) selects the ES-30M layout for a classic protocolType.
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0x07, weightScaleFlag = 0))

        val state1Frame = byteArrayOf(
            0x10, 0x0E, 0x07,
            0xFE.toByte(), 0x01, // [3]=filler, [4]=state 1
            0x21, 0x57, // weight
            0x00, 0x00, 0x00, 0x00, // r1 = r2 = 0
            0x00, 0x00, 0x00,
        )
        state1Frame[state1Frame.lastIndex] = checksum(state1Frame, 0, state1Frame.lastIndex - 1)
        handler.handleNotification(CHR_T2_NOTIFY, state1Frame)

        assertThat(callbacks.published).hasSize(1)
    }

    @Test
    fun `real Fit Plus advertisement (FFE0 name-only, no AE00 or FFF0) is claimed`() {
        // Captured pre-connect advertisement: 16-bit service UUIDs = [FFE0] only (address
        // anonymized). AE00/FFF0 are real GATT services but only appear post-connection, so
        // the AE00 relaxation in supportFor() never sees them here — the name match is load-bearing.
        val device = ScannedDeviceInfo(
            name = "Fit Plus",
            address = "00:11:22:33:44:55",
            rssi = -60,
            serviceUuids = listOf(uuid16(0xFFE0)),
            manufacturerData = null,
        )
        assertThat(QNHandler().supportFor(device)).isNotNull()
    }

    @Test
    fun `real hardware frames - state 1 is not published, state 2 publishes correct weight and impedance`() {
        // Byte-for-byte from a BTSnoop capture of the official app driving a real
        // GE CS 10 G "Fit Plus" (protocolType 0xFF, weightScaleFactor 100, long-frame 0x12),
        // except bytes[3..8] (the device's BD_ADDR, embedded in this frame per the vendor
        // protocol) are replaced with a placeholder and the checksum recomputed accordingly.
        val scaleInfoFrame = byteArrayOf(
            0x12, 0x12, 0xFF.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            0x13, 0x07, 0x10, 0x00, 0x01, 0x05, 0x9F.toByte(), 0x32, 0x89.toByte(),
        )
        val state0 = byteArrayOf(
            0x10, 0x0F, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x21, 0x57,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x94.toByte(),
        )
        val state1 = byteArrayOf(
            0x10, 0x0F, 0xFF.toByte(), 0xFE.toByte(), 0x01, 0x21, 0x57,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x95.toByte(),
        )
        val state2 = byteArrayOf(
            0x10, 0x0F, 0xFF.toByte(), 0xFE.toByte(), 0x02, 0x21, 0x57,
            0x10, 0xFE.toByte(), 0x10, 0xFC.toByte(), 0x00, 0xAC.toByte(), 0x00, 0x5C,
        )

        val user = testUser()
        val (handler, transport, callbacks) = newHandler(user)

        handler.handleNotification(CHR_T2_NOTIFY, scaleInfoFrame)
        handler.handleNotification(CHR_T2_NOTIFY, state0)
        assertThat(callbacks.published).isEmpty()

        handler.handleNotification(CHR_T2_NOTIFY, state1)
        assertThat(callbacks.published).isEmpty() // state 1 has R1=R2=0 -> must not publish yet

        handler.handleNotification(CHR_T2_NOTIFY, state2)
        assertThat(callbacks.published).hasSize(1)
        val published = callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(85.35f)
        // Raw r1 = 4350; the captured protocolType 0xFF family reports resistance in tenths of an
        // ohm (see the class doc comment), so the stored/derived impedance is 435.0, not 4350.0.
        assertThat(published[MeasurementType.IMPEDANCE]?.value).isWithin(0.01f).of(435.0f)

        val expectedAck = byteArrayOf(0x1F, 0x05, 0xFF.toByte(), 0x10, 0x00)
        expectedAck[4] = checksum(expectedAck, 0, 3)
        assertThat(transport.writes.map { it.payload.toList() }).contains(expectedAck.toList())
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement
        }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private data class WriteRecord(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean
    )

    private class CapturingTransport(
        private val available: Set<Pair<UUID, UUID>>
    ) : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<WriteRecord>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += WriteRecord(service, characteristic, payload, withResponse)
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
            available.contains(service to characteristic)
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val values = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }
        override fun getString(key: String, default: String?): String? = values[key] ?: default
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FixedDataProvider(
        private val user: ScaleUser,
        private val users: List<ScaleUser> = listOf(user)
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = users
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
