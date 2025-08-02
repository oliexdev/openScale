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
package com.health.openscale.ui.screen

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.data.Trend
import com.health.openscale.core.data.User
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.database.UserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

private const val TAG = "SharedViewModel"

/**
 * Represents an event to display a Snackbar.
 * It supports internationalization through string resource IDs and formatted strings,
 * as well as direct string content as a fallback.
 *
 * @property messageResId The resource ID for the Snackbar message. Defaults to 0 if not used.
 * @property message A direct string for the Snackbar message. Used if [messageResId] is 0.
 * @property messageFormatArgs Optional arguments for formatting the [messageResId] string.
 * @property duration The [SnackbarDuration] for which the Snackbar is shown.
 * @property actionLabelResId Optional resource ID for the Snackbar's action button label.
 * @property actionLabel Optional direct string for the action button label. Used if [actionLabelResId] is null.
 * @property onAction Optional lambda to be executed when the action button is pressed.
 */
data class SnackbarEvent(
    @StringRes val messageResId: Int = 0,
    val message: String = "",
    val messageFormatArgs: Array<Any>? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    @StringRes val actionLabelResId: Int? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/**
 * Represents a single measurement value ([MeasurementValueWithType]) enhanced with its
 * calculated difference from a previous value and a [Trend] indicator.
 *
 * @property currentValue The [MeasurementValueWithType] this data is about.
 * @property difference The calculated difference (e.g., current - previous). Null if not applicable or no previous value.
 * @property trend The [Trend] indicating if the value went up, down, stayed the same, or is not applicable.
 */
data class ValueWithDifference(
    val currentValue: MeasurementValueWithType,
    val difference: Float? = null,
    val trend: Trend = Trend.NOT_APPLICABLE
)

/**
 * Represents a complete measurement ([MeasurementWithValues]) where each of its individual values
 * has been enriched with trend information, resulting in a list of [ValueWithDifference].
 * This is typically used for display purposes where trend indicators are shown next to values.
 *
 * @property measurementWithValues The original [MeasurementWithValues] data.
 * @property valuesWithTrend A list of [ValueWithDifference], corresponding to each value in [measurementWithValues],
 *                           but enriched with trend and difference information.
 */
data class EnrichedMeasurement(
    val measurementWithValues: MeasurementWithValues,
    val valuesWithTrend: List<ValueWithDifference>
)

/**
 * Shared ViewModel for managing UI state and business logic accessible across multiple screens.
 * It handles user selection, measurement data (CRUD operations, display, enrichment),
 * and UI elements like top bar titles/actions and Snackbars.
 *
 * @param databaseRepository Repository for accessing measurement and user data from the database.
 * @param userSettingRepository Repository for managing user-specific settings, like the last selected user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedViewModel(
    val databaseRepository: DatabaseRepository,
    val userSettingRepository: UserSettingsRepository
) : ViewModel() {

    // --- Top Bar UI State ---

    private val _topBarTitle = MutableStateFlow<Any>(R.string.app_name)
    val topBarTitle: StateFlow<Any> = _topBarTitle.asStateFlow()

    fun setTopBarTitle(title: String) {
        _topBarTitle.value = title
    }

    fun setTopBarTitle(@StringRes titleResId: Int) {
        _topBarTitle.value = titleResId
    }

    data class TopBarAction(
        val icon: ImageVector,
        val onClick: () -> Unit,
        @StringRes val contentDescriptionResId: Int? = null,
        val contentDescription: String? = null,
        val dropdownContent: (@Composable () -> Unit)? = null
    )

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarAction(action: TopBarAction?) {
        _topBarActions.value = if (action != null) listOf(action) else emptyList()
    }

    fun setTopBarActions(actions: List<TopBarAction>) {
        _topBarActions.value = actions
    }

    // --- Snackbar UI Event Channel ---

    private val _snackbarChannel = MutableSharedFlow<SnackbarEvent>() // Consider extraBufferCapacity = 1
    val snackbarChannel: Flow<SnackbarEvent> = _snackbarChannel.asSharedFlow()

    fun showSnackbar(
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            LogManager.v(TAG, "Snackbar requested (String): \"$message\" (UI Event)")
            _snackbarChannel.emit(
                SnackbarEvent(
                    message = message,
                    duration = duration,
                    actionLabel = actionLabel,
                    onAction = onAction
                )
            )
        }
    }

    fun showSnackbar(
        @StringRes messageResId: Int,
        formatArgs: Array<Any>? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        @StringRes actionLabelResId: Int? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            LogManager.v(TAG, "Snackbar requested (Res ID): $messageResId, HasFormatArgs: ${formatArgs != null} (UI Event)")
            val finalActionLabel = if (actionLabelResId != null) null else actionLabel
            _snackbarChannel.emit(
                SnackbarEvent(
                    messageResId = messageResId,
                    messageFormatArgs = formatArgs,
                    duration = duration,
                    actionLabelResId = actionLabelResId,
                    actionLabel = finalActionLabel,
                    onAction = onAction
                )
            )
        }
    }

    // --- User Management ---

    private val _selectedUserId = MutableStateFlow<Int?>(null)
    val selectedUserId: StateFlow<Int?> = _selectedUserId.asStateFlow()

    val allUsers: StateFlow<List<User>> = databaseRepository.getAllUsers()
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        ).also {
            LogManager.v(TAG, "allUsers flow initialized. (Data Flow)")
        }

    val selectedUser: StateFlow<User?> = selectedUserId.flatMapLatest { userId ->
        if (userId == null) {
            LogManager.d(TAG, "No user ID selected, selectedUser Flow emits null. (User Data Flow)")
            MutableStateFlow<User?>(null)
        } else {
            LogManager.d(TAG, "Fetching user by ID: $userId for selectedUser Flow. (User Data Flow)")
            databaseRepository.getUserById(userId).flowOn(Dispatchers.IO)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null
    ).also {
        LogManager.v(TAG, "selectedUser flow initialized. (User Data Flow)")
    }

    fun selectUser(userId: Int?) {
        viewModelScope.launch {
            _selectedUserId.value = userId
            userSettingRepository.setCurrentUserId(userId)
            LogManager.i(TAG, "User selection changed to ID: $userId. Persisted to settings. (User Action)")
        }
    }

    // --- Measurement Type Data ---

    val measurementTypes: StateFlow<List<MeasurementType>> = databaseRepository.getAllMeasurementTypes()
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        ).also {
            LogManager.v(TAG, "measurementTypes flow initialized. (Data Flow)")
        }

    // --- Current Measurement Management (for editing/detail view) ---

    private val _currentMeasurementId = MutableStateFlow<Int?>(null)

    val currentMeasurementWithValues: StateFlow<MeasurementWithValues?> = _currentMeasurementId
        .flatMapLatest { id ->
            if (id == null || id == -1) {
                LogManager.d(TAG, "Current measurement ID is $id, emitting null for currentMeasurementWithValues. (Measurement Detail Flow)")
                MutableStateFlow<MeasurementWithValues?>(null)
            } else {
                LogManager.d(TAG, "Fetching measurement with values for ID: $id for currentMeasurementWithValues flow. (Measurement Detail Flow)")
                databaseRepository.getMeasurementWithValuesById(id).flowOn(Dispatchers.IO)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        ).also {
            LogManager.v(TAG, "currentMeasurementWithValues flow initialized. (Measurement Detail Flow)")
        }

    fun setCurrentMeasurementId(measurementId: Int?) {
        _currentMeasurementId.value = measurementId
        LogManager.d(TAG, "Current measurement ID set to: $measurementId (UI/Navigation Action)")
    }

    // --- Measurement CRUD Operations ---

    fun saveMeasurement(measurementToSave: Measurement, valuesToSave: List<MeasurementValue>) {
        viewModelScope.launch(Dispatchers.IO) {
            val isNewMeasurement = measurementToSave.id == 0
            val operationType = if (isNewMeasurement) "insert" else "update"
            LogManager.i(TAG, "User initiated $operationType for measurement. (User Action to Data Operation)")

            try {
                if (!isNewMeasurement) {
                    LogManager.d(TAG, "Preparing to update existing measurement ID: ${measurementToSave.id}. (ViewModel Logic)")
                    databaseRepository.updateMeasurement(measurementToSave)

                    val existingDbValues = databaseRepository.getValuesForMeasurement(measurementToSave.id).first()
                    val valueIdsInNewSet = valuesToSave.mapNotNull { if (it.id != 0) it.id else null }.toSet()
                    val valueIdsInDbSet = existingDbValues.map { it.id }.toSet()

                    val valueIdsToDelete = valueIdsInDbSet - valueIdsInNewSet
                    valueIdsToDelete.forEach { valueId ->
                        databaseRepository.deleteMeasurementValueById(valueId)
                    }

                    valuesToSave.forEach { value ->
                        val existingValue = existingDbValues.find { dbVal -> dbVal.id == value.id && value.id != 0 }
                        if (existingValue != null) {
                            databaseRepository.updateMeasurementValue(value.copy(measurementId = measurementToSave.id))
                        } else {
                            databaseRepository.insertMeasurementValue(value.copy(measurementId = measurementToSave.id))
                        }
                    }
                    LogManager.i(TAG, "Measurement ID ${measurementToSave.id} and its values update process completed by ViewModel. (ViewModel Result)")
                    showSnackbar(messageResId = R.string.success_measurement_updated)
                } else {
                    LogManager.d(TAG, "Preparing to insert new measurement. (ViewModel Logic)")
                    val newMeasurementId = databaseRepository.insertMeasurement(measurementToSave).toInt()
                    valuesToSave.forEach { value ->
                        databaseRepository.insertMeasurementValue(value.copy(measurementId = newMeasurementId))
                    }
                    LogManager.i(TAG, "New measurement insertion process completed by ViewModel with ID: $newMeasurementId. (ViewModel Result)")
                    showSnackbar(messageResId = R.string.success_measurement_saved)
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during $operationType orchestration for measurement (ID if existing: ${measurementToSave.id}): ${e.message}", e)
                showSnackbar(messageResId = R.string.error_saving_measurement)
            }
        }
    }

    fun deleteMeasurement(measurement: Measurement) {
        viewModelScope.launch(Dispatchers.IO) {
            LogManager.i(TAG, "User initiated deletion for measurement ID: ${measurement.id}. (User Action to Data Operation)")
            try {
                LogManager.d(TAG, "Preparing to delete measurement ID: ${measurement.id}. (ViewModel Logic)")
                databaseRepository.deleteMeasurement(measurement)
                LogManager.i(TAG, "Measurement ID ${measurement.id} deletion process completed by ViewModel. (ViewModel Result)")
                showSnackbar(messageResId = R.string.success_measurement_deleted)
                if (_currentMeasurementId.value == measurement.id) {
                    _currentMeasurementId.value = null
                    LogManager.d(TAG, "Cleared currentMeasurementId as deleted measurement was active. (ViewModel State Update)")
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error during delete orchestration for measurement ID ${measurement.id}: ${e.message}", e)
                showSnackbar(messageResId = R.string.error_deleting_measurement)
            }
        }
    }

    // --- Displaying Measurement Lists & Enriched Data ---

    val allMeasurementsForSelectedUser: StateFlow<List<MeasurementWithValues>> =
        selectedUserId
            .flatMapLatest { userId ->
                if (userId == null) {
                    LogManager.d(TAG, "No user selected, allMeasurementsForSelectedUser emitting empty list. (Measurement List Flow)")
                    MutableStateFlow(emptyList<MeasurementWithValues>())
                } else {
                    LogManager.d(TAG, "Fetching all measurements for user ID: $userId for allMeasurementsForSelectedUser flow. (Measurement List Flow)")
                    databaseRepository.getMeasurementsWithValuesForUser(userId).flowOn(Dispatchers.IO)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            ).also {
                LogManager.v(TAG, "allMeasurementsForSelectedUser flow initialized. (Measurement List Flow)")
            }

    val lastMeasurementOfSelectedUser: StateFlow<MeasurementWithValues?> =
        allMeasurementsForSelectedUser.map { measurements ->
            measurements.firstOrNull().also {
                LogManager.d(TAG, "Last measurement for selected user updated. Has value: ${it != null}. (Derived Data Flow)")
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        ).also {
            LogManager.v(TAG, "lastMeasurementOfSelectedUser flow initialized. (Derived Data Flow)")
        }

    private val _isBaseDataLoading = MutableStateFlow(false)
    val isBaseDataLoading: StateFlow<Boolean> = _isBaseDataLoading.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val enrichedMeasurementsFlow: StateFlow<List<EnrichedMeasurement>> =
        allMeasurementsForSelectedUser.combine(measurementTypes) { measurements, globalTypes ->
            LogManager.v(TAG, "Recalculating enrichedMeasurementsFlow. Measurements: ${measurements.size}, GlobalTypes: ${globalTypes.size}. (Data Enrichment Logic)")
            if (measurements.isEmpty()) {
                return@combine emptyList<EnrichedMeasurement>()
            }

            if (globalTypes.isEmpty()) {
                LogManager.w(TAG, "Global measurement types are empty during enrichment. Trend calculation will be limited or inaccurate. (Data Enrichment Warning)")
                return@combine measurements.map { currentMeasurement ->
                    val trendValuesUnsorted = currentMeasurement.values.map { currentValueWithType ->
                        val (difference, trendResult) = calculateSingleValueTrendLogic(
                            currentValueWithType,
                            null,
                            currentValueWithType.type
                        )
                        ValueWithDifference(currentValueWithType, difference, trendResult)
                    }
                    EnrichedMeasurement(currentMeasurement, trendValuesUnsorted)
                }
            }

            measurements.mapIndexed { index, currentMeasurement ->
                val previousMeasurement: MeasurementWithValues? = measurements.getOrNull(index + 1)
                val processedAndSortedTrendValues = currentMeasurement.values
                    .mapNotNull { valueWithType ->
                        val fullType = globalTypes.find { it.id == valueWithType.type.id }
                        if (fullType == null || !fullType.isEnabled) {
                            if (fullType == null) {
                                LogManager.w(TAG, "Measurement value type ID ${valueWithType.type.id} not found in global types during enrichment. Skipping value. (Data Enrichment Warning)")
                            } else {
                                LogManager.d(TAG, "Measurement value type (ID: ${fullType.id}) is disabled globally. Skipping value in enrichment. (Data Enrichment Logic)")
                            }
                            null
                        } else {
                            val previousValueForType = previousMeasurement?.values?.find { it.type.id == fullType.id }
                            val (difference, trendResult) = calculateSingleValueTrendLogic(
                                valueWithType,
                                previousValueForType,
                                fullType
                            )
                            ValueWithDifference(valueWithType.copy(type = fullType), difference, trendResult)
                        }
                    }
                    .sortedBy { valueWithDiff ->
                        valueWithDiff.currentValue.type.displayOrder
                    }
                EnrichedMeasurement(currentMeasurement, processedAndSortedTrendValues)
            }
        }
            .onStart {
                LogManager.d(TAG, "enrichedMeasurementsFlow collection started, setting base data loading to true. (Flow Lifecycle)")
                _isBaseDataLoading.value = true
            }
            .mapLatest { enrichedMeasurements ->
                _isBaseDataLoading.value = false
                LogManager.d(TAG, "enrichedMeasurementsFlow processing complete. Count: ${enrichedMeasurements.size}. Base data loading set to false. (Flow Update)")
                enrichedMeasurements
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            ).also {
                LogManager.v(TAG, "enrichedMeasurementsFlow initialized. (Data Enrichment Flow)")
            }

    fun getTimeFilteredEnrichedMeasurements(
        selectedTimeRange: TimeRangeFilter
    ): Flow<List<EnrichedMeasurement>> {
        LogManager.v(TAG, "Request to get time-filtered enriched measurements for range: $selectedTimeRange. (Filtering Request)")
        return enrichedMeasurementsFlow.map { allEnrichedMeasurements ->
            LogManager.v(TAG, "Applying time filter '$selectedTimeRange' to ${allEnrichedMeasurements.size} enriched measurements. (Filtering Logic)")
            if (selectedTimeRange == TimeRangeFilter.ALL_DAYS) {
                allEnrichedMeasurements
            } else {
                val calendar = Calendar.getInstance()
                val endTime = calendar.timeInMillis

                when (selectedTimeRange) {
                    TimeRangeFilter.LAST_7_DAYS -> calendar.add(Calendar.DAY_OF_YEAR, -7)
                    TimeRangeFilter.LAST_30_DAYS -> calendar.add(Calendar.DAY_OF_YEAR, -30)
                    TimeRangeFilter.LAST_365_DAYS -> calendar.add(Calendar.DAY_OF_YEAR, -365)
                    else -> { /* Handled */ }
                }
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startTime = calendar.timeInMillis

                allEnrichedMeasurements.filter {
                    it.measurementWithValues.measurement.timestamp in startTime..endTime
                }
            }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /**
     * Filters a list of already (e.g., time-filtered) enriched measurements by selected measurement type IDs.
     * This is a synchronous operation on a given list, intended for further refinement of an already processed list.
     *
     * @param measurementsToFilter The list of [EnrichedMeasurement]s to filter.
     * @param selectedTypeIds A set of type IDs to filter by. If empty, returns the original list unmodified.
     * @return A new list containing only measurements that include at least one of the selected types.
     *         The values within each measurement are not filtered, only the top-level measurements.
     */
    fun filterEnrichedMeasurementsByTypes(
        measurementsToFilter: List<EnrichedMeasurement>,
        selectedTypeIds: Set<Int>
    ): List<EnrichedMeasurement> {
        LogManager.d(TAG, "Filtering ${measurementsToFilter.size} enriched measurements by type IDs. Selected count: ${selectedTypeIds.size}. (Synchronous Filter)")
        if (selectedTypeIds.isEmpty()) {
            return measurementsToFilter
        }
        return measurementsToFilter.filter { enrichedMeasurement ->
            enrichedMeasurement.valuesWithTrend.any { valueWithDifference ->
                valueWithDifference.currentValue.type.id in selectedTypeIds
            }
        }
    }

    /**
     * Internal helper to calculate the difference and trend for a single measurement value
     * compared to its previous value, considering the measurement type.
     * This logic is central to the enrichment process.
     *
     * @param currentMeasurementValue The current value with its type.
     * @param previousMeasurementValue The corresponding previous value with its type. Can be null.
     * @param type The definitive [com.health.openscale.core.data.MeasurementType] of the value,
     *             assumed to be globally enabled and correct for trend calculation.
     * @return A Pair containing the calculated difference (Float?) and the determined [com.health.openscale.core.data.Trend].
     */
    private fun calculateSingleValueTrendLogic(
        currentMeasurementValue: MeasurementValueWithType, // Current value is non-null here as per usage
        previousMeasurementValue: MeasurementValueWithType?,
        type: MeasurementType
    ): Pair<Float?, Trend> {
        var differenceValue: Float? = null
        var trend = Trend.NOT_APPLICABLE

        if (previousMeasurementValue != null) {
            if (currentMeasurementValue.type.id == previousMeasurementValue.type.id && currentMeasurementValue.type.id == type.id) {
                when (type.inputType) {
                    InputFieldType.FLOAT -> {
                        val currentVal = currentMeasurementValue.value.floatValue
                        val previousVal = previousMeasurementValue.value.floatValue
                        if (currentVal != null && previousVal != null) {
                            differenceValue = currentVal - previousVal
                            trend = when {
                                differenceValue > 0.001f -> Trend.UP
                                differenceValue < -0.001f -> Trend.DOWN
                                else -> Trend.NONE
                            }
                        }
                    }
                    InputFieldType.INT -> {
                        val currentVal = currentMeasurementValue.value.intValue
                        val previousVal = previousMeasurementValue.value.intValue
                        if (currentVal != null && previousVal != null) {
                            differenceValue = (currentVal - previousVal).toFloat()
                            trend = when {
                                differenceValue > 0f -> Trend.UP
                                differenceValue < 0f -> Trend.DOWN
                                else -> Trend.NONE
                            }
                        }
                    }
                    else -> {
                        trend = Trend.NOT_APPLICABLE
                    }
                }
            } else {
                LogManager.w(TAG, "Trend calculation skipped: type ID mismatch. Current: ${currentMeasurementValue.type.id}, Previous: ${previousMeasurementValue.type.id}, Authoritative Type: ${type.id}. (Trend Logic Warning)")
            }
        } else {
            trend = Trend.NOT_APPLICABLE
        }
        return differenceValue to trend
    }

    init {
        LogManager.i(TAG, "ViewModel initializing... (Lifecycle Event)")
        setTopBarTitle(R.string.app_name)

        viewModelScope.launch {
            LogManager.d(TAG, "Init: Attempting to load last selected user ID from UserSettingsRepository. (Initialization Logic)")
            val lastSelectedId = userSettingRepository.currentUserId.first()

            if (lastSelectedId != null) {
                LogManager.d(TAG, "Init: User ID $lastSelectedId found in settings. Verifying existence in database. (Initialization Logic)")
                val userExists = databaseRepository.getUserById(lastSelectedId)
                    .flowOn(Dispatchers.IO)
                    .first() != null

                if (userExists) {
                    _selectedUserId.value = lastSelectedId
                    LogManager.i(TAG, "Init: User $lastSelectedId loaded from settings and verified in DB. Set as selected. (Initialization Result)")
                } else {
                    LogManager.w(TAG, "Init: User $lastSelectedId from settings not found in DB. Clearing selection. (Initialization Warning)")
                    _selectedUserId.value = null
                    userSettingRepository.setCurrentUserId(null)
                }
            } else {
                LogManager.i(TAG, "Init: No user ID found in settings. No user auto-selected. (Initialization Logic)")
            }
        }
        LogManager.i(TAG, "ViewModel initialization complete. (Lifecycle Event)")
    }
}

/**
 * Utility function to create a [ViewModelProvider.Factory] for ViewModels that have constructor dependencies.
 */
inline fun <VM : ViewModel> createViewModelFactory(crossinline creator: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return creator() as T
        }
    }
