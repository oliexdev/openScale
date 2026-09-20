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
import com.health.openscale.core.bluetooth.libs.AlphaSmartScalePro3Lib
import com.health.openscale.core.bluetooth.libs.AlphaSmartScalePro3Lib.ScaleFrame
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ALPHA Smart Scale PRO 3 (model SCP-03), a FitDays/icomon scale sold in Poland. It advertises as
 * "MY_SCALE" with the shared 0xFFB0 service, so it must be registered ahead of [MGBHandler], which
 * claims that service by itself and cannot parse this scale's frames (openScale issue #1358).
 *
 * Session, as the FitDays app does it:
 *  1. Enable NOTIFY on 0xFFB2. The scale starts streaming live weight (0xD5) on its own.
 *  2. Write the time/profile frame (0xD0), a control frame (0xDF/01) and the profile again.
 *  3. Weigh-ins done without the app arrive as stored records (0xD8); each is ACKed.
 *  4. Once the weight locks, the scale sends the final result (0xD6) with the impedance; it is
 *     ACKed, published and the link is closed.
 *
 * The scale only transmits weight and impedance. FitDays derives everything else (fat, water,
 * muscle, bone, BMR, ...) in the app, so nothing derived is published here. All protocol parsing
 * lives in [AlphaSmartScalePro3Lib].
 */
class AlphaSmartScalePro3Handler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_WRITE: UUID = uuid16(0xFFB1)   // write
    private val CHAR_NOTIFY: UUID = uuid16(0xFFB2)  // notify

    private var finalPublished = false
    private var lastLiveWeightGrams = -1

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim().uppercase(Locale.ROOT)
        if (name != ADVERTISED_NAME) return null
        if (device.serviceUuids.none { it == SERVICE }) return null

        return DeviceSupport(
            displayName = DISPLAY_NAME,
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,   // impedance electrodes; FitDays computes the rest in-app
                DeviceCapability.HISTORY_READ,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.HISTORY_READ,
                DeviceCapability.TIME_SYNC,
                DeviceCapability.USER_SYNC,
            ),
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        finalPublished = false
        lastLiveWeightGrams = -1

        setNotifyOn(SERVICE, CHAR_NOTIFY)

        // FitDays writes the profile, one control frame, then the profile again.
        writeProfile(user)
        writeTo(SERVICE, CHAR_WRITE, AlphaSmartScalePro3Lib.buildSessionStart())
        writeProfile(user)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return

        val frame = AlphaSmartScalePro3Lib.parse(data)
        if (frame == null) {
            logD("dropping invalid frame ${data.toHexPreview(20)}")
            return
        }

        when (frame) {
            is ScaleFrame.LiveWeight -> {
                if (frame.weightGrams != lastLiveWeightGrams) {
                    lastLiveWeightGrams = frame.weightGrams
                    logD("live weight ${frame.weightGrams} g locked=${frame.locked}")
                }
            }

            is ScaleFrame.FinalResult -> {
                logI("final result ${frame.weightGrams} g, impedance=${frame.impedanceOhms} Ω, locked=${frame.locked}")
                acknowledge(AlphaSmartScalePro3Lib.WIRE_FINAL_RESULT)
                if (finalPublished) {
                    logD("final result already published for this session, ignoring")
                    return
                }
                finalPublished = true
                publishMeasurement(user, frame.weightGrams, frame.impedanceOhms, timestampEpochSeconds = null)
                // The ACK is queued behind the publish; give the adapter a moment to send it.
                scope.launch {
                    delay(DISCONNECT_DELAY_MS)
                    requestDisconnect()
                }
            }

            is ScaleFrame.StoredRecord -> {
                logI("stored record ${frame.weightGrams} g at ${frame.timestampEpochSeconds} (locked=${frame.locked})")
                acknowledge(AlphaSmartScalePro3Lib.WIRE_STORED_RECORD)
                publishMeasurement(user, frame.weightGrams, impedanceOhms = null, timestampEpochSeconds = frame.timestampEpochSeconds)
            }

            is ScaleFrame.Unknown -> logD("unhandled wire type 0x%02X: %s".format(frame.wireType, data.toHexPreview(20)))
        }
    }

    override fun onDisconnected() {
        finalPublished = false
        lastLiveWeightGrams = -1
    }

    // --- I/O helpers -----------------------------------------------------------

    private fun writeProfile(user: ScaleUser) {
        val sex = if (user.gender.isMale()) AlphaSmartScalePro3Lib.SEX_MALE else AlphaSmartScalePro3Lib.SEX_FEMALE
        val frame = AlphaSmartScalePro3Lib.buildProfile(
            nowEpochSeconds = System.currentTimeMillis() / 1000L,
            heightCm = user.bodyHeight.toInt(),
            age = user.age,
            sex = sex,
        )
        writeTo(SERVICE, CHAR_WRITE, frame)
    }

    private fun acknowledge(wireType: Int) {
        writeTo(SERVICE, CHAR_WRITE, AlphaSmartScalePro3Lib.buildAck(wireType))
    }

    private fun publishMeasurement(user: ScaleUser, weightGrams: Int, impedanceOhms: Int?, timestampEpochSeconds: Long?) {
        if (weightGrams <= 0) {
            logW("ignoring measurement without weight")
            return
        }
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = timestampEpochSeconds?.let { plausibleDate(it) } ?: Date()
            this[MeasurementType.WEIGHT] = Kg(weightGrams / 1000f)
            if (impedanceOhms != null && impedanceOhms > 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhms.toFloat())
            }
        }
        publish(measurement)
    }

    /** Stored-record timestamps are Unix seconds; fall back to "now" if the scale's clock is off. */
    private fun plausibleDate(epochSeconds: Long): Date {
        val millis = epochSeconds * 1000L
        return if (millis in EARLIEST_PLAUSIBLE_MS..(System.currentTimeMillis() + ONE_DAY_MS)) Date(millis) else Date()
    }

    companion object {
        const val ADVERTISED_NAME = "MY_SCALE"
        const val DISPLAY_NAME = "ALPHA Smart Scale PRO 3"

        private const val DISCONNECT_DELAY_MS = 1_000L
        private const val ONE_DAY_MS = 86_400_000L
        private const val EARLIEST_PLAUSIBLE_MS = 1_577_836_800_000L // 2020-01-01
    }
}
