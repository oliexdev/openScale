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

/**
 * Wire protocol of the scale AFU-BH-TZ-B1,
 * reverse-engineered from the official AFU Android app
 *
 * Every frame is exactly 20 bytes, in both directions:
 *
 *     [0xAF][0x45][sub-cmd @2][body @3..17][wire type @18][crc @19]
 *
 * crc = sum(raw[2..18]) & 0x1F — every captured frame validates against it.
 *
 * The 18th byte ("wire type") says what the frame IS, the 2nd byte ("sub-command")
 * disambiguates within that type:
 *
 *   wire type 0x54  stored / history record        (scale -> phone)
 *   wire type 0x51  live weight                    (scale -> phone)
 *   wire type 0x52  single-impedance result        (scale -> phone)
 *   wire type 0x5f  control frames                 (both directions):
 *                     sub-cmd 0x12 = ACK, 0x14 = chunk request/report chunk,
 *                     0x15 = close report, 0x16 = time-sync
 *   wire type 0x62  sync-request                   (phone -> scale only)
 *   wire type 0x61  user profile                   (phone -> scale only)
 *
 * A typical session (verified against btsnoop_hci_260817_140338.log timestamps):
 *
 *   1. Handshake:  time-sync -> 2x sync-request. The profile must NOT be sent yet —
 *      sending it early switches the scale into weighing mode and blocks history.
 *   2. Idle:       with nobody standing on it the scale starts pushing its stored
 *      measurements (0x54), but on its own it repeats the same record forever, no
 *      matter how often it is ACKed. A history-dump request (0x8e) switches it
 *      into export mode: every stored record is sent once, each ACKed before the
 *      next one arrives (the correlation index echoes the record sequence).
 *   3. First weigh: live weight streams (0x51) until the weight is marked final
 *      (state bit) and the phone ACKs. Only after the scale goes idle again
 *      (user stepped off) does the phone
 *      re-sync and send profile 0x4b + a chunk request (0x14) to PULL the fresh committed
 *      record (0x54), followed by 7 report chunks and a closing sub-cmd 0x15 request.
 *      The report payload is [per-session bytes]["AFU-BH-TZ-B1"+MAC in plaintext][zeros];
 *      it is not needed — body composition comes from weight + impedance of the 0x54.
 *   4. Arming:     profile 0x5b tags the NEXT weigh-in for an impedance measurement and
 *      profile 0x68 closes the session. Both are optional: the scale commits and serves
 *      every weigh with plain weight + resistance even when no profiles are sent at all.
 *   5. Second weigh (same connection, armed by 0x5b): once its weight is marked
 *      final and is ACKed the scale replies with 0x52 (single impedance result)
 *      instead of a stored record.
 *      A 0x52 therefore never occurs in a single-weigh session — its absence is normal.
 *
 * Profile bodies carry a per-variant "open byte" pair (`af XX` between <user> and
 * the 0x17 byte), copied from the official captures.
 *
 * Timestamps are Unix epoch seconds: LE32 in stored records, `ConvertIntEndian(now)`
 * bytes big-endian in time-sync/profile frames.
 */
object AfuB1Lib {

    const val FRAME_SIZE = 20

    // ---- Wire types (raw[18]) --------------------------------------------------

    const val WIRE_STORED_RECORD = 0x54     // scale -> phone
    const val WIRE_IMPEDANCE_RESULT = 0x52  // scale -> phone
    const val WIRE_LIVE_WEIGHT = 0x51       // scale -> phone
    const val WIRE_CONTROL = 0x5F           // both directions
    const val WIRE_SYNC_REQUEST = 0x62      // phone -> scale
    const val WIRE_USER_PROFILE = 0x61      // phone -> scale

    // ---- Sub-commands (raw[2]) -------------------------------------------------

