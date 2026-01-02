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
import com.health.openscale.BuildConfig
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.usecase.MeasurementTypeCrudUseCases
import com.health.openscale.core.utils.ConverterUtils
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
    fun measurementTypeCrudUseCases(): MeasurementTypeCrudUseCases
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
    private lateinit var measurementTypeUseCases: MeasurementTypeCrudUseCases

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
            measurementTypeUseCases = entryPoint.measurementTypeCrudUseCases()

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
                        val weightType = measurementTypeUseCases.getByKey(MeasurementTypeKey.WEIGHT)

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

                        measurementsWithValuesList.forEachIndexed { index, mcv -> // mcv is MeasurementWithValues
                            val measurement = mcv.measurement
                            val valuesMap = mcv.values.associateBy { it.type.key }
                            val weightInKg = valuesMap[MeasurementTypeKey.WEIGHT]?.value?.floatValue?.let {
                                weightType?.unit?.let { unit -> ConverterUtils.convertFloatValueUnit(it, unit, UnitType.KG) }
                            }

                            fun convertToPercent(key: MeasurementTypeKey): Float? {
                                val value = valuesMap[key]?.value?.floatValue ?: return null
                                val fromUnit = valuesMap[key]?.type?.unit ?: return null
                                if (fromUnit == UnitType.PERCENT) return value
                                if (fromUnit.isWeightUnit() && weightInKg != null && weightInKg > 0) {
                                    val valueInKg = ConverterUtils.convertFloatValueUnit(value, fromUnit, UnitType.KG)
                                    return (valueInKg / weightInKg) * 100f
                                }
                                return null
                            }

                            val fatPercent = convertToPercent(MeasurementTypeKey.BODY_FAT)
                            val waterPercent = convertToPercent(MeasurementTypeKey.WATER)
                            val musclePercent = convertToPercent(MeasurementTypeKey.MUSCLE)

                            val rowData = mutableListOf<Any?>()
                            if (currentProjection.contains(MeasurementColumns._ID)) rowData.add(measurement.id)
                            if (currentProjection.contains(MeasurementColumns.DATETIME)) rowData.add(measurement.timestamp)
                            if (currentProjection.contains(MeasurementColumns.WEIGHT)) rowData.add(weightInKg)
                            if (currentProjection.contains(MeasurementColumns.BODY_FAT)) rowData.add(fatPercent ?: 0.0f)
                            if (currentProjection.contains(MeasurementColumns.WATER)) rowData.add(waterPercent ?: 0.0f)
                            if (currentProjection.contains(MeasurementColumns.MUSCLE)) rowData.add(musclePercent ?: 0.0f)

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

                // Assume incoming weight is in the base unit (KG).
                val weightFromProviderInKg = values.getAsFloat(MeasurementColumns.WEIGHT)
                if (weightFromProviderInKg == null) {
                    LogManager.e(TAG, "Cannot insert measurement: '${MeasurementColumns.WEIGHT}' is mandatory and missing. datetime=$datetime, userId=$userIdFromUri")
                    return null
                }

                // Assume other incoming values are in their base unit (%).
                val fatFromProviderPercent = values.getAsFloat(MeasurementColumns.BODY_FAT)
                val waterFromProviderPercent = values.getAsFloat(MeasurementColumns.WATER)
                val muscleFromProviderPercent = values.getAsFloat(MeasurementColumns.MUSCLE)

                val measurement = Measurement(
                    // id = 0, // Room will generate this if it's an AutoGenerate PrimaryKey
                    userId = userIdFromUri,
                    timestamp = datetime
                )

                val measurementValuesToInsert = mutableListOf<MeasurementValue>()
                var weightTypeIdFound: Int? = null // To ensure weight type exists

                runBlocking {
                    val allMeasurementTypes = databaseRepository.getAllMeasurementTypes().first()
                    val weightType = allMeasurementTypes.find { it.key == MeasurementTypeKey.WEIGHT }
                    val fatType = allMeasurementTypes.find { it.key == MeasurementTypeKey.BODY_FAT }
                    val waterType = allMeasurementTypes.find { it.key == MeasurementTypeKey.WATER }
                    val muscleType = allMeasurementTypes.find { it.key == MeasurementTypeKey.MUSCLE }
                    val typeIdMap = allMeasurementTypes.associate { it.key to it.id }
                    weightTypeIdFound = typeIdMap[MeasurementTypeKey.WEIGHT]

                    if (weightType != null) {
                        val targetWeightValue = ConverterUtils.convertFloatValueUnit(weightFromProviderInKg, UnitType.KG, weightType.unit)
                        measurementValuesToInsert.add(MeasurementValue(measurementId = 0, typeId = weightType.id, floatValue = targetWeightValue))
                    } else {
                        LogManager.e(TAG, "Weight MeasurementType not found. Cannot insert.")
                        return@runBlocking null
                    }

                    fun addConvertedValue(valuePercent: Float?, targetType: MeasurementType?) {
                        if (valuePercent == null || targetType == null) return

                        val targetValue = when {
                            // If target is %, no conversion needed.
                            targetType.unit == UnitType.PERCENT -> valuePercent
                            // If target is a weight unit (kg, lb), convert % to absolute value.
                            targetType.unit.isWeightUnit() -> {
                                val absoluteValueInKg = (valuePercent / 100f) * weightFromProviderInKg
                                ConverterUtils.convertFloatValueUnit(absoluteValueInKg, UnitType.KG, targetType.unit)
                            }
                            // Fallback for other unit types.
                            else -> valuePercent
                        }
                        measurementValuesToInsert.add(MeasurementValue(measurementId = 0, typeId = targetType.id, floatValue = targetValue))
                    }

                    addConvertedValue(fatFromProviderPercent, fatType)
                    addConvertedValue(waterFromProviderPercent, waterType)
                    addConvertedValue(muscleFromProviderPercent, muscleType)
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

                // DATETIME is used to identify the measurement to update.
                val datetimeToUpdate = values.getAsLong(MeasurementColumns.DATETIME)
                if (datetimeToUpdate == null) {
                    LogManager.e(
                        TAG,
                        "Cannot update measurement: '${MeasurementColumns.DATETIME}' is missing. " +
                                "This field identifies the measurement to update."
                    )
                    return 0
                }

                // Assume incoming values are in base units (kg, %).
                val weightFromProviderInKg = values.getAsFloat(MeasurementColumns.WEIGHT)
                val fatFromProviderPercent = values.getAsFloat(MeasurementColumns.BODY_FAT)
                val waterFromProviderPercent = values.getAsFloat(MeasurementColumns.WATER)
                val muscleFromProviderPercent = values.getAsFloat(MeasurementColumns.MUSCLE)

                rowsAffected = runBlocking {
                    try {
                        val allMeasurementTypes = databaseRepository.getAllMeasurementTypes().first()
                        val typeMap = allMeasurementTypes.associateBy { it.key }

                        // Find the existing measurement
                        val existingMeasurementWithValues = databaseRepository
                            .getMeasurementsWithValuesForUser(targetUserIdFromUri)
                            .first()
                            .find { it.measurement.timestamp == datetimeToUpdate }

                        if (existingMeasurementWithValues == null) {
                            LogManager.d(TAG, "No measurement found to update for user $targetUserIdFromUri at datetime $datetimeToUpdate.")
                            return@runBlocking 0
                        }

                        val measurementToUpdate = existingMeasurementWithValues.measurement
                        var anyChangeMade = false

                        // Helper to update a value, converting from base unit to user's configured unit.
                        suspend fun processValueUpdate(
                            newValueFromProvider: Float?, // Value in base unit (kg or %)
                            typeKey: MeasurementTypeKey,
                            cvKey: String
                        ) {
                            if (!values.containsKey(cvKey)) return // This value is not being updated.

                            val targetType = typeMap[typeKey]
                            if (targetType == null) {
                                LogManager.w(TAG, "MeasurementType for key '$typeKey' not found. Cannot process update for '$cvKey'.")
                                return
                            }

                            val existingValue = existingMeasurementWithValues.values.find { it.type.id == targetType.id }

                            if (newValueFromProvider != null) { // A new value is provided (insert or update)
                                // --- START: Unit Conversion ---
                                val targetValue = when {
                                    typeKey == MeasurementTypeKey.WEIGHT -> {
                                        // Convert incoming KG to user's weight unit.
                                        ConverterUtils.convertFloatValueUnit(newValueFromProvider, UnitType.KG, targetType.unit)
                                    }
                                    targetType.unit.isWeightUnit() -> {
                                        // For composition, convert incoming % to absolute weight in user's unit.
                                        // The base weight for calculation must be the new weight being provided.
                                        val baseWeightInKg = weightFromProviderInKg ?: existingMeasurementWithValues.values
                                            .find { it.type.key == MeasurementTypeKey.WEIGHT }?.value?.floatValue?.let {
                                                typeMap[MeasurementTypeKey.WEIGHT]?.unit?.let { unit ->
                                                    ConverterUtils.convertFloatValueUnit(it, unit, UnitType.KG)
                                                }
                                            }
                                        if (baseWeightInKg == null) {
                                            LogManager.w(TAG, "Cannot convert '$cvKey' to absolute value: Base weight is unknown.")
                                            return
                                        }
                                        val absoluteInKg = (newValueFromProvider / 100f) * baseWeightInKg
                                        ConverterUtils.convertFloatValueUnit(absoluteInKg, UnitType.KG, targetType.unit)
                                    }
                                    // If target unit is % or other non-weight unit, use the value as is.
                                    else -> newValueFromProvider
                                }

                                if (existingValue != null) { // Value exists, so update it.
                                    if (existingValue.value.floatValue != targetValue) {
                                        val updatedDbValue = existingValue.value.copy(floatValue = targetValue)
                                        databaseRepository.updateMeasurementValue(updatedDbValue)
                                        anyChangeMade = true
                                        LogManager.d(TAG, "Updated $typeKey for measurement ${measurementToUpdate.id} to $targetValue ${targetType.unit}")
                                    }
                                } else { // Value doesn't exist, insert new.
                                    val newDbValue = MeasurementValue(
                                        measurementId = measurementToUpdate.id,
                                        typeId = targetType.id,
                                        floatValue = targetValue
                                    )
                                    databaseRepository.insertMeasurementValue(newDbValue)
                                    anyChangeMade = true
                                    LogManager.d(TAG, "Inserted new $typeKey for measurement ${measurementToUpdate.id} with value $targetValue ${targetType.unit}")
                                }
                            } else { // newValueFromProvider is null, which means delete.
                                existingValue?.value?.let { valueToDelete ->
                                    databaseRepository.deleteMeasurementValueById(valueToDelete.id)
                                    anyChangeMade = true
                                    LogManager.d(TAG, "Deleted $typeKey (ID: ${valueToDelete.id}) for measurement ${measurementToUpdate.id}")
                                }
                            }
                        }

                        // Process updates for all relevant measurement types
                        processValueUpdate(weightFromProviderInKg, MeasurementTypeKey.WEIGHT, MeasurementColumns.WEIGHT)
                        processValueUpdate(fatFromProviderPercent, MeasurementTypeKey.BODY_FAT, MeasurementColumns.BODY_FAT)
                        processValueUpdate(waterFromProviderPercent, MeasurementTypeKey.WATER, MeasurementColumns.WATER)
                        processValueUpdate(muscleFromProviderPercent, MeasurementTypeKey.MUSCLE, MeasurementColumns.MUSCLE)

                        if (anyChangeMade) {
                            LogManager.d(TAG, "Measurement values changed for user ${measurementToUpdate.userId}. Derived values will be recalculated by repository calls.")
                            return@runBlocking 1 // Return 1 row affected
                        } else {
                            LogManager.d(TAG, "No actual changes detected for measurement values for user $targetUserIdFromUri at datetime $datetimeToUpdate.")
                            return@runBlocking 0 // Return 0 rows affected
                        }

                    } catch (e: Exception) {
                        LogManager.e(TAG, "Error updating measurement for user $targetUserIdFromUri: ${e.message}", e)
                        return@runBlocking 0
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
