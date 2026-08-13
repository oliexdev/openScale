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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * FitTrack Dara 2.0 body-composition scale.
 *
 * The FitTrack Dara (vendor app `com.elink.fittrackhealth.pro`, an eLink/Icomon platform) is part of
 * the same "MGB" family as [MGBHandler]/[TaylorBIAHandler]: it exposes service 0xFFB0 and uses the
 * `AC 02 … CC`/checksum framing. It advertises as "FitTrack" (not swan/icomon/yg), so it needs its
 * own name match; register this handler *before* [MGBHandler] in ScaleFactory so it wins (MGBHandler
 * also claims service 0xFFB0).
 *
 * ## GATT layout
 *   Service 0xFFB0:
 *     0xFFB1 – config/command write (App → Scale, Write-No-Response)
 *     0xFFB2 – data NOTIFY          (Scale → App)
 *   (A second custom service d618d000-…-000000000000 is present but unused by this protocol.)
 *
 * ## Framing (reverse-engineered from a btsnoop_hci.log capture)
 * Short control/telemetry frames are 8 bytes:
 *     AC 02 <b2> <b3> <b4> <b5> <chan> <cksum>
 *   - cksum = (b2 + b3 + b4 + b5 + chan) & 0xFF     (see [checksum8])
 *   - <chan> (byte 6) selects the logical stream:
 *       0xCC = command / handshake (App → Scale, and echoes)
 *       0xCE = live (unstable) weight  (Scale → App)
 *       0xCA = stabilised weight       (Scale → App)
 *       0xCB = final body-composition data dump (Scale → App)
 *
 * Weight frames (chan 0xCE/0xCA): b2/b3 hold the weight big-endian in 0.1 display-unit steps (see
 * [weightDeciUnits]). The scale transmits in whatever unit we set via `FE 06 <unit>`, so we convert
 * to kg with [ConverterUtils.toKilogram] (WeightUnit.toInt(): KG=0, LB=1, ST=2).
 *
 * ## Final body-composition dump (chan 0xCB)
 * When the reading locks, the scale sends the computed metrics as index/value pairs
 * `AC 02 FE <idx> <hi> <lo> CB <cksum>`, then repeats them packed into two 20-byte notifications
 * (`AC 02 FF …` + `01 00 …`) mirroring the [MGBHandler] layout. The individual index frames are the
 * authoritative source; the packed frames are used only as a completion signal.
 *
 * Index → metric (each index has its own scale factor; most are ÷10, but visceral fat, metabolic
 * age and BMR are integers):
 *   0 weight · 1 BMI · 2 fat % · 3 subcutaneous fat % · 4 visceral index · 5 lean mass kg ·
 *   6 BMR kcal · 7 bone kg · 8 water % · 9 metabolic age · 10 protein % · 252 (0xFC) end marker.
 * [applyDumpIndex] stores the fields [ScaleMeasurement] supports; BMI/BMR are derived by openScale,
 * and subcutaneous fat / metabolic age have no field.
 */
class FitTrackDaraHandler : ScaleDeviceHandler() {

    companion object {
        /** Consecutive identical live readings that mark a weight as final (~1 s at the scale's rate). */
        private const val STABLE_FRAMES = 4

        /**
         * Grace period after a settled weight before publishing without a dump. The body-composition
         * dump (chan 0xCB) follows a few seconds after the weight settles; if it never arrives (e.g.
         * the scale requires the vendor's AE/AD pairing, which we don't replay), we still record weight.
         */
        private const val DUMP_GRACE_MS = 10_000L

        // Frame constants
        private const val HDR = 0xAC
        private const val CHAN_CMD = 0xCC
        private const val CHAN_WEIGHT_LIVE = 0xCE
        private const val CHAN_WEIGHT_STABLE = 0xCA
        private const val CHAN_DUMP = 0xCB

        /** 8-byte control-frame checksum: (bytes[2] + bytes[3] + bytes[4] + bytes[5] + bytes[6]) & 0xFF. */
        fun checksum8(b: ByteArray): Int {
            var s = 0
            for (i in 2..6) s += b[i].toInt() and 0xFF
            return s and 0xFF
        }

        /** Weight as tenths of a display unit (kg/lb/st) from the two big-endian value bytes. */
        fun weightDeciUnits(hi: Byte, lo: Byte): Int =
            ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
    }

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_CFG: UUID = uuid16(0xFFB1)   // config/command writes (App → Scale)
    private val CHAR_DATA: UUID = uuid16(0xFFB2)  // measurement notifications (Scale → App)

