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
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Handler for the Realme Smart Scale (Lifesense A6 lineage).
 * Protocol architecture:
 * - Requires a 6-step active handshake written to 0xA624 immediately upon connection.
 * - Handshake commands are dynamically generated and XOR-obfuscated using the scale's MAC address.
 * - Requires a continuous 0x0001D9 keep-alive ping written to 0xA622 every 1 second.
 * - Live and history measurement streams to 0xA621.
 * - Packets are fully XOR encrypted using a repeating MAC[i % 6] cipher.
 */
class RealmeSmartScaleHandler : ScaleDeviceHandler() {

    companion object {
        private const val TAG = "RealmeScaleHandler"

        private val SVC_A602 = UUID.fromString("0000a602-0000-1000-8000-00805f9b34fb")
        private val CHR_A621 = UUID.fromString("0000a621-0000-1000-8000-00805f9b34fb")
        private val CHR_A622 = UUID.fromString("0000a622-0000-1000-8000-00805f9b34fb")
        private val CHR_A624 = UUID.fromString("0000a624-0000-1000-8000-00805f9b34fb")
        private val CHR_A625 = UUID.fromString("0000a625-0000-1000-8000-00805f9b34fb")

        private val KEEP_ALIVE_CMD = byteArrayOf(0x00, 0x01, 0xD9.toByte())
    }

    private var keepAliveTimer: Timer? = null
    private var scaleMacBytes = ByteArray(6)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()
        val hasSvc = device.serviceUuids.any { it == SVC_A602 }

        if (!name.contains("realme") && !hasSvc) return null

        // Cache the MAC address for the XOR cipher
        scaleMacBytes = macStringToBytes(device.address)

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ
        )

        return DeviceSupport(
            displayName = "Realme Smart Scale",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        setNotifyOn(SVC_A602, CHR_A621)
        setNotifyOn(SVC_A602, CHR_A625)

        LogManager.d(TAG, "Generating and sending dynamic MAC-obfuscated handshake...")
        buildHandshake(user).forEach { cmd ->
            writeTo(SVC_A602, CHR_A624, cmd)
        }

        keepAliveTimer = Timer()
        keepAliveTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    writeTo(SVC_A602, CHR_A622, KEEP_ALIVE_CMD)
                } catch (e: Exception) {
                    LogManager.e(TAG, "Keep-alive write failed: ${e.message}")
                }
            }
        }, 500, 1000)

        LogManager.i(TAG, "Realme scale connected and active.")
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_A621) return

        if (data.size >= 19 && data[0] == 0x10.toByte() && data[1] == 0x11.toByte()) {
            parseMeasurement(data, user)
        }
    }

    override fun onDisconnected() {
        keepAliveTimer?.cancel()
        keepAliveTimer = null
        LogManager.d(TAG, "Disconnected. Stopped keep-alive timer.")
    }

    private fun parseMeasurement(data: ByteArray, user: ScaleUser) {
        // Strip header/length and completely decrypt the payload
        val payload = deobfuscate(data)

        // Read directly from the clean payload bytes
        val weightRaw = u16be(payload, 8)
        val weightKg = weightRaw / 100.0f

        val scaleTime = u32be(payload, 10)
        val impedance = u16be(payload, 14)

        // Sanity check
        if (weightKg <= 0.5f || weightKg > 300f) return

        val measurement = ScaleMeasurement().apply {
            this.userId = user.id
            this.dateTime = if (scaleTime > 0) Date(scaleTime * 1000L) else Date()
            this.weight = weightKg
        }

        // --- LOCAL BIA CALCULATION ENGINE ---
        if (impedance > 0) {
            measurement.impedance = impedance.toDouble()

            val sex = if (user.gender.isMale()) 1 else 0
            val calc = com.health.openscale.core.bluetooth.libs.YunmaiLib(sex, user.bodyHeight, user.activityLevel)

            val fatPct = calc.getFat(user.age, weightKg, impedance)

            if (fatPct > 0f) {
                measurement.fat = fatPct
                measurement.muscle = calc.getMuscle(fatPct) / weightKg * 100.0f
                measurement.water = calc.getWater(fatPct)
                measurement.bone = calc.getBoneMass(measurement.muscle, weightKg)
                measurement.lbm = calc.getLeanBodyMass(weightKg, fatPct)
                measurement.visceralFat = calc.getVisceralFat(fatPct, user.age)
            }
        }

        LogManager.i(TAG, "Measurement: Weight=$weightKg kg, Fat=${measurement.fat}%, Date=${measurement.dateTime}")
        publish(measurement)
    }

    // --- PROTOCOL DYNAMIC GENERATORS ---

    private fun deobfuscate(data: ByteArray): ByteArray {
        val payload = ByteArray(data.size - 2)
        for (i in payload.indices) {
            payload[i] = (data[i + 2].toInt() xor (scaleMacBytes[i % 6].toInt() and 0xFF)).toByte()
        }
        return payload
    }

    private fun buildHandshake(user: ScaleUser): List<ByteArray> {
        val ts = (System.currentTimeMillis() / 1000L).toInt()
        val tz = (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000).toByte()

        val sexByte = if (user.gender.isMale()) 0x00.toByte() else 0x80.toByte()
        val hCm = user.bodyHeight.roundToInt()

        // Handle new users with no initial weight
        val weightToSend = if (user.initialWeight <= 0.0f) {
            0xFFFF
        } else {
            (user.initialWeight * 100.0f).roundToInt()
        }

        // Un-obfuscated raw payloads
        val p1 = byteArrayOf(0x00, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02) // Register
        val p2 = byteArrayOf(0x00, 0x0A, 0x18, (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(), tz) // Set Time
        val p3 = byteArrayOf(0x48, 0x01, 0x00, 0x01) // Start Measure
        val p4 = byteArrayOf(
            0x10, 0x01, 0x01, sexByte, user.age.toByte(),
            (hCm shr 8).toByte(), hCm.toByte(), 0x00, 0x00,
            (weightToSend shr 8).toByte(), weightToSend.toByte()
        ) // User Info
        val p5 = byteArrayOf(0x10, 0x04, 0x00) // Formula
        val p6 = byteArrayOf(0x10, 0x07, 0x01) // Unit (KG)

        return listOf(p1, p2, p3, p4, p5, p6).map { wrapAndObfuscate(it) }
    }

    private fun wrapAndObfuscate(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 2)
        out[0] = 0x10 // Header
        out[1] = payload.size.toByte() // Length
        for (i in payload.indices) {
            out[i + 2] = (payload[i].toInt() xor (scaleMacBytes[i % 6].toInt() and 0xFF)).toByte()
        }
        return out
    }

    private fun macStringToBytes(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "")
        val out = ByteArray(6)
        if (clean.length == 12) {
            for (i in 0 until 6) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return out
    }

    private fun u16be(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun u32be(b: ByteArray, off: Int): Long {
        if (off + 3 >= b.size) return 0
        return ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)
    }
}
