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

import android.R.attr.data
import android.R.attr.vendor
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Handler for RENPHO ES-WBE28 (GATT; mix of standard and vendor-specific behavior).
 *
 * Port of the legacy `BluetoothRenphoScale` to the modern handler architecture.
 * - Enables NOTIFY/INDICATE on several characteristics
 * - Writes current time and user profile data
 * - Sends vendor "magic" sequences to kick the device into measurement mode
 * - Parses weight from a proprietary encoding published on 0x2A9D
 *
 * Notes:
 * - Body Composition measurement (0x2A9C) is device-specific here and left as TODO.
 * - The sequencing below relies on the adapter's I/O pacing; no explicit state machine needed.
 */
class RenphoHandler : ScaleDeviceHandler() {

    companion object { private const val TAG = "RenphoHandler" }

    // --- Services (16-bit base UUIDs) -----------------------------------------

    private val SERV_BODY_COMP     = uuid16(0x181B)
    private val SERV_USER_DATA     = uuid16(0x181C)
    private val SERV_WEIGHT_SCALE  = uuid16(0x181D)
    private val SERV_CUR_TIME      = uuid16(0x1805)

    // --- Characteristics (incl. vendor/custom) --------------------------------

    // Custom #0 under Body Composition service
    private val CHAR_CUSTOM0_NOTIFY = uuid16(0xFFE1)     // notify
    private val CHAR_CUSTOM0        = uuid16(0xFFE2)     // write

    // Custom #1 under User Data service (actually UCP 0x2A9F used as both write/indicate)
    private val CHAR_CUSTOM1_NOTIFY = uuid16(0x2A9F)     // indicate
    private val CHAR_CUSTOM1        = uuid16(0x2A9F)     // write

    // Body Composition service (standard UUIDs, but payload is vendor-specific on this device)
    private val CHAR_BODY_COMP_FEAT = uuid16(0x2A9B)     // read
    private val CHAR_BODY_COMP_MEAS = uuid16(0x2A9C)     // indicate

    // User Data (standard)
    private val CHAR_GENDER         = uuid16(0x2A8C)     // 0x00 male, 0x01 female
    private val CHAR_HEIGHT         = uuid16(0x2A8E)     // uint16 (cm, LE)
    private val CHAR_BIRTH          = uuid16(0x2A85)     // Year(2 LE), Month(1), Day(1)
    private val CHAR_AGE            = uuid16(0x2A80)     // uint8
    private val CHAR_ATHLETE        = uuid16(0x2AFF)     // {0x0D,0x00}=athlete, {0x03,0x00}=non-athlete

    // Weight Scale (standard UUID but proprietary encoding on this device)
    private val CHAR_WEIGHT         = uuid16(0x2A9D)     // notify

    // Current Time service
    private val CHAR_CUR_TIME       = uuid16(0x2A2B)     // write + (notify on some stacks)
    private val CHAR_ICCEDK         = uuid16(0xFFF1)     // vendor notify (service unknown; used as in legacy)

    // --- "Magic" payloads observed on the legacy driver -----------------------

    private val MAGIC0 = byteArrayOf(0x10, 0x01, 0x00, 0x11) // write to FFE2
    private val MAGIC1 = byteArrayOf(0x03, 0x00, 0x01, 0x04) // write to FFE2

    // UCP magic (looked like "consent") – legacy always sent [0x02, 0xAA, 0x0F, 0x27]
    // 0x02 = UDS_CP_CONSENT, index 0xAA, consent 0x270F (9999).
    private val MAGIC_UCP = byteArrayOf(0x02, 0xAA.toByte(), 0x0F, 0x27)

    // -------------------------------------------------------------------------

    /**
     * Recognize Renpho by name or by a mix of advertised services.
     * We keep this moderately strict to avoid grabbing unrelated scales.
     */
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val nameLc = device.name.lowercase()
        val svc = device.serviceUuids.toSet()

        val hasQN = svc.contains(uuid16(0xFFE0)) || svc.contains(uuid16(0xFFF0))
        val looksRenphoByName = nameLc.contains("renpho-scale")

        // Never claim Renpho when QN services are present
        if (hasQN) return null
        if (!looksRenphoByName) return null

