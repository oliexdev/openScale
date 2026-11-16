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
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Eufy Smart Scale T9140 that advertises as `eufy T9140` (AiCare/eLink module).
 *
 * The wire format and the differences between firmware versions are documented in
 * [EufyT9140Lib]. Session flow: subscribe to the notify characteristic, set the clock, set the
 * display unit, request stored weigh-ins. The scale then streams live weights and, per weigh-in,
 * a stable weight, an impedance result, the measurement time and "measurement finished". The
 * connection stays open for further weigh-ins until the scale powers off.
 *
 * Published per weigh-in: weight, impedance, and body fat / water / muscle / bone derived from
 * the impedance with [StandardImpedanceLib] (the scale itself measures only weight and
 * impedance). Every measurement carries the scale's own timestamp, so a weigh-in that reaches
 * openScale twice (as a live result and as a stored record) collides on openScale's unique
 * user + timestamp key; the handler also skips such repeats itself.
 */
class EufyT9140Handler : ScaleDeviceHandler() {

    companion object {
        private fun aicare(short: String): UUID = UUID.fromString("4143$short-5300-4900-4700-414943415245")

        /** (service, notify, write) per firmware family; 2026 units use f7b0, the #772 unit f6b0. */
        private val FAMILIES = listOf(
            Triple(aicare("f7b0"), aicare("f7b2"), aicare("f7b1")),
            Triple(aicare("f6b0"), aicare("f6b2"), aicare("f6b1")),
        )
        private val NOTIFY_CHARS = FAMILIES.map { it.second }.toSet()

        /** Records older than this mean the scale's clock was never set. */
        private const val EARLIEST_PLAUSIBLE_EPOCH = 1_577_836_800L // 2020-01-01T00:00:00Z

        private const val MAX_PLAUSIBLE_DECI_KG = 1800 // the scale's range ends at 180 kg

        /** A stored record within this many seconds of a published weigh-in with the same weight is that weigh-in. */
        private const val SAME_WEIGH_IN_SECONDS = 2L

        /** True if [epochSeconds] can be a real weigh-in time on a scale whose clock is set. */
        fun isPlausibleTimestamp(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
            epochSeconds >= EARLIEST_PLAUSIBLE_EPOCH && epochSeconds * 1000 <= nowMillis + 24 * 3600 * 1000L

        /** Wire unit code for the user's display unit (codes 1/2 per the vendor-app notes). */
        fun unitCode(unit: WeightUnit): Int = when (unit) {
            WeightUnit.LB -> EufyT9140Lib.UNIT_LB
            WeightUnit.ST -> EufyT9140Lib.UNIT_ST
            else -> EufyT9140Lib.UNIT_KG
        }
    }

    // Per-weigh-in state.
    private var stableDeciKg = 0
    private var impedanceOhm = 0
    private var measurementEpoch = 0L
    private var published = false

    /** Weigh-ins published in this connection, for skipping repeats. */
    private val publishedThisSession = mutableListOf<EufyT9140Lib.Record>()

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
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetWeighIn()
        publishedThisSession.clear()

        val (service, notify, write) =
            FAMILIES.firstOrNull { hasCharacteristic(it.first, it.second) } ?: FAMILIES.first()
        logD("using service $service")

        setNotifyOn(service, notify)
        writeTo(service, write, EufyT9140Lib.timeSync(System.currentTimeMillis() / 1000), withResponse = false)
        writeTo(service, write, EufyT9140Lib.setUnit(unitCode(user.scaleUnit)), withResponse = false)
        writeTo(service, write, EufyT9140Lib.historyRequest(), withResponse = false)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic !in NOTIFY_CHARS) return
        for (frame in EufyT9140Lib.parseNotification(data)) handleFrame(frame, user)
    }

    override fun onDisconnected() {
        publishLatched(currentAppUser())
    }

    private fun handleFrame(frame: Frame, user: ScaleUser) {
        when (frame) {
            // A new weigh-in starts with live frames (all firmware) or the preamble (2026
            // firmware; also for a result delivered right after connecting, which has no live
            // frames). Stable frames are repeated inside later notifications, so they do not reset.
            is Frame.Weight -> {
                if (frame.deciKg <= 0 || frame.deciKg > MAX_PLAUSIBLE_DECI_KG) return
                if (!frame.stable) {
                    if (published) resetWeighIn()
                } else if (!published) {
                    stableDeciKg = frame.deciKg
                }
            }
            is Frame.Preamble -> {
                logD("result preamble 0x%02X".format(frame.code))
                if (published) resetWeighIn()
            }
            is Frame.Impedance -> if (!published) impedanceOhm = frame.ohm
            is Frame.MeasurementTime -> if (!published) measurementEpoch = frame.epochSeconds
            is Frame.FinalRecord -> publishLive(frame.record, user)
            is Frame.History -> frame.records.forEach { publishStored(it, user) }
            is Frame.Status -> when (frame.code) {
                EufyT9140Lib.STATUS_MEASUREMENT_FINISHED,
                EufyT9140Lib.STATUS_POWER_OFF -> publishLatched(user)
                EufyT9140Lib.STATUS_TIME_SYNC_FAILED -> logW("scale rejected the time sync")
                else -> logD("status 0x%02X".format(frame.code))
            }
            Frame.ImpedanceStarted -> logD("impedance measurement started")
            is Frame.Unknown -> logD("ignored frame (${frame.reason}): ${frame.bytes.toHexPreview(20)}")
        }
    }

    private fun resetWeighIn() {
        stableDeciKg = 0
        impedanceOhm = 0
        measurementEpoch = 0L
        published = false
    }

    /** Publishes the streamed stable weight + impedance once the scale reports the weigh-in done. */
    private fun publishLatched(user: ScaleUser) {
        if (published || stableDeciKg <= 0) return
        publishLive(EufyT9140Lib.Record(measurementEpoch, stableDeciKg, impedanceOhm), user)
    }

    private fun publishLive(record: EufyT9140Lib.Record, user: ScaleUser) {
        // The #772 firmware follows "measurement finished" with a final record of the same
        // weigh-in; it was already published from the streamed values.
        if (published) return
        if (!isPlausibleWeight(record)) return
        published = true
        publishRecord(record, user, if (isPlausibleTimestamp(record.epochSeconds)) Date(record.epochSeconds * 1000) else Date())
    }

    private fun publishStored(record: EufyT9140Lib.Record, user: ScaleUser) {
        if (!isPlausibleWeight(record)) return
        if (!isPlausibleTimestamp(record.epochSeconds)) {
            logW("dropping stored record with unset clock $record")
            return
        }
        if (publishedThisSession.any { isSameWeighIn(it, record) }) {
            logD("stored record already published live: $record")
            return
        }
        publishRecord(record, user, Date(record.epochSeconds * 1000))
    }

    private fun isSameWeighIn(a: EufyT9140Lib.Record, b: EufyT9140Lib.Record) =
        a.weightDeciKg == b.weightDeciKg && kotlin.math.abs(a.epochSeconds - b.epochSeconds) <= SAME_WEIGH_IN_SECONDS

    private fun isPlausibleWeight(record: EufyT9140Lib.Record): Boolean {
        if (record.weightDeciKg in 1..MAX_PLAUSIBLE_DECI_KG) return true
        logW("dropping record with implausible weight $record")
        return false
    }

    private fun publishRecord(record: EufyT9140Lib.Record, user: ScaleUser, date: Date) {
        publishedThisSession += record
        logI("publishing weigh-in $record at $date")
        publish(buildMeasurement(record, user, date))
    }

    private fun buildMeasurement(record: EufyT9140Lib.Record, user: ScaleUser, date: Date): ScaleMeasurement {
        val weightKg = record.weightKg
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = date
            this[MeasurementType.WEIGHT] = Kg(weightKg)
        }
        if (record.impedanceOhm <= 0) return m // no bioimpedance run, e.g. weighed in socks
        m[MeasurementType.IMPEDANCE] = Ohm(record.impedanceOhm.toFloat())
        // Height and age are inputs to the formula; ScaleUser defaults height to -1.
        if (user.bodyHeight > 0f && user.age > 0) {
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
        }
        return m
    }
}
