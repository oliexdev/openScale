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
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Excelvan CF36xBLE (a.k.a. "Electronic Scale") GATT handler.
 *
 * Protocol summary:
 * - Service 0xFFF0
 *   - 0xFFF1: Write user config (header 0xFE ... + XOR checksum)
 *   - 0xFFF4: Notify measurement (16 or 17 bytes, starts with 0xCF)
 *
 * The device returns weight in the *unit we configured* (kg/lb/st).
 * We convert to kilograms before publishing, matching app storage semantics.
 */
class ExcelvanCF36xHandler : ScaleDeviceHandler() {

    // --- GATT UUIDs (Bluetooth Base UUID) -------------------------------------
    private val SVC get() = uuid16(0xFFF0)
    private val CHAR_WRITE get() = uuid16(0xFFF1)
    private val CHAR_NOTIFY get() = uuid16(0xFFF4)

    // Last full frame we processed, to ignore duplicates
    private var lastFrame: ByteArray? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // These scales commonly advertise as "Electronic Scale"
        // (no reliable manufacturer data). Keep it simple and name-match.
        val name = device.name ?: return null
        if (!name.equals("Electronic Scale", ignoreCase = true)) return null

        val theoretical = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG
        )
        val implemented = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG
        )

        return DeviceSupport(
            displayName = "Excelvan CF36xBLE",
            capabilities = theoretical,
            implemented = implemented,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logD("onConnected -> send user config, then enable notify, then prompt user")

        // 1) Build and send user configuration (mirrors legacy driver)
        val cfg = buildUserConfig(user)
        writeTo(SVC, CHAR_WRITE, cfg, withResponse = true)

        // 2) Enable notifications for measurement characteristic
        setNotifyOn(SVC, CHAR_NOTIFY)

        // 3) Tell the user to step on the scale
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) {
            logD("notify ignored: chr=$characteristic (expected $CHAR_NOTIFY)")
            return
        }
        if (data.isEmpty()) return

        // Expected: 16 or 17 bytes starting with 0xCF
        if (data.size !in 16..17 || data[0] != 0xCF.toByte()) {
            logD("unexpected frame len=${data.size} head=${String.format("%02X", data[0])}")
            return
        }

        // De-dup the same full frame (some devices repeat once)
        val previous = lastFrame
        if (previous != null && previous.contentEquals(data)) {
            logD("duplicate frame ignored")
            return
        }
        lastFrame = data.copyOf()

        // Parse & publish
        parseAndPublish(data, user)

        // These scales typically send a single "final" frame. It's reasonable to disconnect.
        // If you prefer to keep the link open, comment the next line.
        requestDisconnect()
    }

    // --- Protocol helpers ------------------------------------------------------

    /**
     * Build the 8-byte user configuration frame:
     * [0]=0xFE, [1]=userId, [2]=sex(1=male,0=female), [3]=activity(0/1/2),
     * [4]=height(cm), [5]=age, [6]=unit(1=kg,2=lb,4=st), [7]=xor checksum over [1..6]
     */
    private fun buildUserConfig(user: ScaleUser): ByteArray {
        val userId = 0x01 // Legacy driver always used 0x01; keep the same for compatibility
        val sex = if (user.gender.isMale()) 0x01 else 0x00

        val activity = when (user.activityLevel) {
            ActivityLevel.SEDENTARY,
            ActivityLevel.MILD -> 0x00
            ActivityLevel.MODERATE -> 0x01
            ActivityLevel.HEAVY,
            ActivityLevel.EXTREME -> 0x02
        }

        val height = user.bodyHeight
            .roundToInt()
            .coerceIn(0, 255)

        val age = user.age.coerceIn(0, 255)

        val unit = when (user.scaleUnit) {
            WeightUnit.KG -> 0x01
            WeightUnit.LB -> 0x02
            WeightUnit.ST -> 0x04
        }

        val cfg = byteArrayOf(
            0xFE.toByte(),
            userId.toByte(),
            sex.toByte(),
            activity.toByte(),
            height.toByte(),
            age.toByte(),
            unit.toByte(),
            0x00 // checksum placeholder
        )
        cfg[cfg.lastIndex] = xorChecksum(cfg, start = 1, endExclusive = cfg.size - 1)
        logD("config -> " + cfg.toHexPreview(32))
        return cfg
    }

    private fun parseAndPublish(frame: ByteArray, user: ScaleUser) {
        // Byte layout (legacy driver):
        // [0]=0xCF
        // [4..5]=weight (BE) /10
        // [6..7]=fat (BE) /10  [%]
        // [8]=bone /10 [kg]
        // [9..10]=muscle (BE) /10 [%]
        // [11]=visceral fat (index)
        // [12..13]=water (BE) /10 [%]
        // [14..15]=BMR (BE) (ignored)
        val weightDeviceUnit = ConverterUtils.fromUnsignedInt16Be(frame, 4) / 10.0f
        val fat = ConverterUtils.fromUnsignedInt16Be(frame, 6) / 10.0f
        val bone = (frame[8].toInt() and 0xFF) / 10.0f
        val muscle = ConverterUtils.fromUnsignedInt16Be(frame, 9) / 10.0f
        val visceral = (frame[11].toInt() and 0xFF).toFloat()
        val water = ConverterUtils.fromUnsignedInt16Be(frame, 12) / 10.0f
        // val bmr = ConverterUtils.fromUnsignedInt16Be(frame, 14) // not stored

        // Convert weight from the unit we configured to kilograms (app storage is kg)
        val weightKg = ConverterUtils.toKilogram(weightDeviceUnit, user.scaleUnit)

        val m = ScaleMeasurement().apply {
            weight = weightKg
            this.fat = fat
            this.muscle = muscle
            this.water = water
            this.bone = bone
            this.visceralFat = visceral
            // timestamp: device does not provide; let app fill "now" on save if needed
        }

        logD("publish kg=${m.weight} fat=${m.fat} water=${m.water} muscle=${m.muscle} bone=${m.bone} visc=${m.visceralFat}")
        publish(m)
    }

    // XOR checksum over [start, endExclusive)
    private fun xorChecksum(src: ByteArray, start: Int, endExclusive: Int): Byte {
        var sum = 0
        for (i in start until endExclusive) sum = sum xor (src[i].toInt() and 0xFF)
        return (sum and 0xFF).toByte()
    }

    // Broadcast-only devices are not used here, but keep default:
    override fun onAdvertisement(result: ScanResult, user: ScaleUser) = BroadcastAction.IGNORED
}
