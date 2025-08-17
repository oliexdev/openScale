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
package com.health.openscale.core.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.database.UserSettingsRepository
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val userSettingsRepository: UserSettingsRepository,
    private val databaseRepository: DatabaseRepository
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "com.health.openscale.AUTO_DATABASE_BACKUP"
    }

    override suspend fun doWork(): Result {
        LogManager.i(TAG, "Automatic backup worker started.")

        val isEnabled = userSettingsRepository.autoBackupEnabledGlobally.first()
        val locationUriString = userSettingsRepository.autoBackupLocationUri.first()
        val createNewFile = userSettingsRepository.autoBackupCreateNewFile.first()

        if (!isEnabled || locationUriString == null) {
            LogManager.i(TAG, "Auto backup is disabled or location not set. Worker finishing.")
            return Result.success()
        }

        val backupDirUri = Uri.parse(locationUriString)
        val parentDocumentFile = DocumentFile.fromTreeUri(applicationContext, backupDirUri)

        if (parentDocumentFile == null || !parentDocumentFile.canWrite()) {
            LogManager.e(TAG, "Cannot write to backup location: $locationUriString. Permissions might be lost or URI invalid.")
            userSettingsRepository.setAutoBackupLastSuccessfulTimestamp(0L)
            return Result.failure()
        }

        try {
            val dbName = databaseRepository.getDatabaseName()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseBackupFileName = "${dbName}_auto_backup"
            val finalFileName = if (createNewFile) "${baseBackupFileName}_$timeStamp.zip" else "$baseBackupFileName.zip"

            var backupDocumentFile = parentDocumentFile.findFile(finalFileName)
            if (backupDocumentFile != null && backupDocumentFile.exists()) {
                if (createNewFile) {
                    LogManager.w(TAG, "File $finalFileName already exists, but createNewFile is true. Creating with new timestamp again.")
                } else {
                    if (!backupDocumentFile.delete()) {
                        LogManager.e(TAG, "Could not delete existing file $finalFileName for overwrite.")
                        return Result.failure()
                    }
                    backupDocumentFile = null
                }
            }

            if (backupDocumentFile == null) {
                backupDocumentFile = parentDocumentFile.createFile("application/zip", finalFileName)
            }

            if (backupDocumentFile == null) {
                LogManager.e(TAG, "Could not create backup file: $finalFileName in $locationUriString")
                return Result.failure()
            }

            applicationContext.contentResolver.openOutputStream(backupDocumentFile.uri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOutputStream ->
                    val dbFile = applicationContext.getDatabasePath(dbName)
                    val dbDir = dbFile.parentFile ?: return Result.failure()

                    val filesToBackup = listOfNotNull(
                        dbFile,
                        File(dbDir, "$dbName-shm"),
                        File(dbDir, "$dbName-wal")
                    )

                    filesToBackup.forEach { file ->
                        if (file.exists() && file.isFile) {
                            FileInputStream(file).use { fileInputStream ->
                                val entry = ZipEntry(file.name)
                                zipOutputStream.putNextEntry(entry)
                                fileInputStream.copyTo(zipOutputStream)
                                zipOutputStream.closeEntry()
                            }
                        }
                    }
                }
            } ?: return Result.failure()

            LogManager.i(TAG, "Automatic backup successful to: ${backupDocumentFile.uri}")
            userSettingsRepository.setAutoBackupLastSuccessfulTimestamp(System.currentTimeMillis())
            return Result.success()

        } catch (e: Exception) {
            LogManager.e(TAG, "Error during automatic backup", e)
            userSettingsRepository.setAutoBackupLastSuccessfulTimestamp(0L)
            return Result.failure()
        }
    }
}