    const val CMD_TIME_SYNC = 0x16
    const val CMD_SYNC_REQUEST = 0x04
    const val CMD_ACK = 0x12
    const val CMD_BODY_COMP_CHUNK = 0x14    // scale: chunk payload / phone: chunk request
    const val CMD_HISTORY_DUMP = 0x8E       // profile variant: dump the full history queue

    /**
     * Purpose of a user-profile frame (raw[2] of wire type 0x61). The scale treats each
     * code differently, so they are an enum, not free ints. [openByte] is the `af XX`
     * pair in the body, copied from the official captures per variant.
     */
    enum class ProfileVariant(val code: Int, val openByte: Int) {
        /** First contact once live weighing has started. */
        INITIAL(0x3F, 0xB6),

        /** Re-measure: makes the scale commit the weigh and push the fresh record. */
        GET_WEIGH_AGAIN_1(0x4B, 0xB6),
        GET_WEIGH_AGAIN_2(0x5B, 0xD9),

        /** Finalize the session and arm the next weigh. */
        FINAL(0x68, 0xD9)
    }

    /** The two sync-requests of the handshake, in captured order. */
    enum class SyncRequestVariant { FIRST, SECOND }

    // Weight is stored in the low 18 bits of the (endian-swapped) flags word.
    const val WEIGHT_G_MASK = 0x3FFFF

    // Body-composition table constants.
    private const val BAND_LOW = 4.0
    private const val BAND_HIGH = 75.0
    private const val BMI_SCALE = 10000.0
    private const val VFAL_DIV = 10000.0

    // --- Endian helpers ----------------------------------------------------
    // Faithful port of the j3/j3.c byte-swap functions the app uses: values are stored
    // little-endian but read back byte-swapped.

    /** Byte-reverses a 32-bit word (the app's ConvertIntEndian). */
    fun convertIntEndian(v: Int): Int =
        ((v and 0xFF) shl 24) or ((v and 0xFF00) shl 8) or
                ((v ushr 8) and 0xFF00) or ((v ushr 24) and 0xFF)

    // --- Commands (phone -> scale) -----------------------------------------

    /** af45 16 <time4> 20 00...00 5f <crc> — syncs the scale's clock to the phone's. */
    fun buildTimeSync(nowEpochSeconds: Long): ByteArray =
        buildFrame(CMD_TIME_SYNC, WIRE_CONTROL, time4(nowEpochSeconds) + byteArrayOf(0x20) + ByteArray(10))

    /**
     * af45 04 <body> 62 <crc> — one of the two sync-requests of the handshake.
     *
     * Some body bytes (the 0xb6/0xf8/0x11 "open byte", the 0x17 constant, the counter
     * bytes 0x1d/0x56/0x02/0x04) were captured verbatim from one session of the official
     * app and may be session-dependent — tweak if the scale rejects pairing.
     */
    fun buildSyncRequest(variant: SyncRequestVariant): ByteArray {
        val body = if (variant == SyncRequestVariant.FIRST) {
            byteArrayOf(
                0x00, 0x01, 0xAF.toByte(), 0xB6.toByte(), 0x17, 0x1D, 0x01,
                0x00, 0x02, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x56, 0x01, 0x00
            )
        } else {
            byteArrayOf(
                0x01, 0x03, 0xA5.toByte(), 0xF8.toByte(), 0x11, 0x1D, 0x02,
                0x00, 0x04, 0xB4.toByte(), 0xC6.toByte(), 0x1B, 0x1D, 0x01, 0x00
            )
        }
        return buildFrame(CMD_SYNC_REQUEST, WIRE_SYNC_REQUEST, body)
    }

