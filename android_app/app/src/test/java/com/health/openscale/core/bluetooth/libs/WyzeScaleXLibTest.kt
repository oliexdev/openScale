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
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Test

/**
 * Fixtures were produced with the reference Python implementation (JesusFreke/wyze_scale_tool
 * and the `xxtea` package it uses) over a fixed key pair, so every frame here is what that tool
 * would have written or accepted; `ab043705808c5d57` is the published XXTEA test vector.
 */
class WyzeScaleXLibTest {

    companion object {
        private const val PRIVATE_KEY = 0x12345678L
        private const val SCALE_PUBLIC_KEY = 0xa457cad6L
        private const val SESSION_KEY = "61343464373366360000000000000000"

        // Key exchange, both directions: frame · F0 00 08 · public key LE32 · 00 00 00 00.
        private const val DH_HELLO = "00f00008bbc0c8d200000000"
        private const val DH_REPLY = "40f00008d6ca57a400000000"

        // Sealed envelope: hdr(1x host / 5x scale) 01 00 len · XXTEA(8-byte blocks of the
        // zero-padded message). Host message: 16 00 lenLE16 cmd A8 payload;
        // scale message: 22 01 lenLE16 cmd A8 payload, len counted from cmd.

        private const val SYNC_PLAIN = "1600070001a80000006501"
        private const val SYNC_FRAME = "1101000b44a0b5e34cb732c3ca1ff2b9b1c0e24f"
        private const val SYNC_OK_FRAME = "500100077ac789861ce94b78"

        private const val CUR_USER_PLAIN = "16001b000ea86f70656e5363616c6530303030303033aa1e0129b400000002"
        private const val CUR_USER_FRAME = "1201001fb918d407f4625194f0df63962daaf7ef1142f1d53c11feea91a1c4c2a92a8d8f"

        // User record (25 bytes): id[16] · weight kg×100 LE16 · sex · age · height cm ·
        // athlete · weight-only · last impedance Ω×10 LE16.
        // Live weight payload: battery · unit · id[16] · sex age height athlete weightOnly state ·
        // metrics; history payload: ok · epoch LE32 · id[16] · sex age height athlete weightOnly ·
        // metrics. Metrics (21 bytes): weight kg×100 · impedance Ω×10 · fat‰ · muscle kg×10 ·
        // bone kg×10 (u8) · water‰ · protein‰ · LBM kg×10 · visceral (u8) · BMR kcal · body age (u8)
        // · BMI×10, all LE16 unless noted.
        private const val USERLIST_REPLY_FRAME = "51010039d0c151db3338ad4c34ac53d3f173eaa8615fe14a57b35daa2991f46feac9fdb5e1bee10ec4753fd5d9b23845d0405893b462085b1b5df672c59042253215df2c"
        private const val LIVE_FRAME = "520100333d8ff2a6ad1b44fed798072956ba02170d0409591d15390963225452fe14880ec59042253215df2cc59042253215df2cc59042253215df2c"
        private const val FINAL_FRAME = "530100333d8ff2a6ad1b44fed798072956ba02170d0409591d153909584ec45cbffcd0dcecb36d819f3b5638b3988247dc95927fb51e015ca632f8c6"
        private const val HIST_FRAME = "5401003515edbfc0df53f7fd0d06b0318ee3ab495d31653a9c9224cf561caab04fd3c42553d1c76fad7ef891db4fd0e9f8adfe0d53b60964fb0b7f71"
        private const val HIST_WEIGHT_ONLY_PLAIN = "2201310009a801ccbbaa666f70656e5363616c65303030303030330129b40001e11e00000000000000000000000000000000000000"
    }

    private val key = hex(SESSION_KEY)
    private val user = WyzeScaleXLib.UserRecord(
        userId = WyzeScaleXLib.userIdFor(3), weightKg = 78.50f, male = true, age = 41, heightCm = 180, lastImpedanceOhm = 51.2f
    )

    @Test
    fun `xxtea block cipher matches the published test vector and the python library`() {
        assertThat(toHex(WyzeScaleXLib.encryptBlock(ByteArray(8), ByteArray(16)))).isEqualTo("ab043705808c5d57")
        val k = hex("00112233445566778899aabbccddeeff")
        val enc = WyzeScaleXLib.encryptBlock(hex("0102030405060708"), k)
        assertThat(toHex(enc)).isEqualTo("0fd16bca25b8027c")
        assertThat(toHex(WyzeScaleXLib.decryptBlock(enc, k))).isEqualTo("0102030405060708")
    }

