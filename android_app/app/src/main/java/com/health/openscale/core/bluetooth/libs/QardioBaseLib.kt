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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.Date

/**
 * Wire format and body-composition model of the Qardio QardioBase (B100) and QardioBase 2 (B200).
 *
 * [com.health.openscale.core.bluetooth.scales.QardioBaseHandler] owns the BLE sequencing.
 *
 * Protocol summary (vendor service `c8219e89-…`):
 * - STATE characteristic (uint8, notify): 0 idle, 1 configuration mode, 2 ready, 3 measuring,
 *   6 result ready, 12 going to sleep (the scale then disconnects).
 * - ENGINEERING characteristic: commands are written as `00 00 <cmd> [args]`, ints little-endian;
 *   replies arrive on the same characteristic as `00 00 <cmd> <value>`.
 * - RESULT characteristic: plain JSON with the final measurement, read after STATE 6. Once read
 *   (or before any weigh-in) reads fail with GATT INVALID_OFFSET.
 * - USER CONFIG characteristic: JSON list of profiles stored on the scale. Profiles are written
 *   through the ENGINEERING characteristic in 15-byte chunks while the scale is in configuration mode.
 */
object QardioBaseLib {

    // --- Engineering commands ---------------------------------------------------------------

    const val CMD_LONG_WRITE = 0x04
    const val CMD_STEP_EVENT = 0x05
    const val CMD_CONFIG_MODE = 0x0A
    const val CMD_SET_TIMESTAMP = 0x0C
    const val CMD_UNIQUE_ID = 0x10
    const val CMD_RESET = 0x11
    const val CMD_SELECT_USER = 0x14

    /** Plausible barefoot impedance range; shoes or a bad contact read far outside it. */
    const val MIN_IMPEDANCE = 150
    const val MAX_IMPEDANCE = 1500

    /** Long-write target of the user config (high nibble of byte 3; 1 is Wi-Fi, 3 calibration). */
    const val TARGET_USER_CONFIG = 2
    const val TARGET_WIFI_CONFIG = 1

    /** Long-write ack value the scale returns when all user slots are taken. */
    const val ACK_USERS_FULL = -2


    /** Payload bytes per long-write frame. */
    private const val CHUNK_SIZE = 15

    /**
     * Per-profile display mode ("ui" field). Only Normal is used. The official app also offered
     * (ui value, with the scale-wide flags it wrote alongside):
     * - 35 Smart Feedback: smileys instead of numbers (impedance and vibration on).
     * - 38 Weight Only, for implanted devices (impedance off).
     * - 43 Pregnancy, weight hidden (impedance and vibration off).
     * The impedance/vibration part of those modes is scale-wide, not per person: the scale has to
     * decide whether to pass current before it knows who is standing on it. openScale exposes those
     * two switches directly (see [ConfigFlags]) and keeps every profile on Normal, which shows the
     * numbers; hiding them is of little use when the app records them anyway. Modes would be a
     * per-profile `{"users":[…,{"ui":"<value>"},…]}` write (C5704nj.m34407f / tk2).
     *
     * `goal`/`rate` (goal weight and weekly rate ×10, for Smart Feedback) are written as 0, like the
     * official profile write; the app's separate goal write (C5704nj.m34405d) was unreachable from
     * its UI in 2.5.1.
     */
    const val UI_NORMAL = 4

    fun command(cmd: Int): ByteArray = byteArrayOf(0, 0, cmd.toByte())

    private fun commandWithInt(cmd: Int, value: Int): ByteArray =
        command(cmd) + byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    fun configMode(enable: Boolean): ByteArray = command(CMD_CONFIG_MODE) + byteArrayOf(if (enable) 1 else 0)

    fun setTimestamp(epochSeconds: Long): ByteArray = commandWithInt(CMD_SET_TIMESTAMP, epochSeconds.toInt())

