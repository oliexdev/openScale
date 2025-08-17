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
package com.health.openscale.core.database

import android.content.Context
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
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

// DataStore instance for user settings
val Context.userSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/**
 * Defines keys for user preferences stored in DataStore.
 */
object UserPreferenceKeys {
    // General App Settings
    val IS_FILE_LOGGING_ENABLED = booleanPreferencesKey("is_file_logging_enabled")
    val IS_FIRST_APP_START = booleanPreferencesKey("is_first_app_start")
    val CURRENT_USER_ID = intPreferencesKey("current_user_id")
    val APP_LANGUAGE_CODE = stringPreferencesKey("app_language_code")

    // Settings for specific UI components
    val SELECTED_TYPES_TABLE = stringSetPreferencesKey("selected_types_table") // IDs of measurement types selected for the data table

    // Saved Bluetooth Scale
    val SAVED_BLUETOOTH_SCALE_ADDRESS = stringPreferencesKey("saved_bluetooth_scale_address")
    val SAVED_BLUETOOTH_SCALE_NAME = stringPreferencesKey("saved_bluetooth_scale_name")

    // Settings for chart
    val CHART_SHOW_DATA_POINTS = booleanPreferencesKey("chart_show_data_points")
    val CHART_SMOOTHING_ALGORITHM = stringPreferencesKey("chart_smoothing_algorithm")
    val CHART_SMOOTHING_ALPHA = floatPreferencesKey("chart_smoothing_alpha")
    val CHART_SMOOTHING_WINDOW_SIZE = intPreferencesKey("chart_smoothing_window_size")

    // --- Settings for Automatic Backups ---
    val AUTO_BACKUP_ENABLED_GLOBALLY = booleanPreferencesKey("auto_backup_enabled_globally")
    val AUTO_BACKUP_LOCATION_URI = stringPreferencesKey("auto_backup_location_uri")
    val AUTO_BACKUP_INTERVAL = stringPreferencesKey("auto_backup_interval")
    val AUTO_BACKUP_CREATE_NEW_FILE = booleanPreferencesKey("auto_backup_create_new_file")
    val AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP = longPreferencesKey("auto_backup_last_successful_timestamp")

    // Context strings for screen-specific settings (can be used as prefixes for dynamic keys)
    const val OVERVIEW_SCREEN_CONTEXT = "overview_screen"
    const val GRAPH_SCREEN_CONTEXT = "graph_screen"
    const val STATISTICS_SCREEN_CONTEXT = "statistics_screen"
}

/**
 * Repository interface for accessing and managing user settings.
 */
interface UserSettingsRepository {
    // General app settings
    val isFileLoggingEnabled: Flow<Boolean>
    suspend fun setFileLoggingEnabled(enabled: Boolean)

    val isFirstAppStart: Flow<Boolean>
    suspend fun setFirstAppStartCompleted(completed: Boolean)

    val appLanguageCode: Flow<String?>
    suspend fun setAppLanguageCode(languageCode: String?)

    val currentUserId: Flow<Int?>
    suspend fun setCurrentUserId(userId: Int?)

    // Table settings
    val selectedTableTypeIds: Flow<Set<String>>
    suspend fun saveSelectedTableTypeIds(typeIds: Set<String>)

    // Bluetooth scale settings
    val savedBluetoothScaleAddress: Flow<String?>
    val savedBluetoothScaleName: Flow<String?>
    suspend fun saveBluetoothScale(address: String, name: String?)
    suspend fun clearSavedBluetoothScale()

    val showChartDataPoints: Flow<Boolean>
    suspend fun setShowChartDataPoints(show: Boolean)

    val chartSmoothingAlgorithm: Flow<SmoothingAlgorithm>
    suspend fun setChartSmoothingAlgorithm(algorithm: SmoothingAlgorithm)

    val chartSmoothingAlpha: Flow<Float>
    suspend fun setChartSmoothingAlpha(alpha: Float)

