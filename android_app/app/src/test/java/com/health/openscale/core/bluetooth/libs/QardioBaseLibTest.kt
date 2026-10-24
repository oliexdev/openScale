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
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.GregorianCalendar

// Robolectric for the real org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QardioBaseLibTest {

    // --- Commands ---------------------------------------------------------------------------

    @Test
    fun `commands match the official app's byte layout`() {
        assertThat(QardioBaseLib.configMode(true)).isEqualTo(hex("00 00 0a 01"))
        assertThat(QardioBaseLib.configMode(false)).isEqualTo(hex("00 00 0a 00"))
        // Unix seconds, little-endian int32.
        assertThat(QardioBaseLib.setTimestamp(0x6543_2101L)).isEqualTo(hex("00 00 0c 01 21 43 65"))
        assertThat(QardioBaseLib.reset()).isEqualTo(hex("00 00 11"))
        assertThat(QardioBaseLib.selectUser(4242)).isEqualTo(hex("00 00 14 92 10 00 00"))
        assertThat(QardioBaseLib.uniqueId(false, "ABCDEFGHIJKLMNOP"))
            .isEqualTo(hex("00 00 10 00") + "ABCDEFGHIJKLMNOP".toByteArray())
    }

    @Test
    fun `long write splits payload into 15 byte frames with target and offset`() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = QardioBaseLib.longWriteFrames(QardioBaseLib.TARGET_USER_CONFIG, payload)

        assertThat(frames).hasSize(3)
        assertThat(frames[0].copyOfRange(0, 5)).isEqualTo(hex("00 00 04 20 00"))
        assertThat(frames[1].copyOfRange(0, 5)).isEqualTo(hex("00 00 04 20 0f"))
        assertThat(frames[2].copyOfRange(0, 5)).isEqualTo(hex("00 00 04 20 1e"))
        assertThat(frames[2].size).isEqualTo(5 + 10)
        assertThat(frames.flatMap { it.drop(5) }.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `long write carries offsets above 255 in the low nibble of byte 3`() {
        val frames = QardioBaseLib.longWriteFrames(QardioBaseLib.TARGET_USER_CONFIG, ByteArray(300))
        // Frame 18 starts at offset 270 = 0x10E.
        assertThat(frames[18].copyOfRange(3, 5)).isEqualTo(hex("21 0e"))
    }

    @Test
    fun `engineering replies are recognised and the raw sensor stream is ignored`() {
        assertThat(QardioBaseLib.parseEngineeringReply(hex("00 00 04 00")))
            .isEqualTo(QardioBaseLib.EngineeringReply(4, 0))
        assertThat(QardioBaseLib.parseEngineeringReply(hex("00 00 04 fe"))!!.value)
            .isEqualTo(QardioBaseLib.ACK_USERS_FULL)
        // The state echo the B200 sends on every STATE change (here: 6, result ready).
        assertThat(QardioBaseLib.parseEngineeringReply(hex("00 00 05 06")))
            .isEqualTo(QardioBaseLib.EngineeringReply(QardioBaseLib.CMD_STEP_EVENT, 6))
        // Load-cell frame from the B100 engineering stream.
        assertThat(QardioBaseLib.parseEngineeringReply(hex("21 70 28 10"))).isNull()
        assertThat(QardioBaseLib.parseEngineeringReply(hex("00 00 04"))).isNull()
    }

    // --- Result -----------------------------------------------------------------------------

    /*
     * Captured from a QardioBase 2 (B200) on 2026-10-14. The first ten id digits (the scale's own
     * prefix) are blanked, and the profile name and id replaced; the rest is the weigh-in counter
     * and the Unix time of the weigh-in.
     */
    private val identified =
        """{"id":"000000000000041791951128","weight":"87.4","bmi":"29.2","z":"474","fat":"26","tbw":"51",""" +
            """"bmc":"4","mt":"14","sm":"37","algorithm":"0","user":"wedsa5","userid":"1000000001"}"""
    /** The same scale when it matched nobody: weight and impedance only. */
    private val guest = """{"id":"000000000004061791936503","weight":"87.0","z":"484"}"""

    @Test
    fun `identified weigh-in maps exactly the values the scale reports`() {
        val r = QardioBaseLib.parseResult(identified)!!
        assertThat(r.weightKg).isWithin(1e-4f).of(87.4f)
        assertThat(r.impedanceOhm).isEqualTo(474)
        assertThat(r.fatPercent!!).isWithin(1e-4f).of(26f)
        assertThat(r.waterPercent!!).isWithin(1e-4f).of(51f)   // tbw
        assertThat(r.skeletalMusclePercent!!).isWithin(1e-4f).of(37f) // sm, not mt
        assertThat(r.boneKg!!).isWithin(1e-4f).of(4f)          // bmc
        assertThat(r.scaleUserId).isEqualTo(1000000001L)
        assertThat(r.scaleUserName).isEqualTo("wedsa5")
    }

    @Test
    fun `guest weigh-in has weight and impedance only`() {
        val r = QardioBaseLib.parseResult(guest)!!
        assertThat(r.weightKg).isWithin(1e-4f).of(87.0f)
        assertThat(r.impedanceOhm).isEqualTo(484)
        assertThat(r.fatPercent).isNull()
        assertThat(r.waterPercent).isNull()
        assertThat(r.skeletalMusclePercent).isNull()
        assertThat(r.boneKg).isNull()
        assertThat(r.scaleUserId).isNull()
    }

    @Test
    fun `B100 result without id or user fields`() {
        // From LibreBase issue #42's B100 comparison: no id, user or userid.
        val r = QardioBaseLib.parseResult("""{"weight":"76.0","bmi":"19.3","z":"2031","fat":"57","tbw":"31","bmc":"3","mt":"9","sm":"17"}""")!!
        assertThat(r.weightKg).isWithin(1e-4f).of(76.0f)
        assertThat(r.id).isNull()
        assertThat(r.time).isNull()
        assertThat(r.scaleUserId).isNull()
        assertThat(r.impedanceOhm).isNull() // 2031 Ω is outside the barefoot range
    }

    @Test
    fun `weigh-in time comes from the result id`() {
        // 2026-10-14T04:12:08Z and, from the LibreBase capture, 2025-03-29T23:22:09Z.
        assertThat(QardioBaseLib.parseResult(identified)!!.time!!.time).isEqualTo(1_791_951_128_000L)
        assertThat(QardioBaseLib.timeFromResultId("18335650007791743290529")!!.time).isEqualTo(1_743_290_529_000L)
        assertThat(QardioBaseLib.timeFromResultId("r1")).isNull()
        assertThat(QardioBaseLib.timeFromResultId("1234")).isNull()
        assertThat(QardioBaseLib.timeFromResultId("1385308700049999999999")).isNull() // far future
    }

    @Test
    fun `zero or implausible values are not reported`() {
        val r = QardioBaseLib.parseResult("""{"weight":"80.2","z":"0","fat":"0","tbw":"0","sm":"0","bmc":"0"}""")!!
        assertThat(r.impedanceOhm).isNull()
        assertThat(r.fatPercent).isNull()
        assertThat(r.waterPercent).isNull()
        assertThat(r.skeletalMusclePercent).isNull()
        assertThat(r.boneKg).isNull()
        assertThat(QardioBaseLib.parseResult("""{"weight":"80.2","z":"4000"}""")!!.impedanceOhm).isNull()
    }

    @Test
    fun `rejects results without a usable weight`() {
        assertThat(QardioBaseLib.parseResult("""{"weight":"0"}""")).isNull()
        assertThat(QardioBaseLib.parseResult("""{"z":"500"}""")).isNull()
        assertThat(QardioBaseLib.parseResult("not json")).isNull()
        // A failed GATT read is delivered as a single 0x00 byte.
        assertThat(QardioBaseLib.parseResult("\u0000")).isNull()
    }

    @Test
    fun `json null fields read as absent`() {
        val r = QardioBaseLib.parseResult("""{"weight":"70.0","user":null,"userid":null,"id":null}""")!!
        assertThat(r.scaleUserName).isNull()
        assertThat(r.scaleUserId).isNull()
        assertThat(r.id).isNull()
    }

    @Test
    fun `tolerates a trailing NUL byte`() {
        assertThat(QardioBaseLib.parseResult("{\"weight\":\"70.0\"}\u0000")).isNotNull()
    }

    // --- Profiles ---------------------------------------------------------------------------

    private val profile = QardioBaseLib.Profile(
        id = 4242,
        name = "Wedsa5 Example",
        male = true,
        heightCm = 180,
        birthday = GregorianCalendar(1990, 0, 1).time,
        units = 0,
        token = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `add payload carries the profile fields the official app sent`() {
        val json = QardioBaseLib.profileJson(profile)
        // Exact bytes, in the official app's key order (C5704nj).
        assertThat(json).isEqualTo(
            """{"z":"1","haptic":"1","add":{"id":"4242","name":"wedsa5","male":"1","height":"180",""" +
                """"born":"32874","units":"0","goal":"0","rate":"0","token":"${profile.token}","ui":"4"}}"""
        )
        assertThat(JSONObject(json).has("users")).isFalse()
    }

    @Test
    fun `update payload rewrites only the given slot`() {
        val o = JSONObject(QardioBaseLib.profileJson(profile, slotIndex = 1, slotCount = 3))
        val users = o.getJSONArray("users")
        assertThat(users.length()).isEqualTo(3)
        assertThat(users.getJSONObject(0).length()).isEqualTo(0)
        assertThat(users.getJSONObject(1).getString("id")).isEqualTo("4242")
        assertThat(users.getJSONObject(2).length()).isEqualTo(0)
        assertThat(o.has("add")).isFalse()
    }

    @Test
    fun `parses the user list stored on the scale`() {
        assertThat(QardioBaseLib.parseUserIds("""{"users":[{"id":"11"},{"id":"4242"},{}]}"""))
            .containsExactly(11L, 4242L, null).inOrder()
        assertThat(QardioBaseLib.parseUserIds("""{"z":"1","haptic":"1"}""")).isNull()
        assertThat(QardioBaseLib.parseUserIds("garbage")).isNull()
    }

    @Test
    fun `scale name is lowercase letters and digits, at most 6, like the official app`() {
        assertThat(QardioBaseLib.scaleName("Wedsa5")).isEqualTo("wedsa5")
        assertThat(QardioBaseLib.scaleName("Zoë-Marie Smith")).isEqualTo("zoemar")
        assertThat(QardioBaseLib.scaleName("José")).isEqualTo("jose")
        assertThat(QardioBaseLib.scaleName("R2 D2")).isEqualTo("r2d2")
        assertThat(QardioBaseLib.scaleName("名前")).isEqualTo("user")
    }

    @Test
    fun `excel serial day matches spreadsheet dates including the 1900 leap bug`() {
        assertThat(QardioBaseLib.excelSerialDay(GregorianCalendar(1900, 0, 1).time)).isEqualTo(1)
        assertThat(QardioBaseLib.excelSerialDay(GregorianCalendar(1900, 1, 28).time)).isEqualTo(59)
        assertThat(QardioBaseLib.excelSerialDay(GregorianCalendar(1900, 2, 1).time)).isEqualTo(61)
        assertThat(QardioBaseLib.excelSerialDay(GregorianCalendar(1990, 0, 1).time)).isEqualTo(32874)
        assertThat(QardioBaseLib.excelSerialDay(GregorianCalendar(2000, 0, 1).time)).isEqualTo(36526)
    }

    @Test
    fun `profile signature changes when stored fields change`() {
        assertThat(profile.copy(heightCm = 181).signature).isNotEqualTo(profile.signature)
        assertThat(profile.copy(token = "f".repeat(32)).signature).isEqualTo(profile.signature)
    }

    @Test
    fun `scale-wide flags are read from the user config and default to on`() {
        // Captured user config of the same scale.
        assertThat(QardioBaseLib.parseConfigFlags("""{"z":"1","haptic":"1","gym":"0","users":[{"id":"1000000001"}]}"""))
            .isEqualTo(QardioBaseLib.ConfigFlags(impedance = true, haptic = true))
        assertThat(QardioBaseLib.parseConfigFlags("""{"z":"1","haptic":"0"}"""))
            .isEqualTo(QardioBaseLib.ConfigFlags(impedance = true, haptic = false))
        assertThat(QardioBaseLib.parseConfigFlags("""{"z":"0"}""")).isEqualTo(QardioBaseLib.ConfigFlags(impedance = false, haptic = true))
        assertThat(QardioBaseLib.parseConfigFlags("garbage")).isEqualTo(QardioBaseLib.ConfigFlags())
    }

    @Test
    fun `legacy clear frames target the user and wifi configs`() {
        assertThat(QardioBaseLib.clearConfigTarget(QardioBaseLib.TARGET_USER_CONFIG)).isEqualTo(hex("00 00 04 20 00"))
        assertThat(QardioBaseLib.clearConfigTarget(QardioBaseLib.TARGET_WIFI_CONFIG)).isEqualTo(hex("00 00 04 10 00"))
    }
}
