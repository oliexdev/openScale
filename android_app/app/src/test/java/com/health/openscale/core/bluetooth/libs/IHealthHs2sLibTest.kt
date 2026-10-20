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
import com.health.openscale.core.bluetooth.libs.IHealthHs2sLib.Variant
import org.junit.Test
import java.util.UUID

/**
 * Fixtures are real traffic:
 * - HS2S Pro handshake: iHealth MyVitals logcat (MAC 00:4D:32:31:32:CE), where the app logs K,
 *   R1 and its FC answer.
 * - Wyze Scale (WHSCL1): HCI snoop logs attached to openScale issue #853 (188.7 lb weighing;
 *   the Wyze app showed 85.6 kg, fat 21.4 %, muscle 139.3 lb, bone 9.0 lb, water 57.6 %,
 *   protein 16.2 %, visceral fat 8, BMR 1823).
 */
class IHealthHs2sLibTest {

    companion object {
        // HS2S Pro: FB payload, the R1 the app sent, the K it logged and the FC it sent.
        private const val PRO_FB = "22437d54503b24736c64766c7557527449117c719637c3e4072cef6d1f31869040372423004d323132ce00000000000000000000"
        private const val PRO_R1 = "025b5e4a3f113d640c5e7960035b4b0b"
        private const val PRO_K = "9094aa138f9fb6e6cef3f12f9a97040a"
        private const val PRO_FC = "33d2120f23ab2c754aac604ff8c67c2442b96388"

        // WHSCL1 frames, as on the wire.
        private const val WYZE_FA_FRAME = "b0140001a9fa63696737684e4977516e3939596476765e"
        private const val WYZE_FB_FRAME = "a0380000a9fb6453556c574d325f333b2b23526c592ec2a8dcfee944f5e6c94efac06aeb1a7ba49f24df2caa8e408b1800000000000000000000e6"
        private const val WYZE_RESULT_FRAME = "a0280000a942000121700284022d021301f900011fc16274a48e01000600d6027829024000a2000207aa41"
        private const val WYZE_STABLE_FRAME = "a0070000a9410021707b"
        private const val WYZE_USER_RECORD = "14421da2303f0a963eb39374826694f36274a4811dd8011fc1010000"
    }

    @Test
    fun `service and characteristic UUIDs are the ASCII protocol names`() {
        assertThat(Variant.WYZE_WHSCL1.service).isEqualTo(UUID.fromString("636f6d2e-6a69-7561-6e2e-424653563231"))
        assertThat(Variant.WYZE_WHSCL1.notifyChar).isEqualTo(UUID.fromString("7365642e-6a69-7561-6e2e-424653563231"))
        assertThat(Variant.WYZE_WHSCL1.writeChar).isEqualTo(UUID.fromString("7265632e-6a69-7561-6e2e-424653563231"))
        assertThat(Variant.HS2S_PRO.service).isEqualTo(UUID.fromString("636f6d2e-6a69-7561-6e2e-485332533032"))
        assertThat(Variant.HS2S.service).isEqualTo(UUID.fromString("636f6d2e-6a69-7561-6e2e-424653563232"))
        assertThat(IHealthHs2sLib.variantForService(Variant.HS2S_PRO.service)).isEqualTo(Variant.HS2S_PRO)
    }

    @Test
    fun `HS2S Pro session key derives from the MAC seed and model key`() {
        val challenge = IHealthHs2sLib.parseAuthChallenge(hex(PRO_FB))!!
        assertThat(toHex(challenge.seed)).isEqualTo("004d323132ce00000000000000000000")
        val key = IHealthHs2sLib.deriveKey(challenge.seed, Variant.HS2S_PRO.masterKey!!)
        assertThat(toHex(key)).isEqualTo(PRO_K)
    }

    @Test
    fun `HS2S Pro challenge answer matches the vendor app byte for byte`() {
        val challenge = IHealthHs2sLib.parseAuthChallenge(hex(PRO_FB))!!
        val key = IHealthHs2sLib.deriveKey(challenge.seed, Variant.HS2S_PRO.masterKey!!)
        val answer = IHealthHs2sLib.answerChallenge(challenge, hex(PRO_R1), key)
        assertThat(answer).isNotNull()
        assertThat(toHex(answer!!)).isEqualTo(PRO_FC)
    }

    @Test
    fun `wrong key is rejected instead of answering`() {
        val challenge = IHealthHs2sLib.parseAuthChallenge(hex(PRO_FB))!!
        val wrong = IHealthHs2sLib.deriveKey(challenge.seed, Variant.HS2S.masterKey!!)
        assertThat(IHealthHs2sLib.answerChallenge(challenge, hex(PRO_R1), wrong)).isNull()
    }

    @Test
    fun `xxtea round trips with the length word`() {
        val key = hex("00112233445566778899aabbccddeeff")
        val data = "abcdefghijklmnop".toByteArray()
        val enc = IHealthHs2sLib.xxteaEncrypt(data, key)
        assertThat(enc).hasLength(20)
        assertThat(IHealthHs2sLib.xxteaDecrypt(enc, key)).isEqualTo(data)
    }

    @Test
    fun `frame builder reproduces the captured FA frame`() {
        val r1 = "cig7hNIwQn99Ydvv".toByteArray()
        val frame = IHealthHs2sLib.buildFrame(1, IHealthHs2sLib.CMD_AUTH_R1, r1)
        assertThat(toHex(frame)).isEqualTo(WYZE_FA_FRAME)
        assertThat(IHealthHs2sLib.chunk(frame).map { it.size }).containsExactly(20, 3).inOrder()
    }