        val caps = setOf(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BODY_COMPOSITION
        )
        val impl = setOf(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC
        )
        return DeviceSupport(
            displayName = "RENPHO ES-WBE28",
            capabilities = caps,
            implemented = impl,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    /**
     * After services are discovered, enable notifications and perform the vendor handshake
     * followed by writing user data.
     */
    override fun onConnected(user: ScaleUser) {
        // Notifications / indications first (best practice)
        setNotifyOn(SERV_CUR_TIME, CHAR_CUR_TIME)
        setNotifyOn(SERV_USER_DATA, CHAR_CUSTOM1_NOTIFY)     // UCP indication
        setNotifyOn(SERV_CUR_TIME, CHAR_ICCEDK)              // vendor notify (service unknown; legacy used 0x1805)
        setNotifyOn(SERV_BODY_COMP, CHAR_CUSTOM0_NOTIFY)     // vendor notify

        // Sync time (Current Time characteristic format, LE year first)
        writeTo(SERV_CUR_TIME, CHAR_CUR_TIME, buildCurrentTimePayload())

        // Vendor handshakes
        writeTo(SERV_BODY_COMP, CHAR_CUSTOM0, MAGIC0)
        writeTo(SERV_BODY_COMP, CHAR_CUSTOM0, MAGIC1)
        writeTo(SERV_USER_DATA, CHAR_CUSTOM1, MAGIC_UCP)

        // Write user data (best-effort; device appears to accept these anytime)
        writeUserProfile(user)

        // Optional: read feature flags
        readFrom(SERV_BODY_COMP, CHAR_BODY_COMP_FEAT)

        // Subscribe to measurements
        setNotifyOn(SERV_WEIGHT_SCALE, CHAR_WEIGHT)          // weight → proprietary format
        setNotifyOn(SERV_BODY_COMP, CHAR_BODY_COMP_MEAS)     // body comp → vendor specific (TODO)

        userInfo(R.string.bt_info_step_on_scale)
    }

    /**
     * Dispatch incoming notifications.
     * We only parse weight here, body composition is device-specific and left as TODO.
     */
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHAR_WEIGHT -> handleWeightNotification(data, user)
            CHAR_BODY_COMP_MEAS -> {
                // TODO: parse RENPHO vendor body composition (non-standard payload)
                LogManager.d(TAG, "BodyComp indication (vendor-specific) len=${data.size}")
            }
            CHAR_CUR_TIME, CHAR_ICCEDK, CHAR_CUSTOM0_NOTIFY, CHAR_CUSTOM1_NOTIFY -> {
                // No special handling needed; these are just part of the handshake/noise.
                LogManager.d(TAG, "Notify from ${characteristic} len=${data.size}")
            }
            CHAR_BODY_COMP_FEAT -> {
                LogManager.d(TAG, "read body comp feat len=${data.size}")
            }
            else -> LogManager.d(TAG, "Unhandled notify chr=$characteristic len=${data.size}")
        }
    }

    // --- Parsing ---------------------------------------------------------------

    /**
     * Legacy found weight on 0x2A9D in a proprietary layout:
     * - data[0] must be 0x2E to indicate a valid frame
     * - weight_kg = ((data[2] << 8) | data[1]) / 20.0f
     */
    private fun handleWeightNotification(value: ByteArray, user: ScaleUser) {
        if (value.size < 3) return
        if (value[0] != 0x2E.toByte()) return

        val raw = (value[2].toInt() and 0xFF shl 8) or (value[1].toInt() and 0xFF)
        val weightKg = raw / 20.0f

        LogManager.d(TAG, "RENPHO weight frame → raw=$raw, kg=$weightKg")

        val m = ScaleMeasurement().apply { this.weight = weightKg }
        publish(m)
    }

    // --- Helpers ---------------------------------------------------------------

    /**
     * Build a Current Time payload:
     * Year(LSB,MSB), Month(1..12), Day(1..31), Hour, Minute, Second,
     * DayOfWeek(1=Mon..7=Sun), Fractions(0), AdjustReason(0)
     */
    private fun buildCurrentTimePayload(now: Date = Date()): ByteArray {
        val cal = Calendar.getInstance().apply { time = now }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

        return byteArrayOf(
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            month.toByte(),
            day.toByte(),
            hour.toByte(),
            minute.toByte(),
            second.toByte(),
            dow.toByte(),
            0x00,  // Fractions256
            0x00   // AdjustReason
        )
    }

    /**
     * Write user profile fields using standard UDS characteristics the device accepts:
     * - Gender (0 male, 1 female)
     * - Height (cm, uint16 LE)
     * - Date of Birth (Year(LE), Month, Day)
     * - Age (uint8)
     * - Athlete flag ({0x0D,0x00} athlete; {0x03,0x00} non-athlete)
     */
    private fun writeUserProfile(u: ScaleUser) {
        // Gender
        val gender = if (u.gender.isMale()) 0 else 1
        writeTo(SERV_USER_DATA, CHAR_GENDER, byteArrayOf(gender.toByte()))

        // Height: assume bodyHeight already in cm (legacy converted using measure unit)
        val heightCm = u.bodyHeight.toInt().coerceIn(0, 300)
        writeTo(
            SERV_USER_DATA, CHAR_HEIGHT,
            byteArrayOf((heightCm and 0xFF).toByte(), ((heightCm shr 8) and 0xFF).toByte())
        )

        // Date of birth
        val cal = Calendar.getInstance().apply { time = u.birthday }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        writeTo(
            SERV_USER_DATA, CHAR_BIRTH,
            byteArrayOf(
                (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
                month.toByte(),
                day.toByte()
            )
        )

        // Age
        val age = u.age.coerceIn(0, 120)
        writeTo(SERV_USER_DATA, CHAR_AGE, byteArrayOf(age.toByte()))

        // Athlete mapping (legacy: HEAVY/EXTREME → athlete)
        val athlete = when (u.activityLevel.toInt()) {
            // ActivityLevel enum mapping is app-specific; typical: 0..n
            // Use a conservative mapping:
            3, 4 -> byteArrayOf(0x0D, 0x00) // heavy, extreme
            else -> byteArrayOf(0x03, 0x00) // non-athlete
        }
        writeTo(SERV_USER_DATA, CHAR_ATHLETE, athlete)
    }
}