    /**
     * af45 <variant> <time4> 00 <user> af b6 17 <age> <sex> <tgt*100 LE> 03 00 61 <crc>.
     * Sends the user's age/sex/target weight to the scale. [variant] picks the purpose
     * (see [ProfileVariant]). [sex] 1 = male, 0 = female.
     */
    fun buildUserProfile(
        variant: ProfileVariant,
        nowEpochSeconds: Long,
        user: Int,
        age: Int,
        sex: Int,
        targetKg: Float
    ): ByteArray {
        val targetX100 = Math.round(targetKg * 100f) and 0xFFFF
        val body = time4(nowEpochSeconds) + byteArrayOf(
            0x00, (user and 0xFF).toByte(),
            0xAF.toByte(), variant.openByte.toByte(), 0x17,
            (age and 0xFF).toByte(), (sex and 0xFF).toByte(),
            (targetX100 and 0xFF).toByte(), ((targetX100 ushr 8) and 0xFF).toByte(),
            0x03, 0x00
        )
        return buildFrame(variant.code, WIRE_USER_PROFILE, body)
    }

    /**
     * af45 8e <time4> 00 <user> af de 17 <age> <sex> <tgt*100 LE> 03 00 61 <crc>.
     * Tells the scale to export its ENTIRE history queue. Without this request the
     * scale just repeats the same stored record forever.
     */
    fun buildHistoryDumpRequest(
        nowEpochSeconds: Long,
        user: Int,
        age: Int,
        sex: Int,
        targetKg: Float
    ): ByteArray {
        val targetX100 = Math.round(targetKg * 100f) and 0xFFFF
        val body = time4(nowEpochSeconds) + byteArrayOf(
            0x00, (user and 0xFF).toByte(),
            0xAF.toByte(), 0xDE.toByte(), 0x17,
            (age and 0xFF).toByte(), (sex and 0xFF).toByte(),
            (targetX100 and 0xFF).toByte(), ((targetX100 ushr 8) and 0xFF).toByte(),
            0x03, 0x00
        )
        return buildFrame(CMD_HISTORY_DUMP, WIRE_USER_PROFILE, body)
    }

    /**
     * af45 12 <reply_wire_type> 00 <correlation> 00...00 5f <crc>.
     * Acknowledges a received frame. `replyWireType` is the wire type of the frame being
     * acknowledged (0x54/0x51/0x52, or 0x14 for body-comp chunks) and `correlationIndex`
     * the record sequence for history records (0 otherwise).
     */
    fun buildAck(replyWireType: Int, correlationIndex: Int): ByteArray =
        buildFrame(
            CMD_ACK, WIRE_CONTROL,
            byteArrayOf((replyWireType and 0xFF).toByte(), 0x00, (correlationIndex and 0xFF).toByte()) + ByteArray(12)
        )

    /** Asks the scale to stream the (encrypted, undecodable) report chunks of the last weigh. */
    fun buildChunkRequest(): ByteArray = buildFrame(CMD_BODY_COMP_CHUNK, WIRE_CONTROL, ByteArray(15))

    // --- Incoming frames (scale -> phone) ----------------------------------

    sealed class ScaleFrame {
        /** The wire type byte (raw[18]) this frame arrived as. */
        abstract val wireType: Int

        /** What an ACK for this frame must echo (its wire type, 0x14 for chunks). */
        abstract val ackReplyByte: Int

        /** Stored / history record (wire type 0x54). */
        data class StoredRecord(
            val historyType: Int,
            val sequence: Int,
            val timestampEpochSeconds: Long,
            val weightGrams: Int,
            val hasElectrode: Boolean,
            val hasHeartRate: Boolean,
            val hasPhaseAngle: Boolean,
            val hasZx: Boolean,          // vendor flag, purpose unknown
            val hasTemperature: Boolean,
            val locked: Boolean,         // scale considers this weight final
            val resistanceOhms: Int
        ) : ScaleFrame() {
            override val wireType = WIRE_STORED_RECORD
            override val ackReplyByte = WIRE_STORED_RECORD
        }

        /** Live weight while someone stands on the scale (wire type 0x51). */
        data class LiveWeight(
            val weightGrams: Int,
            val mode: Int,               // 0x02 = live-weight subtype
            val locked: Boolean          // scale signals the weight is final
        ) : ScaleFrame() {
            override val wireType = WIRE_LIVE_WEIGHT
            override val ackReplyByte = WIRE_LIVE_WEIGHT
        }

