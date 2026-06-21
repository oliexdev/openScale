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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Ported from ble-scale-sync (GPL-3.0, © Kristián Partl): src/scales/eufy-p2.ts,
 * itself ported from bdr99/eufylife-ble-client (MIT, © bdr99). Both credited per GPL/MIT.
 * NOTE: the GATT handshake timing could not be verified on hardware; the crypto/parse core
 * is covered by EufyP2LibTest. The C0/C2 writes may need slower pacing (upstream uses ~1s
 * between frames) — confirm on a real T9148/T9149 before release.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.EufyAuthHandler
import com.health.openscale.core.bluetooth.libs.EufyP2Lib
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Eufy Smart Scale P2 (T9148) and P2 Pro (T9149).
 *
 * Service 0xFFF0: FFF1 write (commands + auth), FFF2 weight notify, FFF4 auth notify. Before
 * the scale streams weight on FFF2, the client completes a C0/C1/C2/C3 handshake over
 * FFF1 → FFF4 (key = MD5 of the MAC). Once authenticated, FFF2 yields 16-byte weight frames
 * with impedance. See [EufyP2Lib] for the protocol details and tests.
 */
class EufyP2Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFF0)
    private val CHAR_WRITE: UUID = uuid16(0xFFF1)
    private val CHAR_DATA: UUID = uuid16(0xFFF2)   // weight notify
    private val CHAR_AUTH: UUID = uuid16(0xFFF4)   // auth notify

    private var auth: EufyAuthHandler? = null
    private var c3Seen = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        if (!name.startsWith("eufy t9148") && !name.startsWith("eufy t9149")) return null

        val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM, DeviceCapability.BODY_COMPOSITION)
        return DeviceSupport(
            displayName = "Eufy Smart Scale P2/P2 Pro",
            capabilities = caps,
            implemented = caps,
            tuningProfile = TuningProfile.Conservative, // Eufy needs slow handshake pacing
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        auth = null
        c3Seen = false

        val mac = getPeripheral()?.address
        if (mac.isNullOrEmpty()) {
            logE("Eufy P2: no device MAC available — cannot authenticate")
            return
        }

        setNotifyOn(SERVICE, CHAR_DATA)
        setNotifyOn(SERVICE, CHAR_AUTH)

        val handler = try {
            EufyAuthHandler(mac)
        } catch (e: IllegalArgumentException) {
            logE("Eufy P2: ${e.message}")
            return
        }
        auth = handler

        logD("Eufy P2: sending C0 handshake")
        for (frame in handler.buildC0()) {
            writeTo(SERVICE, CHAR_WRITE, frame, withResponse = true)
        }
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHAR_AUTH -> handleAuthFrame(data)
            CHAR_DATA -> {
                if (auth?.isAuthenticated != true) {
                    logD("Eufy P2: weight frame before auth complete — ignoring")
                    return
                }
                val reading = EufyP2Lib.parseWeightNotification(data) ?: return
                publish(ScaleMeasurement().apply {
                    dateTime = Date()
                    weight = reading.weightKg
                    userId = user.id
                    if (reading.impedanceOhm > 0) impedance = reading.impedanceOhm.toDouble()
                })
            }
        }
    }

    override fun onDisconnected() {
        auth = null
        c3Seen = false
    }

    private fun handleAuthFrame(data: ByteArray) {
        val handler = auth ?: return
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            0xc1 -> {
                if (!handler.handleC1(data)) return
                logD("Eufy P2: C1 complete, sending C2")
                for (frame in handler.buildC2()) {
                    writeTo(SERVICE, CHAR_WRITE, frame, withResponse = true)
                }
            }
            0xc3 -> {
                if (c3Seen) return
                c3Seen = true
                handler.handleC3(data)
                if (handler.isAuthenticated) {
                    logI("Eufy P2: authentication successful, waiting for weight")
                } else {
                    logW("Eufy P2: authentication failed (scale rejected credentials)")
                }
            }
        }
    }
}
