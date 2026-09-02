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
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DeviceThermostat
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
import androidx.compose.material.icons.filled.Hive
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
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.utils.LocaleUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class SupportedLanguage(val code: String, val nativeDisplayName: String) {
    // Keep the list below alphabetically sorted
    // Native names from https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes
    //ARABIC("ar", "Arabic (العربية)"),
    //BENGALI("bn-BD", "Bengali (বাংলা)"),
    BASQUE("eu", "Basque (Euskara)"),
    BULGARIAN("bg", "Bulgarian (български език)"),
    CATALAN("ca", "Catalan (català)"),
    CHINESE_SIMPLIFIED("zh-CN", "Chinese (simplified; 中文 (汉语))"),
    CHINESE_TRADITIONAL("zh-TW", "Chinese (traditional; 中文 (繁體))"),
    //CROATIAN("hr", "Croatian (hrvatski jezik)"),
    //CZECH("cs", "Czech (čeština)"),
    //DANISH("da", "Danish (dansk)"),
    DUTCH("nl", "Dutch (Nederlands)"),
    ENGLISH("en", "English"),
    //ESPERANTO("eo", "Esperanto"),
    FINNISH("fi", "Finnish (suomi)"),
    FRENCH("fr", "French (français)"),
    //GALICIAN("gl", "Galician (Galego)"),
    GERMAN("de", "German (Deutsch)"),
    GREEK("el", "Greek (ελληνικά)"),
    HEBREW("iw", "Hebrew (עברית)"),
    HINDI("hi", "Hindi (हिन्दी)"),
    //HUNGARIAN("hu", "Hungarian (magyar)"),
    //INDONESIAN("id", "Indonesian (Bahasa Indonesia)"),
    ITALIAN("it", "Italian (Italiano)"),
    JAPANESE("ja", "Japanese (日本語)"),
    KOREAN("ko", "Korean (한국어)"),
    //LITHUANIAN("lt", "Lithuanian (lietuvių kalba)"),
    //NORWEGIAN_BOKMAL("nb", "Norwegian Bokmål (Norsk)"),
    POLISH("pl", "Polish (język polski)"),
    PORTUGUESE("pt", "Portuguese (Português)"),
    PORTUGUESE_BRAZIL("pt-BR", "Portuguese (Brazil; Português)"),
    PERSIAN("fa", "Persian (فارسی)"),
    ROMANIAN("ro", "Romanian (Română)"),
    RUSSIAN("ru", "Russian (русский)"),
    //SLOVAK("sk", "Slovak (Slovenčina)"),
    SLOVENIAN("sl", "Slovenian (Slovenski Jezik)"),
    SPANISH("es", "Spanish (Español)"),
    SWEDISH("sv", "Swedish (Svenska)"),
    //TAMIL("ta", "Tamil (தமிழ்)"),
    TURKISH("tr", "Turkish (Türkçe)");
    //UKRAINIAN("uk", "Ukrainian (Українська)"),
    //VIETNAMESE("vi", "Vietnamese (Tiếng Việt)");

    fun toLocale(): Locale {
        val parts = code.split("-")
        val language = parts.getOrNull(0) ?: ""
        val region = parts.getOrNull(1)?.replace("r", "") ?: ""

        if (language.isBlank()) {
            return Locale.getDefault()
        }

        return Locale.Builder()
            .setLanguage(language)
            .setRegion(region)
            .build()
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

enum class GenderType(@param:StringRes val displayNameResId: Int) {
    MALE(R.string.gender_male),
    FEMALE(R.string.gender_female);

    fun isMale(): Boolean {
        return this == MALE}

    fun getDisplayName(context: Context): String {
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
}

enum class Limb(@param:StringRes val displayNameResId: Int) {
    LEFT_ARM(R.string.amputation_left_arm),
    RIGHT_ARM(R.string.amputation_right_arm),
    LEFT_LEG(R.string.amputation_left_leg),
    RIGHT_LEG(R.string.amputation_right_leg)
}

enum class AmputationPart(
    @param:StringRes val displayNameResId: Int,
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
    }

    fun toInt(): Int {
        when (this) {
            CM -> return 0
            INCH -> return 1
        }
    }
}

sealed class IconResource {
    data class PainterResource(@param:DrawableRes val id: Int) : IconResource()
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
    IC_M_PERSON(IconResource.VectorResource(Icons.Filled.Person)),
    IC_M_WATER_DROP(IconResource.VectorResource(Icons.Filled.WaterDrop)),
    IC_M_SCATTER_PLOT(IconResource.VectorResource(Icons.Filled.ScatterPlot)),
    IC_M_BUBBLE_CHART(IconResource.VectorResource(Icons.Filled.BubbleChart)),
    IC_M_HIVE(IconResource.VectorResource(Icons.Filled.Hive));
}



enum class UnitType(val displayName: String) {
    KG("kg"),
    LB("lb"),
    ST("st"),
    PERCENT("%"),
    CM("cm"),
    INCH("in"),
    KCAL("kcal"),
    BPM("bpm"),
    OHM("Ω"),
    NONE("");

    fun isWeightUnit(): Boolean {
        return this == KG || this == LB || this == ST
    }

    /**
     * The units a value in this unit can be converted into without changing what it means.
     * PERCENT is alone in its family on purpose: turning a percentage into a mass needs to
     * know what it is a percentage *of*.
     */
    fun convertibleUnits(): List<UnitType> = when {
        isWeightUnit() -> listOf(KG, LB, ST)
        this == CM || this == INCH -> listOf(CM, INCH)
        else -> listOf(this)
    }

    fun toWeightUnit(): WeightUnit {
        return when (this) {
            LB -> WeightUnit.LB
            ST -> WeightUnit.ST
            else -> WeightUnit.KG
        }
    }
}

/**
 * A raw scale value whose unit is part of its type. Every float-valued builtin
 * [MeasurementType.Key] binds one of these ([Kg], [Percent], [Cm], [Kcal], [Ohm]), so a
 * handler cannot set a value without stating the unit it is in — `m[WEIGHT] = Kg(72.5f)`
 * compiles, `m[WEIGHT] = 72.5f` and `m[WEIGHT] = Percent(20f)` do not. The BLE connector
 * unwraps centrally and converts to the unit the user configured.
 *
 * The companions carry the conversions a handler needs when its scale reports something
 * else (`Kg.from(native, user.scaleUnit)`, `Cm.fromInch(...)`), so the right way is also
 * the discoverable way. `toString()` prints just the number to keep log lines readable.
 */
sealed interface UnitValue {
    val value: Float
}

/** A mass in kilograms — the delivery unit for WEIGHT, LBM, BONE and `deviceKg` keys. */
@JvmInline
value class Kg(override val value: Float) : UnitValue {
    override fun toString(): String = value.toString()

    companion object {
        /** Converts from the unit the scale natively reports in (kg/lb/st). */
        fun from(value: Float, unit: WeightUnit): Kg =
            Kg(com.health.openscale.core.utils.ConverterUtils.toKilogram(value, unit))

        fun fromLb(lb: Float): Kg = from(lb, WeightUnit.LB)

        /** Chinese catty (市斤), used natively by some Asian scales. */
        fun fromJin(jin: Float): Kg = Kg(jin * 0.5f)
    }
}

/** A share of body weight in percent — BODY_FAT, WATER, MUSCLE, PROTEIN, `devicePercent`. */
@JvmInline
value class Percent(override val value: Float) : UnitValue {
    override fun toString(): String = value.toString()
}

/** A length in centimeters — the circumference and caliper keys. */
@JvmInline
value class Cm(override val value: Float) : UnitValue {
    override fun toString(): String = value.toString()

    companion object {
        fun fromInch(inch: Float): Cm = Cm(inch * 2.54f)
    }
}

/** An energy in kilocalories — BMR (and CALORIES). */
@JvmInline
value class Kcal(override val value: Float) : UnitValue {
    override fun toString(): String = value.toString()
}

/** An electrical resistance in ohms — the impedance keys. */
@JvmInline
value class Ohm(override val value: Float) : UnitValue {
    override fun toString(): String = value.toString()
}

/** A heart rate in whole beats per minute. */
@JvmInline
value class Bpm(val value: Int) {
    override fun toString(): String = value.toString()
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

enum class TimeRangeFilter(@param:StringRes val displayNameResId: Int) {
    ALL_DAYS(R.string.time_range_all_days),
    LAST_7_DAYS(R.string.time_range_last_7_days),
    LAST_30_DAYS(R.string.time_range_last_30_days),
    LAST_365_DAYS(R.string.time_range_last_365_days),
    CUSTOM(R.string.time_range_custom);

    fun getDisplayName(context: Context): String {
        return context.getString(displayNameResId)
    }
}

enum class AggregationLevel(@param:StringRes val displayNameResId: Int) {
    NONE(R.string.aggregation_level_none),
    DAY(R.string.aggregation_level_day),
    WEEK(R.string.aggregation_level_week),
    MONTH(R.string.aggregation_level_month),
    YEAR(R.string.aggregation_level_year);

    fun getDisplayName(context: Context): String {
        return context.getString(displayNameResId)
    }


    /**
     * Returns the inclusive start and exclusive end of the period containing [timestamp]
     * as epoch milliseconds.
     *
     * For [NONE] and [DAY] the period is a single calendar day.
     *
     * [weekFields] must be the same rule that [periodKey] and [periodLabel] are given —
     * a [WEEK] period bounded by a different first-day-of-week than the one used to group
     * measurements would not contain its own members (see issue #1454).
     */
    fun periodBounds(
        timestamp: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        weekFields: WeekFields = LocaleUtils.systemWeekFields(),
    ): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val (start, end) = when (this) {
            NONE,
            DAY   -> date to date.plusDays(1)
            WEEK  -> {
                val first = date.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))
                first to first.plusWeeks(1)
            }
            MONTH -> { val f = date.withDayOfMonth(1); f to f.plusMonths(1) }
            YEAR  -> { val f = date.withDayOfYear(1); f to f.plusYears(1) }
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
                end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Returns a key identifying the period containing [timestamp].
     * Suitable as a LazyColumn item key or Map key.
     *
     * Examples: "2025-04-07" (DAY/NONE), "2025-W15" (WEEK), "2025-4" (MONTH), "2025" (YEAR).
     *
     * Stable for a given [zone] and [weekFields]; the [WEEK] key depends on the week rule and
     * therefore changes if the device region does. Nothing persists it — it lives in memory for
     * one aggregation pass — and a locale change recreates the activity, so that is harmless.
     * It is not a durable identifier and must not be written to disk or exported.
     */
    fun periodKey(
        timestamp: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        weekFields: WeekFields = LocaleUtils.systemWeekFields(),
    ): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when (this) {
            NONE,
            DAY   -> date.toString()
            WEEK  -> "${date.get(weekFields.weekBasedYear())}-W${date.get(weekFields.weekOfWeekBasedYear())}"
            MONTH -> "${date.year}-${date.monthValue}"
            YEAR  -> "${date.year}"
        }
    }

    /**
     * Returns a human-readable label for the period containing [timestamp].
     *
     * [locale] only drives presentation — month names, date style. The week *identity* comes
     * from [weekFields], the same rule [periodKey] and [periodBounds] use, so the number shown
     * always belongs to the period the row actually covers.
     *
     * @param calendarWeekAbbrev Localised abbreviation for "calendar week" (e.g. "CW" / "KW").
     *                           Only used for [WEEK].
     * @param short              Renders [MONTH] as "Apr 2025" instead of "April 2025", for
     *                           narrow layouts such as the table's period column.
     */
    fun periodLabel(
        timestamp: Long,
        calendarWeekAbbrev: String,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
        weekFields: WeekFields = LocaleUtils.systemWeekFields(),
        short: Boolean = false,
    ): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when (this) {
            NONE,
            DAY   -> date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            )
            WEEK  ->
                "${date.get(weekFields.weekBasedYear())} – " +
                    "$calendarWeekAbbrev ${date.get(weekFields.weekOfWeekBasedYear())}"
            MONTH -> date.format(
                DateTimeFormatter.ofPattern(if (short) "MMM yyyy" else "MMMM yyyy", locale)
            )
            YEAR  -> date.year.toString()
        }
    }

}