    @Test
    fun `assembler joins chunks and checks sums`() {
        val wire = hex(WYZE_FB_FRAME)
        val asm = IHealthHs2sLib.FrameAssembler()
        val got = IHealthHs2sLib.chunk(wire).flatMap { asm.feed(it) }
        assertThat(got).hasSize(1)
        assertThat(got[0].cmd).isEqualTo(IHealthHs2sLib.CMD_AUTH_FB)
        assertThat(got[0].payload).hasLength(52)

        val corrupt = wire.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertThat(asm.feed(corrupt)).isEmpty()
        assertThat(asm.badFrames).isEqualTo(1)
    }

    @Test
    fun `WHSCL1 result record decodes to the Wyze app values`() {
        val frame = IHealthHs2sLib.FrameAssembler().feed(hex(WYZE_RESULT_FRAME)).single()
        assertThat(frame.cmd).isEqualTo(IHealthHs2sLib.CMD_RESULT)
        val r = IHealthHs2sLib.parseResult(Variant.WYZE_WHSCL1, frame.payload)!!
        assertThat(r.weightKg).isWithin(0.001f).of(85.60f)
        assertThat(r.impedances).containsExactly(644, 557, 531, 505).inOrder()
        assertThat(r.gender).isEqualTo(1)
        assertThat(r.age).isEqualTo(31)
        assertThat(r.heightCm).isEqualTo(193)
        assertThat(r.epochSeconds).isEqualTo(0x6274a48eL)
        assertThat(r.timeValid).isTrue()
        assertThat(r.fatPercent!!).isWithin(0.001f).of(21.4f)
        assertThat(r.muscleKg!!).isWithin(0.001f).of(63.2f)
        assertThat(r.boneKg!!).isWithin(0.001f).of(4.1f)
        assertThat(r.waterPercent!!).isWithin(0.001f).of(57.6f)
        assertThat(r.proteinPercent!!).isWithin(0.001f).of(16.2f)
        assertThat(r.visceralFat).isNull()
    }

    @Test
    fun `offline batch uses the record layout without status byte`() {
        val result = IHealthHs2sLib.FrameAssembler().feed(hex(WYZE_RESULT_FRAME)).single().payload
        val record = result.copyOfRange(1, result.size)
        val payload = byteArrayOf(0, 0, 1) + record + byteArrayOf(0)
        val batch = IHealthHs2sLib.parseOfflineData(Variant.WYZE_WHSCL1, payload)
        assertThat(batch.moreAvailable).isFalse()
        assertThat(batch.records.single().weightKg).isWithin(0.001f).of(85.60f)

        val more = IHealthHs2sLib.parseOfflineData(Variant.WYZE_WHSCL1, byteArrayOf(0, 0, 1) + record + byteArrayOf(1))
        assertThat(more.moreAvailable).isTrue()
        assertThat(IHealthHs2sLib.parseOfflineData(Variant.WYZE_WHSCL1, hex("00000000")).records).isEmpty()
    }

    @Test
    fun `stable weight frame carries kg times 100`() {
        val frame = IHealthHs2sLib.FrameAssembler().feed(hex(WYZE_STABLE_FRAME)).single()
        assertThat(frame.cmd).isEqualTo(IHealthHs2sLib.CMD_STABLE_WEIGHT)
        assertThat(toHex(frame.payload)).isEqualTo("002170")
    }

    @Test
    fun `WHSCL1 user record matches what the Wyze app sent`() {
        val id = hex(WYZE_USER_RECORD).copyOf(16)
        val rec = IHealthHs2sLib.buildUserRecord(
            Variant.WYZE_WHSCL1, id, epochSeconds = 0x6274a481L, weightKg = 76.40f,
            male = true, age = 31, heightCm = 193
        )
        assertThat(toHex(rec)).isEqualTo(WYZE_USER_RECORD)
        val hs2s = IHealthHs2sLib.buildUserRecord(Variant.HS2S, id, 0x6274a481L, 76.40f, true, 31, 193)
        assertThat(hs2s).hasLength(27)
    }

    @Test
    fun `Wyze app formulas reproduce its BMR and visceral fat`() {
        assertThat(IHealthHs2sLib.wyzeBmr(85.6f, 21.4f)).isWithin(0.5f).of(1823f)
        assertThat(IHealthHs2sLib.wyzeVisceralFat(85.6f, 21.4f, 193f)).isEqualTo(8f)
        assertThat(IHealthHs2sLib.wyzeVisceralFat(76.42f, 15.1f, 193f)).isEqualTo(7f)
        assertThat(IHealthHs2sLib.wyzeVisceralFat(94.91f, 29.5f, 193f)).isEqualTo(9f)
    }

    @Test
    fun `user id fits the 16 byte field`() {
        assertThat(IHealthHs2sLib.userIdFor(3)).hasLength(16)
        assertThat(String(IHealthHs2sLib.userIdFor(3))).isEqualTo("openScale0000003")
    }

    @Test
    fun `hex key parsing`() {
        assertThat(IHealthHs2sLib.parseHexKey("00112233445566778899AABBCCDDEEFF")).hasLength(16)
        assertThat(IHealthHs2sLib.parseHexKey("0011")).isNull()
        assertThat(IHealthHs2sLib.parseHexKey("zz112233445566778899aabbccddeeff")).isNull()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
