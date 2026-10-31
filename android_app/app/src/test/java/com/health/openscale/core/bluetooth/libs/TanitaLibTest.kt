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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class TanitaLibTest {
    // Synthetic record: 2000-01-02 00:00:01, 75 kg, 21.3% fat. No personal fields.
    private val record = hex("016a3200016a330000026a38006a3e06d66a3b0060211d4c605600f5602200d5")

    @Test
    fun `measurement command matches observed nonpersonal wire vector`() {
        assertThat(TanitaLib.command(TanitaLib.MEASURE)).isEqualTo(hex("0004201000cb"))
        assertThat(TanitaLib.command(TanitaLib.RETRIEVE_MEASUREMENT, byteArrayOf(1)))
            .isEqualTo(hex("0004301001ba"))
    }

    @Test
    fun `parses observed successful verification acknowledgement`() {
        val response = TanitaLib.parseResponse(hex("00058003000077"))!!
        assertThat(response.command).isEqualTo(0x8003)
        assertThat(response.status).isEqualTo(0)
        assertThat(response.payload).isEqualTo(byteArrayOf(0))
    }

    @Test
    fun `date command uses day count and doubled local seconds`() {
        val message = TanitaLib.writeDate(LocalDateTime.of(2000, 1, 2, 0, 0, 1))
        assertThat(message.copyOfRange(4, message.lastIndex)).isEqualTo(hex("6a3200016a33000002"))
        assertThat(message.size).isEqualTo(14)
    }

    @Test
    fun `synthetic client identifier fragments have byte offsets and final index`() {
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000000")
        val message = TanitaLib.verifyUuid(uuid)
        val fragments = TanitaLib.fragment(message)
        assertThat(fragments.map { it.size }).containsExactly(20, 20, 13).inOrder()
        assertThat(fragments.map { it.copyOf(4).toList() }).containsExactly(
            hex("00000210").toList(), hex("00100210").toList(), hex("00200209").toList(),
        ).inOrder()
        assertThat(assemble(fragments)).isEqualTo(message)
    }

    @Test
    fun `registration and verification are distinct commands`() {
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000000")
        assertThat(TanitaLib.registerUuid(uuid)[3].toInt()).isEqualTo(2)
        assertThat(TanitaLib.verifyUuid(uuid)[3].toInt()).isEqualTo(3)
    }

    @Test
    fun `large message offsets do not wrap after 256 bytes`() {
        val message = TanitaLib.command(TanitaLib.EXCHANGE_USER_INFO, ByteArray(332) { (it % 251).toByte() })
        val fragments = TanitaLib.fragment(message)
        assertThat(fragments).hasSize(22)
        assertThat(fragments[16].copyOf(4)).isEqualTo(hex("01001510"))
        assertThat(assemble(fragments)).isEqualTo(message)
    }

    @Test
    fun `notification padding is outside the transport payload`() {
        val message = hex("00058003000077")
        assertThat(assemble(TanitaLib.fragment(message).map { it.copyOf(20) })).isEqualTo(message)
    }

    @Test
    fun `incomplete messages produce no result`() {
        val fragments = TanitaLib.fragment(response(record))
        val assembler = TanitaLib.Assembler()
        for (fragment in fragments.dropLast(1)) assertThat(assembler.add(fragment)).isNull()
    }

    @Test
    fun `out of order fragments are dropped and next start recovers`() {
        val message = response(record)
        val fragments = TanitaLib.fragment(message)
        val assembler = TanitaLib.Assembler()
        assertThat(assembler.add(fragments[1])).isNull()
        assertThat(assembler.add(fragments[0])).isNull()
        assertThat(assembler.add(fragments[2])).isNull()
        var result: ByteArray? = null
        for (fragment in fragments) result = assembler.add(fragment)
        assertThat(result).isEqualTo(message)
    }

    @Test
    fun `rejects truncated and oversized transport then recovers`() {
        val assembler = TanitaLib.Assembler()
        assertThat(assembler.add(hex("000000"))).isNull()
        assertThat(assembler.add(hex("000000070005"))).isNull()
        assertThat(assembler.add(hex("0000ff02ffff"))).isNull()
        val message = hex("00058003000077")
        assertThat(assembler.add(TanitaLib.fragment(message).single())).isEqualTo(message)
    }

    @Test
    fun `rejects a changed fragment count`() {
        val fragments = TanitaLib.fragment(response(record))
        val assembler = TanitaLib.Assembler()
        assertThat(assembler.add(fragments[0])).isNull()
        val changed = fragments[1].copyOf().also { it[2] = 9 }
        assertThat(assembler.add(changed)).isNull()
        assertThat(assembler.add(fragments.last())).isNull()
    }

    @Test
    fun `corrupt checksum and length are rejected`() {
        val message = response(record)
        val corruptChecksum = message.copyOf().also { it[it.lastIndex] = 0 }
        assertThat(TanitaLib.parseResponse(corruptChecksum)).isNull()
        val fragments = TanitaLib.fragment(message).map { it.copyOf() }
        fragments.last()[fragments.last().lastIndex] = 0
        assertThat(assemble(fragments)).isNull()
        val corruptLength = message.copyOf().also { it[1] = 1 }
        assertThat(TanitaLib.parseResponse(corruptLength)).isNull()
    }

    @Test
    fun `decodes tagged weight fat and measurement timestamp`() {
        val reading = decode(record)!!
        assertThat(reading.weightKg).isEqualTo(75f)
        assertThat(reading.bodyFatPercent).isEqualTo(21.3f)
        assertThat(reading.measuredAt).isEqualTo(LocalDateTime.of(2000, 1, 2, 0, 0, 1))
    }

    @Test
    fun `metric values need not occur in fixed positions`() {
        val reading = decode(hex("01602200d560211d4c"))!!
        assertThat(reading.weightKg).isEqualTo(75f)
        assertThat(reading.bodyFatPercent).isEqualTo(21.3f)
    }

    @Test
    fun `skips a length prefixed extension without interpreting its contents`() {
        val reading = decode(hex("017e21066021ffff602260211d4c602200d5"))!!
        assertThat(reading.weightKg).isEqualTo(75f)
        assertThat(reading.bodyFatPercent).isEqualTo(21.3f)
    }

    @Test
    fun `never searches inside unknown field contents for metric tags`() {
        assertThat(decode(hex("01aaaa60211d4c602200d5"))).isNull()
        val reading = decode(hex("0160211d4caaaa602200d5"))!!
        assertThat(reading.weightKg).isEqualTo(75f)
        assertThat(reading.bodyFatPercent).isNull()
    }

    @Test
    fun `sentinels leave unreported metrics unset`() {
        assertThat(decode(hex("016021ffff602200d5"))).isNull()
        assertThat(decode(hex("0160211d4c6022ffff"))!!.bodyFatPercent).isNull()
    }

    @Test
    fun `rejects truncated fields unsuccessful results and unsupported units`() {
        assertThat(decode(hex("0160211d4c602200"))).isNull()
        assertThat(decode(hex("017e21066021"))).isNull()
        assertThat(decode(record, unit = 2)).isNull()
        val response = TanitaLib.parseResponse(response(record, status = 1))!!
        assertThat(TanitaLib.parseMeasurement(response, TanitaLib.UNIT_KG)).isNull()
    }

    private val profile = hex("6a3200016a330000027e220a546573746572000000006a3c80006a37006a38006a3e06d66a3b00604f00006a15000000016a1302")

    @Test
    fun `reads existing profile nickname and wire units`() {
        val parsed = TanitaLib.parseProfile(TanitaLib.Response(0x9000, 0, profile))!!
        assertThat(parsed.nickname).isEqualTo("Tester")
        assertThat(parsed.unit).isEqualTo(TanitaLib.UNIT_KG)
    }

    @Test
    fun `selecting existing profile preserves body composition parameters`() {
        val parsed = TanitaLib.parseProfile(TanitaLib.Response(0x9000, 0, profile))!!
        val command = TanitaLib.selectExistingProfile(2, parsed, LocalDateTime.of(2001, 2, 3, 4, 5, 6))
        assertThat(command[4]).isEqualTo(2.toByte())
        val exchanged = TanitaLib.parseProfile(TanitaLib.Response(0x9002, 0, command.copyOfRange(5, command.lastIndex)))!!
        for (tag in setOf(0x6a3c, 0x6a37, 0x6a38, 0x6a3e, 0x6a3b, 0x604f, 0x6a15, 0x6a13, 0x7e22)) {
            assertThat(exchanged.fields[tag]).isEqualTo(parsed.fields[tag])
        }
        assertThat(exchanged.fields[0x6a32]).isNotEqualTo(parsed.fields[0x6a32])
        assertThat(exchanged.nickname).isEqualTo("Tester")
    }

    @Test
    fun `incomplete and unknown profile fields are rejected before writing`() {
        assertThat(TanitaLib.parseProfile(TanitaLib.Response(0x9000, 0, profile.copyOf(10)))).isNull()
        assertThat(TanitaLib.parseProfile(TanitaLib.Response(0x9000, 0, profile + hex("aaaa0000")))).isNull()
        assertThat(TanitaLib.parseProfile(TanitaLib.Response(0x9000, 1, profile))).isNull()
    }

    @Test
    fun `reads every known metric after weight and body fat instead of stopping early`() {
        val out = java.io.ByteArrayOutputStream()
        out.write(hex("016a3200016a33000002"))
        for (metric in TanitaLib.Metric.entries) {
            val encoding = metric.encoding
            val size = encoding.width.takeIf { it > 0 } ?: 1
            val raw = when {
                metric == TanitaLib.Metric.WEIGHT -> 7500
                encoding == TanitaLib.Encoding.SIGNED_SCORE -> 0x82
                encoding == TanitaLib.Encoding.SIGNED_TENTHS -> 0xff38
                encoding == TanitaLib.Encoding.SIGNED_HUNDREDTHS -> 0xff9c
                encoding == TanitaLib.Encoding.VARIABLE_INTEGER -> 7
                else -> 100
            }
            out.write(metric.tag ushr 8)
            out.write(metric.tag)
            if (metric.tag in 0x7e21..0x7e7e) out.write(size)
            for (byteIndex in size - 1 downTo 0) out.write(raw ushr (byteIndex * 8))
        }
        val reading = decode(out.toByteArray())!!
        assertThat(reading.metrics).hasSize(TanitaLib.Metric.entries.size)
        assertThat(reading.metrics[TanitaLib.Metric.MUSCLE_MASS]).isEqualTo(1f)
        assertThat(reading.metrics[TanitaLib.Metric.WATER_PERCENT]).isEqualTo(10f)
        assertThat(reading.metrics[TanitaLib.Metric.LEFT_ARM_MUSCLE_SCORE]).isEqualTo(-2f)
        assertThat(reading.metrics[TanitaLib.Metric.LEFT_ARM_50KHZ_REACTANCE]).isEqualTo(-20f)
        assertThat(reading.metrics[TanitaLib.Metric.OBESITY_PERCENT]).isEqualTo(-1f)
        assertThat(reading.physiqueRating).isEqualTo(7)
        assertThat(reading.measuredAt).isEqualTo(LocalDateTime.of(2000, 1, 2, 0, 0, 1))
        assertThat(decode(out.toByteArray().dropLast(1).toByteArray())).isNull()
    }

    @Test
    fun `omits unsigned sentinels but preserves signed and zero measurements`() {
        val reading = decode(record + hex("6029ffff605bff605cff6027000060248060478261440000613cffff"))!!
        assertThat(reading.metrics).doesNotContainKey(TanitaLib.Metric.BONE_MASS)
        assertThat(reading.metrics).doesNotContainKey(TanitaLib.Metric.MUSCLE_QUALITY)
        assertThat(reading.metrics).doesNotContainKey(TanitaLib.Metric.LEFT_ARM_MUSCLE_QUALITY)
        assertThat(reading.metrics[TanitaLib.Metric.BMR]).isEqualTo(0f)
        assertThat(reading.metrics[TanitaLib.Metric.MUSCLE_SCORE]).isEqualTo(0f)
        assertThat(reading.metrics[TanitaLib.Metric.LEFT_ARM_MUSCLE_SCORE]).isEqualTo(-2f)
        assertThat(reading.metrics[TanitaLib.Metric.LEFT_ARM_50KHZ_REACTANCE]).isEqualTo(0f)
        assertThat(reading.metrics[TanitaLib.Metric.RIGHT_ARM_50KHZ_REACTANCE]).isEqualTo(-0.1f)
    }

    @Test
    fun `physique rating uses the vendor nine category lookup and requires both inputs`() {
        val cases = listOf(Triple(4, -2, 1), Triple(5, 0, 2), Triple(4, 4, 3),
            Triple(2, -4, 4), Triple(3, 1, 5), Triple(2, 2, 6),
            Triple(0, -3, 7), Triple(1, -1, 8), Triple(1, 3, 9))
        for ((fat, muscle, rating) in cases) {
            val reading = TanitaLib.Reading(75f, 21.3f, null, mapOf(
                TanitaLib.Metric.FAT_RATING to fat.toFloat(),
                TanitaLib.Metric.MUSCLE_SCORE to muscle.toFloat()))
            assertThat(reading.physiqueRating).isEqualTo(rating)
        }
        assertThat(decode(record)!!.physiqueRating).isNull()
        assertThat(decode(record + hex("607006602400"))!!.physiqueRating).isNull()
        assertThat(decode(record + hex("607004602405"))!!.physiqueRating).isNull()
    }

    @Test
    fun `rejects duplicated fields malformed extensions and a contradictory record unit`() {
        assertThat(decode(record + hex("60211d4c"))).isNull()
        assertThat(decode(record + hex("7e2a0201"))).isNull()
        assertThat(decode(record + hex("7e2a0000"))).isNull()
        assertThat(decode(record + hex("6a1500000002"))).isNull()
        assertThat(decode(hex("0160210000602200d5"))).isNull()
    }

    private fun decode(data: ByteArray, unit: Int = TanitaLib.UNIT_KG): TanitaLib.Reading? =
        TanitaLib.parseMeasurement(TanitaLib.parseResponse(response(data))!!, unit)

    private fun assemble(fragments: List<ByteArray>): ByteArray? {
        val assembler = TanitaLib.Assembler()
        var result: ByteArray? = null
        for (fragment in fragments) result = assembler.add(fragment)
        return result
    }

    private fun response(payload: ByteArray, status: Int = 0): ByteArray {
        val size = payload.size + 6
        val bytes = ByteArray(size)
        bytes[0] = ((size - 2) ushr 8).toByte()
        bytes[1] = (size - 2).toByte()
        bytes[2] = 0xb0.toByte()
        bytes[3] = 0x10
        bytes[4] = status.toByte()
        payload.copyInto(bytes, 5)
        bytes[bytes.lastIndex] = (0xff - bytes.sumOf { it.toInt() and 0xff }).toByte()
        return bytes
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
