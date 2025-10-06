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
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

class CustomOpenScaleHandler : ScaleDeviceHandler() {

    // HM-10 (as used by the DIY openScale sketch)
    private val SVC_HM10: UUID = uuid16(0xFFE0)
    private val CHR_HM10: UUID = uuid16(0xFFE1)

    // Line buffer for incoming ASCII frames
    private val lineBuf = StringBuilder()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim()
        if (!name.equals("openScale", ignoreCase = true)) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM
        )

        return DeviceSupport(
            displayName = "Custom openScale",
            capabilities = caps,
            implemented = caps,
            tuningProfile = TuningProfile.Balanced,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Subscribe to HM-10 RX/TX characteristic and push current date-time
        setNotifyOn(SVC_HM10, CHR_HM10)
        writeTimeCommand()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHR_HM10 || data.isEmpty()) return

        // Append ASCII and split on '\n' (HM-10 delivers text lines)
        val text = String(data, StandardCharsets.ISO_8859_1)
        lineBuf.append(text)

        var idx = lineBuf.indexOf("\n")
        while (idx >= 0) {
            val line = lineBuf.substring(0, idx)
            lineBuf.delete(0, idx + 1)
            parseLine(line)
            idx = lineBuf.indexOf("\n")
        }
    }

    // --- Protocol ----------------------------------------------------------------

    /**
     * Sends the current time in the legacy format:
     * "2YY,MM,DD,HH,mm,ss," (exactly as the old driver did).
     */
    private fun writeTimeCommand() {
        val cal = Calendar.getInstance()
        val yy = cal.get(Calendar.YEAR) - 2000 // 0..99
        val msg = String.format(
            Locale.US,
            "2%d,%d,%d,%d,%d,%d,",
            yy,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND)
        )
        writeTo(SVC_HM10, CHR_HM10, msg.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun clearEeprom() {
        // Original driver wrote a single ASCII '9' to trigger EEPROM clear
        writeTo(SVC_HM10, CHR_HM10, byteArrayOf('9'.code.toByte()))
    }

    /**
     * Expected frame formats (text):
     *  $I$<text>\n        – info
     *  $E$<text>\n        – error
     *  $S$<text>\n        – stored size
     *  $F$<text>\n        – finished sending; clear EEPROM and disconnect
     *  $D$<csv>\n         – data record
     *
     * CSV for $D$ is:
     *  idx,year,month,day,hour,minute,weight,fat,water,muscle,checksum
     */
    private fun parseLine(raw: String) {
        val line = raw.trimEnd('\r') // defensive (some modules send \r\n)
        if (line.length < 3 || line[0] != '$' || line[2] != '$') {
            userWarn(
                com.health.openscale.R.string.bt_error_handler_parse_error,
                "Invalid frame: $line"
            )
            return
        }

        val type = line[1]
        val payload = line.substring(3)

        when (type) {
            'I' -> logD("MCU Info: $payload")
            'E' -> logW("MCU Error: $payload")
            'S' -> logD("MCU stored size: $payload")
            'F' -> {
                logD("MCU finished transmission -> clear EEPROM & disconnect")
                clearEeprom()
                requestDisconnect()
            }
            'D' -> parseDataRecord(payload)
            else -> logW("Unknown MCU frame: $line")
        }
    }

    private fun parseDataRecord(csv: String) {
        val f = csv.split(',')
        // Expect at least 11 fields (index + 10 values)
        if (f.size < 11) {
            userWarn(
                com.health.openscale.R.string.bt_error_handler_parse_error,
                "Too few CSV fields in data record: $csv"
            )
            return
        }

        try {
            val yearRaw = f[1].toInt()
            val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
            val month = f[2].toInt()   // 1..12
            val day = f[3].toInt()
            val hour = f[4].toInt()
            val minute = f[5].toInt()

            val weight = f[6].toFloat()
            val fat = f[7].toFloat()
            val water = f[8].toFloat()
            val muscle = f[9].toFloat()

            val givenChecksum = f[10].toInt()

            // Old driver used XOR of ints and truncated floats
            val computed = xorChecksumInts(
                f[0].toInt(), // index
                yearRaw,
                month, day, hour, minute,
                floor(weight.toDouble()).toInt(),
                floor(fat.toDouble()).toInt(),
                floor(water.toDouble()).toInt(),
                floor(muscle.toDouble()).toInt()
            )

            if (computed != givenChecksum) {
                userWarn(
                    com.health.openscale.R.string.bt_error_handler_parse_error,
                    "Checksum mismatch: calc=$computed recv=$givenChecksum"
                )
                return
            }

            val whenDate: Date = SimpleDateFormat("yyyy/MM/dd/HH/mm", Locale.US)
                .parse("$year/$month/$day/$hour/$minute")!!

            val m = ScaleMeasurement().apply {
                dateTime = whenDate
                this.weight = weight
                this.fat = fat
                this.water = water
                this.muscle = muscle
            }

            publish(m)
            logD("Published measurement: ts=$whenDate kg=$weight fat=$fat water=$water muscle=$muscle")
        } catch (t: Throwable) {
            userWarn(
                com.health.openscale.R.string.bt_error_handler_parse_error,
                "Failed to parse data record: ${t.message}"
            )
        }
    }

    // XOR checksum over a list of ints (mimics legacy code)
    private fun xorChecksumInts(vararg values: Int): Int {
        var x = 0
        for (v in values) x = x xor v
        return x
    }
}
