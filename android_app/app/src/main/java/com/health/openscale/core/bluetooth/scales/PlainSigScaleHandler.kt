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
import java.util.UUID

/**
 * Scales that speak the plain Bluetooth SIG Weight Scale Profile: measurements are indicated on
 * 2A9D and the clock is written through Current Time (2A2B). [StandardWeightProfileHandler]
 * implements both, so this class only supplies model discovery and capability metadata.
 */
class PlainSigScaleHandler : StandardWeightProfileHandler() {

    private class Model(val namePrefix: String, val displayName: String, val requiredService: UUID? = null)

    private val models = listOf(
        Model("A&D_UC-352BLE_", "A&D UC-352BLE"),
        // LEFU_SCALE is Lefu's generic name; only the variant on 0x181D speaks this profile.
        Model("LEFU_SCALE", "Samsung Smart Scale (GP-BELAD001BP0)", requiredService = uuid16(0x181D)),
    )

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase(Locale.US)
        val model = models.firstOrNull {
            name.startsWith(it.namePrefix) && (it.requiredService == null || it.requiredService in device.serviceUuids)
        } ?: return null

        val capabilities = setOf(
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
        )
        return DeviceSupport(
            displayName = model.displayName,
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }
}