    /** The official app writes 16 random alphanumerics after a profile write and before a reset. */
    fun uniqueId(flag: Boolean, token: String): ByteArray {
        require(token.length == 16) { "token must be 16 characters" }
        return command(CMD_UNIQUE_ID) + byteArrayOf(if (flag) 1 else 0) + token.toByteArray(Charsets.US_ASCII)
    }

    fun reset(): ByteArray = command(CMD_RESET)

    /**
     * Empties the config store of [target]: `00 00 04 <target << 4> 00` (ru0 without a payload),
     * sent for the user and Wi-Fi configs by the official app's legacy reset for firmware before
     * 1.8. Acked like a long-write frame.
     */
    fun clearConfigTarget(target: Int): ByteArray =
        byteArrayOf(0, 0, CMD_LONG_WRITE.toByte(), (target shl 4).toByte(), 0)

    /** Tells the scale who steps on next, instead of letting it guess (the official app did this while open). */
    fun selectUser(scaleUserId: Int): ByteArray = commandWithInt(CMD_SELECT_USER, scaleUserId)

    /**
     * Splits [payload] into long-write frames:
     * `00 00 04 <(target << 4) | (offset >> 8 & 0x0F)> <offset & 0xFF> <≤15 payload bytes>`.
     * The scale acks each frame with `00 00 04 00`; send the next one only after that.
     */
    fun longWriteFrames(target: Int, payload: ByteArray): List<ByteArray> =
        (payload.indices step CHUNK_SIZE).map { offset ->
            val end = minOf(offset + CHUNK_SIZE, payload.size)
            byteArrayOf(
                0, 0, CMD_LONG_WRITE.toByte(),
                ((target shl 4) or ((offset shr 8) and 0x0F)).toByte(),
                (offset and 0xFF).toByte(),
            ) + payload.copyOfRange(offset, end)
        }

    /** A reply frame on the ENGINEERING characteristic; null for the scale's raw sensor stream. */
    data class EngineeringReply(val cmd: Int, val value: Int)

    fun parseEngineeringReply(data: ByteArray): EngineeringReply? {
        if (data.size < 4 || data[0].toInt() != 0 || data[1].toInt() != 0) return null
        return EngineeringReply(data[2].toInt() and 0xFF, data[3].toInt())
    }

    // --- Measurement result ---------------------------------------------------------------

    /**
     * Final measurement as read from the RESULT characteristic. QardioBase 2 captures (values as
     * strings, as the scale sends them; serial part of the id blanked):
     * - identified: `{"id":"000000000000041791951128","weight":"87.4","bmi":"29.2","z":"474",
     *   "fat":"26","tbw":"51","bmc":"4","mt":"14","sm":"37","algorithm":"0","user":"wedsa5","userid":"1000000001"}`
     * - not matched to a profile (guest): `{"id":"000000000004061791936503","weight":"87.0","z":"484"}`
     *
     * The B100 sends no `id`, `user` or `userid`.
     *
     * Only what the scale reports is decoded; openScale derives BMI, BMR and lean body mass itself.
     * Field meanings, from the official app 2.5.1 and the captures:
     * - `weight` kg (always kg, whatever unit the display uses); `z` impedance in Ω.
     * - `fat` body fat %, `tbw` total body water % (the app showed both with a % sign).
     * - `sm` skeletal muscle %: never read by the official app, so not proven, but the name fits and
     *   the values do (36–37 % for a man whose impedance gives 36.2 % with the MRI-validated BIA
     *   equation of Janssen et al., J Appl Physiol 89:465, 2000). openScale's MUSCLE is skeletal muscle.
     * - `bmc` bone mineral content in kg (standard BIA term; 4 for an adult man). The app only uploaded
     *   it to Qardio's cloud and never displayed it, so the unit is inferred. Whole kg only.
     * - `bmi`: dropped, openScale computes BMI.
     * - `mt`: dropped. The official app showed it as "Muscle %" and, when it was missing, substituted
     *   lean body mass × 0.208 / weight × 100 (C6003pn.m36386q via qi3.m37093w); that gives 14.3 % for
     *   the weigh-in that reported `"mt":"14"`. So it is Qardio's own model-specific "muscle" share of
     *   body weight, not skeletal muscle, and it does not match openScale's MUSCLE (ranges of 15–60 %
     *   that describe skeletal muscle). No other definition was found.
     * - `algorithm`: always "0" so far; meaning unknown, ignored.
     * - `user`/`userid`: the profile the scale matched; absent for a guest weigh-in.
     */
    data class Result(
        val id: String?,
        val weightKg: Float,
        /** Impedance in Ω when it is in the plausible barefoot range. */
        val impedanceOhm: Int?,
        val scaleUserId: Long?,
        val scaleUserName: String?,
        /**
         * When the weigh-in happened, from the last 10 digits of `id` (Unix seconds; verified on a
         * B200). Null when the id does not carry a plausible time.
         */
        val time: Date?,
        val fatPercent: Float?,
        val waterPercent: Float?,
        val skeletalMusclePercent: Float?,
        val boneKg: Float?,
    )

