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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Taylor 5331891 BIA (Body Composition) bathroom scale.
 *
 * Advertises as "5331891 BIA Scale" and exposes the same GATT layout as the MGB family
 * (service 0xFFB0), which is why [MGBHandler] previously mis-claimed it. The Taylor's NOTIFY
 * payload format is different, so it needs its own handler. Register this handler *before*
 * [MGBHandler] in ScaleFactory so it wins the name match.
 *
 * Service 0xFFB0:
 *   0xFFB1 – config write (App → Scale)
 *   0xFFB2 – data NOTIFY  (Scale → App, 20 bytes)
 *
 * NOTIFY frame (20 bytes), reverse-engineered from btsnoop_hci.log:
 *   AC 27 <flag> <chan> <hi> <lo> 02 00 00 01 00 00 00 00 00 00 00 24 D5 <cksum>
 *    0  1    2      3     4    5  ......................................  17 18  19
 *   - byte[2] flag : 0x00 = live/streaming, 0x80 = stable/locked
 *   - byte[3] chan : weight channel; the low bit is weight bit 16, so 0x8C = weight < 65.536 kg
 *                    (also the idle frame 8C 00 00) and 0x8D = weight ≥ 65.536 kg
 *   - byte[4..5]   : value, big-endian
 *   - weight_kg    = (((chan & 0x01) << 16) | (hi << 8) | lo) / 1000.0
 *                    Verified across two captures:
 *                      AC 27 80 8D 2F 52 → 77.650 kg = 171.2 lb
 *                      AC 27 80 8D 32 40 → 78.400 kg = 172.8 lb (≈ the app's 173.0 lb)
 *
 * Summary frame (emitted once when the reading locks):
 *   AC 27 01 00 02 <idx> 01 80 8D <hi> <lo> 00 …   — echoes the locked weight at bytes[8..10].
 *
 * Publishing: in practice, under openScale's basic init the scale never emits a stable (0x80) or
 * summary (0x01) frame — it just streams live values that plateau. So we publish using a stability
 * heuristic: once the same weight has repeated [STABLE_FRAMES] times in a row the reading is final.
 * The explicit stable/summary frames are still honored if they ever arrive, and [armFallback] is a
 * last-resort timer for the case where the weight never settles.
 *
 * Body composition: this scale transmits WEIGHT ONLY over BLE. A clean single-measurement capture
 * contains nothing but 0x8D weight frames (plus one idle 0x8C 00 00) — no impedance channel. The
 * Taylor app computes fat/water/muscle/etc. on the phone from weight + the user profile using vendor
 * formulas that are not exposed over Bluetooth, so they cannot be reproduced here. We therefore
 * publish weight only and declare BODY_COMPOSITION as a (theoretical) capability but NOT implemented.
 */
class TaylorBIAHandler : ScaleDeviceHandler() {

    companion object {
        /**
         * Number of consecutive identical weight readings that marks the measurement as final.
         * The scale's settled value repeats verbatim once the user is steady, whereas the step-on
         * ramp values are all distinct — so a short run of identical frames (~1 s at ~4 Hz) is a
         * reliable "stable" signal even though this scale never sends an explicit stable/summary frame.
         */
        private const val STABLE_FRAMES = 4

        /**
         * Last-resort timeout (ms): if the weight never settles into a [STABLE_FRAMES] run (e.g. the
         * user keeps shifting), publish the latest reading anyway so a measurement is still recorded.
         */
        private const val FALLBACK_DELAY_MS = 8000L

        /**
         * Decode the weight (kg) from a NOTIFY frame's channel/hi/lo bytes.
         *
         * The scale reports grams as a 17-bit big-endian value: bits 0-15 are [hi][lo] and bit 16 is
         * the low bit of the channel byte (0x8C → 0, 0x8D → +65 536 g). Hence:
         *   weight_kg = (((chan & 0x01) << 16) | (hi << 8) | lo) / 1000.0
         *
         * Pure and side-effect free so it can be unit-tested directly (see TaylorBIAHandlerTest).
         */
        fun decodeWeightKg(chan: Byte, hi: Byte, lo: Byte): Float {
            val g = ((chan.toInt() and 0x01) shl 16) or
                    ((hi.toInt() and 0xFF) shl 8) or
                    (lo.toInt() and 0xFF)
            return g / 1000.0f
        }
    }

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_CFG: UUID = uuid16(0xFFB1)   // FFB1: config/command writes (App → Scale)
    private val CHAR_DATA: UUID = uuid16(0xFFB2)  // FFB2: measurement notifications (Scale → App)

    /** Most recent live (non-zero) weight seen this session; the value we publish once it settles. */
    private var pendingWeightKg: Float = 0f

    /** Run-length of consecutive identical readings; when it hits [STABLE_FRAMES] we publish. */
    private var stableCount = 0

    /** Guards against publishing more than once per session (stability, summary and fallback can all fire). */
    private var published = false

    /** Last-resort timer (see [FALLBACK_DELAY_MS]) for the case where the weight never settles. */
    private var fallbackJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Match on the advertised name (NOT service 0xFFB0) so we don't re-collide with MGBHandler.
        val name = device.name.uppercase(Locale.ROOT)
        if (!name.startsWith("5331891") && !name.contains("BIA SCALE")) return null

        return DeviceSupport(
            displayName = "Taylor 5331891 BIA Scale",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.USER_SYNC,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.UNIT_CONFIG,
            ),
            // The scale transmits weight only over BLE; body composition is computed app-side by the
            // vendor and is not available to us, so it stays out of `implemented`.
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Handlers are long-lived singletons reused across connections (see ScaleFactory), so reset
        // all per-session state at the start of every connection.
        pendingWeightKg = 0f
        stableCount = 0
        published = false
        fallbackJob?.cancel()
        fallbackJob = null

        // 1) Subscribe to measurement notifications on FFB2.
        setNotifyOn(SERVICE, CHAR_DATA)

        // 2) Minimal MGB-style init sequence on FFB1. We don't strictly need the user/clock data to
        //    read weight, but the scale only began streaming weight frames after this exact 8-byte
        //    "AC 02 .. CC" handshake in the openScale debug capture, so we replay it verbatim.
        writeCfg(0xF7, 0, 0, 0)   // magic init #1
        writeCfg(0xFA, 0, 0, 0)   // magic init #2

        // User profile: sex (1=male, 2=female), age in years, height in cm.
        val sexByte = if (user.gender.isMale()) 1 else 2
        val heightCm = user.bodyHeight.toInt().coerceAtLeast(0)
        writeCfg(0xFB, sexByte, user.age, heightCm)

        // Date (year since 2000, month 1-12, day) and time (HH, MM, SS) from the phone clock.
        val now = Calendar.getInstance()
        val yy = (now.get(Calendar.YEAR) - 2000).coerceIn(0, 99)
        writeCfg(0xFD, yy, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        writeCfg(0xFC, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))

        // Display unit: legacy WeightUnit.toInt() mapping (KG=1, LB=2, ST=3).
        writeCfg(0xFE, 6, user.scaleUnit.toInt(), 0)

        // Prompt the user to step on; the result arrives asynchronously via onNotification().
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        fallbackJob?.cancel()
        fallbackJob = null
        // Last-chance weight-only publish if a live weight was seen but never finalized.
        if (!published && pendingWeightKg > 0f) {
            published = true
            publish(ScaleMeasurement().apply {
                dateTime = Date()
                weight = pendingWeightKg
            })
        }
    }

    // `user` is unused: this scale reports only weight, which needs no per-user decoding.
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_DATA) return
        // Every valid measurement frame is 20 bytes and starts with the "AC 27" header; ignore the rest.
        if (data.size < 20 || data[0].toInt() and 0xFF != 0xAC || data[1].toInt() and 0xFF != 0x27) return

        val flag = data[2].toInt() and 0xFF   // 0x80 = stable/locked, 0x01 = summary record, else live
        val chan = data[3].toInt() and 0xFF   // weight channel (0x8C/0x8D) or 0x00 for the summary frame

        // Summary frame: AC 27 01 00 02 <idx> 01 80 <chan> <hi> <lo> 00 …
        // It echoes the locked weight at bytes[8..10]; publishWeight() is idempotent so it is safe even
        // when the preceding stable frame already published this same measurement.
        if (flag == 0x01 && chan == 0x00) {
            publishWeight(weightKg(data[8], data[9], data[10]))
            return
        }

        // Weight streams on the 0x8C/0x8D channel: the channel byte's low bit is weight bit 16, so
        // readings < 65.536 kg use 0x8C and readings ≥ 65.536 kg use 0x8D. Idle is 8C 00 00, which
        // decodes to 0 and is filtered by the w > 0 guard below.
        if (chan == 0x8C || chan == 0x8D) {
            val w = weightKg(data[3], data[4], data[5])
            if (w <= 0f) return

            // Stability detection: count how many identical readings arrive back-to-back. The settled
            // value repeats exactly while the step-on ramp values are all distinct, so once we've seen
            // the same weight STABLE_FRAMES times in a row we treat it as final and publish immediately.
            stableCount = if (w == pendingWeightKg) stableCount + 1 else 1
            pendingWeightKg = w

            if (flag == 0x80 || stableCount >= STABLE_FRAMES) {
                publishWeight(w)            // explicit stable frame, or our own stability heuristic
            } else {
                armFallback()               // safety net while the reading is still moving
            }
        }
    }

    // --- Finalize / publish ---------------------------------------------------

    /**
     * Emit the final measurement exactly once, then close the link. Idempotent: the first caller wins
     * (stability run, stable frame, summary frame or fallback timer), the rest are no-ops via [published].
     */
    private fun publishWeight(weightKg: Float) {
        if (published || weightKg <= 0f) return
        published = true
        fallbackJob?.cancel()
        fallbackJob = null

        // Weight-only: the scale transmits no impedance/body-composition data over BLE.
        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            weight = weightKg
        }

        publish(measurement)
        requestDisconnect() // free the connection; this scale sends nothing more after a locked reading
    }

    /**
     * Safety net: armed on the first live reading, fires once after [FALLBACK_DELAY_MS]. Normally the
     * stability heuristic publishes first; this only triggers if the weight never settles into a
     * [STABLE_FRAMES] run, so we still record the latest value instead of hanging until disconnect.
     */
    private fun armFallback() {
        if (fallbackJob != null || published) return
        fallbackJob = scope.launch {
            delay(FALLBACK_DELAY_MS)
            if (!published && pendingWeightKg > 0f) {
                logD("Weight never stabilized within ${FALLBACK_DELAY_MS} ms; publishing latest reading")
                publishWeight(pendingWeightKg)
            }
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun weightKg(chan: Byte, hi: Byte, lo: Byte): Float = decodeWeightKg(chan, hi, lo)

    /**
     * Writes an 8-byte config packet to 0xFFB1 (same framing as the MGB family):
     * [AC, 02, b2, b3, b4, b5, CC, checksum], checksum = (b2 + b3 + b4 + b5 + 0xCC) & 0xFF.
     */
    private fun writeCfg(b2: Int, b3: Int, b4: Int, b5: Int) {
        val buf = ByteArray(8)
        buf[0] = 0xAC.toByte()
        buf[1] = 0x02.toByte()
        buf[2] = (b2 and 0xFF).toByte()
        buf[3] = (b3 and 0xFF).toByte()
        buf[4] = (b4 and 0xFF).toByte()
        buf[5] = (b5 and 0xFF).toByte()
        buf[6] = 0xCC.toByte()
        val sum = (buf[2].toUByte().toInt() +
                buf[3].toUByte().toInt() +
                buf[4].toUByte().toInt() +
                buf[5].toUByte().toInt() +
                buf[6].toUByte().toInt()) and 0xFF
        buf[7] = sum.toByte()
        writeTo(SERVICE, CHAR_CFG, buf, withResponse = true)
    }
}