        /** Single-impedance measurement result (wire type 0x52). */
        data class ImpedanceResult(
            val valid: Boolean,          // sub-cmd 0x01 marks a valid result
            val adcOhms: Int             // resistance in Ohm
        ) : ScaleFrame() {
            override val wireType = WIRE_IMPEDANCE_RESULT
            override val ackReplyByte = WIRE_IMPEDANCE_RESULT
        }

        /** One 14-byte chunk of the scale's encrypted report (0x5f/0x14). Purpose unknown. */
        data class BodyCompChunk(
            val chunkIndex: Int,
            val payload: ByteArray
        ) : ScaleFrame() {
            override val wireType = WIRE_CONTROL
            override val ackReplyByte = CMD_BODY_COMP_CHUNK
        }

        /** Inbound acknowledgment (0x5f/0x12). */
        data class Acknowledgement(
            val replyCmd: Int,
            val replyOp: Int
        ) : ScaleFrame() {
            override val wireType = WIRE_CONTROL
            override val ackReplyByte = 0 // ACKs are not acknowledged
        }

        /** Other control frame, e.g. an echoed time-sync (0x5f). */
        data class ControlFrame(val cmd: Int) : ScaleFrame() {
            override val wireType = WIRE_CONTROL
            override val ackReplyByte = 0
        }
    }

    /**
     * Decode one 20-byte frame. Returns null for anything that is not a valid AFU frame
     * (wrong length, wrong header, bad CRC, or a phone->scale frame the scale never sends).
     */
    fun parse(raw: ByteArray): ScaleFrame? {
        if (raw.size != FRAME_SIZE ||
            (raw[0].toInt() and 0xFF) != 0xAF ||
            (raw[1].toInt() and 0xFF) != 0x45
        ) return null
        if (!crcValid(raw)) return null

        val cmd = raw[2].toInt() and 0xFF
        return when (raw[18].toInt() and 0xFF) {
            WIRE_STORED_RECORD -> parseStoredRecord(cmd, raw)
            WIRE_IMPEDANCE_RESULT -> parseImpedanceResult(cmd, raw)
            WIRE_LIVE_WEIGHT -> parseLiveWeight(raw)
            WIRE_CONTROL -> when (cmd) {
                CMD_BODY_COMP_CHUNK -> ScaleFrame.BodyCompChunk(
                    chunkIndex = raw[3].toInt() and 0xFF,
                    payload = raw.copyOfRange(4, 18)
                )

                CMD_ACK -> ScaleFrame.Acknowledgement(
                    replyCmd = raw[3].toInt() and 0xFF,
                    replyOp = raw[4].toInt() and 0xFF
                )

                else -> ScaleFrame.ControlFrame(cmd)
            }

            WIRE_SYNC_REQUEST, WIRE_USER_PROFILE -> null // phone -> scale only
            else -> null
        }
    }

    private fun parseStoredRecord(cmd: Int, raw: ByteArray): ScaleFrame.StoredRecord {
        // j3.e() case 84: first byte = history_type(bits 0-1) + sequence(bits 2-7)
        val timestamp = (raw[3].toLong() and 0xFF) or ((raw[4].toLong() and 0xFF) shl 8) or
                ((raw[5].toLong() and 0xFF) shl 16) or ((raw[6].toLong() and 0xFF) shl 24)
        val weightFlags = convertIntEndian(be32(raw, 7))
        return ScaleFrame.StoredRecord(
            historyType = cmd and 0x03,
            sequence = (cmd ushr 2) and 0x3F,
            timestampEpochSeconds = timestamp,
            weightGrams = weightFlags and WEIGHT_G_MASK,
            hasElectrode = weightFlags.bit(24),
            hasHeartRate = weightFlags.bit(25),
            hasPhaseAngle = weightFlags.bit(26),
            hasZx = weightFlags.bit(27),
            hasTemperature = weightFlags.bit(28),
            locked = weightFlags.bit(31),
            // resistance always present in 0x54 frames, LE16 at raw[13..14]
            resistanceOhms = le16(raw, 13)
        )
    }

