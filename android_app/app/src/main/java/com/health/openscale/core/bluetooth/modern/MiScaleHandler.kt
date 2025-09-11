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
package com.health.openscale.core.bluetooth.modern

import android.bluetooth.le.ScanResult
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.MiScaleLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Xiaomi Mi Scale (v1/v2) handler for the modern GATT stack.
 *
 * Goals:
 * - Clean separation between v1 and v2 (primary service per variant).
 * - Avoid duplicate writes/subscribes across services to reduce "not found" noise.
 * - Add a history fallback for firmwares that ignore the "only last" marker.
 * - Keep logic resilient (best-effort writes, clear logs).
 *
 * Empirical expectations:
 * - v1 (Mi Scale): history/time under 0x181D (Weight Service).
 * - v2 (Mi Body Composition Scale): history/time under 0x181B (Body Composition Service).
 * - Vendor config (0x1530/0x1542) for unit setting exists only on v2.
 * - 0x2A9D (Weight Measurement) is often absent on Mi → optional.
 */
class MiScaleHandler : ScaleDeviceHandler() {

    // ----- Variant detection -----
    private enum class Variant { V1, V2 }
    private var variant: Variant = Variant.V1

    // GATT UUIDs
    private val SERVICE_BODY_COMP = uuid16(0x181B)
    private val SERVICE_WEIGHT    = uuid16(0x181D)
    private val CHAR_CURRENT_TIME = uuid16(0x2A2B)
    private val CHAR_WEIGHT_MEAS  = uuid16(0x2A9D) // usually absent on Mi

    // Mi vendor service (v2 only)
    private val SERVICE_MI_CFG    = UUID.fromString("00001530-0000-3512-2118-0009af100700")
    private val CHAR_MI_CONFIG    = UUID.fromString("00001542-0000-3512-2118-0009af100700")

    // Mi history stream (notify + control)
    private val CHAR_MI_HISTORY   = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")

    // Protocol constants
    private val ENABLE_HISTORY_MAGIC = byteArrayOf(0x01, 0x96.toByte(), 0x8A.toByte(), 0xBD.toByte(), 0x62)

    // Session state
    private val histBuf = java.io.ByteArrayOutputStream()
    private var historyMode = false
    private var importedHistory = 0
    private var pendingHistoryCount = -1
    private var warnedHistoryStatusBits = false

    // Timers
    private val scope = CoroutineScope(Dispatchers.Main)
    private var historyFallbackJob: Job? = null

