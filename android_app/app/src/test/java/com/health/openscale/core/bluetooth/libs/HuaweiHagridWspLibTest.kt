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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HuaweiHagridWspLibTest {

    @Test
    fun `write frames reassemble from notification frames`() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = HuaweiHagridWspLib.buildWriteFrames(payload)
        val accumulator = HuaweiHagridWspLib.FrameAccumulator()

        assertThat(frames).hasSize(3)
        val first = accumulator.ingest(toNotifyFrame(frames[0]))
        val second = accumulator.ingest(toNotifyFrame(frames[1]))
        val third = accumulator.ingest(toNotifyFrame(frames[2]))

        assertThat(first).isNull()
        assertThat(second).isNull()
        assertThat(third).isEqualTo(payload)
    }

    @Test
    fun `auth token validates expected scale response`() {
        val randA = ByteArray(16) { it.toByte() }
        val randB = ByteArray(16) { (it + 16).toByte() }
        val cak = ByteArray(16) { (it + 32).toByte() }

        val token = HuaweiHagridWspLib.buildAuthTokenPayload(randA, randB, cak)
        val expectedResponse = HuaweiHagridWspLib.expectedAuthResponsePayload(randA, randB, cak)

        assertThat(token.copyOfRange(0, 16)).isEqualTo(randB)
        assertThat(token).hasLength(48)
        assertThat(expectedResponse).hasLength(32)
        assertThat(HuaweiHagridWspLib.isValidAuthResponsePayload(expectedResponse, randA, randB, cak))
            .isTrue()
    }

    @Test
    fun `encrypted payload round trips`() {
        val plaintext = "hagrid-user-profile".encodeToByteArray()
        val key = ByteArray(16) { (it + 4).toByte() }
        val iv = ByteArray(16) { (it + 80).toByte() }

        val encrypted = HuaweiHagridWspLib.encryptedPayloadWithIv(plaintext, key, iv)

        assertThat(encrypted.copyOfRange(0, 16)).isEqualTo(iv)
        assertThat(HuaweiHagridWspLib.decryptPayload(encrypted, key)).isEqualTo(plaintext)
    }

    @Test
    fun `realtime payload parses low and high impedance bands`() {
        val parsed = HuaweiHagridWspLib.parseRealtimeMeasurement(realtimePayload())

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(77.32f)
        assertThat(parsed.fatPercent).isWithin(0.0001f).of(18.5f)
        assertThat(parsed.heartRateBpm).isEqualTo(72)
        assertThat(parsed.lowFrequencyImpedanceOhm).containsExactly(500.0, 501.0, 502.0, 503.0, 504.0, 505.0)
        assertThat(parsed.highFrequencyImpedanceOhm).containsExactly(600.0, 601.0, 602.0, 603.0, 604.0, 605.0)
    }

    @Test
    fun `history payload parses timestamp and terminal flag`() {
        val parsed = HuaweiHagridWspLib.parseHistoryMeasurement(historyPayload(completeFlag = 0x00))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.timestamp).isNotNull()
        assertThat(parsed.isHistoryComplete).isTrue()
        assertThat(parsed.historyDedupeKey).isNotEmpty()

        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.time = parsed.timestamp!!
        assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2026)
        assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.JUNE)
        assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(22)
    }

    @Test
    fun `standard weight measurement parses kilograms`() {
        val parsed = HuaweiHagridWspLib.parseStandardWeightMeasurement(
            byteArrayOf(0x00, 0x8C.toByte(), 0x3C)
        )

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.weightKg).isWithin(0.0001f).of(77.5f)
    }

    @Test
    fun `user info payload has fixed Huawei layout`() {
        val payload = HuaweiHagridWspLib.buildUserInfoPayload(
            HuaweiHagridWspLib.HagridUserInfo(
                huid = "openscale:001122334455",
                uid = "u:00000007",
                gender = 1,
                ageYears = 26,
                heightCm = 171,
                weightKg = 83.2f,
            )
        )

        assertThat(payload).hasLength(69)
        assertThat(payload[62].toInt() and 0xFF).isEqualTo(1)
        assertThat(payload[63].toInt() and 0xFF).isEqualTo(26)
        assertThat(payload[64].toInt() and 0xFF).isEqualTo(171)
        assertThat(payload[65].toInt() and 0xFF).isEqualTo(0)
        assertThat(payload[66].toInt() and 0xFF).isEqualTo(0x80)
        assertThat(payload[67].toInt() and 0xFF).isEqualTo(0x20)
    }

    private fun toNotifyFrame(writeFrame: ByteArray): ByteArray {
        val notifyFrame = writeFrame.copyOf()
        notifyFrame[0] = HuaweiHagridWspLib.FRAME_NOTIFY_PLAIN.toByte()
        val crc = HuaweiHagridWspLib.crc16Modbus(notifyFrame.copyOfRange(0, notifyFrame.size - 2))
        notifyFrame[notifyFrame.size - 2] = (crc and 0xFF).toByte()
        notifyFrame[notifyFrame.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return notifyFrame
    }

    private fun realtimePayload(): ByteArray =
        byteArrayOf(
            0x34, 0x1E,
            0xB9.toByte(), 0x00,
            0xEA.toByte(), 0x07, 0x06, 0x16, 0x0F, 0x0E, 0x2A, 0x00,
            0xF4.toByte(), 0x01, 0xF5.toByte(), 0x01, 0xF6.toByte(), 0x01,
            0xF7.toByte(), 0x01, 0xF8.toByte(), 0x01, 0xF9.toByte(), 0x01,
            0x48, 0x00,
            0x58, 0x02, 0x59, 0x02, 0x5A, 0x02,
            0x5B, 0x02, 0x5C, 0x02, 0x5D, 0x02,
        )

    private fun historyPayload(completeFlag: Int): ByteArray =
        realtimePayload() + byteArrayOf(0x01, completeFlag.toByte())
}