enum class SmoothingAlgorithm(@param:StringRes val displayNameResId: Int) {
    NONE(R.string.smoothing_algorithm_none),
    SIMPLE_MOVING_AVERAGE(R.string.smoothing_algorithm_sma),
    EXPONENTIAL_SMOOTHING(R.string.smoothing_algorithm_ses);

    fun getDisplayName(context: Context): String {
        return context.getString(displayNameResId)
    }
}

enum class PolynomialDegree(val degree: Int, @param:StringRes val displayNameRes: Int) {
    LINEAR(1, R.string.poly_degree_linear),
    QUADRATIC(2, R.string.poly_degree_quadratic),
    CUBIC(3, R.string.poly_degree_cubic);

    fun getDisplayName(context: Context): String {
        return context.getString(displayNameRes)
    }

    companion object {
        fun fromDegree(degree: Int): PolynomialDegree {
            return entries.find { it.degree == degree } ?: LINEAR
        }
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

/**
 * Reason why the last automatic backup attempt failed.
 *
 * Persisted by name, so entries must not be renamed without a migration.
 */
enum class AutoBackupError {
    /** The configured folder is gone, or the persisted URI permission was revoked. */
    LOCATION_INACCESSIBLE,
    /** The folder is writable, but the backup file itself could not be created. */
    FILE_CREATION_FAILED,
    /** Writing the database into the backup file failed. */
    WRITE_FAILED;

    fun getDisplayName(context: Context): String {
        return when (this) {
            LOCATION_INACCESSIBLE -> context.getString(R.string.settings_backup_error_location_inaccessible)
            FILE_CREATION_FAILED -> context.getString(R.string.settings_backup_error_file_creation_failed)
            WRITE_FAILED -> context.getString(R.string.settings_backup_error_write_failed)
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
    GALLAGHER_2000_ASIAN,
    US_NAVY;

    fun displayName(context: Context) = when (this) {
        OFF -> context.getString(R.string.formula_off)
        DEURENBERG_1991 -> context.getString(R.string.formula_bf_deurenberg_1991)
        DEURENBERG_1992 -> context.getString(R.string.formula_bf_deurenberg_1992)
        EDDY_1976 -> context.getString(R.string.formula_bf_eddy_1976)
        GALLAGHER_2000_NON_ASIAN -> context.getString(R.string.formula_bf_gallagher_2000_non_asian)
        GALLAGHER_2000_ASIAN -> context.getString(R.string.formula_bf_gallagher_2000_asian)
        US_NAVY -> context.getString(R.string.formula_bf_us_navy)
    }

    fun shortDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_short)
        DEURENBERG_1991 -> ctx.getString(R.string.bf_deurenberg_1991_short)
        DEURENBERG_1992 -> ctx.getString(R.string.bf_deurenberg_1992_short)
        EDDY_1976 -> ctx.getString(R.string.bf_eddy_1976_short)
        GALLAGHER_2000_NON_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_non_asian_short)
        GALLAGHER_2000_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_asian_short)
        US_NAVY -> ctx.getString(R.string.bf_us_navy_short)
    }
    fun longDescription(ctx: Context) = when (this) {
        OFF -> ctx.getString(R.string.formula_desc_off_long)
        DEURENBERG_1991 -> ctx.getString(R.string.bf_deurenberg_1991_long)
        DEURENBERG_1992 -> ctx.getString(R.string.bf_deurenberg_1992_long)
        EDDY_1976 -> ctx.getString(R.string.bf_eddy_1976_long)
        GALLAGHER_2000_NON_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_non_asian_long)
        GALLAGHER_2000_ASIAN -> ctx.getString(R.string.bf_gallagher_2000_asian_long)
        US_NAVY -> ctx.getString(R.string.bf_us_navy_long)
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