    val chartSmoothingWindowSize: Flow<Int>
    suspend fun setChartSmoothingWindowSize(windowSize: Int)

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
 * Implementation of [UserSettingsRepository] using Jetpack DataStore.
 */
class UserSettingsRepositoryImpl(context: Context) : UserSettingsRepository {
    private val dataStore: DataStore<Preferences> = context.userSettingsDataStore
    private val TAG = "UserSettingsRepository" // Tag for logging

    override val isFileLoggingEnabled: Flow<Boolean> = observeSetting(
        UserPreferenceKeys.IS_FILE_LOGGING_ENABLED.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing isFileLoggingEnabled", exception)
        emit(false) // Fallback to default on error
    }

    override suspend fun setFileLoggingEnabled(enabled: Boolean) {
        LogManager.d(TAG, "Setting file logging enabled to: $enabled")
        saveSetting(UserPreferenceKeys.IS_FILE_LOGGING_ENABLED.name, enabled)
    }

    override val isFirstAppStart: Flow<Boolean> = observeSetting(
        UserPreferenceKeys.IS_FIRST_APP_START.name,
        true // Default to true, meaning it IS the first start until explicitly set otherwise
    ).catch { exception ->
        LogManager.e(TAG, "Error observing isFirstAppStart", exception)
        emit(true) // Fallback to default on error
    }

    override suspend fun setFirstAppStartCompleted(completed: Boolean) {
        LogManager.d(TAG, "Setting first app start completed to: $completed")
        saveSetting(UserPreferenceKeys.IS_FIRST_APP_START.name, completed)
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
            preferences[UserPreferenceKeys.APP_LANGUAGE_CODE]
        }
        .distinctUntilChanged()

    override suspend fun setAppLanguageCode(languageCode: String?) {
        LogManager.d(TAG, "Setting app language code to: $languageCode")
        dataStore.edit { preferences ->
            if (languageCode != null) {
                preferences[UserPreferenceKeys.APP_LANGUAGE_CODE] = languageCode
            } else {
                preferences.remove(UserPreferenceKeys.APP_LANGUAGE_CODE)
            }
        }
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
            preferences[UserPreferenceKeys.CURRENT_USER_ID]
        }
        .distinctUntilChanged()

    override suspend fun setCurrentUserId(userId: Int?) {
        LogManager.d(TAG, "Setting current user ID to: $userId")
        dataStore.edit { preferences ->
            if (userId != null) {
                preferences[UserPreferenceKeys.CURRENT_USER_ID] = userId
            } else {
                preferences.remove(UserPreferenceKeys.CURRENT_USER_ID)
            }
        }
    }

    override val selectedTableTypeIds: Flow<Set<String>> = observeSetting(
        UserPreferenceKeys.SELECTED_TYPES_TABLE.name,
        emptySet<String>()
    ).catch { exception ->
        LogManager.e(TAG, "Error observing selectedTableTypeIds", exception)
        emit(emptySet()) // Fallback to default on error
    }

    override suspend fun saveSelectedTableTypeIds(typeIds: Set<String>) {
        LogManager.d(TAG, "Saving selected table type IDs: $typeIds")
        saveSetting(UserPreferenceKeys.SELECTED_TYPES_TABLE.name, typeIds)
    }

