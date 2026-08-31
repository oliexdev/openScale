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
 * against the decompiled Qing Niu vendor SDK (QNDecoderImpl.decodeData / CmdBuilder):
 *
 *  - The 0x13 config command must carry the real user height/age/gender
 *    (CmdBuilder.buildCmd(0x13, scaleType, [unit, lightInterval, height, age, gender])),
 *    not zeros — QNDecoderImpl.setScaleConfig always fills these from the app's active user.
 *  - Every stable 0x10 weight frame must be acknowledged with
 *    [0x1F, 0x05, protocolType, 0x10, checksum] (CmdBuilder.buildOverCmd), which the vendor
 *    app sends unconditionally for a "state == stable" frame.
 *
 * [realHardwareFrames] additionally locks in behavior against an actual BTSnoop capture of the
 * official "Fit Profile" app talking to a real GE CS 10 G "Fit Plus" (2026-08-31): the frames
 * there are copied byte-for-byte from that capture, not synthesized, and cover a bug the
 * capture uncovered — this device reports weightScaleFactor=100 (not 10) yet still uses the
 * ES-30M-style 0x10 layout ([4]=state, [5,6]=weight), and state==1 frames always carry
 * R1=R2=0 (only state==2 has real impedance).
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

    @Test
    fun `0x13 config command carries real height, age and gender instead of zeros`() {
        val user = testUser()
        val transport = CapturingTransport(available = setOf(SVC_T2 to CHR_T2_NOTIFY, SVC_T2 to CHR_T2_WRITE))
        val handler = QNHandler()
        handler.attach(
            transport = transport,
            callbacks = CapturingCallbacks(),
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        handler.handleConnected(user)

        // 0x12 scale-info frame: [opcode, len, protocolType, ..., byte10=weightScaleFlag]
        val scaleInfoFrame = ByteArray(15)
        scaleInfoFrame[0] = 0x12
        scaleInfoFrame[1] = 15
        scaleInfoFrame[2] = 0x07 // arbitrary protocol type, echoed back
        scaleInfoFrame[10] = 1 // /100 weight factor
        handler.handleNotification(CHR_T2_NOTIFY, scaleInfoFrame)

        val cfg = transport.writes.map { it.payload }.firstOrNull { it.size == 9 && it[0] == 0x13.toByte() }
        assertThat(cfg).isNotNull()
        cfg!!

        val expectedChecksum = checksum(cfg, 0, cfg.lastIndex - 1)
        assertThat(cfg[8]).isEqualTo(expectedChecksum)
        assertThat(cfg[2]).isEqualTo(0x07.toByte()) // echoed protocol type
        assertThat(cfg[4]).isEqualTo(0x10.toByte()) // lightInterval default
        assertThat(cfg[5]).isEqualTo(181.toByte()) // height (unsigned 181 == signed -75 in two's complement)
        assertThat(cfg[6]).isEqualTo(user.age.toByte())
        assertThat(cfg[7]).isEqualTo(0x00.toByte()) // male == 0 per vendor wire encoding
    }

    @Test
    fun `stable weight frame is acknowledged with 0x1F and publishes a measurement`() {
        val user = testUser()
        val transport = CapturingTransport(available = setOf(SVC_T2 to CHR_T2_NOTIFY, SVC_T2 to CHR_T2_WRITE))
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

        // Original 0x10 format: [op, len, protoType, weightHi, weightLo, stable, r1Hi, r1Lo, r2Hi, r2Lo]
        // weightScaleFactor defaults to 100 -> raw 7000 == 70.00kg.
        val weightFrame = byteArrayOf(
            0x10, 0x0A, 0x07,
            ((7000 shr 8) and 0xFF).toByte(), (7000 and 0xFF).toByte(),
            0x01, // stable
            0x01, 0xF4.toByte(), // r1 = 500
            0x01, 0xF4.toByte(), // r2 = 500
        )
        handler.handleNotification(CHR_T2_NOTIFY, weightFrame)

        val expectedAck = byteArrayOf(0x1F, 0x05, 0x07, 0x10, 0x00)
        expectedAck[4] = checksum(expectedAck, 0, 3)
        // ByteArray has no structural equals, so compare via List<Byte> (or contentEquals)
        // rather than raw contains(), which would only match by reference identity.
        assertThat(transport.writes.map { it.payload.toList() }).contains(expectedAck.toList())

        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single().weight).isWithin(0.01f).of(70.0f)
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
        val transport = CapturingTransport(available = setOf(SVC_T2 to CHR_T2_NOTIFY, SVC_T2 to CHR_T2_WRITE))
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

        handler.handleNotification(CHR_T2_NOTIFY, scaleInfoFrame)
        handler.handleNotification(CHR_T2_NOTIFY, state0)
        assertThat(callbacks.published).isEmpty()

        handler.handleNotification(CHR_T2_NOTIFY, state1)
        assertThat(callbacks.published).isEmpty() // state 1 has R1=R2=0 -> must not publish yet

        handler.handleNotification(CHR_T2_NOTIFY, state2)
        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single().weight).isWithin(0.01f).of(85.35f)
        assertThat(callbacks.published.single().impedance).isWithin(0.01).of(4350.0)
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
