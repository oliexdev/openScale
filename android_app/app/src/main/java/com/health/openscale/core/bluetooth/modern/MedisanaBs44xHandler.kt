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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MedisanaBs44xHandler : ScaleDeviceHandler() {

    // GATT
    private val SERVICE: UUID     = uuid16(0x78B2)
    private val CHR_WEIGHT: UUID  = uuid16(0x8A21) // Indicate
    private val CHR_FEATURE: UUID = uuid16(0x8A22) // Indicate
    private val CHR_CMD: UUID     = uuid16(0x8A81) // Write
    private val CHR_CUSTOM5: UUID = uuid16(0x8A82) // Indicate (optional)

    // Epoch detection
    private enum class EpochMode { UNIX, FROM_2010 }
    private val SCALE_EPOCH_OFFSET = 1262304000L // secs since 1970 to 2010-01-01
    private val KEY_EPOCH_MODE = "epochMode"     // persisted per device via DriverSettings

    private var epochMode: EpochMode? = null
    private var predictedFromName: EpochMode? = null

    // Aggregation (weight first, then feature → publish)
    private var current: ScaleMeasurement? = null

    // ---------- Support detection ----------
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        val serviceHint = device.serviceUuids.any { it == SERVICE }

        // Legacy mapping heuristics:
        //  013197 / 013198 / 0202B6  => BS444/BS440   (2010-epoch)
        //  0203B*                     => BS430        (unix-epoch)
        val looksMedisana = name.startsWith("013197") ||
                name.startsWith("013198") || name.startsWith("0202b6") ||
                name.startsWith("0203b")  || serviceHint

        if (!looksMedisana) return null

        predictedFromName = when {
            name.startsWith("0203b") -> EpochMode.UNIX
            name.startsWith("013197") || name.startsWith("013198") || name.startsWith("0202b6") -> EpochMode.FROM_2010
            else -> null // unknown → will auto-detect at runtime
        }

        val variant = when {
            name.startsWith("0203b") -> "Medisana BS430"
            name.startsWith("013197") || name.startsWith("013198") || name.startsWith("0202b6") -> "Medisana BS444/BS440"
            else -> "Medisana BS44x"
        }

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC
        )

        return DeviceSupport(
            displayName = variant,
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ---------- Session ----------
    override fun onConnected(user: com.health.openscale.core.bluetooth.data.ScaleUser) {
        // Resolve epochMode: persisted → name-heuristic → default UNIX
        epochMode = loadEpochMode() ?: predictedFromName ?: EpochMode.UNIX

        // Enable indications
        setNotifyOn(SERVICE, CHR_FEATURE)
        setNotifyOn(SERVICE, CHR_WEIGHT)
        setNotifyOn(SERVICE, CHR_CUSTOM5) // harmless if absent

        // Send "time" command: 0x02 + <timestamp LE>
        val nowSec = System.currentTimeMillis() / 1000
        val tsForScale = when (epochMode) {
            EpochMode.FROM_2010 -> nowSec - SCALE_EPOCH_OFFSET
            else -> nowSec
        }
        val ts = int32Le(tsForScale)
        val cmd = byteArrayOf(0x02, ts[0], ts[1], ts[2], ts[3])
        writeTo(SERVICE, CHR_CMD, cmd, withResponse = true)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: com.health.openscale.core.bluetooth.data.ScaleUser
    ) {
        when (characteristic) {
            CHR_WEIGHT  -> parseWeight(data)
            CHR_FEATURE -> {
                parseFeature(data)
                current?.let { publish(it) }
                current = null
            }
            CHR_CUSTOM5 -> { /* optional/ignored */ }
        }
    }

    // ---------- Parsing ----------
    // Weight frame:
    //  [1..2] u16 LE → kg/100
    //  [5..8] u32 LE → timestamp (unix or 2010-epoch)
    private fun parseWeight(d: ByteArray) {
        if (d.size < 9) return

        val weightRaw = u16Le(d, 1)
        val weightKg = weightRaw / 100.0f

        val tsRaw = u32Le(d, 5).toLong()
        val tsSec = mapTimestampFromScale(tsRaw)

        val m = current ?: ScaleMeasurement().also { current = it }
        m.dateTime = Date(tsSec * 1000)
        m.weight = weightKg
    }

    // Feature frame:
    // fat@8..9  water@10..11  muscle@12..13  bone@14..15
    // value = (u16 & 0x0FFF) / 10
    private fun parseFeature(d: ByteArray) {
        if (d.size < 16) return
        val m = current ?: ScaleMeasurement().also { current = it }

        m.fat    = decode12bitTenth(d, 8)
        m.water  = decode12bitTenth(d, 10)
        m.muscle = decode12bitTenth(d, 12)
        m.bone   = decode12bitTenth(d, 14)
    }

    private fun decode12bitTenth(d: ByteArray, off: Int): Float {
        val v = u16Le(d, off) and 0x0FFF
        return v / 10.0f
    }

    // ---------- Epoch auto-correction ----------
    /**
     * Convert raw timestamp from scale to unix seconds, auto-correcting epoch mode if needed.
     * Heuristic: choose the epoch that puts the timestamp within ±90 days of "now".
     * Persist the detected mode for the next session.
     */
    private fun mapTimestampFromScale(tsRaw: Long): Long {
        val now = System.currentTimeMillis() / 1000
        val near = 90L * 24 * 3600

        fun nearNow(x: Long) = abs(x - now) <= near

        val unixCandidate = tsRaw
        val epoch2010Candidate = tsRaw + SCALE_EPOCH_OFFSET

        val mode = epochMode
        return when {
            // If unknown, decide by proximity to now
            mode == null -> {
                val chosen = when {
                    nearNow(unixCandidate) -> {
                        saveEpochMode(EpochMode.UNIX); unixCandidate
                    }
                    nearNow(epoch2010Candidate) -> {
                        saveEpochMode(EpochMode.FROM_2010); epoch2010Candidate
                    }
                    else -> unixCandidate // fallback (don’t flip)
                }
                epochMode = loadEpochMode() ?: epochMode
                chosen
            }

            // If set to UNIX but looks like 2010 → flip and persist
            mode == EpochMode.UNIX && !nearNow(unixCandidate) && nearNow(epoch2010Candidate) -> {
                saveEpochMode(EpochMode.FROM_2010)
                epochMode = EpochMode.FROM_2010
                epoch2010Candidate
            }

            // If set to 2010 but looks like UNIX → flip and persist
            mode == EpochMode.FROM_2010 && nearNow(unixCandidate) && !nearNow(epoch2010Candidate) -> {
                saveEpochMode(EpochMode.UNIX)
                epochMode = EpochMode.UNIX
                unixCandidate
            }

            // Normal path
            mode == EpochMode.FROM_2010 -> epoch2010Candidate
            else -> unixCandidate
        }
    }

    private fun loadEpochMode(): EpochMode? =
        when (settingsGetString(KEY_EPOCH_MODE, null)) {
            "unix" -> EpochMode.UNIX
            "2010" -> EpochMode.FROM_2010
            else   -> null
        }

    private fun saveEpochMode(mode: EpochMode) {
        settingsPutString(KEY_EPOCH_MODE, if (mode == EpochMode.UNIX) "unix" else "2010")
        logI("Detected epoch mode: $mode (persisted)")
    }

    // ---------- LE helpers ----------
    private fun u16Le(d: ByteArray, off: Int): Int {
        if (off + 1 >= d.size) return 0
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32Le(d: ByteArray, off: Int): Int {
        if (off + 3 >= d.size) return 0
        return (d[off].toInt() and 0xFF) or
                ((d[off + 1].toInt() and 0xFF) shl 8) or
                ((d[off + 2].toInt() and 0xFF) shl 16) or
                ((d[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun int32Le(v: Long): ByteArray {
        val x = v.toInt()
        return byteArrayOf(
            (x and 0xFF).toByte(),
            ((x ushr 8) and 0xFF).toByte(),
            ((x ushr 16) and 0xFF).toByte(),
            ((x ushr 24) and 0xFF).toByte()
        )
    }
}
