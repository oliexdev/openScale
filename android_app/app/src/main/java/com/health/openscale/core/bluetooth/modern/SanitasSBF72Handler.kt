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
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import java.util.UUID
import kotlin.math.round

/**
 * Sanitas SBF72 / BF915 / SBF73 handler built on top of StandardWeightProfileHandler.
 * Uses transformBeforePublish() to turn water mass (kg) into water percentage (%).
 * Also handles a few vendor-specific niceties via custom service 0xFFFF.
 */
class SanitasSBF72Handler(
    private val declaredName: String? = null
) : StandardWeightProfileHandler() {

    companion object {
        private const val TAG = "SanitasSBF72"
    }

    // Vendor custom service & characteristics (16-bit in Bluetooth Base UUID):
    private val SVC_CUSTOM: UUID = uuid16(0xFFFF)
    private val CHR_USER_LIST: UUID = uuid16(0x0001)       // notify/read + write
    private val CHR_ACTIVITY_LEVEL: UUID = uuid16(0x0004)  // write 1..5
    private val CHR_TAKE_MEASUREMENT: UUID = uuid16(0x0006)// write 0x00 to trigger
    // private val CHR_REFER_WEIGHT_BF: UUID = uuid16(0x000B) // not used here

    // ---- Support detection ----------------------------------------------------
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val n = device.name ?: return null
        val match = (n == "SBF72") || (n == "BF915") || (n == "SBF73")
        return if (match) {
            DeviceSupport(
                displayName = declaredName ?: n,
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.BATTERY_LEVEL,
                    DeviceCapability.UNIT_CONFIG
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.BATTERY_LEVEL
                ),
                bleTuning = BleTuningProfile.Balanced.asTuning(),
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // ---- Lifecycle ------------------------------------------------------------
    override fun onConnected(user: ScaleUser) {
        // Standard init (CTS, UDS, Battery, subscribe to standard chrs, etc.)
        super.onConnected(user)

        // Vendor: subscribe to USER_LIST (for list / pin responses)
        setNotifyOn(SVC_CUSTOM, CHR_USER_LIST)

        // Vendor: write activity level (app 0..4 → 1..5)
        val level = (user.activityLevel.toInt() + 1).coerceIn(1, 5)
        writeTo(SVC_CUSTOM, CHR_ACTIVITY_LEVEL, byteArrayOf(level.toByte()))

        // Optional: request the vendor user list (some devices respond)
        writeTo(SVC_CUSTOM, CHR_USER_LIST, byteArrayOf(0x00))

        userInfo(R.string.bt_info_step_on_scale)
    }

    // ---- UI feedback: show PIN on scale before consent entry -----------------
    override fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: android.os.Handler
    ) {
        if (interactionType == UserInteractionType.CHOOSE_USER) {
            val selectedIdx = (feedbackData as? Int)
            if (selectedIdx != null && selectedIdx >= 0) {
                // For user index N on scale → write (0x10 + N) to USER_LIST to show PIN
                val pinIndex = (0x10 + selectedIdx).coerceIn(0, 0xFF)
                writeTo(SVC_CUSTOM, CHR_USER_LIST, byteArrayOf(pinIndex.toByte()))
                LogManager.d(TAG, "Requested PIN display for slot $selectedIdx (idxByte=0x${pinIndex.toString(16)})")
            }
        }
        // Continue the normal flow (mapping + consent handling) in the base class
        super.onUserInteractionFeedback(interactionType, appUserId, feedbackData, uiHandler)
    }

    // ---- Transform hook: convert water (kg) → percentage (%) -----------------
    override fun transformBeforePublish(m: ScaleMeasurement): ScaleMeasurement {
        val weight = m.weight
        val water = m.water
        // Heuristics: only convert if weight is positive and water looks like mass (<= 200 kg)
        if (weight > 0f && water > 0f && water <= 200f) {
            val pct = round((water / weight) * 10000f) / 100f   // two decimals
            m.water = pct
        }
        return m
    }

    override fun onRequestMeasurement() {
        writeTo(SVC_CUSTOM, CHR_TAKE_MEASUREMENT, byteArrayOf(0x00))
    }
}
