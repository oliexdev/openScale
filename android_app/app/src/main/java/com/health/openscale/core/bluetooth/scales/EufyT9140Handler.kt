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
import com.health.openscale.core.bluetooth.libs.EufyT9140Lib
import com.health.openscale.core.bluetooth.libs.EufyT9140Lib.Frame
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Eufy Smart Scale T9140 that advertises as `eufy T9140` (AiCare/eLink module).
 *
 * The wire format is documented in [EufyT9140Lib]. Session flow, as the EufyLife app runs it:
 * subscribe to `…f6b2`, set the clock, set the unit, request stored weigh-ins; the scale then
 * streams live weights, an impedance result and a final record with the measurement time.
 *
 * Published per weigh-in: weight, impedance, and body fat / water / muscle / bone derived from
 * the impedance with [StandardImpedanceLib] (the scale itself measures only weight and
 * impedance; the vendor computes the rest in its app). Weigh-ins the scale stored while no phone
 * was connected are published with their own timestamps.
 */
class EufyT9140Handler : ScaleDeviceHandler() {

    companion object {
        private val SERVICE: UUID = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")
        private val CHAR_NOTIFY: UUID = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
        private val CHAR_WRITE: UUID = UUID.fromString("4143f6b1-5300-4900-4700-414943415245")

        /** After "measurement finished", wait this long for the final record before falling back. */
        private const val FINAL_RECORD_GRACE_MS = 3_000L

        /** Stored records older than this are treated as an unset scale clock. */
        private const val EARLIEST_PLAUSIBLE_EPOCH = 1_577_836_800L // 2020-01-01T00:00:00Z

        private const val MAX_PLAUSIBLE_DECI_KG = 1800 // the scale's range ends at 180 kg

        /** True if [epochSeconds] can be a real weigh-in time on a scale whose clock we set. */
        fun isPlausibleTimestamp(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
            epochSeconds >= EARLIEST_PLAUSIBLE_EPOCH && epochSeconds * 1000 <= nowMillis + 24 * 3600 * 1000L
    }

    // Per-session state. Handlers are reused across connections; reset in onConnected().
    private var stableDeciKg = 0
    private var impedanceOhm = 0
    private var measurementEpoch = 0L
    private var published = false
    private var graceJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.lowercase(Locale.ROOT).startsWith("eufy t9140")) return null
        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.UNIT_CONFIG,
        )
        return DeviceSupport(
            displayName = "Eufy Smart Scale T9140",
            capabilities = caps,
            // Unit selection is not offered: only kg mode has been captured, see EufyT9140Lib.
            implemented = caps - DeviceCapability.UNIT_CONFIG,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetMeasurement()

        setNotifyOn(SERVICE, CHAR_NOTIFY)
        writeTo(SERVICE, CHAR_WRITE, EufyT9140Lib.timeSync(System.currentTimeMillis() / 1000), withResponse = false)
        writeTo(SERVICE, CHAR_WRITE, EufyT9140Lib.setUnit(EufyT9140Lib.UNIT_KG), withResponse = false)
        writeTo(SERVICE, CHAR_WRITE, EufyT9140Lib.historyRequest(), withResponse = false)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        for (frame in EufyT9140Lib.parseNotification(data)) handleFrame(frame, user)
    }

    override fun onDisconnected() {
        graceJob?.cancel()
        graceJob = null
        publishLatched(currentAppUser())
    }

    private fun handleFrame(frame: Frame, user: ScaleUser) {
        when (frame) {
            is Frame.Weight -> {
                if (frame.deciKg <= 0 || frame.deciKg > MAX_PLAUSIBLE_DECI_KG) return
                // A live frame after publishing means a new weigh-in before the scale slept; the
                // scale also repeats the stable frame inside later notifications, so ignore those.
                if (published && !frame.stable) resetMeasurement()
                if (frame.stable && !published) stableDeciKg = frame.deciKg
            }
            is Frame.Impedance -> impedanceOhm = frame.ohm
            is Frame.MeasurementTime -> measurementEpoch = frame.epochSeconds
            is Frame.FinalRecord -> publishRecord(frame.record, user, fromHistory = false)
            is Frame.History -> frame.records.forEach { publishRecord(it, user, fromHistory = true) }
            is Frame.Status -> when (frame.code) {
                EufyT9140Lib.STATUS_MEASUREMENT_FINISHED -> armGrace(user)
                EufyT9140Lib.STATUS_POWER_OFF -> publishLatched(user)
                EufyT9140Lib.STATUS_TIME_SYNC_FAILED -> logW("scale rejected the time sync")
                else -> logD("status 0x%02X".format(frame.code))
            }
            Frame.ImpedanceStarted -> logD("impedance measurement started")
            is Frame.Unknown -> logD("ignored frame (${frame.reason}): ${frame.bytes.toHexPreview(20)}")
        }
    }

    private fun resetMeasurement() {
        graceJob?.cancel()
        graceJob = null
        stableDeciKg = 0
        impedanceOhm = 0
        measurementEpoch = 0L
        published = false
    }

    private fun armGrace(user: ScaleUser) {
        if (published || graceJob != null) return
        graceJob = scope.launch {
            delay(FINAL_RECORD_GRACE_MS)
            logD("no final record within $FINAL_RECORD_GRACE_MS ms; publishing the streamed values")
            publishLatched(user)
        }
    }

    /** Fallback when the final record never arrives: use the streamed stable weight + impedance. */
    private fun publishLatched(user: ScaleUser) {
        if (published || stableDeciKg <= 0) return
        val epoch = measurementEpoch.takeIf { isPlausibleTimestamp(it) }
        publishRecord(EufyT9140Lib.Record(epoch ?: 0L, stableDeciKg, impedanceOhm), user, fromHistory = false)
    }

    private fun publishRecord(record: EufyT9140Lib.Record, user: ScaleUser, fromHistory: Boolean) {
        if (record.weightDeciKg <= 0 || record.weightDeciKg > MAX_PLAUSIBLE_DECI_KG) {
            logW("dropping record with implausible weight $record")
            return
        }
        val plausibleTime = isPlausibleTimestamp(record.epochSeconds)
        if (fromHistory && !plausibleTime) {
            logW("dropping stored record with unset clock $record")
            return
        }
        if (!fromHistory) {
            if (published) return
            published = true
            graceJob?.cancel()
            graceJob = null
        }

        val m = buildMeasurement(
            record = record,
            user = user,
            date = if (plausibleTime) Date(record.epochSeconds * 1000) else Date(),
        )
        logI("publishing ${if (fromHistory) "stored" else "live"} weigh-in $record")
        publish(m)
        if (!fromHistory) requestDisconnect()
    }

    private fun buildMeasurement(record: EufyT9140Lib.Record, user: ScaleUser, date: Date): ScaleMeasurement {
        val weightKg = record.weightKg
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = date
            this[MeasurementType.WEIGHT] = Kg(weightKg)
        }
        // No impedance (0) means the scale could not measure it, e.g. when weighed in socks.
        // Height and age are inputs to the formula; ScaleUser defaults height to -1.
        if (record.impedanceOhm > 0 && user.bodyHeight > 0f && user.age > 0) {
            m[MeasurementType.IMPEDANCE] = Ohm(record.impedanceOhm.toFloat())
            val lib = StandardImpedanceLib(
                gender = user.gender,
                age = user.age,
                weightKg = weightKg.toDouble(),
                heightM = user.bodyHeight / 100.0,
                impedance = record.impedanceOhm.toDouble(),
            )
            m[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
            m[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
            m[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
            m[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
        } else if (record.impedanceOhm > 0) {
            m[MeasurementType.IMPEDANCE] = Ohm(record.impedanceOhm.toFloat())
        }
        return m
    }
}
