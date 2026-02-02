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
import com.health.openscale.core.bluetooth.libs.TrisaBodyAnalyzeLib
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID
import kotlin.experimental.and

/**
 * QN / FITINDEX ES-26M style scales (vendor protocol on 0xFFE0/0xFFF0).
 *
 * Notes:
 * - There are two very similar layouts (“type 1” using FFE0..FFE5, and an alternative using FFF0..FFF2).
 * - We subscribe to both layouts. Writes to a missing characteristic are harmless (adapter logs a warning).
 * - Weight and two resistance values are broadcast in notifications of 0xFFE1/0xFFF1 (opcode 0x10).
 * - We derive body composition with [TrisaBodyAnalyzeLib] from weight + impedance.
 *
 * FIXED: Protocol type race condition - now waits for 0x12 frame before sending configuration.
 */
class QNHandler : ScaleDeviceHandler() {

    companion object {
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

    /** Last seen protocol type from a vendor packet (byte[2]), echoed back in our replies. */
    private var seenProtocolType: Byte = 0x00.toByte()

    /** Flag to track if we've received the protocol type from 0x12 frame. */
    private var hasReceivedProtocolType = false

    /** Store the current user to access later when sending configuration. */
    private var currentUser: ScaleUser? = null

    // ---- Capability discovery --------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val nameLc = device.name.lowercase()
        val uuids = device.serviceUuids.toSet()

        val hasQN = uuids.contains(uuid16(0xFFE0)) || uuids.contains(uuid16(0xFFF0))
        val nameIsQn = nameLc.contains("qn-scale") || nameLc.contains("renpho-scale")

        // Require BOTH: QN services AND QN-typical name,
        if (!((hasQN && nameIsQn))) return null

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
        seenProtocolType = 0x00.toByte()
        hasReceivedProtocolType = false
        currentUser = user

        // Generic Access: Device Name
        readFrom(uuid16(0x1800), uuid16(0x2A00))
        // Device Information: Manufacturer / Model / FW / SW
        readFrom(uuid16(0x180A), uuid16(0x2A29))
        readFrom(uuid16(0x180A), uuid16(0x2A24))
        readFrom(uuid16(0x180A), uuid16(0x2A26))
        readFrom(uuid16(0x180A), uuid16(0x2A28))

        // Subscribe to both flavors; the adapter will ignore missing ones gracefully.
        if (hasCharacteristic(SVC_T1, CHR_T1_NOTIFY_WEIGHT_TIME)) {
            setNotifyOn(SVC_T1, CHR_T1_NOTIFY_WEIGHT_TIME)
        }
        if (hasCharacteristic(SVC_T1, CHR_T1_INDICATE_MISC)) {
            setNotifyOn(SVC_T1, CHR_T1_INDICATE_MISC)
        }
        if (hasCharacteristic(SVC_T2, CHR_T2_NOTIFY_WEIGHT_TIME)) {
            setNotifyOn(SVC_T2, CHR_T2_NOTIFY_WEIGHT_TIME)
        }

        // IMPORTANT: Do NOT send configuration yet!
        // Wait for 0x12 frame to arrive with protocol type first.
        
        // Tell the user to step on
        userInfo(R.string.bt_info_step_on_scale)
    }

