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
package com.health.openscale.core.facade

import android.content.ContentResolver
import android.net.Uri
import com.health.openscale.core.data.BackupInterval
import com.health.openscale.core.usecase.AutoBackupUseCases
import com.health.openscale.core.usecase.BackupRestoreUseCases
import com.health.openscale.core.usecase.ImportExportUseCases
import com.health.openscale.core.usecase.ImportReport
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level facade for managing app data end‑to‑end:
 * - Auto-backup preferences & scheduling (delegates to [AutoBackupUseCases])
 * - Manual DB backup / restore / wipe (delegates to [BackupRestoreUseCases])
 * - CSV import / export for user data (delegates to [ImportExportUseCases])
 *
 * ViewModels should depend on this facade instead of individual use cases.
 */
@Singleton
class DataManagementFacade @Inject constructor(
    private val autoBackup: AutoBackupUseCases,
    private val backupRestore: BackupRestoreUseCases,
    private val importExport: ImportExportUseCases
) {
    // ---------------------------------------------------------------------
    // Auto-backup (settings + scheduling)
    // ---------------------------------------------------------------------

    // Observables
    val isAutoBackupEnabled: Flow<Boolean> get() = autoBackup.enabled
    val autoBackupTargetUri: Flow<String?> get() = autoBackup.locationUri
    val autoBackupInterval: Flow<BackupInterval> get() = autoBackup.interval
    val isAutoBackupNewFileMode: Flow<Boolean> get() = autoBackup.createNewFile
    val autoBackupLastSuccessTimestamp: Flow<Long> get() = autoBackup.lastSuccessfulTimestamp

    // Mutations
    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        autoBackup.setEnabled(enabled)

    suspend fun setAutoBackupTargetUri(uri: String?) =
        autoBackup.setLocationUri(uri)

    suspend fun setAutoBackupInterval(interval: BackupInterval) =
        autoBackup.setInterval(interval)

    suspend fun setAutoBackupNewFileMode(createNew: Boolean) =
        autoBackup.setCreateNewFile(createNew)

    // ---------------------------------------------------------------------
    // Manual DB backup / restore / wipe (SAF)
    // ---------------------------------------------------------------------

    suspend fun backupDatabase(
        uri: Uri,
        resolver: ContentResolver
    ) = backupRestore.backupDatabase(uri, resolver)

    suspend fun restoreDatabase(
        uri: Uri,
        resolver: ContentResolver
    ) = backupRestore.restoreDatabase(uri, resolver)

    suspend fun wipeDatabase() =
        backupRestore.wipeDatabase()

    // ---------------------------------------------------------------------
    // CSV Import / Export (user-scoped)
    // ---------------------------------------------------------------------

    suspend fun exportUserToCsv(
        userId: Int,
        uri: Uri,
        resolver: ContentResolver
    ): Result<Int> = importExport.exportUserToCsv(userId, uri, resolver)

    suspend fun importUserFromCsv(
        userId: Int,
        uri: Uri,
        resolver: ContentResolver
    ): Result<ImportReport> = importExport.importUserFromCsv(userId, uri, resolver)
}