/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.bluetooth.libs.YunmaiLib
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handler for ES-CS20M scales (Renpho / Lefu lineage).
 *
 * Protocol (reverse-engineered from Android HCI btsnoop capture of official Renpho app,
 * 2026-03-07; all traffic uses the 0x1A10 service — 0xFFF0 / QN channels are NOT used):
 *
 * Frame format: 55 AA [cmd] 00 [len] [payload…] [sum-checksum]
 * Checksum = sum of all preceding bytes mod 256.
 *
 * Commands written to CHR_CUR_TIME (0x2A11):
 *   0x97  User-profile: 01 00 00 [unix_ts_be4] 01 06
 *            field[7]=0x01 is a constant (NOT sex); field[8]=0x06 is a constant.
 *   0x96  Config/save:  [sex] [year_be2] [month] [day] [height_mm_be2] 00 00 [weight_raw_be2] [mode] 01 05
 *            sex=0x11 male, 0x21 female  (first byte, NOT the second-to-last)
 *            mode=0xAA standard, 0x6A athlete (activity=EXTREME maps to athlete)
 *   0x90  Start meas:   01 00 01 00   ← byte[2]=0x01 enables BIA mode
 *   0x99  Interim ACK:  01
 *
 * Notifications received on CHR_RESULTS (0x2A10):
 *   0x11  START/STOP          byte[5]=0x01 start, 0x00 stop
 *   0x14  Weight frame        frame[8:9]=weight u16be/100=kg (resistance bytes always 0x0000)
 *   0x16  History-save ACK    pre-meas: byte[5]=0x01 accepted / 0x00 rejected; post-BIA: ignored
 *   0x17  Profile ACK         payload=0x01 0x01
 *   0x18  Final BIA result    frame[10:11]=weight, frame[12:13]=r1(Ω), frame[14:15]=r2(Ω)
 *   0x19  Interim BIA result  frame[11:12]=weight, frame[13:14]=r1(Ω) — multi-fragment
 *   0x10  Op callback         frame[5]=0x00 BIA mode active, 0x01 weight-only mode (NOT success/failure)
 *
 * Connection sequence:
 *   onConnected → setNotifyOn(CHR_RESULTS) + write 0x97
 *   (0x17 ACK) → write 0x96 (pre-meas, with user.initialWeight as reference weight)
 *   (0x16 ACK, byte[5]=0x01 accepted) → write 0x90  ← skipped if rejected (refWeight=0)
 *   scale sends 0x14 weight frames × N
 *   scale sends 0x19 intermediate BIA → write 0x99 + 0x96 (intermediate weight)
 *   scale sends 0x18 final BIA → extract r1 → write 0x96 × 2 (final weight)
 *   scale sends 0x11 STOP → publish measurement
 *
 * Long frames (e.g. 0x19, 26 bytes) exceed ATT MTU and are fragmented by the scale into
 * two ATT notifications using a 3-byte prefix: 0xAD=first fragment, 0xAF=continuation.
 */
