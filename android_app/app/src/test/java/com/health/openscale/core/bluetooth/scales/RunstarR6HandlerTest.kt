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
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Unit tests for [RunstarR6Handler]: frame parsing, and which values a final result publishes.
 *
 * The body-composition math is StandardImpedanceLib's and is pinned with exact expected
 * values in StandardImpedanceLibTest.
 */
class RunstarR6HandlerTest {

    private fun hex(s: String): ByteArray =
        s.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Build a 20-byte frame with a valid trailer checksum: `[seq][len][00][type][payload…][chk]`.
     */
    private fun frame(type: Int, payload: String, seq: Int = 0x01): ByteArray {
        val bytes = hex(payload)
        require(bytes.size <= 15)
        val f = ByteArray(20)
        f[0] = seq.toByte()
        f[1] = (bytes.size + 1).toByte()
        f[2] = 0x00
        f[3] = type.toByte()
        bytes.copyInto(f, 4)
        f[19] = RunstarR6Handler.computeChecksum(f).toByte()
        return f
    }

    @Test
    fun `extracts weight, heart rate and impedance from an A3 frame`() {
        // status 00, weight 01 4C 08 = 85000 g, heart rate 4A = 74 bpm, impedance 01 F4 = 500 Ω.
        val result = RunstarR6Handler.decodeFinalResult(frame(0xA3, "00 01 4c 08 4a 01 f4"))

        assertThat(result).isNotNull()
        assertThat(result!!.status).isEqualTo(0x00)
        assertThat(result.grams).isEqualTo(85000)
        assertThat(result.grams / 1000.0f).isWithin(1e-3f).of(85.0f)
        assertThat(result.heartRate).isEqualTo(74)
        assertThat(result.impedanceOhm).isEqualTo(500)
    }

    @Test
    fun `decodes the frame captured from a physical Runstar R6`() {
        // Verbatim from a real weigh-in: 66.35 kg, 75 bpm, 581 Ω.
        val result = RunstarR6Handler.decodeFinalResult(
            hex("f3 08 00 a3 00 01 03 2e 4b 02 45 00 00 00 00 00 00 00 00 07")
        )

        assertThat(result).isNotNull()
        assertThat(result!!.grams).isEqualTo(66350)
        assertThat(result.heartRate).isEqualTo(75)
        assertThat(result.impedanceOhm).isEqualTo(581)
    }

    @Test
    fun `decodes the frame captured from a Hoffen HO-36`() {
        // Verbatim from a weigh-in of a bucket of water standing on the electrodes; Fitdays
        // read 9.45 kg (the bucket hid the scale's display). The 558 Ω is presumably
        // measured through the water. The HO-36 sets 0x68 in the weight's top byte, so the
        // raw u24 68 24 ea would read as 6825.194 kg.
        val result = RunstarR6Handler.decodeFinalResult(
            hex("36 08 00 a3 00 68 24 ea 00 02 2e 00 00 00 00 00 00 00 00 09")
        )

        assertThat(result).isNotNull()
        assertThat(result!!.status).isEqualTo(0x00)
        assertThat(result.grams).isEqualTo(9450)
        assertThat(result.heartRate).isEqualTo(0)
        assertThat(result.impedanceOhm).isEqualTo(558)
    }

    @Test
    fun `clears the HO-36 flag bits in live weights`() {
        // Live A2 frames captured from the HO-36: empty scale (68 00 00 = 0 g) and the
        // 9.45 kg object (68 24 ea).
        val empty = hex("1a 07 00 a2 01 00 68 00 00 00 00 00 00 00 00 00 00 00 00 0b")
        val loaded = hex("ba 07 00 a2 02 00 68 24 ea 00 00 00 00 00 00 00 00 00 00 1a")
        assertThat(RunstarR6Handler.isChecksumValid(empty)).isTrue()
        assertThat(RunstarR6Handler.isChecksumValid(loaded)).isTrue()

        assertThat(RunstarR6Handler.weightGrams(empty, 6)).isEqualTo(0)
        assertThat(RunstarR6Handler.weightGrams(loaded, 6)).isEqualTo(9450)
    }

    @Test
    fun `clears the HO-36 flag bits in history weights`() {
        // A4 layout: timestamp at 4..7, weight at 9..11 (same flag byte as A2/A3).
        val history = frame(0xA4, "6a c1 76 bd 00 68 24 ea")
        assertThat(RunstarR6Handler.weightGrams(history, 9)).isEqualTo(9450)
    }