    /** Latest live weight (kg) seen this session; published once it settles / on fallback. */
    private var pendingWeightKg: Float = 0f
    /** Run length of identical live readings; publishing weight-only triggers at [STABLE_FRAMES]. */
    private var stableCount = 0
    /** Deci-unit value of the previous live reading (for the identical-run counter). */
    private var lastDeci = -1
    /** True once we've published this session (publishing is single-shot). */
    private var published = false
    /** Grace timer that publishes if the body-comp dump never arrives. */
    private var dumpJob: Job? = null
    /** Measurement accumulated across the dump's index frames, published when the dump completes. */
    private var pending = ScaleMeasurement()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // Match on the advertised name so we don't collide with the other 0xFFB0 handlers.
        val name = device.name.uppercase(Locale.ROOT)
        if (!name.startsWith("FITTRACK")) return null

        return DeviceSupport(
            displayName = "FitTrack Dara 2.0",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.USER_SYNC,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.UNIT_CONFIG,
            ),
            // Weight + fat/water/bone/visceral are decoded from real hardware (see [applyDumpIndex]).
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Handlers are long-lived singletons reused across connections, so reset per-session state.
        pendingWeightKg = 0f
        stableCount = 0
        lastDeci = -1
        published = false
        dumpJob?.cancel()
        dumpJob = null
        pending = ScaleMeasurement()

        // 1) Subscribe to measurement notifications on FFB2.
        setNotifyOn(SERVICE, CHAR_DATA)

        // 2) MGB-family init sequence on FFB1. Mirrors what the FitTrack app sent in the capture
        //    (F7/FA magic, FB user profile, FE 06 unit). We intentionally do NOT replay the vendor's
        //    AE 03 … / AD 01 … challenge-response: it is dynamic (the AD payload looks encrypted) so it
        //    cannot be reproduced from a capture, and weight streaming worked without it.
        writeCfg(0xF7, 0, 0, 0)   // magic init #1
        writeCfg(0xFA, 0, 0, 0)   // magic init #2

        // User profile: sex (1=male, 2=female), age in years, height in cm.
        val sexByte = if (user.gender.isMale()) 1 else 2
        val heightCm = user.bodyHeight.toInt().coerceIn(0, 255)
        writeCfg(0xFB, sexByte, user.age.coerceIn(0, 255), heightCm)

        // Date (year since 2000, month 1-12, day) and time (HH, MM, SS) from the phone clock.
        val now = Calendar.getInstance()
        val yy = (now.get(Calendar.YEAR) - 2000).coerceIn(0, 99)
        writeCfg(0xFD, yy, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        writeCfg(0xFC, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))