    @Test
    fun `key exchange reproduces the reference hello, reply and session key`() {
        assertThat(WyzeScaleXLib.publicKey(PRIVATE_KEY)).isEqualTo(0xd2c8c0bbL)
        assertThat(toHex(WyzeScaleXLib.keyExchangeFrame(0, WyzeScaleXLib.publicKey(PRIVATE_KEY)))).isEqualTo(DH_HELLO)
        assertThat(WyzeScaleXLib.parseKeyExchangeReply(hex(DH_REPLY))).isEqualTo(SCALE_PUBLIC_KEY)
        assertThat(toHex(WyzeScaleXLib.sessionKey(SCALE_PUBLIC_KEY, PRIVATE_KEY))).isEqualTo(SESSION_KEY)
        assertThat(WyzeScaleXLib.parseKeyExchangeReply(hex(SYNC_OK_FRAME))).isNull()
        assertThat(WyzeScaleXLib.parseKeyExchangeReply(hex("40f00008d6ca57a4000000"))).isNull()
    }

    @Test
    fun `sealed commands match the reference frames byte for byte`() {
        val sync = WyzeScaleXLib.syncTime(0x65000000L)
        assertThat(toHex(sync)).isEqualTo(SYNC_PLAIN)
        assertThat(toHex(WyzeScaleXLib.seal(1, key, sync))).isEqualTo(SYNC_FRAME)

        val select = WyzeScaleXLib.selectUser(user)
        assertThat(toHex(select)).isEqualTo(CUR_USER_PLAIN)
        assertThat(toHex(WyzeScaleXLib.seal(2, key, select))).isEqualTo(CUR_USER_FRAME)

        assertThat(toHex(WyzeScaleXLib.updateUser(user))).isEqualTo("16001b000aa86f70656e5363616c6530303030303033aa1e0129b400000002")
        assertThat(toHex(WyzeScaleXLib.setUnit(pounds = true))).isEqualTo("1600030004a801")
        assertThat(toHex(WyzeScaleXLib.userList())).isEqualTo("160002000da8")
        assertThat(toHex(WyzeScaleXLib.historyAck())).isEqualTo("1600030009a800")
    }

    @Test
    fun `frame counter wraps at 16 and only touches the low nibble`() {
        assertThat(WyzeScaleXLib.seal(0x1F, key, WyzeScaleXLib.userList())[0]).isEqualTo(0x1F.toByte())
        assertThat(WyzeScaleXLib.keyExchangeFrame(5, 1L)[0]).isEqualTo(5.toByte())
    }

    @Test
    fun `status reply opens and reports success`() {
        val reply = WyzeScaleXLib.parseReply(WyzeScaleXLib.open(key, hex(SYNC_OK_FRAME))!!)
        assertThat(reply).isEqualTo(WyzeScaleXLib.Status(WyzeScaleXLib.CMD_SYNC_TIME, 0))
        assertThat((reply as WyzeScaleXLib.Status).ok).isTrue()
        assertThat(WyzeScaleXLib.parseReply(hex("220103000ea801"))).isEqualTo(WyzeScaleXLib.Status(WyzeScaleXLib.CMD_CURRENT_USER_NEW, 1))
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertThat(WyzeScaleXLib.open(key, hex("1101000b44a0b5e34cb732c3"))).isNull()
        assertThat(WyzeScaleXLib.open(key, hex("5001000744a0b5e34cb732"))).isNull()
        assertThat(WyzeScaleXLib.open(key, hex("5001002044a0b5e34cb732c3"))).isNull()
        assertThat(WyzeScaleXLib.open(hex(SESSION_KEY), hex(SYNC_OK_FRAME))).isNotNull()
        assertThat(WyzeScaleXLib.parseReply(hex("2201030001"))).isNull()
        assertThat(WyzeScaleXLib.parseReply(hex("1600030001a800"))).isNull()
    }

    @Test
    fun `user list decodes every stored profile`() {
        val list = WyzeScaleXLib.parseReply(WyzeScaleXLib.open(key, hex(USERLIST_REPLY_FRAME))!!) as WyzeScaleXLib.UserList
        assertThat(list.users).hasSize(2)
        assertThat(list.users[0]).isEqualTo(user)
        val other = list.users[1]
        assertThat(String(other.userId, Charsets.US_ASCII)).isEqualTo("WyzeAppUser00001")
        assertThat(other.weightKg).isWithin(0.001f).of(60.20f)
        assertThat(other.male).isFalse()
        assertThat(other.age).isEqualTo(29)
        assertThat(other.heightCm).isEqualTo(165)
        assertThat(other.weightOnly).isTrue()
    }

