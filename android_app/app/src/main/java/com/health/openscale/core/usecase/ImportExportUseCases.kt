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
import android.content.Context
import android.net.Uri
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    /** Headers that matched no measurement type; their whole column was skipped. */
    val skippedColumns: List<String> = emptyList(),
)

/**
 * Use case for importing/exporting user data as CSV via SAF.
 *
 * Keeps file IO, CSV parsing/formatting and DB orchestration out of ViewModels.
 */
@Singleton
class ImportExportUseCases @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val repository: DatabaseRepository,
    private val sync: SyncUseCases
) {

    private val TAG = "ImportExportUseCase"

    // Only ISO 8601 is accepted on import; spreadsheets tend to rewrite these columns
    // in the device locale (e.g. "8:30:00 AM"), so name the expected format in the log.
    private val DATE_FORMAT_HINT = "yyyy-MM-dd"
    private val TIME_FORMAT_HINT = "HH:mm[:ss], 24h, zero-padded"

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

    private val photoTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** True if the measurements an export would cover carry at least one photo. */
    suspend fun hasPhotos(userId: Int, filterByMeasurementIds: List<Int>? = null): Boolean =
        repository.getMeasurementsWithValuesForUser(userId).first()
            .filter { filterByMeasurementIds == null || it.measurement.id in filterByMeasurementIds }
            .any { mwv -> mwv.values.any { it.type.inputType == InputFieldType.IMAGE && it.value.textValue != null } }

    /**
     * Export all measurements of a user to a CSV file at [uri].
     *
     * With [includePhotos] the file is a ZIP instead: the CSV as [CSV_ENTRY] plus every photo
     * under [PHOTO_DIR], referenced from the photo columns by its relative path.
     * @return number of exported data rows (not counting header).
     */
    suspend fun exportUserToCsv(
        userId: Int,
        uri: Uri,
        contentResolver: ContentResolver,
        filterByMeasurementIds: List<Int>? = null,
        includePhotos: Boolean = false,
    ): Result<Int> = runCatching {
        LogManager.i(TAG, "CSV export for userId=$userId -> $uri")

        val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()

        val exportableValueTypes = allAppTypes.filter {
            it.key != MeasurementType.DATE &&
            it.key != MeasurementType.TIME &&
            it.key != MeasurementType.USER &&
            (includePhotos || it.inputType != InputFieldType.IMAGE)
        }

        // One header per type, guaranteed unique. Identities make that automatic; only
        // rows without one (inserted straight through the DAO) fall back to their display
        // name and get numbered instead of silently collapsing into one column.
        val columnKeyByTypeId = uniqueColumnKeysByTypeId(exportableValueTypes)
        val valueColumnKeys = columnKeyByTypeId.values.toList()

        val dateColumnKey = MeasurementType.identityColumnKey(MeasurementType.DATE.identity)
        val timeColumnKey = MeasurementType.identityColumnKey(MeasurementType.TIME.identity)

        val allCsvColumnKeys = buildList {
            add(dateColumnKey)
            add(timeColumnKey)
            addAll(valueColumnKeys.sorted())
        }

        val allUserMeasurementsWithValues: List<MeasurementWithValues> =
            repository.getMeasurementsWithValuesForUser(userId).first()

        val filteredUserMeasurementsWithValues = if (filterByMeasurementIds != null) {
            allUserMeasurementsWithValues.filter { it.measurement.id in filterByMeasurementIds }
        } else {
            allUserMeasurementsWithValues
        }

        require(filteredUserMeasurementsWithValues.isNotEmpty()) {
            "No measurements found for userId=$userId"
        }

        val rows = mutableListOf<Map<String, String?>>()
        val photoEntries = mutableListOf<Pair<String, File>>()
        filteredUserMeasurementsWithValues.forEach { mwv ->
            val zdt = Instant.ofEpochMilli(mwv.measurement.timestamp).atZone(ZoneId.systemDefault())
            val row = mutableMapOf<String, String?>(
                dateColumnKey to dateFormatter.format(zdt),
                timeColumnKey to timeFormatter.format(zdt)
            )

            mwv.values.forEach { mwvSingle ->
                val type = mwvSingle.type
                val value = mwvSingle.value

                val currentColumnKey = columnKeyByTypeId[type.id]

                if (currentColumnKey != null &&
                    type.key != MeasurementType.DATE && type.key != MeasurementType.TIME
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
                        InputFieldType.IMAGE -> MeasurementCrudUseCases.imageFile(appContext, value.textValue)
                            ?.takeIf { it.isFile }
                            ?.let { file ->
                                val safeKey = currentColumnKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
                                val path = "$PHOTO_DIR/${photoTimestampFormatter.format(zdt)}_$safeKey.jpg"
                                photoEntries += path to file
                                path
                            }
                    }
                    row[currentColumnKey] = s
                }
            }
            rows.add(row)
        }

        require(rows.isNotEmpty()) { "No exportable values after transformation" }

        fun writeCsv(os: OutputStream) = csvWriter().open(os) {
            writeRow(allCsvColumnKeys)
            rows.forEach { map ->
                writeRow(allCsvColumnKeys.map { k -> map[k] })
            }
        }

        withContext(Dispatchers.IO) {
            val os = contentResolver.openOutputStream(uri)
                ?: error("Cannot open OutputStream for uri=$uri")
            if (includePhotos) {
                // csvWriter closes the stream it writes to, so the CSV is buffered first.
                val csvBytes = ByteArrayOutputStream().also { writeCsv(it) }.toByteArray()
                ZipOutputStream(os).use { zip ->
                    zip.putNextEntry(ZipEntry(CSV_ENTRY))
                    zip.write(csvBytes)
                    zip.closeEntry()
                    photoEntries.forEach { (path, file) ->
                        zip.putNextEntry(ZipEntry(path))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } else {
                writeCsv(os)
            }
            LogManager.d(TAG, "CSV export done: rows=${rows.size} photos=${photoEntries.size} userId=$userId")
            rows.size
        }
    }


    /**
     * The CSV column header for one type — one style for the whole file: the identity,
     * uppercased (`WEIGHT`, `SEGMENTAL_FAT_LEFT_ARM`, `SCHRITTE`). Identities are frozen
     * and unique, so the header is stable across renames and languages. Only a row that
     * somehow has no identity — one inserted straight through the DAO — falls back to its
     * display name. Lives here because the column key is CSV vocabulary, not part of the
     * type's identity mechanics.
     */
    private fun MeasurementType.csvColumnKey(): String =
        if (identity.isNotBlank()) MeasurementType.identityColumnKey(identity)
        else name?.takeIf { it.isNotBlank() } ?: "CUSTOM"

    private fun uniqueColumnKeysByTypeId(types: List<MeasurementType>): Map<Int, String> {
        val used = mutableSetOf<String>()
        val result = LinkedHashMap<Int, String>()
        types.forEach { type ->
            val base = type.csvColumnKey()
            var candidate = base
            var suffix = 1
            while (!used.add(candidate.uppercase())) {
                suffix++
                candidate = "${base}_$suffix"
            }
            result[type.id] = candidate
        }
        return result
    }

    /**
     * Import measurements for a user from a CSV file at [uri], or from a ZIP written by
     * [exportUserToCsv] with photos. The CSV format matches the exporter (first row is header).
     * Returns an [ImportReport] with success/skip counts.
     */
    suspend fun importUserFromCsv(
        userId: Int,
        uri: Uri,
        contentResolver: ContentResolver
    ): Result<ImportReport> = runCatching {
        LogManager.i(TAG, "CSV import for userId=$userId <- $uri")

        val allAppTypes: List<MeasurementType> = repository.getAllMeasurementTypes().first()

        val dateColumnKey = MeasurementType.identityColumnKey(MeasurementType.DATE.identity)
        val timeColumnKey = MeasurementType.identityColumnKey(MeasurementType.TIME.identity)
        val dateTimeColumnKey = "dateTime"
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        var linesSkippedMissingDate = 0
        var linesSkippedDateParseError = 0
        var valuesSkippedParseError = 0
        val skippedColumns = mutableListOf<String>()
        var importedMeasurementsCount = 0
        var ignoredMeasurementsCount = 0

        val toInsert = mutableListOf<Pair<Measurement, List<MeasurementValue>>>()
        val importedPhotosByTimestamp = mutableMapOf<Long, MutableList<String>>()
        val workDir = File(appContext.cacheDir, "csv_import_${System.nanoTime()}")
        val photoDir = File(workDir, PHOTO_DIR)

        try { withContext(Dispatchers.IO) {
            val isZip = BackupRestoreUseCases.isZip(contentResolver, uri)
            val input = if (isZip) {
                FileInputStream(extractImportZip(contentResolver, uri, workDir, photoDir))
            } else {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open InputStream for Uri: $uri")
            }

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

                            // 1) the identity-derived column key — the same derivation the
                            //    exporter uses. List order (displayOrder, predefined first)
                            //    keeps the historical precedence: a user type named
                            //    "COMMENT" cannot hijack the predefined column.
                            var matched = allAppTypes.find { t ->
                                t.key != MeasurementType.DATE &&
                                    t.key != MeasurementType.TIME &&
                                    (isZip || t.inputType != InputFieldType.IMAGE) &&
                                    t.csvColumnKey().equals(colName, ignoreCase = true)
                            }
                            // 2) fallback for files written before identity headers: user
                            //    types used to export under their display name.
                            if (matched == null) {
                                matched = allAppTypes.find { t ->
                                    t.isUserOwned() &&
                                        (isZip || t.inputType != InputFieldType.IMAGE) &&
                                        (t.name?.equals(colName, ignoreCase = true) == true)
                                }
                            }
                            // Internal raw inputs (e.g. impedance bands) are disabled by
                            // default but must still be importable for re-derivation.
                            if (matched != null && (matched.isEnabled || matched.isInternal)) {
                                valueColumnMap[colIdx] = matched
                                LogManager.d(TAG, "Header map: '$colName' -> ${matched.identity} (id=${matched.id})")
                            } else {
                                // Columns used to vanish in complete silence here.
                                skippedColumns.add(colName)
                                LogManager.w(
                                    TAG,
                                    "CSV column '$colName' matches no enabled measurement type; " +
                                        "the whole column is skipped."
                                )
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
                            LogManager.w(
                                TAG,
                                "Line ${rowIndex + 1}: cannot parse date '$d', expected ISO 8601 " +
                                        "($DATE_FORMAT_HINT). Skipping this line."
                            )
                            linesSkippedDateParseError++
                            return@forEachIndexed
                        }
                    }

                    if (parsedDate == null) {
                        LogManager.w(TAG, "Line ${rowIndex + 1}: no date column value. Skipping this line.")
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
                                try {
                                    LocalTime.parse(t, flexibleTimeFormatter)
                                } catch (_: DateTimeParseException) {
                                    LogManager.w(
                                        TAG,
                                        "Line ${rowIndex + 1}: cannot parse time '$t', expected ISO 8601 " +
                                                "($TIME_FORMAT_HINT). Falling back to 12:00."
                                    )
                                    LocalTime.NOON
                                }
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

                        if (type.inputType == InputFieldType.IMAGE) {
                            val stored = importPhoto(raw, photoDir)
                            if (stored == null) {
                                LogManager.w(TAG, "Line ${rowIndex + 1}: photo '$raw' missing or invalid in the ZIP.")
                                valuesSkippedParseError++
                            } else {
                                importedPhotosByTimestamp.getOrPut(ts) { mutableListOf() } += stored
                                values.add(MeasurementValue(typeId = type.id, measurementId = 0, textValue = stored))
                            }
                            return@forEach
                        }

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

                // Photos copied for measurements dropped as duplicates would otherwise be orphans.
                MeasurementCrudUseCases.deleteImageFiles(
                    appContext,
                    ids.second.flatMap { importedPhotosByTimestamp[it].orEmpty() }
                )

                // Recalc derived values for each inserted measurement (like in your VM)
                ids.first.forEach { id ->
                    try { repository.recalculateDerivedValuesForMeasurement(id.toInt()) }
                    catch (e: Exception) {
                        LogManager.e(TAG, "Derived recalculation failed for measurementId=$id", e)
                    }
                }

                // Bulk import: one coalesced "changed" wake-up instead of N per-measurement events.
                sync.triggerSyncChangedAll()
            }
        } } finally {
            workDir.deleteRecursively()
        }

        ImportReport(
            importedMeasurementsCount = importedMeasurementsCount,
            ignoredMeasurementsCount = ignoredMeasurementsCount,
            linesSkippedMissingDate = linesSkippedMissingDate,
            linesSkippedDateParseError = linesSkippedDateParseError,
            valuesSkippedParseError = valuesSkippedParseError,
            skippedColumns = skippedColumns.toList()
        )
    }

    /**
     * Unpacks an export ZIP into [workDir]: the first root-level CSV and the photos under
     * [PHOTO_DIR] whose names are plain file names. Everything else is ignored, which also
     * keeps entries like `photos/../x` from escaping [workDir].
     * @return the extracted CSV file.
     */
    private fun extractImportZip(contentResolver: ContentResolver, uri: Uri, workDir: File, photoDir: File): File {
        photoDir.mkdirs()
        var csvFile: File? = null
        (contentResolver.openInputStream(uri) ?: throw IOException("Could not open InputStream for Uri: $uri")).use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val photoName = name.removePrefix("$PHOTO_DIR/")
                    val target = when {
                        entry.isDirectory -> null
                        csvFile == null && '/' !in name && name.endsWith(".csv", ignoreCase = true) ->
                            File(workDir, CSV_ENTRY).also { csvFile = it }
                        name.startsWith("$PHOTO_DIR/") && PHOTO_FILE_NAME.matches(photoName) -> File(photoDir, photoName)
                        else -> null
                    }
                    if (target != null) FileOutputStream(target).use { zis.copyTo(it) }
                    else LogManager.d(TAG, "Skipping ZIP entry '$name' during CSV import.")
                    entry = zis.nextEntry
                }
            }
        }
        return csvFile ?: throw IOException("ZIP contains no CSV file")
    }

    /** Copies the photo a CSV cell refers to into app storage; returns its new file name or null. */
    private fun importPhoto(cell: String, photoDir: File): String? {
        val photoName = cell.trim().removePrefix("$PHOTO_DIR/").takeIf { PHOTO_FILE_NAME.matches(it) } ?: return null
        val source = File(photoDir, photoName).takeIf { it.isFile && it.isJpeg() } ?: return null
        val name = "${UUID.randomUUID()}.jpg"
        val target = MeasurementCrudUseCases.imageFile(appContext, name) ?: return null
        target.parentFile?.mkdirs()
        source.copyTo(target)
        return name
    }

    private fun File.isJpeg(): Boolean = inputStream().use { input ->
        val header = ByteArray(2)
        input.read(header) == 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
    }

    companion object {
        const val CSV_ENTRY = "measurements.csv"
        const val PHOTO_DIR = "photos"
        private val PHOTO_FILE_NAME = Regex("^[A-Za-z0-9._-]+\\.jpe?g$", RegexOption.IGNORE_CASE)
    }
}
