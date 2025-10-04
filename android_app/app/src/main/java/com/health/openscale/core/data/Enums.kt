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
package com.health.openscale.core.data

import android.content.Context
import android.text.InputType
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Face3
import androidx.compose.material.icons.filled.Face6
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import java.util.Locale

enum class SupportedLanguage(val code: String, val nativeDisplayName: String) {
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch");

    fun toLocale(): Locale {
        return Locale.Builder().setLanguage(code).build()
    }

    companion object {
        fun fromCode(code: String?): SupportedLanguage? {
            return entries.find { it.code == code }
        }

        fun getDefault(): SupportedLanguage {
            val systemLangCode = Locale.getDefault().language
            return fromCode(systemLangCode) ?: ENGLISH
        }
    }
}

enum class GenderType(@StringRes val displayNameResId: Int) {
    MALE(R.string.gender_male),
    FEMALE(R.string.gender_female);

    fun isMale(): Boolean {
        return this == MALE}

    fun getDisplayName(context: android.content.Context): String {
        return context.getString(displayNameResId)
    }
}

enum class ActivityLevel {
    SEDENTARY, MILD, MODERATE, HEAVY, EXTREME;

    fun toInt(): Int {
        when (this) {
            SEDENTARY -> return 0
            MILD -> return 1
            MODERATE -> return 2
            HEAVY -> return 3
            EXTREME -> return 4
        }
    }

    companion object {
        @JvmStatic
        fun fromInt(unit: Int): ActivityLevel {
            when (unit) {
                0 -> return SEDENTARY
                1 -> return MILD
                2 -> return MODERATE
                3 -> return HEAVY
                4 -> return EXTREME
            }
            return SEDENTARY
        }
    }
}

enum class WeightUnit {
    KG, LB, ST;

    override fun toString(): String {
        when (this) {
            LB -> return "lb"
            ST -> return "st"
            KG -> return "kg"
        }

    }

    fun toInt(): Int {
        when (this) {
            LB -> return 1
            ST -> return 2
            KG -> return 0
        }
    }

    companion object {
        fun fromInt(unit: Int): WeightUnit {
            when (unit) {
                1 -> return LB
                2 -> return ST
            }
            return KG
        }
    }
}

enum class Limb(@StringRes val displayNameResId: Int) {
    LEFT_ARM(R.string.amputation_left_arm),
    RIGHT_ARM(R.string.amputation_right_arm),
    LEFT_LEG(R.string.amputation_left_leg),
    RIGHT_LEG(R.string.amputation_right_leg)
}

enum class AmputationPart(
    @StringRes val displayNameResId: Int,
    val correctionValue: Float
) {
    HAND(R.string.amputation_hand, 0.8f),
    FOREARM(R.string.amputation_forearm, 3.0f),
    FULL_ARM(R.string.amputation_full_arm, 11.5f),

    FOOT(R.string.amputation_foot, 1.8f),
    LOWER_LEG(R.string.amputation_lower_leg, 7.1f),
    FULL_LEG(R.string.amputation_full_leg, 18.6f);

    companion object {
        @Composable
        fun toSummaryString(amputations: Map<Limb, AmputationPart>): String {
            if (amputations.isEmpty()) {
                return stringResource(R.string.amputation_none)
            }

            val partSummaries = amputations.map { (limb, part) ->
                val limbName = stringResource(limb.displayNameResId)
                val partName = stringResource(part.displayNameResId)
                "$limbName ($partName)"
            }

            return partSummaries.joinToString(", ")
        }
    }
}

enum class MeasureUnit {
    CM, INCH;

    override fun toString(): String {
        when (this) {
            CM -> return "cm"
            INCH -> return "in"
        }

        return ""
    }

    fun toInt(): Int {
        when (this) {
            CM -> return 0
            INCH -> return 1
        }

        return 0
    }

    companion object {
        fun fromInt(unit: Int): MeasureUnit {
            when (unit) {
                0 -> return CM
                1 -> return INCH
            }
            return CM
        }
    }
}

sealed class IconResource {
    data class PainterResource(@DrawableRes val id: Int) : IconResource()
    data class VectorResource(val imageVector: ImageVector) : IconResource()
}

enum class UserIcon(val resource: IconResource) {
    IC_DEFAULT(IconResource.VectorResource(Icons.Filled.AccountCircle)),
    IC_MALE(IconResource.VectorResource(Icons.Filled.Face6)),
    IC_FEMALE(IconResource.VectorResource(Icons.Filled.Face3)),
    IC_CHILD(IconResource.VectorResource(Icons.Filled.ChildCare)),
    IC_HAPPY_FACE(IconResource.VectorResource(Icons.Filled.SentimentSatisfied)),
    IC_HAPPY_FACE_MOOD(IconResource.VectorResource(Icons.Filled.Mood)),
    IC_HAPPY_FACE_ALT(IconResource.VectorResource(Icons.Filled.SentimentVerySatisfied))

}

