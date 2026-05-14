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
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

/**
 * Handler for the **Cult Smart Scale Pro** (Espressif-based BLE scale, 8-electrode BIA).
 *
 * ## Protocol summary (reverse-engineered from btsnoop_hci.log)
 *
 * ### GATT layout
 * | Handle | UUID   | Role                                    |
 * |--------|--------|-----------------------------------------|
 * | 0x001D | 0xFFF0 | Custom Scale Service                    |
 * | 0x001F | 0xFFF1 | Write characteristic (commands → scale) |
 * | 0x0021 | 0xFFF4 | Notify characteristic (scale → phone)   |
 * | 0x0022 | 0x2902 | CCCD for FFF4 (enable notifications)    |
 *
 * No explicit start command needed — enabling notifications triggers streaming.
 *
 * ### Notification packet types on 0xFFF4
 *
 * **Type 0xCF – Live weight stream (11 bytes)**
 * ```
 * [0]     = 0xCF  packet type
 * [1]     = flags (0x80 = handle measurement)
 * [2]     = reserved
 * [3..4]  = weight uint16-LE, unit = 1/100 kg
 * [5..8]  = reserved
 * [9]     = status: 0x01=live, 0x00=stable(no handles), 0x08=impedance measuring, 0x05=stable(handles)
 * [10]    = checksum XOR bytes[0..9]
 * ```
 *
 * **Type 0xBE – Raw multi-frequency impedance (13 bytes, 2 packets)**
 * ```
 * [0]     = 0xBE  packet type
 * [1]     = packet index: 0x00 = set A, 0x01 = set B
 * [2..3]  = impedance[0] uint16-LE (Ω)
 * [4..5]  = impedance[1] uint16-LE (Ω)
 * [6..7]  = impedance[2] uint16-LE (Ω)  ← whole-body impedance (~250Ω range)
 * [8..9]  = impedance[3] uint16-LE (Ω)
 * [10..11]= impedance[4] uint16-LE (Ω)
 * [12]    = checksum XOR bytes[0..11]
 * ```
 *
 * **Type 0xDF – Body composition result (17 bytes, 4 packets)**
 * The 0xDF packets contain proprietary/encrypted body composition data.
 * The encoding is not publicly documented and could not be reverse-engineered
 * from the captured traffic. Body composition is instead computed using
 * StandardImpedanceLib from the raw impedance values.
 *
 * ### Impedance value selection
 * The scale sends 10 impedance values across 2 x 0xBE packets.
 * impedance[2] from packet 0x00 (bytes[6..7]) is the whole-body impedance
 * (~248Ω range) which is in the correct range for StandardImpedanceLib formulas.
 * The other values (2000-3000Ω range) are segmental/high-frequency readings.
 */
class CultSmartScaleProHandler : ScaleDeviceHandler() {

    // ── GATT UUIDs ────────────────────────────────────────────────────────────
    private val SERVICE_UUID    get() = uuid16(0xFFF0)
    private val CHR_NOTIFY_UUID get() = uuid16(0xFFF4)

    // ── Packet type identifiers ───────────────────────────────────────────────
    private val TYPE_LIVE_WEIGHT: Byte = 0xCF.toByte()
    private val TYPE_IMPEDANCE  : Byte = 0xBE.toByte()
    private val TYPE_BODY_COMP  : Byte = 0xDF.toByte()

    // ── Accumulated state across packets ─────────────────────────────────────
    private var pendingWeight      = 0f
    private var pendingImpedance   = 0.0  // whole-body impedance from BE packet 0, imp[2]
    private var impedanceReceived  = false
    private var bodyCompCount      = 0
    private var stableWeightCount  = 0

    // ── supportFor ────────────────────────────────────────────────────────────

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
        if (!name.startsWith("CULT")) return null

        return DeviceSupport(
            displayName = "Cult Smart Scale Pro",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.BATTERY_LEVEL
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    override fun onConnected(user: ScaleUser) {
        logI("onConnected → enabling notifications on FFF4")
        resetPendingState()
        setNotifyOn(SERVICE_UUID, CHR_NOTIFY_UUID)
        userInfo(R.string.bt_info_waiting_for_measurement)
    }

    override fun onDisconnected() {
        logD("onDisconnected → clearing pending state")
        // If we got a handle-mode stable weight but body comp never arrived
        // (e.g. user stepped off before measurement completed), publish weight-only.
        if (pendingWeight > 0f && bodyCompCount == 0 && stableWeightCount > 0) {
            logI("disconnected mid-handle-measurement — publishing weight-only fallback")
            publishMeasurement(withBodyComp = false, user = null)
        }
        resetPendingState()
    }

    // ── Notification dispatcher ───────────────────────────────────────────────

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_NOTIFY_UUID) return
        if (data.isEmpty()) return

        logD("notify ${data.toHexPreview(20)}")

