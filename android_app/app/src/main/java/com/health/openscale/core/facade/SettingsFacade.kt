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
package com.health.openscale.core.facade

import android.content.Context
import android.util.Base64
import android.util.SparseArray
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.health.openscale.core.data.BackupInterval
import com.health.openscale.core.data.BodyFatFormulaOption
import com.health.openscale.core.data.BodyWaterFormulaOption
import com.health.openscale.core.data.LbmFormulaOption
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.LogManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.util.size
import org.json.JSONObject
import androidx.core.util.isNotEmpty

// DataStore instance for user settings
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Defines keys for user preferences stored in DataStore.
 */
object SettingsPreferenceKeys {
    // General App Settings
    val IS_FILE_LOGGING_ENABLED = booleanPreferencesKey("is_file_logging_enabled")
    val IS_FIRST_APP_START = booleanPreferencesKey("is_first_app_start")
    val CURRENT_USER_ID = intPreferencesKey("current_user_id")
    val APP_LANGUAGE_CODE = stringPreferencesKey("app_language_code")
    val HAPTIC_ON_MEASUREMENT = booleanPreferencesKey("haptic_on_measurement")

    // Settings for specific UI components
    val SELECTED_TYPES_TABLE = stringSetPreferencesKey("selected_types_table") // IDs of measurement types selected for the data table
    val MY_GOALS_EXPANDED_OVERVIEW = booleanPreferencesKey("my_goals_expanded")

    // Saved Bluetooth Scale
    val SAVED_BLUETOOTH_DEVICE_ADDRESS        = stringPreferencesKey("saved_bluetooth_device_address")
    val SAVED_BLUETOOTH_DEVICE_NAME           = stringPreferencesKey("saved_bluetooth_device_name")
    val SAVED_BLUETOOTH_DEVICE_RSSI           = intPreferencesKey("saved_bluetooth_device_rssi")
    val SAVED_BLUETOOTH_DEVICE_SERVICE_UUIDS  = stringSetPreferencesKey("saved_bluetooth_device_service_uuids")
    val SAVED_BLUETOOTH_DEVICE_HANDLER_HINT   = stringPreferencesKey("saved_bluetooth_device_handler_hint")
    val SAVED_BLUETOOTH_DEVICE_MANUFACTURER_DATA   = stringPreferencesKey("saved_bluetooth_device_manufacturer_data")
    val SAVED_BLUETOOTH_TUNE_PROFILE = stringPreferencesKey("saved_bluetooth_tune_profile")
    val SAVED_BLUETOOTH_SMART_ASSIGNMENT_ENABLED = booleanPreferencesKey("saved_bluetooth_smart_assignment_enabled")
    val SAVED_BLUETOOTH_TOLERANCE_PERCENT = intPreferencesKey("saved_bluetooth_tolerance_percent")
    val SAVED_BLUETOOTH_IGNORE_OUTSIDE_TOLERANCE = booleanPreferencesKey("saved_bluetooth_ignore_outside_tolerance")

    // Settings for chart
    val CHART_SHOW_DATA_POINTS = booleanPreferencesKey("chart_show_data_points")
    val CHART_SMOOTHING_ALGORITHM = stringPreferencesKey("chart_smoothing_algorithm")
    val CHART_SMOOTHING_ALPHA = floatPreferencesKey("chart_smoothing_alpha")
    val CHART_SMOOTHING_WINDOW_SIZE = intPreferencesKey("chart_smoothing_window_size")
    val CHART_SMOOTHING_MAX_GAP_DAYS = intPreferencesKey("chart_smoothing_max_gap_days")
    val CHART_SHOW_GOAL_LINES = booleanPreferencesKey("chart_show_goal_lines")
    val CHART_PROJECTION_ENABLED = booleanPreferencesKey("chart_projection_enabled")
    val CHART_PROJECTION_DAYS_IN_THE_PAST = intPreferencesKey("chart_projection_days_in_the_past")
    val CHART_PROJECTION_DAYS_TO_PROJECT = intPreferencesKey("chart_projection_days_to_project")
    val CHART_PROJECTION_POLYNOMIAL_DEGREE = intPreferencesKey("chart_projection_polynomial_degree")

    // --- Settings for Automatic Backups ---
    val AUTO_BACKUP_ENABLED_GLOBALLY = booleanPreferencesKey("auto_backup_enabled_globally")
    val AUTO_BACKUP_LOCATION_URI = stringPreferencesKey("auto_backup_location_uri")
    val AUTO_BACKUP_INTERVAL = stringPreferencesKey("auto_backup_interval")
    val AUTO_BACKUP_CREATE_NEW_FILE = booleanPreferencesKey("auto_backup_create_new_file")
    val AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP = longPreferencesKey("auto_backup_last_successful_timestamp")

