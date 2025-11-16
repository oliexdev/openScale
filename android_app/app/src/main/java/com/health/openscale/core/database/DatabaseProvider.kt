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
package com.health.openscale.core.database

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.health.openscale.BuildConfig
import com.health.openscale.OpenScaleApp
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DatabaseProviderEntryPoint {
    fun databaseRepository(): DatabaseRepository
    fun userSettingsFacade(): SettingsFacade
}

/**
 * Exposes the user and measurement data from openScale via
 * [Android Content Providers](https://developer.android.com/guide/topics/providers/content-providers).
 * This version is adapted to use DatabaseRepository internally while maintaining the external interface.
 */
class DatabaseProvider : ContentProvider() {
    private val TAG = "DatabaseProvider"

    private lateinit var databaseRepository: DatabaseRepository
    private lateinit var userSettingsFacade: SettingsFacade

    object UserColumns {
        const val _ID = "_ID"
        const val NAME = "username"
    }

    object MeasurementColumns {
        const val _ID = "_ID"
        const val DATETIME = "datetime"
        const val WEIGHT = "weight"
        const val BODY_FAT = "fat"
        const val WATER = "water"
        const val MUSCLE = "muscle"
    }

    override fun onCreate(): Boolean {
        val appContext = context!!.applicationContext
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                DatabaseProviderEntryPoint::class.java
            )
            databaseRepository = entryPoint.databaseRepository()
            userSettingsFacade = entryPoint.userSettingsFacade()

