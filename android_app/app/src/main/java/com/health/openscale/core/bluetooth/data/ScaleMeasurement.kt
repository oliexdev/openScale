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
package com.health.openscale.core.bluetooth.data

import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitValue
import java.util.Date
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Percent

/**
 * One weigh-in as reported by a scale, potentially combined from multiple BLE packets —
 * the envelope (who, when) plus a map of values keyed by the [MeasurementType.Key] that
 * describes them.
 *
 * Handlers write predefined quantities and their own device keys through the same API.
 * The unit is part of the value's type — setting a bare number or the wrong unit does
 * not compile, and the conversions live on the unit classes (`Kg.fromLb`, `Cm.fromInch`):
 *
 *     m[MeasurementType.WEIGHT] = Kg(raw * 0.005f)       // Key<Kg>
 *     m[MeasurementType.BODY_FAT] = Percent(fat)          // Key<Percent>
 *     m[MeasurementType.HEART_RATE] = Bpm(60)             // Key<Bpm>
 *     m[FAT_LEFT_ARM] = Percent(12.4f)                    // handler-declared devicePercent
 *
 * Absence is absence: a quantity the scale did not report is simply not in the map — there
 * is no 0f sentinel. [set] therefore drops values that never describe a real measurement
 * (non-finite or non-positive numbers, blank text), which keeps [mergeWith]'s
 * first-value-wins semantics sound without every handler guarding its writes.
 */
class ScaleMeasurement(
    /** openScale's internal app user ID; 0xFF = the scale did not report a user slot. */
    var userId: Int = 0xFF,
    /** Timestamp of the weigh-in; becomes the Measurement row's timestamp, null = "now". */
    var dateTime: Date? = null,
    val values: MutableMap<MeasurementType.Key<*>, Any> = LinkedHashMap(),
) {

    /**
     * Records a value; silently drops what never describes a real measurement (see class
     * doc). The value's type is the unit contract — the connector unwraps and converts to
     * the user's configured unit once, centrally.
     */
    operator fun <T : Any> set(key: MeasurementType.Key<T>, value: T) {
        val usable = when (value) {
            is UnitValue -> value.value.isFinite() && value.value > 0f
            is Float -> value.isFinite() && value > 0f
            is Bpm -> value.value > 0
            is Int -> value > 0
            is String -> value.isNotBlank()
            is Date -> true
            else -> false   // Unit (the USER column key) carries no value
        }
        if (usable) values[key] = value
    }

    /** Reads a value back, typed as the key declared it; null = not reported. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: MeasurementType.Key<T>): T? = values[key] as T?

    operator fun contains(key: MeasurementType.Key<*>): Boolean = key in values

    fun hasWeight(): Boolean = MeasurementType.WEIGHT in this

    /**
     * Fill-the-gaps merge for multi-packet protocols: a value already collected wins over
     * one arriving later; userId and dateTime transfer only when still unset.
     */
    fun mergeWith(other: ScaleMeasurement) = apply {
        other.values.forEach { (key, value) -> values.putIfAbsent(key, value) }

        if (other.userId != 0xFF &&
            (this.userId == 0xFF || this.userId == -1)) { // -1 was a common init value
            this.userId = other.userId
        }
        if (this.dateTime == null && other.dateTime != null) this.dateTime = other.dateTime
    }

    /**
     * Deep copy for handlers that publish while continuing to mutate their accumulator —
     * the map is detached, so later mutations never bleed into published data.
     */
    fun snapshot(): ScaleMeasurement = ScaleMeasurement(userId, dateTime, LinkedHashMap(values))

    override fun toString(): String =
        "ScaleMeasurement(userId=$userId, dateTime=$dateTime, " +
            "values=${values.entries.joinToString { "${it.key.identity}=${it.value}" }})"
}
