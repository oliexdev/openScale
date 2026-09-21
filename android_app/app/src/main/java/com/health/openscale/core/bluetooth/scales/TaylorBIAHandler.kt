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
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * FitDays/icomon scales that speak the 20-byte `AC 27` protocol on service 0xFFB0:
 *
 *  - **Taylor 5331891 BIA** — advertises as "5331891 BIA Scale" (PR #1393).
 *  - **ALPHA Smart Scale PRO 3** (model SCP-03, sold in Poland) — advertises as "MY_SCALE";
 *    weight, impedance and the weigh-ins it stored while the phone was away (issue #1358).
 *
 * Both share the GATT layout with the MGB family, which is why [MGBHandler] used to mis-claim
 * them; this handler must stay ahead of it in ScaleFactory.
 *
 *   0xFFB1 – config write (App → Scale)
 *   0xFFB2 – data NOTIFY  (Scale → App)
 *
 * ## Frames
 *
 * Every frame is 20 bytes in both directions: `[AC 27][body @2..17][wire type @18][checksum @19]`.
 * The phone writes `sum(raw[2..18]) & 0xFF`; the scale keeps only the low five bits of that sum and
 * puts a device flag in bit 5 — set on every Taylor frame, clear on every ALPHA one — so only
 * `raw[19] & 0x1F` is the checksum. Verified against every frame in both vendor-app captures.
 *
 * | wire type | direction | content |
 * |---|---|---|
 * | `0xD5` | scale → phone | live weight, byte 2 = `0x80` once the display locked |
 * | `0xD6` | scale → phone | final result: impedance (u16 @4) + weight (@8) |
 * | `0xD8` | scale → phone | stored record: Unix time BE (@3) + weight (@8), must be ACKed |
 * | `0xD0` | phone → scale | Unix time BE + `08 00 00` + height cm + `00 00` + age + sex |
 * | `0xDF` | phone → scale | control: `04 <wire type>` = ACK, `01` = sent once after the profile |
 *
 * Weight is the low 18 bits of the big-endian 24-bit word (`69 5B 8A` → 88 970 g), so the Taylor's
 * `8C`/`8D` "channel" is simply that word's top byte. Timestamps are Unix seconds, big-endian.
 * Reverse-engineered from HCI snoop captures of the vendor apps.
 *
 * ## Per-model differences
 *
 * Only two things differ, both selected by [model]:
 *
 *  - **Handshake.** The Taylor gets the old 8-byte `AC 02 … CC` MGB config frames it was verified
 *    with; the ALPHA gets the `0xD0`/`0xDF` frames FitDays writes.
 *  - **Publishing.** The ALPHA publishes on the `0xD6` final result and ACKs it. Under openScale's
 *    handshake the Taylor never sends a locked or final frame in practice — it just streams live
 *    values that plateau — so it publishes once the same weight has repeated [STABLE_FRAMES]
 *    times, with [armFallback] as a last resort.
 *
 * The Taylor's own app does get a `0xD6` frame out of it, impedance included (528 Ω next to the
 * 78.400 kg reading in the PR #1393 capture) — but only after writing the same `0xD0` profile the
 * ALPHA gets. Whether openScale's 8-byte handshake can be swapped for that one is untested, so it
 * stays as it is; if a `0xD6` does arrive, its impedance is published.
 *
 * Neither scale transmits body composition: both vendor apps compute fat/water/muscle/… on the
 * phone with formulas we do not have. Where an impedance arrives, openScale computes its own with
 * [StandardImpedanceLib] instead — the same science-based path a dozen other handlers take.
 */
class TaylorBIAHandler : ScaleDeviceHandler() {

    /** The products this driver serves. */
    internal enum class Model { TAYLOR_BIA, ALPHA_PRO3 }

    /** Decoded NOTIFY frame. */
    internal sealed class ScaleFrame {
        /** Live weight while someone stands on the scale (wire type 0xD5). */
        data class LiveWeight(val weightGrams: Int, val locked: Boolean) : ScaleFrame()

        /** Final result of a weigh-in (wire type 0xD6); [impedanceOhms] is 0 when not measured. */
        data class FinalResult(val weightGrams: Int, val impedanceOhms: Int, val locked: Boolean) : ScaleFrame()

        /** A weigh-in done without the app, replayed on connect (wire type 0xD8). */
        data class StoredRecord(val timestampEpochSeconds: Long, val weightGrams: Int) : ScaleFrame()

        /** A well-formed frame this handler does not decode. */
        data class Unknown(val wireType: Int) : ScaleFrame()
    }

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_CFG: UUID = uuid16(0xFFB1)   // FFB1: config/command writes (App → Scale)
    private val CHAR_DATA: UUID = uuid16(0xFFB2)  // FFB2: measurement notifications (Scale → App)

    /** Latched by [supportFor] when the device is matched, re-checked against the peripheral name. */
    private var model: Model = Model.TAYLOR_BIA

    /** Most recent live (non-zero) weight seen this session; the value we publish once it settles. */
    private var pendingWeightGrams = 0

    /** Run-length of consecutive identical readings; when it hits [STABLE_FRAMES] we publish. */
    private var stableCount = 0

    /** Guards against publishing more than one final measurement per session. */
    private var published = false

    /** Last-resort timer (see [FALLBACK_DELAY_MS]) for the case where the weight never settles. */
    private var fallbackJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val matched = modelFor(device.name) ?: return null
        // "MY_SCALE" is a generic enough name to demand the service as well; the Taylor's is not.
        if (matched == Model.ALPHA_PRO3 && device.serviceUuids.none { it == SERVICE }) return null

        model = matched
        return when (matched) {
            Model.TAYLOR_BIA -> DeviceSupport(
                displayName = "Taylor 5331891 BIA Scale",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.UNIT_CONFIG,
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                ),
                linkMode = LinkMode.CONNECT_GATT
            )

            Model.ALPHA_PRO3 -> DeviceSupport(
                displayName = "ALPHA Smart Scale PRO 3",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                ),
                linkMode = LinkMode.CONNECT_GATT
            )
        }
    }

    override fun onConnected(user: ScaleUser) {
        // Handlers are long-lived singletons reused across connections (see ScaleFactory), so reset
        // all per-session state at the start of every connection.
        pendingWeightGrams = 0
        stableCount = 0
        published = false
        fallbackJob?.cancel()
        fallbackJob = null
        // supportFor() latched the model when the device was matched; once connected the peripheral
        // itself is the better source, in case another device was scanned in between.
        getPeripheral()?.name?.let { modelFor(it) }?.let { model = it }

        // 1) Subscribe to measurement notifications on FFB2.
        setNotifyOn(SERVICE, CHAR_DATA)

        // 2) The handshake the respective vendor app performs.
        when (model) {
            Model.TAYLOR_BIA -> writeTaylorHandshake(user)
            Model.ALPHA_PRO3 -> writeAlphaHandshake(user)
        }

        // Prompt the user to step on; the result arrives asynchronously via onNotification().
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        fallbackJob?.cancel()
        fallbackJob = null
        // Last-chance weight-only publish if a live weight was seen but never finalized. Only the
        // Taylor needs it: the ALPHA publishes on its final-result frame.
        if (model == Model.TAYLOR_BIA && !published && pendingWeightGrams > 0) {
            publishFinal(currentAppUser(), pendingWeightGrams, impedanceOhms = 0)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_DATA) return

        val frame = parse(data)
        if (frame == null) {
            logD("dropping invalid frame ${data.toHexPreview(20)}")
            return
        }

        when (frame) {
            is ScaleFrame.LiveWeight -> onLiveWeight(frame, user)
            is ScaleFrame.FinalResult -> onFinalResult(frame, user)
            is ScaleFrame.StoredRecord -> onStoredRecord(frame, user)
            is ScaleFrame.Unknown ->
                logD("unhandled wire type 0x%02X: %s".format(frame.wireType, data.toHexPreview(20)))
        }
    }

    // --- Frame handling -------------------------------------------------------

    private fun onLiveWeight(frame: ScaleFrame.LiveWeight, user: ScaleUser) {
        if (model == Model.ALPHA_PRO3) {
            // The ALPHA always follows up with a final result, so live frames are only logged.
            if (frame.weightGrams != pendingWeightGrams) {
                pendingWeightGrams = frame.weightGrams
                logD("live weight ${frame.weightGrams} g locked=${frame.locked}")
            }
            return
        }

        // Idle frames (8C 00 00) decode to 0 and are filtered here.
        if (frame.weightGrams <= 0) return

        // Stability detection: count how many identical readings arrive back-to-back. The settled
        // value repeats exactly while the step-on ramp values are all distinct, so once we've seen
        // the same weight STABLE_FRAMES times in a row we treat it as final and publish immediately.
        stableCount = if (frame.weightGrams == pendingWeightGrams) stableCount + 1 else 1
        pendingWeightGrams = frame.weightGrams

        if (frame.locked || stableCount >= STABLE_FRAMES) {
            publishAndDisconnect(user, frame.weightGrams)  // explicit stable frame, or our heuristic
        } else {
            armFallback()                                  // safety net while the reading is moving
        }
    }

    private fun onFinalResult(frame: ScaleFrame.FinalResult, user: ScaleUser) {
        logI("final result ${frame.weightGrams} g, impedance=${frame.impedanceOhms} Ω, locked=${frame.locked}")
        acknowledge(WIRE_FINAL_RESULT)

        if (model == Model.TAYLOR_BIA) {
            publishAndDisconnect(user, frame.weightGrams, frame.impedanceOhms)
            return
        }

        if (published) {
            logD("final result already published for this session, ignoring")
            return
        }
        if (!publishFinal(user, frame.weightGrams, frame.impedanceOhms)) return
        // The ACK is queued behind the publish; give the adapter a moment to send it.
        scope.launch {
            delay(DISCONNECT_DELAY_MS)
            requestDisconnect()
        }
    }

    private fun onStoredRecord(frame: ScaleFrame.StoredRecord, user: ScaleUser) {
        logI("stored record ${frame.weightGrams} g at ${frame.timestampEpochSeconds}")
        acknowledge(WIRE_STORED_RECORD)
        if (frame.weightGrams <= 0) {
            logW("ignoring stored record without weight")
            return
        }
        // Not gated by `published`: every replayed record is its own measurement.
        publish(ScaleMeasurement().apply {
            userId = user.id
            dateTime = plausibleDate(frame.timestampEpochSeconds)
            this[MeasurementType.WEIGHT] = Kg(frame.weightGrams / 1000f)
        })
    }

    // --- Publishing -----------------------------------------------------------

    /**
     * Emit the final measurement exactly once per session; returns false when a caller lost the
     * race (stability run, stable frame, final frame or fallback timer can all fire).
     */
    private fun publishFinal(user: ScaleUser, weightGrams: Int, impedanceOhms: Int): Boolean {
        if (published || weightGrams <= 0) return false
        published = true
        fallbackJob?.cancel()
        fallbackJob = null

        val weightKg = weightGrams / 1000f
        publish(ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg)
            if (impedanceOhms > 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhms.toFloat())
                addBodyComposition(user, weightKg, impedanceOhms)
            }
        })
        return true
    }

    /**
     * Derives the body composition the scales withhold. Both report a single whole-body impedance
     * and leave the formulas to their vendor app, so [StandardImpedanceLib] takes over. A negative
     * fat percentage means the impedance is outside the formulas' calibration range; the reading
     * is then kept as weight plus raw impedance rather than padded with nonsense.
     */
    private fun ScaleMeasurement.addBodyComposition(user: ScaleUser, weightKg: Float, impedanceOhms: Int) {
        if (user.bodyHeight <= 0f) return

        val lib = StandardImpedanceLib(
            gender = user.gender,
            age = user.age,
            weightKg = weightKg.toDouble(),
            heightM = user.bodyHeight / 100.0,
            impedance = impedanceOhms.toDouble(),
        )
        val fatPercent = lib.totalFatPercentage.toFloat()
        if (fatPercent <= 0f) {
            logD("body composition skipped: fat=$fatPercent % at $impedanceOhms Ω")
            return
        }

        this[MeasurementType.BODY_FAT] = Percent(fatPercent.coerceIn(0f, 75f))
        this[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f))
        this[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat().coerceIn(0f, 99f))
        this[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat().coerceIn(0f, 10f))
        this[MeasurementType.LBM] = Kg(lib.fatFreeMassKg.toFloat().coerceIn(0f, 150f))
        this[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat().coerceIn(0f, 5000f))
    }

    /** Taylor: publish and drop the link — the scale sends nothing more after a locked reading. */
    private fun publishAndDisconnect(user: ScaleUser, weightGrams: Int, impedanceOhms: Int = 0) {
        if (publishFinal(user, weightGrams, impedanceOhms)) requestDisconnect()
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
            if (!published && pendingWeightGrams > 0) {
                logD("Weight never stabilized within ${FALLBACK_DELAY_MS} ms; publishing latest reading")
                publishAndDisconnect(currentAppUser(), pendingWeightGrams)
            }
        }
    }

    /** Stored-record timestamps are Unix seconds; fall back to "now" if the scale's clock is off. */
    private fun plausibleDate(epochSeconds: Long): Date {
        val millis = epochSeconds * 1000L
        return if (millis in EARLIEST_PLAUSIBLE_MS..(System.currentTimeMillis() + ONE_DAY_MS)) Date(millis) else Date()
    }

    // --- Handshakes (phone -> scale) ------------------------------------------

    /**
     * The minimal MGB-style init sequence the Taylor was verified with. We don't strictly need the
     * user/clock data to read weight, but the scale only began streaming weight frames after this
     * exact 8-byte "AC 02 .. CC" handshake in the openScale debug capture, so we replay it verbatim.
     */
    private fun writeTaylorHandshake(user: ScaleUser) {
        writeCfg(0xF7, 0, 0, 0)   // magic init #1
        writeCfg(0xFA, 0, 0, 0)   // magic init #2

        // User profile: sex (1=male, 2=female), age in years, height in cm.
        val sexByte = if (user.gender.isMale()) SEX_MALE else SEX_FEMALE
        val heightCm = user.bodyHeight.toInt().coerceAtLeast(0)
        writeCfg(0xFB, sexByte, user.age, heightCm)

        // Date (year since 2000, month 1-12, day) and time (HH, MM, SS) from the phone clock.
        val now = Calendar.getInstance()
        val yy = (now.get(Calendar.YEAR) - 2000).coerceIn(0, 99)
        writeCfg(0xFD, yy, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        writeCfg(0xFC, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))

        // Display unit: legacy WeightUnit.toInt() mapping (KG=1, LB=2, ST=3).
        writeCfg(0xFE, 6, user.scaleUnit.toInt(), 0)
    }

    /** What FitDays writes on connect: the profile, one control frame, then the profile again. */
    private fun writeAlphaHandshake(user: ScaleUser) {
        writeProfile(user)
        writeTo(SERVICE, CHAR_CFG, buildSessionStart())
        writeProfile(user)
    }

    private fun writeProfile(user: ScaleUser) {
        val frame = buildProfile(
            nowEpochSeconds = System.currentTimeMillis() / 1000L,
            heightCm = user.bodyHeight.toInt(),
            age = user.age,
            sex = if (user.gender.isMale()) SEX_MALE else SEX_FEMALE,
        )
        writeTo(SERVICE, CHAR_CFG, frame)
    }

    /** Only the ALPHA expects its results to be acknowledged; the Taylor has never seen a 0xDF. */
    private fun acknowledge(wireType: Int) {
        if (model != Model.ALPHA_PRO3) return
        writeTo(SERVICE, CHAR_CFG, buildAck(wireType))
    }

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

    companion object {
        /**
         * Number of consecutive identical weight readings that marks the measurement as final.
         * The scale's settled value repeats verbatim once the user is steady, whereas the step-on
         * ramp values are all distinct — so a short run of identical frames (~1 s at ~4 Hz) is a
         * reliable "stable" signal even though this scale never sends an explicit stable frame.
         */
        private const val STABLE_FRAMES = 4

        /**
         * Last-resort timeout (ms): if the weight never settles into a [STABLE_FRAMES] run (e.g. the
         * user keeps shifting), publish the latest reading anyway so a measurement is still recorded.
         */
        private const val FALLBACK_DELAY_MS = 8000L

        /** Grace period so the queued ACK leaves before we drop the ALPHA's link. */
        private const val DISCONNECT_DELAY_MS = 1_000L

        private const val ONE_DAY_MS = 86_400_000L
        private const val EARLIEST_PLAUSIBLE_MS = 1_577_836_800_000L // 2020-01-01

        private const val FRAME_SIZE = 20
        private const val HEADER_0 = 0xAC
        private const val HEADER_1 = 0x27

        // Wire types (byte 18).
        const val WIRE_LIVE_WEIGHT = 0xD5     // scale -> phone
        const val WIRE_FINAL_RESULT = 0xD6    // scale -> phone
        const val WIRE_STORED_RECORD = 0xD8   // scale -> phone
        const val WIRE_PROFILE = 0xD0         // phone -> scale
        const val WIRE_CONTROL = 0xDF         // phone -> scale

        // Control sub-commands (byte 2 of a 0xDF frame).
        const val CMD_SESSION_START = 0x01    // sent once by FitDays after the profile; purpose unknown
        const val CMD_ACK = 0x04              // byte 3 = wire type being acknowledged

        const val SEX_MALE = 1
        const val SEX_FEMALE = 2

        /** Bit set in the state byte once the scale considers the weight final. */
        private const val STATE_LOCKED = 0x80

        /** Weight lives in the low 18 bits of the 24-bit weight word. */
        private const val WEIGHT_G_MASK = 0x3FFFF

        /** The scale's checksum over raw[2..18] — the low five bits of the sum, see the class doc. */
        fun scaleChecksum(raw: ByteArray): Int = sum(raw) and 0x1F

        /** The phone's 8-bit checksum over raw[2..18]. */
        fun phoneChecksum(raw: ByteArray): Int = sum(raw) and 0xFF

        private fun sum(raw: ByteArray): Int {
            var s = 0
            for (i in 2 until 19) s += raw[i].toInt() and 0xFF
            return s
        }

        internal fun modelFor(advertisedName: String): Model? {
            val name = advertisedName.trim().uppercase(Locale.ROOT)
            return when {
                name == "MY_SCALE" -> Model.ALPHA_PRO3
                name.startsWith("5331891") || name.contains("BIA SCALE") -> Model.TAYLOR_BIA
                else -> null
            }
        }

        /** Decode one notification, or null when it is not a well-formed frame. */
        internal fun parse(raw: ByteArray): ScaleFrame? {
            if (raw.size != FRAME_SIZE) return null
            if ((raw[0].toInt() and 0xFF) != HEADER_0 || (raw[1].toInt() and 0xFF) != HEADER_1) return null
            if ((raw[19].toInt() and 0x1F) != scaleChecksum(raw)) return null

            val wireType = raw[18].toInt() and 0xFF
            return when (wireType) {
                WIRE_LIVE_WEIGHT -> ScaleFrame.LiveWeight(
                    weightGrams = weightGrams(raw, 3),
                    locked = isLocked(raw[2]),
                )

                WIRE_FINAL_RESULT -> ScaleFrame.FinalResult(
                    weightGrams = weightGrams(raw, 8),
                    impedanceOhms = u16(raw, 4),
                    locked = isLocked(raw[7]),
                )

                WIRE_STORED_RECORD -> ScaleFrame.StoredRecord(
                    timestampEpochSeconds = u32(raw, 3),
                    weightGrams = weightGrams(raw, 8),
                )

                else -> ScaleFrame.Unknown(wireType)
            }
        }

        private fun isLocked(state: Byte): Boolean = (state.toInt() and STATE_LOCKED) != 0

        private fun u16(raw: ByteArray, at: Int): Int =
            ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)

        private fun u32(raw: ByteArray, at: Int): Long =
            (u16(raw, at).toLong() shl 16) or u16(raw, at + 2).toLong()

        /** Low 18 bits of the big-endian 24-bit word at [at], in grams. */
        fun weightGrams(raw: ByteArray, at: Int): Int =
            (((raw[at].toInt() and 0xFF) shl 16) or u16(raw, at + 1)) and WEIGHT_G_MASK

        /**
         * AC 27 <unix32> 08 00 00 <height> 00 00 <age> <sex> 00 00 03 00 D0 <chk> — the time/profile
         * frame FitDays writes right after enabling notifications. [sex] is [SEX_MALE] or [SEX_FEMALE].
         */
        fun buildProfile(nowEpochSeconds: Long, heightCm: Int, age: Int, sex: Int): ByteArray {
            val body = ByteArray(16)
            putU32(body, 0, nowEpochSeconds)
            body[4] = 0x08
            body[7] = heightCm.coerceIn(0, 255).toByte()
            body[10] = age.coerceIn(0, 255).toByte()
            body[11] = sex.toByte()
            body[14] = 0x03
            return buildPhoneFrame(body, WIRE_PROFILE)
        }

        /** AC 27 04 <wireType> 00… DF <chk> — acknowledges a final result or stored record. */
        fun buildAck(wireType: Int): ByteArray =
            buildPhoneFrame(byteArrayOf(CMD_ACK.toByte(), wireType.toByte()) + ByteArray(14), WIRE_CONTROL)

        /** AC 27 01 00… DF <chk> — sent once per session by FitDays after the profile. */
        fun buildSessionStart(): ByteArray =
            buildPhoneFrame(byteArrayOf(CMD_SESSION_START.toByte()) + ByteArray(15), WIRE_CONTROL)

        private fun buildPhoneFrame(body: ByteArray, wireType: Int): ByteArray {
            require(body.size == 16) { "body must be 16 bytes, got ${body.size}" }
            val f = ByteArray(FRAME_SIZE)
            f[0] = HEADER_0.toByte()
            f[1] = HEADER_1.toByte()
            body.copyInto(f, 2)
            f[18] = wireType.toByte()
            f[19] = phoneChecksum(f).toByte()
            return f
        }

        private fun putU32(dst: ByteArray, at: Int, value: Long) {
            dst[at] = ((value ushr 24) and 0xFF).toByte()
            dst[at + 1] = ((value ushr 16) and 0xFF).toByte()
            dst[at + 2] = ((value ushr 8) and 0xFF).toByte()
            dst[at + 3] = (value and 0xFF).toByte()
        }
    }
}
