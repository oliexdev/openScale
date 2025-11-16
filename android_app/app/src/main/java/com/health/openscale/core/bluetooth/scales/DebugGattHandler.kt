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
 */
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.BluetoothGattCharacteristic
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * ## DebugGattHandler
 *
 * A pure **inspection** handler that:
 *
 * - Only activates when the scanned device name equals `"Debug"` (case-insensitive).
 * - On connect, **dumps the full GATT table** (all services and characteristics) by
 *   asking the adapter/transport for the current `BluetoothPeripheral`.
 * - Optionally performs a few safe reads/subscriptions on common services to trigger
 *   some traffic (helpful to verify notifications).
 * - Logs **every incoming notification** with a compact hex & ASCII preview.
 *
 * This handler **never publishes measurements** and is intended solely for diagnostics.
 *
 * ### Adapter requirement
 * The adapter/transport must expose `debugGetPeripheral(): BluetoothPeripheral?`.
 * In `GattScaleAdapter`, implement it by returning the current `BluetoothPeripheral`.
 *
 * ### Why this lives here
 * We keep all formatting, pretty-printing, and logging **inside this handler**, while
 * the adapter stays minimal and unopinionated.
 */
class DebugGattHandler : ScaleDeviceHandler() {
    /**
     * Only claim the device if the UI/scan explicitly selected the **Debug** handler.
     * This prevents us from hijacking real devices during normal operation.
     */
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val isDebug = device.name.equals("debug", ignoreCase = true)
        if (!isDebug) return null

        return DeviceSupport(
            displayName = "Debug",
            capabilities = emptySet(),   // no functional features
            implemented = emptySet(),
            tuningProfile = TuningProfile.Balanced,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    /**
     * On connect:
     * 1) Dump the **entire** GATT service/characteristic tree.
     * 2) Optionally poke a few common characteristics (best-effort).
     * 3) Arm light-weight subscriptions to typical measurement characteristics,
     *    if present (best-effort; errors are logged).
     */
    override fun onConnected(user: ScaleUser) {
        logD("Connected in Debug mode. Dumping full GATT services/characteristics…")
        dumpAllGatt()

        // --- Optional sanity probes (best-effort; they can fail silently) -----
        // Generic Access: Device Name
        readSafe(uuid16(0x1800), uuid16(0x2A00))
        // Device Information: Manufacturer / Model / FW / SW
        readSafe(uuid16(0x180A), uuid16(0x2A29))
        readSafe(uuid16(0x180A), uuid16(0x2A24))
        readSafe(uuid16(0x180A), uuid16(0x2A26))
        readSafe(uuid16(0x180A), uuid16(0x2A28))
        // Battery Level
        readSafe(uuid16(0x180F), uuid16(0x2A19))

        // Subscribe to common measurement characteristics if present
        setNotifySafe(uuid16(0x181D), uuid16(0x2A9D)) // Weight Scale -> Weight Measurement
        setNotifySafe(uuid16(0x181B), uuid16(0x2A9C)) // Body Comp   -> Body Composition Measurement

        logD("Debug handler armed. Incoming NOTIFY frames will be logged; no data is stored.")
    }

    /**
     * Every incoming notification is logged in a concise form:
     * - Pretty UUID (16-bit when possible)
     * - Hex preview (up to 64 bytes)
     * - ASCII preview (non-printables as '?')
     */
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val hex = data.toHexPreview(64)
        val ascii = data.toAsciiPreview(64)
        logD("NOTIFY chr=${prettyUuid(characteristic)}  $hex  ascii=$ascii")
    }

    // ---------------------------------------------------------------------------------------------
    // Dump utilities
    // ---------------------------------------------------------------------------------------------

    /**
     * Dump all discovered GATT services and their characteristics (with property flags).
     * Uses the transport's `debugGetPeripheral()` hook to access the raw peripheral.
     */
    private fun dumpAllGatt() {
        val peripheral = getPeripheral()
        if (peripheral == null) {
            logD("No peripheral available yet (transport.debugGetPeripheral() returned null).")
            return
        }

        val services = peripheral.services ?: emptyList()
        logD("=== GATT Service Dump BEGIN ===")
        if (services.isEmpty()) {
            logD( "(no services)")
            logD( "=== GATT Service Dump END ===")
            return
        }

        for (svc in services) {
            logD( "Service ${prettyUuid(svc.uuid)}")
            val chars = svc.characteristics ?: emptyList()
            for (ch in chars) {
                logD("  └─ Char ${prettyUuid(ch.uuid)} props=${propsToString(ch.properties)}"
                )
            }
        }
        logD("=== GATT Service Dump END ===")
    }

    // ---------------------------------------------------------------------------------------------
    // Safe wrappers (never throw; best-effort operations)
    // ---------------------------------------------------------------------------------------------

    private fun setNotifySafe(service: UUID, characteristic: UUID) {
        logD("→ setNotifyOn svc=${prettyUuid(service)} chr=${prettyUuid(characteristic)}")
        runCatching { setNotifyOn(service, characteristic) }
            .onFailure { logD("setNotifyOn failed: ${it.message ?: it::class.simpleName}") }
    }

    private fun readSafe(service: UUID, characteristic: UUID) {
        logD("→ read svc=${prettyUuid(service)} chr=${prettyUuid(characteristic)} (best effort)")
        runCatching { readFrom(service, characteristic) }
            .onFailure { logD("read failed: ${it.message ?: it::class.simpleName}") }
    }

    // ---------------------------------------------------------------------------------------------
    // Pretty-print helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Convert a standard 128-bit UUID with the Bluetooth base into a compact **0xNNNN** form.
     * Leaves full UUIDs intact for vendor/custom values.
     */
    private fun prettyUuid(u: UUID): String {
        val s = u.toString().lowercase(Locale.ROOT)
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
            "0x" + s.substring(4, 8)
        else
            s
    }

    /**
     * Turn Android GATT property flags into a readable pipe-separated string.
     * Example: `READ|WRITE_NR|NOTIFY|INDICATE`
     */
    private fun propsToString(p: Int): String {
        val flags = mutableListOf<String>()
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) flags += "READ"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) flags += "WRITE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) flags += "WRITE_NR"
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) flags += "NOTIFY"
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) flags += "INDICATE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) flags += "SIGNED"
        if ((p and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) flags += "BROADCAST"
        if ((p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) flags += "EXT"
        return if (flags.isEmpty()) "0" else flags.joinToString("|")
    }
}