    private fun parseLiveWeight(raw: ByteArray): ScaleFrame.LiveWeight {
        val weightFlags = convertIntEndian(be32(raw, 2))
        return ScaleFrame.LiveWeight(
            weightGrams = weightFlags and WEIGHT_G_MASK,
            mode = raw[6].toInt() and 0xFF,
            locked = weightFlags.bit(31)
        )
    }

    private fun parseImpedanceResult(cmd: Int, raw: ByteArray): ScaleFrame.ImpedanceResult =
        ScaleFrame.ImpedanceResult(
            valid = cmd == 0x01,
            adcOhms = le16(raw, 4)
        )

    // ----------------------------------------------------------------------------
    // Body composition from weight + impedance. The arithmetic deliberately mixes
    // single/double precision: every .toFloat() boundary below is load-bearing.
    // ----------------------------------------------------------------------------

    /**
     * Body-fat percentage. Sex-aware (1 = male, 0 = female); impedance 0 -> null.
     * Clamped to [5, 80] and rounded half-even to one decimal.
     */
    fun bodyFatPercent(
        weightKg: Double,
        heightCm: Double,
        age: Int,
        sex: Int,
        impedanceOhm: Double,
    ): Float? {
        if (impedanceOhm == 0.0) return null
        val h = (heightCm / 100.0).toFloat()
        val hd = h.toDouble()
        val h2 = hd * hd
        var r: Double
        if (sex == 1) {
            val h2f = (h * (h * -486583.0f)).toDouble()
            r = 1625303.0 / impedanceOhm / impedanceOhm
            r += 9.146 * weightKg / h2 / impedanceOhm
            r += h2f / weightKg / impedanceOhm + 61.8
            r += -251.193 * h2 / weightKg / age
            r += impedanceOhm * -0.0139
            r += age * 0.05975
        } else {
            val h2f = (h * (h * -382280.0f)).toDouble()
            r = -186.422 * h2 / weightKg + 58.82
            r += h2f / weightKg / impedanceOhm
            r += 128.005 * weightKg / hd / impedanceOhm
            r += -0.0728 * weightKg / hd
            r += 7816.359 / hd / impedanceOhm
            r += -3.333 * weightKg / h2 / age
        }
        val clamped = r.toFloat().coerceIn(5f, 80f)
        return (Math.rint(clamped * 10.0) / 10.0).toFloat()
    }

    /** The composition fields openScale publishes for one measurement. */
    data class BodyComposition(
        val waterPercent: Double,
        val skeletalMusclePercent: Double,
        val boneMassKg: Float,
        val visceralFatIndex: Int,
        val proteinPercent: Double,
        val fatFreeMassKg: Float,
    )

