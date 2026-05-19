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

/**
 * Huawei CH100 body-fat scale.
 *
 * Same Chipsea CST34M97-based hardware as the [HuaweiAH100Handler]; the two
 * products only differ in the BLE advertisement name. All wire-protocol
 * logic lives in [HuaweiAhCh100ScaleHandler].
 *
 * This handler restores the v2.5.4 measurement-decoding behaviour after the
 * 3.x rewrite regressed it (see issues #1206, #1276, #1280). Tests in
 * `HuaweiAhCh100ProtocolTest` lock the protocol so the regression cannot
 * recur silently.
 */
class HuaweiCH100Handler : HuaweiAhCh100ScaleHandler() {
    override val supportedAdvertName: String = "CH100"
    override val displayName: String = "Huawei CH100"
}