    fun parseResult(json: String): Result? {
        val o = parseObject(json) ?: return null
        // isNull also covers a missing key; optString would turn a JSON null into "null".
        fun text(key: String): String? = if (o.isNull(key)) null else o.optString(key).trim().ifEmpty { null }
        fun number(key: String): Float? = text(key)?.toFloatOrNull()
        val weight = number("weight")?.takeIf { it > 0f } ?: return null
        val id = text("id")
        return Result(
            id = id,
            time = id?.let(::timeFromResultId),
            weightKg = weight,
            impedanceOhm = number("z")?.toInt()?.takeIf { it in MIN_IMPEDANCE..MAX_IMPEDANCE },
            scaleUserId = text("userid")?.toLongOrNull(),
            scaleUserName = text("user"),
            // The scale only computes these for a matched profile; 0 or absent means not measured.
            fatPercent = number("fat")?.takeIf { it > 0f && it < 100f },
            waterPercent = number("tbw")?.takeIf { it > 0f && it < 100f },
            skeletalMusclePercent = number("sm")?.takeIf { it > 0f && it < 100f },
            boneKg = number("bmc")?.takeIf { it > 0f },
        )
    }

    /** Weigh-ins are stamped with the scale clock (set on every connect); reject anything odd. */
    fun timeFromResultId(id: String): Date? {
        if (id.length <= 10 || !id.all { it.isDigit() }) return null
        val seconds = id.takeLast(10).toLongOrNull() ?: return null
        val millis = seconds * 1000
        val earliest = 1_420_070_400_000L // 2015-01-01, before the QardioBase existed
        val latest = System.currentTimeMillis() + 24 * 3600_000L
        return if (millis in earliest..latest) Date(millis) else null
    }

    // --- User profiles ----------------------------------------------------------------------

    data class Profile(
        val id: Int,
        val name: String,
        val male: Boolean,
        val heightCm: Int,
        val birthday: Date?,
        /** 0 kg, 1 lb, 2 st. */
        val units: Int,
        /** 32 hex chars; the official app used the account UUID. */
        val token: String,
        val ui: Int = UI_NORMAL,
    ) {
        /** Changes whenever a field the scale stores changes, to decide whether to re-send. */
        val signature: String
            get() = listOf(id, scaleName(name), male, heightCm, bornField(birthday), units, ui).joinToString("|")
    }

    /**
     * IDs of the profiles stored on the scale in slot order, from the USER CONFIG characteristic
     * (`{"users":[{"id":"123",…},{…}]}`). Null when the payload is not a user list.
     */
    fun parseUserIds(json: String): List<Long?>? {
        val users = parseObject(json)?.optJSONArray("users") ?: return null
        return List(users.length()) { i ->
            val raw = users.optJSONObject(i)?.optString("id").orEmpty()
            // The official app also tolerated a trailing non-digit.
            raw.toLongOrNull() ?: raw.dropLast(1).toLongOrNull()
        }
    }