    // --- Reminder Settings ---
    val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
    val REMINDER_TEXT = stringPreferencesKey("reminder_text")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
    val REMINDER_DAYS = stringSetPreferencesKey("reminder_days")

    val BODY_FAT_FORMULA_OPTION   = stringPreferencesKey("body_fat_formula_option")
    val BODY_WATER_FORMULA_OPTION = stringPreferencesKey("body_water_formula_option")
    val LBM_FORMULA_OPTION        = stringPreferencesKey("lbm_formula_option")

    // Context strings for screen-specific settings (can be used as prefixes for dynamic keys)
    const val OVERVIEW_SCREEN_CONTEXT = "overview_screen"
    const val GRAPH_SCREEN_CONTEXT = "graph_screen"
    const val STATISTICS_SCREEN_CONTEXT = "statistics_screen"
}

@Module
@InstallIn(SingletonComponent::class)
object SettingsProvidesModule {

    @Provides
    @Singleton
    fun provideUserSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.settingsDataStore
}

@Module
@InstallIn(SingletonComponent::class)
interface SettingsBindsModule {

    @Binds
    @Singleton
    fun bindUserSettingsRepository(
        impl: SettingsFacadeImpl
    ): SettingsFacade
}

/**
 * Repository interface for accessing and managing user settings.
 */
interface SettingsFacade {
    // General app settings
    val isFileLoggingEnabled: Flow<Boolean>
    suspend fun setFileLoggingEnabled(enabled: Boolean)

    val isFirstAppStart: Flow<Boolean>
    suspend fun setFirstAppStartCompleted(completed: Boolean)

    val appLanguageCode: Flow<String?>
    suspend fun setAppLanguageCode(languageCode: String?)

    val hapticOnMeasurement: Flow<Boolean>
    suspend fun setHapticOnMeasurement(value: Boolean)

    val currentUserId: Flow<Int?>
    suspend fun setCurrentUserId(userId: Int?)

    // Table settings
    val selectedTableTypeIds: Flow<Set<String>>
    suspend fun saveSelectedTableTypeIds(typeIds: Set<String>)

    val myGoalsExpandedOverview: Flow<Boolean>
    suspend fun setMyGoalsExpandedOverview(isExpanded: Boolean)

    fun observeSplitterWeight(keyPrefix: String, defaultValue: Float): Flow<Float>
    suspend fun setSplitterWeight(keyPrefix : String, weight: Float)

    // Bluetooth scale settings
    fun observeSavedDevice(): Flow<ScannedDeviceInfo?>
    suspend fun saveSavedDevice(device: ScannedDeviceInfo)
    suspend fun clearSavedBluetoothScale()
    suspend fun clearBleDriverSettings()

    val savedBluetoothTuneProfile: Flow<String?>
    suspend fun saveBluetoothTuneProfile(name: String?)

    val isSmartAssignmentEnabled : Flow<Boolean>
    suspend fun setSmartAssignmentEnabled(enabled: Boolean)

    val smartAssignmentTolerancePercent: Flow<Int>
    suspend fun setSmartAssignmentTolerancePercent(tolerance: Int)

    val smartAssignmentIgnoreOutsideTolerance: Flow<Boolean>
    suspend fun setSmartAssignmentIgnoreOutsideTolerance(ignore: Boolean)

    val showChartDataPoints: Flow<Boolean>
    suspend fun setShowChartDataPoints(show: Boolean)

    val chartSmoothingAlgorithm: Flow<SmoothingAlgorithm>
    suspend fun setChartSmoothingAlgorithm(algorithm: SmoothingAlgorithm)

    val chartSmoothingAlpha: Flow<Float>
    suspend fun setChartSmoothingAlpha(alpha: Float)

    val chartSmoothingWindowSize: Flow<Int>
    suspend fun setChartSmoothingWindowSize(windowSize: Int)

    val chartSmoothingMaxGapDays: Flow<Int>
    suspend fun setChartSmoothingMaxGapDays(days: Int)

    val showChartGoalLines: Flow<Boolean>
    suspend fun setShowChartGoalLines(show: Boolean)

    val chartProjectionEnabled: Flow<Boolean>
    suspend fun setChartProjectionEnabled(enabled: Boolean)

    val chartProjectionDaysInThePast: Flow<Int>
    suspend fun setChartProjectionDaysInThePast(days: Int)

    val chartProjectionDaysToProject: Flow<Int>
    suspend fun setChartProjectionDaysToProject(days: Int)

    val chartProjectionPolynomialDegree: Flow<Int>
    suspend fun setChartProjectionPolynomialDegree(degree: Int)

    // --- Automatic Backup Settings ---
    val autoBackupEnabledGlobally: Flow<Boolean>
    suspend fun setAutoBackupEnabledGlobally(enabled: Boolean)

