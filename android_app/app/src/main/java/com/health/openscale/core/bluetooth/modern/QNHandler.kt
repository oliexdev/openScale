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

import android.R.attr.name
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.UUID
import kotlin.math.min

/**
 * QN / FITINDEX ES-26M style scales (vendor protocol on 0xFFE0/0xFFF0).
 *
 * Notes:
 * - There are two very similar layouts (“type 1” using FFE0..FFE5, and an alternative using FFF0..FFF2).
 * - We subscribe to both layouts. Writes to a missing characteristic are harmless (adapter logs a warning).
 * - Weight and two resistance values are broadcast in notifications of 0xFFE1/0xFFF1 (opcode 0x10).
 * - We derive body composition with [TrisaBodyAnalyzeLib] from weight + impedance.
 */
class QNHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "QNHandler"

        // Vendor “epoch”: seconds since 2000-01-01 00:00:00 UTC
        private const val SCALE_UNIX_TIMESTAMP_OFFSET = 946_702_800L
    }

    // ---- Services / Characteristics (16-bit UUIDs) ---------------------------

    // Type 1 (FFE0..FFE5)
    private val SVC_T1                     = uuid16(0xFFE0)
    private val CHR_T1_NOTIFY_WEIGHT_TIME  = uuid16(0xFFE1) // notify (weight/time/resistances)
    private val CHR_T1_INDICATE_MISC       = uuid16(0xFFE2) // indicate (misc ack)
    private val CHR_T1_WRITE_CONFIG        = uuid16(0xFFE3) // write (unit config)
    private val CHR_T1_WRITE_TIME          = uuid16(0xFFE4) // write (time sync)

    // Type 2 (FFF0..FFF2)
    private val SVC_T2                     = uuid16(0xFFF0)
    private val CHR_T2_NOTIFY_WEIGHT_TIME  = uuid16(0xFFF1) // notify (weight/time/resistances)
    private val CHR_T2_WRITE_SHARED        = uuid16(0xFFF2) // write (used for unit+time on T2)

    // ---- State ----------------------------------------------------------------

    /** Heuristic: prefer Type 2 if we saw 0xFFF0 in advertisements; else fall back to Type 1. */
    private var likelyUseType1: Boolean = true

    /** Prevents double-publish for the same stable frame. */
    private var hasPublishedForThisSession = false

    /** Scale’s internal “weight scale factor” (100 = /100, 10 = /10). Defaults to type-1 behavior. */
    private var weightScaleFactor: Float = 100.0f

    // ---- Capability discovery --------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val uuids = device.serviceUuids.toSet()

        val hasQN = uuids.contains(uuid16(0xFFE0)) || uuids.contains(uuid16(0xFFF0))

        if (!hasQN) return null
        if (!device.name.startsWith("QN-Scale")) return null

        likelyUseType1 = uuids.contains(uuid16(0xFFE0)) && !uuids.contains(uuid16(0xFFF0))

        val caps = setOf(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        )
        return DeviceSupport(
            displayName = "QN Scale",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ---- Connection sequencing -------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        hasPublishedForThisSession = false
        weightScaleFactor = 100.0f

        // Subscribe to both flavors; the adapter will ignore missing ones gracefully.
        setNotifyOn(SVC_T1, CHR_T1_NOTIFY_WEIGHT_TIME)
        setNotifyOn(SVC_T1, CHR_T1_INDICATE_MISC)
        setNotifyOn(SVC_T2, CHR_T2_NOTIFY_WEIGHT_TIME)

        // Configure unit on both flavors (the non-matching write will be ignored by the stack).
        val unitByte = when (user.scaleUnit) {
            WeightUnit.LB, WeightUnit.ST -> 0x02 // LB (vendor uses LB also for ST in their apps)
            else -> 0x01 // KG
        }.toByte()

        val cfg = byteArrayOf(
            0x13, 0x09, 0x15, unitByte, 0x10, 0x00, 0x00, 0x00, 0x00
        )
        cfg[cfg.lastIndex] = checksum(cfg, 0, cfg.lastIndex) // last byte = checksum

        writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, cfg)
        writeTo(SVC_T2, CHR_T2_WRITE_SHARED, cfg)

        // Push current time (seconds since 2000-01-01).
        val epochSecs = (System.currentTimeMillis() / 1000L) - SCALE_UNIX_TIMESTAMP_OFFSET
        val t = epochSecs.toInt()
        val timeMagic = byteArrayOf(
            0x02,
            (t and 0xFF).toByte(),
            ((t ushr 8) and 0xFF).toByte(),
            ((t ushr 16) and 0xFF).toByte(),
            ((t ushr 24) and 0xFF).toByte()
        )
        writeTo(SVC_T1, CHR_T1_WRITE_TIME, timeMagic)
        writeTo(SVC_T2, CHR_T2_WRITE_SHARED, timeMagic)

        // Tell the user to step on
        userInfo(R.string.bt_info_step_on_scale)
    }

    // ---- Notifications ---------------------------------------------------------

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_T1_NOTIFY_WEIGHT_TIME, CHR_T2_NOTIFY_WEIGHT_TIME -> handleVendorPacket(data, user)
            CHR_T1_INDICATE_MISC -> {
                // Not used currently, keep for completeness.
                LogManager.d(TAG, "INDICATE_MISC: ${data.toHexPreview(24)}")
            }
        }
    }

    // ---- Vendor protocol parsing ----------------------------------------------

    private fun handleVendorPacket(data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return

        when (data[0].toInt() and 0xFF) {
            0x10 -> handleLiveWeightFrame(data, user)  // live / stable weight frame
            0x12 -> handleScaleInfoFrame(data)         // scale factor setup
            0x21 -> { /* unknown/unused in current impl */ }
            0x23 -> { /* historical record frame (timestamp+impedance) – not implemented */ }
            else -> LogManager.d(TAG, "QN: unhandled opcode=0x${(data[0].toInt() and 0xFF).toString(16)} ${data.toHexPreview(24)}")
        }
    }

    /**
     * 0x10 frame: live weight updates. When stable flag (byte[5] == 1) is seen,
     * we parse weight and optional resistances (bytes [6..9]) and publish one result.
     */
    private fun handleLiveWeightFrame(data: ByteArray, user: ScaleUser) {
        // Need at least up to indices 9 to read resistances safely.
        if (data.size < 10) return

        val stable = data[5].toInt() == 1
        if (!stable || hasPublishedForThisSession) return

        // Weight is (bytes 3,4) / weightScaleFactor
        val raw = u16be(data[3], data[4])
        var weightKg = raw / weightScaleFactor

        // Heuristic fallback: some “type 2” devices report with /10 even before 0x12 arrives.
        // If weight looks unreasonably small or large, try the /10 fallback once.
        if (weightKg <= 5f || weightKg >= 250f) {
            weightKg = raw / 10.0f
        }

        // Optional resistances (often two values). We primarily use the first one.
        val r1 = u16be(data[6], data[7])
        val r2 = u16be(data[8], data[9])

        LogManager.d(TAG, "QN weight=$weightKg kg, r1=$r1, r2=$r2 (scale=$weightScaleFactor)")

        if (weightKg > 0f) {
            val m = ScaleMeasurement().apply {
                userId = user.id
                weight = weightKg
            }

            // QN body-comp derivation via TrisaBodyAnalyzeLib (vendor-approx model).
            // Empirical conversion from raw resistance to an “impedance-like” value used by lib.
            val impedance = if (r1 < 410f) 3.0f else 0.3f * (r1 - 400f)

            val trisa = TrisaBodyAnalyzeLib(
                if (user.gender.isMale()) 1 else 0,
                user.age,
                user.bodyHeight
            )

            m.fat    = trisa.getFat(weightKg, impedance)
            m.water  = trisa.getWater(weightKg, impedance)
            m.muscle = trisa.getMuscle(weightKg, impedance)
            m.bone   = trisa.getBone(weightKg, impedance)

            publish(snapshot(m))
            hasPublishedForThisSession = true
        }
    }

    /**
     * 0x12 frame: contains a flag describing the native weight scaling.
     * If byte[10] == 1 → /100 else → /10.
     */
    private fun handleScaleInfoFrame(data: ByteArray) {
        if (data.size <= 10) return
        weightScaleFactor = if (data[10].toInt() == 1) 100.0f else 10.0f
        LogManager.d(TAG, "QN set weightScaleFactor=$weightScaleFactor from opcode 0x12")
    }

    // ---- Helpers ---------------------------------------------------------------

    private fun checksum(buf: ByteArray, from: Int, toInclusive: Int): Byte {
        var s = 0
        for (i in from..toInclusive) s = (s + (buf[i].toInt() and 0xFF)) and 0xFF
        return s.toByte()
    }

    private fun u16be(a: Byte, b: Byte): Float =
        (((a.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)).toFloat()

    /** Make a defensive snapshot so later mutations don’t affect published data. */
    private fun snapshot(m: ScaleMeasurement) = ScaleMeasurement().also {
        it.userId      = m.userId
        it.dateTime    = m.dateTime
        it.weight      = m.weight
        it.fat         = m.fat
        it.water       = m.water
        it.muscle      = m.muscle
        it.bone        = m.bone
        it.lbm         = m.lbm
        it.visceralFat = m.visceralFat
    }
}
