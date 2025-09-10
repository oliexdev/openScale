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
package com.health.openscale.ui.screen.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.TimePickerState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.R
import com.health.openscale.core.data.BackupInterval
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.User
import com.health.openscale.core.facade.DataManagementFacade
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.usecase.ImportReport
import com.health.openscale.core.usecase.ReminderUseCase
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SnackbarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI-centric ViewModel delegating to Facades only.
 *
 * - Users & app language: [UserFacade]
 * - Auto-backup, DB backup/restore, CSV import/export: [DataManagementFacade]
 * - Measurement types: [MeasurementFacade]
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userFacade: UserFacade,
    private val dataManagementFacade: DataManagementFacade,
    private val measurementFacade: MeasurementFacade,
    private val reminderUseCase: ReminderUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"

        const val ACTION_ID_EXPORT_USER_DATA = "export_user_data"
        const val ACTION_ID_IMPORT_USER_DATA = "import_user_data"
        const val ACTION_ID_BACKUP_DB       = "backup_database"
        const val ACTION_ID_RESTORE_DB      = "restore_database"
    }

    // --- Snackbar ---
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 1)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private suspend fun showSnackbar(
        resId: Int,
        args: List<Any> = emptyList(),
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        _snackbarEvents.emit(
            SnackbarEvent(messageResId = resId, messageFormatArgs = args, duration = duration)
        )
    }

    // --- SAF events to the UI ---
    sealed class SafEvent {
        data class RequestCreateFile(val suggestedName: String, val actionId: String, val userId: Int) : SafEvent()
        data class RequestOpenFile(val actionId: String, val userId: Int) : SafEvent()
    }
    private val _safEvent = MutableSharedFlow<SafEvent>()
    val safEvent = _safEvent.asSharedFlow()

    // --- Loading flags ---
    private val _isLoadingExport = MutableStateFlow(false)
    val isLoadingExport: StateFlow<Boolean> = _isLoadingExport.asStateFlow()

    private val _isLoadingImport = MutableStateFlow(false)
    val isLoadingImport: StateFlow<Boolean> = _isLoadingImport.asStateFlow()

    private val _isLoadingBackup = MutableStateFlow(false)
    val isLoadingBackup: StateFlow<Boolean> = _isLoadingBackup.asStateFlow()

    private val _isLoadingRestore = MutableStateFlow(false)
    val isLoadingRestore: StateFlow<Boolean> = _isLoadingRestore.asStateFlow()

    // optional: Danger zone flags (falls später genutzt)
    private val _isLoadingDeletion = MutableStateFlow(false)
    val isLoadingDeletion: StateFlow<Boolean> = _isLoadingDeletion.asStateFlow()

    private val _isLoadingEntireDatabaseDeletion = MutableStateFlow(false)
    val isLoadingEntireDatabaseDeletion: StateFlow<Boolean> = _isLoadingEntireDatabaseDeletion.asStateFlow()

    // --- Users (UserFacade) ---
    val allUsers: StateFlow<List<User>> =
        userFacade.observeAllUsers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun addUser(user: User): Long {
        return try {
            val id = userFacade.addUser(user).getOrThrow()
            id
        } catch (e: Exception) {
            LogManager.e(TAG, "addUser failed", e)
            showSnackbar(R.string.user_updated_error, listOf(user.name))
            -1L
        }
    }

    suspend fun updateUser(user: User) {
        try {
            userFacade.updateUser(user).getOrThrow()
            showSnackbar(R.string.user_updated_successfully, listOf(user.name))
        } catch (e: Exception) {
            LogManager.e(TAG, "updateUser failed", e)
            showSnackbar(R.string.user_updated_error, listOf(user.name))
        }
    }

    suspend fun deleteUser(user: User, reseatSelection: Boolean = true) {
        try {
            userFacade.deleteUser(user, reseatSelection).getOrThrow()
            showSnackbar(R.string.user_deleted_successfully, listOf(user.name))
        } catch (e: Exception) {
            LogManager.e(TAG, "deleteUser failed", e)
            showSnackbar(R.string.user_deleted_error, listOf(user.name))
        }
    }

    // --- Auto-backup (DataManagementFacade) ---
    val autoBackupEnabled =
        dataManagementFacade.isAutoBackupEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoBackupLocationUri =
        dataManagementFacade.autoBackupTargetUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val autoBackupInterval =
        dataManagementFacade.autoBackupInterval.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupInterval.WEEKLY)

    val autoBackupCreateNewFile =
        dataManagementFacade.isAutoBackupNewFileMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val autoBackupLastSuccessfulTimestamp =
        dataManagementFacade.autoBackupLastSuccessTimestamp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { dataManagementFacade.setAutoBackupEnabled(enabled) }
    }

    fun setAutoBackupLocationUri(uri: String?) {
        viewModelScope.launch { dataManagementFacade.setAutoBackupTargetUri(uri) }
    }

    fun setAutoBackupInterval(interval: BackupInterval) {
        viewModelScope.launch { dataManagementFacade.setAutoBackupInterval(interval) }
    }

    fun setAutoBackupCreateNewFile(createNew: Boolean) {
        viewModelScope.launch { dataManagementFacade.setAutoBackupNewFileMode(createNew) }
    }

    // --- CSV import/export ---
    fun startExportProcess() {
        viewModelScope.launch {
            val users = allUsers.value
            when (users.size) {
                0 -> {
                    showSnackbar(R.string.export_no_users_available)
                }
                1 -> {
                    val user = users.first()
                    val safeName = user.name.replace("\\s+".toRegex(), "_").take(20)
                    val suggested = "openScale_export_${safeName}.csv"
                    _safEvent.emit(
                        SafEvent.RequestCreateFile(
                            suggested,
                            ACTION_ID_EXPORT_USER_DATA,
                            user.id
                        )
                    )
                }
                else -> {
                    _showUserSelectionDialogForExport.value = true
                }
            }
        }
    }

    fun performCsvExport(userId: Int, uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingExport.value = true
            try {
                val rows = dataManagementFacade.exportUserToCsv(userId, uri, contentResolver).getOrThrow()
                if (rows > 0) showSnackbar(R.string.export_successful)
                else showSnackbar(R.string.export_error_no_exportable_values)
            } catch (e: Exception) {
                LogManager.e(TAG, "CSV export error", e)
                showSnackbar(R.string.export_error_generic, listOf(e.localizedMessage ?: "Unknown error"))
            } finally {
                _isLoadingExport.value = false
            }
        }
    }

    fun startImportProcess() {
        viewModelScope.launch {
            val users = allUsers.value
            when (users.size) {
                0 -> {
                    showSnackbar(R.string.import_no_users_available)
                }
                1 -> {
                    val user = users.first()
                    _safEvent.emit(
                        SafEvent.RequestOpenFile(
                            ACTION_ID_IMPORT_USER_DATA,
                            user.id
                        )
                    )
                }
                else -> {
                    _showUserSelectionDialogForImport.value = true
                }
            }
        }
    }


    fun performCsvImport(userId: Int, uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingImport.value = true
            try {
                val report: ImportReport = dataManagementFacade.importUserFromCsv(userId, uri, contentResolver).getOrThrow()
                val details = buildString {
                    val parts = mutableListOf<String>()

                    if (report.ignoredMeasurementsCount > 0) {
                        parts.add(context.getString(R.string.import_summary_ignored_duplicated_timestamp, report.ignoredMeasurementsCount).removeSuffix("."))
                    }
                    if (report.linesSkippedMissingDate > 0) {
                        parts.add(context.getString(R.string.import_summary_skipped_missing_dates, report.linesSkippedMissingDate).removeSuffix("."))
                    }
                    if (report.linesSkippedDateParseError > 0) {
                        parts.add(context.getString(R.string.import_summary_skipped_date_parse_errors, report.linesSkippedDateParseError).removeSuffix("."))
                    }
                    if (report.valuesSkippedParseError > 0) {
                        parts.add(context.getString(R.string.import_summary_values_skipped_parse_errors, report.valuesSkippedParseError).removeSuffix("."))
                    }

                    if (parts.isNotEmpty()) {
                        append(" (")
                        append(parts.joinToString(", "))
                        append(")")
                    }
                }
                if (details.isNotEmpty())
                    showSnackbar(R.string.import_successful_records_with_details, listOf(report.importedMeasurementsCount, details))
                else
                    showSnackbar(R.string.import_successful_records, listOf(report.importedMeasurementsCount))
            } catch (e: Exception) {
                LogManager.e(TAG, "CSV import error", e)
                showSnackbar(R.string.import_error_generic, listOf(e.localizedMessage ?: "Unknown error"))
            } finally {
                _isLoadingImport.value = false
            }
        }
    }

    // --- DB backup/restore (via SAF) ---
    fun startDatabaseBackup() {
        viewModelScope.launch {
            val suggestedName = "openscale_backup_${System.currentTimeMillis()}.zip"
            _safEvent.emit(SafEvent.RequestCreateFile(suggestedName, ACTION_ID_BACKUP_DB, userId = 0))
        }
    }

    fun performDatabaseBackup(backupUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingBackup.value = true
            try {
                dataManagementFacade.backupDatabase(backupUri, contentResolver).getOrThrow()
                showSnackbar(R.string.backup_successful)
            } catch (e: Exception) {
                LogManager.e(TAG, "Backup error", e)
                showSnackbar(R.string.backup_error_generic, listOf(e.localizedMessage ?: "Unknown error"))
            } finally {
                _isLoadingBackup.value = false
            }
        }
    }

    fun startDatabaseRestore() {
        viewModelScope.launch {
            _safEvent.emit(SafEvent.RequestOpenFile(ACTION_ID_RESTORE_DB, userId = 0))
        }
    }

    fun performDatabaseRestore(restoreUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingRestore.value = true
            try {
                dataManagementFacade.restoreDatabase(restoreUri, contentResolver).getOrThrow()
                showSnackbar(R.string.restore_successful)
            } catch (e: Exception) {
                LogManager.e(TAG, "Restore error", e)
                showSnackbar(R.string.restore_error_generic, listOf(e.localizedMessage ?: "Unknown error"))
            } finally {
                _isLoadingRestore.value = false
            }
        }
    }

    suspend fun requestReminderReschedule() {
        reminderUseCase.rescheduleNext()
    }

    // --- Measurement types (MeasurementFacade) ---
    fun addMeasurementType(type: MeasurementType) = viewModelScope.launch {
        try {
            measurementFacade.addMeasurementType(type).getOrThrow()
            showSnackbar(R.string.measurement_type_added_successfully, listOf(type.name.orEmpty()))
        } catch (e: Exception) {
            LogManager.e(TAG, "addMeasurementType failed", e)
            showSnackbar(R.string.measurement_type_added_error, listOf(type.name.orEmpty()))
        }
    }

    fun updateMeasurementType(type: MeasurementType, showSnackbar: Boolean = true) = viewModelScope.launch {
        try {
            measurementFacade.updateMeasurementType(type).getOrThrow()
            if (showSnackbar) {
                showSnackbar(
                    R.string.measurement_type_updated_successfully,
                    listOf(type.name.orEmpty())
                )
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "updateMeasurementType failed", e)
            showSnackbar(R.string.measurement_type_updated_error, listOf(type.name.orEmpty()))
        }
    }

    fun deleteMeasurementType(type: MeasurementType) = viewModelScope.launch {
        try {
            measurementFacade.deleteMeasurementType(type).getOrThrow()
            showSnackbar(R.string.measurement_type_deleted_successfully, listOf(type.name.orEmpty()))
        } catch (e: Exception) {
            LogManager.e(TAG, "deleteMeasurementType failed", e)
            showSnackbar(R.string.measurement_type_deleted_error, listOf(type.name.orEmpty()))
        }
    }

    fun updateMeasurementTypeWithConversion(
        originalType: MeasurementType,
        updatedType: MeasurementType
    ) = viewModelScope.launch {
        try {
            val report = measurementFacade
                .updateTypeWithUnitConversion(originalType, updatedType)
                .getOrThrow()

            if (report.attempted) {
                if (report.updatedCount > 0) {
                    showSnackbar(
                        R.string.measurement_type_updated_and_values_converted_successfully,
                        listOf(updatedType.name.orEmpty(), report.updatedCount)
                    )
                } else {
                    showSnackbar(
                        R.string.measurement_type_updated_unit_changed_no_values_converted,
                        listOf(updatedType.name.orEmpty())
                    )
                }
            } else {
                showSnackbar(
                    R.string.measurement_type_updated_successfully,
                    listOf(updatedType.name.orEmpty())
                )
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "updateMeasurementTypeWithConversion failed", e)
            showSnackbar(
                R.string.measurement_type_update_error_conversion_failed,
                listOf(updatedType.name.orEmpty())
            )
        }
    }

    private val _showUserSelectionDialogForDelete = MutableStateFlow(false)
    val showUserSelectionDialogForDelete: StateFlow<Boolean> = _showUserSelectionDialogForDelete.asStateFlow()

    private val _userPendingDeletion = MutableStateFlow<User?>(null)
    val userPendingDeletion: StateFlow<User?> = _userPendingDeletion.asStateFlow()

    private val _showDeleteConfirmationDialog = MutableStateFlow(false)
    val showDeleteConfirmationDialog: StateFlow<Boolean> = _showDeleteConfirmationDialog.asStateFlow()

    private val _showDeleteEntireDatabaseConfirmationDialog = MutableStateFlow(false)
    val showDeleteEntireDatabaseConfirmationDialog: StateFlow<Boolean> =
        _showDeleteEntireDatabaseConfirmationDialog.asStateFlow()

    // Flags
    private val _showUserSelectionDialogForExport = MutableStateFlow(false)
    val showUserSelectionDialogForExport = _showUserSelectionDialogForExport.asStateFlow()

    private val _showUserSelectionDialogForImport = MutableStateFlow(false)
    val showUserSelectionDialogForImport = _showUserSelectionDialogForImport.asStateFlow()

    // Dialog abbrechen
    fun cancelUserSelectionForExport() { _showUserSelectionDialogForExport.value = false }
    fun cancelUserSelectionForImport() { _showUserSelectionDialogForImport.value = false }

    // Auswahl übernehmen
    fun proceedWithExportForUser(userId: Int) = viewModelScope.launch {
        _showUserSelectionDialogForExport.value = false
        val user = allUsers.value.firstOrNull { it.id == userId } ?: run {
            showSnackbar(R.string.export_no_users_available); return@launch
        }
        val safeName = user.name.replace("\\s+".toRegex(), "_").take(20)
        val suggested = "openScale_export_${safeName}.csv"
        _safEvent.emit(SafEvent.RequestCreateFile(suggested, ACTION_ID_EXPORT_USER_DATA, user.id))
    }

    fun proceedWithImportForUser(userId: Int) = viewModelScope.launch {
        _showUserSelectionDialogForImport.value = false
        val user = allUsers.value.firstOrNull { it.id == userId } ?: run {
            showSnackbar(R.string.import_no_users_available); return@launch
        }
        _safEvent.emit(SafEvent.RequestOpenFile(ACTION_ID_IMPORT_USER_DATA, user.id))
    }

    // --- User data delete flow ---
    fun initiateDeleteAllUserDataProcess() {
        val users = allUsers.value
        when (users.size) {
            0 -> {
                viewModelScope.launch { showSnackbar(R.string.delete_data_no_users_available) }
            }
            1 -> {
                val user = users.first()
                _userPendingDeletion.value = user
                _showDeleteConfirmationDialog.value = true
            }
            else -> {
                _showUserSelectionDialogForDelete.value = true
            }
        }
    }

    fun cancelUserSelectionForDelete() {
        _showUserSelectionDialogForDelete.value = false
    }

    fun proceedWithDeleteForUser(userId: Int) {
        val user = allUsers.value.firstOrNull { it.id == userId }
        _userPendingDeletion.value = user
        _showUserSelectionDialogForDelete.value = false
        _showDeleteConfirmationDialog.value = true
    }

    fun cancelDeleteConfirmation() {
        _showDeleteConfirmationDialog.value = false
        _userPendingDeletion.value = null
    }

    fun confirmActualDeletion() = viewModelScope.launch {
        val user = _userPendingDeletion.value ?: run {
            showSnackbar(R.string.delete_data_error_no_user_selected)
            return@launch
        }

        _isLoadingDeletion.value = true
        try {
            val deletedCount = userFacade.deleteAllMeasurementsForUser(user.id).getOrThrow()
            if (deletedCount > 0) {
                showSnackbar(R.string.delete_data_user_successful, listOf(user.name))
            } else {
                showSnackbar(R.string.delete_data_user_no_data_found, listOf(user.name))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Delete all data for user failed", e)
            showSnackbar(R.string.delete_data_user_error, listOf(user.name))
        } finally {
            _isLoadingDeletion.value = false
            _showDeleteConfirmationDialog.value = false
            _userPendingDeletion.value = null
        }
    }

    // --- Whole database delete flow ---
    fun initiateDeleteEntireDatabaseProcess() {
        _showDeleteEntireDatabaseConfirmationDialog.value = true
    }

    fun cancelDeleteEntireDatabaseConfirmation() {
        _showDeleteEntireDatabaseConfirmationDialog.value = false
    }

    fun confirmDeleteEntireDatabase() = viewModelScope.launch {
        _isLoadingEntireDatabaseDeletion.value = true
        try {
            dataManagementFacade.wipeDatabase().getOrThrow()

            showSnackbar(R.string.delete_db_successful, duration = SnackbarDuration.Long)
        } catch (e: Exception) {
            LogManager.e(TAG, "Wipe database failed", e)
            showSnackbar(R.string.delete_db_error, duration = SnackbarDuration.Long)
        } finally {
            _isLoadingEntireDatabaseDeletion.value = false
            _showDeleteEntireDatabaseConfirmationDialog.value = false
        }
    }
}
