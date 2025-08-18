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
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import com.health.openscale.core.data.SupportedLanguage
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.Locale

object CalculationUtil {
    fun ageOn(dateMillis: Long, birthDateMillis: Long): Int {
        val birth = Instant.ofEpochMilli(birthDateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val onDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return Period.between(birth, onDate).years.coerceAtLeast(0)
    }

    /**
     * Rounds a float value to two decimal places.
     */
    fun roundTo(value: Float): Float {
        return (value * 100).toInt() / 100.0f
    }

    // In SharedViewModel or a utility class

    /**
     * Applies Single Exponential Smoothing to a list of data points.
     *
     * @param data The list of Float values to smooth.
     * @param alpha The smoothing factor (0 < alpha <= 1).
     *              A higher alpha gives more weight to recent observations.
     * @return A new list containing the smoothed data. The list will have the same size as the input.
     */
    fun applyExponentialSmoothing(data: List<Float>, alpha: Float): List<Float> {
        if (data.isEmpty()) {
            return emptyList()
        }
        // Ensure alpha is within the valid range.
        val validAlpha = alpha.coerceIn(0.01f, 1.0f) // Prevent alpha=0 which would freeze the series

        val smoothedValues = mutableListOf<Float>()
        if (data.isNotEmpty()) {
            // Initialize the first smoothed value.
            // Common practice is to start with the first data point,
            // though other initializations (like an average of the first few points) exist.
            smoothedValues.add(data[0])

            for (i in 1 until data.size) {
                val smoothed = validAlpha * data[i] + (1 - validAlpha) * smoothedValues[i - 1]
                smoothedValues.add(smoothed)
            }
        }
        return smoothedValues
    }

    /**
     * Applies a Simple Moving Average (SMA) to a list of data points.
     *
     * @param data The list of Float values to average.
     * @param windowSize The number of data points to include in each average. Must be positive.
     * @return A new list containing the SMA values.
     *         The returned list will be shorter than the input list by (windowSize - 1) elements,
     *         as the SMA can only be calculated once enough data points are available to fill the window.
     *         Returns an empty list if data is empty or windowSize is invalid.
     */
    fun applySimpleMovingAverage(data: List<Float>, windowSize: Int): List<Float> {
        if (data.isEmpty() || windowSize <= 0) {
            return emptyList()
        }
        // If window size is 1, it's just the original data (no averaging).
        // Or if window size is larger than data size, we can't form a single complete window.
        if (windowSize == 1) {
            return data.toList() // Return a copy
        }
        if (windowSize > data.size) {
            // Cannot compute any SMA if window is larger than available data.
            // Alternatively, one might return the average of all available points,
            // but standard SMA implies a full window.
            return emptyList()
        }

        val movingAverages = mutableListOf<Float>()
        var currentSum = 0.0f

        // Calculate sum for the first window
        for (i in 0 until windowSize) {
            currentSum += data[i]
        }
        movingAverages.add(currentSum / windowSize)

        // Slide the window across the rest of the data
        for (i in windowSize until data.size) {
            currentSum -= data[i - windowSize] // Subtract the element that's leaving the window
            currentSum += data[i]             // Add the new element entering the window
            movingAverages.add(currentSum / windowSize)
        }

        return movingAverages
    }

}

/**
 * Utility object for language-related operations within the application.
 * Includes functions for changing the app's language and retrieving
 * supported languages.
 */
object LanguageUtil {

    private const val TAG = "LanguageUtil"

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
    }

    /**
     * Applies the new locale configuration to the given activity.
     * This is the fallback method for older API versions or when LocaleManager is not available.
     */
    private fun applyConfigurationToActivity(activity: ComponentActivity, newLocale: Locale, newLocaleList: LocaleList) {
        val currentActivityConfiguration = activity.resources.configuration
        val currentActivityLocale = currentActivityConfiguration.locales.get(0)

        // Only apply if the language or country actually changes,
        // to avoid unnecessary configuration changes.
        if (currentActivityLocale.language != newLocale.language ||
            (newLocale.country.isNotBlank() && currentActivityLocale.country != newLocale.country)) {

            val newConfiguration = Configuration(currentActivityConfiguration)
            newConfiguration.setLocale(newLocale)
            newConfiguration.setLocales(newLocaleList) // Important for a consistent locale list

            activity.applyOverrideConfiguration(newConfiguration)
            LogManager.i(TAG, "Applied override configuration to activity for locale: ${newLocale.toLanguageTag()}.")
        } else {
            LogManager.d(TAG, "Activity locale is already set to: ${newLocale.toLanguageTag()}. No configuration override needed.")
        }
    }
}
