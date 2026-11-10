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
 * Protocol from bdr99/eufylife-ble-client (MIT, © bdr99) and particleflux's 2021
 * reverse-engineering notes; see [EufyT9140Lib] for the wire format and credits.
 * NOTE: the GATT wiring (service/characteristic choice, write-without-response, command
 * pacing) has not been verified on hardware yet — the parser and command framing are
 * covered by EufyT9140LibTest.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.EufyT9140Lib
import com.health.openscale.core.bluetooth.libs.OneByoneLib
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
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
 * Eufy Smart Scale T9140.
 *
 * Advertises as `eufy T9140` and speaks the older Eufy vendor profile: service `4143f6b0-…`
 * (some firmware `4143f7b0-…`, fallback `0000fff0-…`), notifications on `…b2`, writes on
 * `…b1`. No pairing and no encryption, unlike the P2/P2 Pro — see [EufyT9140Lib].
 *
 * A weigh-in arrives as a settled `AC 02 <weight> <impedance> CA` frame. The scale also sends
 * intermediate `…CE` frames while the weight is still moving; those are logged but not saved,
 * because openScale stores one row per weigh-in.
 */
class EufyT9140Handler : ScaleDeviceHandler() {

    private val serviceModern: UUID = UUID.fromString("4143f7b0-5300-4900-4700-414943415245")
    private val notifyModern: UUID = UUID.fromString("4143f7b2-5300-4900-4700-414943415245")
    private val writeModern: UUID = UUID.fromString("4143f7b1-5300-4900-4700-414943415245")

    private val serviceBody: UUID = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")
    private val notifyBody: UUID = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private val writeBody: UUID = UUID.fromString("4143f6b1-5300-4900-4700-414943415245")

    private val serviceFf: UUID = uuid16(0xFFF0)
    private val notifyFf: UUID = uuid16(0xFFB2)
    private val writeFf: UUID = uuid16(0xFFB1)

    private val serviceBattery: UUID = uuid16(0x180F)
    private val charBattery: UUID = uuid16(0x2A19)

    private var service: UUID? = null
    private var notifyChar: UUID? = null
    private var writeChar: UUID? = null

    private var publishedThisSession = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        if (!name.contains("t9140") && !name.contains("bodysense")) return null

        val caps = buildSet {
            add(DeviceCapability.LIVE_WEIGHT_STREAM)
            add(DeviceCapability.BODY_COMPOSITION)
            add(DeviceCapability.TIME_SYNC)
            add(DeviceCapability.UNIT_CONFIG)
            add(DeviceCapability.HISTORY_READ)
        }
        return DeviceSupport(
            displayName = "Eufy Smart Scale (T9140)",
            capabilities = caps,
            // History read is understood (see EufyT9140Lib.historyRequestCommand) but the record
            // parser is not written yet; live weigh-ins are what this driver is for.
            implemented = caps - DeviceCapability.HISTORY_READ,
            tuningProfile = TuningProfile.Conservative, // Eufy firmware dislikes back-to-back writes
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        // Handler instances are reused for the lifetime of the app, so reset per-connection state.
        publishedThisSession = false
        service = null
        notifyChar = null
        writeChar = null

        val candidates = listOf(
            Triple(serviceBody, notifyBody, writeBody),
            Triple(serviceModern, notifyModern, writeModern),
            Triple(serviceFf, notifyFf, writeFf)
        )
        val found = candidates.firstOrNull { hasCharacteristic(it.first, it.second) }
            ?: candidates.firstOrNull { hasCharacteristic(it.first, it.third) }
        if (found == null) {
            logE("T9140: no known Eufy service/characteristic on this device — cannot subscribe")
            return
        }

        service = found.first
        notifyChar = found.second
        writeChar = found.third
        logI("T9140: service=${found.first} notify=${found.second} write=${found.third}")

        setNotifyOn(found.first, found.second)

        if (hasCharacteristic(serviceBattery, charBattery)) {
            setNotifyOn(serviceBattery, charBattery)
            readFrom(serviceBattery, charBattery)
        }

        // Configure the scale: unit, then clock. The characteristic is write-without-response,
        // and neither ACK is required for the weigh-in to arrive.
        writeTo(found.first, found.third, EufyT9140Lib.unitCommand(unitCode(user)), withResponse = false)
        writeTo(
            found.first,
            found.third,
            EufyT9140Lib.timeSyncCommand(System.currentTimeMillis() / 1000L),
            withResponse = false
        )

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic == charBattery) {
            val level = data.firstOrNull()?.toInt()?.and(0xFF) ?: return
            logD("T9140: battery $level%")
            if (level <= 10) userWarn(R.string.bluetooth_scale_warning_low_battery, level)
            return
        }