    @Test
    fun `live weight distinguishes settling from final and carries the battery`() {
        val live = WyzeScaleXLib.parseReply(WyzeScaleXLib.open(key, hex(LIVE_FRAME))!!) as WyzeScaleXLib.LiveWeight
        assertThat(live.final).isFalse()
        assertThat(live.batteryPercent).isEqualTo(87)
        assertThat(live.pounds).isTrue()
        assertThat(live.metrics.weightKg).isWithin(0.001f).of(78.12f)
        assertThat(live.metrics.hasBodyComposition).isFalse()

        val final = WyzeScaleXLib.parseReply(WyzeScaleXLib.open(key, hex(FINAL_FRAME))!!) as WyzeScaleXLib.LiveWeight
        assertThat(final.final).isTrue()
        assertThat(final.userId).isEqualTo(WyzeScaleXLib.userIdFor(3))
        val m = final.metrics
        assertThat(m.weightKg).isWithin(0.001f).of(78.50f)
        assertThat(m.impedanceOhm).isWithin(0.001f).of(49.8f)
        assertThat(m.fatPercent).isWithin(0.001f).of(18.2f)
        assertThat(m.muscleKg).isWithin(0.001f).of(59.6f)
        assertThat(m.boneKg).isWithin(0.001f).of(3.2f)
        assertThat(m.waterPercent).isWithin(0.001f).of(58.1f)
        assertThat(m.proteinPercent).isWithin(0.001f).of(17.1f)
        assertThat(m.lbmKg).isWithin(0.001f).of(64.2f)
        assertThat(m.visceralFat).isEqualTo(7)
        assertThat(m.bmrKcal).isEqualTo(1734)
        assertThat(m.bodyAge).isEqualTo(38)
        assertThat(m.bmi).isWithin(0.001f).of(24.2f)
        assertThat(m.hasBodyComposition).isTrue()
    }

    @Test
    fun `history record carries its own timestamp and user`() {
        val h = WyzeScaleXLib.parseReply(WyzeScaleXLib.open(key, hex(HIST_FRAME))!!) as WyzeScaleXLib.History
        assertThat(h.epochSeconds).isEqualTo(0x66aabbccL)
        assertThat(h.userId).isEqualTo(WyzeScaleXLib.userIdFor(3))
        assertThat(h.metrics.weightKg).isWithin(0.001f).of(79.05f)
        assertThat(h.metrics.impedanceOhm).isWithin(0.001f).of(50.5f)
        assertThat(h.metrics.fatPercent).isWithin(0.001f).of(19.0f)
        assertThat(h.metrics.bmrKcal).isEqualTo(1720)

        val weightOnly = WyzeScaleXLib.parseReply(hex(HIST_WEIGHT_ONLY_PLAIN)) as WyzeScaleXLib.History
        assertThat(weightOnly.metrics.weightKg).isWithin(0.001f).of(79.05f)
        assertThat(weightOnly.metrics.hasBodyComposition).isFalse()
    }

    @Test
    fun `user record round trips and clamps the byte fields`() {
        assertThat(WyzeScaleXLib.UserRecord.parse(user.encode())).isEqualTo(user)
        val tall = user.copy(heightCm = 300, age = 300, weightKg = 1000f, lastImpedanceOhm = 7000f).encode()
        assertThat(tall[19].toInt() and 0xFF).isEqualTo(255)
        assertThat(tall[20].toInt() and 0xFF).isEqualTo(255)
        assertThat(WyzeScaleXLib.UserRecord.parse(tall)!!.weightKg).isWithin(0.01f).of(655.35f)
        assertThat(WyzeScaleXLib.UserRecord.parse(tall)!!.lastImpedanceOhm).isWithin(0.001f).of(6553.5f)
        assertThat(WyzeScaleXLib.UserRecord.parse(ByteArray(24))).isNull()
    }

    @Test
    fun `user id fits the 16 byte field`() {
        assertThat(WyzeScaleXLib.userIdFor(3)).hasLength(16)
        assertThat(String(WyzeScaleXLib.userIdFor(3))).isEqualTo("openScale0000003")
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
