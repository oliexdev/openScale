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
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.TaylorBIAHandler.Model
import com.health.openscale.core.bluetooth.scales.TaylorBIAHandler.ScaleFrame
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Tests for [TaylorBIAHandler], which drives two scales on the same `AC 27` protocol:
 *
 *  - **Taylor 5331891 BIA** — frames from btsnoop_hci.log. Ground truth: the user's recorded
 *    12:49 pm weigh-in read 171.2 lb, and the matching stable frame was `AC 27 80 8D 2F 52 …`
 *    → 0x12F52 = 77 650 g = 77.650 kg = 171.2 lb.
 *  - **ALPHA Smart Scale PRO 3** — frames captured with the vendor's FitDays app (2026-09-20).
 *    Its last weigh-in reads 88 970 g and 469 Ohm; FitDays showed 88.95 kg, i.e. rounded to the
 *    scale's 0.05 kg display resolution.
 */
class TaylorBIAHandlerTest {

    private val serviceFfb0: UUID = uuid16(0xFFB0)
    private val charCfg: UUID = uuid16(0xFFB1)
    private val charData: UUID = uuid16(0xFFB2)

    // --- Taylor frames (PR #1393 capture); byte 19 carries the device flag 0x20 plus the checksum.
    private val taylorIdle = hex("AC 27 00 8C 00 00 02 00 00 01 00 00 00 00 00 00 00 24 D5 28")
    private val taylorRamp = hex("AC 27 00 8D 12 CE 02 00 00 01 00 00 00 00 00 00 00 24 D5 29")
    private val taylorLive = hex("AC 27 00 8D 32 40 02 00 00 01 00 00 00 00 00 00 00 24 D5 3B")
    private val taylorLocked = hex("AC 27 80 8D 32 40 02 00 00 01 00 00 00 00 00 00 00 24 D5 3B")
    private val taylorFinal = hex("AC 27 01 00 02 10 01 80 8D 32 40 00 00 00 00 00 00 24 D6 2D")

    // --- ALPHA frames ---
    private val alphaLiveSettling = hex("AC 27 00 69 5B 8A 02 00 30 01 00 00 00 00 00 00 00 24 D5 1A")
    private val alphaLiveLocked = hex("AC 27 80 69 5B 8A 02 00 30 01 00 00 00 00 00 00 00 24 D5 1A")
    private val alphaFinal = hex("AC 27 01 00 01 D5 01 80 69 5B 8A 00 00 00 00 00 00 24 D6 00")
    private val alphaFinalNoImpedance = hex("AC 27 01 00 00 00 01 80 69 5C 98 00 00 00 00 00 00 24 D6 19")
    private val alphaStored = hex("AC 27 00 6A B0 4E E1 80 68 18 F6 00 00 00 01 00 00 24 D8 1C")

    // --- Matching --------------------------------------------------------------

    @Test
    fun `claims the Taylor by name and MY_SCALE only together with service FFB0`() {
        val handler = TaylorBIAHandler()

        assertThat(handler.supportFor(device("BIA SCALE", serviceFfb0))!!.displayName)
            .isEqualTo("Taylor 5331891 BIA Scale")
        assertThat(handler.supportFor(device("5331891 BIA Scale"))!!.displayName)
            .isEqualTo("Taylor 5331891 BIA Scale")

        assertThat(handler.supportFor(device("my_scale", serviceFfb0))!!.displayName)
            .isEqualTo("ALPHA Smart Scale PRO 3")
        assertThat(handler.supportFor(device("MY_SCALE"))).isNull()
    }

    @Test
    fun `both models implement body composition, computed from the impedance`() {
        val handler = TaylorBIAHandler()

        listOf(device("BIA SCALE", serviceFfb0), device("MY_SCALE", serviceFfb0)).forEach { scale ->
            val support = handler.supportFor(scale)!!
            assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
            assertThat(support.implemented).contains(DeviceCapability.BODY_COMPOSITION)
        }
    }

    @Test
    fun `leaves the other FFB0 scales to their own handlers`() {
        val handler = TaylorBIAHandler()

        listOf("swan", "SSW532", "relaxmedic", "FITTRACK Dara", "robi").forEach { name ->
            assertThat(handler.supportFor(device(name, serviceFfb0))).isNull()
        }
    }

    @Test
    fun `is registered ahead of MGBHandler, which would otherwise claim both scales`() {
        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }

