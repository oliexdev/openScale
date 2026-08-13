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
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.welie.blessed.BluetoothBytesParser
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Handler for Beurer BF450.
 *
 */
class BeurerBF450Handler : StandardWeightProfileHandler() {

    // UUID helper
    private val SVC   = uuid16(0xFFFF)

    // characteristic 
    private val CHR_USER_LIST         = uuid16(0xFFF1)
    private val CHR_ACTIVITY          = uuid16(0xFFF2)
    private val CHR_TAKE_MEASUREMENT  = uuid16(0xFFF4)
    private val CHR_LIVE_WEIGHT       = uuid16(0xFFF0)
    private val CHR_PROPRIETARY_BC    = uuid16(0xFFF5)
    private val CHR_BF450_EXTRA       = uuid16(0xFFF6)
    private val CHR_ACK               = uuid16(0xFFF7)

    // characteristic GATT Standard
    private val CHR_BCS_BODY_COMP     = UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb")
    private val CHR_WSP_WEIGHT        = UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb")

    // state 
    private val scaleUserList = mutableListOf<ScaleUser>()
    private var pendingBodyComposition = false

    private var lastFatPct    = 0f
    private var lastMusclePct = 0f
    private var lastMuscleMass = 0f
    private var lastBodyWater  = 0f
    private var lastImpedance  = 0.0