    val autoBackupLocationUri: Flow<String?>
    suspend fun setAutoBackupLocationUri(uri: String?)

    val autoBackupInterval: Flow<BackupInterval>
    suspend fun setAutoBackupInterval(interval: BackupInterval)

    val autoBackupCreateNewFile: Flow<Boolean>
    suspend fun setAutoBackupCreateNewFile(createNew: Boolean)

    val autoBackupLastSuccessfulTimestamp: Flow<Long>
    suspend fun setAutoBackupLastSuccessfulTimestamp(timestamp: Long)

    // --- Reminder Settings ---
    val reminderEnabled: Flow<Boolean>
    suspend fun setReminderEnabled(enabled: Boolean)

    val reminderText: Flow<String>
    suspend fun setReminderText(text: String)

    val reminderHour: Flow<Int>
    suspend fun setReminderHour(hour: Int)

    val reminderMinute: Flow<Int>
    suspend fun setReminderMinute(minute: Int)

    val reminderDays: Flow<Set<String>>
    suspend fun setReminderDays(days: Set<String>)

    val selectedBodyFatFormula: Flow<BodyFatFormulaOption>
    suspend fun setSelectedBodyFatFormula(option: BodyFatFormulaOption)

    val selectedBodyWaterFormula: Flow<BodyWaterFormulaOption>
    suspend fun setSelectedBodyWaterFormula(option: BodyWaterFormulaOption)

    val selectedLbmFormula: Flow<LbmFormulaOption>
    suspend fun setSelectedLbmFormula(option: LbmFormulaOption)

    // Generic Settings Accessors
    /**
     * Observes a setting with the given key name and default value.
     * The type T determines the preference key type.
     */
    fun <T> observeSetting(keyName: String, defaultValue: T): Flow<T>

    /**
     * Saves a setting with the given key name and value.
     * The type T determines the preference key type.
     */
    suspend fun <T> saveSetting(keyName: String, value: T)
}

/**
 * Implementation of [SettingsFacade] using Jetpack DataStore.
 */
