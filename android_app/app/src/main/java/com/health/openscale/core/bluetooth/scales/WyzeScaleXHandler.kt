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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.WyzeScaleXLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Date
import java.util.UUID

/**
 * Wyze Scale X (WSCALX). Protocol in [WyzeScaleXLib].
 *
 * Session: key exchange → time (0x01) → unit (0x04) → select user (0x0E) → update user (0x0A).
 * Selecting the user makes the scale replay that user's stored weigh-ins (0x09, each deleted
 * once acknowledged) and then stream live weight (0x08) until the reading settles.
 */
class WyzeScaleXHandler : ScaleDeviceHandler() {

    companion object {
        private const val DISCONNECT_DELAY_MS = 1_000L
    }

    private val random = SecureRandom()
    private var frame = 0
    private var privateKey = 0L
    private var sessionKey: ByteArray? = null
    private var scaleUserId = ByteArray(0)
    private var published = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val claimed = device.serviceUuids.contains(WyzeScaleXLib.SERVICE) ||
            device.name.equals(WyzeScaleXLib.ADVERTISED_NAME, ignoreCase = true) ||
            device.manufacturerData?.contains(WyzeScaleXLib.MANUFACTURER_ID) == true
        if (!claimed) return null
        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.UNIT_CONFIG,
            DeviceCapability.BATTERY_LEVEL,
        )
        return DeviceSupport(
            displayName = "Wyze Scale X",
            capabilities = caps,
            implemented = caps - DeviceCapability.BATTERY_LEVEL,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        frame = 0
        sessionKey = null
        published = false
        scaleUserId = WyzeScaleXLib.userIdFor(user.id)
        privateKey = random.nextInt().toLong() and 0xFFFFFFFFL

        setNotifyOn(WyzeScaleXLib.SERVICE, WyzeScaleXLib.CHARACTERISTIC)
        write(WyzeScaleXLib.keyExchangeFrame(nextFrame(), WyzeScaleXLib.publicKey(privateKey)))
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != WyzeScaleXLib.CHARACTERISTIC) return
        WyzeScaleXLib.parseKeyExchangeReply(data)?.let { peerPublicKey ->
            sessionKey = WyzeScaleXLib.sessionKey(peerPublicKey, privateKey)
            logD("Session key agreed")
            send(WyzeScaleXLib.syncTime(System.currentTimeMillis() / 1000))
            return
        }
        val key = sessionKey ?: run {
            logW("Encrypted frame before key exchange ${data.toHexPreview(8)}")
            return
        }
        val message = WyzeScaleXLib.open(key, data) ?: run {
            logW("Unreadable frame ${data.toHexPreview(8)}")
            return
        }
        val reply = WyzeScaleXLib.parseReply(message) ?: run {
            logD("Unhandled message ${message.toHexPreview(24)}")
            return
        }
        handleReply(reply, user)
    }

    override fun onDisconnected() {
        sessionKey = null
    }

    // --- Protocol ----------------------------------------------------------------------------

    private fun handleReply(reply: WyzeScaleXLib.Reply, user: ScaleUser) {
        when (reply) {
            is WyzeScaleXLib.Status -> handleStatus(reply, user)
            is WyzeScaleXLib.UserList -> logD("Scale holds ${reply.users.size} users")
            is WyzeScaleXLib.History -> {
                if (!reply.userId.contentEquals(scaleUserId)) {
                    logW("History for another scale user, left on the scale")
                    return
                }
                publishMetrics(reply.metrics, Date(reply.epochSeconds * 1000), user)
                send(WyzeScaleXLib.historyAck())
            }
            is WyzeScaleXLib.LiveWeight -> {
                logD("Battery ${reply.batteryPercent} %, live ${reply.metrics.weightKg} kg, final=${reply.final}")
                if (!reply.final || published) return
                published = true
                publishMetrics(reply.metrics, Date(), user)
                scope.launch {
                    delay(DISCONNECT_DELAY_MS)
                    requestDisconnect()
                }
            }
        }
    }

    private fun handleStatus(status: WyzeScaleXLib.Status, user: ScaleUser) {
        if (!status.ok) {
            logW("Command 0x%02X rejected with %d".format(status.cmd, status.code))
            if (status.cmd == WyzeScaleXLib.CMD_CURRENT_USER_NEW) {
                userWarn(R.string.bt_wyze_scale_x_user_rejected)
                requestDisconnect()
            }
            return
        }
        when (status.cmd) {
            WyzeScaleXLib.CMD_SYNC_TIME -> send(WyzeScaleXLib.setUnit(user.scaleUnit == WeightUnit.LB))
            WyzeScaleXLib.CMD_SET_UNIT -> send(WyzeScaleXLib.selectUser(userRecord(user)))
            WyzeScaleXLib.CMD_CURRENT_USER_NEW -> send(WyzeScaleXLib.updateUser(userRecord(user)))
            WyzeScaleXLib.CMD_UPDATE_USER -> userInfo(R.string.bt_info_step_on_scale)
            else -> logD("Command 0x%02X acknowledged".format(status.cmd))
        }
    }

    private fun userRecord(user: ScaleUser): WyzeScaleXLib.UserRecord {
        val last = lastMeasurementFor(user.id)
        return WyzeScaleXLib.UserRecord(
            userId = scaleUserId,
            weightKg = last?.get(MeasurementType.WEIGHT)?.value ?: user.initialWeight,
            male = user.gender.isMale(),
            age = user.age,
            heightCm = user.bodyHeight.toInt(),
            lastImpedanceOhm = last?.get(MeasurementType.IMPEDANCE)?.value ?: 0f,
        )
    }

    private fun publishMetrics(r: WyzeScaleXLib.Metrics, at: Date, user: ScaleUser) {
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = at
            this[MeasurementType.WEIGHT] = Kg(r.weightKg)
            this[MeasurementType.IMPEDANCE] = Ohm(r.impedanceOhm)
        }
        if (r.hasBodyComposition && r.weightKg > 0f) {
            m[MeasurementType.BODY_FAT] = Percent(r.fatPercent)
            m[MeasurementType.WATER] = Percent(r.waterPercent)
            m[MeasurementType.MUSCLE] = Percent(r.muscleKg / r.weightKg * 100f)
            m[MeasurementType.BONE] = Kg(r.boneKg)
            m[MeasurementType.PROTEIN] = Percent(r.proteinPercent)
            m[MeasurementType.LBM] = Kg(r.lbmKg)
            m[MeasurementType.BMR] = Kcal(r.bmrKcal.toFloat())
            m[MeasurementType.VISCERAL_FAT] = r.visceralFat.toFloat()
        }
        publish(m)
    }

    private fun send(message: ByteArray) {
        val key = sessionKey ?: return
        write(WyzeScaleXLib.seal(nextFrame(), key, message))
    }

    private fun write(frame: ByteArray) =
        writeTo(WyzeScaleXLib.SERVICE, WyzeScaleXLib.CHARACTERISTIC, frame, withResponse = false)

    private fun nextFrame(): Int {
        val f = frame
        frame = (frame + 1) and 0x0F
        return f
    }
}
