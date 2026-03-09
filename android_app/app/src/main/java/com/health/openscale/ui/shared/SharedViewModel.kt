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
package com.health.openscale.ui.shared

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.data.AggregationLevel
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.facade.DataManagementFacade
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.model.EnrichedMeasurement
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.usecase.MeasurementEvaluationResult
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.components.AGGREGATION_LEVEL_SUFFIX
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Shared VM coordinating user selection, measurement flows, and UI chrome.
 * Depends only on UserFacade & MeasurementFacade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SharedViewModel @Inject constructor(
    private val userFacade: UserFacade,
    private val measurementFacade: MeasurementFacade,
    private val dataManagementFacade: DataManagementFacade,
    private val settingsFacade: SettingsFacade
) : ViewModel(), SettingsFacade by settingsFacade {
    companion object {
        private const val TAG = "SharedViewModel"
    }

    sealed interface UiState<out T> {
        data object Loading : UiState<Nothing>
        data class Success<T>(val data: T) : UiState<T>
        data class Error(val message: String? = null) : UiState<Nothing>
    }

    // --- Top Bar state (UI chrome) ---
    private val _topBarTitle = MutableStateFlow<Any>(R.string.app_name)
    val topBarTitle: StateFlow<Any> = _topBarTitle.asStateFlow()
    fun setTopBarTitle(title: String) { _topBarTitle.value = title }
    fun setTopBarTitle(@StringRes titleResId: Int) { _topBarTitle.value = titleResId }

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()
    fun setTopBarAction(action: TopBarAction?) { _topBarActions.value = if (action != null) listOf(action) else emptyList() }
    fun setTopBarActions(actions: List<TopBarAction>) { _topBarActions.value = actions }

    private val _isInContextualSelectionMode = MutableStateFlow(false)
    val isInContextualSelectionMode: StateFlow<Boolean> = _isInContextualSelectionMode.asStateFlow()

    fun setContextualSelectionMode(isActive: Boolean) {
        _isInContextualSelectionMode.value = isActive
    }

    // --- Bluetooth Assisted Weighing State ---
    private val _pendingAssistedWeighingUser = MutableStateFlow<User?>(null)
    val pendingAssistedWeighingUser = _pendingAssistedWeighingUser.asStateFlow()

    fun setPendingAssistedWeighingUser(user: User?) {
        _pendingAssistedWeighingUser.value = user
    }

    fun setPendingReferenceUserForBle(referenceUser: User?) {
        viewModelScope.launch {
            measurementFacade.setPendingReferenceUserForBle(referenceUser)
        }
    }

    // --- Snackbar events ---
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    fun showSnackbar(
        message: String? = null,
        @StringRes messageResId: Int? = null,
        formatArgs: List<Any> = emptyList(),
        duration: SnackbarDuration = SnackbarDuration.Short,
        @StringRes actionLabelResId: Int? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (message == null && messageResId == null) return

        viewModelScope.launch {
            _snackbarEvents.emit(
                SnackbarEvent(
                    message = message,
                    messageResId = messageResId ?: 0,
                    messageFormatArgs = formatArgs,
                    duration = duration,
                    actionLabelResId = actionLabelResId,
                    actionLabel = if (actionLabelResId != null) null else actionLabel,
                    onAction = onAction
                )
            )
        }
    }

    private val didRunDerivedBackfill = AtomicBoolean(false)
    private val _isInitialUserLoadComplete = MutableStateFlow(false)

    // --- Users (via UserFacade) ---
    val allUsers: StateFlow<List<User>> =
        userFacade.observeAllUsers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedUserId: StateFlow<Int?> =
        userFacade.observeSelectedUserId()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedUser: StateFlow<User?> =
        userFacade.observeSelectedUser()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- User Goals ---
    data class UserGoalDialogContext(
        val showDialog: Boolean = false,
        val typeForDialog: MeasurementType? = null,
        val existingGoalForDialog: UserGoals? = null,
    )

    private val _userGoalDialogContext = MutableStateFlow(UserGoalDialogContext())
    val userGoalDialogContext: StateFlow<UserGoalDialogContext> = _userGoalDialogContext.asStateFlow()

    fun showUserGoalDialogWithContext(type: MeasurementType, existingGoal: UserGoals? = null) {
        _userGoalDialogContext.value = UserGoalDialogContext(
            showDialog = true,
            typeForDialog = type,
            existingGoalForDialog = existingGoal,
        )
    }

    fun dismissUserGoalDialogWithContext() {
        if (_userGoalDialogContext.value.showDialog) {
            _userGoalDialogContext.value = UserGoalDialogContext(showDialog = false)
        }
    }

    fun getAllGoalsForUser(userId: Int): Flow<List<UserGoals>> {
        if (userId == 0) return flowOf(emptyList())
        return userFacade.getAllGoalsForUser(userId)
            .catch { emit(emptyList()) }
    }

    fun insertUserGoal(goal: UserGoals) {
        viewModelScope.launch { userFacade.insertUserGoal(goal) }
    }

    fun updateUserGoal(goal: UserGoals) {
        viewModelScope.launch { userFacade.updateUserGoal(goal) }
    }

    fun deleteUserGoal(userId: Int, measurementTypeId: Int) {
        viewModelScope.launch { userFacade.deleteUserGoal(userId, measurementTypeId) }
    }

    val userEvaluationContext: StateFlow<UserEvaluationContext?> =
        userFacade.observeUserEvaluationContext()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectUser(userId: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            userFacade.setSelectedUserId(userId)
                .onSuccess { LogManager.i(TAG, "Selected user: $userId") }
                .onFailure {
                    LogManager.e(TAG, "Failed to select user: $userId -> ${it.message}")
                    showSnackbar(messageResId = R.string.error_selecting_user)
                }
        }
    }

    // --- Aggregation level flows (per screen context) ---

    /**
     * Returns a Flow emitting the current [AggregationLevel] for the given screen context.
     * The value is persisted in DataStore under "${screenContextName}_aggregation_level".
     */
    fun observeAggregationLevel(screenContextName: String): Flow<AggregationLevel> {
        val key = "${screenContextName}${AGGREGATION_LEVEL_SUFFIX}"
        return observeSetting(key, AggregationLevel.NONE.name)
            .map { name ->
                AggregationLevel.entries.find { it.name == name } ?: AggregationLevel.NONE
            }
    }

    /**
     * Persists an [AggregationLevel] for the given screen context.
     */
    suspend fun saveAggregationLevel(screenContextName: String, level: AggregationLevel) {
        val key = "${screenContextName}${AGGREGATION_LEVEL_SUFFIX}"
        saveSetting(key, level.name)
    }

    // --- Overview UI state ---
    val overviewUiState: StateFlow<UiState<List<EnrichedMeasurement>>> =
        _isInitialUserLoadComplete
            .flatMapLatest { initialAttemptDone ->
                if (!initialAttemptDone) {
                    flowOf(UiState.Loading)
                } else {
                    userFacade.observeSelectedUserId().flatMapLatest { uidFromFacade ->
                        if (uidFromFacade == null) {
                            flowOf(UiState.Success(emptyList()))
                        } else {
                            // Overview does not aggregate (shows all raw measurements)
                            measurementFacade.enrichedFlowForUser(uidFromFacade, measurementTypes)
                                .map<List<EnrichedMeasurement>, UiState<List<EnrichedMeasurement>>> {
                                    UiState.Success(it)
                                }
                                .onStart { emit(UiState.Loading) }
                                .catch { emit(UiState.Error(it.message)) }
                        }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = UiState.Loading
            )

    // --- Graph UI state (with aggregation) ---
    val graphUiState: StateFlow<UiState<List<EnrichedMeasurement>>> =
        _isInitialUserLoadComplete
            .flatMapLatest { initialAttemptDone ->
                if (!initialAttemptDone) {
                    flowOf(UiState.Loading)
                } else {
                    userFacade.observeSelectedUserId().flatMapLatest { uidFromFacade ->
                        if (uidFromFacade == null) {
                            flowOf(UiState.Success(emptyList()))
                        } else {
                            measurementFacade.pipeline(
                                userId = uidFromFacade,
                                measurementTypesFlow = measurementTypes,
                                startTimeMillisFlow = flowOf(null),
                                endTimeMillisFlow = flowOf(null),
                                typesToSmoothFlow = typesToSmoothAndDisplay,
                                algorithmFlow = selectedSmoothingAlgorithm,
                                alphaFlow = smoothingAlpha,
                                windowFlow = smoothingWindowSize,
                                maxGapDaysFlow = smoothingMaxGapDays,
                                aggregationLevelFlow = observeAggregationLevel(
                                    SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT
                                )
                            )
                                .map<List<EnrichedMeasurement>, UiState<List<EnrichedMeasurement>>> {
                                    UiState.Success(it)
                                }
                                .onStart { emit(UiState.Loading) }
                                .catch { emit(UiState.Error(it.message)) }
                        }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UiState.Loading
            )

    fun statisticsUiState(
        startTimeMillis: Long?,
        endTimeMillis: Long?
    ): Flow<UiState<List<EnrichedMeasurement>>> =
        selectedUserId.flatMapLatest { uid ->
            if (uid == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                measurementFacade
                    .timeFilteredEnrichedFlow(
                        userId = uid,
                        measurementTypesFlow = measurementTypes,
                        startTimeMillis = startTimeMillis,
                        endTimeMillis = endTimeMillis
                    )
                    .map<List<EnrichedMeasurement>, UiState<List<EnrichedMeasurement>>> { UiState.Success(it) }
                    .onStart { emit(UiState.Loading) }
                    .catch { emit(UiState.Error(it.message)) }
            }
        }

    fun performCsvExport(userId: Int, uri: Uri, contentResolver: ContentResolver, filterByMeasurementIds: List<Int>? = null) {
        viewModelScope.launch {
            try {
                val rows = dataManagementFacade.exportUserToCsv(userId, uri, contentResolver, filterByMeasurementIds).getOrThrow()
                if (rows > 0) showSnackbar(messageResId = R.string.export_successful)
                else showSnackbar(messageResId = R.string.export_error_no_exportable_values)
            } catch (e: Exception) {
                LogManager.e(TAG, "CSV export error", e)
                showSnackbar(messageResId = R.string.export_error_generic, formatArgs = listOf(e.localizedMessage ?: "Unknown error"))
            }
        }
    }

    // --- Measurement types (via MeasurementFacade) ---
    val measurementTypes: StateFlow<List<MeasurementType>> =
        measurementFacade.getAllMeasurementTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Current measurement (detail) ---
    private val _currentMeasurementId = MutableStateFlow<Int?>(null)
    val currentMeasurementWithValues: StateFlow<MeasurementWithValues?> =
        _currentMeasurementId
            .flatMapLatest { id ->
                if (id == null || id == -1) flowOf(null)
                else measurementFacade.getMeasurementWithValuesById(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setCurrentMeasurementId(measurementId: Int?) { _currentMeasurementId.value = measurementId }

    // --- Chart smoothing config (via SettingsFacade) ---
    val selectedSmoothingAlgorithm: StateFlow<SmoothingAlgorithm> =
        chartSmoothingAlgorithm
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmoothingAlgorithm.NONE)

    val smoothingAlpha: StateFlow<Float> =
        chartSmoothingAlpha
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.5f)

    val smoothingWindowSize: StateFlow<Int> =
        chartSmoothingWindowSize
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5)

    val smoothingMaxGapDays: StateFlow<Int> =
        chartSmoothingMaxGapDays
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)

    // --- UI controls (local UI state) ---
    private val _typesToSmoothAndDisplay = MutableStateFlow<Set<Int>>(emptySet())
    val typesToSmoothAndDisplay: StateFlow<Set<Int>> = _typesToSmoothAndDisplay.asStateFlow()

    // --- Base enriched flow for current user (Table screen, no aggregation at this level) ---
    val enrichedMeasurementsFlow: StateFlow<List<EnrichedMeasurement>> =
        selectedUserId
            .flatMapLatest { uid ->
                if (uid == null) flowOf(emptyList())
                else measurementFacade.enrichedFlowForUser(uid, measurementTypes)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastMeasurementOfSelectedUser: StateFlow<MeasurementWithValues?> =
        enrichedMeasurementsFlow
            .map { list -> list.firstOrNull()?.measurementWithValues }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- CRUD delegates (via MeasurementFacade) ---
    fun getMeasurementById(id: Int): Flow<MeasurementWithValues?> {
        return measurementFacade.getMeasurementWithValuesById(id)
    }

    suspend fun saveMeasurement(measurement: Measurement, values: List<MeasurementValue>, silent: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            val result = measurementFacade.saveMeasurement(measurement, values)
            if (result.isSuccess) {
                if (!silent) showSnackbar(
                    messageResId = if (measurement.id == 0) R.string.success_measurement_saved
                    else R.string.success_measurement_updated
                )
                true
            } else {
                if (!silent) showSnackbar(messageResId = R.string.error_saving_measurement)
                false
            }
        }
    }

    suspend fun deleteMeasurement(measurement: Measurement, silent: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            val result = measurementFacade.deleteMeasurement(measurement)
            if (result.isSuccess) {
                if (!silent) {
                    val formattedDateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(measurement.timestamp))
                    showSnackbar(messageResId = R.string.success_measurement_deleted, formatArgs = listOf(formattedDateTime))
                }
                if (_currentMeasurementId.value == measurement.id) _currentMeasurementId.value = null
                true
            } else {
                if (!silent) showSnackbar(messageResId = R.string.error_deleting_measurement)
                false
            }
        }
    }

    // --- Helpers ---
    fun findClosestMeasurement(selectedTimestamp: Long, items: List<MeasurementWithValues>) =
        measurementFacade.findClosestMeasurement(selectedTimestamp, items)

    // --- Init: restore last selected user or pick first (via UserFacade) ---
    init {
        viewModelScope.launch(Dispatchers.IO) {
            userFacade.restoreOrSelectDefaultUser()
                .onFailure { LogManager.e(TAG, "Failed to restore/select default user: ${it.message}") }
                .also { _isInitialUserLoadComplete.value = true }

            maybeBackfillDerivedValues()
        }
    }

    fun filteredRawMeasurements(
        startTimeMillis: Long?,
        endTimeMillis: Long?
    ): Flow<List<EnrichedMeasurement>> {
        return selectedUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else measurementFacade.pipeline(
                userId = userId,
                measurementTypesFlow = measurementTypes,
                startTimeMillisFlow = flowOf(startTimeMillis),
                endTimeMillisFlow = flowOf(endTimeMillis),
                typesToSmoothFlow = flowOf(emptySet()),
                algorithmFlow = flowOf(SmoothingAlgorithm.NONE),
                alphaFlow = flowOf(0.5f),
                windowFlow = flowOf(5),
                maxGapDaysFlow = flowOf(7),
                aggregationLevelFlow = flowOf(AggregationLevel.NONE)
            )
        }
    }

    // --- Optional: ad-hoc pipeline with provided UI state (Table screen aggregation) ---

    /**
     * Returns an aggregated enriched flow for the Table screen or Statistics screen.
     * Combines time filtering with aggregation.
     *
     * @param startTimeMillis Start of time range (inclusive), or null for no bound.
     * @param endTimeMillis End of time range (inclusive), or null for no bound.
     * @param screenContextName Screen context used to load the persisted aggregation level.
     */
    fun aggregatedEnrichedMeasurements(
        startTimeMillis: Long?,
        endTimeMillis: Long?,
        screenContextName: String
    ): Flow<List<EnrichedMeasurement>> {
        return selectedUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else measurementFacade.pipeline(
                userId = userId,
                measurementTypesFlow = measurementTypes,
                startTimeMillisFlow = flowOf(startTimeMillis),
                endTimeMillisFlow = flowOf(endTimeMillis),
                typesToSmoothFlow = flowOf(emptySet()),
                algorithmFlow = flowOf(SmoothingAlgorithm.NONE),
                alphaFlow = flowOf(0.5f),
                windowFlow = flowOf(5),
                maxGapDaysFlow = flowOf(7),
                aggregationLevelFlow = observeAggregationLevel(screenContextName)
            )
        }
    }

    fun smoothedEnrichedMeasurements(
        startTimeMillis: Long?,
        endTimeMillis: Long?,
        typeIds: Set<Int>,
        screenContextName: String = SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT
    ): Flow<List<EnrichedMeasurement>> {
        return selectedUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else measurementFacade.pipeline(
                userId = userId,
                measurementTypesFlow = measurementTypes,
                startTimeMillisFlow = flowOf(startTimeMillis),
                endTimeMillisFlow = flowOf(endTimeMillis),
                typesToSmoothFlow = flowOf(typeIds),
                algorithmFlow = selectedSmoothingAlgorithm,
                alphaFlow = smoothingAlpha,
                windowFlow = smoothingWindowSize,
                maxGapDaysFlow = chartSmoothingMaxGapDays,
                aggregationLevelFlow = observeAggregationLevel(screenContextName)
            )
        }
    }

    fun evaluateMeasurement(
        type: MeasurementType,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long
    ): MeasurementEvaluationResult? {
        return measurementFacade.evaluate(
            type = type,
            value = value,
            userEvaluationContext = userEvaluationContext,
            measuredAtMillis = measuredAtMillis
        )
    }

    fun getPlausiblePercentRange(typeKey: MeasurementTypeKey): ClosedFloatingPointRange<Float>? {
        return measurementFacade.plausiblePercentRangeFor(typeKey)
    }

    /**
     * Ensures that all derived measurement values are present for all users in the database.
     * Runs only once per app session.
     */
    fun maybeBackfillDerivedValues() {
        if (!didRunDerivedBackfill.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val types = withTimeoutOrNull(10_000) {
                    measurementFacade.getAllMeasurementTypes().first { it.isNotEmpty() }
                }

                if (types.isNullOrEmpty()) {
                    LogManager.w(TAG, "Backfill skip: no measurement types loaded after 10s")
                    return@launch
                }

                val bmiType = types.firstOrNull { it.key == MeasurementTypeKey.BMI } ?: run {
                    LogManager.w(TAG, "Backfill skip: BMI type not found.")
                    return@launch
                }

                val users = withTimeoutOrNull(10_000) { allUsers.first { it.isNotEmpty() } }

                if (users.isNullOrEmpty()) {
                    LogManager.w(TAG, "Backfill skip: no users loaded after 10s")
                    return@launch
                }

                var totalMeasurements = 0
                var ok = 0
                var usersAffected = 0

                users.forEach { user ->
                    val allForUser: List<MeasurementWithValues> =
                        measurementFacade.getMeasurementsForUser(user.id).first()

                    if (allForUser.isEmpty()) {
                        LogManager.d(TAG, "Backfill skip: no measurements for userId=${user.id}.")
                        return@forEach
                    }

                    val hasAnyBmi = allForUser.any { mwv ->
                        mwv.values.any { v ->
                            v.type.id == bmiType.id && (v.value.floatValue ?: 0f) > 0f
                        }
                    }

                    if (hasAnyBmi) {
                        LogManager.d(TAG, "Backfill not needed for userId=${user.id}: at least one BMI>0 exists.")
                        return@forEach
                    }

                    usersAffected++
                    totalMeasurements += allForUser.size
                    LogManager.i(TAG, "No BMI for userId=${user.id} -> recalculating derived values for all ${allForUser.size} measurements…")

                    showSnackbar(messageResId = R.string.derived_backfill_start, duration = SnackbarDuration.Short)

                    allForUser.forEach { mwv ->
                        try {
                            measurementFacade.recalculateDerivedValuesForMeasurement(mwv.measurement.id)
                            ok++
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Recalc failed for measurementId=${mwv.measurement.id}", e)
                        }
                    }
                }

                if (usersAffected == 0) {
                    LogManager.i(TAG, "Derived backfill not needed for any user.")
                } else {
                    LogManager.i(TAG, "Derived backfill done: $ok/$totalMeasurements measurements processed across $usersAffected users.")
                    showSnackbar(messageResId = R.string.derived_backfill_done, duration = SnackbarDuration.Short)
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Derived backfill fatal error", e)
            }
        }
    }
}