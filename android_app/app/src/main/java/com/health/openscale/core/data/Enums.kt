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

import androidx.annotation.StringRes
import com.health.openscale.R
import java.util.Locale

enum class SupportedLanguage(val code: String, val nativeDisplayName: String) {
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français");

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

enum class GenderType {
    MALE,
    FEMALE;

    fun isMale(): Boolean {
        return this == MALE}
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

enum class MeasurementTypeKey(
    val id: Int,
    @StringRes val localizedNameResId: Int // Added: Nullable resource ID for the name
) {
    WEIGHT(1, R.string.measurement_type_weight),
    BMI(2, R.string.measurement_type_bmi),
    BODY_FAT(3, R.string.measurement_type_body_fat),
    WATER(4, R.string.measurement_type_water),
    MUSCLE(5, R.string.measurement_type_muscle),
    LBM(6, R.string.measurement_type_lbm),
    BONE(7, R.string.measurement_type_bone),
    WAIST(8, R.string.measurement_type_waist),
    WHR(9, R.string.measurement_type_whr),
    WHTR(10, R.string.measurement_type_whtr),
    HIPS(11, R.string.measurement_type_hips),
    VISCERAL_FAT(12, R.string.measurement_type_visceral_fat),
    CHEST(13, R.string.measurement_type_chest),
    THIGH(14, R.string.measurement_type_thigh),
    BICEPS(15, R.string.measurement_type_biceps),
    NECK(16, R.string.measurement_type_neck),
    CALIPER_1(17, R.string.measurement_type_caliper1),
    CALIPER_2(18, R.string.measurement_type_caliper2),
    CALIPER_3(19, R.string.measurement_type_caliper3),
    CALIPER(20, R.string.measurement_type_fat_caliper),
    BMR(21, R.string.measurement_type_bmr),
    TDEE(22, R.string.measurement_type_tdee),
    CALORIES(23, R.string.measurement_type_calories),
    DATE(24, R.string.measurement_type_date),
    TIME(25, R.string.measurement_type_time),
    COMMENT(26, R.string.measurement_type_comment),
    CUSTOM(99, R.string.measurement_type_custom_default_name);
}


enum class UnitType(val displayName: String) {
    KG("kg"),
    PERCENT("%"),
    CM("cm"),
    KCAL("kcal"),
    NONE("")
}

enum class InputFieldType {
    FLOAT,
    INT,
    TEXT,
    DATE,
    TIME
}

enum class Trend {
    UP, DOWN, NONE, NOT_APPLICABLE
}

enum class TimeRangeFilter(val displayName: String) {
    ALL_DAYS("Alle Tage"),
    LAST_7_DAYS("Letzte 7 Tage"),
    LAST_30_DAYS("Letzte 30 Tage"),
    LAST_365_DAYS("Letzte 365 Tage")
}