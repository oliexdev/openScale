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
package com.health.openscale.ui.screen.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.Converters
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.SharedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQueries.localDate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sealed class representing events related to Storage Access Framework (SAF) operations.
 */
sealed class SafEvent {
    data class RequestCreateFile(val suggestedName: String, val actionId: String, val userId: Int) : SafEvent()
    data class RequestOpenFile(val actionId: String, val userId: Int) : SafEvent()
}

/**
 * ViewModel for settings-related screens.
 */
class SettingsViewModel(
    private val sharedViewModel: SharedViewModel
) : ViewModel() {

    private val repository = sharedViewModel.databaseRepository
    private val userSettingsRepository = sharedViewModel.userSettingRepository

    private val _appLanguageCode = MutableStateFlow(getDefaultAppLanguage())
    val appLanguageCode: StateFlow<String> = _appLanguageCode.asStateFlow()

    val allUsers: StateFlow<List<User>> = sharedViewModel.allUsers

    private val _showUserSelectionDialogForExport = MutableStateFlow(false)
    val showUserSelectionDialogForExport: StateFlow<Boolean> = _showUserSelectionDialogForExport.asStateFlow()

    private val _showUserSelectionDialogForImport = MutableStateFlow(false)
    val showUserSelectionDialogForImport: StateFlow<Boolean> = _showUserSelectionDialogForImport.asStateFlow()

    private val _showUserSelectionDialogForDelete = MutableStateFlow(false)
    val showUserSelectionDialogForDelete: StateFlow<Boolean> = _showUserSelectionDialogForDelete.asStateFlow()

    private val _userPendingDeletion = MutableStateFlow<User?>(null)
    val userPendingDeletion: StateFlow<User?> = _userPendingDeletion.asStateFlow()

    private val _showDeleteConfirmationDialog = MutableStateFlow(false)
    val showDeleteConfirmationDialog: StateFlow<Boolean> = _showDeleteConfirmationDialog.asStateFlow()

    private val _showDeleteEntireDatabaseConfirmationDialog = MutableStateFlow(false)
    val showDeleteEntireDatabaseConfirmationDialog: StateFlow<Boolean> = _showDeleteEntireDatabaseConfirmationDialog.asStateFlow()

    private val _isLoadingExport = MutableStateFlow(false)
    val isLoadingExport: StateFlow<Boolean> = _isLoadingExport.asStateFlow()

    private val _isLoadingImport = MutableStateFlow(false)
    val isLoadingImport: StateFlow<Boolean> = _isLoadingImport.asStateFlow()

    private val _isLoadingDeletion = MutableStateFlow(false)
    val isLoadingDeletion: StateFlow<Boolean> = _isLoadingDeletion.asStateFlow()

    private val _isLoadingBackup = MutableStateFlow(false)
    val isLoadingBackup: StateFlow<Boolean> = _isLoadingBackup.asStateFlow()

    private val _isLoadingRestore = MutableStateFlow(false)
    val isLoadingRestore: StateFlow<Boolean> = _isLoadingRestore.asStateFlow()

    private val _isLoadingEntireDatabaseDeletion = MutableStateFlow(false)
    val isLoadingEntireDatabaseDeletion: StateFlow<Boolean> = _isLoadingEntireDatabaseDeletion.asStateFlow()

    companion object {
        private const val TAG = "SettingsViewModel"
        const val ACTION_ID_EXPORT_USER_DATA = "export_user_data"
        const val ACTION_ID_IMPORT_USER_DATA = "import_user_data"
        const val ACTION_ID_BACKUP_DB = "backup_database"
        const val ACTION_ID_RESTORE_DB = "restore_database"
    }

    private val _safEvent = MutableSharedFlow<SafEvent>()
    val safEvent = _safEvent.asSharedFlow()

    private var currentActionUserId: Int? = null

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_TIME
    private val flexibleTimeFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter(Locale.ROOT)

    init {
        LogManager.d(TAG, "Initializing SettingsViewModel...")
        viewModelScope.launch {
            userSettingsRepository.appLanguageCode
                .map { storedLanguageCode ->
                    LogManager.d(TAG, "Observed stored language code from repository: $storedLanguageCode")
                    storedLanguageCode ?: getDefaultAppLanguage()
                }
                .catch { exception ->
                    LogManager.e(TAG, "Error collecting app language code from repository", exception)
                    emit(getDefaultAppLanguage())
                }
                .collect { effectiveLanguageCode ->
                    if (_appLanguageCode.value != effectiveLanguageCode) {
                        _appLanguageCode.value = effectiveLanguageCode
                        LogManager.i(TAG, "App language in ViewModel updated to: $effectiveLanguageCode")
                    } else {
                        LogManager.d(TAG, "App language in ViewModel is already: $effectiveLanguageCode, no update needed.")
                    }
                }
        }
    }

    fun setAppLanguage(languageCode: String) {
        if (languageCode.isBlank()) {
            LogManager.w(TAG, "Attempted to set a blank language code. Ignoring.")
            return
        }

        viewModelScope.launch {
            try {
                LogManager.d(TAG, "Attempting to set app language preference to: $languageCode via repository")
                userSettingsRepository.setAppLanguageCode(languageCode)
                LogManager.i(TAG, "Successfully requested to set app language preference to: $languageCode in repository.")
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to set app language preference to: $languageCode via repository", e)
            }
        }
    }

    fun getDefaultAppLanguage(): String {
        val supportedAppLanguages = listOf("en", "de", "es", "fr")
        val systemLanguage = Locale.getDefault().language
        val defaultLang = if (systemLanguage in supportedAppLanguages) {
            systemLanguage
        } else {
            "en"
        }
        LogManager.d(TAG, "Determined default app language: $defaultLang (System: $systemLanguage)")
        return defaultLang
    }

    fun performCsvExport(userId: Int, uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingExport.value = true
            LogManager.i(TAG, "Starting CSV export for user ID: $userId to URI: $uri")
            try {
                val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()
                val exportableValueTypes = allAppTypes.filter {
                    it.key != MeasurementTypeKey.DATE && it.key != MeasurementTypeKey.TIME
                }
                val valueColumnKeys = exportableValueTypes
                    .map { it.key.name }
                    .distinct()

                val dateColumnKey = MeasurementTypeKey.DATE.name
                val timeColumnKey = MeasurementTypeKey.TIME.name

                val allCsvColumnKeys = mutableListOf(dateColumnKey, timeColumnKey)
                allCsvColumnKeys.addAll(valueColumnKeys.sorted())

                if (valueColumnKeys.isEmpty()) {
                    LogManager.w(TAG, "No specific data fields (value columns) defined for export for user ID: $userId.")
                    sharedViewModel.showSnackbar(R.string.export_error_no_specific_fields)
                }

                val userMeasurementsWithValues: List<MeasurementWithValues> =
                    repository.getMeasurementsWithValuesForUser(userId).first()

                if (userMeasurementsWithValues.isEmpty()) {
                    LogManager.i(TAG, "No measurements found for User ID $userId to export.")
                    sharedViewModel.showSnackbar(R.string.export_error_no_measurements)
                    _isLoadingExport.value = false
                    return@launch
                }

                val csvRowsData = mutableListOf<Map<String, String?>>()
                // ... (CSV data preparation logic as before) ...
                userMeasurementsWithValues.forEach { measurementData ->
                    val mainTimestamp = measurementData.measurement.timestamp
                    val valuesMap = mutableMapOf<String, String?>()
                    val instant = Instant.ofEpochMilli(mainTimestamp)
                    val zonedDateTime = instant.atZone(ZoneId.systemDefault())

                    valuesMap[dateColumnKey] = dateFormatter.format(zonedDateTime)
                    valuesMap[timeColumnKey] = timeFormatter.format(zonedDateTime)

                    measurementData.values.forEach { mvWithType ->
                        val typeEntity = mvWithType.type
                        val valueEntity = mvWithType.value
                        if (typeEntity.key != MeasurementTypeKey.DATE && typeEntity.key != MeasurementTypeKey.TIME && valueColumnKeys.contains(typeEntity.key.name)
                        ) {
                            val valueAsString: String? = when (typeEntity.inputType) {
                                InputFieldType.TEXT -> valueEntity.textValue
                                InputFieldType.FLOAT -> valueEntity.floatValue?.toString()
                                InputFieldType.INT -> valueEntity.intValue?.toString()
                                InputFieldType.DATE -> valueEntity.dateValue?.let {
                                    dateFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                                }
                                InputFieldType.TIME -> valueEntity.dateValue?.let {
                                    timeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                                }
                            }
                            typeEntity.key.name.let { keyName -> valuesMap[keyName] = valueAsString }
                        }
                    }
                    csvRowsData.add(valuesMap)
                }


                if (csvRowsData.isEmpty()) {
                    LogManager.w(TAG, "No exportable measurement values found for User ID $userId after transformation.")
                    sharedViewModel.showSnackbar(R.string.export_error_no_exportable_values)
                    _isLoadingExport.value = false
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    var exportSuccessful = false
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        csvWriter().open(outputStream) {
                            writeRow(allCsvColumnKeys)
                            csvRowsData.forEach { rowMap ->
                                val dataRow = allCsvColumnKeys.map { key -> rowMap[key] }
                                writeRow(dataRow)
                            }
                        }
                        exportSuccessful = true
                        LogManager.d(TAG, "CSV data written successfully for User ID $userId to URI: $uri.")
                    } ?: run {
                        sharedViewModel.showSnackbar(R.string.export_error_cannot_create_file)
                        LogManager.e(TAG, "Export failed for user ID $userId: Could not open OutputStream for Uri: $uri")
                    }

                    if (exportSuccessful) {
                        sharedViewModel.showSnackbar(R.string.export_successful)
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during CSV export for User ID $userId to URI: $uri", e)
                val errorMessage = e.localizedMessage ?: "Unknown error" // In a real app, use R.string.settings_unknown_error
                sharedViewModel.showSnackbar(R.string.export_error_generic, listOf(errorMessage))
            } finally {
                _isLoadingExport.value = false
                LogManager.i(TAG, "CSV export process finished for user ID: $userId.")
            }
        }
    }

    fun performCsvImport(userId: Int, uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingImport.value = true
            LogManager.i(TAG, "Starting CSV import for user ID: $userId from URI: $uri")
            var linesSkippedMissingDate = 0
            var linesSkippedDateParseError = 0
            var valuesSkippedParseError = 0
            var importedMeasurementsCount = 0

            try {
                // ... (Rest of the import logic including CSV parsing as before) ...
                val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()

                val dateColumnKey = MeasurementTypeKey.DATE.name
                val timeColumnKey = MeasurementTypeKey.TIME.name
                val dateTimeColumnKey = "dateTime"
                val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

                val newMeasurementsToSave = mutableListOf<Pair<Measurement, List<MeasurementValue>>>()

                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        csvReader {
                            skipEmptyLine = true
                            quoteChar = '"'
                        }.open(inputStream) {
                            var header: List<String>? = null
                            var dateColumnIndex = -1
                            var timeColumnIndex = -1
                            var dateTimeColumnIndex = -1
                            val valueColumnMap = mutableMapOf<Int, MeasurementType>()

                            readAllAsSequence().forEachIndexed { rowIndex, row ->
                                if (rowIndex == 0) { // Header row
                                    header = row
                                    dateColumnIndex = header.indexOfFirst { it.equals(dateColumnKey, ignoreCase = true) }
                                    timeColumnIndex = header.indexOfFirst { it.equals(timeColumnKey, ignoreCase = true) }
                                    dateTimeColumnIndex = header.indexOfFirst { it.equals(dateTimeColumnKey, ignoreCase = true) }

                                    if (dateColumnIndex == -1 && dateTimeColumnIndex == -1) {
                                        LogManager.e(TAG, "CSV import for user $userId: Mandatory date information (expected '$dateColumnKey' or '$dateTimeColumnKey') not found in header: $header. Aborting import.")
                                        sharedViewModel.showSnackbar(R.string.import_error_missing_date_column, listOf(dateColumnKey))
                                        _isLoadingImport.value = false
                                        return@forEachIndexed // Exit the coroutine
                                    }


                                    header.forEachIndexed { colIdx, csvColumnName ->
                                        // Skip date and time columns as they are handled separately
                                        if (colIdx == dateColumnIndex || colIdx == timeColumnIndex || colIdx == dateTimeColumnIndex) {
                                            return@forEachIndexed
                                        }

                                        // Attempt to find a matching MeasurementType
                                        // 1. Check against MeasurementTypeKey.name (for predefined types like WEIGHT, FAT, etc.)
                                        var matchedType = allAppTypes.find { type ->
                                            type.key.name.equals(csvColumnName, ignoreCase = true) &&
                                                    type.key != MeasurementTypeKey.DATE && // Ensure we don't try to map DATE/TIME as value columns here
                                                    type.key != MeasurementTypeKey.TIME
                                        }

                                        // 2. If not found, check against MeasurementType.name for CUSTOM types
                                        if (matchedType == null) {
                                            matchedType = allAppTypes.find { type ->
                                                type.key == MeasurementTypeKey.CUSTOM &&
                                                        type.name.equals(csvColumnName, ignoreCase = true)
                                            }
                                        }

                                        if (matchedType != null) {
                                            if (matchedType.isEnabled) { // Only map enabled types
                                                valueColumnMap[colIdx] = matchedType
                                                LogManager.d(TAG, "CSV import for user $userId: Column '$csvColumnName' (idx: $colIdx) mapped to existing type: ${matchedType.key.name} (ID: ${matchedType.id}, Custom Name: ${matchedType.name ?: "N/A"})")
                                            } else {
                                                LogManager.w(TAG, "CSV import for user $userId: Column '$csvColumnName' matches disabled type '${matchedType.key.name}'. It will be ignored.")
                                            }
                                        } else {
                                            LogManager.w(TAG, "CSV import for user $userId: Column '$csvColumnName' (idx: $colIdx) in CSV not found or couldn't be mapped to any known/custom enabled measurement types. It will be ignored.")
                                        }
                                    }

                                    if (valueColumnMap.isEmpty()) {
                                        LogManager.w(TAG, "CSV import for user $userId: No measurement value columns in CSV could be mapped to known types after processing header. CSV Header: $header")
                                        // Decide if you want to abort here or continue (might result in no data imported)
                                    }
                                    return@forEachIndexed // Continue to next row
                                }

                                if (header == null) throw IOException("CSV header not found or processed.") // Should not happen

                                var parsedLocalDate: LocalDate? = null
                                var parsedLocalTime: LocalTime? = null
                                val dateTimeString = if (dateTimeColumnIndex != -1) row.getOrNull(dateTimeColumnIndex) else null
                                val dateString = if (dateColumnIndex != -1) row.getOrNull(dateColumnIndex) else null
                                val timeStringFromColumn = if (timeColumnIndex != -1) row.getOrNull(timeColumnIndex) else null

                                if (!dateTimeString.isNullOrBlank()) {
                                    try {
                                        val tempDateTime = LocalDateTime.parse(dateTimeString, dateTimeFormatter)
                                        parsedLocalDate = tempDateTime.toLocalDate()
                                        parsedLocalTime = tempDateTime.toLocalTime()
                                        LogManager.d(TAG, "CSV import for user $userId: Row ${rowIndex + 1} parsed dateTime: $dateTimeString -> Date: $parsedLocalDate, Time: $parsedLocalTime")
                                    } catch (e: DateTimeParseException) {
                                        LogManager.w(TAG, "CSV import for user $userId: Error parsing dateTime '$dateTimeString' (expected YYYY-MM-DD HH:mm) in row ${rowIndex + 1}. Trying separate date/time columns or skipping.", e)
                                    }
                                }

                                if (parsedLocalDate == null && !dateString.isNullOrBlank()) {
                                    try {
                                        parsedLocalDate = LocalDate.parse(dateString, dateFormatter)
                                        LogManager.d(TAG, "CSV import for user $userId: Row ${rowIndex + 1} parsed dateString: $dateString -> Date: $parsedLocalDate")
                                    } catch (e: DateTimeParseException) {
                                        LogManager.w(TAG, "CSV import for user $userId: Error parsing date '$dateString' (expected YYYY-MM-DD) in row ${rowIndex + 1}. Skipping row.", e)
                                        linesSkippedDateParseError++
                                        return@forEachIndexed
                                    }
                                }

                                if (parsedLocalTime == null) {
                                    parsedLocalTime = if (timeStringFromColumn.isNullOrBlank()) {
                                        LocalTime.NOON
                                    } else {
                                        try { LocalTime.parse(timeStringFromColumn, timeFormatter) }
                                        catch (e1: DateTimeParseException) {
                                            try { LocalTime.parse(timeStringFromColumn, flexibleTimeFormatter) }
                                            catch (e2: DateTimeParseException) {
                                                LogManager.w(TAG, "CSV import for user $userId: Time '$timeStringFromColumn' in row ${rowIndex + 1} could not be parsed. Using default.", e2)
                                                LocalTime.NOON
                                            }
                                        }
                                    }
                                    LogManager.d(TAG, "CSV import for user $userId: Row ${rowIndex + 1} parsed timeString: $timeStringFromColumn -> Time: $parsedLocalTime")
                                }

                                val localDateTime = LocalDateTime.of(parsedLocalDate, parsedLocalTime)
                                val timestampMillis = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val measurement = Measurement(userId = userId, timestamp = timestampMillis)
                                val measurementValues = mutableListOf<MeasurementValue>()

                                valueColumnMap.forEach { (colIdx, type) ->
                                    val valueString = row.getOrNull(colIdx)
                                    if (!valueString.isNullOrBlank()) {
                                        try {
                                            var skipThisValue = false

                                            val floatVal = if (type.inputType == InputFieldType.FLOAT) valueString.toFloatOrNull() else null
                                            val intVal = if (type.inputType == InputFieldType.INT) valueString.toIntOrNull() else null

                                            if (type.inputType == InputFieldType.FLOAT && floatVal == 0.0f) {
                                                LogManager.d(TAG, "CSV import for user $userId: Skipping value '0' for FLOAT type '${type.key.name}' in row ${rowIndex + 1}.")
                                                skipThisValue = true
                                            }
                                            if (type.inputType == InputFieldType.INT && intVal == 0) {
                                                LogManager.d(TAG, "CSV import for user $userId: Skipping value '0' for INT type '${type.key.name}' in row ${rowIndex + 1}.")
                                                skipThisValue = true
                                            }

                                            if (!skipThisValue) {
                                                val mv = MeasurementValue(
                                                    typeId = type.id,
                                                    measurementId = 0, // Will be set by Room
                                                    textValue = if (type.inputType == InputFieldType.TEXT) valueString else null,
                                                    floatValue = if (type.inputType == InputFieldType.FLOAT) valueString.toFloatOrNull() else null,
                                                    intValue = if (type.inputType == InputFieldType.INT) valueString.toIntOrNull() else null,
                                                    dateValue = when (type.inputType) {
                                                        InputFieldType.DATE -> LocalDate.parse(
                                                            valueString,
                                                            dateFormatter
                                                        ).atStartOfDay(ZoneId.systemDefault())
                                                            .toInstant().toEpochMilli()

                                                        InputFieldType.TIME -> {
                                                            val parsedTime = try {
                                                                LocalTime.parse(
                                                                    valueString,
                                                                    timeFormatter
                                                                )
                                                            } catch (e: Exception) {
                                                                LocalTime.parse(
                                                                    valueString,
                                                                    flexibleTimeFormatter
                                                                )
                                                            }
                                                            parsedTime.atDate(
                                                                LocalDate.of(
                                                                    1970,
                                                                    1,
                                                                    1
                                                                )
                                                            ).atZone(ZoneId.systemDefault())
                                                                .toInstant().toEpochMilli()
                                                        }

                                                        else -> null
                                                    }
                                                )
                                                var isValidValue = true
                                                if (type.inputType == InputFieldType.FLOAT && mv.floatValue == null) isValidValue =
                                                    false
                                                if (type.inputType == InputFieldType.INT && mv.intValue == null) isValidValue =
                                                    false
                                                if (isValidValue) {
                                                    measurementValues.add(mv)
                                                } else {
                                                    LogManager.w(
                                                        TAG,
                                                        "CSV import for user $userId: Could not parse value '$valueString' for type '${type.key.name}' in row ${rowIndex + 1}."
                                                    )
                                                    valuesSkippedParseError++
                                                }
                                            }
                                        } catch (e: Exception) {
                                            LogManager.w(TAG, "CSV import for user $userId: Error processing value '$valueString' for type '${type.key.name}' in row ${rowIndex + 1}.", e)
                                            valuesSkippedParseError++
                                        }
                                    }
                                }
                                if (measurementValues.isNotEmpty()) {
                                    newMeasurementsToSave.add(measurement to measurementValues)
                                } else if (valueColumnMap.isNotEmpty()){
                                    LogManager.d(TAG,"CSV import for user $userId: Row ${rowIndex + 1} for $localDateTime resulted in no valid measurement values. Skipping.")
                                }
                            }
                        }
                    } ?: throw IOException("Could not open InputStream for Uri: $uri")

                    if (newMeasurementsToSave.isNotEmpty()) {
                        val insertedMeasurementIds = repository.insertMeasurementsWithValues(newMeasurementsToSave)
                        importedMeasurementsCount = insertedMeasurementIds.size
                        LogManager.i(TAG, "CSV Import for User ID $userId successful. $importedMeasurementsCount measurements imported.")

                        if (insertedMeasurementIds.isNotEmpty()) {
                            LogManager.d(TAG, "Starting derived value recalculation for ${insertedMeasurementIds.size} imported measurements.")
                            insertedMeasurementIds.forEach { measurementId ->
                                try {
                                    repository.recalculateDerivedValuesForMeasurement(measurementId.toInt())
                                } catch (e: Exception) {
                                    LogManager.e(TAG, "Error recalculating derived values for measurement ID $measurementId post-import.", e)
                                }
                            }
                            LogManager.i(TAG, "Derived value recalculation for ${insertedMeasurementIds.size} imported measurements completed.")
                        }

                        var detailsForMessage = ""
                        if (linesSkippedMissingDate > 0) {
                            detailsForMessage += " ($linesSkippedMissingDate rows skipped due to missing dates"
                        }
                        if (linesSkippedDateParseError > 0) {
                            detailsForMessage += if(detailsForMessage.contains("(")) ", " else " ("
                            detailsForMessage += "$linesSkippedDateParseError rows skipped due to date parsing errors"
                        }
                        if (valuesSkippedParseError > 0) {
                            detailsForMessage += if(detailsForMessage.contains("(")) ", " else " ("
                            detailsForMessage += "$valuesSkippedParseError values skipped"
                        }
                        if (detailsForMessage.isNotEmpty()) detailsForMessage += ")"


                        if (detailsForMessage.isNotEmpty()) {
                            sharedViewModel.showSnackbar(R.string.import_successful_records_with_details, listOf(importedMeasurementsCount, detailsForMessage))
                        } else {
                            sharedViewModel.showSnackbar(R.string.import_successful_records, listOf(importedMeasurementsCount))
                        }

                    } else {
                        LogManager.w(TAG, "No valid data found in CSV for User ID $userId or all rows had errors.")
                        sharedViewModel.showSnackbar(R.string.import_error_no_valid_data)
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during CSV import for User ID $userId from URI: $uri", e)
                val userErrorMessage = when {
                    e is IOException && e.message?.contains("CSV header is missing the mandatory column 'date'") == true ->
                        // Assuming R.string.import_error_missing_date_column takes dateColumnKey as an argument
                        sharedViewModel.showSnackbar(R.string.import_error_missing_date_column)
                    e is IOException && e.message?.contains("Could not open InputStream") == true ->
                        sharedViewModel.showSnackbar(R.string.import_error_cannot_read_file)
                    else -> {
                        val errorMsg = e.localizedMessage ?: "Unknown error" // Use R.string.settings_unknown_error
                        sharedViewModel.showSnackbar(R.string.import_error_generic, listOf(errorMsg))
                    }
                }
            } finally {
                _isLoadingImport.value = false
                LogManager.i(TAG, "CSV import process finished for user ID: $userId. Imported: $importedMeasurementsCount, Skipped (missing date): $linesSkippedMissingDate, Skipped (date parse error): $linesSkippedDateParseError, Values skipped (parse error): $valuesSkippedParseError.")
            }
        }
    }

    fun startExportProcess() {
        viewModelScope.launch {
            if (allUsers.value.isEmpty()) {
                LogManager.i(TAG, "Export process start: No users available for export.")
                sharedViewModel.showSnackbar(R.string.export_no_users_available)
                return@launch
            }
            if (allUsers.value.size == 1) {
                val userId = allUsers.value.first().id
                LogManager.d(TAG, "Export process start: Single user (ID: $userId) found, proceeding directly.")
                initiateActualExport(userId)
            } else {
                currentActionUserId = null
                _showUserSelectionDialogForExport.value = true
                LogManager.d(TAG, "Export process start: Multiple users found, showing user selection dialog.")
            }
        }
    }

    fun proceedWithExportForUser(userId: Int) {
        _showUserSelectionDialogForExport.value = false
        LogManager.i(TAG, "Proceeding with export for selected user ID: $userId.")
        initiateActualExport(userId)
    }

    fun cancelUserSelectionForExport() {
        _showUserSelectionDialogForExport.value = false
        currentActionUserId = null
        LogManager.d(TAG, "User selection for export cancelled.")
    }

    private fun initiateActualExport(userId: Int) {
        currentActionUserId = userId
        viewModelScope.launch {
            val user = allUsers.value.find { it.id == userId }
            val userNamePart = user?.name?.replace("\\s+".toRegex(), "_")?.take(20) ?: "user$userId"
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val suggestedName = "openScale_export_${userNamePart}_${timeStamp}.csv"
            _safEvent.emit(SafEvent.RequestCreateFile(suggestedName, ACTION_ID_EXPORT_USER_DATA, userId))
            LogManager.i(TAG, "Initiating actual export for user ID: $userId. Suggested file name: $suggestedName. SAF event emitted.")
        }
    }

    fun startImportProcess() {
        viewModelScope.launch {
            if (allUsers.value.isEmpty()) {
                LogManager.i(TAG, "Import process start: No users available for import.")
                sharedViewModel.showSnackbar(R.string.import_no_users_available)
                return@launch
            }
            if (allUsers.value.size == 1) {
                val userId = allUsers.value.first().id
                LogManager.d(TAG, "Import process start: Single user (ID: $userId) found, proceeding directly.")
                initiateActualImport(userId)
            } else {
                currentActionUserId = null
                _showUserSelectionDialogForImport.value = true
                LogManager.d(TAG, "Import process start: Multiple users found, showing user selection dialog.")
            }
        }
    }

    fun proceedWithImportForUser(userId: Int) {
        _showUserSelectionDialogForImport.value = false
        LogManager.i(TAG, "Proceeding with import for selected user ID: $userId.")
        initiateActualImport(userId)
    }

    fun cancelUserSelectionForImport() {
        _showUserSelectionDialogForImport.value = false
        currentActionUserId = null
        LogManager.d(TAG, "User selection for import cancelled.")
    }

    private fun initiateActualImport(userId: Int) {
        currentActionUserId = userId
        viewModelScope.launch {
            _safEvent.emit(SafEvent.RequestOpenFile(ACTION_ID_IMPORT_USER_DATA, userId))
            LogManager.i(TAG, "Initiating actual import for user ID: $userId. SAF event emitted.")
        }
    }

    fun initiateDeleteAllUserDataProcess() {
        viewModelScope.launch {
            val userList = allUsers.value
            if (userList.size > 1) {
                LogManager.d(TAG, "Initiate delete user data: Multiple users found, showing selection dialog.")
                _showUserSelectionDialogForDelete.value = true
            } else if (userList.isNotEmpty()) {
                val userToDelete = userList.first()
                LogManager.d(TAG, "Initiate delete user data: Single user (ID: ${userToDelete.id}, Name: ${userToDelete.name}) found, proceeding to confirmation.")
                _userPendingDeletion.value = userToDelete
                _showDeleteConfirmationDialog.value = true
            } else {
                LogManager.i(TAG, "Initiate delete user data: No user data available to delete.")
                sharedViewModel.showSnackbar(R.string.delete_data_no_users_available)
            }
        }
    }

    fun proceedWithDeleteForUser(userId: Int) {
        viewModelScope.launch {
            val user = allUsers.value.find { it.id == userId }
            _userPendingDeletion.value = user
            _showUserSelectionDialogForDelete.value = false
            if (user != null) {
                LogManager.i(TAG, "Proceeding with delete confirmation for user ID: ${user.id}, Name: ${user.name}.")
                _showDeleteConfirmationDialog.value = true
            } else {
                LogManager.w(TAG, "Proceed with delete: User ID $userId not found after selection.")
            }
        }
    }

    fun cancelUserSelectionForDelete() {
        _showUserSelectionDialogForDelete.value = false
        _userPendingDeletion.value = null
        LogManager.d(TAG, "User selection for delete cancelled.")
    }

    fun confirmActualDeletion() {
        _userPendingDeletion.value?.let { userToDelete ->
            viewModelScope.launch {
                _isLoadingDeletion.value = true
                LogManager.i(TAG, "Confirming actual deletion of all data for user ID: ${userToDelete.id}, Name: ${userToDelete.name}.")
                try {
                    val deletedRowCount = repository.deleteAllMeasurementsForUser(userToDelete.id)
                    if (deletedRowCount > 0) {
                        LogManager.i(TAG, "Data for User ${userToDelete.name} (ID: ${userToDelete.id}) successfully deleted. $deletedRowCount measurement records removed.")
                        sharedViewModel.showSnackbar(R.string.delete_data_user_successful, listOf(userToDelete.name))
                    } else {
                        LogManager.i(TAG, "No measurement data found to delete for User ${userToDelete.name} (ID: ${userToDelete.id}).")
                        sharedViewModel.showSnackbar(R.string.delete_data_user_no_data_found, listOf(userToDelete.name))
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error deleting data for User ${userToDelete.name} (ID: ${userToDelete.id})", e)
                    sharedViewModel.showSnackbar(R.string.delete_data_user_error, listOf(userToDelete.name))
                } finally {
                    _isLoadingDeletion.value = false
                    _showDeleteConfirmationDialog.value = false
                    _userPendingDeletion.value = null
                    LogManager.d(TAG, "Actual deletion process finished for user ID: ${userToDelete.id}.")
                }
            }
        } ?: run {
            viewModelScope.launch {
                sharedViewModel.showSnackbar(R.string.delete_data_error_no_user_selected)
                _showDeleteConfirmationDialog.value = false
            }
            LogManager.w(TAG, "confirmActualDeletion called without a user pending deletion.")
        }
    }

    fun cancelDeleteConfirmation() {
        _showDeleteConfirmationDialog.value = false
        LogManager.d(TAG, "Actual deletion confirmation cancelled for user: ${_userPendingDeletion.value?.name ?: "N/A"}.")
    }

    fun startDatabaseRestore() {
        viewModelScope.launch {
            _safEvent.emit(SafEvent.RequestOpenFile(ACTION_ID_RESTORE_DB, userId = 0 ))
            LogManager.i(TAG, "Database restore process started. SAF event emitted.")
        }
    }

    fun startDatabaseBackup() {
        viewModelScope.launch {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dbName = repository.getDatabaseName()
            val suggestedName = "${dbName}_backup_${timeStamp}.zip"
            _safEvent.emit(SafEvent.RequestCreateFile(suggestedName, ACTION_ID_BACKUP_DB, userId = 0))
            LogManager.i(TAG, "Database backup process started. Suggested name: $suggestedName. SAF event emitted.")
        }
    }

    fun performDatabaseBackup(backupUri: Uri, applicationContext: android.content.Context, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingBackup.value = true
            LogManager.i(TAG, "Performing database backup to URI: $backupUri")
            try {
                val dbName = repository.getDatabaseName()
                val dbFile = applicationContext.getDatabasePath(dbName)
                val dbDir = dbFile.parentFile ?: run {
                    LogManager.e(TAG, "Database backup error: Database directory could not be determined for $dbName.")
                    sharedViewModel.showSnackbar(R.string.backup_error_db_name_not_retrieved) // Generic error might be better
                    _isLoadingBackup.value = false
                    return@launch
                }


                val filesToBackup = listOfNotNull(
                    dbFile,
                    File(dbDir, "$dbName-shm"),
                    File(dbDir, "$dbName-wal")
                )

                if (!dbFile.exists()) {
                    sharedViewModel.showSnackbar(R.string.backup_error_main_db_not_found, listOf(dbName))
                    LogManager.e(TAG, "Database backup error: Main DB file ${dbFile.absolutePath} not found.")
                    _isLoadingBackup.value = false
                    return@launch
                }
                LogManager.d(TAG, "Main DB file path for backup: ${dbFile.absolutePath}")

                withContext(Dispatchers.IO) {
                    var backupSuccessful = false
                    try {
                        contentResolver.openOutputStream(backupUri)?.use { outputStream ->
                            ZipOutputStream(outputStream).use { zipOutputStream ->
                                filesToBackup.forEach { file ->
                                    if (file.exists() && file.isFile) {
                                        try {
                                            FileInputStream(file).use { fileInputStream ->
                                                val entry = ZipEntry(file.name)
                                                zipOutputStream.putNextEntry(entry)
                                                fileInputStream.copyTo(zipOutputStream)
                                                zipOutputStream.closeEntry()
                                                LogManager.d(TAG, "Added ${file.name} to backup archive.")
                                            }
                                        } catch (e: Exception) {
                                            // Log error for individual file but continue (especially for -shm or -wal)
                                            LogManager.w(TAG, "Could not add ${file.name} to backup archive, continuing. Error: ${e.message}", e)
                                        }
                                    } else {
                                        // Main DB file existence is checked above. This handles missing -shm or -wal.
                                        if (file.name.endsWith("-shm") || file.name.endsWith("-wal")) {
                                            LogManager.i(TAG, "Optional backup file ${file.name} not found, skipping.")
                                        }
                                    }
                                }
                            }
                        } ?: run {
                            sharedViewModel.showSnackbar(R.string.backup_error_no_output_stream)
                            LogManager.e(TAG, "Backup failed: Could not open OutputStream for Uri: $backupUri")
                            return@withContext // Exit IO context
                        }
                        backupSuccessful = true
                    } catch (e: IOException) {
                        LogManager.e(TAG, "IO Error during database backup zip process to URI $backupUri", e)
                        val errorMsg = e.localizedMessage ?: "Unknown I/O error"
                        sharedViewModel.showSnackbar(R.string.backup_error_generic, listOf(errorMsg))
                        return@withContext
                    }

                    if (backupSuccessful) {
                        LogManager.i(TAG, "Database backup to $backupUri successful.")
                        sharedViewModel.showSnackbar(R.string.backup_successful)
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "General error during database backup preparation for URI $backupUri", e)
                val errorMsg = e.localizedMessage ?: "Unknown error"
                sharedViewModel.showSnackbar(R.string.backup_error_generic, listOf(errorMsg))
            } finally {
                _isLoadingBackup.value = false
                LogManager.i(TAG, "Database backup process finished for URI: $backupUri.")
            }
        }
    }

    fun performDatabaseRestore(restoreUri: Uri, applicationContext: android.content.Context, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingRestore.value = true
            LogManager.i(TAG, "Performing database restore from URI: $restoreUri")
            try {
                val dbName = repository.getDatabaseName()
                val dbFile = applicationContext.getDatabasePath(dbName)
                val dbDir = dbFile.parentFile ?: run {
                    LogManager.e(TAG, "Database restore error: Database directory could not be determined for $dbName.")
                    sharedViewModel.showSnackbar(R.string.backup_error_db_name_not_retrieved)
                    _isLoadingRestore.value = false
                    return@launch
                }

                // Close the database before attempting to overwrite files
                LogManager.d(TAG, "Attempting to close database before restore.")
                repository.closeDatabase() // Ensure this method exists and correctly closes Room
                LogManager.i(TAG, "Database closed for restore operation.")

                withContext(Dispatchers.IO) {
                    var restoreSuccessful = false
                    var mainDbRestored = false
                    try {
                        contentResolver.openInputStream(restoreUri)?.use { inputStream ->
                            ZipInputStream(inputStream).use { zipInputStream ->
                                var entry: ZipEntry? = zipInputStream.nextEntry
                                while (entry != null) {
                                    val outputFile = File(dbDir, entry.name)
                                    // Basic path traversal protection
                                    if (!outputFile.canonicalPath.startsWith(dbDir.canonicalPath)) {
                                        LogManager.e(TAG, "Skipping restore of entry '${entry.name}' due to path traversal attempt.")
                                        entry = zipInputStream.nextEntry
                                        continue
                                    }

                                    // Delete existing file before restoring (important for WAL mode)
                                    if (outputFile.exists()) {
                                        if (!outputFile.delete()) {
                                            LogManager.w(TAG, "Could not delete existing file ${outputFile.name} before restore. Restore might fail or be incomplete.")
                                        }
                                    }

                                    FileOutputStream(outputFile).use { fileOutputStream ->
                                        zipInputStream.copyTo(fileOutputStream)
                                    }
                                    LogManager.d(TAG, "Restored ${entry.name} from backup archive to ${outputFile.absolutePath}.")
                                    if (entry.name == dbName) {
                                        mainDbRestored = true
                                    }
                                    entry = zipInputStream.nextEntry
                                }
                            }
                        } ?: run {
                            sharedViewModel.showSnackbar(R.string.restore_error_no_input_stream)
                            LogManager.e(TAG, "Restore failed: Could not open InputStream for Uri: $restoreUri")
                            return@withContext
                        }

                        if (!mainDbRestored) {
                            LogManager.e(TAG, "Restore failed: Main database file '$dbName' not found in the backup archive.")
                            sharedViewModel.showSnackbar(R.string.restore_error_db_files_missing)
                            // Attempt to clean up partially restored files might be needed here, or let the user handle it.
                            return@withContext
                        }
                        restoreSuccessful = true

                    } catch (e: IOException) {
                        LogManager.e(TAG, "IO Error during database restore from URI $restoreUri", e)
                        val errorMsg = e.localizedMessage ?: "Unknown I/O error"
                        sharedViewModel.showSnackbar(R.string.restore_error_generic, listOf(errorMsg))
                        return@withContext
                    } catch (e: IllegalStateException) { // Can be thrown by ZipInputStream
                        LogManager.e(TAG, "Error processing ZIP file during restore from URI $restoreUri", e)
                        sharedViewModel.showSnackbar(R.string.restore_error_zip_format)
                        return@withContext
                    }


                    if (restoreSuccessful) {
                        LogManager.i(TAG, "Database restore from $restoreUri successful. App restart is required.")
                        sharedViewModel.showSnackbar(R.string.restore_successful)
                        // The app needs to be restarted for Room to pick up the new database files correctly.
                        // This usually involves sharedViewModel.requestAppRestart() or similar mechanism.
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "General error during database restore from URI $restoreUri", e)
                val errorMsg = e.localizedMessage ?: "Unknown error"
                sharedViewModel.showSnackbar(R.string.restore_error_generic, listOf(errorMsg))
            } finally {
                // Re-open the database regardless of success, unless app is restarting
                // If an app restart is requested, reopening might not be necessary or could cause issues.
                // However, if the restore failed and no restart is pending, the DB should be reopened.
                if (!_isLoadingRestore.value) { // Check if not already restarting
                    try {
                        LogManager.d(TAG, "Attempting to re-open database after restore attempt.")
                        // This might require re-initialization of the Room database instance
                        // if the underlying files were changed.
                        // For simplicity, we assume the repository handles this.
                        // A full app restart is generally the safest way after a DB restore.
                        // TODO repository.reopenDatabase() // Ensure this method exists and correctly re-opens Room
                        LogManager.i(TAG, "Database re-opened after restore attempt.")
                    } catch (reopenError: Exception) {
                        LogManager.e(TAG, "Error re-opening database after restore attempt. App restart is highly recommended.", reopenError)
                        sharedViewModel.showSnackbar(R.string.restore_error_generic, listOf("Error re-opening database."))
                    }
                }
                _isLoadingRestore.value = false
                LogManager.i(TAG, "Database restore process finished for URI: $restoreUri.")
            }
        }
    }

    fun initiateDeleteEntireDatabaseProcess() {
        LogManager.d(TAG, "Initiating delete entire database process. Showing confirmation dialog.")
        _showDeleteEntireDatabaseConfirmationDialog.value = true
    }

    fun cancelDeleteEntireDatabaseConfirmation() {
        _showDeleteEntireDatabaseConfirmationDialog.value = false
        LogManager.d(TAG, "Delete entire database confirmation cancelled.")
    }

    fun confirmDeleteEntireDatabase(applicationContext: android.content.Context) {
        viewModelScope.launch {
            _isLoadingEntireDatabaseDeletion.value = true
            _showDeleteEntireDatabaseConfirmationDialog.value = false
            LogManager.i(TAG, "User confirmed deletion of the entire database.")

            try {
                LogManager.d(TAG, "Attempting to close database before deletion.")
                repository.closeDatabase()
                LogManager.i(TAG, "Database closed for deletion.")

                withContext(Dispatchers.IO) {
                    val dbName = repository.getDatabaseName()
                    val databaseDeleted = applicationContext.deleteDatabase(dbName)

                    // Also try to delete -shm and -wal files explicitly, as deleteDatabase might not always get them.
                    val dbFile = applicationContext.getDatabasePath(dbName)
                    val dbDir = dbFile.parentFile
                    var shmDeleted = true
                    var walDeleted = true
                    if (dbDir != null && dbDir.exists()) {
                        val shmFile = File(dbDir, "$dbName-shm")
                        if (shmFile.exists()) shmDeleted = shmFile.delete()
                        val walFile = File(dbDir, "$dbName-wal")
                        if (walFile.exists()) walDeleted = walFile.delete()
                    }

                    if (databaseDeleted) {
                        LogManager.i(TAG, "Entire database '$dbName' (and associated files: shm=$shmDeleted, wal=$walDeleted) successfully deleted.")
                        sharedViewModel.showSnackbar(R.string.delete_db_successful)
                        // App must be restarted as the database is gone.
                        // TODO sharedViewModel.requestAppRestart()
                    } else {
                        LogManager.e(TAG, "Failed to delete the entire database '$dbName'. deleteDatabase returned false.")
                        sharedViewModel.showSnackbar(R.string.delete_db_error)
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during entire database deletion process.", e)
                sharedViewModel.showSnackbar(R.string.delete_db_error)
            } finally {
                // No need to reopen DB here as it's supposed to be deleted.
                // If deletion failed, the app state is uncertain, restart is still best.
                _isLoadingEntireDatabaseDeletion.value = false
                LogManager.i(TAG, "Entire database deletion process finished.")
            }
        }
    }

    // --- User-Operationen (wiederhergestellt aus der ursprünglichen Version) ---
    /**
     * Adds a new user to the database.
     * This is a suspend function as it involves a database write operation.
     *
     * @param user The [User] object to insert.
     * @return The ID of the newly inserted user.
     */
    suspend fun addUser(user: User): Long {
        LogManager.d(TAG, "Adding new user: ${user.name}")
        val newUserId = repository.insertUser(user)
        LogManager.i(TAG, "User '${user.name}' added with ID: $newUserId")
        // Optionally, trigger a refresh of allUsers or let the SharedViewModel handle it
        // sharedViewModel.refreshUsers()
        return newUserId
    }

    /**
     * Deletes a user and all their associated data from the database.
     * This operation is performed in a background coroutine.
     *
     * @param user The [User] object to delete.
     */
    fun deleteUser(user: User) {
        viewModelScope.launch {
            LogManager.d(TAG, "Attempting to delete user: ${user.name} (ID: ${user.id})")
            try {
                // Consider the implications: this will also delete all measurements for the user.
                // You might want a confirmation dialog for this action elsewhere in the UI.
                repository.deleteUser(user)
                LogManager.i(TAG, "User '${user.name}' (ID: ${user.id}) and their data deleted successfully.")
                // Optionally, emit a success message or trigger UI refresh
                sharedViewModel.showSnackbar(R.string.user_deleted_successfully, listOf(user.name))
                // sharedViewModel.refreshUsers() // Or handle user list updates through SharedViewModel
            } catch (e: Exception) {
                LogManager.e(TAG, "Error deleting user '${user.name}' (ID: ${user.id})", e)
                sharedViewModel.showSnackbar(R.string.user_deleted_error, listOf(user.name))
            }
        }
    }

    /**
     * Updates an existing user's information in the database.
     * This is a suspend function as it involves a database write operation.
     *
     * @param user The [User] object with updated information.
     */
    suspend fun updateUser(user: User) {
        LogManager.d(TAG, "Updating user: ${user.name} (ID: ${user.id})")
        try {
            repository.updateUser(user)
            LogManager.i(TAG, "User '${user.name}' (ID: ${user.id}) updated successfully.")
            // Optionally, emit a success message or trigger UI refresh
            sharedViewModel.showSnackbar(R.string.user_updated_successfully, listOf(user.name))
            // sharedViewModel.refreshUsers()
        } catch (e: Exception) {
            LogManager.e(TAG, "Error updating user '${user.name}' (ID: ${user.id})", e)
            sharedViewModel.showSnackbar(R.string.user_updated_error, listOf(user.name))
        }
    }

    // --- MeasurementType-Operationen (wiederhergestellt aus der ursprünglichen Version) ---
    /**
     * Adds a new measurement type to the database.
     * This operation is performed in a background coroutine.
     *
     * @param type The [MeasurementType] object to insert.
     */
    fun addMeasurementType(type: MeasurementType) {
        viewModelScope.launch {
            LogManager.d(TAG, "Adding new measurement type (Key: ${type.key})")
            try {
                repository.insertMeasurementType(type)
                LogManager.i(TAG, "Measurement type '${type.key}' added successfully.")
                sharedViewModel.showSnackbar(R.string.measurement_type_added_successfully, listOf(type.key.toString()))
                // Optionally, trigger a refresh of measurement types if displayed
                // sharedViewModel.refreshMeasurementTypes()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error adding measurement type '${type.key}'", e)
                sharedViewModel.showSnackbar(R.string.measurement_type_added_error, listOf(type.key.toString()))
            }
        }
    }

    /**
     * Deletes a measurement type from the database.
     * This operation is performed in a background coroutine.
     * Consider the implications: associated measurement values might need handling.
     *
     * @param type The [MeasurementType] object to delete.
     */
    fun deleteMeasurementType(type: MeasurementType) {
        viewModelScope.launch {
            LogManager.d(TAG, "Attempting to delete measurement type (ID: ${type.id})")
            try {
                // WARNING: Deleting a MeasurementType might orphan MeasurementValue entries
                // or require cascading deletes/cleanup logic in the repository or database schema.
                // Ensure this is handled correctly based on your app's requirements.
                repository.deleteMeasurementType(type)
                LogManager.i(TAG, "Measurement type (ID: ${type.id}) deleted successfully.")
                sharedViewModel.showSnackbar(R.string.measurement_type_deleted_successfully, listOf(type.key.toString()))
                // sharedViewModel.refreshMeasurementTypes()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error deleting measurement type (ID: ${type.id})", e)
                sharedViewModel.showSnackbar(R.string.measurement_type_deleted_error, listOf(type.key.toString()))
            }
        }
    }

    /**
     * Updates an existing measurement type in the database.
     * If the unit has changed for a FLOAT type, it will also convert associated measurement values.
     * This version performs operations sequentially without a single overarching repository transaction
     * and relies on the ViewModel for orchestration.
     *
     * @param originalType The MeasurementType as it was BEFORE any edits in the UI.
     *                     Crucially contains the original unit and ID.
     * @param updatedType  The MeasurementType object with potentially updated information from the UI
     *                     (new name, new unit, new color, etc.).
     * @param showSnackbarMaster Boolean to control if a snackbar is shown after the entire operation.
     *                           Individual snackbars for errors might still appear.
     */
    fun updateMeasurementTypeAndConvertDataViewModelCentric(
        originalType: MeasurementType,
        updatedType: MeasurementType,
        showSnackbarMaster: Boolean = true
    ) {
        viewModelScope.launch {
            LogManager.i(
                TAG,
                "ViewModelCentric Update: Type ID ${originalType.id}. Original Unit: ${originalType.unit}, New Unit: ${updatedType.unit}"
            )

            var conversionErrorOccurred = false
            var valuesConvertedCount = 0

            if (originalType.unit != updatedType.unit && originalType.inputType == InputFieldType.FLOAT && updatedType.inputType == InputFieldType.FLOAT) {
                LogManager.i(
                    TAG,
                    "Unit changed for FLOAT type ID ${originalType.id} from ${originalType.unit} to ${updatedType.unit}. Converting values."
                )

                try {
                    val valuesToConvert = repository.getValuesForType(originalType.id).first() // .first() um den aktuellen Wert des Flows zu erhalten

                    if (valuesToConvert.isNotEmpty()) {
                        LogManager.d(TAG, "Found ${valuesToConvert.size} values of type ID ${originalType.id} to potentially convert.")
                        val updatedValuesBatch = mutableListOf<MeasurementValue>()

                        valuesToConvert.forEach { valueToConvert ->
                            valueToConvert.floatValue?.let { currentFloatVal ->
                                val convertedFloat = Converters.convertFloatValueUnit(
                                    value = currentFloatVal,
                                    fromUnit = originalType.unit,
                                    toUnit = updatedType.unit
                                )

                                if (convertedFloat != currentFloatVal) {
                                    updatedValuesBatch.add(valueToConvert.copy(floatValue = convertedFloat))
                                    valuesConvertedCount++
                                }
                            }
                        }

                        if (updatedValuesBatch.isNotEmpty()) {
                            LogManager.d(TAG, "Updating ${updatedValuesBatch.size} values in batch for type ID ${originalType.id}.")
                            // Aktualisiere jeden Wert einzeln. Dein Repository.updateMeasurementValue
                            // stößt die Neuberechnung der abgeleiteten Werte an.
                            updatedValuesBatch.forEach { repository.updateMeasurementValue(it) }
                            LogManager.d(TAG, "Batch update of ${updatedValuesBatch.size} values completed for type ID ${originalType.id}.")
                        } else {
                            LogManager.i(TAG, "No values required actual conversion or update for type ID ${originalType.id} after checking.")
                        }
                    } else {
                        LogManager.i(TAG, "No values found for type ID ${originalType.id} to convert.")
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error during value conversion/update for type ID ${originalType.id}", e)
                    conversionErrorOccurred = true
                    //  Verwende hier getDisplayName vom originalType, da updatedType möglicherweise noch nicht committet wurde.
                    sharedViewModel.showSnackbar(
                        messageResId = R.string.measurement_type_update_error_conversion_failed,
                        formatArgs = listOf(originalType.name ?: originalType.key.toString())
                    )
                }
            } else if (originalType.unit != updatedType.unit) {
                LogManager.i(
                    TAG,
                    "Unit changed for type ID ${originalType.id}, but InputType is not FLOAT (Original: ${originalType.inputType}, Updated: ${updatedType.inputType}). No value conversion performed."
                )
            }

            if (!conversionErrorOccurred) {
                try {
                    val finalTypeToUpdate = MeasurementType(
                        id = originalType.id,
                        key = originalType.key,
                        name = updatedType.name,
                        color = updatedType.color,
                        icon = updatedType.icon,
                        unit = updatedType.unit,
                        inputType = updatedType.inputType,
                        displayOrder = originalType.displayOrder,
                        isDerived = originalType.isDerived,
                        isEnabled = updatedType.isEnabled,
                        isPinned = updatedType.isPinned,
                        isOnRightYAxis = updatedType.isOnRightYAxis
                    )

                    repository.updateMeasurementType(finalTypeToUpdate)
                    LogManager.i(
                        TAG,
                        "MeasurementType (ID: ${originalType.id}) updated successfully to new unit '${finalTypeToUpdate.unit}'."
                    )

                    if (showSnackbarMaster) {
                        if (valuesConvertedCount > 0) {
                            sharedViewModel.showSnackbar(
                                messageResId =  R.string.measurement_type_updated_and_values_converted_successfully,
                                formatArgs = listOf(updatedType.name ?: updatedType.key.toString(), valuesConvertedCount.toString()) // Context für getDisplayName wäre besser
                            )
                        } else if (originalType.unit != updatedType.unit && originalType.inputType == InputFieldType.FLOAT) {
                            sharedViewModel.showSnackbar(
                                messageResId = R.string.measurement_type_updated_unit_changed_no_values_converted,
                                formatArgs = listOf(updatedType.name ?: updatedType.key.toString()) // Context für getDisplayName wäre besser
                            )
                        }
                        else {
                            sharedViewModel.showSnackbar(
                                messageResId = R.string.measurement_type_updated_successfully,
                                formatArgs = listOf(updatedType.name ?: updatedType.key.toString()) // Context für getDisplayName wäre besser
                            )
                        }
                    }
                    // sharedViewModel.refreshMeasurementTypes() // Optional, wenn die Liste sich nicht automatisch aktualisiert
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error updating MeasurementType (ID: ${originalType.id}) itself", e)
                    sharedViewModel.showSnackbar(
                        messageResId = R.string.measurement_type_updated_error,
                        formatArgs = listOf(originalType.name ?: originalType.key.toString()) // Context für getDisplayName wäre besser
                    )
                }
            } else {
                LogManager.w(
                    TAG,
                    "Skipped updating MeasurementType definition (ID: ${originalType.id}) due to prior value conversion error."
                )
            }
        }
    }

    /**
     * Updates an existing measurement type in the database.
     * This operation is performed in a background coroutine.
     *
     * @param type The [MeasurementType] object with updated information.
     */
    fun updateMeasurementType(type: MeasurementType, showSnackbar: Boolean = true) {
        viewModelScope.launch {
            if (showSnackbar) {
                LogManager.d(TAG, "Updating measurement type (ID: ${type.id})")
            }
            try {
                repository.updateMeasurementType(type)
                LogManager.i(TAG, "Measurement type (ID: ${type.id}) updated successfully.")
                if (showSnackbar) {
                    sharedViewModel.showSnackbar(
                        R.string.measurement_type_updated_successfully,
                        listOf(type.key.toString())
                    )
                }
                // sharedViewModel.refreshMeasurementTypes()
            } catch (e: Exception) {
                LogManager.e(TAG, "Error updating measurement type (ID: ${type.id})", e)
                sharedViewModel.showSnackbar(R.string.measurement_type_updated_error, listOf(type.key.toString()))
            }
        }
    }

}
