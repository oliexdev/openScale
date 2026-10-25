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

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import org.robolectric.shadows.ShadowBluetoothDevice
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ActivityLevel
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
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16

/**
 * QN protocol regressions using anonymized GE "Fit Plus" and RENPHO ES-CS20M frames.
 * VA/0xFF devices must finish BIA before publishing, receive the 0x1F completion ACK, and
 * enable BIA in the 0x13 configuration. Resistance is whole ohms after decoding the optional
 * bit permutation advertised in manufacturer payload byte 15, bit 2; 0xFF alone cannot select
 * a decimal scale factor. Classic QN configuration, frame selection and resistance calibration
 * retain their previous behavior. Missing resistance must never generate composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QNHandlerProtocolTest {


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
        scanResult: ScanResult? = null,
    ): Triple<QNHandler, CapturingTransport, CapturingCallbacks> {
        val transport = CapturingTransport(available)
        val callbacks = CapturingCallbacks()
        val handler = QNHandler()
        scanResult?.let(handler::onGattAdvertisement)
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

    @Suppress("DEPRECATION")
    private fun advertisement(payload: ByteArray?, name: String? = null): ScanResult {
        val manufacturer = if (payload == null) byteArrayOf() else
            byteArrayOf((payload.size + 3).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + payload
        val localName = name?.toByteArray(Charsets.UTF_8)?.let { byteArrayOf((it.size + 1).toByte(), 0x09) + it } ?: byteArrayOf()
        val bytes = localName + manufacturer + byteArrayOf(0)
        val parse = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        parse.isAccessible = true
        val record = parse.invoke(null, bytes) as ScanRecord
        return ScanResult(ShadowBluetoothDevice.newInstance("00:11:22:33:44:55"), record, -50, 0L)
    }

    private fun completedVaFrame(r1High: Byte, r1Low: Byte, protocolType: Byte = 0xFF.toByte()): ByteArray {
        // ES-CS20M layout with synthetic 70 kg weight and resistance, no personal data.
        val frame = byteArrayOf(0x10, 0x0E, protocolType, 0x01, 0x02, 0x1B, 0x58,
            r1High, r1Low, r1High, r1Low, 0x00, 0x00, 0x00)
        frame[frame.lastIndex] = checksum(frame, 0, frame.lastIndex - 1)
        return frame
    }

    private fun renphoHandler(user: ScaleUser = testUser()) =
        newHandler(user, scanResult = advertisement(null, "Renpho-Scale"))

    private fun fatFrame(resistance: Int = 500, fatTenths: Int = 200): ByteArray =
        completedVaFrame((resistance shr 8).toByte(), resistance.toByte()).also {
            it[11] = (fatTenths shr 8).toByte()
            it[12] = fatTenths.toByte()
            it[it.lastIndex] = checksum(it, 0, it.lastIndex - 1)
        }

    @Test
    fun `Renpho VA profile uses real user data in guest mode without deleting a user`() {
        val user = testUser().copy(bodyHeight = 183.9f, gender = GenderType.FEMALE)
        val (handler, transport, _) = renphoHandler(user)
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        handler.handleNotification(CHR_T2_NOTIFY, byteArrayOf(0x21, 0x05, 0xFF.toByte(), 0x00, 0x25))
        val profiles = transport.writes.map { it.payload }.filter { it[0] == 0xA0.toByte() }
        assertThat(profiles).hasSize(1)
        val profile = profiles.single()
        assertThat(profile.take(7)).containsExactlyElementsIn(
            listOf(0xA0, 0x0D, 0x02, 0xFE, 0xFF, 0xEE, 0x01).map { it.toByte() }).inOrder()
        assertThat(profile[7]).isEqualTo(user.age.toByte())
        assertThat(profile[8]).isEqualTo(0x07.toByte()) // 1830 mm, big-endian
        assertThat(profile[9]).isEqualTo(0x26.toByte())
        assertThat(profile[10]).isEqualTo(0x04.toByte())
        assertThat(profile.last()).isEqualTo(checksum(profile, 0, profile.lastIndex - 1))
    }

    @Test
    fun `Renpho extreme activity requests the firmware athlete algorithm`() {
        val (handler, transport, _) = renphoHandler(testUser().copy(activityLevel = ActivityLevel.EXTREME))
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        handler.handleNotification(CHR_T2_NOTIFY, byteArrayOf(0x21, 0x05, 0xFF.toByte(), 0x00, 0x25))
        assertThat(transport.writes.single { it.payload[0] == 0xA0.toByte() }.payload[10]).isEqualTo(0x0E.toByte())
    }

    @Test
    fun `Renpho VA keeps firmware fat and derives the Renpho composition panel`() {
        val (handler, _, callbacks) = renphoHandler()
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        handler.handleNotification(CHR_T2_NOTIFY, fatFrame())
        val m = callbacks.published.single()
        assertThat(m[MeasurementType.WEIGHT]?.value).isEqualTo(70f)
        assertThat(m[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
        assertThat(m[MeasurementType.BODY_FAT]?.value).isEqualTo(20f)
        assertThat(m[MeasurementType.WATER]?.value).isWithin(0.01f).of(57.7574f)
        assertThat(m[MeasurementType.MUSCLE]?.value).isWithin(0.01f).of(51.6114f)
        assertThat(m[MeasurementType.BONE]?.value).isWithin(0.01f).of(2.8013f)
        assertThat(m[MeasurementType.BMR]?.value).isEqualTo(1580f)
        assertThat(m[MeasurementType.LBM]?.value).isEqualTo(56f)
        assertThat(m[MeasurementType.PROTEIN]?.value).isWithin(0.01f).of(18.24f)
        assertThat(m[QNHandler.TOTAL_MUSCLE]?.value).isWithin(0.01f).of(75.9982f)
    }

    @Test
    fun `Renpho missing or invalid firmware fat is not silently replaced with Trisa`() {
        for (fat in listOf(0, 1001, 65535)) {
            val (handler, _, callbacks) = renphoHandler()
            handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
            handler.handleNotification(CHR_T2_NOTIFY, fatFrame(fatTenths = fat))
            val m = callbacks.published.single()
            assertThat(m[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
            assertThat(m[MeasurementType.BODY_FAT]).isNull()
            assertThat(m[MeasurementType.WATER]).isNull()
        }
    }

    @Test
    fun `Renpho short packet cannot treat a checksum byte as firmware fat`() {
        val (handler, _, callbacks) = renphoHandler()
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        val short = fatFrame().copyOf(13)
        short[12] = checksum(short, 0, 11)
        handler.handleNotification(CHR_T2_NOTIFY, short)
        assertThat(callbacks.published.single()[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
        assertThat(callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `Renpho zero resistance still produces weight only even if a fat field is present`() {
        val (handler, _, callbacks) = renphoHandler()
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        handler.handleNotification(CHR_T2_NOTIFY, fatFrame(resistance = 0))
        assertThat(callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
        assertThat(callbacks.published.single()[MeasurementType.IMPEDANCE]).isNull()
    }

    @Test
    fun `firmware composition change is restricted to the Renpho name and VA protocol`() {
        for ((name, proto) in listOf("Fit Plus" to 0xFF.toByte(), "Renpho-Scale" to 0x07.toByte())) {
            val user = testUser()
            val (handler, _, callbacks) = newHandler(user, scanResult = advertisement(null, name))
            handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(proto, 0))
            handler.handleNotification(CHR_T2_NOTIFY, fatFrame().also {
                it[2] = proto; it[it.lastIndex] = checksum(it, 0, it.lastIndex - 1)
            })
            assertThat(callbacks.published.single()[MeasurementType.BODY_FAT]?.value).isNotEqualTo(20f)
            assertThat(callbacks.published.single()[QNHandler.TOTAL_MUSCLE]).isNull()
        }
    }

    @Test
    fun `Renpho extended stored record uses big endian resistance and firmware fat`() {
        val (handler, _, callbacks) = renphoHandler()
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        val frame = byteArrayOf(0x23, 0x13, 0xFF.toByte(), 1, 1, 0xFE.toByte(),
            0, 0, 0, 0, 0x1B, 0x58, 0x01, 0xF4.toByte(), 0x01, 0xF2.toByte(), 0, 0xC8.toByte(), 0)
        val timestamp = System.currentTimeMillis() / 1000L - 946_702_800L
        for (i in 0..3) frame[6 + i] = (timestamp shr (8 * i)).toByte()
        frame[frame.lastIndex] = checksum(frame, 0, frame.lastIndex - 1)
        handler.handleNotification(CHR_T2_NOTIFY, frame)
        val m = callbacks.published.single()
        assertThat(m[MeasurementType.WEIGHT]?.value).isEqualTo(70f)
        assertThat(m[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
        assertThat(m[MeasurementType.BODY_FAT]?.value).isEqualTo(20f)
    }

    @Test
    fun `VA resistance is whole ohms with absent short or unencrypted advertisement metadata`() {
        for (payload in listOf(null, ByteArray(15), ByteArray(16))) {
            val (handler, _, callbacks) = newHandler(testUser(), scanResult = advertisement(payload))
            handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
            handler.handleNotification(CHR_T2_NOTIFY, completedVaFrame(0x01, 0xF4.toByte()))
            assertThat(callbacks.published.single()[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
        }
    }

    @Test
    fun `VA advertised resistance encryption swaps bits in each byte instead of dividing by ten`() {
        val (handler, _, callbacks) = newHandler(testUser(),
            scanResult = advertisement(ByteArray(16).also { it[15] = 0x04 }))
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        // 0x10FE on the wire decodes to 0x01EF = 495 ohms, rather than 435 ohms.
        handler.handleNotification(CHR_T2_NOTIFY, completedVaFrame(0x10, 0xFE.toByte()))
        assertThat(callbacks.published.single()[MeasurementType.IMPEDANCE]?.value).isEqualTo(495f)
    }

    @Test
    fun `VA encryption flag does not change classic QN resistance`() {
        val (handler, _, callbacks) = newHandler(testUser(),
            scanResult = advertisement(ByteArray(16).also { it[15] = 0x04 }))
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0x07, 0))
        handler.handleNotification(CHR_T2_NOTIFY, completedVaFrame(0x01, 0xF4.toByte(), 0x07))
        assertThat(callbacks.published.single()[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
    }

    @Test
    fun `VA 0x13 configuration must not encode the user profile as measurement switches`() {
        // In the ES-CS20M VA protocol, byte 5 is a flags field: bit 0 disables BIA and bit 1 persists
        // that setting. Byte 6 bit 0 selects baby weighing. These are NOT height/age.
        val user = testUser() // Odd height: copying it into byte 5 disables BIA.
        val (handler, transport, _) = newHandler(user)

        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0xFF.toByte(), weightScaleFlag = 1))

        val cfg = transport.writes.map { it.payload }.firstOrNull { it.size == 9 && it[0] == 0x13.toByte() }
        assertThat(cfg).isNotNull()
        cfg!!

        val expectedChecksum = checksum(cfg, 0, cfg.lastIndex - 1)
        assertThat(cfg[8]).isEqualTo(expectedChecksum)
        assertThat(cfg[2]).isEqualTo(0xFF.toByte()) // echoed protocol type
        assertThat(cfg[4]).isEqualTo(0x10.toByte()) // lightInterval default
        assertThat(cfg[5]).isEqualTo(0x00.toByte()) // BIA enabled; no persistent mode change
        assertThat(cfg[6]).isEqualTo(0x00.toByte()) // normal adult weighing
        assertThat(cfg[7]).isEqualTo(0x00.toByte()) // reserved
    }

    @Test
    fun `weight-only ES-CS20M result does not fabricate composition from zero resistance`() {
        val user = testUser()
        val (handler, transport, callbacks) = newHandler(user)
        // Captured weight-only ES-CS20M packet; weight and checksum anonymized (70 kg).
        val frame = byteArrayOf(
            0x10, 0x0E, 0xFF.toByte(), 0x01, 0x02, 0x1B, 0x58,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x93.toByte(),
        )
        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(0xFF.toByte(), 0))
        handler.handleNotification(CHR_T2_NOTIFY, frame)

        assertThat(callbacks.published).hasSize(1)
        val measurement = callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(0.001f).of(70f)
        assertThat(measurement[MeasurementType.IMPEDANCE]).isNull()
        assertThat(measurement[MeasurementType.BODY_FAT]).isNull()
        assertThat(measurement[MeasurementType.WATER]).isNull()
        assertThat(measurement[MeasurementType.MUSCLE]).isNull()
        assertThat(measurement[MeasurementType.BONE]).isNull()
        assertThat(transport.writes.map { it.payload.toList() })
            .contains(listOf(0x1F, 0x05, 0xFF, 0x10, 0x33).map { it.toByte() })
    }

    @Test
    fun `classic 0x13 configuration keeps its zeroed extension bytes`() {
        // Regression guard: classic devices keep their pre-existing configuration.
        val user = testUser()
        val (handler, transport, _) = newHandler(user)

        handler.handleNotification(CHR_T2_NOTIFY, classicScaleInfoFrame(protocolType = 0x07, weightScaleFlag = 1))

        val cfg = transport.writes.map { it.payload }.firstOrNull { it.size == 9 && it[0] == 0x13.toByte() }
        assertThat(cfg).isNotNull()
        cfg!!
        assertThat(cfg[5]).isEqualTo(0x00.toByte())
        assertThat(cfg[6]).isEqualTo(0x00.toByte())
        assertThat(cfg[7]).isEqualTo(0x00.toByte())
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
    fun `Fit Plus frame replay waits for state 2 and decodes advertised encrypted resistance`() {
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
        // Supply the encoding flag explicitly: the notification capture alone does not carry it.
        val (handler, transport, callbacks) = newHandler(user,
            scanResult = advertisement(ByteArray(16).also { it[15] = 0x04 }))
        handler.handleNotification(CHR_T2_NOTIFY, scaleInfoFrame)
        handler.handleNotification(CHR_T2_NOTIFY, state0)
        assertThat(callbacks.published).isEmpty()

        handler.handleNotification(CHR_T2_NOTIFY, state1)
        assertThat(callbacks.published).isEmpty() // state 1 has R1=R2=0 -> must not publish yet

        handler.handleNotification(CHR_T2_NOTIFY, state2)
        assertThat(callbacks.published).hasSize(1)
        val published = callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]?.value).isWithin(0.01f).of(85.35f)
        // With the encryption flag, 0x10FE decodes to 0x01EF (495 whole ohms).
        assertThat(published[MeasurementType.IMPEDANCE]?.value).isWithin(0.01f).of(495.0f)

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
