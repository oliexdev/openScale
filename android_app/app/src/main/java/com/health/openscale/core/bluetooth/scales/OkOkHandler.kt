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
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils

/**
 * Unified OKOK broadcast handler with dynamic display name:
 *  - "OKOK V20"  (manufacturer id 0x20CA): stable flag + XOR checksum
 *  - "OKOK V11"  (manufacturer id 0x11CA): XOR checksum, unit & resolution in body properties
 *  - "OKOK VF0"  (manufacturer id 0xF0FF): simple weight field
 *  - "OKOK C0" (any manufacturer id where low byte == 0xC0): MAC-embedded, unit & resolution in attrib
 *
 * Link mode: BROADCAST_ONLY (no GATT connection).
 */
class OkOkHandler : ScaleDeviceHandler() {

    // Known manufacturer ids
    private val MANUF_V20 = 0x20ca
    private val MANUF_V11 = 0x11ca
    private val MANUF_VF0 = 0xf0ff

    // V20 indices
    private val IDX_V20_FINAL = 6
    private val IDX_V20_WEIGHT_MSB = 8
    private val IDX_V20_WEIGHT_LSB = 9
    private val IDX_V20_IMPEDANCE_MSB = 10
    private val IDX_V20_IMPEDANCE_LSB = 11
    private val IDX_V20_CHECKSUM = 12

    // V11 indices
    private val IDX_V11_WEIGHT_MSB = 3
    private val IDX_V11_WEIGHT_LSB = 4
    private val IDX_V11_BODY_PROPERTIES = 9
    private val IDX_V11_CHECKSUM = 16

    // VF0 indices
    private val IDX_VF0_WEIGHT_MSB = 3
    private val IDX_VF0_WEIGHT_LSB = 2

    // 0xC0 indices
    private val IDX_WEIGHT_MSB = 0
    private val IDX_WEIGHT_LSB = 1
    private val IDX_ATTRIB     = 6
    private val UNIT_KG   = 0
    private val UNIT_JIN  = 1
    private val UNIT_LB   = 2
    private val UNIT_STLB = 3

    private val NamelessAlias = "OKOK Nameless"

    /**
     * Decide support + build a *dynamic* displayName based on manufacturer data present
     * in the advertisement that matched this device.
     */
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val m = device.manufacturerData ?: return null

        if (device.name.isEmpty() && containsLowByteC0(m))
            device.name = NamelessAlias
        val name = device.name

        val supports = name.equals(NamelessAlias) || name.equals("ADV") || name.equals("Chipsea-BLE")
        if (!supports) return null

        val variantName = when {
            hasKey(m, MANUF_V20) -> "OKOK V20"
            hasKey(m, MANUF_V11) -> "OKOK V11"
            hasKey(m, MANUF_VF0) -> "OKOK VF0"
            containsLowByteC0(m) -> "OKOK C0"
            else -> null
        } ?: return null

