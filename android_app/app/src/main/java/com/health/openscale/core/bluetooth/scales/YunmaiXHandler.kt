/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.YunmaiLib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date

/**
 * Advertisement parser for the Yunmai X (YMBS-M268) broadcast protocol.
 *
 * The scale never needs a GATT connection for measurements: it broadcasts them as
 * manufacturer-specific data (the vendor app labels this device family "YUNMAI-ADV").
 * It advertises WITHOUT a local name, so identification relies on the advertisement
 * structure itself plus the advertised 16-bit service UUID 0x1320.
 *
 * Full manufacturer AD structure (type 0xFF, 16 bytes; reverse-engineered from a
 * YMBS-M268 advertisement capture and confirmed against a live measurement):
 *
 *   [0..5]   device MAC, byte-reversed
 *   [6..8]   constant signature 0B 74 17
 *   [9]      per-session byte, constant within a weigh-in but varying between them
 *            (observed 0x04, 0x0F, 0x10); purpose unknown, so it is ignored
 *   [10]     state: 01 = measuring (live weight), 02 = weight stable,
 *                   03 = final result incl. impedance
 *   [11..12] weight, u16 BE, unit 0.01 kg
 *   [13..14] impedance, u16 BE, Ω (zero until state 03)
 *   [15]     XOR checksum over bytes [9..14]
 *
 * Android peels the first two bytes off as the manufacturer id, so the payload seen here is
 * the last 14 bytes (offsets above shift by -2). That id is just part of the reversed MAC,
 * so matching relies on the MAC echo, the 0B 74 17 signature and the checksum.
 */
internal object YunmaiXAdv {
    const val STATE_LIVE = 0x01
    const val STATE_STABLE = 0x02
    const val STATE_FINAL = 0x03

    /** 16-bit service UUID present in the scale's advertisements. */
    const val ADV_SERVICE_UUID16 = 0x1320

    data class Frame(val state: Int, val weightKg: Float, val impedanceOhm: Int)

    /** Parse one Android-split manufacturer-data entry; [address] enables the MAC-echo check (skipped if null). */
    fun parse(manufacturerId: Int, payload: ByteArray?, address: String?): Frame? {
        if (payload == null || payload.size != 14) return null

        // Constant signature
        if (payload[4] != 0x0B.toByte() || payload[5] != 0x74.toByte() || payload[6] != 0x17.toByte()) {
            return null
        }

        // XOR checksum over the six bytes preceding it
        var checksum = 0
        for (i in 7..12) checksum = checksum xor (payload[i].toInt() and 0xFF)
        if (checksum != (payload[13].toInt() and 0xFF)) return null

        // The advertisement echoes the device MAC byte-reversed across the
        // manufacturer id and the first four payload bytes.
        val octets = address?.split(":")?.mapNotNull { it.toIntOrNull(16) }
        if (octets != null && octets.size == 6) {
            val macMatches = manufacturerId == ((octets[4] shl 8) or octets[5]) &&
                (payload[0].toInt() and 0xFF) == octets[3] &&
                (payload[1].toInt() and 0xFF) == octets[2] &&
                (payload[2].toInt() and 0xFF) == octets[1] &&
                (payload[3].toInt() and 0xFF) == octets[0]
            if (!macMatches) return null
        }

        val state = payload[8].toInt() and 0xFF
        val weightRaw = ((payload[9].toInt() and 0xFF) shl 8) or (payload[10].toInt() and 0xFF)
        val impedance = ((payload[11].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
        return Frame(state, weightRaw / 100.0f, impedance)
    }
}

/**
 * Yunmai X (YMBS-M268) – broadcast-only handler, see [YunmaiXAdv] for the protocol.
 *
 * Body composition is derived from weight + impedance via [YunmaiLib], like the
 * connected Yunmai SE/Mini handler. The scale repeats the final frame for many
 * seconds, so the handler publishes once and re-arms only when a new measurement
 * session (live/stable frames) is observed.
 */
class YunmaiXHandler : ScaleDeviceHandler() {

    private val deviceSupport = DeviceSupport(
        displayName = "Yunmai X",
        capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM
        ),
        implemented = setOf(
            DeviceCapability.BODY_COMPOSITION
        ),
        linkMode = LinkMode.BROADCAST_ONLY
    )

    /** Publish the next final frame; re-armed by live/stable frames of a new session. */
    private var armed = true

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val manufacturerData = device.manufacturerData
        if (manufacturerData != null) {
            for (i in 0 until manufacturerData.size()) {
                if (YunmaiXAdv.parse(manufacturerData.keyAt(i), manufacturerData.valueAt(i), device.address) != null) {
                    return deviceSupport
                }
            }
        }

        // Saved-device snapshots carry no manufacturer data, and the scale advertises no
        // name — fall back to the advertised 16-bit service UUID.
        if (device.serviceUuids.any { it == uuid16(YunmaiXAdv.ADV_SERVICE_UUID16) }) {
            return deviceSupport
        }

        return null
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val manufacturerData = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED

        var frame: YunmaiXAdv.Frame? = null
        for (i in 0 until manufacturerData.size()) {
            frame = YunmaiXAdv.parse(
                manufacturerData.keyAt(i),
                manufacturerData.valueAt(i),
                result.device?.address
            )
            if (frame != null) break
        }
        val f = frame ?: return BroadcastAction.IGNORED

        return when (f.state) {
            YunmaiXAdv.STATE_LIVE, YunmaiXAdv.STATE_STABLE -> {
                armed = true
                BroadcastAction.CONSUMED_KEEP_SCANNING
            }

            YunmaiXAdv.STATE_FINAL -> {
                if (!armed) return BroadcastAction.CONSUMED_KEEP_SCANNING
                if (f.weightKg <= 0f || !f.weightKg.isFinite()) return BroadcastAction.IGNORED

                publish(buildMeasurement(f, user))
                armed = false
                logI("Measurement published: weight=${f.weightKg} kg, R=${f.impedanceOhm} Ω")
                BroadcastAction.CONSUMED_STOP
            }

            else -> BroadcastAction.IGNORED
        }
    }

    override fun onConnected(user: ScaleUser) {
        logI("Yunmai X handler – broadcast-only, waiting for advertisements")
    }

    private fun buildMeasurement(f: YunmaiXAdv.Frame, user: ScaleUser): ScaleMeasurement {
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            weight = f.weightKg
        }

        if (f.impedanceOhm > 0) {
            m.impedance = f.impedanceOhm.toDouble()

            val sexInt = if (user.gender.isMale()) 1 else 0
            val yunmai = YunmaiLib(sexInt, user.bodyHeight, user.activityLevel)
            val fatPct = yunmai.getFat(user.age, f.weightKg, f.impedanceOhm)

            if (fatPct > 0f && fatPct.isFinite()) {
                m.fat = fatPct
                m.muscle = yunmai.getMuscle(fatPct)
                m.water = yunmai.getWater(fatPct)
                m.bone = yunmai.getBoneMass(m.muscle, f.weightKg)
                m.lbm = yunmai.getLeanBodyMass(f.weightKg, fatPct)
                m.visceralFat = yunmai.getVisceralFat(fatPct, user.age)
            } else {
                logW("Body fat is zero/invalid (R=${f.impedanceOhm})")
            }
        } else {
            logW("Final frame without impedance – weight-only measurement")
        }

        return m
    }
}