    // DeviceSupport 
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()
        if ("bf450" !in name) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.UNIT_CONFIG
        )
        val impl = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.HISTORY_READ,
        )
        return DeviceSupport(
            displayName = "Beurer BF450",
            capabilities = caps,
            implemented = impl,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // Connection
    override fun onConnected(user: ScaleUser) {
        // super drives the user mapping/consent: auto-consent if a mapping exists,
        // otherwise the standard UDS listing (which the BF450 ignores — we use FFF1).
        super.onConnected(user)
        logD("BF450 connected: userId=${user.id}, name=${user.userName}")

        val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1

        // Only enumerate the scale's stored users (over the proprietary FFF1 list)
        // when we don't yet have a consent mapping. Discovery only — the actual
        // mapping/consent is negotiated by the base UDS flow, never written here.
        if (loadConsentForScaleIndex(scaleIndex) == -1) {
            logD("No consent found, requesting FFF1 user list")
            scaleUserList.clear()
            setNotifyOn(SVC, CHR_USER_LIST)
            writeTo(SVC, CHR_USER_LIST, byteArrayOf(0x00))
        } else {
            logD("Consent valid, proceeding")
        }

        setNotifyOn(SVC, CHR_LIVE_WEIGHT)
        setNotifyOn(SVC, CHR_TAKE_MEASUREMENT)
        setNotifyOn(SVC, CHR_BF450_EXTRA)
        setNotifyOn(SVC, CHR_PROPRIETARY_BC)
        setNotifyOn(SVC, CHR_ACK)
    }

    // Write user data
    override fun writeUserDataToScale() {
        super.writeUserDataToScale()   
        val user = currentAppUser()
        logD("Additional user data BF450 for userId=${user.id}")
        writeActivityLevel(user)
    }

    override fun onRequestMeasurement() {
        logD("Start Measurement: sent 0x00 at CHR_TAKE_MEASUREMENT")
        writeTo(SVC, CHR_TAKE_MEASUREMENT, byteArrayOf(0x00))
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_USER_LIST -> handleUserList(data, user)
            CHR_LIVE_WEIGHT -> handleLiveWeight(data, user)
            CHR_TAKE_MEASUREMENT -> handleMeasurementStatus(data)
            CHR_BF450_EXTRA -> handleBF450Extra(data)
            CHR_PROPRIETARY_BC -> logD("CHR_PROPRIETARY_BC (FFF5): ${data.toHexString()} [raw, not decoded]")
            CHR_ACK -> logD("CHR_ACK (FFF7): ${data.toHexString()}")
            
            CHR_BCS_BODY_COMP -> {
                handleStandardBodyComposition(data)
                super.onNotification(characteristic, data, user)
            }
            else -> super.onNotification(characteristic, data, user)
        }
    }

    private fun handleUserList(data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) {
            logW("FFF1: empty packet, ignored")
            return
        }

        val parser = BluetoothBytesParser(data)
        when (val status = parser.getUInt8().toInt()) {
            0x02 -> {
                // Empty scale: no stored users to choose from. Offer "create new user"
                // and let the base UDS flow register it — REGISTER_NEW_USER returns the
                // real scale slot and the real consent code. Do NOT pre-write a guessed
                // slot/consent here: a fake consent (e.g. 1) overwrites the real one and
                // causes USER_NOT_AUTHORIZED ("U ??") on the next reconnect.
                logD("FFF1: empty scale, offering create-new-user")
                presentCreateOnlyChoice()
            }

            0x01 -> {
                logD("FFF1: user list: (${scaleUserList.size} users)")
                val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1
                if (loadConsentForScaleIndex(scaleIndex) == -1) {
                    presentChooseFromUsers(scaleUserList)
                }
            }

            0x00 -> {
                if (data.size < 12) {
                    logW("FFF1: entry user too short (${data.size} byte)")
                    return
                }

                val index = parser.getUInt8().toInt()
                val rawInitials = ByteArray(3) { parser.getUInt8().toByte() }
                val initials = if (rawInitials.all { it == 0xFF.toByte() }) {
                    "unknown"
                } else {
                    String(rawInitials, Charsets.US_ASCII)
                        .filter { it.isLetterOrDigit() }
                        .take(3)
                        .ifEmpty { "unknown" }
                }

                parser.offset = 5
                val year     = parser.getUInt16().toInt()
                val month    = parser.getUInt8().toInt()
                val day      = parser.getUInt8().toInt()
                val height   = parser.getUInt8().toInt()
                val gender   = parser.getUInt8().toInt()
                val activity = parser.getUInt8().toInt()

                val calendar = GregorianCalendar(year, month - 1, day)
                val scaleUser = ScaleUser().apply {
                    this.id           = index
                    this.userName     = initials
                    this.birthday     = calendar.time
                    this.bodyHeight   = height.toFloat()
                    this.gender       = if (gender == 0) GenderType.MALE else GenderType.FEMALE
                    this.activityLevel = ActivityLevel.fromInt(activity - 1)
                }
                scaleUserList.add(scaleUser)
                logD("FFF1: added user – $scaleUser")
            }
            else -> logW("FFF1: unknown 0x${status.toString(16)}, payload=${data.toHexString()}")
        }
    }

    // Live weight streaming (FFF0)
    private fun handleLiveWeight(data: ByteArray, user: ScaleUser) {
        if (data.size < 5) return
        val statusByte = data[1].toInt() and 0xFF
        val rawWeight  = data[3].toInt() and 0xFF
        val liveKg     = rawWeight * 0.5f
        logD("FFF0 Live: status=0x${statusByte.toString(16)} weight_live≈${liveKg}kg raw=${rawWeight}")
        
        val dummyGattPayload = ByteArray(10)
        dummyGattPayload[0] = 0x00 // Flags: instabile, unità in kg
        val rawWeightU16 = (liveKg / 0.005f).toInt()
        dummyGattPayload[1] = (rawWeightU16 and 0xFF).toByte()
        dummyGattPayload[2] = ((rawWeightU16 shr 8) and 0xFF).toByte()

        super.onNotification(CHR_WSP_WEIGHT, dummyGattPayload, user)
    }

    private fun handleMeasurementStatus(data: ByteArray) {
        if (data.isEmpty()) return
        when (val s = data[0].toInt() and 0xFF) {
            0x00 -> logD("FFF4: Taking measurement…")
            0x01 -> logD("FFF4: Measurement taken")
            else -> logD("FFF4: status unknown 0x${s.toString(16)}")
        }
    }

    // extra BF450 (FFF6)
    private fun handleBF450Extra(data: ByteArray) {
        if (data.size < 16) {
            logW("FFF6: payload too short (${data.size} byte), ignored")
            return
        }
        val impedance = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
        val userId    = data[9].toInt() and 0xFF
        lastImpedance = impedance.toDouble()
        logD("FFF6: impedance=${impedance}Ω user_id_scale=${userId}")
    }

    // Gatt weight measurement (0x2A9C) 
    private fun handleStandardBodyComposition(data: ByteArray) {
        if (data.size < 14) {
            logW("2A9C: payload too short (${data.size} byte)")
            return
        }

        val flags      = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val fatPctRaw  = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val muscPctRaw = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
        val muscMassRaw = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
        val waterRaw   = (data[10].toInt() and 0xFF) or ((data[11].toInt() and 0xFF) shl 8)

        if (fatPctRaw == 0) {
            logD("2A9C: measure only weight (no foot contact)")
            pendingBodyComposition = false
            return
        }

        lastFatPct     = fatPctRaw    * 0.1f
        lastMusclePct  = muscPctRaw   * 0.1f
        lastMuscleMass = muscMassRaw  * 0.1f
        lastBodyWater  = waterRaw     * 0.1f
        pendingBodyComposition = true

        logD("2A9C: flags=0x${flags.toString(16)} fat=%.1f%% muscPct=%.1f%% muscMass=%.1fkg water=%.1fkg (impd=%.1fΩ)".format(lastFatPct, lastMusclePct, lastMuscleMass, lastBodyWater, lastImpedance))
    }


    override fun transformBeforePublish(m: ScaleMeasurement): ScaleMeasurement {
        val transformed = super.transformBeforePublish(m)

        if (pendingBodyComposition) {
            transformed.fat        = lastFatPct
            transformed.muscle     = lastMusclePct
            transformed.water      = (lastBodyWater / (transformed.weight.takeIf {it > 0f} ?: 1f) * 100f)
            if (lastImpedance > 0.0) {
                transformed.impedance = lastImpedance
            }

            logD("Added values to measurement in BF450: fat=%.1f%% musc=%.1f%% water=%.1f%% impd=%.1fΩ".format(transformed.fat, transformed.muscle, transformed.water, transformed.impedance))
            pendingBodyComposition = false
        }

        return transformed
    }

    private fun writeActivityLevel(user: ScaleUser) {
        val lvl = (user.activityLevel.toInt() + 1).coerceIn(1, 5)
        logD("Write activity lv: lvl=$lvl per userId=${user.id}")
        writeTo(SVC, CHR_ACTIVITY, byteArrayOf(lvl.toByte()))
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
