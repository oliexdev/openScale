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
package com.health.openscale.core.bluetooth.libs

/**
 * Replaceable source for Huawei Hagrid CAK/C1/C2 key material.
 *
 * Implementations must not log raw secret values. The upstream app only uses
 * explicitly configured settings here; private Huawei Health extraction tools
 * are outside this boundary.
 */
interface HuaweiHagridSecretProvider {
    val id: String

    fun load(
        getString: (String) -> String?,
        warn: (String) -> Unit
    ): HuaweiHagridWspLib.HagridSecrets?

    class DriverSettings(
        private val cakKey: String,
        private val c1Key: String,
        private val c2Key: String
    ) : HuaweiHagridSecretProvider {
        override val id: String = "driver-settings"

        override fun load(
            getString: (String) -> String?,
            warn: (String) -> Unit
        ): HuaweiHagridWspLib.HagridSecrets? {
            val cak = settingsHexOrNull(getString, warn, cakKey, 16)
            val c1 = settingsHexOrNull(getString, warn, c1Key, 16)
            val c2 = settingsHexOrNull(getString, warn, c2Key, 16)
            if (cak == null && c1 == null && c2 == null) return null
            if (cak == null || c1 == null || c2 == null) {
                warn("Incomplete Huawei Hagrid secrets; require CAK, C1, and C2 hex settings")
                return null
            }
            return runCatching { HuaweiHagridWspLib.HagridSecrets(cak, c1, c2) }
                .getOrElse { error ->
                    warn("Invalid Huawei Hagrid secrets: ${error.message}")
                    null
                }
        }

        private fun settingsHexOrNull(
            getString: (String) -> String?,
            warn: (String) -> Unit,
            key: String,
            expectedBytes: Int
        ): ByteArray? {
            val raw = getString(key)?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (hex.length != expectedBytes * 2) {
                warn("Ignoring Huawei Hagrid setting $key: expected ${expectedBytes * 2} hex digits, got ${hex.length}")
                return null
            }
            return runCatching {
                ByteArray(expectedBytes) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }.getOrElse { error ->
                warn("Ignoring Huawei Hagrid setting $key: ${error.message}")
                null
            }
        }
    }
}