    @Test
    fun `weight mask leaves a plain R6 weight unchanged`() {
        // The largest weight the mask keeps: bits 0..18.
        val f = frame(0xA3, "00 07 ff ff 00 00 00")
        assertThat(RunstarR6Handler.decodeFinalResult(f)!!.grams).isEqualTo(0x07FFFF)
    }

    @Test
    fun `encodes the HO-36 user frame Fitdays sends for a female profile`() {
        // Verbatim from an HCI snoop of Fitdays with a made-up guest profile:
        // female, born 1995-01-01 (31 at capture time), 165 cm.
        val captured = hex("02 10 00 ba 6a c1 75 16 01 4a 00 00 00 00 a5 00 00 1f 2f 0e")

        val payload = RunstarR6Handler.encodeHoffenUserInfo(
            epochSeconds = 0x6AC17516L, heightCm = 165, age = 31, isMale = false
        )

        assertThat(payload).isEqualTo(captured.copyOfRange(4, 19))
        assertThat(RunstarR6Handler.isChecksumValid(captured)).isTrue()
    }

    @Test
    fun `encodes the HO-36 user frame Fitdays sends for a male profile`() {
        // Same capture session, made-up guest profile: male, born 1960-01-01 (66), 190 cm.
        // Only time, height and the sex/age byte differ from the female frame.
        val captured = hex("02 10 00 ba 6a c1 75 ea 01 4a 00 00 00 00 be 00 00 c2 2f 1e")

        val payload = RunstarR6Handler.encodeHoffenUserInfo(
            epochSeconds = 0x6AC175EAL, heightCm = 190, age = 66, isMale = true
        )

        assertThat(payload).isEqualTo(captured.copyOfRange(4, 19))
        assertThat(RunstarR6Handler.isChecksumValid(captured)).isTrue()
    }

    @Test
    fun `decodes the frame captured from a Fitdays FG2202WB`() {
        // Verbatim from a weigh-in of a 10.3 kg object (the display read 10.3). The FG2202WB
        // sends 0x25 where the R6 sends status 00, and a 0x1A length: the frame is the first
        // of two parts, the second is not decoded. The object conducts nothing, so the
        // impedance field holds a meaningless 50 Ω.
        val result = RunstarR6Handler.decodeFinalResult(
            hex("05 1a 00 a3 25 00 28 64 00 00 32 00 0a 00 00 00 0a 00 00 1a")
        )

        assertThat(result).isNotNull()
        assertThat(result!!.status).isEqualTo(0x25)
        assertThat(result.grams).isEqualTo(10340)
        assertThat(result.heartRate).isEqualTo(0)
        assertThat(result.impedanceOhm).isEqualTo(50)
    }

    @Test
    fun `publishes the FG2202WB weight without its 50 ohm placeholder impedance`() {
        val (handler, callbacks) = connected()

        handler.handleNotification(CHAR_RESULT, hex("05 1a 00 a3 25 00 28 64 00 00 32 00 0a 00 00 00 0a 00 00 1a"))

        val measurement = callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isWithin(1e-3f).of(10.34f)
        assertThat(measurement[MeasurementType.IMPEDANCE]).isNull()
        assertThat(measurement[MeasurementType.BODY_FAT]).isNull()
    }

    @Test
    fun `publishes an impedance inside the plausible range`() {
        val (handler, callbacks) = connected()

        // The physical R6 capture above: 66.35 kg, 75 bpm, 581 Ω.
        handler.handleNotification(CHAR_RESULT, hex("f3 08 00 a3 00 01 03 2e 4b 02 45 00 00 00 00 00 00 00 00 07"))

        val measurement = callbacks.published.single()
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isWithin(1e-4f).of(581f)
        assertThat(measurement[MeasurementType.BODY_FAT]).isNotNull()
    }

    @Test
    fun `drops an impedance just outside the plausible range`() {
        listOf(RunstarR6Handler.IMPEDANCE_MIN - 1, RunstarR6Handler.IMPEDANCE_MAX + 1).forEach { ohm ->
            val (handler, callbacks) = connected()

            handler.handleNotification(
                CHAR_RESULT,
                frame(0xA3, "00 01 4c 08 4a %02x %02x".format(ohm shr 8, ohm and 0xFF))
            )

            val measurement = callbacks.published.single()
            assertThat(measurement[MeasurementType.WEIGHT]).isNotNull()
            assertThat(measurement[MeasurementType.IMPEDANCE]).isNull()
        }
    }

