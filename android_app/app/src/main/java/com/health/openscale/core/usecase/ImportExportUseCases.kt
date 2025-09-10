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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import android.content.ContentResolver
import android.net.Uri
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Report returned by CSV import with counts for UI messaging.
 */
data class ImportReport(
    val importedMeasurementsCount: Int,
    val ignoredMeasurementsCount: Int,
    val linesSkippedMissingDate: Int,
    val linesSkippedDateParseError: Int,
    val valuesSkippedParseError: Int,
)

/**
 * Use case for importing/exporting user data as CSV via SAF.
 *
 * Keeps file IO, CSV parsing/formatting and DB orchestration out of ViewModels.
 */
@Singleton
class ImportExportUseCases @Inject constructor(
    private val repository: DatabaseRepository
) {

    private val TAG = "ImportExportUseCase"

    // Shared formatters (same semantics wie in deinem VM)
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
        .toFormatter()

    /**
     * Export all measurements of a user to a CSV file at [uri].
     * @return number of exported data rows (not counting header).
     */
    suspend fun exportUserToCsv(
        userId: Int,
        uri: Uri,
        contentResolver: ContentResolver
    ): Result<Int> = runCatching {
        LogManager.i(TAG, "CSV export for userId=$userId -> $uri")

        val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()
        val exportableValueTypes = allAppTypes.filter {
            it.key != MeasurementTypeKey.DATE &&
            it.key != MeasurementTypeKey.TIME &&
            it.key != MeasurementTypeKey.USER
        }
        val valueColumnKeys = exportableValueTypes.map { it.key.name }.distinct()

        val dateColumnKey = MeasurementTypeKey.DATE.name
        val timeColumnKey = MeasurementTypeKey.TIME.name

        val allCsvColumnKeys = buildList {
            add(dateColumnKey)
            add(timeColumnKey)
            addAll(valueColumnKeys.sorted())
        }

        val userMeasurementsWithValues: List<MeasurementWithValues> =
            repository.getMeasurementsWithValuesForUser(userId).first()

        require(userMeasurementsWithValues.isNotEmpty()) {
            "No measurements found for userId=$userId"
        }

        val rows = mutableListOf<Map<String, String?>>()
        userMeasurementsWithValues.forEach { mwv ->
            val zdt = Instant.ofEpochMilli(mwv.measurement.timestamp).atZone(ZoneId.systemDefault())
            val row = mutableMapOf<String, String?>(
                dateColumnKey to dateFormatter.format(zdt),
                timeColumnKey to timeFormatter.format(zdt)
            )

            mwv.values.forEach { mwvSingle ->
                val type = mwvSingle.type
                val value = mwvSingle.value

                if (type.key != MeasurementTypeKey.DATE && type.key != MeasurementTypeKey.TIME &&
                    valueColumnKeys.contains(type.key.name)
                ) {
                    val s = when (type.inputType) {
                        InputFieldType.TEXT  -> value.textValue
                        InputFieldType.FLOAT -> value.floatValue?.toString()
                        InputFieldType.INT   -> value.intValue?.toString()
                        InputFieldType.DATE  -> value.dateValue?.let {
                            dateFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                        }
                        InputFieldType.TIME  -> value.dateValue?.let {
                            timeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                        }
                        InputFieldType.USER -> null
                    }
                    row[type.key.name] = s
                }
            }
            rows.add(row)
        }

        require(rows.isNotEmpty()) { "No exportable values after transformation" }

        withContext(Dispatchers.IO) {
            val os = contentResolver.openOutputStream(uri)
                ?: error("Cannot open OutputStream for uri=$uri")
            var count = 0
            csvWriter().open(os) {
                writeRow(allCsvColumnKeys)
                rows.forEach { map ->
                    writeRow(allCsvColumnKeys.map { k -> map[k] })
                }
                count = rows.size
            }
            LogManager.d(TAG, "CSV export done: rows=$count userId=$userId")
            count
        }
    }


    /**
     * Import measurements for a user from a CSV file at [uri].
     * The CSV format matches the exporter (first row is header).
     * Returns an [ImportReport] with success/skip counts.
     */
    suspend fun importUserFromCsv(
        userId: Int,
        uri: Uri,
        contentResolver: ContentResolver
    ): Result<ImportReport> = runCatching {
        LogManager.i(TAG, "CSV import for userId=$userId <- $uri")

        val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()

        val dateColumnKey = MeasurementTypeKey.DATE.name
        val timeColumnKey = MeasurementTypeKey.TIME.name
        val dateTimeColumnKey = "dateTime"
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        var linesSkippedMissingDate = 0
        var linesSkippedDateParseError = 0
        var valuesSkippedParseError = 0
        var importedMeasurementsCount = 0
        var ignoredMeasurementsCount = 0

        val toInsert = mutableListOf<Pair<Measurement, List<MeasurementValue>>>()

        withContext(Dispatchers.IO) {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open InputStream for Uri: $uri")

            csvReader {
                skipEmptyLine = true
                quoteChar = '"'
            }.open(input) {
                var header: List<String>? = null
                var dateIdx = -1
                var timeIdx = -1
                var dateTimeIdx = -1
                val valueColumnMap = mutableMapOf<Int, MeasurementType>()

                readAllAsSequence().forEachIndexed { rowIndex, row ->
                    if (rowIndex == 0) {
                        header = row
                        dateIdx = row.indexOfFirst { it.equals(dateColumnKey, ignoreCase = true) }
                        timeIdx = row.indexOfFirst { it.equals(timeColumnKey, ignoreCase = true) }
                        dateTimeIdx = row.indexOfFirst { it.equals(dateTimeColumnKey, ignoreCase = true) }

                        if (dateIdx == -1 && dateTimeIdx == -1) {
                            throw IOException("CSV header is missing mandatory date column ($dateColumnKey or $dateTimeColumnKey)")
                        }

                        row.forEachIndexed { colIdx, colName ->
                            if (colIdx == dateIdx || colIdx == timeIdx || colIdx == dateTimeIdx) return@forEachIndexed

                            // 1) map by MeasurementTypeKey.name
                            var matched = allAppTypes.find { t ->
                                t.key.name.equals(colName, ignoreCase = true) &&
                                        t.key != MeasurementTypeKey.DATE &&
                                        t.key != MeasurementTypeKey.TIME
                            }
                            // 2) fallback: custom name for CUSTOM types
                            if (matched == null) {
                                matched = allAppTypes.find { t ->
                                    t.key == MeasurementTypeKey.CUSTOM &&
                                            (t.name?.equals(colName, ignoreCase = true) == true)
                                }
                            }
                            if (matched != null && matched.isEnabled) {
                                valueColumnMap[colIdx] = matched
                                LogManager.d(TAG, "Header map: '$colName' -> ${matched.key} (id=${matched.id})")
                            }
                        }
                        return@forEachIndexed
                    }

                    if (header == null) throw IOException("CSV header not found")

                    var parsedDate: LocalDate? = null
                    var parsedTime: LocalTime? = null

                    val dt = if (dateTimeIdx != -1) row.getOrNull(dateTimeIdx) else null
                    val d = if (dateIdx != -1) row.getOrNull(dateIdx) else null
                    val t = if (timeIdx != -1) row.getOrNull(timeIdx) else null

                    if (!dt.isNullOrBlank()) {
                        try {
                            val ldt = LocalDateTime.parse(dt, dateTimeFormatter)
                            parsedDate = ldt.toLocalDate()
                            parsedTime = ldt.toLocalTime()
                        } catch (_: DateTimeParseException) {
                            // try separate columns
                        }
                    }

                    if (parsedDate == null && !d.isNullOrBlank()) {
                        try {
                            parsedDate = LocalDate.parse(d, dateFormatter)
                        } catch (_: DateTimeParseException) {
                            linesSkippedDateParseError++
                            return@forEachIndexed
                        }
                    }

                    if (parsedDate == null) {
                        linesSkippedMissingDate++
                        return@forEachIndexed
                    }

                    if (parsedTime == null) {
                        parsedTime = if (t.isNullOrBlank()) {
                            LocalTime.NOON
                        } else {
                            try {
                                LocalTime.parse(t, timeFormatter)
                            } catch (_: DateTimeParseException) {
                                try { LocalTime.parse(t, flexibleTimeFormatter) }
                                catch (_: DateTimeParseException) { LocalTime.NOON }
                            }
                        }
                    }

                    val ts = LocalDateTime.of(parsedDate, parsedTime)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val measurement = Measurement(userId = userId, timestamp = ts)
                    val values = mutableListOf<MeasurementValue>()

                    valueColumnMap.forEach { (colIdx, type) ->
                        val raw = row.getOrNull(colIdx)
                        if (raw.isNullOrBlank()) return@forEach

                        try {
                            var skip = false
                            val floatVal = if (type.inputType == InputFieldType.FLOAT) raw.toFloatOrNull() else null
                            val intVal = if (type.inputType == InputFieldType.INT) raw.toIntOrNull() else null
                            if (type.inputType == InputFieldType.FLOAT && floatVal == 0f) skip = true
                            if (type.inputType == InputFieldType.INT && intVal == 0) skip = true

                            if (!skip) {
                                val mv = MeasurementValue(
                                    typeId = type.id,
                                    measurementId = 0,
                                    textValue = if (type.inputType == InputFieldType.TEXT) raw else null,
                                    floatValue = if (type.inputType == InputFieldType.FLOAT) floatVal else null,
                                    intValue = if (type.inputType == InputFieldType.INT) intVal else null,
                                    dateValue = when (type.inputType) {
                                        InputFieldType.DATE -> LocalDate.parse(raw, dateFormatter)
                                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                        InputFieldType.TIME -> {
                                            val parsed = try { LocalTime.parse(raw, timeFormatter) }
                                            catch (_: Exception) { LocalTime.parse(raw, flexibleTimeFormatter) }
                                            parsed.atDate(LocalDate.of(1970,1,1))
                                                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                        }
                                        else -> null
                                    }
                                )
                                val valid = when (type.inputType) {
                                    InputFieldType.FLOAT -> mv.floatValue != null
                                    InputFieldType.INT   -> mv.intValue != null
                                    else -> true
                                }
                                if (valid) values.add(mv) else valuesSkippedParseError++
                            }
                        } catch (e: Exception) {
                            LogManager.w(TAG, "Value parse error at row=$rowIndex col=$colIdx for type=${type.key}", e)
                            valuesSkippedParseError++
                        }
                    }

                    if (values.isNotEmpty()) {
                        toInsert.add(measurement to values)
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                val ids = repository.insertMeasurementsWithValues(toInsert)
                importedMeasurementsCount = ids.first.size
                ignoredMeasurementsCount = ids.second.size

                // Recalc derived values for each inserted measurement (like in your VM)
                ids.first.forEach { id ->
                    try { repository.recalculateDerivedValuesForMeasurement(id.toInt()) }
                    catch (e: Exception) {
                        LogManager.e(TAG, "Derived recalculation failed for measurementId=$id", e)
                    }
                }
            }
        }

        ImportReport(
            importedMeasurementsCount = importedMeasurementsCount,
            ignoredMeasurementsCount = ignoredMeasurementsCount,
            linesSkippedMissingDate = linesSkippedMissingDate,
            linesSkippedDateParseError = linesSkippedDateParseError,
            valuesSkippedParseError = valuesSkippedParseError
        )
    }
}
