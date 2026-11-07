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

import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Locale

/**
 * "SWAN" white-label scale: MGB 8-byte streaming framing on 0xFFB0/0xFFB2, but its
 * weight field is in 0.1 kg units instead of the 0.01 kg units [MGBHandler] assumes.
 *
 * Claiming requires the advertised name plus manufacturer data under company id
 * 0x02AC: the advertisement lists only service 0xFFB0, so services cannot tell this
 * variant apart from other vendors' centi-kg MGB scales.
 */
class SwanScaleHandler : MGBHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.lowercase(Locale.ROOT).startsWith("swan")) return null

        val manufacturerData = device.manufacturerData ?: return null
        if (manufacturerData.indexOfKey(SWAN_COMPANY_ID) < 0) return null

        val caps = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.UNIT_CONFIG
        )
        return DeviceSupport(
            displayName = "SWAN",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override val streamingWeightDivisor: Float
        get() = 10f

    companion object {
        private const val SWAN_COMPANY_ID = 0x02AC
    }
}