class ESCS20MHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "ESCS20mHandler"

        // Lefu frame message IDs (frame byte[2])
        private const val MSG_OP_CALLBACK:  Int = 0x10  // mode callback from 0x90 (0x00=BIA, 0x01=weight-only)
        private const val MSG_START_STOP:   Int = 0x11  // byte[5]=0x01 start, 0x00 stop
        private const val MSG_WEIGHT:       Int = 0x14  // weight frame (~5 Hz)
        private const val MSG_HIST_ACK:     Int = 0x16  // ACK to 0x96 history saves
        private const val MSG_PROFILE_ACK:  Int = 0x17  // ACK to 0x97 user-profile write
        private const val MSG_BIA_FINAL:    Int = 0x18  // final BIA result
        private const val MSG_BIA_INTERIM:  Int = 0x19  // intermediate BIA (may be fragmented)

        // Position of the START/STOP flag in 0x11 frames
        private const val START_STOP_IDX = 5

        // Multi-fragment encoding: scale splits frames > ATT MTU into two notifications
        // with a 3-byte prefix. First fragment: 0xAD xx xx [data…], continuation: 0xAF xx xx [data…]
        private const val FRAG_FIRST = 0xAD
        private const val FRAG_CONT  = 0xAF
        private const val FRAG_HDR   = 3     // prefix bytes to skip (prefix + 2 unknown bytes)

        // Connection phases (connPhase):
        private const val PHASE_WAIT_PROFILE_ACK = 0  // sent 0x97, waiting for 0x17
        private const val PHASE_WAIT_HIST_ACK    = 1  // sent 0x96 pre-meas, waiting for 0x16
        private const val PHASE_MEASURING        = 2  // sent 0x90, receiving 0x14 frames
        private const val PHASE_BIA_DONE         = 3  // received 0x18, sent post-BIA 0x96 × 2
    }

    // GATT service / characteristic UUIDs for the 0x1A10 Lefu service
    private val SVC_MAIN     = uuid16(0x1A10)
    private val CHR_CUR_TIME = uuid16(0x2A11)  // write commands here
    private val CHR_RESULTS  = uuid16(0x2A10)  // receive notifications here

    // ── Session state ─────────────────────────────────────────────────────────

    private val acc = ScaleMeasurement()

    private var connPhase    = PHASE_WAIT_PROFILE_ACK
    private var biaResistance = 0   // Ω from 0x18 final BIA frame (r1, primary)
    private var lastWeightRaw = 0   // most recent weight from 0x14 frames, u16be (units: 0.01 kg)
    private var lefuFrag: ByteArray? = null  // reassembly buffer for fragmented 0x19 frames
    private var hasPublished = false  // true after first publish; blocks duplicate STOP publishes

    // ── Device identification ─────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name   = device.name.lowercase(Locale.ROOT)
        val hasSvc = device.serviceUuids.any { it == SVC_MAIN }
        if (!hasSvc && !name.contains("es-cs20m")) return null

        val caps = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.LIVE_WEIGHT_STREAM)
        return DeviceSupport(
            displayName = "ES-CS20M",
            capabilities = caps,
            implemented  = caps,
            linkMode     = LinkMode.CONNECT_GATT
        )
    }

    // ── Connection sequencing ─────────────────────────────────────────────────

    override fun onConnected(user: ScaleUser) {
        resetState()

        // Subscribe to weight/BIA notifications, then immediately send the user-profile command.
        // The scale auto-starts and begins sending 0x14 weight frames on subscribe; we ignore
        // those until the full setup sequence (0x97 → 0x96 → 0x90) completes.
        setNotifyOn(SVC_MAIN, CHR_RESULTS)
        writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd97())
        LogManager.i(TAG, "Session started; wrote 0x97 user-profile")
    }

    // ── Notification dispatch ─────────────────────────────────────────────────

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic == CHR_RESULTS) handleLefuNotification(data, user)
    }

    // ── Fragment reassembly ───────────────────────────────────────────────────

    /**
     * The scale uses a custom 2-part fragmentation for frames that exceed the ATT MTU.
     * First fragment:        0xAD xx xx [partial Lefu frame…]
     * Continuation fragment: 0xAF xx xx [remainder…]
     * Regular frames start with 0x55 and are passed through unchanged.
     */
    private fun handleLefuNotification(data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return

        when (data[0].toInt() and 0xFF) {
            FRAG_FIRST -> {
                lefuFrag = data.copyOfRange(FRAG_HDR, data.size)
            }
            FRAG_CONT -> {
                val frag = lefuFrag
                if (frag == null) {
                    LogManager.w(TAG, "Continuation fragment without a preceding first fragment; discarded")
                    return
                }
                val complete = frag + data.copyOfRange(FRAG_HDR, data.size)
                lefuFrag = null
                handleLefuFrame(complete, user)
            }
            else -> {
                // Do NOT clear lefuFrag here: 0x14 weight frames arrive at ~5 Hz and can
                // legitimately interleave between FRAG_FIRST and FRAG_CONT of a 0x19 pair.
                // A new FRAG_FIRST will overwrite lefuFrag when needed.
                handleLefuFrame(data, user)
            }
        }
    }

    // ── Lefu frame handler ────────────────────────────────────────────────────

    private fun handleLefuFrame(data: ByteArray, user: ScaleUser) {
        if (data.size < 3) return

        val msgId = data[2].toInt() and 0xFF

        // Weight frames arrive at ~5 Hz; avoid building a hex-dump string on every frame.
        if (msgId == MSG_WEIGHT) { onWeightFrame(data); return }

        val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        LogManager.d(TAG, "Lefu 0x%02X len=%d [%s]".format(msgId, data.size, hex))

        when (msgId) {
            MSG_PROFILE_ACK  -> onProfileAck(user)
            MSG_HIST_ACK     -> onHistAck(data)
            MSG_BIA_INTERIM  -> onBiaInterim(data, user)
            MSG_BIA_FINAL    -> onBiaFinal(data, user)
            MSG_START_STOP   -> onStartStop(data, user)
            MSG_OP_CALLBACK  -> onOpCallback(data)
        }
    }

    // ── Per-message handlers ──────────────────────────────────────────────────

    /** 0x17 – Scale acknowledged our 0x97 user-profile write. Send pre-meas 0x96. */
    private fun onProfileAck(user: ScaleUser) {
        if (connPhase != PHASE_WAIT_PROFILE_ACK) return
        val refWeightRaw = (user.initialWeight.coerceAtLeast(0f) * 100f).toInt()
        LogManager.d(TAG, "0x17 profile ACK → sending pre-meas 0x96 (refWeight=${"%.2f".format(refWeightRaw/100f)}kg)")
        writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd96(user, refWeightRaw))
        connPhase = PHASE_WAIT_HIST_ACK
    }

    /**
     * 0x16 – Scale acknowledged a 0x96 history-save write.
     *
     * During setup (PHASE_WAIT_HIST_ACK): this is the pre-meas config ACK.
     *   payload byte[5]=0x01 → config accepted; send 0x90 to arm BIA measurement.
     *   payload byte[5]=0x00 → config rejected (observed when refWeight=0, i.e., first-ever
     *                          measurement before any weight is stored in the user profile).
     *                          In this case 0x90 is skipped; BIA will not be available.
     *                          Weight-only measurement still proceeds via the scale's auto-start.
     * During/after measurement: these are post-BIA history-save ACKs → no action needed.
     */
    private fun onHistAck(data: ByteArray) {
        if (connPhase == PHASE_WAIT_HIST_ACK) {
            val accepted = data.size >= 6 && (data[5].toInt() and 0xFF) == 0x01
            if (accepted) {
                LogManager.d(TAG, "0x16 pre-meas ACK accepted → sending 0x90 start (BIA-enabled)")
                writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd90())
            } else {
                LogManager.w(TAG, "0x16 pre-meas config rejected by scale (refWeight=0 on first measurement?); BIA unavailable this session")
            }
            connPhase = PHASE_MEASURING
        } else {
            LogManager.d(TAG, "0x16 history-save ACK (phase=$connPhase)")
        }
    }

    /**
     * 0x10 – Mode confirmation callback sent by the scale in response to 0x90.
     *
     * Confirmed against Renpho reference capture and openScale test logs:
     *   byte[5]=0x00 → BIA mode active; scale will deliver 0x19 interim + 0x18 final BIA frames.
     *   byte[5]=0x01 → weight-only mode active; no BIA frames will follow.
     *
     * This is NOT a success/failure flag — 0x00 is the expected response for BIA measurements.
     * The original Renpho-app capture showed 0x01 only when the weight-only payload [01 00 00 00]
     * was used (byte[2]=0x00 = no BIA).
     */
    private fun onOpCallback(data: ByteArray) {
        if (data.size < 6) return
        when (data[5].toInt() and 0xFF) {
            0x00 -> LogManager.d(TAG, "0x10 mode callback: BIA mode active")
            0x01 -> LogManager.d(TAG, "0x10 mode callback: weight-only mode active")
            else -> LogManager.d(TAG, "0x10 mode callback: unknown byte[5]=0x${(data[5].toInt() and 0xFF).toString(16)}")
        }
    }

    /** 0x14 – Weight frame. Track the most recent weight for post-BIA 0x96 payload. */
    private fun onWeightFrame(data: ByteArray) {
        if (data.size < 12) return
        val raw = u16be(data, 8)
        if (raw > 0) {
            lastWeightRaw = raw
            LogManager.d(TAG, "Lefu 0x14 weight=${"%.2f".format(raw / 100f)}kg")
        }
    }

    /**
     * 0x19 – Intermediate BIA result (arrives mid-measurement, before final stable reading).
     * The scale expects 0x99 ACK + 0x96 save (using the intermediate weight) in response.
     *
     * Frame layout (after fragment reassembly, frame[0]=0x55):
     *   frame[11:12] = intermediate weight (u16be / 100 = kg)
     *   frame[13:14] = r1 primary resistance (Ω)
     *   frame[15:16] = r2 secondary resistance (Ω)
     */
    private fun onBiaInterim(data: ByteArray, user: ScaleUser) {
        val interimWeightRaw = if (data.size >= 13) u16be(data, 11) else lastWeightRaw
        LogManager.d(TAG, "0x19 interim BIA: weight=${"%.2f".format(interimWeightRaw/100f)}kg → 0x99 + 0x96")
        writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd99())
        writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd96(user, interimWeightRaw))
    }

    /**
     * 0x18 – Final BIA result. Extract primary resistance, then send two 0x96 history saves.
     *
     * Frame layout (frame[0]=0x55):
     *   frame[10:11] = final stable weight (u16be / 100 = kg)
     *   frame[12:13] = r1 primary resistance (Ω)  ← used for body-composition calculation
     *   frame[14:15] = r2 secondary resistance (Ω)
     */
    private fun onBiaFinal(data: ByteArray, user: ScaleUser) {
        if (data.size < 16) {
            LogManager.w(TAG, "0x18 frame too short (${data.size}); BIA data unavailable")
            return
        }
        val finalWeightRaw = u16be(data, 10)
        val r1 = u16be(data, 12)
        val r2 = u16be(data, 14)
        LogManager.i(TAG, "0x18 final BIA: weight=${"%.2f".format(finalWeightRaw/100f)}kg r1=${r1}Ω r2=${r2}Ω")

        if (r1 > 0) biaResistance = r1
        if (finalWeightRaw > 0) lastWeightRaw = finalWeightRaw

        // Send two post-BIA 0x96 history-save commands using the final stable weight
        val postSave = buildCmd96(user, lastWeightRaw)
        writeTo(SVC_MAIN, CHR_CUR_TIME, postSave)
        writeTo(SVC_MAIN, CHR_CUR_TIME, postSave)
        connPhase = PHASE_BIA_DONE
    }

    /** 0x11 – Measurement START or STOP. STOP triggers parsing and publishing (once per session). */
    private fun onStartStop(data: ByteArray, user: ScaleUser) {
        if (data.size < 6) return
        val flag = data[START_STOP_IDX].toInt() and 0xFF
        if (flag != 0) {
            if (hasPublished) {
                // Second step-on within same connection: restart the setup sequence.
                resetState()
                writeTo(SVC_MAIN, CHR_CUR_TIME, buildCmd97())
                LogManager.i(TAG, "Re-started session; wrote 0x97 user-profile")
            } else {
                LogManager.d(TAG, "0x11 START (flag=$flag)")
            }
        } else if (hasPublished) {
            LogManager.d(TAG, "0x11 STOP (already published; ignoring step-off frames)")
        } else {
            LogManager.d(TAG, "0x11 STOP → publishing")
            publishMeasurement(user)
        }
    }

    override fun onDisconnected() = resetState()

    // ── Command builders ──────────────────────────────────────────────────────

    /**
     * Build a Lefu frame: 55 AA [cmd] 00 [len] [payload…] [checksum]
     * Checksum = sum of all preceding bytes mod 256.
     */
    private fun buildLefuFrame(cmd: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size + 1)
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        frame[2] = cmd.toByte()
        frame[3] = 0x00
        frame[4] = payload.size.toByte()
        payload.copyInto(frame, 5)
        var sum = 0
        for (i in 0 until frame.size - 1) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        frame[frame.size - 1] = sum.toByte()
        return frame
    }

    /**
     * 0x97 user-profile command (9-byte payload):
     *   01 00 00 [unix_ts_be4] 01 06
     *
     * unix_ts = current time in seconds since Unix epoch, big-endian uint32.
     * Bytes [7]=0x01 and [8]=0x06 are constants (confirmed across male and female profiles —
     * sex is NOT encoded here; see buildCmd96 for sex encoding).
     */
    private fun buildCmd97(): ByteArray {
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        val payload = byteArrayOf(
            0x01, 0x00, 0x00,
            (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(),
            0x01,  // constant (confirmed; NOT sex)
            0x06   // constant (confirmed)
        )
        return buildLefuFrame(0x97, payload)
    }

    /**
     * 0x96 config/history-save command (14-byte payload):
     *   [sex] [year_be2] [month] [day] [height_mm_be2] 00 00 [weightRaw_be2] [mode] 01 05
     *
     * sex      = FIRST byte: 0x11 male, 0x21 female (confirmed by multi-profile capture).
     * year/month/day = extracted from user.birthday (Calendar.MONTH is 0-indexed, +1 for 1–12).
     * height_mm = user.bodyHeight (cm) × 10, big-endian uint16.
     * weightRaw = weight in units of 0.01 kg, big-endian uint16.
     * mode     = 0xAA standard, 0x6A athlete (EXTREME activity maps to athlete mode).
     * 0x01     = constant (confirmed; the byte after mode is always 0x01, not sex).
     * 0x05     = constant trailing byte (confirmed).
     */
    private fun buildCmd96(user: ScaleUser, weightRaw: Int): ByteArray {
        val cal      = Calendar.getInstance().also { it.time = user.birthday }
        val year     = cal.get(Calendar.YEAR)
        val month    = cal.get(Calendar.MONTH) + 1      // Calendar.MONTH: 0=Jan…11=Dec
        val day      = cal.get(Calendar.DAY_OF_MONTH)
        val heightMm = (user.bodyHeight * 10f).toInt()  // cm → mm, big-endian uint16
        val sexByte  = if (user.gender.isMale()) 0x11 else 0x21
        val modeByte = if (YunmaiLib.toYunmaiActivityLevel(user.activityLevel) == 1) 0x6A else 0xAA
        val w        = weightRaw.coerceIn(0, 0xFFFF)    // guard against u16 overflow in payload
        val payload  = byteArrayOf(
            sexByte.toByte(),
            (year ushr 8).toByte(), year.toByte(),
            month.toByte(),
            day.toByte(),
            (heightMm ushr 8).toByte(), heightMm.toByte(),
            0x00, 0x00,
            (w ushr 8).toByte(), w.toByte(),
            modeByte.toByte(),
            0x01,  // constant (confirmed; NOT sex)
            0x05   // constant (confirmed)
        )
        return buildLefuFrame(0x96, payload)
    }

    /**
     * 0x90 start-measurement command.
     * Payload byte[2] = 0x01 enables BIA mode (vs. 0x00 which gives weight-only).
     */
    private fun buildCmd90(): ByteArray =
        buildLefuFrame(0x90, byteArrayOf(0x01, 0x00, 0x01, 0x00))

    /** 0x99 ACK to the 0x19 intermediate-BIA frame. */
    private fun buildCmd99(): ByteArray =
        buildLefuFrame(0x99, byteArrayOf(0x01))

    // ── Publish ───────────────────────────────────────────────────────────────

    private fun publishMeasurement(user: ScaleUser) {
        val weightKg = lastWeightRaw / 100.0f
        if (weightKg < 0.5f || weightKg > 300f) {
            LogManager.w(TAG, "No valid weight at publish time (lastWeightRaw=$lastWeightRaw); skipped")
            resetState()
            return
        }

        acc.weight = weightKg
        LogManager.i(TAG, "Weight: ${"%.2f".format(weightKg)}kg  BIA resistance: ${biaResistance}Ω")

        if (biaResistance > 0) {
            // Body composition formula: Renpho's proprietary formula is not publicly available
            // (Renpho customer support confirmed: "We apologize, but our proprietary formulation
            // cannot be disclosed."). TrisaBodyAnalyzeLib is used here as the closest available
            // approximation: it is a BMI-polynomial formula that gives results ~2–4% closer to
            // Renpho's reported values than YunmaiLib for this scale's resistance range. The QN
            // resistance-to-impedance conversion from QNHandler is applied first, as QN scales
            // share the same Lefu/Yunmai hardware lineage as this scale.
            val sex  = if (user.gender.isMale()) 1 else 0
            val calc = TrisaBodyAnalyzeLib(sex, user.age, user.bodyHeight)
            val imp  = if (biaResistance < 410) 3.0f else 0.3f * (biaResistance - 400f)
            val fat  = calc.getFat(weightKg, imp)
            acc.impedance = biaResistance.toDouble()
            acc.fat       = fat
            acc.muscle    = calc.getMuscle(weightKg, imp)
            acc.water     = calc.getWater(weightKg, imp)
            acc.bone      = calc.getBone(weightKg, imp)
            acc.lbm       = weightKg * (100f - fat) / 100f
            LogManager.i(TAG, "Body composition: fat=${acc.fat}% muscle=${acc.muscle}%")
        }

        acc.userId   = user.id
        acc.dateTime = Date()
        publish(snapshot(acc))

        resetState()
        hasPublished = true  // set after resetState so step-off STOPs are ignored
    }

    private fun snapshot(m: ScaleMeasurement) = ScaleMeasurement().apply {
        userId      = m.userId
        dateTime    = m.dateTime
        weight      = m.weight
        fat         = m.fat
        muscle      = m.muscle
        water       = m.water
        bone        = m.bone
        lbm         = m.lbm
        visceralFat = m.visceralFat
        impedance   = m.impedance
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun u16be(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun resetState() {
        acc.apply {
            userId = -1; dateTime = null; weight = 0f; fat = 0f; muscle = 0f
            water = 0f; bone = 0f; lbm = 0f; visceralFat = 0f; impedance = 0.0
        }
        connPhase     = PHASE_WAIT_PROFILE_ACK
        biaResistance = 0
        lastWeightRaw = 0
        lefuFrag      = null
        hasPublished  = false
    }
}