    @Test
    fun `surfaces a non-zero status instead of hiding it`() {
        // The status byte is reported, not used to reject the frame: its non-zero values
        // have never been observed, so discarding a weigh-in on it would be a guess.
        val result = RunstarR6Handler.decodeFinalResult(frame(0xA3, "07 01 4c 08 4a 01 f4"))

        assertThat(result).isNotNull()
        assertThat(result!!.status).isEqualTo(0x07)
        assertThat(result.grams).isEqualTo(85000)
    }

    @Test
    fun `reads zero heart rate and impedance as zero rather than failing`() {
        // The scale sends zeros when it could not measure them; the handler treats those as
        // "absent" rather than the decoder rejecting the whole frame.
        val result = RunstarR6Handler.decodeFinalResult(frame(0xA3, "00 01 4c 08 00 00 00"))

        assertThat(result).isNotNull()
        assertThat(result!!.grams).isEqualTo(85000)
        assertThat(result.heartRate).isEqualTo(0)
        assertThat(result.impedanceOhm).isEqualTo(0)
    }

    @Test
    fun `rejects a frame whose checksum does not match`() {
        val corrupted = frame(0xA3, "00 01 4c 08 4a 01 f4").also { it[19] = 0x1E }
        // Guard against the corruption accidentally being the correct checksum.
        assertThat(corrupted[19].toInt() and 0xFF)
            .isNotEqualTo(RunstarR6Handler.computeChecksum(corrupted))

        assertThat(RunstarR6Handler.decodeFinalResult(corrupted)).isNull()
    }

    @Test
    fun `rejects frames of the wrong length`() {
        assertThat(RunstarR6Handler.decodeFinalResult(ByteArray(19))).isNull()
        assertThat(RunstarR6Handler.decodeFinalResult(ByteArray(21))).isNull()
        assertThat(RunstarR6Handler.decodeFinalResult(ByteArray(0))).isNull()
    }

    @Test
    fun `does not read a live weight frame as a final result`() {
        // A2 live frame, state 0x01 measuring, weight at bytes 6..8 — different offsets, so
        // decoding it as A3 would silently yield a wrong weight.
        assertThat(RunstarR6Handler.decodeFinalResult(frame(0xA2, "01 00 01 4c 08"))).isNull()
        // History (A4) and device info (A1) are not final results either.
        assertThat(RunstarR6Handler.decodeFinalResult(frame(0xA4, "68 00 00 00 00 01 4c 08"))).isNull()
        assertThat(RunstarR6Handler.decodeFinalResult(frame(0xA1, "00 01 02 03"))).isNull()
    }

    @Test
    fun `rejects a frame whose third byte is not zero`() {
        val f = frame(0xA3, "00 01 4c 08 4a 01 f4").also { it[2] = 0x01 }
        assertThat(RunstarR6Handler.decodeFinalResult(f)).isNull()
    }

    @Test
    fun `checksum is the low five bits of the sum over bytes 3 to 18`() {
        val f = frame(0xA3, "00 01 4c 08 4a 01 f4")
        val expected = (3..18).sumOf { f[it].toInt() and 0xFF } and 0x1F

        assertThat(RunstarR6Handler.computeChecksum(f)).isEqualTo(expected)
        assertThat(RunstarR6Handler.computeChecksum(f)).isAtMost(0x1F)
        assertThat(RunstarR6Handler.isChecksumValid(f)).isTrue()
    }

    // --- Harness ---------------------------------------------------------------

    private val CHAR_RESULT: UUID = uuid16(0xFFB3)

    /** A connected handler and what it publishes; writes (acks) are discarded. */
    private fun connected(): Pair<RunstarR6Handler, CapturingCallbacks> {
        val user = ScaleUser(
            id = 7,
            birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -31); add(Calendar.DAY_OF_YEAR, -1) }.time,
            bodyHeight = 165f,
            gender = GenderType.FEMALE,
        )
        val handler = RunstarR6Handler()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NullTransport(),
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        handler.handleConnected(user)
        return handler to callbacks
    }

    private class NullTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
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