enum class MeasurementTypeIcon(val resource: IconResource) {
    IC_DEFAULT(IconResource.VectorResource(Icons.Filled.QuestionMark)),
    IC_WEIGHT(IconResource.PainterResource(R.drawable.ic_weight)),
    IC_BMI(IconResource.PainterResource(R.drawable.ic_bmi)),
    IC_BODY_FAT(IconResource.PainterResource(R.drawable.ic_fat)),
    IC_WATER(IconResource.PainterResource(R.drawable.ic_water)),
    IC_MUSCLE(IconResource.PainterResource(R.drawable.ic_muscle)),
    IC_LBM(IconResource.PainterResource(R.drawable.ic_lbm)),
    IC_BONE(IconResource.PainterResource(R.drawable.ic_bone)),
    IC_WAIST(IconResource.PainterResource(R.drawable.ic_waist)),
    IC_WHR(IconResource.PainterResource(R.drawable.ic_whr)),
    IC_WHTR(IconResource.PainterResource(R.drawable.ic_whtr)),
    IC_HIPS(IconResource.PainterResource(R.drawable.ic_hip)),
    IC_VISCERAL_FAT(IconResource.PainterResource(R.drawable.ic_visceral_fat)),
    IC_CHEST(IconResource.PainterResource(R.drawable.ic_chest)),
    IC_THIGH(IconResource.PainterResource(R.drawable.ic_thigh)),
    IC_BICEPS(IconResource.PainterResource(R.drawable.ic_biceps)),
    IC_NECK(IconResource.PainterResource(R.drawable.ic_neck)),
    IC_CALIPER1(IconResource.PainterResource(R.drawable.ic_caliper1)),
    IC_CALIPER2(IconResource.PainterResource(R.drawable.ic_caliper2)),
    IC_CALIPER3(IconResource.PainterResource(R.drawable.ic_caliper3)),
    IC_FAT_CALIPER(IconResource.PainterResource(R.drawable.ic_fat_caliper)),
    IC_BMR(IconResource.PainterResource(R.drawable.ic_bmr)),
    IC_TDEE(IconResource.PainterResource(R.drawable.ic_tdee)),
    IC_CALORIES(IconResource.PainterResource(R.drawable.ic_calories)),
    IC_COMMENT(IconResource.PainterResource(R.drawable.ic_comment)),
    IC_TIME(IconResource.PainterResource(R.drawable.ic_time)),
    IC_DATE(IconResource.PainterResource(R.drawable.ic_date)),
    IC_USER(IconResource.PainterResource(R.drawable.ic_user)),

