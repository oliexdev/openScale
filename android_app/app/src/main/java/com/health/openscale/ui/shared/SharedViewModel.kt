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
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.SmoothingAlgorithm
import com.health.openscale.core.data.TimeRangeFilter
import com.health.openscale.core.data.User
import com.health.openscale.core.data.UserGoals
import com.health.openscale.core.facade.DataManagementFacade
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.SettingsPreferenceKeys
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.model.AggregatedMeasurement
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.model.UserEvaluationContext
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.screen.components.AGGREGATION_LEVEL_SUFFIX
import com.health.openscale.ui.screen.components.CUSTOM_END_DATE_MILLIS_SUFFIX
import com.health.openscale.ui.screen.components.CUSTOM_START_DATE_MILLIS_SUFFIX
import com.health.openscale.ui.screen.components.TIME_RANGE_SUFFIX
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Shared ViewModel coordinating user selection, measurement flows, and UI chrome.
 *
 * ### Screen data access
 * Each screen obtains its data via [screenFlow], which is the single entry point for
 * filtered + aggregated measurement data. The flow is built lazily and cached per
 * screen context, so repeated calls from recompositions are free after the first access.
 *
 * ### Drill-down
 * Screens that need raw (non-aggregated) data for a fixed time window (e.g. a period
 * drill-down) use [drillDownFlow].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SharedViewModel @Inject constructor(
    private val userFacade: UserFacade,
    private val measurementFacade: MeasurementFacade,
    private val dataManagementFacade: DataManagementFacade,
    private val settingsFacade: SettingsFacade,
) : ViewModel(), SettingsFacade by settingsFacade {

    companion object {
        private const val TAG = "SharedViewModel"
    }

    // -------------------------------------------------------------------------
    // UiState
    // -------------------------------------------------------------------------

    sealed interface UiState<out T> {
        data object Loading : UiState<Nothing>
        data class Success<T>(val data: T) : UiState<T>
        data class Error(val message: String? = null) : UiState<Nothing>
    }

    // -------------------------------------------------------------------------
    // Top Bar (UI chrome)
    // -------------------------------------------------------------------------

    private val _topBarTitle = MutableStateFlow<Any>(R.string.app_name)
    val topBarTitle: StateFlow<Any> = _topBarTitle.asStateFlow()

    fun setTopBarTitle(title: String) { _topBarTitle.value = title }
    fun setTopBarTitle(@StringRes titleResId: Int) { _topBarTitle.value = titleResId }

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarAction(action: TopBarAction?) {
        _topBarActions.value = if (action != null) listOf(action) else emptyList()
    }
    fun setTopBarActions(actions: List<TopBarAction>) { _topBarActions.value = actions }

    private val _isInContextualSelectionMode = MutableStateFlow(false)
    val isInContextualSelectionMode: StateFlow<Boolean> =
        _isInContextualSelectionMode.asStateFlow()

    fun setContextualSelectionMode(isActive: Boolean) {
        _isInContextualSelectionMode.value = isActive
    }

    // -------------------------------------------------------------------------
    // Bluetooth / Assisted Weighing
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Snackbar events
    // -------------------------------------------------------------------------

    private val _snackbarEvents =
        MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    fun showSnackbar(
        message: String? = null,
        @StringRes messageResId: Int? = null,
        formatArgs: List<Any> = emptyList(),
        duration: SnackbarDuration = SnackbarDuration.Short,
        @StringRes actionLabelResId: Int? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        if (message == null && messageResId == null) return
        viewModelScope.launch {
            _snackbarEvents.emit(
                SnackbarEvent(
                    message              = message,
                    messageResId         = messageResId ?: 0,
                    messageFormatArgs    = formatArgs,
                    duration             = duration,
                    actionLabelResId     = actionLabelResId,
                    actionLabel          = if (actionLabelResId != null) null else actionLabel,
                    onAction             = onAction,
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Init guard
    // -------------------------------------------------------------------------

    private val didRunDerivedBackfill = AtomicBoolean(false)
    private val _isInitialUserLoadComplete = MutableStateFlow(false)

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    val allUsers: StateFlow<List<User>> =
        userFacade.observeAllUsers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Currently selected user id.
     * [distinctUntilChanged] prevents unnecessary pipeline restarts when Room emits
     * the same value after an unrelated table update.
     * Eagerly shared: screenFlow depends on this; a WhileSubscribed gap would cause
     * a null emission that makes screenFlow return an empty list.
     */
    val selectedUserId: StateFlow<Int?> =
        userFacade.observeSelectedUserId()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedUser: StateFlow<User?> =
        userFacade.observeSelectedUser()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // -------------------------------------------------------------------------
    // User Goals
    // -------------------------------------------------------------------------

    data class UserGoalDialogContext(
        val showDialog: Boolean = false,
        val typeForDialog: MeasurementType? = null,
        val existingGoalForDialog: UserGoals? = null,
    )

    private val _userGoalDialogContext = MutableStateFlow(UserGoalDialogContext())
    val userGoalDialogContext: StateFlow<UserGoalDialogContext> =
        _userGoalDialogContext.asStateFlow()

    fun showUserGoalDialogWithContext(
        type: MeasurementType,
        existingGoal: UserGoals? = null,
    ) {
        _userGoalDialogContext.value = UserGoalDialogContext(
            showDialog            = true,
            typeForDialog         = type,
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
        return userFacade.getAllGoalsForUser(userId).catch { emit(emptyList()) }
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

    // -------------------------------------------------------------------------
    // Aggregation level (persisted per screen context)
    // -------------------------------------------------------------------------

    fun observeAggregationLevel(screenContextName: String): Flow<AggregationLevel> {
        val key = "${screenContextName}${AGGREGATION_LEVEL_SUFFIX}"
        return observeSetting(key, AggregationLevel.NONE.name)
            .map { name ->
                AggregationLevel.entries.find { it.name == name } ?: AggregationLevel.NONE
            }
    }

    suspend fun saveAggregationLevel(screenContextName: String, level: AggregationLevel) {
        val key = "${screenContextName}${AGGREGATION_LEVEL_SUFFIX}"
        saveSetting(key, level.name)
    }

    // -------------------------------------------------------------------------
    // Unified screen data flow — single entry point per screen context
    // -------------------------------------------------------------------------

    /**
     * Cache of built screen flows keyed by screen context name.
     * Flows are constructed lazily on first call and reused for the ViewModel's lifetime,
     * so repeated [screenFlow] calls from recompositions incur no extra work.
     */
    private val screenFlowCache =
        mutableMapOf<String, StateFlow<UiState<List<AggregatedMeasurement>>>>()

    /**
     * Returns a [StateFlow] emitting the current [UiState] for [screenContextName].
     *
     * The flow reacts automatically to:
     * - selected user changes
     * - persisted time range for this context
     * - persisted aggregation level for this context
     * - underlying measurement data changes
     *
     * Screens do **not** pass start/end timestamps — time-range resolution happens here,
     * keeping that logic out of the UI layer.
     *
     * @param screenContextName One of the constants in [SettingsPreferenceKeys].
     * @param useSmoothing      Apply the user's smoothing settings. Should be `true` only
     *                          for [SettingsPreferenceKeys.GRAPH_SCREEN_CONTEXT].
     */
    fun screenFlow(
        screenContextName: String,
        useSmoothing: Boolean = false,
    ): StateFlow<UiState<List<AggregatedMeasurement>>> =
    // Cache key includes useSmoothing — a smoothed and non-smoothed flow for the
        // same screen context are distinct pipelines and must not share the same entry.
        screenFlowCache.getOrPut("$screenContextName#$useSmoothing") {
            buildScreenFlow(screenContextName, useSmoothing)
        }

    private fun buildScreenFlow(
        screenContextName: String,
        useSmoothing: Boolean,
    ): StateFlow<UiState<List<AggregatedMeasurement>>> {
        // Combine the three persisted settings into a single resolved time-range pair
        val timeRangeFlow: Flow<Pair<Long?, Long?>> = combine(
            observeSetting("${screenContextName}${TIME_RANGE_SUFFIX}", TimeRangeFilter.ALL_DAYS.name),
            observeSetting("${screenContextName}${CUSTOM_START_DATE_MILLIS_SUFFIX}", 0L),
            observeSetting("${screenContextName}${CUSTOM_END_DATE_MILLIS_SUFFIX}", 0L),
        ) { rangeName, customStart, customEnd ->
            val range = TimeRangeFilter.entries.find { it.name == rangeName }
                ?: TimeRangeFilter.ALL_DAYS
            resolveTimeRange(range, customStart, customEnd)
        }

        val aggregationFlow: Flow<AggregationLevel> = observeAggregationLevel(screenContextName)

        return _isInitialUserLoadComplete
            .flatMapLatest { ready ->
                if (!ready) return@flatMapLatest flowOf(UiState.Loading)

                combine(
                    selectedUserId,
                    timeRangeFlow,
                    aggregationFlow,
                ) { uid, timeRange, level ->
                    Triple(uid, timeRange, level)
                }.flatMapLatest { (uid, timeRange, level) ->
                    if (uid == null) {
                        return@flatMapLatest flowOf(UiState.Success(emptyList()))
                    }

                    val (startMs, endMs) = timeRange

                    measurementFacade.pipeline(
                        userId               = uid,
                        measurementTypesFlow = measurementTypes,
                        startTimeMillisFlow  = flowOf(startMs),
                        endTimeMillisFlow    = flowOf(endMs),
                        typesToSmoothFlow    = if (useSmoothing) typesToSmoothAndDisplay
                        else flowOf(emptySet()),
                        algorithmFlow        = if (useSmoothing) selectedSmoothingAlgorithm
                        else flowOf(SmoothingAlgorithm.NONE),
                        alphaFlow            = if (useSmoothing) smoothingAlpha
                        else flowOf(0.5f),
                        windowFlow           = if (useSmoothing) smoothingWindowSize
                        else flowOf(5),
                        maxGapDaysFlow       = if (useSmoothing) smoothingMaxGapDays
                        else flowOf(7),
                        aggregationLevelFlow = flowOf(level),
                    )
                        .map<List<AggregatedMeasurement>, UiState<List<AggregatedMeasurement>>> {
                            UiState.Success(it)
                        }
                        .onStart { emit(UiState.Loading) }
                        .catch { emit(UiState.Error(it.message)) }
                }
            }
            .stateIn(
                scope        = viewModelScope,
                // Eagerly: keeps the upstream pipeline alive for the ViewModel's lifetime,
                // regardless of whether a screen is currently subscribed.
                // WhileSubscribed(5_000) was the source of two bugs:
                //   • The time-filter appeared to reset when navigating away and back, because
                //     the upstream was torn down and restarted with initialValue = Loading.
                //   • selectedUserId emitting null after the 5 s gap caused an empty-list flash.
                started      = SharingStarted.Eagerly,
                initialValue = UiState.Loading,
            )
    }

    /**
     * Resolves a [TimeRangeFilter] into concrete start/end epoch-millisecond bounds.
     * Returns `null` for an open bound (no filter applied on that side).
     *
     * Previously this logic was spread across [rememberResolvedTimeRangeState] in
     * multiple Composables. Keeping it here makes it testable without Android instrumentation.
     */
    private fun resolveTimeRange(
        filter: TimeRangeFilter,
        customStartMillis: Long,
        customEndMillis: Long,
    ): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        return when (filter) {
            TimeRangeFilter.ALL_DAYS      -> null to null
            TimeRangeFilter.LAST_7_DAYS   -> (now - 7L   * 86_400_000) to null
            TimeRangeFilter.LAST_30_DAYS  -> (now - 30L  * 86_400_000) to null
            TimeRangeFilter.LAST_365_DAYS -> (now - 365L * 86_400_000) to null
            TimeRangeFilter.CUSTOM        ->
                if (customStartMillis > 0L && customEndMillis > 0L)
                    customStartMillis to customEndMillis
                else
                    null to null
        }
    }

    // -------------------------------------------------------------------------
    // Drill-down flow — raw, non-aggregated, fixed time window
    // -------------------------------------------------------------------------

    private val _lastDrillDownPeriodStart = MutableStateFlow<Long?>(null)
    val lastDrillDownPeriodStart: StateFlow<Long?> = _lastDrillDownPeriodStart.asStateFlow()

    fun setLastDrillDownPeriodStart(periodStart: Long?) {
        _lastDrillDownPeriodStart.value = periodStart
    }

    /**
     * Cache of drill-down StateFlows keyed by "$startMillis-$endMillis".
     *
     * Without caching, every call from a Composable returns a new cold Flow that starts
     * with UiState.Loading — causing a visible flicker on the very first frame even when
     * the data is already available in Room. Caching as a StateFlow with Eagerly sharing
     * means the first emission arrives before the collector is even attached.
     */
    private val drillDownFlowCache =
        mutableMapOf<String, StateFlow<UiState<List<AggregatedMeasurement>>>>()

    /**
     * Returns a raw (non-aggregated) [StateFlow] for a fixed time window.
     * Used by drill-down screens that show the individual measurements within a period.
     *
     * The flow is cached per (startMillis, endMillis) pair so repeated calls from
     * recompositions or from [resolveSelectedMeasurementIds] are free after the first access.
     *
     * Each [AggregatedMeasurement] in the result has [AggregatedMeasurement.aggregatedFromCount] == 1.
     */
    fun drillDownFlow(
        startMillis: Long,
        endMillis: Long,
    ): StateFlow<UiState<List<AggregatedMeasurement>>> =
        drillDownFlowCache.getOrPut("$startMillis-$endMillis") {
            buildDrillDownFlow(startMillis, endMillis)
        }

    private fun buildDrillDownFlow(
        startMillis: Long,
        endMillis: Long,
    ): StateFlow<UiState<List<AggregatedMeasurement>>> =
        selectedUserId.flatMapLatest { uid ->
            if (uid == null) return@flatMapLatest flowOf(UiState.Success(emptyList()))

            measurementFacade.pipeline(
                userId               = uid,
                measurementTypesFlow = measurementTypes,
                startTimeMillisFlow  = flowOf(startMillis),
                endTimeMillisFlow    = flowOf(endMillis),
                typesToSmoothFlow    = flowOf(emptySet()),
                algorithmFlow        = flowOf(SmoothingAlgorithm.NONE),
                alphaFlow            = flowOf(0.5f),
                windowFlow           = flowOf(5),
                maxGapDaysFlow       = flowOf(7),
                aggregationLevelFlow = flowOf(AggregationLevel.NONE),
            )
                .map<List<AggregatedMeasurement>, UiState<List<AggregatedMeasurement>>> {
                    UiState.Success(it)
                }
                .onStart { emit(UiState.Loading) }
                .catch { emit(UiState.Error(it.message)) }
        }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.Eagerly,
                initialValue = UiState.Loading,
            )

    // -------------------------------------------------------------------------
    // Measurement types
    // -------------------------------------------------------------------------

    // Eagerly shared: the pipeline passes this into MeasurementFacade.pipeline() where it is
    // combined with the enriched flow. A WhileSubscribed gap would stall the pipeline.
    val measurementTypes: StateFlow<List<MeasurementType>> =
        measurementFacade.getAllMeasurementTypes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // -------------------------------------------------------------------------
    // Current measurement (detail screen)
    // -------------------------------------------------------------------------

    private val _currentMeasurementId = MutableStateFlow<Int?>(null)

    val currentMeasurementWithValues: StateFlow<MeasurementWithValues?> =
        _currentMeasurementId
            .flatMapLatest { id ->
                if (id == null || id == -1) flowOf(null)
                else measurementFacade.getMeasurementWithValuesById(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setCurrentMeasurementId(measurementId: Int?) {
        _currentMeasurementId.value = measurementId
    }

    // -------------------------------------------------------------------------
    // Chart smoothing config
    // -------------------------------------------------------------------------

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

// -------------------------------------------------------------------------
// Types to smooth — derived from measurementTypes, always up to date
// -------------------------------------------------------------------------

    // All enabled numeric types are eligible for smoothing.
    // Derived reactively from measurementTypes so it never needs a manual setter.
    val typesToSmoothAndDisplay: StateFlow<Set<Int>> =
        measurementTypes
            .map { types ->
                types
                    .filter { it.isEnabled &&
                            (it.inputType == InputFieldType.FLOAT ||
                                    it.inputType == InputFieldType.INT) }
                    .map { it.id }
                    .toSet()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // -------------------------------------------------------------------------
    // Last measurement of selected user (used by add-measurement screen pre-fill)
    // -------------------------------------------------------------------------

    val lastMeasurementOfSelectedUser: StateFlow<MeasurementWithValues?> =
        selectedUserId
            .flatMapLatest { uid ->
                if (uid == null) flowOf(null)
                else measurementFacade.getMeasurementsForUser(uid)
                    .map { list -> list.firstOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    fun getMeasurementById(id: Int): Flow<MeasurementWithValues?> =
        measurementFacade.getMeasurementWithValuesById(id)

    suspend fun saveMeasurement(
        measurement: Measurement,
        values: List<MeasurementValue>,
        silent: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
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

    suspend fun deleteMeasurement(
        measurement: Measurement,
        silent: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val result = measurementFacade.deleteMeasurement(measurement)
        if (result.isSuccess) {
            if (!silent) {
                val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                showSnackbar(
                    messageResId = R.string.success_measurement_deleted,
                    formatArgs   = listOf(fmt.format(Date(measurement.timestamp))),
                )
            }
            if (_currentMeasurementId.value == measurement.id) _currentMeasurementId.value = null
            true
        } else {
            if (!silent) showSnackbar(messageResId = R.string.error_deleting_measurement)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun findClosestMeasurement(
        selectedTimestamp: Long,
        items: List<MeasurementWithValues>,
    ) = measurementFacade.findClosestMeasurement(selectedTimestamp, items)

    fun evaluateMeasurement(
        type: MeasurementType,
        value: Float,
        userEvaluationContext: UserEvaluationContext,
        measuredAtMillis: Long,
    ) = measurementFacade.evaluate(type, value, userEvaluationContext, measuredAtMillis)

    fun getPlausiblePercentRange(typeKey: MeasurementTypeKey) =
        measurementFacade.plausiblePercentRangeFor(typeKey)

    fun performCsvExport(
        userId: Int,
        uri: Uri,
        contentResolver: ContentResolver,
        filterByMeasurementIds: List<Int>? = null,
    ) {
        viewModelScope.launch {
            try {
                val rows = dataManagementFacade
                    .exportUserToCsv(userId, uri, contentResolver, filterByMeasurementIds)
                    .getOrThrow()
                if (rows > 0) showSnackbar(messageResId = R.string.export_successful)
                else showSnackbar(messageResId = R.string.export_error_no_exportable_values)
            } catch (e: Exception) {
                LogManager.e(TAG, "CSV export error", e)
                showSnackbar(
                    messageResId = R.string.export_error_generic,
                    formatArgs   = listOf(e.localizedMessage ?: "Unknown error"),
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Init — restore last selected user, then optionally backfill derived values
    // -------------------------------------------------------------------------

    init {
        viewModelScope.launch(Dispatchers.IO) {
            userFacade.restoreOrSelectDefaultUser()
                .onFailure { LogManager.e(TAG, "Failed to restore/select default user: ${it.message}") }
                .also { _isInitialUserLoadComplete.value = true }

            maybeBackfillDerivedValues()
        }
    }

    /**
     * Ensures derived measurement values (e.g. BMI) are present for all users.
     * Runs at most once per app session.
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

                val users = withTimeoutOrNull(10_000) {
                    allUsers.first { it.isNotEmpty() }
                }
                if (users.isNullOrEmpty()) {
                    LogManager.w(TAG, "Backfill skip: no users loaded after 10s")
                    return@launch
                }

                var totalMeasurements = 0
                var ok = 0
                var usersAffected = 0

                users.forEach { user ->
                    val allForUser = measurementFacade.getMeasurementsForUser(user.id).first()
                    if (allForUser.isEmpty()) return@forEach

                    val hasAnyBmi = allForUser.any { mwv ->
                        mwv.values.any { v ->
                            v.type.id == bmiType.id && (v.value.floatValue ?: 0f) > 0f
                        }
                    }
                    if (hasAnyBmi) return@forEach

                    usersAffected++
                    totalMeasurements += allForUser.size
                    LogManager.i(TAG, "No BMI for userId=${user.id} — recalculating ${allForUser.size} measurements…")
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
                    LogManager.i(TAG, "Derived backfill done: $ok/$totalMeasurements processed across $usersAffected users.")
                    showSnackbar(messageResId = R.string.derived_backfill_done, duration = SnackbarDuration.Short)
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Derived backfill fatal error", e)
            }
        }
    }
}