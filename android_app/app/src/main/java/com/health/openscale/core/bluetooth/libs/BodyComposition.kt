/*
 * openScale
 * Copyright (C) 2026 Dany Mestas
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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType

/**
 * Shared body-composition derivations, factored out of the individual scale
 * handlers so a given calculator lib is bound to (user, raw inputs) in exactly
 * one place. Handlers call these from both their parse path and their
 * [com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler.recomputeBodyComposition]
 * override, which is what lets body composition be (re)computed for the FINAL
 * assigned user instead of whoever was merely selected at weigh-in.
 *
 * Each function reads the raw, user-independent quantities already present on
 * [ScaleMeasurement] (weight + impedance/resistance) and mutates the derived
 * fields in place, returning the same instance. A guard failure (no weight /
 * no impedance) returns [raw] untouched.
 */
object BodyComposition {

    /**
     * MiScaleLib-based scales (Xiaomi Mi Body Composition, Eufy C20). [raw.impedance]
     * holds impedance in Ω. Sets fat/water/muscle/bone/lbm/visceralFat.
     */
    fun fromMiScale(raw: ScaleMeasurement, user: ScaleUser): ScaleMeasurement {
        val imp = raw.impedance.toFloat()
        if (raw.weight <= 0f || imp <= 0f) return raw
        val sex = if (user.gender == GenderType.MALE) 1 else 0
        val lib = MiScaleLib(sex, user.age, user.bodyHeight)
        return raw.apply {
            fat = lib.getBodyFat(weight, imp)
            water = lib.getWater(weight, imp)
            muscle = lib.getMuscle(weight, imp)
            bone = lib.getBoneMass(weight, imp)
            lbm = lib.getLBM(weight, imp)
            visceralFat = lib.getVisceralFat(weight)
        }
    }

    /**
     * TrisaBodyAnalyzeLib-based scales (Trisa, QN, ES-CS20M). [raw.impedance]
     * holds the RAW resistance; the QN resistance→impedance conversion is applied
     * before the lib (shared lineage). Sets fat/water/muscle/bone — callers that
     * also derive lbm do so from the resulting fat.
     */
    fun fromTrisa(raw: ScaleMeasurement, user: ScaleUser): ScaleMeasurement {
        val r = raw.impedance.toFloat()
        if (raw.weight <= 0f || r <= 0f) return raw
        val imp = if (r < 410f) 3.0f else 0.3f * (r - 400f)
        val sex = if (user.gender == GenderType.MALE) 1 else 0
        val lib = TrisaBodyAnalyzeLib(sex, user.age, user.bodyHeight)
        return raw.apply {
            fat = lib.getFat(weight, imp)
            water = lib.getWater(weight, imp)
            muscle = lib.getMuscle(weight, imp)
            bone = lib.getBone(weight, imp)
        }
    }

    /**
     * Builds a [StandardImpedanceLib] configured from [raw] + [user] (height in
     * metres, impedance in Ω), or null when inputs are unusable. Field mapping is
     * left to the caller because the StandardImpedanceLib scales (Etekcity,
     * Dr Trust, Cult) intentionally map/coerce its outputs differently.
     *
     * @param maxImpedance exclusive upper bound; impedance ≥ this returns null.
     */
    fun standardImpedanceLib(
        raw: ScaleMeasurement,
        user: ScaleUser,
        maxImpedance: Double = Double.MAX_VALUE,
    ): StandardImpedanceLib? {
        val impedance = raw.impedance
        if (raw.weight <= 0f || impedance <= 0.0 || impedance >= maxImpedance) return null
        return StandardImpedanceLib(
            gender = user.gender,
            age = user.age,
            weightKg = raw.weight.toDouble(),
            heightM = user.bodyHeight / 100.0,
            impedance = impedance,
        )
    }
}
