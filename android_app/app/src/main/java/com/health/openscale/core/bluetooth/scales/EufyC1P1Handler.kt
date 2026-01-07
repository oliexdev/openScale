/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
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
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

/**
 * Eufy C1/P1 handler (Service 0xFFF0, notify on 0xFFF4, write cmds on 0xFFF1).
 * This handler extends OneByone (classic) handler
 * (com/health/openscale/core/bluetooth/scales/OneByoneHandler.kt)
 * because they share the same protocol as documented below.
 *
 * The only differences are the device name matching and adding of battery service
 *
 * Protocol summary (based on legacy driver behavior):
 * - Subscribe NOTIFY on 0xFFF4.
 * - Send "mode/unit" command FD 37 [unit] [group] ... XOR.
 * - Send clock F1 [YYYY be][MM][dd][HH][mm][ss] → expect 2-byte ACK "F1 00".
 * - Request history F2 00 → historic packets (starting with CF ...) follow, end with 2-byte "F2 00".
 *   If any history received, send F2 01 to clear.
 * - Real-time measurements also arrive as CF ... frames (11 or 18+ bytes).
 *
 * We parse CF frames, compute impedance, validate timestamps for history,
 * derive body composition via OneByoneLib, and publish ScaleMeasurement.
 */
class EufyC1P1Handler : OneByoneHandler() {

    // --- UUIDs (16-bit under Bluetooth Base UUID) ------------------------------
    private val SVC_180F = uuid16(0x180F) //battery service
    private val CHR_2A19 = uuid16(0x2A19) //battery characteristic

    private enum class Model { EUFY_T9146, EUFY_T9147 }

    private data class Profile(
        val mainService: UUID,
        val batteryService: UUID,
        val chrBattery: UUID,
        val chrWrite: UUID,
        val chrNotify: UUID
    )

    private var activeModel: Model? = null
    private var profile: Profile? = null

    private fun pFor(m: Model) = when (m) {
        Model.EUFY_T9146, Model.EUFY_T9147 -> Profile(
            mainService = SVC_FFF0,
            batteryService = SVC_180F,
            chrWrite = CHR_FFF1,
            chrNotify = CHR_FFF4,
            chrBattery = CHR_2A19
        )
    }

    private fun nameFor(m: Model) = when (m) {
        Model.EUFY_T9146 -> "Eufy C1"
        Model.EUFY_T9147 -> "Eufy P1"
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()

        val model = when {
            "t9146" in name -> Model.EUFY_T9146
            "t9147" in name -> Model.EUFY_T9147
            else -> return null
        }

        activeModel = model
        profile = pFor(model)


        return DeviceSupport(
            displayName = nameFor(model),
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Link lifecycle --------------------------------------------------------
    override fun onConnected(user: ScaleUser) {
        val p = profile
        if (p == null) {
            logW("No profile available after connection for userId=${user.id}")
            return
        }

        // 1) Battery: subscribe + read once
        setNotifyOn(p.batteryService, p.chrBattery)
        readFrom(p.batteryService, p.chrBattery)

        super.onConnected(user)
        // NOTE: After we receive the ACK, we will request history (F2 00) in onNotification().
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val p = profile
        if (p == null) {
            logW("No profile available after connection for userId=${user.id}")
            return
        }

        when (characteristic) {
            p.chrBattery -> handleBattery(data)
            p.chrNotify -> super.onNotification(characteristic, data, user)
            else -> {
                logD("Unexpected notify from $characteristic ${data.toHexPreview(24)}")
                return
            }
        }

    }

    private fun handleBattery(value: ByteArray) {
        val level = (value.first().toInt() and 0xFF)
        logD("Reported battery level: $level%")
        if (level <= 10) {
            userWarn(R.string.bluetooth_scale_warning_low_battery, level)
        }
    }
}
