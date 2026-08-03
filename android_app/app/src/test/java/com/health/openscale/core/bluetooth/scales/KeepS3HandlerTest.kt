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
import com.health.openscale.core.bluetooth.libs.KeepS3BodyComposition
import com.health.openscale.core.data.ActivityLevel
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
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepS3HandlerTest {
    private val service = uuid16(0x00FF)
    private val notifyCharacteristic = uuid16(0xFF01)
    private val writeCharacteristic = uuid16(0xFF02)

    @Test
    fun `builds empty negotiate request`() {
        assertThat(KeepS3Protocol.buildRequest(0x38))
            .isEqualTo(bytes("01 53 38 00 00"))
    }

    @Test
    fun `builds measurement event ack`() {
        assertThat(KeepS3Protocol.buildAck(0x57))
            .isEqualTo(bytes("04 53 57 00 00 80"))
    }

    @Test
    fun `parses first capture-verified final measurement`() {
        val event = KeepS3Protocol.parseMeasurementEvent(
            bytes("03 53 57 00 08 29 42 7C 00 00 01 2D 6B"),
        )!!

        assertThat(event.stage).isEqualTo(0x29)
        assertThat(event.weightKg).isWithin(0.0001f).of(85.10f)
        assertThat(event.impedanceOhm).isEqualTo(301)
        assertThat(event.heartRateBpm).isEqualTo(107)
    }

    @Test
    fun `parses second capture-verified final measurement`() {
        val event = KeepS3Protocol.parseMeasurementEvent(
            bytes("03 53 57 00 08 29 42 68 00 00 01 2C 61"),
        )!!

        assertThat(event.stage).isEqualTo(0x29)
        assertThat(event.weightKg).isWithin(0.0001f).of(85.00f)
        assertThat(event.impedanceOhm).isEqualTo(300)
        assertThat(event.heartRateBpm).isEqualTo(97)
    }

    @Test
    fun `parses live weight without publishing`() {
        val setup = attachedHandler()
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        val live = bytes("03 53 57 00 08 00 42 5E 00 00 00 00 00")
        val parsed = KeepS3Protocol.parseMeasurementEvent(live)!!
        setup.handler.handleNotification(notifyCharacteristic, live)

        assertThat(parsed.stage).isEqualTo(0x00)
        assertThat(parsed.weightKg).isWithin(0.0001f).of(84.95f)
        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.transport.writes).hasSize(1)
        assertThat(setup.transport.writes.single().payload)
            .isEqualTo(bytes("04 53 57 00 00 80"))
    }

    @Test
    fun `rejects truncated measurement event safely and still acknowledges known envelope`() {
        val setup = attachedHandler()
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()
        val truncated = bytes("03 53 57 00 08 29 42 7C 00 00 01 2D")

        assertThat(KeepS3Protocol.parseMeasurementEvent(truncated)).isNull()
        setup.handler.handleNotification(notifyCharacteristic, truncated)

        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.transport.writes).hasSize(1)
        assertThat(setup.transport.writes.single().payload)
            .isEqualTo(bytes("04 53 57 00 00 80"))
    }

    @Test
    fun `parses first capture final record and decodes dual frequency fields`() {
        val record = KeepS3Protocol.parseFinalRecord(
            finalRecord(
                weightRaw = 17020,
                encodedImpedance50 = 0x5E255A,
                encodedImpedance100 = 0x2627DA,
                phaseAngle50Raw = -76,
                phaseAngle100Raw = -76,
                heartRate = 107,
            ),
        )!!

        assertThat(record.weightKg).isWithin(0.0001f).of(85.10f)
        assertThat(record.encodedImpedance50).isEqualTo(0x5E255A)
        assertThat(record.encodedImpedance100).isEqualTo(0x2627DA)
        assertThat(record.impedance50Ohm).isEqualTo(506)
        assertThat(record.impedance100Ohm).isEqualTo(478)
        assertThat(record.phaseAngle50Raw).isEqualTo(-76)
        assertThat(record.phaseAngle100Raw).isEqualTo(-76)
        assertThat(record.phaseAngle50Degrees!!).isWithin(0.0001f).of(7.6f)
        assertThat(record.phaseAngle100Degrees!!).isWithin(0.0001f).of(7.6f)
        assertThat(record.heartRateBpm).isEqualTo(107)
    }

    @Test
    fun `parses second capture final record and decodes dual frequency fields`() {
        val record = KeepS3Protocol.parseFinalRecord(
            finalRecord(
                weightRaw = 17000,
                encodedImpedance50 = 0x8227E5,
                encodedImpedance100 = 0x6C66AC,
                phaseAngle50Raw = -77,
                phaseAngle100Raw = -77,
                heartRate = 97,
            ),
        )!!

        assertThat(record.weightKg).isWithin(0.0001f).of(85.00f)
        assertThat(record.impedance50Ohm).isEqualTo(502)
        assertThat(record.impedance100Ohm).isEqualTo(475)
        assertThat(record.phaseAngle50Degrees!!).isWithin(0.0001f).of(7.7f)
        assertThat(record.phaseAngle100Degrees!!).isWithin(0.0001f).of(7.7f)
        assertThat(record.heartRateBpm).isEqualTo(97)
    }

    @Test
    fun `rejects malformed final record and impedance sentinel safely`() {
        assertThat(KeepS3Protocol.parseFinalRecord(ByteArray(65))).isNull()
        assertThat(KeepS3Protocol.decodeEncodedImpedance(0xFFFFFF)).isNull()
    }

    @Test
    fun `final record falls back once and finishes once while every event is acknowledged`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()

        // A non-final 0x57 can still provide the impedance used by the 0x58 fallback.
        setup.handler.handleNotification(
            notifyCharacteristic,
            bytes("03 53 57 00 08 09 42 7C 00 00 01 2D 00"),
        )
        val record = finalRecord(
            weightRaw = 17020,
            encodedImpedance50 = 0x5E255A,
            encodedImpedance100 = 0x2627DA,
            phaseAngle50Raw = -76,
            phaseAngle100Raw = -76,
            heartRate = 107,
        )
        setup.handler.handleNotification(notifyCharacteristic, record)
        setup.handler.handleNotification(notifyCharacteristic, record)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().weight).isWithin(0.0001f).of(85.10f)
        assertThat(setup.callbacks.published.single().impedance).isEqualTo(478.0)
        assertThat(setup.callbacks.published.single().impedanceLow).isEqualTo(506.0)
        assertThat(setup.callbacks.published.single().heartRate).isEqualTo(107)
        assertThat(setup.callbacks.published.single().fat).isEqualTo(29.5f)
        assertThat(setup.callbacks.published.single().water).isEqualTo(50.2f)
        assertThat(setup.callbacks.published.single().muscle)
            .isWithin(0.001f).of(38.42538f)
        assertThat(setup.callbacks.published.single().visceralFat).isEqualTo(12f)
        assertThat(setup.callbacks.published.single().protein).isEqualTo(12.7f)
        assertThat(setup.callbacks.published.single().bone).isEqualTo(3.0f)
        assertThat(setup.callbacks.published.single().lbm).isEqualTo(60.0f)
        assertThat(setup.callbacks.published.single().bmr).isEqualTo(1791f)

        val storedDeviceImpedance = KeepS3Protocol.parseDeviceImpedance(
            setup.settings.strings[KeepS3Protocol.deviceImpedanceSettingKey(setup.user.id)],
        )!!
        assertThat(storedDeviceImpedance.timestampSeconds).isEqualTo(
            setup.callbacks.published.single().dateTime!!.time / 1000L,
        )
        assertThat(storedDeviceImpedance.weightRaw).isEqualTo(17020)
        assertThat(storedDeviceImpedance.impedanceOhm).isEqualTo(301)

        val payloads = setup.transport.writes.map { it.payload }
        assertThat(payloads.count { it.contentEquals(KeepS3Protocol.buildAck(0x58)) }).isEqualTo(2)
        assertThat(payloads.count { isControlRequest(it, start = false) }).isEqualTo(2)

        // The disconnect is held back for DISCONNECT_DELAY_MS so the queued ACKs and stop
        // commands can drain first.
        runCurrent()
        assertThat(setup.transport.disconnectCount).isEqualTo(0)

        advanceTimeBy(5_999)
        runCurrent()
        assertThat(setup.transport.disconnectCount).isEqualTo(0)

        advanceTimeBy(1)
        runCurrent()
        assertThat(setup.transport.disconnectCount).isEqualTo(1)
    }

    @Test
    fun `final measurement waits briefly for final record and publishes enriched data once`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()
        val final = bytes("03 53 57 00 08 29 42 68 00 00 01 2C 61")
        val record = finalRecord(
            weightRaw = 17000,
            encodedImpedance50 = 0x8227E5,
            encodedImpedance100 = 0x6C66AC,
            phaseAngle50Raw = -77,
            phaseAngle100Raw = -77,
            heartRate = 97,
        )

        setup.handler.handleNotification(notifyCharacteristic, final)
        setup.handler.handleNotification(notifyCharacteristic, final)
        assertThat(setup.callbacks.published).isEmpty()

        setup.handler.handleNotification(notifyCharacteristic, record)
        setup.handler.handleNotification(notifyCharacteristic, record)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().userId).isEqualTo(setup.user.id)
        assertThat(setup.callbacks.published.single().impedance).isEqualTo(475.0)
        assertThat(setup.callbacks.published.single().impedanceLow).isEqualTo(502.0)
        assertThat(setup.callbacks.published.single().fat).isEqualTo(29.4f)
        assertThat(setup.callbacks.published.single().water).isEqualTo(50.3f)
        assertThat(setup.callbacks.published.single().muscle)
            .isWithin(0.001f).of(38.47059f)
        assertThat(setup.transport.writes.count {
            it.payload.contentEquals(KeepS3Protocol.buildAck(0x57))
        }).isEqualTo(2)
        assertThat(setup.transport.writes.count {
            it.payload.contentEquals(KeepS3Protocol.buildAck(0x58))
        }).isEqualTo(2)
    }

    @Test
    fun `final measurement publishes once after timeout when final record is missing`() = runTest {
        val setup = attachedHandler(scope = this)
        setup.handler.handleConnected(setup.user)
        setup.transport.clearWrites()
        val final = bytes("03 53 57 00 08 29 42 68 00 00 01 2C 61")

        setup.handler.handleNotification(notifyCharacteristic, final)
        setup.handler.handleNotification(notifyCharacteristic, final)

        assertThat(setup.callbacks.published).isEmpty()
        advanceTimeBy(1_999)
        runCurrent()
        assertThat(setup.callbacks.published).isEmpty()
        advanceTimeBy(1)
        runCurrent()

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().weight).isWithin(0.0001f).of(85.00f)
        assertThat(setup.callbacks.published.single().impedance).isEqualTo(0.0)
        assertThat(setup.callbacks.published.single().impedanceLow).isEqualTo(0.0)
        assertThat(setup.callbacks.published.single().heartRate).isEqualTo(97)
    }

    @Test
    fun `invalid user profile keeps verified result and omits composition safely`() = runTest {
        val user = syntheticUser().apply { bodyHeight = Float.NaN }
        val setup = attachedHandler(user = user, scope = this)
        setup.handler.handleConnected(user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(
            notifyCharacteristic,
            finalRecord(
                weightRaw = 17020,
                encodedImpedance50 = 0x5E255A,
                encodedImpedance100 = 0x2627DA,
                phaseAngle50Raw = -76,
                phaseAngle100Raw = -76,
                heartRate = 107,
            ),
        )

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single().weight).isEqualTo(85.1f)
        assertThat(setup.callbacks.published.single().heartRate).isEqualTo(107)
        assertThat(setup.callbacks.published.single().fat).isEqualTo(0f)
        assertThat(setup.callbacks.published.single().water).isEqualTo(0f)
        assertThat(setup.callbacks.published.single().lbm).isEqualTo(0f)
    }

    @Test
    fun `extreme activity level does not enable unverified Keep athlete mode`() = runTest {
        val user = syntheticUser().apply { activityLevel = ActivityLevel.EXTREME }
        val setup = attachedHandler(user = user, scope = this)
        setup.handler.handleConnected(user)
        setup.transport.clearWrites()

        setup.handler.handleNotification(
            notifyCharacteristic,
            finalRecord(
                weightRaw = 17020,
                encodedImpedance50 = 0x5E255A,
                encodedImpedance100 = 0x2627DA,
                phaseAngle50Raw = -76,
                phaseAngle100Raw = -76,
                heartRate = 107,
            ),
        )

        val expected = KeepS3BodyComposition.calculate(
            KeepS3BodyComposition.Input(
                gender = user.gender,
                age = user.age,
                heightCm = user.bodyHeight.roundToInt(),
                weightKg = 85.10f,
                impedance50Ohm = 506,
                impedance100Ohm = 478,
                athlete = false,
            ),
        )!!
        val actual = setup.callbacks.published.single()
        assertThat(actual.fat).isEqualTo(expected.bodyFatPercent)
        assertThat(actual.water).isEqualTo(expected.waterPercent)
        assertThat(actual.muscle).isEqualTo(expected.skeletalMusclePercent)
        assertThat(actual.bone).isEqualTo(expected.boneKg)
        assertThat(actual.lbm).isEqualTo(expected.fatFreeMassKg)
    }

    @Test
    fun `builds profile payload with repeated token previous record and big endian fields`() {
        val token = "0123456789abcdef01234567"
        val payload = KeepS3Protocol.buildProfilePayload(
            token = token,
            previous = KeepS3Protocol.PreviousRecord(
                weightKg = 85.10f,
                timestampSeconds = 0x1234_5678L,
                impedanceOhm = 301.0,
            ),
            heightCm = 168,
            birthYear = 2000,
            birthMonth = 5,
            birthDay = 20,
        )

        assertThat(payload).hasLength(63)
        assertThat(payload.copyOfRange(0, 24)).isEqualTo(token.encodeToByteArray())
        assertThat(payload.copyOfRange(24, 48)).isEqualTo(token.encodeToByteArray())
        assertThat(KeepS3Protocol.decodeU16BE(payload, 48)).isEqualTo(17020)
        assertThat(KeepS3Protocol.decodeU32BE(payload, 50)).isEqualTo(0x1234_5678L)
        assertThat(KeepS3Protocol.decodeU16BE(payload, 54)).isEqualTo(301)
        assertThat(KeepS3Protocol.decodeU16BE(payload, 56)).isEqualTo(0)
        assertThat(payload[58].toInt() and 0xFF).isEqualTo(168)
        assertThat(KeepS3Protocol.decodeU16BE(payload, 59)).isEqualTo(2000)
        assertThat(payload[61].toInt() and 0xFF).isEqualTo(5)
        assertThat(payload[62].toInt() and 0xFF).isEqualTo(20)
    }

    @Test
    fun `missing previous measurement produces isolated all-zero record`() {
        val payload = KeepS3Protocol.buildProfilePayload(
            token = "0123456789abcdef01234567",
            previous = null,
            heightCm = 168,
            birthYear = 2000,
            birthMonth = 5,
            birthDay = 20,
        )

        assertThat(payload.copyOfRange(48, 58)).isEqualTo(ByteArray(10))
    }

    @Test
    fun `profile numeric fields clamp overflow`() {
        val payload = KeepS3Protocol.buildProfilePayload(
            token = "0123456789abcdef01234567",
            previous = KeepS3Protocol.PreviousRecord(
                weightKg = Float.MAX_VALUE,
                timestampSeconds = Long.MAX_VALUE,
                impedanceOhm = Double.MAX_VALUE,
            ),
            heightCm = Int.MAX_VALUE,
            birthYear = Int.MAX_VALUE,
            birthMonth = 99,
            birthDay = 99,
        )

        assertThat(KeepS3Protocol.decodeU16BE(payload, 48)).isEqualTo(0xFFFF)
        assertThat(KeepS3Protocol.decodeU32BE(payload, 50)).isEqualTo(0xFFFF_FFFFL)
        assertThat(KeepS3Protocol.decodeU16BE(payload, 54)).isEqualTo(0xFFFF)
        assertThat(payload[58].toInt() and 0xFF).isEqualTo(0xFF)
        assertThat(KeepS3Protocol.decodeU16BE(payload, 59)).isEqualTo(0xFFFF)
        assertThat(payload[61].toInt() and 0xFF).isEqualTo(12)
        assertThat(payload[62].toInt() and 0xFF).isEqualTo(31)
    }

    @Test
    fun `builds start and stop control payloads`() {
        val token = "0123456789abcdef01234567"
        val start = KeepS3Protocol.buildMeasurementControl(token, start = true)
        val stop = KeepS3Protocol.buildMeasurementControl(token, start = false)

        assertThat(start).hasLength(50)
        assertThat(start.copyOfRange(0, 48)).isEqualTo((token + token).encodeToByteArray())
        assertThat(start.copyOfRange(48, 50)).isEqualTo(byteArrayOf(0x00, 0x01))
        assertThat(stop.copyOfRange(48, 50)).isEqualTo(byteArrayOf(0x00, 0x00))
    }

    @Test
    fun `validates and deterministically formats token bytes`() {
        val token = KeepS3Protocol.generateToken(ByteArray(12) { it.toByte() })

        assertThat(token).isEqualTo("000102030405060708090a0b")
        assertThat(KeepS3Protocol.validateToken(token)).isTrue()
        assertThat(KeepS3Protocol.validateToken(token.uppercase())).isFalse()
        assertThat(KeepS3Protocol.repeatedTokenBytes(token)).hasLength(48)
    }

    @Test
    fun `persisted device impedance is accepted only for its source measurement`() {
        val encoded = KeepS3Protocol.serializeDeviceImpedance(
            timestampSeconds = 0x1234_5678L,
            weightKg = 85.10f,
            impedanceOhm = 301,
        )!!
        val stored = KeepS3Protocol.parseDeviceImpedance(encoded)!!

        assertThat(stored.timestampSeconds).isEqualTo(0x1234_5678L)
        assertThat(stored.weightRaw).isEqualTo(17020)
        assertThat(stored.impedanceOhm).isEqualTo(301)
        assertThat(KeepS3Protocol.deviceImpedanceMatches(stored, 0x1234_5678L, 85.10f)).isTrue()
        assertThat(KeepS3Protocol.deviceImpedanceMatches(stored, 0x1234_5679L, 85.10f)).isFalse()
        assertThat(KeepS3Protocol.deviceImpedanceMatches(stored, 0x1234_5678L, 85.00f)).isFalse()
        assertThat(KeepS3Protocol.serializeDeviceImpedance(0L, 85.10f, 301)).isNull()
        assertThat(KeepS3Protocol.serializeDeviceImpedance(0x1234_5678L, 0f, 301)).isNull()
        assertThat(KeepS3Protocol.parseDeviceImpedance("invalid")).isNull()
    }

    @Test
    fun `generated token persists and reloads for the same user`() {
        val settings = InMemorySettings()
        val user = syntheticUser()
        val first = attachedHandler(user = user, settings = settings)
        first.handler.handleConnected(user)
        val key = KeepS3Protocol.tokenSettingKey(user.id)
        val generated = settings.strings[key]!!

        val second = attachedHandler(user = user, settings = settings)
        second.handler.handleConnected(user)

        assertThat(KeepS3Protocol.validateToken(generated)).isTrue()
        assertThat(settings.strings[key]).isEqualTo(generated)
    }

    @Test
    fun `state machine advances only on expected successful opcode and requires both F5 responses`() {
        val state = KeepS3InitStateMachine()
        assertThat(state.reset()).isEqualTo(KeepS3InitStep.NEGOTIATE)
        assertThat(state.acceptSuccessfulResponse(0x0A)).isNull()
        assertThat(state.expectedStep).isEqualTo(KeepS3InitStep.NEGOTIATE)

        assertThat(state.acceptSuccessfulResponse(0x38)).isEqualTo(KeepS3InitStep.READ_TIME)
        assertThat(state.acceptSuccessfulResponse(0x0A)).isEqualTo(KeepS3InitStep.SET_TIME)
        assertThat(state.acceptSuccessfulResponse(0x01)).isEqualTo(KeepS3InitStep.SET_UNIT)
        assertThat(state.acceptSuccessfulResponse(0x05)).isEqualTo(KeepS3InitStep.DEVICE_INFO)
        assertThat(state.acceptSuccessfulResponse(0xE7)).isEqualTo(KeepS3InitStep.UNKNOWN_F5_FIRST)
        assertThat(state.acceptSuccessfulResponse(0xF5)).isEqualTo(KeepS3InitStep.UNKNOWN_F5_SECOND)
        assertThat(state.expectedStep).isEqualTo(KeepS3InitStep.UNKNOWN_F5_SECOND)
        assertThat(state.acceptSuccessfulResponse(0x03)).isNull()
        assertThat(state.expectedStep).isEqualTo(KeepS3InitStep.UNKNOWN_F5_SECOND)
        assertThat(state.acceptSuccessfulResponse(0xF5)).isEqualTo(KeepS3InitStep.BATTERY)
    }

    @Test
    fun `handler sends response-driven initialization exactly once`() {
        val setup = attachedHandler(
            settings = InMemorySettings().apply {
                putString(KeepS3Protocol.tokenSettingKey(7), "0123456789abcdef01234567")
            },
        )
        setup.handler.handleConnected(setup.user)

        assertThat(setup.transport.notifications)
            .containsExactly(service to notifyCharacteristic)
        assertThat(requestOpcodes(setup.transport)).containsExactly(0x38).inOrder()

        setup.handler.handleNotification(notifyCharacteristic, response(0x22))
        setup.handler.handleNotification(notifyCharacteristic, response(0x38))
        setup.handler.handleNotification(notifyCharacteristic, response(0x38)) // duplicate old response
        setup.handler.handleNotification(notifyCharacteristic, response(0x0A, bytes("12 34 56 78")))
        setup.handler.handleNotification(notifyCharacteristic, response(0x01))
        setup.handler.handleNotification(notifyCharacteristic, response(0x05))
        setup.handler.handleNotification(notifyCharacteristic, response(0xE7))
        setup.handler.handleNotification(notifyCharacteristic, response(0xF5, byteArrayOf(0x01)))
        setup.handler.handleNotification(notifyCharacteristic, response(0xF5, byteArrayOf(0x01)))
        setup.handler.handleNotification(notifyCharacteristic, response(0x03, byteArrayOf(95)))
        setup.handler.handleNotification(notifyCharacteristic, response(0x20))
        setup.handler.handleNotification(notifyCharacteristic, response(0x32, byteArrayOf(0x00)))
        setup.handler.handleNotification(notifyCharacteristic, response(0x32, byteArrayOf(0x00))) // duplicate old response
        setup.handler.handleNotification(notifyCharacteristic, response(0x36))
        setup.handler.handleNotification(notifyCharacteristic, response(0x36)) // duplicate after completion

        assertThat(requestOpcodes(setup.transport)).containsExactly(
            0x38, 0x0A, 0x01, 0x05, 0xE7, 0xF5, 0xF5, 0x03, 0x20, 0x32, 0x36,
        ).inOrder()
        assertThat(setup.transport.writes.all { it.withResponse }).isTrue()
        assertThat(setup.transport.writes.all {
            it.service == service && it.characteristic == writeCharacteristic
        }).isTrue()
        val timeRequest = setup.transport.writes.first { requestOpcode(it.payload) == 0x01 }.payload
        assertThat(timeRequest.copyOfRange(5, 9)).isEqualTo(bytes("12 34 56 78"))
    }

    @Test
    fun `profile carries the persisted protocol impedance instead of either frequency band`() = runTest {
        val settings = InMemorySettings()
        val first = attachedHandler(previous = null, settings = settings, scope = this)
        first.handler.handleConnected(first.user)
        first.handler.handleNotification(
            notifyCharacteristic,
            bytes("03 53 57 00 08 29 42 68 00 00 01 2C 61"),
        )
        first.handler.handleNotification(
            notifyCharacteristic,
            finalRecord(
                weightRaw = 17000,
                encodedImpedance50 = 0x8227E5,
                encodedImpedance100 = 0x6C66AC,
                phaseAngle50Raw = -77,
                phaseAngle100Raw = -77,
                heartRate = 97,
            ),
        )
        val previous = first.callbacks.published.single()
        assertThat(previous.impedance).isEqualTo(475.0)
        assertThat(previous.impedanceLow).isEqualTo(502.0)

        val setup = attachedHandler(previous = previous, settings = settings, scope = this)

        driveInitializationThroughProfile(setup)

        val profileRequest = setup.transport.writes.single { requestOpcode(it.payload) == 0x32 }.payload
        assertThat(KeepS3Protocol.decodeU16BE(profileRequest, 5 + 48)).isEqualTo(17000)
        assertThat(KeepS3Protocol.decodeU32BE(profileRequest, 5 + 50)).isEqualTo(
            previous.dateTime!!.time / 1000L,
        )
        assertThat(KeepS3Protocol.decodeU16BE(profileRequest, 5 + 54)).isEqualTo(300)
    }

    @Test
    fun `profile uses all-zero record when persisted protocol impedance does not match`() {
        val previous = ScaleMeasurement(
            userId = 7,
            dateTime = Date(0x1234_5678L * 1000L),
            weight = 85.10f,
            impedance = 478.0,
            impedanceLow = 506.0,
        )
        val settings = InMemorySettings().apply {
            putString(
                KeepS3Protocol.deviceImpedanceSettingKey(previous.userId),
                KeepS3Protocol.serializeDeviceImpedance(
                    timestampSeconds = 0x1234_5677L,
                    weightKg = 85.10f,
                    impedanceOhm = 301,
                )!!,
            )
        }
        val setup = attachedHandler(previous = previous, settings = settings)

        driveInitializationThroughProfile(setup)

        val profileRequest = setup.transport.writes.single { requestOpcode(it.payload) == 0x32 }.payload
        assertThat(profileRequest.copyOfRange(5 + 48, 5 + 58)).isEqualTo(ByteArray(10))
    }

    @Test
    fun `failed response status does not advance initialization`() {
        val setup = attachedHandler()
        setup.handler.handleConnected(setup.user)

        setup.handler.handleNotification(
            notifyCharacteristic,
            bytes("02 53 38 00 00 81"),
        )

        assertThat(requestOpcodes(setup.transport)).containsExactly(0x38)
    }

    @Test
    fun `matches exact Keep S3 name without requiring advertised service`() {
        val handler = KeepS3Handler()
        val support = handler.supportFor(device("keep_s3"))!!

        assertThat(support.displayName).isEqualTo("Keep S3")
        assertThat(support.implemented).containsExactly(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG,
            DeviceCapability.BODY_COMPOSITION,
        )
        assertThat(support.capabilities).contains(DeviceCapability.BODY_COMPOSITION)
        assertThat(support.implemented).doesNotContain(DeviceCapability.BATTERY_LEVEL)
        assertThat(handler.supportFor(device("Keep_S3", 0x00FF))).isNotNull()
        assertThat(handler.supportFor(device("Keep_S3_extra", 0x00FF))).isNull()
        assertThat(handler.supportFor(device("Other", 0x00FF, 0xFFF0))).isNull()
    }

    private fun attachedHandler(
        user: ScaleUser = syntheticUser(),
        settings: InMemorySettings = InMemorySettings().apply {
            putString(KeepS3Protocol.tokenSettingKey(user.id), "0123456789abcdef01234567")
        },
        scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext),
        previous: ScaleMeasurement? = ScaleMeasurement(
            userId = user.id,
            dateTime = Date(0x1234_5678L * 1000L),
            weight = 85.10f,
            impedance = 301.0,
        ),
    ): Setup {
        val handler = KeepS3Handler()
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = settings,
            data = FixedDataProvider(user, previous),
            scope = scope,
        )
        return Setup(handler, transport, callbacks, settings, user)
    }

    private fun syntheticUser(): ScaleUser {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val birthday = Calendar.getInstance().apply {
            clear()
            set(currentYear - 26, Calendar.JANUARY, 1)
        }.time
        return ScaleUser(id = 7, birthday = birthday, bodyHeight = 168f)
    }

    private fun response(opcode: Int, data: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(
            KeepS3Protocol.FRAME_RESPONSE.toByte(),
            KeepS3Protocol.MAGIC.toByte(),
            opcode.toByte(),
            0x00,
            data.size.toByte(),
            KeepS3Protocol.STATUS_OK.toByte(),
        ) + data

    private fun driveInitializationThroughProfile(setup: Setup) {
        setup.handler.handleConnected(setup.user)
        setup.handler.handleNotification(notifyCharacteristic, response(0x38))
        setup.handler.handleNotification(notifyCharacteristic, response(0x0A, bytes("12 34 56 78")))
        setup.handler.handleNotification(notifyCharacteristic, response(0x01))
        setup.handler.handleNotification(notifyCharacteristic, response(0x05))
        setup.handler.handleNotification(notifyCharacteristic, response(0xE7))
        setup.handler.handleNotification(notifyCharacteristic, response(0xF5, byteArrayOf(0x01)))
        setup.handler.handleNotification(notifyCharacteristic, response(0xF5, byteArrayOf(0x01)))
        setup.handler.handleNotification(notifyCharacteristic, response(0x03, byteArrayOf(95)))
        setup.handler.handleNotification(notifyCharacteristic, response(0x20))
    }

    private fun finalRecord(
        weightRaw: Int,
        encodedImpedance50: Int = 0,
        encodedImpedance100: Int = 0,
        phaseAngle50Raw: Int = 0,
        phaseAngle100Raw: Int = 0,
        heartRate: Int,
    ): ByteArray {
        val frame = ByteArray(66)
        frame[0] = KeepS3Protocol.FRAME_EVENT.toByte()
        frame[1] = KeepS3Protocol.MAGIC.toByte()
        frame[2] = KeepS3Protocol.OP_FINAL_RECORD.toByte()
        frame[3] = 0x00
        frame[4] = 61
        val tokenPair = KeepS3Protocol.repeatedTokenBytes("0123456789abcdef01234567")
        tokenPair.copyInto(frame, destinationOffset = 5)
        KeepS3Protocol.encodeU16BE(frame, 53, weightRaw)
        encodeU24BE(frame, 55, encodedImpedance50)
        encodeU24BE(frame, 58, encodedImpedance100)
        KeepS3Protocol.encodeU16BE(frame, 61, phaseAngle50Raw and 0xFFFF)
        KeepS3Protocol.encodeU16BE(frame, 63, phaseAngle100Raw and 0xFFFF)
        frame[65] = heartRate.toByte()
        return frame
    }

    private fun encodeU24BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 16).toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = value.toByte()
    }

    private fun requestOpcodes(transport: CapturingTransport): List<Int> =
        transport.writes.mapNotNull { requestOpcode(it.payload) }

    private fun requestOpcode(payload: ByteArray): Int? =
        if (payload.size >= 5 && (payload[0].toInt() and 0xFF) == KeepS3Protocol.FRAME_REQUEST) {
            payload[2].toInt() and 0xFF
        } else {
            null
        }

    private fun isControlRequest(payload: ByteArray, start: Boolean): Boolean =
        requestOpcode(payload) == KeepS3Protocol.OP_MEASUREMENT_CONTROL &&
            payload.size == 55 &&
            payload[53] == 0x00.toByte() &&
            payload[54] == (if (start) 0x01 else 0x00).toByte()

    private fun device(name: String, vararg services: Int) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = services.map(::uuid16),
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
        val handler: KeepS3Handler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
        val settings: InMemorySettings,
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
        val strings = mutableMapOf<String, String>()
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

    private class FixedDataProvider(
        private val user: ScaleUser,
        private val previous: ScaleMeasurement?,
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? =
            previous?.takeIf { userId == user.id }
    }
}