        // Display unit the scale should transmit in: WeightUnit.toInt() → KG=0, LB=1, ST=2.
        writeCfg(0xFE, 6, user.scaleUnit.toInt(), 0)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        dumpJob?.cancel()
        dumpJob = null
        // Last-chance publish if data was seen but nothing was finalised.
        if (!published) publishFinal()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_DATA) return

        // Dump second fragment ("01 00 …"): the per-index values already arrived as FE frames, so
        // this only signals the dump is complete → publish.
        if (data.size >= 20 && (data[0].toInt() and 0xFF) == 0x01 && (data[1].toInt() and 0xFF) == 0x00) {
            publishFinal()
            return
        }

        if (data.size < 8 || (data[0].toInt() and 0xFF) != HDR) return

        val b2 = data[2].toInt() and 0xFF
        val chan = data[6].toInt() and 0xFF

        // Dump first fragment ("AC 02 FF 00 02 21 <yy mm dd HH MM SS> <idx0..3>"): packed weight/BMI/
        // fat/subcut, redundant with the FE frames below but re-applied idempotently if those are missed.
        if (data.size >= 20 && b2 == 0xFF) {
            for ((i, off) in listOf(0 to 12, 1 to 14, 2 to 16, 3 to 18)) {
                applyDumpIndex(i, weightDeciUnits(data[off], data[off + 1]))
            }
            armDumpGrace()
            return
        }

        // 8-byte control/telemetry frame — validate the checksum before trusting it.
        if (data.size == 8 && checksum8(data) != (data[7].toInt() and 0xFF)) {
            logD("bad checksum, dropping frame ${data.toHexPreview(8)}")
            return
        }

        when (chan) {
            CHAN_WEIGHT_LIVE -> handleWeightFrame(data, stable = false, user = user)
            CHAN_WEIGHT_STABLE -> handleWeightFrame(data, stable = true, user = user)
            // FE index/value pairs (AC 02 FE <idx> <hi> <lo> CB); other 0xCB frames (FA/FB) ignored.
            CHAN_DUMP -> if (b2 == 0xFE) applyDumpIndex(data[3].toInt() and 0xFF, weightDeciUnits(data[4], data[5]))
            CHAN_CMD -> { /* command echo / handshake; nothing to do */ }
            else -> logD("unknown chan 0x%02X frame %s".format(chan, data.toHexPreview(8)))
        }
    }

    // --- Weight (live + stable) ------------------------------------------------

    private fun handleWeightFrame(data: ByteArray, stable: Boolean, user: ScaleUser) {
        val deci = weightDeciUnits(data[2], data[3])
        // Ignore the 0-weight idle frames and obviously-out-of-range noise (< 600.0 display units).
        if (deci <= 0 || deci >= 6000) return

        val weightKg = ConverterUtils.toKilogram(deci / 10.0f, user.scaleUnit)
        pendingWeightKg = weightKg

        stableCount = if (deci == lastDeci) stableCount + 1 else 1
        lastDeci = deci

        // Don't publish on a settled weight yet — arm a grace timer and let the body-comp dump win;
        // the timer only fires if the dump never arrives.
        if (stable || stableCount >= STABLE_FRAMES) armDumpGrace()
    }

    private fun armDumpGrace() {
        if (dumpJob != null || published) return
        dumpJob = scope.launch {
            delay(DUMP_GRACE_MS)
            if (!published && pendingWeightKg > 0f) {
                logD("body-composition dump not received within $DUMP_GRACE_MS ms; publishing what we have")
                publishFinal()
            }
        }
    }

    /** Store the dump indices that map to a [ScaleMeasurement] field (see class docs for the full set). */
    private fun applyDumpIndex(idx: Int, value: Int) {
        when (idx) {
            0 -> pending.weight = ConverterUtils.toKilogram(value / 10.0f, currentAppUser().scaleUnit)
            2 -> pending.fat = value / 10.0f            // %
            4 -> pending.visceralFat = value.toFloat()  // index (no ÷10)
            5 -> pending.lbm = value / 10.0f            // kg
            7 -> pending.bone = value / 10.0f           // kg
            8 -> pending.water = value / 10.0f          // %
            10 -> pending.protein = value / 10.0f       // %
        }
    }

    // --- Publish ---------------------------------------------------------------

    /**
     * Publish the accumulated measurement exactly once. Weight comes from the dump (idx 0) when
     * present, otherwise falls back to the latest live-stream weight so a reading is still recorded
     * even if the body-composition dump never arrives. Any body-comp fields set by [applyDumpIndex]
     * ride along.
     */
    private fun publishFinal() {
        if (published) return
        val weightKg = if (pending.weight > 0f) pending.weight else pendingWeightKg
        if (weightKg <= 0f) return
        published = true
        dumpJob?.cancel()
        dumpJob = null
        pending.weight = weightKg
        pending.dateTime = Date()
        logD("publishing weight=${pending.weight} fat=${pending.fat} water=${pending.water} " +
                "lbm=${pending.lbm} bone=${pending.bone} visceral=${pending.visceralFat} protein=${pending.protein}")
        publish(pending)
        requestDisconnect()
    }

    // --- Command writer --------------------------------------------------------

    /**
     * Writes an 8-byte config packet to 0xFFB1 (MGB-family framing):
     * `[AC, 02, b2, b3, b4, b5, CC, checksum]`, checksum = (b2 + b3 + b4 + b5 + CC) & 0xFF.
     * The scale uses Write-No-Response on this characteristic.
     */
    private fun writeCfg(b2: Int, b3: Int, b4: Int, b5: Int) {
        val buf = ByteArray(8)
        buf[0] = HDR.toByte()
        buf[1] = 0x02
        buf[2] = (b2 and 0xFF).toByte()
        buf[3] = (b3 and 0xFF).toByte()
        buf[4] = (b4 and 0xFF).toByte()
        buf[5] = (b5 and 0xFF).toByte()
        buf[6] = CHAN_CMD.toByte()
        buf[7] = checksum8(buf).toByte()
        writeTo(SERVICE, CHAR_CFG, buf, withResponse = false)
    }
}