    override val savedBluetoothScaleAddress: Flow<String?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading savedBluetoothScaleAddress from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_ADDRESS]
        }
        .distinctUntilChanged()

    override val savedBluetoothScaleName: Flow<String?> = dataStore.data
        .catch { exception ->
            LogManager.e(TAG, "Error reading savedBluetoothScaleName from DataStore.", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_NAME]
        }
        .distinctUntilChanged()

    override suspend fun saveBluetoothScale(address: String, name: String?) {
        LogManager.i(TAG, "Saving Bluetooth scale: Address=$address, Name=$name")
        dataStore.edit { preferences ->
            preferences[UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_ADDRESS] = address
            if (name != null) {
                preferences[UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_NAME] = name
            } else {
                preferences.remove(UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_NAME)
            }
        }
    }

    override suspend fun clearSavedBluetoothScale() {
        LogManager.i(TAG, "Clearing saved Bluetooth scale information.")
        dataStore.edit { preferences ->
            preferences.remove(UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_ADDRESS)
            preferences.remove(UserPreferenceKeys.SAVED_BLUETOOTH_SCALE_NAME)
        }
    }

    override val showChartDataPoints: Flow<Boolean> = observeSetting(
        UserPreferenceKeys.CHART_SHOW_DATA_POINTS.name,
        true
    ).catch { exception ->
        LogManager.e(TAG, "Error observing showChartDataPoints", exception)
        emit(true)
    }

    override suspend fun setShowChartDataPoints(show: Boolean) {
        LogManager.d(TAG, "Setting showChartDataPoints to: $show")
        saveSetting(UserPreferenceKeys.CHART_SHOW_DATA_POINTS.name, show)
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
            val algorithmName = preferences[UserPreferenceKeys.CHART_SMOOTHING_ALGORITHM]
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
        saveSetting(UserPreferenceKeys.CHART_SMOOTHING_ALGORITHM.name, algorithm.name)
    }

    override val chartSmoothingAlpha: Flow<Float> = observeSetting(
        UserPreferenceKeys.CHART_SMOOTHING_ALPHA.name,
        0.5f
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartSmoothingAlpha", exception)
        emit(0.5f)
    }

    override suspend fun setChartSmoothingAlpha(alpha: Float) {
        val validAlpha = alpha.coerceIn(0.01f, 0.99f)
        LogManager.d(TAG, "Setting chart smoothing alpha to: $validAlpha (raw input: $alpha)")
        saveSetting(UserPreferenceKeys.CHART_SMOOTHING_ALPHA.name, validAlpha)
    }

    override val chartSmoothingWindowSize: Flow<Int> = observeSetting(
        UserPreferenceKeys.CHART_SMOOTHING_WINDOW_SIZE.name,
        5
    ).catch { exception ->
        LogManager.e(TAG, "Error observing chartSmoothingWindowSize", exception)
        emit(5)
    }

    override suspend fun setChartSmoothingWindowSize(windowSize: Int) {
        val validWindowSize = windowSize.coerceIn(2, 50)
        LogManager.d(TAG, "Setting chart smoothing window size to: $validWindowSize (raw input: $windowSize)")
        saveSetting(UserPreferenceKeys.CHART_SMOOTHING_WINDOW_SIZE.name, validWindowSize)
    }

    override val autoBackupEnabledGlobally: Flow<Boolean> = observeSetting(
        UserPreferenceKeys.AUTO_BACKUP_ENABLED_GLOBALLY.name,
        false // Standardmäßig deaktiviert
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupEnabledGlobally", exception)
        emit(false)
    }

    override suspend fun setAutoBackupEnabledGlobally(enabled: Boolean) {
        LogManager.d(TAG, "Setting autoBackupEnabledGlobally to: $enabled")
        saveSetting(UserPreferenceKeys.AUTO_BACKUP_ENABLED_GLOBALLY.name, enabled)
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
            preferences[UserPreferenceKeys.AUTO_BACKUP_LOCATION_URI]
        }
        .distinctUntilChanged()

    override suspend fun setAutoBackupLocationUri(uri: String?) {
        LogManager.d(TAG, "Setting autoBackupLocationUri to: $uri")
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[UserPreferenceKeys.AUTO_BACKUP_LOCATION_URI] = uri
            } else {
                preferences.remove(UserPreferenceKeys.AUTO_BACKUP_LOCATION_URI)
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
            val intervalName = preferences[UserPreferenceKeys.AUTO_BACKUP_INTERVAL]
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
        saveSetting(UserPreferenceKeys.AUTO_BACKUP_INTERVAL.name, interval.name)
    }


    override val autoBackupCreateNewFile: Flow<Boolean> = observeSetting(
        UserPreferenceKeys.AUTO_BACKUP_CREATE_NEW_FILE.name,
        false
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupCreateNewFile", exception)
        emit(false)
    }

    override suspend fun setAutoBackupCreateNewFile(createNew: Boolean) {
        LogManager.d(TAG, "Setting autoBackupCreateNewFile to: $createNew")
        saveSetting(UserPreferenceKeys.AUTO_BACKUP_CREATE_NEW_FILE.name, createNew)
    }

    override val autoBackupLastSuccessfulTimestamp: Flow<Long> = observeSetting(
        UserPreferenceKeys.AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP.name,
        0L
    ).catch { exception ->
        LogManager.e(TAG, "Error observing autoBackupLastSuccessfulTimestamp", exception)
        emit(0L) // Fallback
    }

    override suspend fun setAutoBackupLastSuccessfulTimestamp(timestamp: Long) {
        LogManager.d(TAG, "Setting autoBackupLastSuccessfulTimestamp to: $timestamp")
        saveSetting(UserPreferenceKeys.AUTO_BACKUP_LAST_SUCCESSFUL_TIMESTAMP.name, timestamp)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> observeSetting(keyName: String, defaultValue: T): Flow<T> {
        LogManager.v(TAG, "Observing setting: key='$keyName', type='${defaultValue!!::class.simpleName}'")
        return dataStore.data
            .catch { exception ->
                LogManager.e(TAG, "Error reading setting '$keyName' from DataStore.", exception)
                if (exception is IOException) {
                    // IOExceptions are common if DataStore is corrupted or inaccessible
                    emit(emptyPreferences())
                } else {
                    // Rethrow other critical exceptions
                    throw exception
                }
            }
            .map { preferences ->
                val preferenceKey = when (defaultValue) {
                    is Boolean -> booleanPreferencesKey(keyName)
                    is Int -> intPreferencesKey(keyName)
                    is Long -> longPreferencesKey(keyName)
                    is Float -> floatPreferencesKey(keyName)
                    is Double -> doublePreferencesKey(keyName)
                    is String -> stringPreferencesKey(keyName)
                    is Set<*> -> {
                        // Ensure all elements in the set are Strings, as DataStore only supports Set<String>
                        if (defaultValue.all { it is String }) {
                            stringSetPreferencesKey(keyName) as Preferences.Key<T>
                        } else {
                            val errorMsg = "Unsupported Set type for preference: $keyName. Only Set<String> is supported."
                            LogManager.e(TAG, errorMsg)
                            throw IllegalArgumentException(errorMsg)
                        }
                    }
                    else -> {
                        val errorMsg = "Unsupported type for preference: $keyName (Type: ${defaultValue::class.java.name})"
                        LogManager.e(TAG, errorMsg)
                        throw IllegalArgumentException(errorMsg)
                    }
                }
                preferences[preferenceKey as Preferences.Key<T>] ?: defaultValue.also {
                    LogManager.v(TAG, "Setting '$keyName' not found, returning default value: $it")
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun <T> saveSetting(keyName: String, value: T) {
        LogManager.v(TAG, "Saving setting: key='$keyName', value='$value', type='${value!!::class.simpleName}'")
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
                        val errorMsg = "Unsupported type for preference: $keyName (Type: ${value::class.java.name})"
                        LogManager.e(TAG, errorMsg)
                        throw IllegalArgumentException(errorMsg) // This will be caught by the outer try-catch
                    }
                }
            }
            LogManager.d(TAG, "Successfully saved setting: key='$keyName'")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to save setting: key='$keyName', value='$value'", e)
            // Depending on the app's needs, you might want to rethrow or handle specific exceptions differently.
        }
    }
}

/**
 * Provides an instance of [UserSettingsRepository].
 * This function should be used for dependency injection.
 */
fun provideUserSettingsRepository(context: Context): UserSettingsRepository {
    return UserSettingsRepositoryImpl(context.applicationContext)
}
