/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import java.util.Calendar

/**
 * BF500 stored readings use the same WSS/BCS indications as a live weigh-in.
 * In a vendor-app capture, consent caused a timestamped 2A9D weight followed by
 * a timestamp-less 2A9C composition packet, before the consent response arrived.
 * No separate history request was observed. Keep each weight as a record boundary.
 */
class BeurerBf500Lib {
    private var pending: ScaleMeasurement? = null

    fun weight(data: ByteArray, mapUser: (Int) -> Int): List<ScaleMeasurement> {
        val next = decode(data, mapUser, body = false) ?: return emptyList()
        val previous = pending
        pending = next.measurement.takeUnless { it.userId == -1 }
        return listOfNotNull(previous)
    }

    fun body(data: ByteArray, mapUser: (Int) -> Int): List<ScaleMeasurement> {
        val decoded = decode(data, mapUser, body = true) ?: return emptyList()
        val composition = decoded.measurement
        val weight = pending ?: return emptyList()
        if (composition.userId == -1) return emptyList()
        if (composition.userId != 0xFF && weight.userId != 0xFF && composition.userId != weight.userId) return emptyList()
        if (composition.dateTime != null && weight.dateTime != null && composition.dateTime != weight.dateTime) return emptyList()
        pending = null
        weight.mergeWith(composition)
        val kg = weight[MeasurementType.WEIGHT]?.value ?: return emptyList()
        decoded.waterKg?.let {
            weight[MeasurementType.WATER] = Percent(it / kg * 100f)
        }
        val fat = composition[MeasurementType.BODY_FAT]?.value
        val softLean = decoded.softLeanKg
        if (fat != null && softLean != null) {
            val lean = kg * (1f - fat / 100f)
            weight[MeasurementType.LBM] = Kg(lean)
            weight[MeasurementType.BONE] = Kg(lean - softLean)
        }
        return listOf(weight)
    }

    fun flush(): ScaleMeasurement? = pending.also { pending = null }
    fun reset() { pending = null }

    private data class Decoded(
        val measurement: ScaleMeasurement,
        val softLeanKg: Float? = null,
        val waterKg: Float? = null,
    )

    private fun decode(data: ByteArray, mapUser: (Int) -> Int, body: Boolean): Decoded? {
        try {
            val r = Reader(data)
            val flags = if (body) r.u16() else r.u8()
            // Fragmented composition is not present in the BF500 captures.
            if (body && flags and 0x1000 != 0) return null
            val kg = flags and 1 == 0
            fun mass(raw: Int): Kg = if (kg) Kg(raw * 0.005f) else Kg.fromLb(raw * 0.01f)
            val m = ScaleMeasurement()
            val primary = r.u16()
            if (body) m[MeasurementType.BODY_FAT] = Percent(primary * 0.1f)
            else m[MeasurementType.WEIGHT] = mass(primary)
            if (flags and 2 != 0) m.dateTime = r.date()
            if (flags and 4 != 0) {
                val slot = r.u8()
                if (slot != 0xFF) {
                    val appId = mapUser(slot)
                    m.userId = appId
                }
            }
            if (!body) {
                if (flags and 8 != 0) { r.u16(); r.u16() } // BMI and height
                return m.takeIf { it.hasWeight() }?.let { Decoded(it) }
            }
            if (flags and 0x0008 != 0) r.u16() // basal metabolism
            if (flags and 0x0010 != 0) m[MeasurementType.MUSCLE] = Percent(r.u16() * 0.1f)
            if (flags and 0x0020 != 0) r.u16() // muscle mass
            if (flags and 0x0040 != 0) r.u16() // fat-free mass
            val softLean = if (flags and 0x0080 != 0) mass(r.u16()).value.takeIf { it > 0f } else null
            val water = if (flags and 0x0100 != 0) mass(r.u16()).value.takeIf { it > 0f } else null
            if (flags and 0x0200 != 0) m[MeasurementType.IMPEDANCE] = Ohm(r.u16() * 0.1f)
            if (flags and 0x0400 != 0) m[MeasurementType.WEIGHT] = mass(r.u16())
            if (flags and 0x0800 != 0) r.u16() // height
            return Decoded(m, softLean, water)
        } catch (_: IndexOutOfBoundsException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0
        fun u8(): Int = data[offset++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun date(): java.util.Date {
            val year = u16()
            val month = u8()
            val day = u8()
            val hour = u8()
            val minute = u8()
            val second = u8()
            return Calendar.getInstance().apply {
                clear()
                isLenient = false
                set(year, month - 1, day, hour, minute, second)
            }.time
        }
    }
}