@Singleton
class SettingsFacadeImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
): SettingsFacade {
    private val TAG = "UserSettingsRepository" // Tag for logging

    override val isFileLoggingEnabled: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.IS_FILE_LOGGING_ENABLED.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing isFileLoggingEnabled", exception)
        emit(false) // Fallback to default on error
    }

    override suspend fun setFileLoggingEnabled(enabled: Boolean) {
        LogManager.d(TAG, "Setting file logging enabled to: $enabled")
        saveSetting(SettingsPreferenceKeys.IS_FILE_LOGGING_ENABLED.name, enabled)
    }

    override val isFirstAppStart: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.IS_FIRST_APP_START.name,
        true // Default to true, meaning it IS the first start until explicitly set otherwise
    ).catch { exception ->
        LogManager.e(TAG, "Error observing isFirstAppStart", exception)
        emit(true) // Fallback to default on error
    }

    override suspend fun setFirstAppStartCompleted(completed: Boolean) {
        LogManager.d(TAG, "Setting first app start completed to: $completed")
        saveSetting(SettingsPreferenceKeys.IS_FIRST_APP_START.name, completed)
    }

    override val appLanguageCode: Flow<String?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading appLanguageCode from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[SettingsPreferenceKeys.APP_LANGUAGE_CODE]
        }
        .distinctUntilChanged()

    override suspend fun setAppLanguageCode(languageCode: String?) {
        LogManager.d(TAG, "Setting app language code to: $languageCode")
        dataStore.edit { preferences ->
            if (languageCode != null) {
                preferences[SettingsPreferenceKeys.APP_LANGUAGE_CODE] = languageCode
            } else {
                preferences.remove(SettingsPreferenceKeys.APP_LANGUAGE_CODE)
            }
        }
    }

    override val hapticOnMeasurement: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.HAPTIC_ON_MEASUREMENT.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing hapticOnMeasurement", exception)
        emit(false)
    }

    override suspend fun setHapticOnMeasurement(value: Boolean) {
        LogManager.d(TAG, "Setting hapticOnMeasurement to: $value")
        saveSetting(SettingsPreferenceKeys.HAPTIC_ON_MEASUREMENT.name, value)
    }

    override val currentUserId: Flow<Int?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading currentUserId from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[SettingsPreferenceKeys.CURRENT_USER_ID]
        }
        .distinctUntilChanged()

    override suspend fun setCurrentUserId(userId: Int?) {
        LogManager.d(TAG, "Setting current user ID to: $userId")
        dataStore.edit { preferences ->
            if (userId != null) {
                preferences[SettingsPreferenceKeys.CURRENT_USER_ID] = userId
            } else {
                preferences.remove(SettingsPreferenceKeys.CURRENT_USER_ID)
            }
        }
    }

    override val selectedTableTypeIds: Flow<Set<String>> = observeSetting(
        SettingsPreferenceKeys.SELECTED_TYPES_TABLE.name,
        emptySet<String>()
    ).catch { exception ->
        LogManager.e(TAG, "Error observing selectedTableTypeIds", exception)
        emit(emptySet()) // Fallback to default on error
    }

    override suspend fun saveSelectedTableTypeIds(typeIds: Set<String>) {
        // LogManager.d(TAG, "Saving selected table type IDs: $typeIds")
        saveSetting(SettingsPreferenceKeys.SELECTED_TYPES_TABLE.name, typeIds)
    }

    override val myGoalsExpandedOverview: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.MY_GOALS_EXPANDED_OVERVIEW.name,
        true
    ).catch { exception ->
        emit(true)
    }

    override suspend fun setMyGoalsExpandedOverview(isExpanded: Boolean) {
        saveSetting(SettingsPreferenceKeys.MY_GOALS_EXPANDED_OVERVIEW.name, isExpanded)
    }


    override fun observeSplitterWeight(keyPrefix: String, defaultValue: Float): Flow<Float> {
        val dynamicKey = floatPreferencesKey("${keyPrefix}_splitter_weight")
        return dataStore.data
            .catch { exception ->
                LogManager.e(TAG, "Error observing splitter weight for key $dynamicKey", exception)
                emit(emptyPreferences())
            }
            .map { preferences ->
                preferences[dynamicKey] ?: defaultValue
            }
    }

    override suspend fun setSplitterWeight(keyPrefix: String, weight: Float) {
        val dynamicKey = floatPreferencesKey("${keyPrefix}_splitter_weight")
        dataStore.edit { preferences ->
            preferences[dynamicKey] = weight
        }
    }


    override fun observeSavedDevice(): Flow<ScannedDeviceInfo?> {
        val addrF  = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_ADDRESS.name,  null as String?)
        val nameF  = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_NAME.name,     null as String?)
        val rssiF  = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_RSSI.name,     0)
        val uuidsF = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_SERVICE_UUIDS.name, emptySet<String>())
        val hintF  = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_HANDLER_HINT.name,  null as String?)
        val manDataF = observeSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_MANUFACTURER_DATA.name, null as String?)

        return combine(addrF, nameF, rssiF, uuidsF, hintF, manDataF) { array ->
            val addr        = array[0] as String?
            val name        = array[1] as String?
            val rssi        = array[2] as Int
            val uuidStrSet = (array[3] as? Set<*>)?.filterIsInstance<String>() ?: emptySet()
            val hint        = array[4] as String?
            val manDataJson = array[5] as String?

            if (addr.isNullOrBlank() || name.isNullOrBlank()) {
                null
            } else {
                // Convert to UUID list with stable ordering to keep distinctUntilChanged effective
                val uuids = uuidStrSet
                    .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                    .sortedBy { it.toString() }

                // ManufacturerData
                val manData = SparseArray<ByteArray>()
                manDataJson?.let { jsonStr ->
                    runCatching {
                        val jsonObj = JSONObject(jsonStr)
                        jsonObj.keys().forEach { key ->
                            val value = Base64.decode(jsonObj.getString(key), Base64.NO_WRAP)
                            manData.put(key.toInt(), value)
                        }
                    }
                }

                ScannedDeviceInfo(
                    name = name,
                    address = addr,
                    rssi = rssi,
                    serviceUuids = uuids,
                    manufacturerData = if (manData.isNotEmpty()) manData else null,
                    isSupported = false,
                    determinedHandlerDisplayName = hint
                )
            }
        }
            .catch { e ->
                LogManager.e("UserSettingsRepository", "observeSavedDevice failed", e)
                emit(null)
            }
            .distinctUntilChanged()
    }

    override suspend fun saveSavedDevice(device: ScannedDeviceInfo) {
        LogManager.i(TAG, "Saving device snapshot: addr=${device.address}, name=${device.name}, uuids=${device.serviceUuids.size}, hint=${device.determinedHandlerDisplayName}")
        dataStore.edit { prefs ->
            prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_ADDRESS]       = device.address
            prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_NAME]          = device.name
            prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_RSSI]          = device.rssi
            prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_SERVICE_UUIDS] = device.serviceUuids.map(UUID::toString).toSet()
            device.determinedHandlerDisplayName?.let {
                prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_HANDLER_HINT] = it
            } ?: prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_HANDLER_HINT)

            val manData = JSONObject()
            device.manufacturerData?.let { sparse ->
                for (i in 0 until sparse.size) {
                    val key = sparse.keyAt(i).toString()
                    val value = Base64.encodeToString(sparse.valueAt(i), Base64.NO_WRAP)
                    manData.put(key, value)
                }
            }
            prefs[SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_MANUFACTURER_DATA] = manData.toString()
        }
    }

    override suspend fun clearSavedBluetoothScale() {
        LogManager.i(TAG, "Clearing saved Bluetooth device snapshot.")
        dataStore.edit { prefs ->
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_ADDRESS)
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_NAME)
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_RSSI)
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_SERVICE_UUIDS)
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_HANDLER_HINT)
            prefs.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_DEVICE_MANUFACTURER_DATA)
        }
    }

    override suspend fun clearBleDriverSettings() {
        LogManager.i(TAG, "Clearing all BLE driver settings (consent codes, user mappings).")
        dataStore.edit { prefs ->
            val bleKeys = prefs.asMap().keys.filter { it.name.startsWith("ble/") }
            for (key in bleKeys) {
                prefs.remove(key)
            }
            LogManager.d(TAG, "Removed ${bleKeys.size} BLE driver setting(s).")
        }
    }

    override val savedBluetoothTuneProfile: Flow<String?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading savedBluetoothTuneProfile from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[SettingsPreferenceKeys.SAVED_BLUETOOTH_TUNE_PROFILE]
        }
        .distinctUntilChanged()

    override suspend fun saveBluetoothTuneProfile(name: String?) {
        LogManager.i(TAG, "Saving Bluetooth tune profile: Name=$name")
        dataStore.edit { preferences ->
            if (name != null) {
                preferences[SettingsPreferenceKeys.SAVED_BLUETOOTH_TUNE_PROFILE] = name
            } else {
                preferences.remove(SettingsPreferenceKeys.SAVED_BLUETOOTH_TUNE_PROFILE)
            }
        }
    }

    override val isSmartAssignmentEnabled: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.SAVED_BLUETOOTH_SMART_ASSIGNMENT_ENABLED.name,
        false
    )

    override suspend fun setSmartAssignmentEnabled(enabled: Boolean) {
        saveSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_SMART_ASSIGNMENT_ENABLED.name, enabled)
    }

    override val smartAssignmentTolerancePercent: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.SAVED_BLUETOOTH_TOLERANCE_PERCENT.name,
        10
    )

    override suspend fun setSmartAssignmentTolerancePercent(tolerance: Int) {
        saveSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_TOLERANCE_PERCENT.name, tolerance)
    }

    override val smartAssignmentIgnoreOutsideTolerance: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.SAVED_BLUETOOTH_IGNORE_OUTSIDE_TOLERANCE.name,
        false
    )

    override suspend fun setSmartAssignmentIgnoreOutsideTolerance(ignore: Boolean) {
        saveSetting(SettingsPreferenceKeys.SAVED_BLUETOOTH_IGNORE_OUTSIDE_TOLERANCE.name, ignore)
    }

    override val showChartDataPoints: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.CHART_SHOW_DATA_POINTS.name,
        true
    ).catch { exception ->
        LogManager.e(TAG, "Error observing showChartDataPoints", exception)
        emit(true)
    }

    override suspend fun setShowChartDataPoints(show: Boolean) {
        LogManager.d(TAG, "Setting showChartDataPoints to: $show")
        saveSetting(SettingsPreferenceKeys.CHART_SHOW_DATA_POINTS.name, show)
    }

    override val chartSmoothingAlgorithm: Flow<SmoothingAlgorithm> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading chartSmoothingAlgorithm from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val algorithmName = preferences[SettingsPreferenceKeys.CHART_SMOOTHING_ALGORITHM]
            try {
                algorithmName?.let { SmoothingAlgorithm.valueOf(it) } ?: SmoothingAlgorithm.NONE
            } catch (e: IllegalArgumentException) {
                LogManager.w(TAG, "Invalid smoothing algorithm name '$algorithmName' in DataStore. Defaulting to NONE.", e)
                SmoothingAlgorithm.NONE
            }
        }
        .distinctUntilChanged()

    override suspend fun setChartSmoothingAlgorithm(algorithm: SmoothingAlgorithm) {
        LogManager.d(TAG, "Setting chart smoothing algorithm to: ${algorithm.name}")
        saveSetting(SettingsPreferenceKeys.CHART_SMOOTHING_ALGORITHM.name, algorithm.name)
    }

    override val chartSmoothingAlpha: Flow<Float> = observeSetting(
        SettingsPreferenceKeys.CHART_SMOOTHING_ALPHA.name,
        0.5f
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartSmoothingAlpha", exception)
        emit(0.5f)
    }

    override suspend fun setChartSmoothingAlpha(alpha: Float) {
        val validAlpha = alpha.coerceIn(0.01f, 0.99f)
        LogManager.d(TAG, "Setting chart smoothing alpha to: $validAlpha (raw input: $alpha)")
        saveSetting(SettingsPreferenceKeys.CHART_SMOOTHING_ALPHA.name, validAlpha)
    }

    override val chartSmoothingWindowSize: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.CHART_SMOOTHING_WINDOW_SIZE.name,
        5
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartSmoothingWindowSize", exception)
        emit(5)
    }

    override suspend fun setChartSmoothingWindowSize(windowSize: Int) {
        val validWindowSize = windowSize.coerceIn(2, 50)
        LogManager.d(TAG, "Setting chart smoothing window size to: $validWindowSize (raw input: $windowSize)")
        saveSetting(SettingsPreferenceKeys.CHART_SMOOTHING_WINDOW_SIZE.name, validWindowSize)
    }

    override val chartSmoothingMaxGapDays: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.CHART_SMOOTHING_MAX_GAP_DAYS.name,
        7 // Default to 7 days
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartSmoothingMaxGapDays", exception)
        emit(7) // Fallback to default on error
    }

    override suspend fun setChartSmoothingMaxGapDays(days: Int) {
        LogManager.d(TAG, "Setting chart smoothing max gap to: $days days")
        saveSetting(SettingsPreferenceKeys.CHART_SMOOTHING_MAX_GAP_DAYS.name, days)
    }

    override val showChartGoalLines: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.CHART_SHOW_GOAL_LINES.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing showChartGoalLines", exception)
        emit(false)
    }

    override suspend fun setShowChartGoalLines(show: Boolean) {
        saveSetting(SettingsPreferenceKeys.CHART_SHOW_GOAL_LINES.name, show)
    }

    override val chartProjectionEnabled: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.CHART_PROJECTION_ENABLED.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartProjectionEnabled", exception)
        emit(false)
    }

    override suspend fun setChartProjectionEnabled(enabled: Boolean) {
        saveSetting(SettingsPreferenceKeys.CHART_PROJECTION_ENABLED.name, enabled)
    }

    override val chartProjectionDaysInThePast: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.CHART_PROJECTION_DAYS_IN_THE_PAST.name,
        30 // Default value
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartProjectionDaysInThePast", exception)
        emit(30)
    }

    override suspend fun setChartProjectionDaysInThePast(days: Int) {
        saveSetting(SettingsPreferenceKeys.CHART_PROJECTION_DAYS_IN_THE_PAST.name, days)
    }

    override val chartProjectionDaysToProject: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.CHART_PROJECTION_DAYS_TO_PROJECT.name,
        14 // Default value
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartProjectionDaysToProject", exception)
        emit(14)
    }

    override suspend fun setChartProjectionDaysToProject(days: Int) {
        saveSetting(SettingsPreferenceKeys.CHART_PROJECTION_DAYS_TO_PROJECT.name, days)
    }

    override val chartProjectionPolynomialDegree: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.CHART_PROJECTION_POLYNOMIAL_DEGREE.name,
        1 // Default to linear
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartProjectionPolynomialDegree", exception)
        emit(1)
    }

    override suspend fun setChartProjectionPolynomialDegree(degree: Int) {
        saveSetting(SettingsPreferenceKeys.CHART_PROJECTION_POLYNOMIAL_DEGREE.name, degree)
    }

    override val autoBackupEnabledGlobally: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.AUTO_BACKUP_ENABLED_GLOBALLY.name,
        false // Standardmäßig deaktiviert
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupEnabledGlobally", exception)
        emit(false)
    }

    override suspend fun setAutoBackupEnabledGlobally(enabled: Boolean) {
        LogManager.d(TAG, "Setting autoBackupEnabledGlobally to: $enabled")
        saveSetting(SettingsPreferenceKeys.AUTO_BACKUP_ENABLED_GLOBALLY.name, enabled)
    }

    override val autoBackupLocationUri: Flow<String?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading autoBackupLocationUri from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[SettingsPreferenceKeys.AUTO_BACKUP_LOCATION_URI]
        }
        .distinctUntilChanged()

    override suspend fun setAutoBackupLocationUri(uri: String?) {
        LogManager.d(TAG, "Setting autoBackupLocationUri to: $uri")
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[SettingsPreferenceKeys.AUTO_BACKUP_LOCATION_URI] = uri
            } else {
                preferences.remove(SettingsPreferenceKeys.AUTO_BACKUP_LOCATION_URI)
            }
        }
    }

    override val autoBackupInterval: Flow<BackupInterval> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading autoBackupInterval from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val intervalName = preferences[SettingsPreferenceKeys.AUTO_BACKUP_INTERVAL]
            try {
                intervalName?.let { BackupInterval.valueOf(it) } ?: BackupInterval.WEEKLY
            } catch (e: IllegalArgumentException) {
                LogManager.w(TAG, "Invalid BackupInterval name '$intervalName' in DataStore. Defaulting to WEEKLY.", e)
                BackupInterval.WEEKLY
            }
        }
        .distinctUntilChanged()

    override suspend fun setAutoBackupInterval(interval: BackupInterval) {
        LogManager.d(TAG, "Setting autoBackupInterval to: ${interval.name}")
        saveSetting(SettingsPreferenceKeys.AUTO_BACKUP_INTERVAL.name, interval.name)
    }


    override val autoBackupCreateNewFile: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.AUTO_BACKUP_CREATE_NEW_FILE.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupCreateNewFile", exception)
        emit(false)
    }

    override suspend fun setAutoBackupCreateNewFile(createNew: Boolean) {
        LogManager.d(TAG, "Setting autoBackupCreateNewFile to: $createNew")
        saveSetting(SettingsPreferenceKeys.AUTO_BACKUP_CREATE_NEW_FILE.name, createNew)
    }

    override val autoBackupLastSuccessfulTimestamp: Flow<Long> = observeSetting(
        SettingsPreferenceKeys.AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP.name,
        0L
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupLastSuccessfulTimestamp", exception)
        emit(0L) // Fallback
    }

    override suspend fun setAutoBackupLastSuccessfulTimestamp(timestamp: Long) {
        LogManager.d(TAG, "Setting autoBackupLastSuccessfulTimestamp to: $timestamp")
        saveSetting(SettingsPreferenceKeys.AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP.name, timestamp)
    }

    // --- Reminder Settings ---
    override val reminderEnabled: Flow<Boolean> = observeSetting(
        SettingsPreferenceKeys.REMINDER_ENABLED.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing reminderEnabled", exception)
        emit(false)
    }

    override suspend fun setReminderEnabled(enabled: Boolean) {
        LogManager.d(TAG, "Setting reminderEnabled to: $enabled")
        saveSetting(SettingsPreferenceKeys.REMINDER_ENABLED.name, enabled)
    }

    override val reminderText: Flow<String> = observeSetting(
        SettingsPreferenceKeys.REMINDER_TEXT.name,
        ""
    ).catch { exception ->
        LogManager.e(TAG, "Error observing reminderText", exception)
        emit("")
    }

    override suspend fun setReminderText(text: String) {
        LogManager.d(TAG, "Setting reminderText to: $text")
        saveSetting(SettingsPreferenceKeys.REMINDER_TEXT.name, text)
    }

    override val reminderHour: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.REMINDER_HOUR.name,
        9
    ).catch { exception ->
        LogManager.e(TAG, "Error observing reminderHour", exception)
        emit(9)
    }

    override suspend fun setReminderHour(hour: Int) {
        val h = hour.coerceIn(0, 23)
        LogManager.d(TAG, "Setting reminderHour to: $h (raw: $hour)")
        saveSetting(SettingsPreferenceKeys.REMINDER_HOUR.name, h)
    }

    override val reminderMinute: Flow<Int> = observeSetting(
        SettingsPreferenceKeys.REMINDER_MINUTE.name,
        0
    ).catch { exception ->
        LogManager.e(TAG, "Error observing reminderMinute", exception)
        emit(0)
    }

    override suspend fun setReminderMinute(minute: Int) {
        val m = minute.coerceIn(0, 59)
        LogManager.d(TAG, "Setting reminderMinute to: $m (raw: $minute)")
        saveSetting(SettingsPreferenceKeys.REMINDER_MINUTE.name, m)
    }

    override val reminderDays: Flow<Set<String>> = observeSetting(
        SettingsPreferenceKeys.REMINDER_DAYS.name,
        emptySet<String>()
    ).catch { exception ->
        LogManager.e(TAG, "Error observing reminderDays", exception)
        emit(emptySet())
    }

    override suspend fun setReminderDays(days: Set<String>) {
        val safe = days.filter {
            runCatching { java.time.DayOfWeek.valueOf(it) }.isSuccess
        }.toSet()
        LogManager.d(TAG, "Setting reminderDays to: $safe (raw: $days)")
        saveSetting(SettingsPreferenceKeys.REMINDER_DAYS.name, safe)
    }

    override val selectedBodyFatFormula = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading BODY_FAT_FORMULA_OPTION", exception)
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val raw = prefs[SettingsPreferenceKeys.BODY_FAT_FORMULA_OPTION] ?: BodyFatFormulaOption.OFF.name
            runCatching { BodyFatFormulaOption.valueOf(raw) }.getOrDefault(BodyFatFormulaOption.OFF)
        }
        .distinctUntilChanged()

    override suspend fun setSelectedBodyFatFormula(option: BodyFatFormulaOption) {
        LogManager.d(TAG, "Setting BODY_FAT_FORMULA_OPTION to: ${option.name}")
        saveSetting(SettingsPreferenceKeys.BODY_FAT_FORMULA_OPTION.name, option.name)
    }

    override val selectedBodyWaterFormula = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading BODY_WATER_FORMULA_OPTION", exception)
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val raw = prefs[SettingsPreferenceKeys.BODY_WATER_FORMULA_OPTION] ?: BodyWaterFormulaOption.OFF.name
            runCatching { BodyWaterFormulaOption.valueOf(raw) }.getOrDefault(BodyWaterFormulaOption.OFF)
        }
        .distinctUntilChanged()

    override suspend fun setSelectedBodyWaterFormula(option: BodyWaterFormulaOption) {
        LogManager.d(TAG, "Setting BODY_WATER_FORMULA_OPTION to: ${option.name}")
        saveSetting(SettingsPreferenceKeys.BODY_WATER_FORMULA_OPTION.name, option.name)
    }

    override val selectedLbmFormula = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading LBM_FORMULA_OPTION", exception)
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val raw = prefs[SettingsPreferenceKeys.LBM_FORMULA_OPTION] ?: LbmFormulaOption.OFF.name
            runCatching { LbmFormulaOption.valueOf(raw) }.getOrDefault(LbmFormulaOption.OFF)
        }
        .distinctUntilChanged()

    override suspend fun setSelectedLbmFormula(option: LbmFormulaOption) {
        LogManager.d(TAG, "Setting LBM_FORMULA_OPTION to: ${option.name}")
        saveSetting(SettingsPreferenceKeys.LBM_FORMULA_OPTION.name, option.name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> observeSetting(keyName: String, defaultValue: T): Flow<T> {
        //LogManager.v(
        //    TAG,
        //    "Observing setting: key='$keyName', type='${defaultValue?.let { it::class.simpleName } ?: "null"}'"
        //)

        return dataStore.data
            .catch { exception ->
                LogManager.e(TAG, "Error reading setting '$keyName' from DataStore.", exception)
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                when (defaultValue) {
                    // Nullable String case (e.g., observeSetting(KEY, null as String?))
                    null -> {
                        val key = stringPreferencesKey(keyName)
                        // May return null; that's intended for nullable String
                        preferences[key] as T?
                    }

                    is Boolean -> {
                        val key = booleanPreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is Int -> {
                        val key = intPreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is Long -> {
                        val key = longPreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is Float -> {
                        val key = floatPreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is Double -> {
                        val key = doublePreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is String -> {
                        val key = stringPreferencesKey(keyName)
                        (preferences[key] ?: defaultValue) as T
                    }

                    is Set<*> -> {
                        // only Set<String> is supported by DataStore
                        if (!defaultValue.all { it is String }) {
                            val msg = "Unsupported Set type for preference: $keyName. Only Set<String> is supported."
                            LogManager.e(TAG, msg)
                            throw IllegalArgumentException(msg)
                        }
                        val key = stringSetPreferencesKey(keyName)
                        @Suppress("UNCHECKED_CAST")
                        (preferences[key] ?: defaultValue as Set<String>) as T
                    }

                    else -> {
                        val msg = "Unsupported type for preference: $keyName (Type: ${defaultValue::class.java.name})"
                        LogManager.e(TAG, msg)
                        throw IllegalArgumentException(msg)
                    }
                }
            }
            .distinctUntilChanged() as Flow<T>
    }


    override suspend fun <T> saveSetting(keyName: String, value: T) {
        //LogManager.v(TAG, "Saving setting: key='$keyName', value='$value', type='${value!!::class.simpleName}'")
        try {
            dataStore.edit { preferences ->
                when (value) {
                    is Boolean -> preferences[booleanPreferencesKey(keyName)] = value
                    is Int -> preferences[intPreferencesKey(keyName)] = value
                    is Long -> preferences[longPreferencesKey(keyName)] = value
                    is Float -> preferences[floatPreferencesKey(keyName)] = value
                    is Double -> preferences[doublePreferencesKey(keyName)] = value
                    is String -> preferences[stringPreferencesKey(keyName)] = value
                    is Set<*> -> {
                        if (value.all { it is String }) {
                            @Suppress("UNCHECKED_CAST")
                            preferences[stringSetPreferencesKey(keyName)] = value as Set<String>
                        } else {
                            val errorMsg = "Unsupported Set type for preference: $keyName. Only Set<String> is supported."
                            LogManager.e(TAG, errorMsg)
                            throw IllegalArgumentException(errorMsg) // This will be caught by the outer try-catch
                        }
                    }
                    else -> {
                        val errorMsg = "Unsupported type for preference: $keyName (Type: ${value!!::class.java.name})"
                        LogManager.e(TAG, errorMsg)
                        throw IllegalArgumentException(errorMsg) // This will be caught by the outer try-catch
                    }
                }
            }
           // LogManager.d(TAG, "Successfully saved setting: key='$keyName'")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to save setting: key='$keyName', value='$value'", e)
            // Depending on the app's needs, you might want to rethrow or handle specific exceptions differently.
        }
    }
}