            CoroutineScope(Dispatchers.IO).launch {
                val isFileLogging = userSettingsFacade.isFileLoggingEnabled.first()
                LogManager.init(appContext, isFileLogging)
                LogManager.i(TAG, "DatabaseProvider initialized with file logging = $isFileLogging")
            }

            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to initialize DatabaseProvider: ${e.message}", e)
            false
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            MATCH_TYPE_META -> "vnd.android.cursor.item/vnd.$AUTHORITY.meta"
            MATCH_TYPE_USER_LIST -> "vnd.android.cursor.dir/vnd.$AUTHORITY.user"
            MATCH_TYPE_MEASUREMENT_LIST_FOR_USER -> "vnd.android.cursor.dir/vnd.$AUTHORITY.measurement"
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? {
        if (!::databaseRepository.isInitialized) {
            LogManager.e(TAG, "DatabaseRepository not initialized in query.")
            return null
        }

        val cursor: Cursor = when (uriMatcher.match(uri)) {
            MATCH_TYPE_META -> {
                MatrixCursor(arrayOf("apiVersion", "versionCode"), 1).apply {
                    addRow(arrayOf<Any>(API_VERSION, BuildConfig.VERSION_CODE))
                }
            }
            MATCH_TYPE_USER_LIST -> {
                runBlocking {
                    try {
                        val users = databaseRepository.getAllUsers().first()
                        val currentProjection = projection ?: arrayOf(UserColumns._ID, UserColumns.NAME)
                        val matrixCursor = MatrixCursor(currentProjection)
                        users.forEach { user ->
                            val rowData = mutableListOf<Any?>()
                            if (currentProjection.contains(UserColumns._ID)) rowData.add(user.id.toLong())
                            if (currentProjection.contains(UserColumns.NAME)) rowData.add(user.name)
                            matrixCursor.addRow(rowData.toTypedArray())
                        }
                        matrixCursor
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error querying users: ${e.message}", e)
                        MatrixCursor(projection ?: arrayOf(UserColumns._ID), 0)
                    }
                }
            }
            MATCH_TYPE_MEASUREMENT_LIST_FOR_USER -> {
                val userIdFromUri = try { ContentUris.parseId(uri).toInt() } catch (e: NumberFormatException) {
                    LogManager.e(TAG, "Invalid User ID in URI for measurement query: $uri", e)
                    return null
                }
                runBlocking {
                    try {
                        val measurementsWithValuesList =
                            databaseRepository.getMeasurementsWithValuesForUser(userIdFromUri).first()

                        val defaultMeasurementProjection = arrayOf(
                            MeasurementColumns._ID,
                            MeasurementColumns.DATETIME,
                            MeasurementColumns.WEIGHT,
                            MeasurementColumns.BODY_FAT,
                            MeasurementColumns.WATER,
                            MeasurementColumns.MUSCLE
                        )
                        val currentProjection = projection ?: defaultMeasurementProjection
                        val matrixCursor = MatrixCursor(currentProjection)

                        val allMeasurementTypes = databaseRepository.getAllMeasurementTypes().first()
                        val typeIdMap = allMeasurementTypes.associate { it.key to it.id }

                        measurementsWithValuesList.forEachIndexed { index, mcv -> // mcv is MeasurementWithValues
                            val measurement = mcv.measurement
                            val rowData = mutableListOf<Any?>()

                            fun findValue(key: MeasurementTypeKey): Float? {
                                val typeId = typeIdMap[key] ?: return null
                                return mcv.values.find { it.type.id == typeId }?.value?.floatValue
                            }

                            if (currentProjection.contains(MeasurementColumns._ID)) rowData.add(measurement.id)
                            if (currentProjection.contains(MeasurementColumns.DATETIME)) rowData.add(measurement.timestamp)
                            if (currentProjection.contains(MeasurementColumns.WEIGHT)) rowData.add(findValue(MeasurementTypeKey.WEIGHT))
                            if (currentProjection.contains(MeasurementColumns.BODY_FAT)) rowData.add(findValue(MeasurementTypeKey.BODY_FAT) ?: 0.0f)
                            if (currentProjection.contains(MeasurementColumns.WATER)) rowData.add(findValue(MeasurementTypeKey.WATER) ?: 0.0f)
                            if (currentProjection.contains(MeasurementColumns.MUSCLE)) rowData.add(findValue(MeasurementTypeKey.MUSCLE) ?: 0.0f)

                            LogManager.d(TAG, "Query Row #${index + 1} for user $userIdFromUri (MeasID: ${measurement.id}): ${
                                currentProjection.zip(rowData).joinToString { "${it.first}=${it.second}" }
                            }")

                            matrixCursor.addRow(rowData.toTypedArray())
                        }
                        matrixCursor
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error querying measurements for user $userIdFromUri: ${e.message}", e)
                        MatrixCursor(projection ?: arrayOf(MeasurementColumns.DATETIME), 0)
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown URI for query: $uri")
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!::databaseRepository.isInitialized) {
            LogManager.e(TAG, "DatabaseRepository not initialized in insert.")
            return null
        }
        if (values == null) {
            LogManager.w(TAG, "Attempted to insert null ContentValues.")
            return null
        }

        when (uriMatcher.match(uri)) {
            MATCH_TYPE_MEASUREMENT_LIST_FOR_USER -> {
                val userIdFromUri = try { ContentUris.parseId(uri).toInt() } catch (e: NumberFormatException) {
                    LogManager.e(TAG, "Invalid User ID in URI for measurement insert: $uri", e)
                    return null
                }

                if (values.containsKey(MeasurementColumns._ID)) {
                    val idFromValues = values.getAsInteger(MeasurementColumns._ID)
                    if (idFromValues != null && idFromValues != userIdFromUri) {
                        LogManager.w(TAG, "User ID in ContentValues ($idFromValues) mismatches User ID in URI ($userIdFromUri) for insert. Using User ID from URI.")
                    }
                }

                val datetime = values.getAsLong(MeasurementColumns.DATETIME)
                if (datetime == null) {
                    LogManager.e(TAG, "Cannot insert measurement: '${MeasurementColumns.DATETIME}' is missing. userId=$userIdFromUri")
                    return null
                }
                // Weight is considered mandatory for this provider's insert operation
                val weight = values.getAsFloat(MeasurementColumns.WEIGHT)
                if (weight == null) {
                    LogManager.e(TAG, "Cannot insert measurement: '${MeasurementColumns.WEIGHT}' is missing. datetime=$datetime, userId=$userIdFromUri")
                    return null
                }


                val measurement = Measurement(
                    // id = 0, // Room will generate this if it's an AutoGenerate PrimaryKey
                    userId = userIdFromUri,
                    timestamp = datetime
                )

                val measurementValuesToInsert = mutableListOf<MeasurementValue>()
                var weightTypeIdFound: Int? = null // To ensure weight type exists

                runBlocking {
                    val allMeasurementTypes = databaseRepository.getAllMeasurementTypes().first()
                    val typeIdMap = allMeasurementTypes.associate { it.key to it.id }
                    weightTypeIdFound = typeIdMap[MeasurementTypeKey.WEIGHT]

                    fun addValueIfPresent(cvKey: String, typeKey: MeasurementTypeKey, isMandatory: Boolean = false) {
                        if (values.containsKey(cvKey)) {
                            val floatValue = values.getAsFloat(cvKey)
                            if (floatValue != null) {
                                typeIdMap[typeKey]?.let { typeId ->
                                    measurementValuesToInsert.add(
                                        MeasurementValue(
                                            measurementId = 0,
                                            typeId = typeId,
                                            floatValue = floatValue
                                        )
                                    )
                                } ?: LogManager.w(TAG, "$typeKey MeasurementTypeKey not found. Cannot insert $cvKey value for key $cvKey.")
                            } else {
                                if (isMandatory) {
                                    LogManager.e(TAG, "Mandatory value for $cvKey ($typeKey) is missing and null in ContentValues.")
                                }
                            }
                        } else {
                            if (isMandatory) {
                                LogManager.e(TAG, "Mandatory key $cvKey ($typeKey) is not present in ContentValues.")
                            }
                        }
                    }


                    // Add weight (already checked for nullability)
                    if (weightTypeIdFound != null) {
                        measurementValuesToInsert.add(
                            MeasurementValue(
                                measurementId = 0,
                                typeId = weightTypeIdFound!!,
                                floatValue = weight
                            )
                        )
                    } else {
                        // This case should be caught by the mandatory weight check, but as a safeguard:
                        LogManager.e(TAG, "Weight MeasurementTypeKey not found internally, though weight value was provided.")
                    }

                    addValueIfPresent(MeasurementColumns.BODY_FAT, MeasurementTypeKey.BODY_FAT)
                    addValueIfPresent(MeasurementColumns.WATER, MeasurementTypeKey.WATER)
                    addValueIfPresent(MeasurementColumns.MUSCLE, MeasurementTypeKey.MUSCLE)
                }

                if (weightTypeIdFound == null) { // Double check if weight type ID was resolved
                    LogManager.e(TAG, "Weight MeasurementTypeKey system configuration issue. Cannot insert essential weight value.")
                    return null
                }


                if (measurementValuesToInsert.isEmpty()) {
                    LogManager.w(TAG, "No valid measurement values to insert (after mandatory weight). userId=$userIdFromUri, datetime=$datetime")
                    return null // Or decide to insert a measurement with no values if that's valid
                }

                try {
                    val insertedMeasurementId: Long? = runBlocking {
                        val pair = Pair(measurement, measurementValuesToInsert)
                        val ids = databaseRepository.insertMeasurementsWithValues(listOf(pair)) // Expects List<Pair<Measurement, List<MeasurementValue>>>
                        ids.first.firstOrNull() // Returns list of inserted measurement IDs
                    }

                    if (insertedMeasurementId != null && insertedMeasurementId > 0) {
                        // Notify change on the general list URI for this user
                        context!!.contentResolver.notifyChange(uri, null)
                        LogManager.d(TAG, "Measurement inserted with ID: $insertedMeasurementId. Old API compatibility: returning null.")
                        return null
                    } else {
                        LogManager.e(TAG, "Failed to insert measurement via Room or no ID returned.")
                        return null
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error during Room insert operation: ${e.message}", e)
                    return null
                }
            }
            else -> throw IllegalArgumentException("Unknown URI for insert: $uri.")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        if (!::databaseRepository.isInitialized) {
            LogManager.e(TAG, "DatabaseRepository not initialized in update.")
            return 0 // Return 0 rows affected
        }
        if (values == null || values.isEmpty) {
            LogManager.w(TAG, "Attempted to update with null or empty ContentValues.")
            return 0 // Return 0 rows affected
        }

        var rowsAffected = 0

        when (uriMatcher.match(uri)) {
            MATCH_TYPE_MEASUREMENT_LIST_FOR_USER -> {
                val targetUserIdFromUri = try {
                    ContentUris.parseId(uri).toInt()
                } catch (e: NumberFormatException) {
                    LogManager.e(TAG, "Invalid User ID in URI for measurement update: $uri", e)
                    return 0 // Return 0 rows affected
                }

                // DATETIME from ContentValues is used to identify the measurement to update.
                // It should NOT be used to change the timestamp of an existing measurement,
                // as that changes its identity. If you need to change a measurement's time,
                // it's a more complex operation (delete old, insert new, or a dedicated function).
                val datetimeToUpdate = values.getAsLong(MeasurementColumns.DATETIME)
                if (datetimeToUpdate == null) {
                    LogManager.e(
                        TAG,
                        "Cannot update measurement: '${MeasurementColumns.DATETIME}' is missing from ContentValues. " +
                                "This field is used to identify the measurement to update."
                    )
                    return 0
                }

                // Check if _ID in ContentValues mismatches the one in URI
                if (values.containsKey(MeasurementColumns._ID)) {
                    val idFromCV = values.getAsInteger(MeasurementColumns._ID)
                    if (idFromCV != null && idFromCV != targetUserIdFromUri) {
                        LogManager.w(
                            TAG,
                            "_ID in ContentValues ($idFromCV) mismatches _ID in URI ($targetUserIdFromUri) for update. " +
                                    "Operation will proceed for _ID from URI."
                        )
                    }
                }

                // Perform database operations within runBlocking
                rowsAffected = runBlocking {
                    try {
                        val allMeasurementTypes = databaseRepository.getAllMeasurementTypes().first()
                        val typeIdMap = allMeasurementTypes.associate { it.key to it.id }

                        // Find the existing measurement with its values
                        val existingMeasurementWithValues = databaseRepository
                            .getMeasurementsWithValuesForUser(targetUserIdFromUri)
                            .first()
                            .find { it.measurement.timestamp == datetimeToUpdate }

                        if (existingMeasurementWithValues == null) {
                            LogManager.d(
                                TAG,
                                "No measurement found to update for user $targetUserIdFromUri at datetime $datetimeToUpdate."
                            )
                            return@runBlocking 0 // Return 0 from runBlocking
                        }

                        val measurementToUpdate = existingMeasurementWithValues.measurement
                        var anyChangeMadeToValues = false

                        // Helper function to process updates for a specific measurement type
                        suspend fun processValueUpdate(cvKey: String, typeKey: MeasurementTypeKey) {
                            if (values.containsKey(cvKey)) {
                                val newValue = values.getAsFloat(cvKey) // Can be null if key exists but value is to be cleared/deleted
                                val typeId = typeIdMap[typeKey]

                                if (typeId == null) {
                                    LogManager.w(
                                        TAG,
                                        "MeasurementTypeKey '$typeKey' (for CV key '$cvKey') not found in typeIdMap. Cannot update/delete value."
                                    )
                                    return // Skip this value
                                }

                                val existingValueWithType =
                                    existingMeasurementWithValues.values.find { it.type.id == typeId }

                                if (newValue != null) { // New value is provided (update or insert)
                                    if (existingValueWithType != null) { // Value exists, try to update
                                        if (existingValueWithType.value.floatValue != newValue) {
                                            val updatedDbValue = existingValueWithType.value.copy(floatValue = newValue)
                                            databaseRepository.updateMeasurementValue(updatedDbValue)
                                            anyChangeMadeToValues = true
                                            LogManager.d(TAG, "Updated $typeKey for measurement ${measurementToUpdate.id} to $newValue")
                                        }
                                    } else { // Value doesn't exist for this type, insert new
                                        val newDbValue = MeasurementValue(
                                            // id = 0, // Room will generate
                                            measurementId = measurementToUpdate.id,
                                            typeId = typeId,
                                            floatValue = newValue
                                        )
                                        databaseRepository.insertMeasurementValue(newDbValue)
                                        anyChangeMadeToValues = true
                                        LogManager.d(TAG, "Inserted new $typeKey for measurement ${measurementToUpdate.id} with value $newValue")
                                    }
                                } else { // New value is null (ContentValues has the key, but its value is null) - implies delete
                                    existingValueWithType?.value?.let { valueToDelete ->
                                        // Special handling for essential values like WEIGHT if deletion is unintended by API design
                                        if (typeKey == MeasurementTypeKey.WEIGHT) {
                                            LogManager.w(
                                                TAG,
                                                "Attempt to delete WEIGHT value via update (by passing null for '${MeasurementColumns.WEIGHT}'). " +
                                                        "Weight value for measurement ID ${valueToDelete.measurementId} will be removed. " +
                                                        "Ensure this is intended as a measurement usually requires a weight."
                                            )
                                        }
                                        databaseRepository.deleteMeasurementValueById(valueToDelete.id) // Assuming delete by ID
                                        anyChangeMadeToValues = true
                                        LogManager.d(TAG, "Deleted $typeKey (ID: ${valueToDelete.id}) for measurement ${measurementToUpdate.id}")
                                    }
                                }
                            }
                        }

                        // Process updates for all relevant measurement types
                        processValueUpdate(MeasurementColumns.WEIGHT, MeasurementTypeKey.WEIGHT)
                        processValueUpdate(MeasurementColumns.BODY_FAT, MeasurementTypeKey.BODY_FAT)
                        processValueUpdate(MeasurementColumns.WATER, MeasurementTypeKey.WATER)
                        processValueUpdate(MeasurementColumns.MUSCLE, MeasurementTypeKey.MUSCLE)

                        if (anyChangeMadeToValues) {
                            // If any MeasurementValue changed, recalculate derived values
                            databaseRepository.recalculateDerivedValuesForMeasurement(measurementToUpdate.id)
                            LogManager.d(
                                TAG,
                                "Measurement values changed for user ${measurementToUpdate.userId}, " +
                                        "original timestamp: ${existingMeasurementWithValues.measurement.timestamp}. Derived values recalculated."
                            )
                            return@runBlocking 1 // Return 1 row affected from runBlocking
                        } else {
                            LogManager.d(
                                TAG,
                                "No actual changes detected for measurement values for user $targetUserIdFromUri at datetime $datetimeToUpdate."
                            )
                            return@runBlocking 0 // Return 0 from runBlocking
                        }

                    } catch (e: Exception) {
                        LogManager.e(
                            TAG,
                            "Error updating measurement for user $targetUserIdFromUri: ${e.message}",
                            e
                        )
                        return@runBlocking 0 // Return 0 from runBlocking on error
                    }
                }
            }

            else -> {
                LogManager.w(TAG, "Update operation not supported for URI: $uri")
                throw IllegalArgumentException("Unknown URI for update: $uri")
            }
        }

        if (rowsAffected > 0) {
            context!!.contentResolver.notifyChange(uri, null)
        }

        return rowsAffected
    }


    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        LogManager.w(TAG, "Delete operation is not supported by this provider.")
        // To implement delete:
        // 1. Identify user from URI (MATCH_TYPE_MEASUREMENT_LIST_FOR_USER implies user ID in URI)
        // 2. Identify specific measurement to delete. This usually requires more than just user ID.
        //    Commonly, the `selection` and `selectionArgs` would specify criteria like `DATETIME = ?`.
        //    Or, you'd have a URI like "measurements/<user_id>/<measurement_id>" (MATCH_TYPE_SINGLE_MEASUREMENT).
        // Example (conceptual):
        /*
        if (uriMatcher.match(uri) == MATCH_TYPE_MEASUREMENT_LIST_FOR_USER) {
            val userId = ContentUris.parseId(uri).toInt()
            if (selection != null && selectionArgs != null) {
                // Parse selection to find the measurement (e.g., by datetime)
                // val measurementToDelete = databaseRepository.findMeasurementByCriteria(userId, selection, selectionArgs)
                // if (measurementToDelete != null) {
                //     databaseRepository.deleteMeasurementWithValues(measurementToDelete)
                //     rowsAffected = 1
                //     context!!.contentResolver.notifyChange(uri, null)
                // }
            }
        }
        */
        throw UnsupportedOperationException("Delete not supported by this provider")
    }

    companion object {
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
        private const val API_VERSION = 1
        val AUTHORITY = BuildConfig.APPLICATION_ID + ".provider"

        private const val MATCH_TYPE_META = 1
        private const val MATCH_TYPE_USER_LIST = 2 // content://<authority>/users
        // content://<authority>/measurements/<user_id> (for list of measurements for a user)
        private const val MATCH_TYPE_MEASUREMENT_LIST_FOR_USER = 3

        init {
            uriMatcher.addURI(AUTHORITY, "meta", MATCH_TYPE_META)
            uriMatcher.addURI(AUTHORITY, "users", MATCH_TYPE_USER_LIST)
            // The '#' wildcard matches a number (user ID in this case)
            uriMatcher.addURI(AUTHORITY, "measurements/#", MATCH_TYPE_MEASUREMENT_LIST_FOR_USER)
        }
    }
}
