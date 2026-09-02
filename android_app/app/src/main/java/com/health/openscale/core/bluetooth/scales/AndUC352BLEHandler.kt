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
 * Handler for the A&D UC-352BLE.
 *
 * The scale uses the Bluetooth SIG Weight Scale Profile: measurements are indicated on 2A9D and
 * its clock is written through Current Time (2A2B). [StandardWeightProfileHandler] implements both
 * parts, so this class only supplies model-specific discovery and capability metadata.
 */
class AndUC352BLEHandler : StandardWeightProfileHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.uppercase(Locale.US).startsWith(DEVICE_NAME_PREFIX)) return null

        val capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.TIME_SYNC,
        )
        return DeviceSupport(
            displayName = "A&D UC-352BLE",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    private companion object {
        const val DEVICE_NAME_PREFIX = "A&D_UC-352BLE_"
    }
}
