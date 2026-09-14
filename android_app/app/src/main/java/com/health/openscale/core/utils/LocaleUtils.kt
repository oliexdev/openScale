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
package com.health.openscale.core.utils

import android.app.LocaleManager
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.content.res.Configuration
import android.content.res.Resources
import android.text.format.DateFormat
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import com.health.openscale.core.data.SupportedLanguage
import com.health.openscale.core.data.UnitType
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale



/**
 * Utility object for language-related operations within the application.
 * Includes functions for changing the app's language and retrieving
 * supported languages.
 */
object LocaleUtils {

    private const val TAG = "LanguageUtil"

    @Volatile
    private var appLocaleOverride: Locale? = null

    private fun effectiveLocale(): Locale = appLocaleOverride ?: Locale.getDefault()

    /**
     * Updates the application's locale for the given activity.
     * The change is made persistent through the system (depending on the API version)
     * and typically requires a `recreate()` of the activity to take effect.
     *
     * @param activity The ComponentActivity whose locale is to be updated.
     * @param languageCode The language code (e.g., "en", "de") of the target language.
     *                     If null, the default system language will be used.
     */
    fun updateAppLocale(activity: ComponentActivity, languageCode: String?) {
        val targetLanguageEnum = SupportedLanguage.fromCode(languageCode)
            ?: SupportedLanguage.getDefault() // Fallback to the default language defined in the enum

        val effectiveLanguageCode = targetLanguageEnum.code

        if (effectiveLanguageCode.isBlank()) {
            LogManager.w(TAG, "Language code is blank, cannot update locale.")
            return
        }

        LogManager.d(TAG, "Attempting to set app locale to: $effectiveLanguageCode for Activity: ${activity::class.java.simpleName}")
        val newLocale = targetLanguageEnum.toLocale() // Use the toLocale() method of the enum
        val newLocaleList = LocaleList(newLocale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                if (localeManager != null) {
                    LogManager.i(TAG, "Using LocaleManager to set application locales to: ${newLocale.toLanguageTag()}")
                    localeManager.applicationLocales = newLocaleList
                } else {
                    LogManager.w(TAG, "LocaleManager is null on API ${Build.VERSION.SDK_INT}, falling back to older method.")
                    applyConfigurationToActivity(activity, newLocale, newLocaleList)
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error using LocaleManager", e)
                applyConfigurationToActivity(activity, newLocale, newLocaleList) // Fallback on error
            }
        } else {
            LogManager.i(TAG, "Using applyOverrideConfiguration for API ${Build.VERSION.SDK_INT} to set locale: ${newLocale.toLanguageTag()}")
            applyConfigurationToActivity(activity, newLocale, newLocaleList)
        }

        appLocaleOverride = newLocale  // keep a cached copy for formatters
    }

    /**
     * Applies the new locale configuration to the given activity.
     * This is the fallback method for older API versions or when LocaleManager is not available.
     */
    private fun applyConfigurationToActivity(activity: ComponentActivity, newLocale: Locale, newLocaleList: LocaleList) {
        // The locale change is triggered asynchronously from a coroutine; the activity may
        // already be finishing/destroyed, in which case applyOverrideConfiguration throws
        // IllegalStateException. Skip in that case — the new locale is persisted elsewhere
        // and applies on the next launch.
        if (activity.isFinishing || activity.isDestroyed) {
            LogManager.w(TAG, "Activity is finishing or destroyed, skipping locale override.")
            return
        }

        val currentActivityConfiguration = activity.resources.configuration
        val currentActivityLocale = currentActivityConfiguration.locales.get(0)

        // Only apply if the language or country actually changes,
        // to avoid unnecessary configuration changes.
        if (currentActivityLocale.language != newLocale.language ||
            (newLocale.country.isNotBlank() && currentActivityLocale.country != newLocale.country)) {

            val newConfiguration = Configuration(currentActivityConfiguration)
            newConfiguration.setLocale(newLocale)
            newConfiguration.setLocales(newLocaleList) // Important for a consistent locale list

            try {
                activity.applyOverrideConfiguration(newConfiguration)
                LogManager.i(TAG, "Applied override configuration to activity for locale: ${newLocale.toLanguageTag()}.")
            } catch (e: IllegalStateException) {
                LogManager.e(TAG, "applyOverrideConfiguration failed (activity in invalid state).", e)
            }
        } else {
            LogManager.d(TAG, "Activity locale is already set to: ${newLocale.toLanguageTag()}. No configuration override needed.")
        }
    }

