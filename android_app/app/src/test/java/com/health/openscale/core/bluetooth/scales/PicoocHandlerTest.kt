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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PicoocHandlerTest {
    private val notifyCharacteristic = uuid16(0xFFF1)

    // --- device matching -------------------------------------------------------------------

    @Test
    fun `claims connection-oriented picooc scales`() {
        val support = PicoocHandler().supportFor(device("PICOOC-CQ"))!!

        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
        assertThat(support.implemented).contains(DeviceCapability.BODY_COMPOSITION)
        assertThat(support.displayName).isEqualTo("PICOOC-CQ")
    }

    /** The whole connection-oriented line, per the protocol write-up and the CQ capture. */
    @Test
    fun `claims every connection-oriented model in the line`() {
        val names = listOf(
            "PICOOC-CQ",   // S3 Lite V2.0
            "PICOOC-C1",
            "PICOOC-14",
            "PICOOC-25",
            "PICOOC-IS",
            "Picooc S3 Lite V2.0",
        )

        for (name in names) {
            assertThat(PicoocHandler().supportFor(device(name))).isNotNull()
        }
    }

    /** The Latin series advertises with no vendor prefix at all, so a "picooc" test misses it. */
    @Test
    fun `claims the Latin series despite its missing vendor prefix`() {
        val support = PicoocHandler().supportFor(device("Latin-S"))!!

        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
        assertThat(support.displayName).isEqualTo("Picooc Latin-S")
    }

    @Test
    fun `leaves the broadcast-only PICOOC-L family unclaimed`() {
        assertThat(PicoocHandler().supportFor(device("PICOOC-L"))).isNull()
        assertThat(PicoocHandler().supportFor(device("PICOOC-L1"))).isNull()
    }

    @Test
    fun `ignores unrelated devices`() {
        assertThat(PicoocHandler().supportFor(device("Vitafit VT701"))).isNull()
        assertThat(PicoocHandler().supportFor(device("QN-Scale"))).isNull()
        assertThat(PicoocHandler().supportFor(device(""))).isNull()
    }

    // --- frame builders --------------------------------------------------------------------

    @Test
    fun `builds three byte acknowledgements`() {
        assertThat(PicoocHandler.buildAck(PicoocHandler.PREFIX_LATIN, 0x39))
            .isEqualTo(bytes("F1 03 39"))
        assertThat(PicoocHandler.buildAck(PicoocHandler.PREFIX_MODERN, 0x52))
            .isEqualTo(bytes("A1 03 52"))
    }

    @Test
    fun `time reply matches its own length field`() {
        val reply = PicoocHandler.buildTimeReply(
            PicoocHandler.PREFIX_LATIN,
            PicoocHandler.OP_HANDSHAKE,
            0x1234_5678L,
            unit = 0,
        )

        assertThat(reply).isEqualTo(bytes("F1 0A 3A 12 34 56 78 00 00 00"))
        assertThat(reply.size).isEqualTo(reply[1].toInt())
    }

    @Test
    fun `encodes height as five millimetre steps above one metre`() {
        assertThat(PicoocHandler.encodeHeight(180f)).isEqualTo(160)
        assertThat(PicoocHandler.encodeHeight(150f)).isEqualTo(100)
        assertThat(PicoocHandler.encodeHeight(100f)).isEqualTo(0)
        // Clamped at both ends: below 1 m underflows, above 227.5 cm overflows a byte.
        assertThat(PicoocHandler.encodeHeight(80f)).isEqualTo(0)
        assertThat(PicoocHandler.encodeHeight(250f)).isEqualTo(255)
        // A user with no height must not abort the handshake.
        assertThat(PicoocHandler.encodeHeight(Float.NaN)).isEqualTo(0)
        assertThat(PicoocHandler.encodeHeight(-1f)).isEqualTo(0)
    }

    @Test
    fun `an unusable height still produces a well-formed profile`() {
        val user = user(GenderType.MALE, 180f, 24).apply { bodyHeight = Float.NaN }

        assertThat(PicoocHandler.buildUserProfile(user)).isEqualTo(bytes("31 06 01 01 00 18"))
    }

    @Test
    fun `user profile matches its own length field and carries the real user`() {
        val profile = PicoocHandler.buildUserProfile(user(GenderType.MALE, 180f, 24))

        assertThat(profile).isEqualTo(bytes("31 06 01 01 A0 18"))
        assertThat(profile.size).isEqualTo(profile[1].toInt())
    }

    @Test
    fun `user profile encodes women as sex two`() {
        val profile = PicoocHandler.buildUserProfile(user(GenderType.FEMALE, 165f, 31))

        assertThat(profile).isEqualTo(bytes("31 06 01 02 82 1F"))
    }

    @Test
    fun `user profile raises ages below the BIA engine minimum`() {
        val profile = PicoocHandler.buildUserProfile(user(GenderType.MALE, 140f, 9))

        assertThat(profile[5].toInt()).isEqualTo(18)
    }

    // --- frame parsers ---------------------------------------------------------------------

    @Test
    fun `parses a completed live frame`() {
        val frame = PicoocHandler.parseLiveFrame(liveFrame(complete = true))!!

        assertThat(frame.timestamp).isEqualTo(0x1234_5678L)
        assertThat(frame.weightKg).isWithin(0.0001f).of(70.0f)
        assertThat(frame.impedance).isWithin(0.0001).of(500.0)
        assertThat(frame.weightLb).isWithin(0.0001f).of(154.3f)
        assertThat(frame.unit).isEqualTo(0)
        assertThat(frame.phaseAngle).isWithin(0.0001f).of(5.25f)
        assertThat(frame.complete).isTrue()
        assertThat(frame.poundFlag).isFalse()
    }

    /** Captured from a PICOOC-CQ weighing 71.7 kg, which the scale's own display confirmed. */
    @Test
    fun `parses the captured PICOOC-CQ weight frame`() {
        val frame = PicoocHandler.parseLiveFrame(
            bytes("39 10 6A 7B 9A E1 05 9A 13 A6 86 2C 00 02 B7 00"),
        )!!

        assertThat(frame.weightKg).isWithin(0.0001f).of(71.7f)
        assertThat(frame.impedance).isWithin(0.0001).of(503.0)
        assertThat(frame.phaseAngle).isWithin(0.0001f).of(6.95f)
        assertThat(frame.complete).isTrue()
        // 0x862C/10 would be 3434.8; only the low 15 bits are the pound value, and 158.0 lb is
        // exactly 71.7 kg. The top bit is a separate, still unexplained flag.
        assertThat(frame.weightLb).isWithin(0.0001f).of(158.0f)
        assertThat(frame.poundFlag).isTrue()
    }

    @Test
    fun `treats a non-zero trailing flag as still settling`() {
        assertThat(PicoocHandler.parseLiveFrame(liveFrame(complete = false))!!.complete).isFalse()
    }

    @Test
    fun `rejects a truncated live frame`() {
        assertThat(PicoocHandler.parseLiveFrame(liveFrame(complete = true).copyOf(15))).isNull()
    }

    @Test
    fun `parses a body composition frame`() {
        val composition = PicoocHandler.parseComposition(compositionFrame())!!

        assertThat(composition.weightKg).isWithin(0.0001f).of(70.0f)
        assertThat(composition.fatPercent).isWithin(0.0001f).of(18.5f)
        assertThat(composition.waterPercent).isWithin(0.0001f).of(55.2f)
        assertThat(composition.boneMassKg).isWithin(0.0001f).of(3.0f)
        assertThat(composition.visceralFat).isEqualTo(8)
        assertThat(composition.bodyAge).isEqualTo(27)
        assertThat(composition.impedance).isWithin(0.0001).of(500.0)
        assertThat(composition.unknown8).isEqualTo(0x1122)
        assertThat(composition.unknown12).isEqualTo(0x3344)
    }

    /**
     * Both 0x3C payloads a PICOOC-CQ produces, captured back to back: the in-progress frame
     * first, then the result. 0x44 = 68 bpm matched the scale's own display.
     */
    @Test
    fun `parses the captured heart rate exchange`() {
        val measuring = bytes("3C 05 00 00 01")
        assertThat(PicoocHandler.parseHeartRate(measuring)).isNull()
        assertThat(PicoocHandler.heartRateStatus(measuring))
            .isEqualTo(PicoocHandler.HEART_RATE_MEASURING)

        val result = bytes("3C 05 00 44 02")
        assertThat(PicoocHandler.parseHeartRate(result)).isEqualTo(68)
        assertThat(PicoocHandler.heartRateStatus(result))
            .isEqualTo(PicoocHandler.HEART_RATE_FINAL)
    }

    @Test
    fun `falls back to a single byte on shorter heart rate frames`() {
        assertThat(PicoocHandler.parseHeartRate(bytes("3C 03 48"))).isEqualTo(72)
        assertThat(PicoocHandler.parseHeartRate(bytes("3C 03 00"))).isNull()
        assertThat(PicoocHandler.parseHeartRate(bytes("3C 03"))).isNull()
    }

    @Test
    fun `dumps every heart rate candidate for diagnosing other models`() {
        val candidates = PicoocHandler.heartRateCandidates(bytes("3C 05 00 44 02")).toMap()

        assertThat(candidates).containsExactly(
            "u16be[2..3]", 68,
            "u8[2]", 0,
            "u8[3]", 68,
            "u16be[3..4]", 17410,
            "u8[4]", 2,
        )
    }

    // --- handshake -------------------------------------------------------------------------

    @Test
    fun `answers the handshake with a ten byte time frame`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, capturedHandshake)

        val reply = setup.transport.writes.single().payload
        assertThat(reply.size).isEqualTo(10)
        assertThat(reply.copyOf(3)).isEqualTo(bytes("F1 0A 3A"))
    }

    /**
     * PICOOC-CQ never sends 0x30 — the capture goes straight from the handshake to the weight
     * frame — so the profile has to be pushed unprompted or the scale's BIA engine never gets
     * sex, height and age.
     */
    @Test
    fun `pushes the profile after the handshake even without a 0x30 request`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, capturedHandshake)
        advanceTimeBy(PicoocHandler.PROFILE_DELAY_MS)
        runCurrent()

        assertThat(setup.transport.writes).hasSize(2)
        assertThat(setup.transport.writes[1].payload).isEqualTo(bytes("31 06 01 01 A0 18"))
    }

    @Test
    fun `does not push the profile twice for repeated handshakes`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, capturedHandshake)
        setup.handler.handleNotification(notifyCharacteristic, capturedHandshake)
        advanceTimeBy(PicoocHandler.PROFILE_DELAY_MS)
        runCurrent()

        assertThat(setup.transport.writes.count { it.payload.contentEquals(bytes("31 06 01 01 A0 18")) })
            .isEqualTo(1)
    }

    @Test
    fun `decodes the captured PICOOC-CQ handshake`() {
        val bom = PicoocHandler.parseBomInfo(capturedHandshake)!!

        assertThat(bom.version).isEqualTo(1)
        assertThat(bom.bomVersion).isEqualTo(0)
        assertThat(bom.modelId).isEqualTo(0x3A)
    }

    @Test
    fun `acks the profile request and follows up with the real user profile`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, bytes("30 02"))

        assertThat(setup.transport.writes.single().payload).isEqualTo(bytes("F1 03 30"))
        advanceTimeBy(PicoocHandler.PROFILE_DELAY_MS)
        runCurrent()

        assertThat(setup.transport.writes).hasSize(2)
        assertThat(setup.transport.writes[1].payload).isEqualTo(bytes("31 06 01 01 A0 18"))
    }

    @Test
    fun `switches to the A1 prefix once the scale uses the newer opcodes`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, bytes("51 0B 01 02 03 04 05 06 07 08 09"))
        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = false))

        // The reply to 0x51 is still a 0x3A time frame, only the prefix changes.
        assertThat(setup.transport.writes[0].payload.copyOf(3)).isEqualTo(bytes("A1 0A 3A"))
        assertThat(setup.transport.writes[1].payload).isEqualTo(bytes("A1 03 39"))
    }

    // --- measurement session ---------------------------------------------------------------

    @Test
    fun `streams live weight without writing a record`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        repeat(3) { setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = false)) }
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.transport.writes.map { it.payload.toList() })
            .containsExactlyElementsIn(List(3) { bytes("F1 03 39").toList() })
    }

    @Test
    fun `publishes once with composition and heart rate after the settle window`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = true))
        setup.handler.handleNotification(notifyCharacteristic, compositionFrame())
        setup.handler.handleNotification(notifyCharacteristic, bytes("3C 03 48"))

        assertThat(setup.callbacks.published).isEmpty()
        advanceTimeBy(PicoocHandler.SETTLE_WAIT_MS - 1)
        runCurrent()
        assertThat(setup.callbacks.published).isEmpty()
        advanceTimeBy(1)
        runCurrent()

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.userId).isEqualTo(setup.user.id)
        assertThat(measurement.weight).isWithin(0.0001f).of(70.0f)
        assertThat(measurement.fat).isWithin(0.0001f).of(18.5f)
        assertThat(measurement.water).isWithin(0.0001f).of(55.2f)
        assertThat(measurement.bone).isWithin(0.0001f).of(3.0f)
        assertThat(measurement.visceralFat).isWithin(0.0001f).of(8.0f)
        assertThat(measurement.lbm).isWithin(0.001f).of(57.05f)
        assertThat(measurement.heartRate).isEqualTo(72)
        assertThat(measurement.impedance).isWithin(0.0001).of(500.0)
        // 0x32 carries neither of these, so they come from the impedance estimator.
        assertThat(measurement.muscle).isGreaterThan(0f)
        assertThat(measurement.bmr).isGreaterThan(0f)
    }

    @Test
    fun `heart rate arriving after the composition frame is still included`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = true))
        setup.handler.handleNotification(notifyCharacteristic, bytes("3C 03 48"))
        setup.handler.handleNotification(notifyCharacteristic, compositionFrame())
        advanceTimeBy(PicoocHandler.SETTLE_WAIT_MS)
        runCurrent()

        assertThat(setup.callbacks.published.single().heartRate).isEqualTo(72)
    }

    @Test
    fun `estimates composition from impedance when the scale sends no 0x32`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = true))
        advanceTimeBy(PicoocHandler.SETTLE_WAIT_MS)
        runCurrent()

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.weight).isWithin(0.0001f).of(70.0f)
        assertThat(measurement.impedance).isWithin(0.0001).of(500.0)
        assertThat(measurement.fat).isGreaterThan(0f)
        assertThat(measurement.water).isGreaterThan(0f)
        assertThat(measurement.muscle).isGreaterThan(0f)
        assertThat(measurement.bmr).isGreaterThan(0f)
    }

    @Test
    fun `publishes the latched weight when the scale hangs up before completing`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = false))
        assertThat(setup.callbacks.published).isEmpty()

        setup.handler.handleDisconnected()

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().weight).isWithin(0.0001f).of(70.0f)
    }

    @Test
    fun `a disconnect after publishing does not add a second record`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, liveFrame(complete = true))
        advanceTimeBy(PicoocHandler.SETTLE_WAIT_MS)
        runCurrent()
        setup.handler.handleDisconnected()

        assertThat(setup.callbacks.published).hasSize(1)
    }

    /**
     * Replays a full PICOOC-CQ session frame for frame: handshake, a single already-settled
     * weight frame, a heart-rate measurement that takes 6.4 s to resolve, then the scale
     * hanging up. One record carrying both the weight and the heart rate, and nothing published
     * early — a 1.5 s settle window used to close the record before the heart rate even
     * started, and without the 0x3C ack the reading never arrived at all.
     */
    @Test
    fun `replays the captured PICOOC-CQ session as a single record`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(notifyCharacteristic, capturedHandshake)
        advanceTimeBy(2_800)
        runCurrent()

        setup.handler.handleNotification(
            notifyCharacteristic,
            bytes("39 10 6A 7B A5 50 05 A2 13 BA 86 36 00 02 C8 00"),
        )
        // 4 s of silence, then the scale reports that it is measuring the heart rate.
        advanceTimeBy(4_000)
        runCurrent()
        setup.handler.handleNotification(notifyCharacteristic, bytes("3C 05 00 00 01"))

        // 6.4 s more before the reading lands — far longer than the old 1.5 s window.
        advanceTimeBy(6_400)
        runCurrent()
        assertThat(setup.callbacks.published).isEmpty()

        setup.handler.handleNotification(notifyCharacteristic, bytes("3C 05 00 44 02"))
        setup.handler.handleDisconnected()

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.weight).isWithin(0.0001f).of(72.1f)
        assertThat(measurement.impedance).isWithin(0.0001).of(505.0)
        assertThat(measurement.heartRate).isEqualTo(68)
        assertThat(measurement.fat).isGreaterThan(0f)
        assertThat(measurement.bmr).isGreaterThan(0f)
    }

    @Test
    fun `undocumented opcodes are tolerated without publishing`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, bytes("3E 08 01 02 03 04 05 06"))
        setup.handler.handleNotification(notifyCharacteristic, bytes("3F 05 01 02 03"))
        setup.handler.handleNotification(notifyCharacteristic, bytes("36 04 01 02"))
        setup.handler.handleNotification(notifyCharacteristic, bytes("37 02"))
        // Neither of these carries data the scale waits on, so neither is acked.
        setup.handler.handleNotification(notifyCharacteristic, bytes("3B 03 01"))
        setup.handler.handleNotification(notifyCharacteristic, bytes("7F 02"))

        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.transport.writes.map { it.payload.toList() }).containsExactly(
            bytes("F1 03 3E").toList(),
            bytes("F1 03 3F").toList(),
            bytes("F1 03 36").toList(),
            bytes("F1 03 37").toList(),
        )
    }

    /**
     * Without this ack a PICOOC-CQ resends a byte-identical `3C 05 00 00 01` ten times at 1 Hz
     * and then drops the link, never reaching the reading its own display was showing.
     */
    @Test
    fun `acknowledges every heart rate frame`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        repeat(3) { setup.handler.handleNotification(notifyCharacteristic, bytes("3C 05 00 00 01")) }

        assertThat(setup.transport.writes.map { it.payload.toList() })
            .containsExactlyElementsIn(List(3) { bytes("F1 03 3C").toList() })
    }

    @Test
    fun `acknowledges the body composition frame`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(notifyCharacteristic, compositionFrame())

        assertThat(setup.transport.writes.single().payload).isEqualTo(bytes("F1 03 32"))
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The 0x3A handshake a PICOOC-CQ actually sent, BOM block and all. */
    private val capturedHandshake =
        bytes("3A 12 0A 00 00 00 00 00 00 00 00 10 39 03 A0 46 47 03")

    /**
     * A 0x39 frame reading 70.00 kg / 500.0 Ω / 154.3 lb / 5.25° phase angle.
     * Byte 15 is the completion flag: 0 means settled and reportable.
     */
    private fun liveFrame(complete: Boolean): ByteArray =
        bytes("39 10 12 34 56 78 05 78 13 88 06 07 00 02 0D") + byteArrayOf(if (complete) 0 else 1)

    /**
     * A 0x32 frame reading 70.00 kg / 18.5 % fat / 55.2 % water / 3.00 kg bone /
     * visceral 8 / body age 27 / 500 Ω, with recognisable filler in the undocumented fields.
     */
    private fun compositionFrame(): ByteArray =
        bytes("32 14 05 78 00 B9 02 28 11 22 00 3C 33 44 08 1B 00 00 01 F4")

    private fun attachedHandler(
        user: ScaleUser = user(GenderType.MALE, 180f, 24),
        scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext),
    ): Setup {
        val handler = PicoocHandler()
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = scope,
        )
        return Setup(handler, transport, callbacks, user)
    }

    private fun user(gender: GenderType, heightCm: Float, age: Int): ScaleUser {
        val birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -age) }.time
        return ScaleUser(id = 7, birthday = birthday, bodyHeight = heightCm, gender = gender)
    }

    private fun device(name: String) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = emptyList(),
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun bytes(hex: String): ByteArray = hex
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private data class Setup(
        val handler: PicoocHandler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
        val user: ScaleUser,
    )

    private data class Write(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean,
    )

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val notifications = mutableListOf<Pair<UUID, UUID>>()
        val writes = mutableListOf<Write>()
        var disconnectCount = 0

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }

        override fun write(
            service: UUID,
            characteristic: UUID,
            payload: ByteArray,
            withResponse: Boolean,
        ) {
            writes += Write(service, characteristic, payload.copyOf(), withResponse)
        }

        override fun read(service: UUID, characteristic: UUID) = Unit

        override fun disconnect() {
            disconnectCount++
        }

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = true

        fun clearWrites() = writes.clear()
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.copy()
        }

        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun remove(key: String) {
            strings.remove(key)
            ints.remove(key)
        }
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