    // ----- Capability & detection -----

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.ROOT)
        val services = device.serviceUuids

        val isKnownName = when {
            name.startsWith("MIBCS") || name.startsWith("MIBFS") -> true
            name == "MI SCALE2" || name.startsWith("MI_SCALE") -> true
            else -> false
        }
        if (!isKnownName) return null

        val looksV2 = services.any { it == SERVICE_MI_CFG } ||
                name == "MIBCS" || name == "MIBFS" || name == "MI SCALE2"
        variant = if (looksV2) Variant.V2 else Variant.V1

        val display = if (variant == Variant.V2) "Xiaomi Mi Scale v2" else "Xiaomi Mi Scale v1"

        val capabilities = when (variant) {
            Variant.V1 -> setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.TIME_SYNC
            )
            Variant.V2 -> setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.UNIT_CONFIG,
                DeviceCapability.BODY_COMPOSITION
            )
        }

        return DeviceSupport(
            displayName = display,
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ----- Connect sequence -----

    override fun onConnected(user: ScaleUser) {
        logI("Connected (${variant.name}); init sequence")

        // Choose primary/alternate by variant to avoid cross-service chatter.
        val svcPrimary   = if (variant == Variant.V2) SERVICE_BODY_COMP else SERVICE_WEIGHT
        val svcAlternate = if (variant == Variant.V2) SERVICE_WEIGHT    else SERVICE_BODY_COMP

        // v2: set unit via vendor cfg (best-effort).
        if (variant == Variant.V2) runCatching { sendUnitV2(user) }

        // Current time: prefer primary, fallback to alternate.
        writeCurrentTimePreferPrimary(svcPrimary, svcAlternate)

        if (variant == Variant.V1) {
            // ---- v1: match legacy order exactly ----
            // 1) Magic first
            writeTo(svcPrimary, CHAR_MI_HISTORY, ENABLE_HISTORY_MAGIC, withResponse = true)
            // 2) Then subscribe history (and optional weight measurement)
            setNotifyOn(svcPrimary, CHAR_MI_HISTORY)
            runCatching { setNotifyOn(svcPrimary, CHAR_WEIGHT_MEAS) }
            // 3) Request only-last
            val uniq = unique16()
            val onlyLast = byteArrayOf(
                0x01, 0xFF.toByte(), 0xFF.toByte(),
                (uniq shr 8).toByte(), (uniq and 0xFF).toByte()
            )
            writeTo(svcPrimary, CHAR_MI_HISTORY, onlyLast, withResponse = true)
            // 4) Trigger transfer
            writeTo(svcPrimary, CHAR_MI_HISTORY, byteArrayOf(0x02), withResponse = true)

        } else {
            // ---- v2: keep robust modern order (same as legacy v2 effectively) ----
            setNotifyOn(svcPrimary, CHAR_MI_HISTORY)
            runCatching { setNotifyOn(svcPrimary, CHAR_WEIGHT_MEAS) } // optional
            writeTo(svcPrimary, CHAR_MI_HISTORY, ENABLE_HISTORY_MAGIC, withResponse = true)

            val uniq = unique16()
            val onlyLast = byteArrayOf(
                0x01, 0xFF.toByte(), 0xFF.toByte(),
                (uniq shr 8).toByte(), (uniq and 0xFF).toByte()
            )
            writeTo(svcPrimary, CHAR_MI_HISTORY, onlyLast, withResponse = true)
            writeTo(svcPrimary, CHAR_MI_HISTORY, byteArrayOf(0x02), withResponse = true)
        }

        historyMode = true
        pendingHistoryCount = -1
        importedHistory = 0
        warnedHistoryStatusBits = false
        userInfo(R.string.bt_info_waiting_for_measurement)

        // Arm fallback in case firmware ignores the "only last" count response.
        armHistoryFallbackTimer(svcPrimary, svcAlternate)
    }

    override fun onDisconnected() {
        historyFallbackJob?.cancel(); historyFallbackJob = null
        histBufReset()
        historyMode = false
        importedHistory = 0
        pendingHistoryCount = -1
        warnedHistoryStatusBits = false
    }

    // ----- Writes -----

    /** v2-only: set unit via vendor config (ignore failures on clones). */
    private fun sendUnitV2(user: ScaleUser) {
        // [0x06, 0x04, 0x00, unit]
        val unit = user.scaleUnit.toInt().coerceIn(0, 2).toByte()
        val cmd = byteArrayOf(0x06, 0x04, 0x00, unit)
        writeTo(SERVICE_MI_CFG, CHAR_MI_CONFIG, cmd, withResponse = true)
        logD("Unit set (v2): ${cmd.toHexPreview(16)}")
    }

    /** Prefer writing Current Time to the primary service; fallback to alternate if needed. */
    private fun writeCurrentTimePreferPrimary(primarySvc: UUID, alternateSvc: UUID) {
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR)
        val payload = byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte(),
            0x03, 0x00, 0x00
        )

        val primaryOk = runCatching {
            writeTo(primarySvc, CHAR_CURRENT_TIME, payload, withResponse = true)
        }.isSuccess

        if (!primaryOk) {
            runCatching {
                writeTo(alternateSvc, CHAR_CURRENT_TIME, payload, withResponse = true)
            }.onSuccess {
                logD("Current time written (alternate).")
            }.onFailure {
                logI("Current time write failed on both services: ${it.message}")
            }
        } else {
            logD("Current time written (primary).")
        }
    }

    // ----- Notifications / parsing -----

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return
        if (characteristic == CHAR_CURRENT_TIME) return // ignore echoes

        if (characteristic == CHAR_MI_HISTORY) {
            handleHistoryNotify(data, user)
            return
        }

        // Some firmwares may leak data elsewhere; log a small preview.
        logD("Notify $characteristic len=${data.size} ${data.toHexPreview(24)}")
    }

    private fun handleHistoryNotify(d: ByteArray, user: ScaleUser) {
        // STOP (0x03)
        if (d.size == 1 && d[0] == 0x03.toByte()) {
            flushHistory()

            // ACK stop + uniq; try both services to be service-agnostic.
            writeToServiceOf(d, byteArrayOf(0x03))
            val uniq = unique16()
            val ack  = byteArrayOf(0x04, 0xFF.toByte(), 0xFF.toByte(), (uniq shr 8).toByte(), (uniq and 0xFF).toByte())
            writeToServiceOf(d, ack)

            logI("History import done: $importedHistory record(s). Announced=$pendingHistoryCount")
            historyMode = false
            return
        }

        // Response to “only last”: 0x01 <count> FF FF <uniqHi> <uniqLo>
        if (d.size >= 6 && d[0] == 0x01.toByte()) {
            val count = d[1].toInt() and 0xFF
            // Accept FF FF and 00 00
            val marker = (d[2].toInt() and 0xFF) shl 8 or (d[3].toInt() and 0xFF)
            if (marker == 0xFFFF || marker == 0x0000) {
                pendingHistoryCount = count
                logI("History count announced (marker=${marker.toString(16)}): $pendingHistoryCount")
                return
            }
        }

        // Live frames (13B) or combined (26B)
        if (d.size == 13 || d.size == 26) {
            if (d.size == 13) {
                if (parseLive13(d, user) && historyMode) importedHistory++
            } else {
                val a = d.copyOfRange(0, 13)
                val b = d.copyOfRange(13, 26)
                val okA = parseLive13(a, user)
                val okB = parseLive13(b, user)
                if (historyMode) importedHistory += (if (okA) 1 else 0) + (if (okB) 1 else 0)
            }
            return
        }

        // Otherwise treat as history chunk(s) → 10-byte aligned records.
        appendHistoryChunk(d)
    }

    /** v2 live frame (13 bytes). With/without impedance. Publishes stabilized frames only. */
    private fun parseLive13(d: ByteArray, user: ScaleUser): Boolean {
        if (d.size != 13) return false

        val c0 = d[0].toInt() and 0xFF
        val c1 = d[1].toInt() and 0xFF
        val isLbs   = (c0 and 0x01) != 0
        val isCatty = (c1 and 0x40) != 0
        val stable  = (c1 and 0x20) != 0
        val removed = (c1 and 0x80) != 0
        val hasImp  = (c1 and 0x02) != 0

        if (!stable || removed) return false

        val year   = ((d[3].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
        val month  = d[4].toInt() and 0xFF
        val day    = d[5].toInt() and 0xFF
        val hour   = d[6].toInt() and 0xFF
        val minute = d[7].toInt() and 0xFF

        val weightRaw = ((d[12].toInt() and 0xFF) shl 8) or (d[11].toInt() and 0xFF)
        val native = if (isLbs || isCatty) weightRaw / 100.0f else weightRaw / 200.0f

        val dt = parseMinuteDate(year, month, day, hour, minute) ?: return false
        val ts = (dt.time / 1000).toInt()
        val lastTs = getLastImportedTimestamp(user.id)
        if (ts <= lastTs) return false

        val m = ScaleMeasurement().apply {
            dateTime = dt
            weight = ConverterUtils.toKilogram(native, user.scaleUnit)
            userId = user.id
        }

        if (hasImp) {
            val imp = ((d[10].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)
            if (imp > 0) {
                val sex = if (user.gender == GenderType.MALE) 1 else 0
                val lib = MiScaleLib(sex, user.age, user.bodyHeight)
                m.water       = lib.getWater(m.weight, imp.toFloat())
                m.visceralFat = lib.getVisceralFat(m.weight)
                m.fat         = lib.getBodyFat(m.weight, imp.toFloat())
                m.muscle      = lib.getMuscle(m.weight, imp.toFloat())
                m.lbm         = lib.getLBM(m.weight, imp.toFloat())
                m.bone        = lib.getBoneMass(m.weight, imp.toFloat())
            }
        }

        publish(m)
        updateLastImportedTimestamp(user.id, ts)

        return true
    }

    /** History record (10 bytes): [status][weightLE(2)][yearLE(2)][mon][day][h][m][s] */
    private fun parseHistory10(d: ByteArray, user: ScaleUser): Boolean {
        if (d.size != 10) return false
        val status = d[0].toInt() and 0xFF
        val isLbs   = (status and 0x01) != 0
        val isCatty = (status and 0x10) != 0
        val stable  = (status and 0x20) != 0
        val removed = (status and 0x80) != 0
        if (!stable || removed) return false

        // Warn once if unknown bits show up (not harmful).
        if (!warnedHistoryStatusBits && ((status and 0x02) != 0 || (status and 0x04) != 0)) {
            logW("History status had unexpected bits (1/2) — ignoring (no impedance in 10B format).")
            warnedHistoryStatusBits = true
        }

        val weightRaw = ((d[2].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val native = if (isLbs || isCatty) weightRaw / 100.0f else weightRaw / 200.0f

        val year   = ((d[4].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        val month  = d[5].toInt() and 0xFF
        val day    = d[6].toInt() and 0xFF
        val hour   = d[7].toInt() and 0xFF
        val minute = d[8].toInt() and 0xFF

        val dt = parseMinuteDate(year, month, day, hour, minute) ?: return false
        if (!plausible(dt)) return false

        val ts = (dt.time / 1000).toInt()
        val lastTs = getLastImportedTimestamp(user.id)
        if (ts <= lastTs) return false

        val m = ScaleMeasurement().apply {
            dateTime = dt
            weight = ConverterUtils.toKilogram(native, user.scaleUnit)
            userId = user.id
        }
        publish(m)
        updateLastImportedTimestamp(user.id, ts)
        return true
    }

    private fun appendHistoryChunk(chunk: ByteArray) {
        if (chunk.size < 2) return
        histBuf.write(chunk, 0, chunk.size)

        val buf = histBuf.toByteArray()
        val full = (buf.size / 10) * 10
        if (full >= 10) {
            var ok = 0
            var off = 0
            val user = currentAppUser()
            while (off < full) {
                if (parseHistory10(buf.copyOfRange(off, off + 10), user)) ok++
                off += 10
            }
            if (ok > 0) importedHistory += ok
            histBufReset()
            if (buf.size > full) histBuf.write(buf, full, buf.size - full) // keep remainder
        }
    }

    private fun flushHistory() {
        val leftover = histBuf.toByteArray()
        if (leftover.isNotEmpty() && leftover.size % 10 == 0) {
            var ok = 0
            var off = 0
            val user = currentAppUser()
            while (off < leftover.size) {
                if (parseHistory10(leftover.copyOfRange(off, off + 10), user)) ok++
                off += 10
            }
            if (ok > 0) importedHistory += ok
        }
        histBufReset()
    }

    // ----- Helpers -----

    private fun histBufReset() {
        try { histBuf.reset() } catch (_: Exception) {}
    }

    private fun unique16(): Int = currentAppUser().id

    private fun parseMinuteDate(y: Int, m: Int, d: Int, h: Int, min: Int): Date? =
        runCatching {
            SimpleDateFormat("yyyy/MM/dd/HH/mm", Locale.US).parse("$y/$m/$d/$h/$min")
        }.getOrNull()

    private fun plausible(date: Date, years: Int = 20): Boolean {
        val now = Calendar.getInstance()
        val max = (now.clone() as Calendar).apply { add(Calendar.YEAR, years) }.time
        val min = (now.clone() as Calendar).apply { add(Calendar.YEAR, -years) }.time
        return date.after(min) && date.before(max)
    }

    /**
     * Write a small payload back to the history characteristic, regardless of which service
     * the firmware uses. Tries 0x181B first (v2 default), then 0x181D (v1 default).
     * Avoids dependency on a GATT "hasChar" API.
     */
    private fun writeToServiceOf(_rx: ByteArray, payload: ByteArray) {
        runCatching { writeTo(SERVICE_BODY_COMP, CHAR_MI_HISTORY, payload, withResponse = true) }
            .onFailure {
                runCatching { writeTo(SERVICE_WEIGHT, CHAR_MI_HISTORY, payload, withResponse = true) }
            }
    }

    /** Arm one-shot fallback if 0x01<count> response did not arrive. */
    private fun armHistoryFallbackTimer(svcPrimary: UUID, svcAlternate: UUID) {
        historyFallbackJob?.cancel()
        historyFallbackJob = scope.launch {
            delay(1000)
            if (!historyMode || pendingHistoryCount >= 0) return@launch

            logW("No history count response on primary; attempting fallback (ALL records).")

            val uniq2 = unique16()
            val all = byteArrayOf(0x01, 0x00, 0x00, (uniq2 shr 8).toByte(), (uniq2 and 0xFF).toByte())

            runCatching {
                writeTo(svcPrimary, CHAR_MI_HISTORY, ENABLE_HISTORY_MAGIC, withResponse = true)
                writeTo(svcPrimary, CHAR_MI_HISTORY, all, withResponse = true)
                writeTo(svcPrimary, CHAR_MI_HISTORY, byteArrayOf(0x02), withResponse = true)
            }.onFailure { logI("Primary fallback write failed: ${it.message}") }

            runCatching {
                setNotifyOn(svcAlternate, CHAR_MI_HISTORY)
                writeTo(svcAlternate, CHAR_MI_HISTORY, ENABLE_HISTORY_MAGIC, withResponse = true)
                writeTo(svcAlternate, CHAR_MI_HISTORY, all, withResponse = true)
                writeTo(svcAlternate, CHAR_MI_HISTORY, byteArrayOf(0x02), withResponse = true)
            }.onFailure { logI("Alternate fallback write failed: ${it.message}") }
        }
    }


    private fun getLastImportedTimestamp(userId: Int): Int {
        return settingsGetInt("last_imported_ts_$userId", 0)
    }

    private fun updateLastImportedTimestamp(userId: Int, timestamp: Int) {
        settingsPutInt("last_imported_ts_$userId", timestamp)
    }
}