        assertThat(order.indexOf("TaylorBIAHandler")).isLessThan(order.indexOf("MGBHandler"))
        assertThat(MGBHandler().supportFor(device("MY_SCALE", serviceFfb0))).isNotNull()
    }

    // --- Frame decoding: Taylor -------------------------------------------------

    @Test
    fun `Taylor live frames decode the recorded weights`() {
        assertThat(TaylorBIAHandler.parse(taylorIdle))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 0, locked = false))
        assertThat(TaylorBIAHandler.parse(taylorRamp))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 70_350, locked = false))
        // The settled reading; the vendor app showed 173.0 lb.
        assertThat(TaylorBIAHandler.parse(taylorLocked))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 78_400, locked = true))
    }

    @Test
    fun `the Taylor final frame reports the same weight plus an impedance`() {
        assertThat(TaylorBIAHandler.parse(taylorFinal))
            .isEqualTo(ScaleFrame.FinalResult(weightGrams = 78_400, impedanceOhms = 528, locked = true))
    }

    @Test
    fun `the Taylor sets a device flag above the checksum bits`() {
        listOf(taylorIdle, taylorRamp, taylorLive, taylorLocked, taylorFinal).forEach { frame ->
            val byte19 = frame[19].toInt() and 0xFF
            assertThat(byte19 and 0x1F).isEqualTo(TaylorBIAHandler.scaleChecksum(frame))
            assertThat(byte19 and 0x20).isEqualTo(0x20)   // clear on every ALPHA frame
            assertThat(TaylorBIAHandler.parse(frame)).isNotNull()
        }
    }

    // --- Frame decoding: ALPHA --------------------------------------------------

    @Test
    fun `every captured ALPHA frame carries the 5-bit checksum`() {
        val captured = listOf(
            alphaLiveSettling, alphaLiveLocked, alphaFinal, alphaFinalNoImpedance, alphaStored,
            hex("AC 27 00 68 00 00 02 00 30 01 00 00 00 00 00 00 00 24 D5 14"), // nobody on the scale
            hex("AC 27 00 69 5A AE 02 00 30 01 00 00 00 00 00 00 00 24 D5 1D"),
            hex("AC 27 01 00 01 D5 01 80 69 5A AE 00 00 00 00 00 00 24 D6 03"),
            hex("AC 27 00 6A B0 39 38 80 68 47 36 00 00 00 01 00 00 24 D8 0D"),
        )
        captured.forEach { frame ->
            assertThat(frame[19].toInt() and 0x20).isEqualTo(0)   // set on every Taylor frame
            assertThat(TaylorBIAHandler.scaleChecksum(frame)).isEqualTo(frame[19].toInt() and 0x1F)
            assertThat(TaylorBIAHandler.parse(frame)).isNotNull()
        }
    }

    @Test
    fun `rejects frames with a bad checksum, a wrong header or a wrong length`() {
        val badChecksum = alphaLiveSettling.copyOf().also { it[19] = 0x1B }
        val badHeader = alphaLiveSettling.copyOf().also { it[1] = 0x02 }
        val truncated = alphaLiveSettling.copyOf(19)
        val eightByteMgbFrame = hex("AC 02 26 B6 00 00 CA A6")

        listOf(badChecksum, badHeader, truncated, eightByteMgbFrame, ByteArray(0)).forEach { frame ->
            assertThat(TaylorBIAHandler.parse(frame)).isNull()
        }
    }

    @Test
    fun `a valid frame of an unknown wire type is reported as such`() {
        val frame = alphaLiveSettling.copyOf().also { it[18] = 0xD9.toByte() }
        frame[19] = TaylorBIAHandler.scaleChecksum(frame).toByte()

        assertThat(TaylorBIAHandler.parse(frame)).isEqualTo(ScaleFrame.Unknown(0xD9))
    }

    @Test
    fun `decodes the ALPHA live weight and the locked state`() {
        assertThat(TaylorBIAHandler.parse(alphaLiveSettling))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 88_970, locked = false))
        assertThat(TaylorBIAHandler.parse(alphaLiveLocked))
            .isEqualTo(ScaleFrame.LiveWeight(weightGrams = 88_970, locked = true))
    }

    @Test
    fun `decodes the empty scale and the step-on ramp`() {
        // Consecutive live frames while stepping on: 0 g, 1 620 g, 61 560 g, 92 040 g overshoot.
        val ramp = listOf(
            hex("AC 27 00 68 00 00 02 00 30 01 00 00 00 00 00 00 00 24 D5 14") to 0,
            hex("AC 27 00 68 06 54 02 00 30 01 00 00 00 00 00 00 00 24 D5 0E") to 1_620,
            hex("AC 27 00 68 F0 78 02 00 30 01 00 00 00 00 00 00 00 24 D5 1C") to 61_560,
            hex("AC 27 00 69 67 88 02 00 30 01 00 00 00 00 00 00 00 24 D5 04") to 92_040,
        )
        ramp.forEach { (frame, grams) ->
            assertThat((TaylorBIAHandler.parse(frame) as ScaleFrame.LiveWeight).weightGrams)
                .isEqualTo(grams)
        }
    }

    @Test
    fun `weight is the low 18 bits of the weight word`() {
        // 150 000 g = 0x249F0 sits above bit 16, so the word becomes 0x6A49F0.
        val frame = alphaLiveSettling.copyOf().also { it[3] = 0x6A; it[4] = 0x49; it[5] = 0xF0.toByte() }
        frame[19] = TaylorBIAHandler.scaleChecksum(frame).toByte()

        assertThat(TaylorBIAHandler.weightGrams(frame, 3)).isEqualTo(150_000)
    }

    @Test
    fun `decodes weight and impedance from the final result`() {
        assertThat(TaylorBIAHandler.parse(alphaFinal))
            .isEqualTo(ScaleFrame.FinalResult(weightGrams = 88_970, impedanceOhms = 469, locked = true))
        assertThat(TaylorBIAHandler.parse(alphaFinalNoImpedance))
            .isEqualTo(ScaleFrame.FinalResult(weightGrams = 89_240, impedanceOhms = 0, locked = true))
    }

    @Test
    fun `decodes timestamp and weight from a stored record`() {
        assertThat(TaylorBIAHandler.parse(alphaStored))
            .isEqualTo(ScaleFrame.StoredRecord(timestampEpochSeconds = 1_789_939_425L, weightGrams = 6_390))
    }

    // --- Frame builders (phone -> scale) ---------------------------------------

    @Test
    fun `builds the profile frame FitDays sent for a 165 cm, 31 year old woman`() {
        // Captured at Unix time 0x6AB05077 with the account's profile: 165 cm, born 1995, female.
        assertThat(TaylorBIAHandler.buildProfile(0x6AB05077L, 165, 31, TaylorBIAHandler.SEX_FEMALE))
            .isEqualTo(hex("AC 27 6A B0 50 77 08 00 00 A5 00 00 1F 02 00 00 03 00 D0 82"))
    }

    @Test
    fun `profile frame encodes a male user and clamps out-of-range values`() {
        val frame = TaylorBIAHandler.buildProfile(0L, 300, -5, TaylorBIAHandler.SEX_MALE)

        assertThat(frame[9].toInt() and 0xFF).isEqualTo(255)
        assertThat(frame[12].toInt() and 0xFF).isEqualTo(0)
        assertThat(frame[13].toInt() and 0xFF).isEqualTo(1)
        assertThat(frame[19].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.phoneChecksum(frame))
    }

    @Test
    fun `builds the control frames FitDays sent`() {
        assertThat(TaylorBIAHandler.buildAck(TaylorBIAHandler.WIRE_FINAL_RESULT))
            .isEqualTo(hex("AC 27 04 D6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF B9"))
        assertThat(TaylorBIAHandler.buildAck(TaylorBIAHandler.WIRE_STORED_RECORD))
            .isEqualTo(hex("AC 27 04 D8 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF BB"))
        assertThat(TaylorBIAHandler.buildSessionStart())
            .isEqualTo(hex("AC 27 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF E0"))
    }

    // --- Session: Taylor --------------------------------------------------------

    @Test
    fun `the Taylor keeps its 8-byte MGB handshake`() {
        val setup = handshake(Model.TAYLOR_BIA)

        assertThat(setup.transport.notifications).containsExactly(serviceFfb0 to charData)
        // AC 02 <b2> <b3> <b4> <b5> CC <checksum>, one per configuration item.
        assertThat(setup.transport.writes.map { it.payload.size }).containsExactly(8, 8, 8, 8, 8, 8)
        assertThat(setup.transport.writes.map { it.payload[2].toInt() and 0xFF })
            .containsExactly(0xF7, 0xFA, 0xFB, 0xFD, 0xFC, 0xFE).inOrder()

        val profile = setup.transport.writes[2].payload   // FB: sex, age, height
        assertThat(profile[3].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.SEX_FEMALE)
        assertThat(profile[4].toInt() and 0xFF).isEqualTo(31)
        assertThat(profile[5].toInt() and 0xFF).isEqualTo(165)
    }

    @Test
    fun `the Taylor publishes weight only once the same reading repeats four times`() {
        val setup = connected(Model.TAYLOR_BIA)

        repeat(3) { setup.handler.handleNotification(charData, taylorLive) }
        assertThat(setup.callbacks.published).isEmpty()

        setup.handler.handleNotification(charData, taylorLive)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(78.400f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT)
    }

    @Test
    fun `a locked Taylor frame publishes immediately and nothing is published twice`() {
        val setup = connected(Model.TAYLOR_BIA)

        setup.handler.handleNotification(charData, taylorLocked)
        setup.handler.handleNotification(charData, taylorLocked)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.callbacks.published.single()[MeasurementType.WEIGHT]?.value)
            .isWithin(1e-4f).of(78.400f)
    }

    @Test
    fun `an idle Taylor frame publishes nothing and is never acknowledged`() {
        val setup = connected(Model.TAYLOR_BIA)

        repeat(6) { setup.handler.handleNotification(charData, taylorIdle) }

        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.transport.writes).isEmpty()
    }

    @Test
    fun `the Taylor publishes the last live reading when the link drops`() {
        val setup = connected(Model.TAYLOR_BIA)

        setup.handler.handleNotification(charData, taylorRamp)
        setup.handler.handleDisconnected()

        assertThat(setup.callbacks.published.single()[MeasurementType.WEIGHT]?.value)
            .isWithin(1e-4f).of(70.350f)
    }

    @Test
    fun `a Taylor final frame publishes its impedance too, but is never acknowledged`() {
        val setup = connected(Model.TAYLOR_BIA)

        setup.handler.handleNotification(charData, taylorFinal)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(78.400f)
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isWithin(1e-4f).of(528f)
        // Same 165 cm / 31 y / female test user, so the derived values differ from the capture's.
        assertThat(measurement[MeasurementType.BODY_FAT]?.value).isGreaterThan(0f)
        assertThat(setup.transport.writes).isEmpty()
    }

    // --- Session: ALPHA ---------------------------------------------------------

    @Test
    fun `the ALPHA gets the FitDays handshake, profile, session start, profile`() {
        val setup = handshake(Model.ALPHA_PRO3)

        assertThat(setup.transport.notifications).containsExactly(serviceFfb0 to charData)
        assertThat(setup.transport.writes.map { it.characteristic }).containsExactly(charCfg, charCfg, charCfg)

        val profile = setup.transport.writes[0].payload
        assertThat(profile[18].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.WIRE_PROFILE)
        assertThat(profile[9].toInt() and 0xFF).isEqualTo(165)
        assertThat(profile[12].toInt() and 0xFF).isEqualTo(31)
        assertThat(profile[13].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.SEX_FEMALE)
        assertThat(profile[19].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.phoneChecksum(profile))

        assertThat(setup.transport.writes[1].payload).isEqualTo(TaylorBIAHandler.buildSessionStart())
        assertThat(setup.transport.writes[2].payload[18].toInt() and 0xFF)
            .isEqualTo(TaylorBIAHandler.WIRE_PROFILE)
    }

    @Test
    fun `the profile carries the male sex code for a male user`() {
        val user = user(heightCm = 180f, ageYears = 40).apply { gender = GenderType.MALE }
        val setup = attached(Model.ALPHA_PRO3, user)

        setup.handler.handleConnected(user)

        assertThat(setup.transport.writes[0].payload[13].toInt() and 0xFF).isEqualTo(TaylorBIAHandler.SEX_MALE)
    }

    @Test
    fun `ALPHA live frames publish nothing`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaLiveSettling)
        setup.handler.handleNotification(charData, alphaLiveLocked)

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun `the final result publishes weight and impedance and is acknowledged`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaFinal)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.userId).isEqualTo(setup.user.id)
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(88.97f)
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isWithin(1e-4f).of(469f)
        assertThat(setup.transport.writes.last().payload)
            .isEqualTo(hex("AC 27 04 D6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF B9"))
    }

    /**
     * The captured weigh-in run through StandardImpedanceLib: 88.970 kg at 469 Ohm for the 165 cm,
     * 31 year old woman whose profile FitDays wrote in the same session.
     */
    @Test
    fun `the final result derives the body composition the scale withholds`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaFinal)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.BODY_FAT]?.value).isWithin(0.1f).of(38.1f)
        assertThat(measurement[MeasurementType.WATER]?.value).isWithin(0.1f).of(44.4f)
        assertThat(measurement[MeasurementType.MUSCLE]?.value).isWithin(0.1f).of(29.4f)
        assertThat(measurement[MeasurementType.BONE]?.value).isWithin(0.1f).of(2.8f)
        assertThat(measurement[MeasurementType.LBM]?.value).isWithin(0.1f).of(55.0f)
        assertThat(measurement[MeasurementType.BMR]?.value).isWithin(1f).of(1559f)
    }

    @Test
    fun `a final result without impedance publishes the weight only`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaFinalNoImpedance)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(89.24f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT)
    }

    @Test
    fun `an impedance outside the formulas' range yields weight and impedance only`() {
        val setup = connected(Model.ALPHA_PRO3)
        // 100 Ohm at 88.970 kg puts the Sun equations' fat-free mass above the body weight.
        val absurd = alphaFinal.copyOf().also { it[4] = 0x00; it[5] = 0x64 }
        absurd[19] = TaylorBIAHandler.scaleChecksum(absurd).toByte()

        setup.handler.handleNotification(charData, absurd)

        val measurement = setup.callbacks.published.single()
        assertThat(measurement.values.keys)
            .containsExactly(MeasurementType.WEIGHT, MeasurementType.IMPEDANCE)
    }

    @Test
    fun `a repeated final result in the same session is acknowledged but not published twice`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaFinal)
        setup.handler.handleNotification(charData, alphaFinal)

        assertThat(setup.callbacks.published).hasSize(1)
        assertThat(setup.transport.writes.count { it.payload[2].toInt() == TaylorBIAHandler.CMD_ACK })
            .isEqualTo(2)
    }

    @Test
    fun `stored records publish at their own timestamp and are acknowledged`() {
        val setup = connected(Model.ALPHA_PRO3)

        setup.handler.handleNotification(charData, alphaStored)
        setup.handler.handleNotification(charData, alphaFinal)

        assertThat(setup.callbacks.published).hasSize(2)
        val record = setup.callbacks.published.first()
        assertThat(record[MeasurementType.WEIGHT]?.value).isWithin(1e-4f).of(6.39f)
        assertThat(record.values.keys).containsExactly(MeasurementType.WEIGHT)
        assertThat(record.dateTime?.time).isEqualTo(1_789_939_425L * 1000L)
        assertThat(setup.transport.writes.map { it.payload }.first { it[2].toInt() == TaylorBIAHandler.CMD_ACK })
            .isEqualTo(hex("AC 27 04 D8 00 00 00 00 00 00 00 00 00 00 00 00 00 00 DF BB"))
    }

    @Test
    fun `frames from other characteristics and corrupt frames are ignored`() {
        val setup = connected(Model.ALPHA_PRO3)
        val corrupt = alphaFinal.copyOf().also { it[19] = 0x01 }

        setup.handler.handleNotification(charCfg, alphaFinal)
        setup.handler.handleNotification(charData, corrupt)

        assertThat(setup.callbacks.published).isEmpty()
    }

    // --- Harness ---------------------------------------------------------------

    /** A connected handler whose handshake writes are still in [CapturingTransport.writes]. */
    private fun handshake(model: Model): Setup {
        val setup = attached(model, user(heightCm = 165f, ageYears = 31))
        setup.handler.handleConnected(setup.user)
        return setup
    }

    /** A connected handler, handshake writes dropped so a test only sees what it triggers. */
    private fun connected(model: Model): Setup =
        handshake(model).also { it.transport.writes.clear() }

    /** Builds a handler that has already matched [model]'s advertisement, as ScaleFactory does. */
    private fun attached(model: Model, user: ScaleUser): Setup {
        val handler = TaylorBIAHandler()
        val name = if (model == Model.ALPHA_PRO3) "MY_SCALE" else "BIA SCALE"
        assertThat(handler.supportFor(device(name, serviceFfb0))).isNotNull()

        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, transport, callbacks, user)
    }

    private fun user(heightCm: Float, ageYears: Int): ScaleUser =
        ScaleUser(
            id = 7,
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -ageYears); add(Calendar.DAY_OF_YEAR, -1) }.time,
            bodyHeight = heightCm,
            gender = GenderType.FEMALE,
        )

    private data class Setup(
        val handler: TaylorBIAHandler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
        val user: ScaleUser,
    )

    private data class Write(val service: UUID, val characteristic: UUID, val payload: ByteArray)

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<Write>()
        val notifications = mutableListOf<Pair<UUID, UUID>>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += Write(service, characteristic, payload.copyOf())
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        override fun getInt(key: String, default: Int): Int = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?): String? = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