    IC_M_HEIGHT(IconResource.VectorResource(Icons.Filled.Height)),
    IC_M_HEART_RATE(IconResource.VectorResource(Icons.Filled.Favorite)),
    IC_M_STEPS(IconResource.VectorResource(Icons.AutoMirrored.Filled.DirectionsWalk)),
    IC_M_SLEEP(IconResource.VectorResource(Icons.Filled.NightsStay)),
    IC_M_WORKOUT(IconResource.VectorResource(Icons.Filled.FitnessCenter)),
    IC_M_WATER_INTAKE(IconResource.VectorResource(Icons.Filled.LocalDrink)),
    IC_M_GOAL(IconResource.VectorResource(Icons.Filled.Flag)),
    IC_M_NOTES(IconResource.VectorResource(Icons.Filled.EditNote)),
    IC_M_TEMPERATURE(IconResource.VectorResource(Icons.Filled.DeviceThermostat)),
    IC_M_BLOOD_PRESSURE(IconResource.VectorResource(Icons.Filled.Bloodtype)),
    IC_M_GLUCOSE(IconResource.VectorResource(Icons.Filled.Bloodtype)),
    IC_M_TREND_UP(IconResource.VectorResource(Icons.AutoMirrored.Filled.TrendingUp)),
    IC_M_TREND_DOWN(IconResource.VectorResource(Icons.AutoMirrored.Filled.TrendingDown)),
    IC_M_TREND_FLAT(IconResource.VectorResource(Icons.AutoMirrored.Filled.TrendingFlat)),
    IC_M_CALENDAR(IconResource.VectorResource(Icons.Filled.CalendarMonth)),
    IC_M_CLOCK(IconResource.VectorResource(Icons.Filled.Schedule)),
    IC_M_TIMER(IconResource.VectorResource(Icons.Filled.Timer)),
    IC_M_INFO(IconResource.VectorResource(Icons.Filled.Info)),
    IC_M_HELP(IconResource.VectorResource(Icons.AutoMirrored.Filled.HelpOutline)),
    IC_M_SETTINGS(IconResource.VectorResource(Icons.Filled.Settings)),
    IC_M_ADD(IconResource.VectorResource(Icons.Filled.AddCircleOutline)),
    IC_M_REMOVE(IconResource.VectorResource(Icons.Filled.RemoveCircleOutline)),
    IC_M_DONE(IconResource.VectorResource(Icons.Filled.Done)),
    IC_M_CHECK_CIRCLE(IconResource.VectorResource(Icons.Filled.CheckCircleOutline)),
    IC_M_WARNING(IconResource.VectorResource(Icons.Filled.WarningAmber)),
    IC_M_ANALYTICS(IconResource.VectorResource(Icons.Filled.Analytics)),
    IC_M_CHART_BAR(IconResource.VectorResource(Icons.AutoMirrored.Filled.ShowChart)),
    IC_M_CHART_LINE(IconResource.VectorResource(Icons.Filled.StackedLineChart)),
    IC_M_CHART_PIE(IconResource.VectorResource(Icons.Filled.PieChart)),
    IC_M_NUTRITION(IconResource.VectorResource(Icons.Filled.LocalDining)),
    IC_M_PROTEIN(IconResource.VectorResource(Icons.Filled.Egg)),
    IC_M_CARBS(IconResource.VectorResource(Icons.Filled.Grain)),
    IC_M_FAT_FOOD(IconResource.VectorResource(Icons.Filled.OilBarrel)),
    IC_M_SPEED(IconResource.VectorResource(Icons.Filled.Speed)),
    IC_M_DISTANCE(IconResource.VectorResource(Icons.Filled.SquareFoot)),
    IC_M_MOOD(IconResource.VectorResource(Icons.Filled.SentimentSatisfiedAlt)),
    IC_M_MEDICATION(IconResource.VectorResource(Icons.Filled.Medication)),
    IC_M_LIST(IconResource.VectorResource(Icons.AutoMirrored.Filled.List)),
    IC_M_LABEL(IconResource.VectorResource(Icons.AutoMirrored.Filled.Label)),
    IC_M_PERSON(IconResource.VectorResource(Icons.Filled.Person));
}