    /**
     * Format a numeric *string* for display according to the given UnitType.
     * - For ST, 'value' is expected to be decimal stones (e.g., "12.5") and will be shown as "12 st 7 lb".
     * - For KG/LB, the number is localized and a short unit suffix is appended.
     * - If includeSign = true, a '+' or '−' (Unicode minus) is prefixed based on the numeric sign.
     * - Returns "" for blank input; returns the raw string if parsing fails.
     */
    @JvmStatic
    fun formatValueForDisplay(
        value: String,
        unit: UnitType,
        includeSign: Boolean = false,
        locale: Locale = effectiveLocale(), // oder Locale.getDefault()
    ): String {
        if (value.isBlank()) return ""

        val n = value.replace(',', '.').toDoubleOrNull() ?: return value
        val signPrefix = when {
            !includeSign -> ""
            n > 0        -> "+"
            n < 0        -> "−"
            else         -> ""
        }
        val absVal = kotlin.math.abs(n)

        return when (unit) {
            UnitType.ST -> {
                val (st, lb) = ConverterUtils.decimalStToStLb(absVal)
                "$signPrefix$st st $lb lb"
            }
            UnitType.KG  -> "$signPrefix${formatNumber(absVal, maxFraction = 2, locale)} kg"
            UnitType.LB  -> "$signPrefix${formatNumber(absVal, maxFraction = 1, locale)} lb"
            UnitType.PERCENT -> "$signPrefix${formatNumber(absVal, maxFraction = 1, locale)} %"
            UnitType.CM  -> "$signPrefix${formatNumber(absVal, maxFraction = 1, locale)} cm"
            UnitType.INCH-> "$signPrefix${formatNumber(absVal, maxFraction = 2, locale)} in"
            UnitType.KCAL-> "$signPrefix${formatNumber(absVal, maxFraction = 0, locale)} kcal"
            UnitType.BPM -> "$signPrefix${formatNumber(absVal, maxFraction = 0, locale)} bpm"
            UnitType.OHM -> "$signPrefix${formatNumber(absVal, maxFraction = 1, locale)} Ω"
            UnitType.NONE-> signPrefix + formatNumber(absVal, maxFraction = 1, locale)
        }
    }

    /**
     * The calendar-week rule (first day of week, minimal days in first week) for grouping
     * measurements into weeks.
     *
     * Deliberately read from the **device** configuration, never from [effectiveLocale] or
     * `Locale.getDefault()`: [SupportedLanguage.toLocale] builds region-less locales such as
     * `de`, and `WeekFields.of(Locale("de"))` is Sunday-based with a 1-day first week — so a
     * German user picking the German in-app language would get Sunday weeks. On API 33+
     * [updateAppLocale] additionally pushes that region-less locale into `LocaleManager`,
     * where it can also become `Locale.getDefault()`.
     *
     * Falls back to ISO whenever no region is known (or the framework is unavailable, as in
     * plain JVM unit tests), so the result is deterministic instead of accidentally Sunday.
     */
    @JvmStatic
    fun systemWeekFields(): WeekFields = runCatching {
        val locale = Resources.getSystem().configuration.locales[0]
        if (locale.country.isNullOrBlank()) WeekFields.ISO else WeekFields.of(locale)
    }.getOrDefault(WeekFields.ISO)

    /**
     * Formats the span from [from] to [to] as weeks and days, e.g. "6 weeks, 3 days". Zero
     * components are left out; below a week only the day count is returned.
     *
     * Weeks are the coarsest unit on purpose — months would have to come from [java.time.Period]
     * to be calendar-correct, and a diet is counted in weeks anyway.
     *
     * Unit names, plural rules and the list separator come from [MeasureFormat] — a hand-rolled
     * singular/plural pair is wrong in every language with more than two forms, and openScale
     * ships Polish, Russian and Slovenian among others.
     */
    @JvmStatic
    fun formatElapsed(from: LocalDate, to: LocalDate): String {
        val totalDays = ChronoUnit.DAYS.between(from, to).coerceAtLeast(0L).toInt()
        if (totalDays < DAYS_PER_WEEK) return formatDays(totalDays)

        val parts = buildList {
            add(Measure(totalDays / DAYS_PER_WEEK, MeasureUnit.WEEK))
            val days = totalDays % DAYS_PER_WEEK
            if (days > 0) add(Measure(days, MeasureUnit.DAY))
        }
        return measureFormat(MeasureFormat.FormatWidth.WIDE).formatMeasures(*parts.toTypedArray())
    }

    /** An epoch timestamp as the calendar date it falls on in the device's zone. */
    @JvmStatic
    fun toLocalDate(timestampMillis: Long): LocalDate =
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * A short localized date, e.g. "Oct 31" or "31. Okt". The year is only spelled out when the
     * date is not in the current one, where leaving it off would be ambiguous.
     */
    @JvmStatic
    fun formatCompactDate(date: LocalDate): String {
        val locale = effectiveLocale()
        val skeleton = if (date.year == LocalDate.now().year) "dMMM" else "dMMMy"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** A bare localized day count, e.g. "45 days". */
    @JvmStatic
    fun formatDays(days: Int): String =
        measureFormat(MeasureFormat.FormatWidth.WIDE)
            .formatMeasures(Measure(days.coerceAtLeast(0), MeasureUnit.DAY))

    private fun measureFormat(width: MeasureFormat.FormatWidth): MeasureFormat =
        MeasureFormat.getInstance(effectiveLocale(), width)

    private const val DAYS_PER_WEEK = 7

    /**
     * Locale-aware number formatting with clamped fraction digits.
     * Returns the raw string if parsing fails.
     */
    @JvmStatic
    fun formatNumber(value: Double, maxFraction: Int, locale: Locale): String {
        val cleaned = if (kotlin.math.abs(value) < 1e-9) 0.0 else value // avoid "-0"
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = maxFraction
            isGroupingUsed = false
        }.format(cleaned)
    }
}