        // What the device can in theory (capabilities) vs what we actually implement now (implemented)
        val capabilities: Set<DeviceCapability> = when {
            hasKey(m, MANUF_V20) -> setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            )
            else -> setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        }

        val implemented: Set<DeviceCapability> = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM
        )

        return DeviceSupport(
            displayName = variantName,
            capabilities = capabilities,
            implemented = implemented,
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    /**
     * Parse manufacturer frames from advertisements; publish on first stable result.
     */
    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val m = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED

        // Try strict formats first
        parseV20(m)?.let { kg ->
            publish(ScaleMeasurement().apply { userId = user.id; weight = kg })
            return BroadcastAction.CONSUMED_STOP
        }
        parseV11(m)?.let { kg ->
            publish(ScaleMeasurement().apply { userId = user.id; weight = kg })
            return BroadcastAction.CONSUMED_STOP
        }
        parseVF0(m)?.let { kg ->
            publish(ScaleMeasurement().apply { userId = user.id; weight = kg })
            return BroadcastAction.CONSUMED_STOP
        }

        // Fallback: 0xC0 vendor
        parseC0(m)?.let { kg ->
            publish(ScaleMeasurement().apply { userId = user.id; weight = kg })
            return BroadcastAction.CONSUMED_STOP
        }

        return BroadcastAction.IGNORED
    }

    // --- Parsers --------------------------------------------------------------

    private fun parseV20(m: SparseArray<ByteArray>): Float? {
        val data = getManuf(m, MANUF_V20) ?: return null
        if (data.size != 19) return null

        val finalFlag = (data[IDX_V20_FINAL].toInt() and 0x01) != 0
        if (!finalFlag) return null

        // XOR checksum including implicit version 0x20
        var checksum = 0x20
        for (i in 0 until IDX_V20_CHECKSUM) checksum = checksum xor (data[i].toInt() and 0xFF)
        val got = data[IDX_V20_CHECKSUM].toInt() and 0xFF
        if (got != (checksum and 0xFF)) return null

        val divider = if ((data[IDX_V20_FINAL].toInt() and 0x04) != 0) 100.0f else 10.0f
        val weightRaw = u16be(data[IDX_V20_WEIGHT_MSB], data[IDX_V20_WEIGHT_LSB])

        // If needed later:
        // val imp = u16be(data[IDX_V20_IMPEDANCE_MSB], data[IDX_V20_IMPEDANCE_LSB]) / 10.0f

        return weightRaw / divider
    }

    private fun parseV11(m: SparseArray<ByteArray>): Float? {
        val data = getManuf(m, MANUF_V11) ?: return null
        // legacy length check: IDX_V11_CHECKSUM + 6 + 1 = 23 bytes
        if (data.size != IDX_V11_CHECKSUM + 6 + 1) return null

        // XOR checksum with implicit 0xCA ^ 0x11
        var checksum = 0xCA xor 0x11
        for (i in 0 until IDX_V11_CHECKSUM) checksum = checksum xor (data[i].toInt() and 0xFF)
        val got = data[IDX_V11_CHECKSUM].toInt() and 0xFF
        if (got != (checksum and 0xFF)) return null

        val props = data[IDX_V11_BODY_PROPERTIES].toInt() and 0xFF

        // resolution ((props >> 1) & 3)
        var divider = when ((props shr 1) and 0x3) {
            0 -> 10.0f
            1 -> 1.0f
            2 -> 100.0f
            else -> 10.0f
        }

        var weight = u16be(data[IDX_V11_WEIGHT_MSB], data[IDX_V11_WEIGHT_LSB])

        // unit ((props >> 3) & 3)
        return when ((props shr 3) and 0x3) {
            UNIT_KG -> weight / divider
            UNIT_JIN -> weight / (divider * 2.0f)
            UNIT_LB -> (weight / divider) / 2.204623f
            UNIT_STLB -> {
                val stones = (weight shr 8)
                val pounds = (weight and 0xFF) / divider
                stones * 6.350293f + pounds * 0.453592f
            }
            else -> null
        }
    }

    private fun parseVF0(m: SparseArray<ByteArray>): Float? {
        val data = getManuf(m, MANUF_VF0) ?: return null
        if (data.size < 4) return null
        val raw = u16be(data[IDX_VF0_WEIGHT_MSB], data[IDX_VF0_WEIGHT_LSB])
        return raw / 10.0f
    }

    private fun parseC0(m: SparseArray<ByteArray>): Float? {
        val key = firstKeyWithLowByteC0(m) ?: return null
        val data = m.get(key) ?: return null
        if (data.size < 13) return null

        val attrib = data[IDX_ATTRIB].toInt() and 0xFF
        val isStable = (attrib and 0x01) != 0
        if (!isStable) return null

        val divider = when ((attrib shr 1) and 0x3) {
            0 -> 10f
            1 -> 1f
            2 -> 100f
            else -> 10f
        }

        return when ((attrib shr 3) and 0x3) {
            UNIT_KG -> {
                val raw = u16be(data[IDX_WEIGHT_MSB], data[IDX_WEIGHT_LSB])
                raw / divider
            }
            UNIT_JIN -> {
                val raw = u16be(data[IDX_WEIGHT_MSB], data[IDX_WEIGHT_LSB])
                raw / divider / 2
            }
            UNIT_LB -> {
                val raw = u16be(data[IDX_WEIGHT_MSB], data[IDX_WEIGHT_LSB])
                ConverterUtils.toKilogram(raw / divider, WeightUnit.LB)
            }
            UNIT_STLB -> {
                val stones = data[IDX_WEIGHT_MSB].toInt() and 0xFF
                val pounds = (data[IDX_WEIGHT_LSB].toInt() and 0xFF) / divider
                stones * 6.350293f + pounds * 0.453592f
            }
            else -> null
        }
    }

    // --- utils ----------------------------------------------------------------

    private fun hasKey(sa: SparseArray<ByteArray>, key: Int): Boolean =
        sa.indexOfKey(key) >= 0

    private fun getManuf(sa: SparseArray<ByteArray>, key: Int): ByteArray? =
        if (sa.indexOfKey(key) >= 0) sa.get(key) else null

    private fun containsLowByteC0(sa: SparseArray<ByteArray>): Boolean {
        for (i in 0 until sa.size()) if ((sa.keyAt(i) and 0xFF) == 0xC0) return true
        return false
    }

    private fun firstKeyWithLowByteC0(sa: SparseArray<ByteArray>): Int? {
        for (i in 0 until sa.size()) {
            val k = sa.keyAt(i)
            if ((k and 0xFF) == 0xC0) return k
        }
        return null
    }

    private fun u16be(msb: Byte, lsb: Byte): Int =
        ((msb.toInt() and 0xFF) shl 8) or (lsb.toInt() and 0xFF)
}