enum class MeasurementTypeKey(
    val id: Int,
    @StringRes val localizedNameResId: Int,
    val allowedUnitTypes: List<UnitType>,
    val allowedInputType: List<InputFieldType>
) {
    WEIGHT(1, R.string.measurement_type_weight, listOf(UnitType.KG, UnitType.LB, UnitType.ST), listOf(InputFieldType.FLOAT)),
    BMI(2, R.string.measurement_type_bmi, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
    BODY_FAT(3, R.string.measurement_type_body_fat, listOf(UnitType.PERCENT, UnitType.KG, UnitType.LB, UnitType.ST), listOf(InputFieldType.FLOAT)),
    WATER(4, R.string.measurement_type_water, listOf(UnitType.PERCENT, UnitType.KG, UnitType.LB, UnitType.ST), listOf(InputFieldType.FLOAT)),
    MUSCLE(5, R.string.measurement_type_muscle, listOf(UnitType.PERCENT, UnitType.KG, UnitType.LB, UnitType.ST), listOf(InputFieldType.FLOAT)),
    LBM(6, R.string.measurement_type_lbm, listOf(UnitType.KG, UnitType.LB, UnitType.ST), listOf(InputFieldType.FLOAT)),
    BONE(7, R.string.measurement_type_bone, listOf(UnitType.KG, UnitType.LB), listOf(InputFieldType.FLOAT)),
    WAIST(8, R.string.measurement_type_waist, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    WHR(9, R.string.measurement_type_whr, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
    WHTR(10, R.string.measurement_type_whtr, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
    HIPS(11, R.string.measurement_type_hips, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    VISCERAL_FAT(12, R.string.measurement_type_visceral_fat, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
    CHEST(13, R.string.measurement_type_chest, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    THIGH(14, R.string.measurement_type_thigh, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    BICEPS(15, R.string.measurement_type_biceps, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    NECK(16, R.string.measurement_type_neck, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    CALIPER_1(17, R.string.measurement_type_caliper1, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    CALIPER_2(18, R.string.measurement_type_caliper2, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    CALIPER_3(19, R.string.measurement_type_caliper3, listOf(UnitType.CM, UnitType.INCH), listOf(InputFieldType.FLOAT)),
    CALIPER(20, R.string.measurement_type_fat_caliper, listOf(UnitType.PERCENT), listOf(InputFieldType.FLOAT)),
    BMR(21, R.string.measurement_type_bmr, listOf(UnitType.KCAL), listOf(InputFieldType.FLOAT)),
    TDEE(22, R.string.measurement_type_tdee, listOf(UnitType.KCAL), listOf(InputFieldType.FLOAT)),
    CALORIES(23, R.string.measurement_type_calories, listOf(UnitType.KCAL), listOf(InputFieldType.FLOAT)),
    DATE(24, R.string.measurement_type_date, listOf(UnitType.NONE), listOf(InputFieldType.DATE)),
    TIME(25, R.string.measurement_type_time, listOf(UnitType.NONE), listOf(InputFieldType.TIME)),
    COMMENT(26, R.string.measurement_type_comment, listOf(UnitType.NONE), listOf(InputFieldType.TEXT)),
    USER(27, R.string.measurement_type_user, listOf(UnitType.NONE), listOf(InputFieldType.USER)),
    CUSTOM(99, R.string.measurement_type_custom_default_name, UnitType.entries.toList(), listOf(InputFieldType.FLOAT, InputFieldType.INT, InputFieldType.TEXT, InputFieldType.DATE, InputFieldType.TIME));
}


enum class UnitType(val displayName: String) {
    KG("kg"),
    LB("lb"),
    ST("st"),
    PERCENT("%"),
    CM("cm"),
    INCH("in"),
    KCAL("kcal"),
    NONE("");

    fun isWeightUnit(): Boolean {
        return this == KG || this == LB || this == ST
    }
}

enum class InputFieldType {
    FLOAT,
    INT,
    TEXT,
    DATE,
    TIME,
    USER
}

enum class Trend {
    UP, DOWN, NONE, NOT_APPLICABLE
}

enum class TimeRangeFilter(@StringRes val displayNameResId: Int) {
    ALL_DAYS(R.string.time_range_all_days),
    LAST_7_DAYS(R.string.time_range_last_7_days),
    LAST_30_DAYS(R.string.time_range_last_30_days),
    LAST_365_DAYS(R.string.time_range_last_365_days),
    CUSTOM(R.string.time_range_custom);

    fun getDisplayName(context: android.content.Context): String {
        return context.getString(displayNameResId)
    }
}

enum class SmoothingAlgorithm(@StringRes val displayNameResId: Int) {
    NONE(R.string.smoothing_algorithm_none),
    SIMPLE_MOVING_AVERAGE(R.string.smoothing_algorithm_sma),
    EXPONENTIAL_SMOOTHING(R.string.smoothing_algorithm_ses);

    fun getDisplayName(context: android.content.Context): String {
        return context.getString(displayNameResId)
    }
}

enum class BackupInterval {
    DAILY,
    WEEKLY,
    MONTHLY;

    fun getDisplayName(context: Context): String {
         return when (this) {
             DAILY -> context.getString(R.string.interval_daily)
             WEEKLY -> context.getString(R.string.interval_weekly)
             MONTHLY -> context.getString(R.string.interval_monthly)
         }
    }
}

enum class EvaluationState {
    LOW,
    NORMAL,
    HIGH,
    UNDEFINED;

    fun toColor(): Color = when (this) {
        LOW       -> Color(0xFFEF5350) // Red 400
        NORMAL    -> Color(0xFF66BB6A) // Green 400
        HIGH      -> Color(0xFFFFA726) // Orange 400
        UNDEFINED -> Color(0xFFBDBDBD) // Grey 400
    }
}

/**
 * High-level connection state for a Bluetooth scale.
 */
enum class ConnectionStatus {
    /** No BT flow started yet. */
    NONE,
    /** Ready but not connected; idle state after init or after a clean stop. */
    IDLE,
    BROADCAST_LISTENING,
    /** Explicitly not connected (after a disconnect or failure). */
    DISCONNECTED,
    /** Connecting handshake is in progress. */
    CONNECTING,
    /** Fully connected and ready to exchange data. */
    CONNECTED,
    /** A disconnect sequence is in progress. */
    DISCONNECTING,
    /** A connection attempt failed or connection broke unexpectedly. */
    FAILED
}

enum class BodyFatFormulaOption {
    OFF,
    DEURENBERG_1991,
    DEURENBERG_1992,
    EDDY_1976,
    GALLAGHER_2000_NON_ASIAN,
    GALLAGHER_2000_ASIAN;

    fun displayName(context: Context) = when (this) {
        OFF -> context.getString(R.string.formula_off)
        DEURENBERG_1991 -> context.getString(R.string.formula_bf_deurenberg_1991)
        DEURENBERG_1992 -> context.getString(R.string.formula_bf_deurenberg_1992)
        EDDY_1976 -> context.getString(R.string.formula_bf_eddy_1976)
        GALLAGHER_2000_NON_ASIAN -> context.getString(R.string.formula_bf_gallagher_2000_non_asian)
        GALLAGHER_2000_ASIAN -> context.getString(R.string.formula_bf_gallagher_2000_asian)
    }

    fun shortDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_short)
        DEURENBERG_1991 -> ctx.getString(R.string.bf_deurenberg_1991_short)
        DEURENBERG_1992 -> ctx.getString(R.string.bf_deurenberg_1992_short)
        EDDY_1976 -> ctx.getString(R.string.bf_eddy_1976_short)
        GALLAGHER_2000_NON_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_non_asian_short)
        GALLAGHER_2000_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_asian_short)
    }
    fun longDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_long)
        DEURENBERG_1991 -> ctx.getString(R.string.bf_deurenberg_1991_long)
        DEURENBERG_1992 -> ctx.getString(R.string.bf_deurenberg_1992_long)
        EDDY_1976 -> ctx.getString(R.string.bf_eddy_1976_long)
        GALLAGHER_2000_NON_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_non_asian_long)
        GALLAGHER_2000_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_asian_long)
    }
}

enum class BodyWaterFormulaOption {
    OFF,
    BEHNKE_1963,
    DELWAIDE_CRENIER_1973,
    HUME_WEYERS_1971,
    LEE_SONG_KIM_2001;

    fun displayName(context: Context) = when (this) {
        OFF -> context.getString(R.string.formula_off)
        BEHNKE_1963 -> context.getString(R.string.formula_bw_behnke_1963)
        DELWAIDE_CRENIER_1973 -> context.getString(R.string.formula_bw_delwaide_crenier_1973)
        HUME_WEYERS_1971 -> context.getString(R.string.formula_bw_hume_weyers_1971)
        LEE_SONG_KIM_2001 -> context.getString(R.string.formula_bw_lee_song_kim_2001)
    }

    fun shortDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_short)
        BEHNKE_1963 -> ctx.getString(R.string.bw_behnke_1963_short)
        DELWAIDE_CRENIER_1973 -> ctx.getString(R.string.bw_delwaide_crenier_1973_short)
        HUME_WEYERS_1971 -> ctx.getString(R.string.bw_hume_weyers_1971_short)
        LEE_SONG_KIM_2001 -> ctx.getString(R.string.bw_lee_song_kim_2001_short)
    }
    fun longDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_long)
        BEHNKE_1963 -> ctx.getString(R.string.bw_behnke_1963_long)
        DELWAIDE_CRENIER_1973 -> ctx.getString(R.string.bw_delwaide_crenier_1973_long)
        HUME_WEYERS_1971 -> ctx.getString(R.string.bw_hume_weyers_1971_long)
        LEE_SONG_KIM_2001 -> ctx.getString(R.string.bw_lee_song_kim_2001_long)
    }
}

enum class LbmFormulaOption {
    OFF,
    BOER_1984,
    HUME_1966,
    WEIGHT_MINUS_BODY_FAT;

    fun displayName(context: Context) = when (this) {
        OFF -> context.getString(R.string.formula_off)
        BOER_1984 -> context.getString(R.string.formula_lbm_boer_1984)
        HUME_1966 -> context.getString(R.string.formula_lbm_hume_1966)
        WEIGHT_MINUS_BODY_FAT -> context.getString(R.string.formula_lbm_weight_minus_body_fat)
    }

    fun shortDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_short)
        BOER_1984 -> ctx.getString(R.string.lbm_boer_1984_short)
        HUME_1966 -> ctx.getString(R.string.lbm_hume_1966_short)
        WEIGHT_MINUS_BODY_FAT -> ctx.getString(R.string.lbm_weight_minus_bf_short)
    }
    fun longDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_long)
        BOER_1984 -> ctx.getString(R.string.lbm_boer_1984_long)
        HUME_1966 -> ctx.getString(R.string.lbm_hume_1966_long)
        WEIGHT_MINUS_BODY_FAT -> ctx.getString(R.string.lbm_weight_minus_bf_long)
    }
}
