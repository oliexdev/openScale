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
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * SWAN / Icomon / YG (aka "MGB") handler.
 *
 * Protocol summary:
 * - Service 0xFFB0
 *   - Char 0xFFB1: config (Write)
 *   - Char 0xFFB2: control/data (Notify)
 *
 * Session flow (observed):
 * 1) Enable NOTIFY on 0xFFB2.
 * 2) Send a few "config" writes on 0xFFB1:
 *    - 0xF7 00 00 00  (init)
 *    - 0xFA 00 00 00  (init)
 *    - 0xFB <sex> <age> <height_cm>
 *    - 0xFD <yy> <mm> <dd>
 *    - 0xFC <HH> <MM> <SS>
 *    - 0xFE 06 <unit> 00
 * 3) Ask user to step on the scale.
 *
 * Data frames (NOTIFY on 0xFFB2, 20 bytes):
 *  - First part:  header AC 02|03 FF … → contains weight, fat (and other fields we ignore)
 *  - Second part: header 01 00 …       → contains muscle, bone, water (+ misc)
 * We collect both parts to publish one complete ScaleMeasurement.
 */
class MGBHandler : ScaleDeviceHandler() {

    // -- UUIDs --
    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_CFG: UUID = uuid16(0xFFB1)   // write
    private val CHAR_CTRL: UUID = uuid16(0xFFB2)  // notify

    // Pending measurement until 2nd frame arrives
    private var pending: ScaleMeasurement? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase(Locale.ROOT)
        val nameMatch =
            (name?.startsWith("swan") == true) ||
                    (name == "icomon") ||
                    (name == "yg")

        val serviceMatch = device.serviceUuids.any { it == SERVICE }

        if (!nameMatch && !serviceMatch) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM, // we get final (and sometimes intermediate) frames
            DeviceCapability.BODY_COMPOSITION,   // fat, water, muscle, bone
            DeviceCapability.TIME_SYNC,          // we set clock
            DeviceCapability.USER_SYNC,          // sex/age/height
            DeviceCapability.UNIT_CONFIG         // kg/lb/st
        )
        // We implement all of the above for this device
        return DeviceSupport(
            displayName = "SWAN / Icomon (MGB)",
            capabilities = caps,
            implemented = caps,
            bleTuning = null,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // -- Session bootstrap --
    override fun onConnected(user: ScaleUser) {
        // 1) Notifications on data characteristic
        setNotifyOn(SERVICE, CHAR_CTRL)

        // 2) Write initialization/config sequence
        //    The adapter paces I/O for us; just queue in order.

        // Magic inits
        writeCfg(0xF7, 0, 0, 0)
        writeCfg(0xFA, 0, 0, 0)

        // User: sex (1=male, 2=female), age, height(cm)
        val sexByte = if (user.gender.isMale()) 1 else 2
        val age = user.age
        val heightCm = user.bodyHeight.toInt().coerceAtLeast(0)
        writeCfg(0xFB, sexByte, age, heightCm)

        // Date (yy since 2000, mm, dd)
        val now = java.util.Calendar.getInstance()
        val yy = (now.get(java.util.Calendar.YEAR) - 2000).coerceIn(0, 99)
        val mm = now.get(java.util.Calendar.MONTH) + 1 // 1..12
        val dd = now.get(java.util.Calendar.DAY_OF_MONTH)
        writeCfg(0xFD, yy, mm, dd)

        // Time (HH, MM, SS)
        val HH = now.get(java.util.Calendar.HOUR_OF_DAY)
        val MM = now.get(java.util.Calendar.MINUTE)
        val SS = now.get(java.util.Calendar.SECOND)
        writeCfg(0xFC, HH, MM, SS)

        // Units: 0xFE, 6, unit, 0
        // Note: ScaleUser.scaleUnit.toInt() matches legacy mapping (KG=1, LB=2, ST=3)
        writeCfg(0xFE, 6, user.scaleUnit.toInt(), 0)

        // 3) Inform user
        userInfo(R.string.bt_info_step_on_scale)
    }

    // -- Notifications --
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_CTRL) return
        if (data.size != 20) return

        val b0 = data[0].toUByte().toInt()
        val b1 = data[1].toUByte().toInt()
        val b2 = data[2].toUByte().toInt()

        // First frame: AC 02|03 FF …
        if (b0 == 0xAC && (b1 == 0x02 || b1 == 0x03) && b2 == 0xFF) {
            parseFirstFrame(data)
            return
        }

        // Second frame: 01 00 …
        if (b0 == 0x01 && b1 == 0x00) {
            parseSecondFrameAndPublish(data)
        }
    }

    // -- Helpers: config writer, parsers, LE readers --

    /**
     * Writes an 8-byte config packet to 0xFFB1:
     * [AC, 02, b2, b3, b4, b5, CC, checksum]
     * checksum = (b2 + b3 + b4 + b5 + 0xCC) & 0xFF
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

    /**
     * First 20-byte frame:
     * AC 02|03 FF  00 02 21  YY MM DD HH mm ss  (we ignore these timestamps)
     *  .. then:
     *  weight(LE, *0.1), BMI(LE, *0.1), fat(LE, *0.1), 00, 00
     */
    private fun parseFirstFrame(d: ByteArray) {
        // Create fresh measurement; legacy code stores "now" as timestamp
        val m = ScaleMeasurement().apply { dateTime = Date() }

        // Byte index pointer after 3-byte header:
        var p = 3

        // Skip 3 unknown bytes: 00, 02, 21
        p += 3

        // Skip 6 bytes (YY MM DD HH mm ss) – scale timestamp, legacy ignored
        p += 6

        // weight (uint16 LE * 0.1)
        m.weight = readDeciLE(d, p); p += 2

        // BMI (ignored)
        /* val bmi = readDeciLE(d, p); */ p += 2

        // fat %
        m.fat = readDeciLE(d, p); p += 2

        // two unknown bytes
        p += 2

        pending = m
        LogManager.d("MgbHandler", "first frame -> weight=${m.weight}, fat=${m.fat}")
    }

    /**
     * Second 20-byte frame:
     * 01 00 … then: muscle(LE,*0.1), BMR(LE,*0.1, ignored),
     * bone(LE,*0.1), water(LE,*0.1), age(u8), protein(LE,*0.1, ignored), … padding
     */
    private fun parseSecondFrameAndPublish(d: ByteArray) {
        val m = pending ?: return

        var p = 2 // after 01 00 header

        m.muscle = readDeciLE(d, p); p += 2
        /* val bmr = readDeciLE(d, p); */ p += 2
        m.bone = readDeciLE(d, p); p += 2
        m.water = readDeciLE(d, p); p += 2
        /* val age = d[p].toUByte().toInt(); */ p += 1
        /* val protein = readDeciLE(d, p); */ p += 2
        // Skip remaining bytes (unknown/padding)
        // p now ~ 11; frame is 20 bytes, the rest is vendor noise

        publish(m)
        pending = null
        LogManager.d("MgbHandler", "second frame -> muscle=${m.muscle}, bone=${m.bone}, water=${m.water} → published")
    }

    /** Read uint16 little-endian and scale by 0.1f. */
    private fun readDeciLE(d: ByteArray, off: Int): Float {
        val lo = d[off].toUByte().toInt()
        val hi = d[off + 1].toUByte().toInt()
        val v = (hi shl 8) or lo
        return v / 10.0f
    }
}