    /**
     * Derives water %, skeletal muscle %, bone mass, the visceral fat index,
     * protein % and fat-free mass from weight and [bodyFatPercent]. Returns null
     * outside the validated input ranges.
     */
    fun bodyComposition(
        sex: Int,
        age: Int,
        heightCm: Float,
        weightKg: Float,
        bodyFatPercent: Float,
    ): BodyComposition? {
        if (sex > 1 || age !in 10..99) return null
        if (heightCm < 90f || heightCm > 240f || weightKg < 10f || weightKg > 200f) return null

        val bf = bodyFatPercent.toDouble()
        val w = weightKg.toDouble()
        val h = heightCm.toDouble()

        // _classify: fat % bands below 4 / above 75 swap fixed values in; degenerate
        // f64 edge windows are rejected outright by the reference implementation.
        if ((bf <= BAND_LOW && bf <= 3.999) || (bf >= BAND_HIGH && (bf <= 74.999 || bf > 75.001))) {
            return null
        }
        val bandLow = bf <= BAND_LOW
        val bandHigh = bf >= BAND_HIGH

        val bmi = (w * BMI_SCALE / (heightCm * heightCm).toDouble()).toFloat()

        // _recompute_bf: back-solve the internal fat rate from BMI + measured fat %.
        val recomputedBf: Float = run {
            val num: Double
            val denom: Double
            if (sex == 0) {
                if (age <= 15) {
                    num = ((-556421.0 / bmi).toFloat() + 17387.0).toFloat().toDouble()
                    denom = bf - 44.19
                } else {
                    num = ((-518695.0 / bmi).toFloat() + 15802.0).toFloat().toDouble()
                    denom = bf - 42.86 + age * -0.057
                }
            } else {
                if (age <= 15) {
                    num = ((-506791.0 / bmi).toFloat() + 12009.0).toFloat().toDouble()
                    denom = bf - 63.25 + age * 1.121
                } else {
                    num = ((-460857.0 / bmi).toFloat() + 10645.0).toFloat().toDouble()
                    denom = bf - 37.74 + age * -0.0511
                }
            }
            (num / denom).toFloat()
        }

        // _body_rate (c0), with band replacements.
        val c0: Float = when {
            bandLow -> (if (sex == 0) 66.5 else 66.0).toFloat()
            bandHigh -> (if (sex == 0) 18.0 else 15.0).toFloat()
            sex == 0 -> {
                var s2 = (3752.4 * heightCm).toFloat(); s2 *= heightCm
                var s3 = (444.93 + (556707.0 / recomputedBf).toFloat()).toFloat()
                s2 /= recomputedBf
                s3 /= weightKg
                s2 = (s2 / 10000.0).toFloat()
                val sa = (10276.0 / recomputedBf).toFloat()
                s2 = s3 + s2
                (24.305 + (s2 - sa)).toFloat()
            }
            else -> {
                var s2 = (3705.3 * heightCm).toFloat(); s2 *= heightCm
                var s3 = (299.43 + (696819.0 / recomputedBf).toFloat()).toFloat()
                s2 /= recomputedBf
                s3 /= weightKg
                s2 = (s2 / 10000.0).toFloat()
                val sa = (10770.0 / recomputedBf).toFloat()
                s2 = s3 + s2
                (29.61 + (s2 - sa)).toFloat()
            }
        }
        val c4 = (c0.toDouble() * w / 100.0).toFloat()

        // Bone mass.
        val boneMassKg: Float = if (bandLow || bandHigh) {
            val fac = if (bandLow) (if (sex == 0) 5.8 else 5.0) else (if (sex == 0) 1.6 else 1.3)
            ((w * fac).toFloat() / 100.0).toFloat()
        } else {
            val fac = if (sex == 0) 0.061 else 0.052
            val p1 = (w * fac).toFloat()
            val p2 = (100.0 - bf).toFloat()
            (p1.toDouble() * p2.toDouble() / 100.0).toFloat()
        }

        // Skeletal muscle rate (s1) and total muscle rate (s4).
        val s1: Float
        val s4: Float
        if (bandLow) {
            s1 = (if (sex == 0) 56.6 else 59.0).toFloat()
            s4 = (if (sex == 0) 90.2 else 91.0).toFloat()
        } else if (bandHigh) {
            s1 = (if (sex == 0) 15.1 else 17.9).toFloat()
            s4 = (if (sex == 0) 23.4 else 23.7).toFloat()
        } else {
            s1 = if (sex == 0) c0 * 0.857f - 0.36f else c0 * 0.895f
            val mf = if (sex == 0) 0.939f else 0.948f
            s4 = mf * (100f - bf.toFloat())
        }
        val skeletalMusclePercent = ((s1 * w).toFloat() / 100.0).toFloat() * 100.0 / w

        // Protein rate feeds the water derivation.
        val muscleKg = ((s4 * w).toFloat() / 100.0).toFloat()
        val proteinRate = ((muscleKg - c4) * 100.0 / w).toFloat()

        // Visceral fat index (VFAL).
        val vfal: Int = when {
            bandLow -> 1
            bandHigh -> 30
            sex == 1 -> {
                val bf64 = recomputedBf.toDouble()
                var acc = 77.216 / bf64 + 0.068
                acc *= w
                val d17 = acc + 0.5
                var d5 = (-5489.0 / recomputedBf).toFloat().toDouble() + 2.8
                d5 *= h; d5 *= h
                var d6 = d17 + d5 / VFAL_DIV
                d6 += 6096.3 / bf64
                val raw = (-1.22 + (d6 + 0.1668 * age)).toInt()
                if (raw <= 0) 1 else if (raw > 30) 30 else raw
            }
            else -> {
                val bf64 = recomputedBf.toDouble()
                var acc = 71.92 / bf64 + 0.0876
                acc *= w
                val d17 = acc + 0.5
                var d5 = (-1261.7 / bf64).toFloat().toDouble() - 2.88
                d5 *= h; d5 *= h
                var d6 = d17 + d5 / VFAL_DIV
                d6 += -1545.6 / bf64
                val raw = (5.9 + (d6 + 0.068 * age)).toInt()
                if (raw <= 0) 1 else if (raw > 30) 30 else raw
            }
        }

        val bonePct = boneMassKg * 100.0 / w

        // Fat-free mass: w + w*bf/(-100).
        val fatMassKg = (w * bf).toFloat()
        val fatFreeMassKg = (w + (fatMassKg / -100.0).toFloat()).toFloat()

        return BodyComposition(
            waterPercent = 100.0 - bf - bonePct - proteinRate,
            skeletalMusclePercent = skeletalMusclePercent,
            boneMassKg = boneMassKg,
            visceralFatIndex = vfal,
            proteinPercent = proteinRate.toDouble(),
            fatFreeMassKg = fatFreeMassKg,
        )
    }