        when (data[0]) {
            TYPE_LIVE_WEIGHT -> handleLiveWeight(data)
            TYPE_IMPEDANCE   -> handleImpedance(data, user)
            TYPE_BODY_COMP   -> handleBodyComp(data, user)
            else             -> logD("unknown packet type 0x${String.format("%02X", data[0])}")
        }
    }

    // ── Packet parsers ────────────────────────────────────────────────────────

    /**
     * 0xCF – live weight stream (11 bytes).
     *
     * Status byte[9]:
     *   0x01 = live/unstable streaming
     *   0x00 = stable, weight-only (no handles) → publish immediately
     *   0x08 = impedance measurement in progress (handles held) → save weight, wait
     *   0x05 = stable, full body measurement complete (handles) → save weight, wait for 0xBE+0xDF
     */
    private fun handleLiveWeight(data: ByteArray) {
        if (data.size < 11) { logW("0xCF too short"); return }

        val rawWeight  = ((data[4].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val weightKg   = rawWeight / 100.0f
        val statusByte = data[9].toInt() and 0xFF

        logD("live weight=${weightKg}kg status=0x${String.format("%02X", statusByte)}")

        when {
            statusByte == 0x01 || weightKg <= 0f -> {
                // Live/unstable — ignore
            }
            statusByte == 0x00 -> {
                // Weight-only stable (no handles) — publish immediately, no body comp coming
                pendingWeight = weightKg
                stableWeightCount++
                logI("stable weight-only #$stableWeightCount = ${weightKg}kg → publishing immediately")
                publishMeasurement(withBodyComp = false, user = null)
                // Don't disconnect — let the scale disconnect naturally after weight drops to 0
            }
            statusByte == 0x08 || statusByte == 0x05 -> {
                // Handle measurement in progress or complete — save weight, wait for 0xBE + 0xDF
                stableWeightCount++
                pendingWeight = weightKg
                logI("stable handle weight #$stableWeightCount = ${weightKg}kg (status=0x${String.format("%02X", statusByte)}) — waiting for body comp")
            }
            else -> {
                // Unknown stable status — save weight just in case
                pendingWeight = weightKg
                logW("unknown stable status 0x${String.format("%02X", statusByte)}, weight=${weightKg}kg")
            }
        }
    }

    /**
     * 0xBE – raw multi-frequency impedance (13 bytes, 2 packets).
     *
     * Packet 0x00 layout (bytes[2..11] = 5 × uint16-LE):
     *   imp[0] = ~2867Ω  (segmental, high freq)
     *   imp[1] = ~3013Ω  (segmental, high freq)
     *   imp[2] = ~248Ω   (whole-body, suitable for StandardImpedanceLib)
     *   imp[3] = ~2904Ω  (segmental)
     *   imp[4] = ~2668Ω  (segmental)
     *
     * We use imp[2] from packet 0x00 as the whole-body impedance.
     */
    private fun handleImpedance(data: ByteArray, user: ScaleUser) {
        if (data.size < 13) { logW("0xBE too short"); return }

        val packetIdx = data[1].toInt() and 0xFF
        val imp = Array(5) {
            val lo = data[2 + it * 2].toInt() and 0xFF
            val hi = data[3 + it * 2].toInt() and 0xFF
            (hi shl 8) or lo
        }

        logD("impedance packet=$packetIdx values=${imp.joinToString()}")

        if (packetIdx == 0x00) {
            // imp[2] is the whole-body impedance (~250Ω range)
            pendingImpedance = imp[2].toDouble()
            impedanceReceived = true
            logI("whole-body impedance = ${pendingImpedance}Ω")
        }
    }

    /**
     * 0xDF – body composition result (17 bytes, 4 packets).
     *
     * The 0xDF payload encoding is proprietary and could not be decoded.
     * We count the 4 packets to know when the measurement is complete,
     * then compute body composition using StandardImpedanceLib.
     */
    private fun handleBodyComp(data: ByteArray, user: ScaleUser) {
        if (data.size < 17) { logW("0xDF too short"); return }

        val seqA = data[2].toInt() and 0xFF
        val seqB = data[3].toInt() and 0xFF
        logD("0xDF seqA=$seqA seqB=$seqB received")

        bodyCompCount++

        // Publish after all 4 body comp packets and impedance are received
        if (bodyCompCount >= 4 && impedanceReceived && pendingWeight > 0f) {
            publishMeasurement(withBodyComp = true, user = user)
            requestDisconnect()
        }
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    private fun publishMeasurement(withBodyComp: Boolean, user: ScaleUser?) {
        val m = ScaleMeasurement().apply {
            dateTime = Date()
            weight   = pendingWeight
        }

        if (withBodyComp && user != null && pendingImpedance > 0.0) {
            val lib = StandardImpedanceLib(
                gender     = user.gender,
                age        = user.age,
                weightKg   = pendingWeight.toDouble(),
                heightM    = user.bodyHeight / 100.0,
                impedance  = pendingImpedance
            )

            m.fat        = lib.totalFatPercentage.toFloat().coerceIn(0f, 75f)
            m.water      = lib.totalBodyWaterPercentage.toFloat().coerceIn(0f, 80f)
            m.muscle     = lib.skeletalMuscleMassKg.toFloat().coerceIn(0f, 100f)
            m.bone       = lib.boneMassKg.toFloat().coerceIn(0f, 10f)
            m.lbm        = lib.fatFreeMassKg.toFloat().coerceIn(0f, 150f)
            m.bmr        = lib.basalMetabolicRate.toFloat().coerceIn(0f, 5000f)
            m.impedance  = pendingImpedance

            logI(
                "body comp (StandardImpedanceLib, impedance=${pendingImpedance}Ω): " +
                "fat=${m.fat}% water=${m.water}% muscle=${m.muscle}kg " +
                "bone=${m.bone}kg lbm=${m.lbm}kg bmr=${m.bmr}kcal"
            )
        }

        logI("publishing → weight=${m.weight}kg fat=${m.fat}% water=${m.water}% muscle=${m.muscle}kg bmr=${m.bmr}kcal impedance=${m.impedance}Ω")
        publish(m)
    }

    private fun resetPendingState() {
        pendingWeight     = 0f
        pendingImpedance  = 0.0
        impedanceReceived = false
        bodyCompCount     = 0
        stableWeightCount = 0
    }
}