    /**
     * Scale-wide flags stored at the top level of the user config, next to the profiles:
     * `z` = impedance measurement (body composition; off = no current through the body, e.g. for
     * implanted devices or pregnancy, and the scale then tells people apart by weight only) and
     * `haptic` = vibration feedback. Every profile write carries them.
     */
    data class ConfigFlags(val impedance: Boolean = true, val haptic: Boolean = true)

    /**
     * The `z`/`haptic` flags of the USER CONFIG payload (`{"z":"1","haptic":"1","gym":"0","users":[…]}`).
     * Missing values default to on, as in the official app's normal mode.
     */
    fun parseConfigFlags(json: String): ConfigFlags {
        val o = parseObject(json) ?: return ConfigFlags()
        return ConfigFlags(
            impedance = o.optString("z") != "0",
            haptic = o.optString("haptic") != "0",
        )
    }

    /**
     * Profile write payload. Appends a new slot when [slotIndex] is null, otherwise rewrites slot
     * [slotIndex] of [slotCount] and leaves the other slots untouched (empty objects).
     */
    fun profileJson(
        profile: Profile,
        slotIndex: Int? = null,
        slotCount: Int = 0,
        impedance: Boolean = true,
        haptic: Boolean = true,
    ): String {
        // Key order as in the official app (C5704nj): the firmware gets the same bytes.
        val user = JSONObject()
            .put("id", profile.id.toString())
            .put("name", scaleName(profile.name))
            .put("male", if (profile.male) "1" else "0")
            .put("height", profile.heightCm.toString())
            .put("born", bornField(profile.birthday).toString())
            .put("units", profile.units.toString())
            .put("goal", "0")
            .put("rate", "0")
            .put("token", profile.token)
            .put("ui", profile.ui.toString())
        val root = JSONObject()
            .put("z", if (impedance) "1" else "0")
            .put("haptic", if (haptic) "1" else "0")
        if (slotIndex == null) {
            root.put("add", user)
        } else {
            val users = JSONArray()
            repeat(maxOf(slotCount, slotIndex + 1)) { i -> users.put(if (i == slotIndex) user else JSONObject()) }
            root.put("users", users)
        }
        return root.toString()
    }

    /**
     * Name shown on the scale: lowercase letters and digits, at most 6, exactly what the official
     * app allowed (`[a-zA-Z0-9]+`, maxLength 6, lowercased). The firmware ignores profiles whose
     * name falls outside this (verified on a B200: a profile named with a capital letter was never matched; the same profile in lowercase was).
     * Accents are dropped first ("Zoë" → "zoe"); a name with no Latin letters or digits becomes "user".
     */
    fun scaleName(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }.take(6).ifEmpty { "user" }

    /** `born` is a spreadsheet-style serial day; 0 when unknown. */
    private fun bornField(birthday: Date?): Int {
        if (birthday == null || birthday.after(Date())) return 0
        return excelSerialDay(birthday).takeIf { it > 0 } ?: 0
    }

    /**
     * Days since 1900-01-00 including the historic 1900 leap-day bug (1900-01-01 = 1,
     * 2000-01-01 = 36526), matching the official app.
     */
    fun excelSerialDay(date: Date): Int {
        val cal = Calendar.getInstance().apply { time = date }
        val year = cal.get(Calendar.YEAR)
        val prev = year - 1
        val yearOffset = (year - 1900) * 365 + (prev / 4 - prev / 100 + prev / 400) - 460
        val serial = cal.get(Calendar.DAY_OF_YEAR) + yearOffset
        return if (serial >= 60) serial + 1 else serial
    }

    /** The top-level object of [text], ignoring the scale's trailing NULs; null if malformed. */
    private fun parseObject(text: String): JSONObject? =
        try {
            JSONObject(text.trim().trimEnd('\u0000').trim())
        } catch (_: JSONException) {
            null
        }
}