    // --- Helpers -----------------------------------------------------------

    /** Build a frame: AF 45 [sub-cmd@2][body@3..17][wire type@18][crc@19]. */
    private fun buildFrame(cmd: Int, wireType: Int, body: ByteArray): ByteArray {
        require(body.size == 15) { "AFU frame body must be 15 bytes, got ${body.size}" }
        val b = ByteArray(FRAME_SIZE)
        b[0] = 0xAF.toByte()
        b[1] = 0x45.toByte()
        b[2] = (cmd and 0xFF).toByte()
        body.copyInto(b, 3)
        b[18] = (wireType and 0xFF).toByte()
        b[19] = crc(b).toByte()
        return b
    }

    /** Timestamp slot: ConvertIntEndian(now) stored as raw big-endian bytes. */
    private fun time4(nowEpochSeconds: Long): ByteArray {
        val v = convertIntEndian((nowEpochSeconds and 0xFFFFFFFFL).toInt())
        return byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
    }

    /** Checksum over raw[2..18]: byte sum, transmitted in the lower 5 bits. */
    private fun crc(raw: ByteArray): Int {
        var sum = 0
        for (i in 2 until 19) sum += raw[i].toInt() and 0xFF
        return sum and 0x1F
    }

    private fun crcValid(raw: ByteArray): Boolean =
        crc(raw) == (raw[19].toInt() and 0x1F)

    /** Little-endian 16-bit word at [off]. */
    private fun le16(raw: ByteArray, off: Int): Int =
        (raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)

    /** True if bit [n] of the flags word is set. */
    private fun Int.bit(n: Int): Boolean = ((this ushr n) and 1) == 1

    /** Big-endian 32-bit word at [start]. */
    private fun be32(raw: ByteArray, start: Int): Int =
        ((raw[start].toInt() and 0xFF) shl 24) or ((raw[start + 1].toInt() and 0xFF) shl 16) or
                ((raw[start + 2].toInt() and 0xFF) shl 8) or (raw[start + 3].toInt() and 0xFF)
}