        // Raw dump on purpose: firmware variants put slightly different payloads in the
        // wrapper, and this line is what makes a user-submitted log diagnosable.
        logD("T9140 ← ${EufyT9140Lib.toHex(data)}")

        for (frame in EufyT9140Lib.splitFrames(data)) {
            val reading = EufyT9140Lib.parseReading(frame)
            if (reading == null) {
                logD("T9140: not a weigh-in frame (${EufyT9140Lib.toHex(frame)})")
                continue
            }
            if (!reading.isFinal) {
                logD("T9140: interim ${reading.weightKg} kg")
                continue
            }
            if (publishedThisSession) {
                logD("T9140: settled reading already saved this session, ignoring repeat")
                continue
            }
            publish(buildMeasurement(reading, user))
            publishedThisSession = true
        }
    }

    override fun onDisconnected() {
        publishedThisSession = false
    }

    private fun buildMeasurement(reading: EufyT9140Lib.Reading, user: ScaleUser): ScaleMeasurement {
        val m = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(reading.weightKg)
            // Stored even when body composition is skipped below, so it can be recomputed later.
            if (reading.impedanceOhm > 0) {
                this[MeasurementType.IMPEDANCE] = Ohm(reading.impedanceOhm.toFloat())
            }
        }

        if (reading.impedanceOhm <= 0) {
            logI("T9140: no impedance in frame — saving weight only (%.2f kg)".format(reading.weightKg))
            return m
        }
        if (user.bodyHeight <= 0f) {
            logI("T9140: user has no body height — saving weight and impedance only")
            return m
        }

        // Same BIA model the sibling Eufy models use (see OneByoneHandler).
        try {
            val lib = OneByoneLib(
                if (user.gender == GenderType.MALE) 1 else 0,
                user.age,
                user.bodyHeight,
                peopleType(user.activityLevel)
            )
            val fat = lib.getBodyFat(reading.weightKg, reading.impedanceOhm.toFloat())
            m[MeasurementType.BODY_FAT] = Percent(fat)
            m[MeasurementType.WATER] = Percent(lib.getWater(fat))
            m[MeasurementType.BONE] = Kg(lib.getBoneMass(reading.weightKg, reading.impedanceOhm.toFloat()))
            m[MeasurementType.VISCERAL_FAT] = lib.getVisceralFat(reading.weightKg)
            m[MeasurementType.MUSCLE] =
                Percent(lib.getMuscle(reading.weightKg, reading.impedanceOhm.toFloat()))
            m[MeasurementType.LBM] =
                Kg(lib.getLBM(reading.weightKg, m[MeasurementType.BODY_FAT]?.value ?: 0f))
        } catch (t: Throwable) {
            logW("T9140: body composition failed, saving weight and impedance: ${t.message}")
        }
        return m
    }

    private fun unitCode(user: ScaleUser): Int = when (user.scaleUnit) {
        WeightUnit.KG -> 0x00
        WeightUnit.LB -> 0x01
        WeightUnit.ST -> 0x02
    }

    /** SEDENTARY/MILD → 0, MODERATE → 1, HEAVY/EXTREME → 2 (matches the legacy mapping). */
    private fun peopleType(level: ActivityLevel): Int = when (level) {
        ActivityLevel.SEDENTARY -> 0
        ActivityLevel.MILD -> 0
        ActivityLevel.MODERATE -> 1
        ActivityLevel.HEAVY -> 2
        ActivityLevel.EXTREME -> 2
    }
}
