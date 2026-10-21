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
 * Handler for the Samsung Smart Scale (GP-BELAD001BP0 / LEFU_SCALE CF376).
 *
 * Distributed as an official promotional accessory with Samsung Galaxy Watches.
 * The hardware is manufactured by Shenzhen Unique Scales (Lefu) and communicates
 * via standard Bluetooth SIG Weight Scale (0x181D) and User Data (0x181C) profiles.
 *
 * [StandardWeightProfileHandler] implements clock alignment (2A2B), user registration,
 * consent exchange (2A9F) and measurement indications (2A9D), so this class only
 * provides device matching and metadata.
 */
class SamsungSmartScaleHandler : StandardWeightProfileHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.US)
        if (!name.startsWith(DEVICE_NAME_PREFIX)) return null

        val capabilities = setOf(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
        )

        return DeviceSupport(
            displayName = "Samsung Smart Scale (GP-BELAD001BP0)",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    private companion object {
        const val DEVICE_NAME_PREFIX = "LEFU_SCALE"
    }
}