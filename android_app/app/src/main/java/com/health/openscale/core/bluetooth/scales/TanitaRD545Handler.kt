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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TanitaLib
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.UUID

/** RD-545EU: existing scale profiles, metric measurements, explicit per-app-user slot links. */
class TanitaRD545Handler : ScaleDeviceHandler() {
    companion object {
        val SERVICE: UUID = UUID.fromString("273e5100-6b90-4779-83b8-b8bf1dadac35")
        val WRITE: UUID = UUID.fromString("273e5107-6b90-4779-83b8-b8bf1dadac35")
        val NOTIFY: UUID = UUID.fromString("273e510e-6b90-4779-83b8-b8bf1dadac35")

        // openScale's own public application identity. Register it only after the
        // user requests setup; the vendor app's identifier is never needed.
        val APPLICATION_ID: UUID = UUID.fromString("df901c76-c585-423b-a15c-effc20fda925")
        internal const val KEY_REGISTER = "tanita/register"
        internal const val KEY_LINKS = "tanita/user-slots"

        internal val KG_METRICS: Map<TanitaLib.Metric, MeasurementType.Key<Kg>> = mapOf(
            TanitaLib.Metric.MUSCLE_MASS to MeasurementType.deviceKg("total_muscle_mass", R.string.measurement_type_total_muscle, enabled = false, isInternal = true),
            TanitaLib.Metric.WATER_MASS to MeasurementType.deviceKg("water_mass", R.string.tanita_metric_water_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_LEG_FAT_MASS to MeasurementType.deviceKg("segmental.fat_mass.right_leg", R.string.tanita_metric_right_leg_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_LEG_LEAN_MASS to MeasurementType.deviceKg("segmental.lean_mass.right_leg", R.string.tanita_metric_right_leg_lean_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_LEG_MUSCLE_MASS to MeasurementType.deviceKg("segmental.muscle_mass.right_leg", R.string.tanita_metric_right_leg_muscle_mass),
            TanitaLib.Metric.LEFT_LEG_FAT_MASS to MeasurementType.deviceKg("segmental.fat_mass.left_leg", R.string.tanita_metric_left_leg_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_LEG_LEAN_MASS to MeasurementType.deviceKg("segmental.lean_mass.left_leg", R.string.tanita_metric_left_leg_lean_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_LEG_MUSCLE_MASS to MeasurementType.deviceKg("segmental.muscle_mass.left_leg", R.string.tanita_metric_left_leg_muscle_mass),
            TanitaLib.Metric.RIGHT_ARM_FAT_MASS to MeasurementType.deviceKg("segmental.fat_mass.right_arm", R.string.tanita_metric_right_arm_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_ARM_LEAN_MASS to MeasurementType.deviceKg("segmental.lean_mass.right_arm", R.string.tanita_metric_right_arm_lean_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_ARM_MUSCLE_MASS to MeasurementType.deviceKg("segmental.muscle_mass.right_arm", R.string.tanita_metric_right_arm_muscle_mass),
            TanitaLib.Metric.LEFT_ARM_FAT_MASS to MeasurementType.deviceKg("segmental.fat_mass.left_arm", R.string.tanita_metric_left_arm_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_ARM_LEAN_MASS to MeasurementType.deviceKg("segmental.lean_mass.left_arm", R.string.tanita_metric_left_arm_lean_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_ARM_MUSCLE_MASS to MeasurementType.deviceKg("segmental.muscle_mass.left_arm", R.string.tanita_metric_left_arm_muscle_mass),
            TanitaLib.Metric.TORSO_FAT_MASS to MeasurementType.deviceKg("segmental.fat_mass.torso", R.string.tanita_metric_torso_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.TORSO_LEAN_MASS to MeasurementType.deviceKg("segmental.lean_mass.torso", R.string.tanita_metric_torso_lean_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.TORSO_MUSCLE_MASS to MeasurementType.deviceKg("segmental.muscle_mass.torso", R.string.tanita_metric_torso_muscle_mass),
            TanitaLib.Metric.FAT_MASS to MeasurementType.deviceKg("fat_mass", R.string.tanita_metric_fat_mass, enabled = false, isInternal = true),
            TanitaLib.Metric.STANDARD_WEIGHT to MeasurementType.deviceKg("tanita.standard_weight", R.string.tanita_metric_standard_weight, enabled = false, isInternal = true),
        )

        internal val PERCENT_METRICS: Map<TanitaLib.Metric, MeasurementType.Key<Percent>> = mapOf(
            TanitaLib.Metric.RIGHT_LEG_FAT to MeasurementType.devicePercent("segmental.fat.right_leg", R.string.measurement_type_segmental_fat_right_leg),
            TanitaLib.Metric.LEFT_LEG_FAT to MeasurementType.devicePercent("segmental.fat.left_leg", R.string.measurement_type_segmental_fat_left_leg),
            TanitaLib.Metric.RIGHT_ARM_FAT to MeasurementType.devicePercent("segmental.fat.right_arm", R.string.measurement_type_segmental_fat_right_arm),
            TanitaLib.Metric.LEFT_ARM_FAT to MeasurementType.devicePercent("segmental.fat.left_arm", R.string.measurement_type_segmental_fat_left_arm),
            TanitaLib.Metric.TORSO_FAT to MeasurementType.devicePercent("segmental.fat.torso", R.string.measurement_type_segmental_fat_torso),
            TanitaLib.Metric.OBESITY_PERCENT to MeasurementType.devicePercent("tanita.obesity", R.string.tanita_metric_obesity_percent, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
        )

        internal val INT_METRICS: Map<TanitaLib.Metric, MeasurementType.Key<Int>> = mapOf(
            TanitaLib.Metric.MUSCLE_SCORE to MeasurementType.deviceInt("tanita.muscle_score", R.string.tanita_metric_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.METABOLIC_AGE to MeasurementType.deviceInt("metabolic_age", R.string.measurement_type_metabolic_age),
            TanitaLib.Metric.LEG_SCORE to MeasurementType.deviceInt("tanita.leg_score", R.string.tanita_metric_leg_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.BMR_RATING to MeasurementType.deviceInt("tanita.rating.bmr", R.string.tanita_metric_bmr_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_LEG_FAT_SCORE to MeasurementType.deviceInt("tanita.segmental.fat_score.right_leg", R.string.tanita_metric_right_leg_fat_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_LEG_MUSCLE_SCORE to MeasurementType.deviceInt("tanita.segmental.muscle_score.right_leg", R.string.tanita_metric_right_leg_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_LEG_FAT_SCORE to MeasurementType.deviceInt("tanita.segmental.fat_score.left_leg", R.string.tanita_metric_left_leg_fat_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_LEG_MUSCLE_SCORE to MeasurementType.deviceInt("tanita.segmental.muscle_score.left_leg", R.string.tanita_metric_left_leg_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_ARM_FAT_SCORE to MeasurementType.deviceInt("tanita.segmental.fat_score.right_arm", R.string.tanita_metric_right_arm_fat_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_ARM_MUSCLE_SCORE to MeasurementType.deviceInt("tanita.segmental.muscle_score.right_arm", R.string.tanita_metric_right_arm_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_ARM_FAT_SCORE to MeasurementType.deviceInt("tanita.segmental.fat_score.left_arm", R.string.tanita_metric_left_arm_fat_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_ARM_MUSCLE_SCORE to MeasurementType.deviceInt("tanita.segmental.muscle_score.left_arm", R.string.tanita_metric_left_arm_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.TORSO_FAT_SCORE to MeasurementType.deviceInt("tanita.segmental.fat_score.torso", R.string.tanita_metric_torso_fat_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.TORSO_MUSCLE_SCORE to MeasurementType.deviceInt("tanita.segmental.muscle_score.torso", R.string.tanita_metric_torso_muscle_score, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.BONE_RATING to MeasurementType.deviceInt("tanita.rating.bone", R.string.tanita_metric_bone_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.MUSCLE_QUALITY to MeasurementType.deviceInt("tanita.muscle_quality", R.string.tanita_metric_muscle_quality, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_ARM_MUSCLE_QUALITY to MeasurementType.deviceInt("tanita.segmental.muscle_quality.left_arm", R.string.tanita_metric_left_arm_muscle_quality, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_ARM_MUSCLE_QUALITY to MeasurementType.deviceInt("tanita.segmental.muscle_quality.right_arm", R.string.tanita_metric_right_arm_muscle_quality, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_LEG_MUSCLE_QUALITY to MeasurementType.deviceInt("tanita.segmental.muscle_quality.left_leg", R.string.tanita_metric_left_leg_muscle_quality, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_LEG_MUSCLE_QUALITY to MeasurementType.deviceInt("tanita.segmental.muscle_quality.right_leg", R.string.tanita_metric_right_leg_muscle_quality, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.HEART_RATE_STATE to MeasurementType.deviceInt("tanita.heart_rate_state", R.string.tanita_metric_heart_rate_state, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.FAT_RATING to MeasurementType.deviceInt("tanita.rating.fat", R.string.tanita_metric_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_LEG_FAT_RATING to MeasurementType.deviceInt("tanita.segmental.rating.fat.right_leg", R.string.tanita_metric_right_leg_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_LEG_FAT_RATING to MeasurementType.deviceInt("tanita.segmental.rating.fat.left_leg", R.string.tanita_metric_left_leg_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_ARM_FAT_RATING to MeasurementType.deviceInt("tanita.segmental.rating.fat.right_arm", R.string.tanita_metric_right_arm_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_ARM_FAT_RATING to MeasurementType.deviceInt("tanita.segmental.rating.fat.left_arm", R.string.tanita_metric_left_arm_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.TORSO_FAT_RATING to MeasurementType.deviceInt("tanita.segmental.rating.fat.torso", R.string.tanita_metric_torso_fat_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.BMI_RATING to MeasurementType.deviceInt("tanita.rating.bmi", R.string.tanita_metric_bmi_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.MUSCLE_RATING to MeasurementType.deviceInt("tanita.rating.muscle", R.string.tanita_metric_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_LEG_MUSCLE_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle.right_leg", R.string.tanita_metric_right_leg_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_LEG_MUSCLE_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle.left_leg", R.string.tanita_metric_left_leg_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_ARM_MUSCLE_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle.right_arm", R.string.tanita_metric_right_arm_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_ARM_MUSCLE_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle.left_arm", R.string.tanita_metric_left_arm_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.TORSO_MUSCLE_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle.torso", R.string.tanita_metric_torso_muscle_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.VISCERAL_RATING to MeasurementType.deviceInt("tanita.rating.visceral_fat", R.string.tanita_metric_visceral_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.MUSCLE_QUALITY_RATING to MeasurementType.deviceInt("tanita.rating.muscle_quality", R.string.tanita_metric_muscle_quality_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_ARM_MUSCLE_QUALITY_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle_quality.left_arm", R.string.tanita_metric_left_arm_muscle_quality_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_ARM_MUSCLE_QUALITY_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle_quality.right_arm", R.string.tanita_metric_right_arm_muscle_quality_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.LEFT_LEG_MUSCLE_QUALITY_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle_quality.left_leg", R.string.tanita_metric_left_leg_muscle_quality_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
            TanitaLib.Metric.RIGHT_LEG_MUSCLE_QUALITY_RATING to MeasurementType.deviceInt("tanita.segmental.rating.muscle_quality.right_leg", R.string.tanita_metric_right_leg_muscle_quality_rating, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.NON_NEGATIVE),
        )

        internal val OHM_METRICS: Map<TanitaLib.Metric, MeasurementType.Key<Ohm>> = mapOf(
            TanitaLib.Metric.LEFT_BODY_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_body.50khz.resistance", R.string.tanita_metric_left_body_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_BODY_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_body.50khz.reactance", R.string.tanita_metric_left_body_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_BODY_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_body.6_25khz.resistance", R.string.tanita_metric_left_body_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_BODY_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_body.6_25khz.reactance", R.string.tanita_metric_left_body_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_BODY_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_body.50khz.resistance", R.string.tanita_metric_right_body_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_BODY_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_body.50khz.reactance", R.string.tanita_metric_right_body_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_BODY_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_body.6_25khz.resistance", R.string.tanita_metric_right_body_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_BODY_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_body.6_25khz.reactance", R.string.tanita_metric_right_body_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_ARM_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_arm.50khz.resistance", R.string.tanita_metric_left_arm_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_ARM_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_arm.50khz.reactance", R.string.tanita_metric_left_arm_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_ARM_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_arm.6_25khz.resistance", R.string.tanita_metric_left_arm_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_ARM_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_arm.6_25khz.reactance", R.string.tanita_metric_left_arm_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_ARM_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_arm.50khz.resistance", R.string.tanita_metric_right_arm_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_ARM_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_arm.50khz.reactance", R.string.tanita_metric_right_arm_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_ARM_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_arm.6_25khz.resistance", R.string.tanita_metric_right_arm_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_ARM_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_arm.6_25khz.reactance", R.string.tanita_metric_right_arm_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_LEG_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_leg.50khz.resistance", R.string.tanita_metric_left_leg_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_LEG_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_leg.50khz.reactance", R.string.tanita_metric_left_leg_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEFT_LEG_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.left_leg.6_25khz.resistance", R.string.tanita_metric_left_leg_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEFT_LEG_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.left_leg.6_25khz.reactance", R.string.tanita_metric_left_leg_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_LEG_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_leg.50khz.resistance", R.string.tanita_metric_right_leg_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_LEG_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_leg.50khz.reactance", R.string.tanita_metric_right_leg_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.RIGHT_LEG_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.right_leg.6_25khz.resistance", R.string.tanita_metric_right_leg_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.RIGHT_LEG_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.right_leg.6_25khz.reactance", R.string.tanita_metric_right_leg_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.ARMS_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.arms.50khz.resistance", R.string.tanita_metric_arms_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.ARMS_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.arms.50khz.reactance", R.string.tanita_metric_arms_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.ARMS_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.arms.6_25khz.resistance", R.string.tanita_metric_arms_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.ARMS_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.arms.6_25khz.reactance", R.string.tanita_metric_arms_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEGS_50KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.legs.50khz.resistance", R.string.tanita_metric_legs_50khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEGS_50KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.legs.50khz.reactance", R.string.tanita_metric_legs_50khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
            TanitaLib.Metric.LEGS_6_25KHZ_RESISTANCE to MeasurementType.deviceOhm("tanita.impedance.legs.6_25khz.resistance", R.string.tanita_metric_legs_6_25khz_resistance, enabled = false, isInternal = true),
            TanitaLib.Metric.LEGS_6_25KHZ_REACTANCE to MeasurementType.deviceOhm("tanita.impedance.legs.6_25khz.reactance", R.string.tanita_metric_legs_6_25khz_reactance, enabled = false, isInternal = true, numericPolicy = MeasurementType.NumericPolicy.SIGNED),
        )

        val TOTAL_MUSCLE = KG_METRICS.getValue(TanitaLib.Metric.MUSCLE_MASS)
        val METABOLIC_AGE = INT_METRICS.getValue(TanitaLib.Metric.METABOLIC_AGE)
        val MUSCLE_QUALITY = INT_METRICS.getValue(TanitaLib.Metric.MUSCLE_QUALITY)
        val PHYSIQUE_RATING = MeasurementType.deviceInt("tanita.physique_rating", R.string.tanita_metric_physique_rating)
    }

    private enum class Stage(val response: Int) {
        IDLE(0), MODEL(0), VERIFY(0x8003), CLOCK(0x8010), INFO(0x8020),
        PROFILES(0x9000), CHOICE(0x9000), EXCHANGE(0x9002), REGISTER(0x8002),
        MEASURING(0xa010), COUNT(0xb000), READING(0xb010), SAVING(0), FINISHED(0),
    }

    private val assembler = TanitaLib.Assembler()
    @Volatile private var stage = Stage.IDLE
    private var pendingSaveToken: Any? = null
    private var timeoutJob: Job? = null
    private var choiceJob: Job? = null
    private var sessionUserId = -1
    private var registering = false
    private var selectedSlot = -1
    private var profileSlot = 0
    private var recordIndex = 1
    private val profiles = linkedMapOf<Int, TanitaLib.Profile>()

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
        val modelName = name == "RD-545EU" || name == "RD-545"
        val familyName = name in setOf("TNT_PAIR", "TNT_BW") && SERVICE in device.serviceUuids
        if (!modelName && !familyName) return null
        // The family advertisement is shared. Read and validate the model before
        // subscribing or issuing any Tanita-specific command.
        val capabilities = setOf(DeviceCapability.BODY_COMPOSITION, DeviceCapability.TIME_SYNC, DeviceCapability.HISTORY_READ)
        return DeviceSupport("Tanita RD-545", capabilities, capabilities, linkMode = LinkMode.CONNECT_GATT)
    }

    @Composable
    override fun DeviceConfigurationUi() {
        var pending by remember { mutableStateOf(settingsGetInt(KEY_REGISTER, 0) == 1) }
        var linksCleared by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tanita_profile_help))
            Text(stringResource(R.string.tanita_registration_help))
            Button(onClick = {
                settingsPutInt(KEY_REGISTER, 1)
                pending = true
            }) { Text(stringResource(R.string.tanita_register)) }
            if (pending) Text(stringResource(R.string.tanita_registration_pending))
            TextButton(onClick = {
                settingsPutString(KEY_LINKS, "")
                linksCleared = true
            }) { Text(stringResource(R.string.tanita_reset_links)) }
            if (linksCleared) Text(stringResource(R.string.tanita_links_cleared))
        }
    }

    @Synchronized
    override fun onConnected(user: ScaleUser) {
        pendingSaveToken = null
        assembler.reset()
        profiles.clear()
        sessionUserId = user.id
        registering = settingsGetInt(KEY_REGISTER, 0) == 1
        selectedSlot = userLinks()[user.id] ?: -1
        if (user.useAssistedWeighing) {
            fail(R.string.tanita_assisted_unsupported)
            return
        }
        if (!hasCharacteristic(SERVICE, WRITE) || !hasCharacteristic(SERVICE, NOTIFY)) {
            fail(R.string.tanita_invalid_response)
            return
        }
        enter(Stage.MODEL)
        readFrom(uuid16(0x1800), uuid16(0x2a00))
    }

    @Synchronized
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (stage in setOf(Stage.IDLE, Stage.FINISHED)) return
        if (user.id != sessionUserId) {
            fail(R.string.tanita_user_changed)
            return
        }
        if (stage == Stage.MODEL && characteristic == uuid16(0x2a00)) {
            val model = data.toString(Charsets.UTF_8).trimEnd('\u0000')
            if (model !in setOf("RD-545EU", "RD-545")) {
                fail(R.string.tanita_unsupported_model, model.take(64))
                return
            }
            setNotifyOn(SERVICE, NOTIFY)
            if (registering) sendClock() else send(TanitaLib.verifyUuid(APPLICATION_ID), Stage.VERIFY)
            return
        }
        if (characteristic != NOTIFY) return
        val message = assembler.add(data) ?: return
        val response = TanitaLib.parseResponse(message) ?: return
        logD("Tanita response ${response.command.toString(16)}: status=${response.status}, payload=${response.payload.size} bytes")
        if (response.command != stage.response) {
            logD("Ignoring unexpected Tanita response ${response.command.toString(16)}")
            return
        }
        if (stage == Stage.CHOICE) return // read-only keepalive while the dialog is open
        if (stage == Stage.PROFILES) {
            if (response.status == 0) TanitaLib.parseProfile(response)?.let { profiles[profileSlot] = it }
            finishProfileRead()
            return
        }
        if (response.status != 0) {
            if (stage == Stage.MEASURING && response.status == 1) {
                fail(R.string.tanita_measurement_failed)
            } else {
                fail(R.string.tanita_operation_failed, response.status)
            }
            return
        }
        when (stage) {
            Stage.VERIFY -> {
                if (response.payload.firstOrNull() != 0.toByte()) fail(R.string.tanita_setup_required)
                else sendClock()
            }
            Stage.CLOCK -> send(TanitaLib.command(TanitaLib.RETRIEVE_DEVICE_INFO), Stage.INFO)
            Stage.INFO -> {
                profileSlot = selectedSlot.takeIf { it in 0..3 } ?: 0
                readProfile()
            }
            Stage.EXCHANGE -> {
                val profile = TanitaLib.parseProfile(response)
                if (profile == null || profile.unit != TanitaLib.UNIT_KG) {
                    fail(R.string.tanita_invalid_profile)
                    return
                }
                profiles[selectedSlot] = profile
                if (registering) send(TanitaLib.registerUuid(APPLICATION_ID), Stage.REGISTER)
                else startMeasurement()
            }
            Stage.REGISTER -> {
                if (response.payload.firstOrNull() != 0.toByte()) {
                    fail(R.string.tanita_setup_required)
                    return
                }
                settingsPutInt(KEY_REGISTER, 0)
                registering = false
                startMeasurement()
            }
            Stage.MEASURING -> send(TanitaLib.command(TanitaLib.RETRIEVE_MEASUREMENT_COUNT), Stage.COUNT)
            Stage.COUNT -> {
                val recordCount = response.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                if (recordCount == 0) {
                    fail(R.string.tanita_no_measurements)
                    return
                }
                // Descending order also remains valid if reading consumes or renumbers records.
                recordIndex = recordCount
                readMeasurement()
            }
            Stage.READING -> {
                if (response.payload.firstOrNull()?.toInt()?.and(0xff) != recordIndex) {
                    fail(R.string.tanita_invalid_response)
                    return
                }
                val reading = TanitaLib.parseMeasurement(response, profiles.getValue(selectedSlot).unit)
                val measuredAt = reading?.measuredAt
                if (reading == null || measuredAt == null) {
                    fail(R.string.tanita_invalid_response)
                    return
                }
                val measurement = ScaleMeasurement(userId = sessionUserId)
                measurement[MeasurementType.WEIGHT] = Kg(reading.weightKg)
                reading.bodyFatPercent?.let { measurement[MeasurementType.BODY_FAT] = Percent(it) }
                addCompositionMetrics(measurement, reading)
                measurement.dateTime = Date.from(measuredAt.atZone(ZoneId.systemDefault()).toInstant())
                enter(Stage.SAVING)
                val token = Any()
                pendingSaveToken = token
                publish(measurement, sessionUserId) { saved ->
                    synchronized(this) {
                        if (stage != Stage.SAVING || pendingSaveToken !== token) return@publish
                        pendingSaveToken = null
                        if (!saved) {
                            fail(R.string.tanita_save_failed)
                        } else if (runCatching { currentAppUser().id }.getOrNull() != sessionUserId) {
                            fail(R.string.tanita_user_changed)
                        } else if (recordIndex > 1) {
                            recordIndex--
                            readMeasurement()
                        } else {
                            enter(Stage.FINISHED)
                            requestDisconnect()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun sendClock() = send(TanitaLib.writeDate(LocalDateTime.now()), Stage.CLOCK)

    private fun readProfile() = send(TanitaLib.command(TanitaLib.RETRIEVE_USER_INFO, byteArrayOf(profileSlot.toByte())), Stage.PROFILES)

    private fun finishProfileRead() {
        if (selectedSlot in 0..3) {
            selectProfile(selectedSlot)
        } else if (profileSlot < 3) {
            profileSlot++
            readProfile()
        } else {
            if (profiles.isEmpty()) {
                fail(R.string.tanita_invalid_profile)
                return
            }
            enter(Stage.CHOICE, 120_000)
            val occupied = userLinks().filterKeys { it != sessionUserId }.values.toSet()
            val slots = profiles.keys.filter { it !in occupied }
            if (slots.isEmpty()) {
                fail(R.string.tanita_no_available_slot)
                return
            }
            val labels = slots.map { slot ->
                val name = profiles.getValue(slot).nickname
                if (name.isNullOrBlank()) resolveString(R.string.tanita_slot_label, slot + 1)
                else resolveString(R.string.tanita_named_slot_label, slot + 1, name)
            }.toTypedArray<CharSequence>()
            requestUserInteraction(UserInteractionType.CHOOSE_USER, Pair(labels, slots.toIntArray()))
            choiceJob = scope.launch {
                while (stage == Stage.CHOICE) {
                    delay(2_000)
                    if (stage == Stage.CHOICE) write(TanitaLib.command(TanitaLib.RETRIEVE_USER_INFO, byteArrayOf(slots.first().toByte())))
                }
            }
        }
    }

    override suspend fun onUserInteractionFeedback(interactionType: UserInteractionType, appUserId: Int, feedbackData: Any) {
        if (interactionType != UserInteractionType.CHOOSE_USER || stage != Stage.CHOICE) return
        if (appUserId != sessionUserId || currentAppUser().id != sessionUserId) {
            fail(R.string.tanita_user_changed)
            return
        }
        val slot = feedbackData as? Int ?: return
        if (slot !in profiles) return
        if (userLinks().any { (user, linkedSlot) -> user != sessionUserId && linkedSlot == slot }) return
        val choice = choiceJob
        choice?.cancelAndJoin()
        synchronized(this) {
            if (stage != Stage.CHOICE || choiceJob !== choice) return
            if (appUserId != sessionUserId || currentAppUser().id != sessionUserId) {
                fail(R.string.tanita_user_changed)
                return
            }
            val links = userLinks().toMutableMap()
            links[sessionUserId] = slot
            settingsPutString(KEY_LINKS, links.entries.joinToString(",") { "${it.key}:${it.value}" })
            selectProfile(slot)
        }
    }

    private fun selectProfile(slot: Int) {
        val profile = profiles[slot]
        if (profile == null) {
            fail(R.string.tanita_invalid_profile)
            return
        }
        if (profile.unit != TanitaLib.UNIT_KG) {
            fail(R.string.tanita_metric_only)
            return
        }
        selectedSlot = slot
        send(TanitaLib.selectExistingProfile(slot, profile, LocalDateTime.now()), Stage.EXCHANGE)
    }

    private fun addCompositionMetrics(measurement: ScaleMeasurement, reading: TanitaLib.Reading) {
        val values = reading.metrics
        values[TanitaLib.Metric.MUSCLE_MASS]?.takeIf { it > 0f && it <= reading.weightKg }?.let {
            // The common muscle column stores percent; retain reported kilograms separately too.
            measurement[MeasurementType.MUSCLE] = Percent(it * 100f / reading.weightKg)
        }
        values[TanitaLib.Metric.BONE_MASS]?.let { measurement[MeasurementType.BONE] = Kg(it) }
        values[TanitaLib.Metric.WATER_PERCENT]?.let { measurement[MeasurementType.WATER] = Percent(it) }
        values[TanitaLib.Metric.LEAN_MASS]?.let { measurement[MeasurementType.LBM] = Kg(it) }
        values[TanitaLib.Metric.VISCERAL_FAT]?.let { measurement[MeasurementType.VISCERAL_FAT] = it }
        values[TanitaLib.Metric.BMR]?.let { measurement[MeasurementType.BMR] = Kcal(it) }
        values[TanitaLib.Metric.BMI]?.let { measurement[MeasurementType.BMI] = it }
        values[TanitaLib.Metric.HEART_RATE]?.let { measurement[MeasurementType.HEART_RATE] = Bpm(it.toInt()) }
        for ((metric, key) in KG_METRICS) values[metric]?.let { measurement[key] = Kg(it) }
        for ((metric, key) in PERCENT_METRICS) values[metric]?.let { measurement[key] = Percent(it) }
        for ((metric, key) in INT_METRICS) values[metric]?.let { measurement[key] = it.toInt() }
        for ((metric, key) in OHM_METRICS) values[metric]?.let { measurement[key] = Ohm(it) }
        reading.physiqueRating?.let { measurement[PHYSIQUE_RATING] = it }
    }

    private fun startMeasurement() {
        userInfo(R.string.bt_info_waiting_for_measurement)
        send(TanitaLib.command(TanitaLib.MEASURE), Stage.MEASURING, 180_000)
    }

    private fun readMeasurement() = send(TanitaLib.command(TanitaLib.RETRIEVE_MEASUREMENT, byteArrayOf(recordIndex.toByte())), Stage.READING)

    private fun send(message: ByteArray, next: Stage, timeout: Long = 20_000) {
        enter(next, timeout)
        write(message)
    }

    private fun write(message: ByteArray) {
        val command = ((message[2].toInt() and 0xff) shl 8) or (message[3].toInt() and 0xff)
        logD("Tanita command ${command.toString(16)}: ${message.size} bytes")
        for (fragment in TanitaLib.fragment(message)) writeTo(SERVICE, WRITE, fragment, withResponse = false)
    }

    private fun enter(next: Stage, timeout: Long = 20_000) {
        timeoutJob?.cancel()
        stage = next
        logD("Tanita stage: $next")
        if (next !in setOf(Stage.IDLE, Stage.FINISHED)) {
            timeoutJob = scope.launch {
                delay(timeout)
                synchronized(this@TanitaRD545Handler) {
                    if (isActive) fail(R.string.tanita_timeout)
                }
            }
        }
    }

    @Synchronized
    private fun fail(message: Int, vararg args: Any) {
        pendingSaveToken = null
        enter(Stage.FINISHED)
        choiceJob?.cancel()
        userError(message, *args)
        requestDisconnect()
    }

    private fun userLinks(): Map<Int, Int> = settingsGetString(KEY_LINKS).orEmpty().split(',').mapNotNull {
        val pair = it.split(':')
        val user = pair.getOrNull(0)?.toIntOrNull()
        val slot = pair.getOrNull(1)?.toIntOrNull()
        if (pair.size == 2 && user != null && user > 0 && slot != null && slot in 0..3) user to slot else null
    }.toMap()

    @Synchronized
    override fun onDisconnected() {
        pendingSaveToken = null
        timeoutJob?.cancel()
        choiceJob?.cancel()
        assembler.reset()
        stage = Stage.IDLE
    }
}