    // ---- Notifications ---------------------------------------------------------

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_T1_NOTIFY_WEIGHT_TIME, CHR_T2_NOTIFY_WEIGHT_TIME -> handleVendorPacket(data, user)
            CHR_T1_INDICATE_MISC -> {
                // Not used currently, keep for completeness.
                logD( "QN: indicate misc: ${data.toHexPreview(24)}")
            }
            else -> {
                val hex = data.toHexPreview(64)
                val ascii = data.toAsciiPreview(64)
                logD("QN: notify chr=${(characteristic.toString())} $hex ascii=$ascii")
            }
        }
    }

    // ---- Vendor protocol parsing ----------------------------------------------

    private fun handleVendorPacket(data: ByteArray, user: ScaleUser) {
        if (data.size < 3) return

        // Capture the protocol type from the scale's message to echo it back in our replies.
        if (seenProtocolType == 0x00.toByte() && data.size > 2) {
            seenProtocolType = (data[2].toInt() and 0xFF).toByte()
            logD("QN: captured seenProtocolType=$seenProtocolType (raw: 0x${(seenProtocolType.toInt() and 0xFF).toString(16).uppercase()})")
        }

        when (data[0].toInt() and 0xFF) {
            0x10 -> handleLiveWeightFrame(data, user)  // live / stable weight frame
            0x14 -> {
                // Scale acknowledgment after unit config - respond with 0x20 time sync
                logD("QN: received 0x14 frame, sending 0x20 time sync")

                // Timestamp: seconds since 2000-01-01 (QN epoch), little-endian
                val epochSecs = (System.currentTimeMillis() / 1000L) - SCALE_UNIX_TIMESTAMP_OFFSET
                val t = epochSecs.toInt()

                val msg = byteArrayOf(
                    0x20, // Opcode
                    0x08, // Length
                    seenProtocolType,
                    (t and 0xFF).toByte(),
                    ((t ushr 8) and 0xFF).toByte(),
                    ((t ushr 16) and 0xFF).toByte(),
                    ((t ushr 24) and 0xFF).toByte(),
                    0x00  // Checksum placeholder
                )
                msg[msg.lastIndex] = checksum(msg, 0, msg.lastIndex - 1)

                if (hasCharacteristic(SVC_T2, CHR_T2_WRITE_SHARED)) {
                    writeTo(SVC_T2, CHR_T2_WRITE_SHARED, msg, true)
                } else if (hasCharacteristic(SVC_T1, CHR_T1_WRITE_CONFIG)) {
                    writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, msg, true)
                }
            }
            0x12 -> handleScaleInfoFrame(data)         // scale factor setup
            0x21 -> {
                // ES-30M requires TWO 0xA0 response frames (from BLE capture analysis)
                logD("QN: received 0x21 frame, sending TWO 0xA0 responses")

                // Response 1: a00d04fe0000000000000000XX
                val msg1 = byteArrayOf(
                    0xa0.toByte(), // Opcode
                    0x0d,          // Length (13 bytes)
                    0x04,          // Sub-opcode type (not protocol type!)
                    0xfe.toByte(), // Payload
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                    0x00           // Checksum placeholder
                )
                msg1[msg1.lastIndex] = checksum(msg1, 0, msg1.lastIndex - 1)

                // Response 2: a00d02010008002106b804029d
                val msg2 = byteArrayOf(
                    0xa0.toByte(), // Opcode
                    0x0d,          // Length (13 bytes)
                    0x02,          // Sub-opcode type (not protocol type!)
                    0x01, 0x00, 0x08, 0x00,
                    0x21, 0x06, 0xb8.toByte(), 0x04, 0x02,
                    0x00           // Checksum placeholder
                )
                msg2[msg2.lastIndex] = checksum(msg2, 0, msg2.lastIndex - 1)

                // Write both responses
                if (hasCharacteristic(SVC_T2, CHR_T2_WRITE_SHARED)) {
                    writeTo(SVC_T2, CHR_T2_WRITE_SHARED, msg1, true)
                    writeTo(SVC_T2, CHR_T2_WRITE_SHARED, msg2, true)
                } else if (hasCharacteristic(SVC_T1, CHR_T1_WRITE_CONFIG)) {
                    writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, msg1, true)
                    writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, msg2, true)
                }

                // After 0xA0 responses, send 0x22 query for stored data
                val queryMsg = byteArrayOf(
                    0x22, // Opcode
                    0x06, // Length
                    seenProtocolType,
                    0x00, 0x03,
                    0x00  // Checksum placeholder
                )
                queryMsg[queryMsg.lastIndex] = checksum(queryMsg, 0, queryMsg.lastIndex - 1)

                if (hasCharacteristic(SVC_T2, CHR_T2_WRITE_SHARED)) {
                    writeTo(SVC_T2, CHR_T2_WRITE_SHARED, queryMsg, true)
                } else if (hasCharacteristic(SVC_T1, CHR_T1_WRITE_CONFIG)) {
                    writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, queryMsg, true)
                }
            }
            0x23 -> {
                // Historical record frame - user data from scale memory
                logD("QN: received user data frame (0x23)")
            }
            0xA1 -> {
                // Acknowledgment from scale
                logD("QN: received 0xA1 acknowledgment")
            }
            0xA3 -> {
                // Acknowledgment from scale
                logD("QN: received 0xA3 acknowledgment")
            }
            else -> logD("QN: unhandled opcode=0x${(data[0].toInt() and 0xFF).toString(16)} ${data.toHexPreview(24)}")
        }
    }

    /**
     * 0x10 frame: live weight updates.
     * Two formats exist:
     * - Original: byte[3,4]=weight, byte[5]=stable, bytes[6-9]=resistances
     * - ES-30M: byte[3]=unit, byte[4]=stable, bytes[5,6]=weight, bytes[7-10]=resistances
     */
    private fun handleLiveWeightFrame(data: ByteArray, user: ScaleUser) {
        logD( "QN: raw notify: ${data.toHexPreview(24)}")

        // Detect format by checking if byte[4] looks like a stable flag (0x00, 0x01, 0x02)
        // vs weight data (typically > 0x10)
        val byte4Value = data[4].toInt() and 0xFF
        val isES30MFormat = byte4Value <= 0x02 && weightScaleFactor == 10.0f

        val stable: Boolean
        val raw: Float
        val r1: Float
        val r2: Float

        if (isES30MFormat) {
            // ES-30M format: byte[4]=stable, bytes[5,6]=weight
            if (data.size < 11) return
            val stableFlag = byte4Value
            stable = stableFlag == 0x02 || stableFlag == 0x01
            raw = u16be(data[5], data[6])
            r1 = u16be(data[7], data[8])
            r2 = u16be(data[9], data[10])
            logD("QN: using ES-30M format (byte[4]=$stableFlag)")
        } else {
            // Original format: byte[5]=stable, bytes[3,4]=weight
            if (data.size < 10) return
            stable = data[5].toInt() == 1
            raw = u16be(data[3], data[4])
            r1 = u16be(data[6], data[7])
            r2 = u16be(data[8], data[9])
            logD("QN: using original format")
        }

        if (!stable || hasPublishedForThisSession) return

        var weightKg = raw / weightScaleFactor

        // Heuristic fallback: some "type 2" devices report with /10 even before 0x12 arrives.
        // If weight looks unreasonably small or large, try the /10 fallback once.
        if (weightKg <= 5f || weightKg >= 250f) {
            weightKg = weightKg / 10.0f
        }

        logD( "QN: weight=$weightKg kg, r1=$r1, r2=$r2 (weight scale factor is = $weightScaleFactor)")

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
     * 
     * CRITICAL FIX: Now sends configuration commands AFTER receiving this frame.
     */
    private fun handleScaleInfoFrame(data: ByteArray) {
        if (data.size <= 10) return
        
        weightScaleFactor = if (data[10].toInt() == 1) 100.0f else 10.0f
        logD("QN: set weightScaleFactor=$weightScaleFactor from opcode 0x12")
        
        // NOW send the configuration after we have the protocol type
        if (!hasReceivedProtocolType) {
            hasReceivedProtocolType = true
            logD("QN: protocol type received, now sending configuration commands")
            sendConfigurationCommands()
        }
    }

    /**
     * Send configuration commands to the scale.
     * MUST be called AFTER receiving the 0x12 frame with protocol type.
     */
    private fun sendConfigurationCommands() {
        val user = currentUser ?: run {
            logD("QN: ERROR - currentUser is null, cannot send configuration")
            return
        }
        
        val unitByte = when (user.scaleUnit) {
            WeightUnit.LB, WeightUnit.ST -> 0x02.toByte() // LB (vendor uses LB also for ST in their apps)
            else -> 0x01.toByte() // KG
        }

        logD("QN: sending config with seenProtocolType=$seenProtocolType unitByte=$unitByte")

        // Configure unit on both flavors (the non-matching write will be ignored by the stack).
        val cfg = byteArrayOf(
            0x13, 0x09, seenProtocolType, unitByte, 0x10, 0x00, 0x00, 0x00, 0x00
        )
        cfg[cfg.lastIndex] = checksum(cfg, 0, cfg.lastIndex) // last byte = checksum

        if (hasCharacteristic(SVC_T1, CHR_T1_WRITE_CONFIG)) {
            writeTo(SVC_T1, CHR_T1_WRITE_CONFIG, cfg, true)
        }
        if (hasCharacteristic(SVC_T2, CHR_T2_WRITE_SHARED)) {
            writeTo(SVC_T2, CHR_T2_WRITE_SHARED, cfg, true)
        }
        
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
        if (hasCharacteristic(SVC_T1, CHR_T1_WRITE_TIME)) {
            writeTo(SVC_T1, CHR_T1_WRITE_TIME, timeMagic, true)
        }
        if (hasCharacteristic(SVC_T2, CHR_T2_WRITE_SHARED)) {
            writeTo(SVC_T2, CHR_T2_WRITE_SHARED, timeMagic, true)
        }
        
        logD("QN: configuration commands sent successfully")
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
