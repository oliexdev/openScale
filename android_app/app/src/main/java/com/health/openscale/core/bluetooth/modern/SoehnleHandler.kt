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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.SoehnleLib
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.utils.ConverterUtils
import com.welie.blessed.BluetoothBytesParser
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID

/**
 * SoehnleHandler
 * --------------------
 * Modern Kotlin handler for Soehnle smart scales using the custom service
 * 352e3000-28e9-40b8-a361-6db4cca4147c in combination with standard
 * Battery (0x180F), Current Time (0x1805) and User Data (0x181C) services.
 *
 * The device supports per-scale user indices (1..7). We keep a mapping
 * between *app userId* and *scale user index* using [ScaleDeviceHandler]'s
 * built-in DriverSettings helpers (persisted per device address).
 *
 * Flow on connect:
 *  1) (Optional) Factory reset if we have no known mappings at all.
 *  2) Subscribe Battery and read initial level.
 *  3) Write Current Time.
 *  4) Subscribe User Control Point (UDS).
 *  5) Create/select user on the scale via UCP.
 *  6) Write age, gender, height.
 *  7) Subscribe custom measurement notifications (A/B).
 *  8) Request history for indices 1..7.
 */
class SoehnleHandler : ScaleDeviceHandler() {

    override fun supportFor(device: com.health.openscale.core.service.ScannedDeviceInfo): DeviceSupport? {
        val name = device.name ?: return null
        val supported = name.startsWith("Shape200") || name.startsWith("Shape100") ||
                name.startsWith("Shape50") || name.startsWith("Style100")
        return if (supported) {
            DeviceSupport(
                displayName = "Soehnle Scale",
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.BATTERY_LEVEL
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.BATTERY_LEVEL
                ),
                bleTuning = BleTuningProfile.Balanced.asTuning(),
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // --- UUIDs ---------------------------------------------------------------

    // Standard services/characteristics
    private val SVC_BATTERY = uuid16(0x180F)
    private val CHR_BATTERY_LEVEL = uuid16(0x2A19)

    private val SVC_CURRENT_TIME = uuid16(0x1805)
    private val CHR_CURRENT_TIME = uuid16(0x2A2B)

    private val SVC_USER_DATA = uuid16(0x181C)
    private val CHR_USER_CONTROL_POINT = uuid16(0x2A9F)
    private val CHR_USER_AGE = uuid16(0x2A80)
    private val CHR_USER_GENDER = uuid16(0x2A8C)
    private val CHR_USER_HEIGHT = uuid16(0x2A8E)

    // Soehnle custom service
    private val SVC_SOEHNLE = UUID.fromString("352e3000-28e9-40b8-a361-6db4cca4147c")
    private val CHR_SOEHNLE_A = UUID.fromString("352e3001-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_B = UUID.fromString("352e3004-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_CMD = UUID.fromString("352e3002-28e9-40b8-a361-6db4cca4147c") // write

    // --- Lifecycle -----------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // (0) Optional: Factory reset if we have no known mappings at all
        val anyMapped = (1..7).any { loadUserIdForScaleIndex(it) != -1 }
        if (!anyMapped) {
            factoryReset()
        }

        // (1) Battery: subscribe + read once
        setNotifyOn(SVC_BATTERY, CHR_BATTERY_LEVEL)
        readFrom(SVC_BATTERY, CHR_BATTERY_LEVEL)

        // (2) Write current time using BluetoothBytesParser (CTS)
        val parser = BluetoothBytesParser()
        parser.setCurrentTime(Calendar.getInstance())
        writeTo(SVC_CURRENT_TIME, CHR_CURRENT_TIME, parser.value, withResponse = true)

        // (3) Subscribe to UDS User Control Point for create/select responses
        setNotifyOn(SVC_USER_DATA, CHR_USER_CONTROL_POINT)

        // (4) Ensure user exists on scale
        val appUserId = user.id
        val scaleIndex = loadScaleIndexForAppUser(appUserId)
        if (scaleIndex == -1) {
            // Create new scale user
            // Payload per legacy: [0x01, 0x00, 0x00]
            writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, byteArrayOf(0x01, 0x00, 0x00), withResponse = true)
        } else {
            // Select existing scale user
            writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, byteArrayOf(0x02, scaleIndex.toByte(), 0x00, 0x00), withResponse = true)
        }

        // (5-7) Push profile fields
        writeTo(SVC_USER_DATA, CHR_USER_AGE, byteArrayOf(user.age.toByte()), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_GENDER, byteArrayOf(if (user.gender.isMale()) 0x00 else 0x01), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_HEIGHT, ConverterUtils.toInt16Le(user.bodyHeight.toInt()), withResponse = true)

        // (8) Subscribe to custom A/B notifications
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_A)
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_B)

        // (9) Request history for indices 1..7
        for (i in 1..7) {
            writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x09, i.toByte()), withResponse = true)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return
        when (characteristic) {
            CHR_SOEHNLE_A -> handleSoehnleA(data)
            CHR_USER_CONTROL_POINT -> handleUserControlPoint(data, user)
            CHR_BATTERY_LEVEL -> handleBattery(data)
            else -> Unit
        }
    }

    // --- Handlers -------------------------------------------------------------

    private fun handleBattery(value: ByteArray) {
        val level = (value.first().toInt() and 0xFF)
        if (level <= 10) {
            userWarn(R.string.bluetooth_scale_warning_low_battery, level)
        }
    }

    private fun handleUserControlPoint(value: ByteArray, user: ScaleUser) {
        if (value.isEmpty() || value[0] != 0x20.toByte()) return
        val cmd = value.getOrNull(1)?.toInt() ?: return
        when (cmd) {
            0x01 -> { // user create
                val success = value.getOrNull(2) ?: return
                val idx = value.getOrNull(3)?.toInt() ?: return
                if (success == 0x01.toByte()) {
                    saveScaleIndexForAppUser(user.id, idx)
                    userInfo(R.string.bluetooth_scale_info_step_on_for_reference, 0)
                } else {
                    logE("Soehnle: error creating user")
                }
            }
            0x02 -> { // user select
                val success = value.getOrNull(2) ?: return
                if (success != 0x01.toByte()) {
                    logE("Soehnle: error selecting user; attempting create")
                    // Try to create instead
                    writeTo(SVC_USER_DATA, CHR_USER_CONTROL_POINT, byteArrayOf(0x01, 0x00, 0x00), withResponse = true)
                }
            }
        }
    }

    private fun handleSoehnleA(value: ByteArray) {
        // Only handle 0x09 frames of length 15
        if (value.size != 15 || value[0] != 0x09.toByte()) return

        val weightKg = ConverterUtils.fromUnsignedInt16Be(value, 9) / 10.0f
        val soehnleUserIndex = (value[1].toInt() and 0xFF)
        val year = ConverterUtils.fromUnsignedInt16Be(value, 2)
        val month = (value[4].toInt() and 0xFF)
        val day = (value[5].toInt() and 0xFF)
        val hour = (value[6].toInt() and 0xFF)
        val minute = (value[7].toInt() and 0xFF)
        val second = (value[8].toInt() and 0xFF)

        val imp5 = ConverterUtils.fromUnsignedInt16Be(value, 11)
        val imp50 = ConverterUtils.fromUnsignedInt16Be(value, 13)

        val cal: Calendar = GregorianCalendar(TimeZone.getDefault()).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

        val openScaleUserId = loadUserIdForScaleIndex(soehnleUserIndex)
        if (openScaleUserId == -1) {
            logE("Unknown Soehnle user index $soehnleUserIndex")
            return
        }

        // We need user's profile for composition calcs → try to use the currently selected user
        // (This is usually the same as 'openScaleUserId')
        val u = try { requireUser() } catch (_: Throwable) { null }
        val activity = mapActivityLevel(u)
        val isMale = u?.gender?.isMale() ?: true
        val age = u?.age ?: 30
        val height = u?.bodyHeight ?: 175f

        val lib = SoehnleLib(isMale, age, height, activity)

        val m = ScaleMeasurement().apply {
            userId = openScaleUserId
            weight = weightKg
            dateTime = cal.time
            water = lib.getWater(weightKg, imp50.toFloat())
            fat = lib.getFat(weightKg, imp50.toFloat())
            muscle = lib.getMuscle(weightKg, imp50.toFloat(), imp5.toFloat())
        }
        publish(m)
    }

    // --- Helpers --------------------------------------------------------------

    private fun factoryReset() {
        logD("Soehnle: factory reset + clear mappings")
        writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x0B, 0xFF.toByte()), withResponse = true)
        for (i in 1..7) saveUserIdForScaleIndex(i, -1)
    }

    private fun mapActivityLevel(user: ScaleUser?): Int = when (user?.activityLevel) {
        ActivityLevel.SEDENTARY -> 0
        ActivityLevel.MILD -> 1
        ActivityLevel.MODERATE -> 2
        ActivityLevel.HEAVY -> 4
        ActivityLevel.EXTREME -> 5
        else -> 0
    }
}
