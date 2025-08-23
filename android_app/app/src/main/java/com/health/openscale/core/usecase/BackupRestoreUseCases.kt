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
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for creating/restoring database backups via SAF Uris.
 *
 * - Backup: writes a ZIP with main DB + optional -shm/-wal if present.
 * - Restore: accepts either that ZIP format or a legacy plain .db file.
 * - The caller (VM) decides how to message the UI based on Result.
 */
@Singleton
class BackupRestoreUseCases @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: DatabaseRepository,
    private val settings: SettingsFacade
) {

    private val TAG = "BackupRestoreUseCase"

    suspend fun backupDatabase(
        backupUri: Uri,
        contentResolver: ContentResolver
    ) = runCatching {
        val dbName = repository.getDatabaseName()
        val dbFile = appContext.getDatabasePath(dbName)
        val dbDir = dbFile.parentFile ?: error("Database directory not found")

        // Main db is required, shm/wal optional
        val candidates = listOf(
            dbFile,
            File(dbDir, "$dbName-shm"),
            File(dbDir, "$dbName-wal")
        )

        require(dbFile.exists()) { "Main database file not found: ${dbFile.absolutePath}" }

        val added = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            val os = contentResolver.openOutputStream(backupUri)
                ?: throw IOException("Cannot open OutputStream for Uri: $backupUri")
            ZipOutputStream(os).use { zip ->
                candidates.forEach { f ->
                    if (f.exists() && f.isFile) {
                        try {
                            FileInputStream(f).use { `in` ->
                                zip.putNextEntry(ZipEntry(f.name))
                                `in`.copyTo(zip)
                                zip.closeEntry()
                                added += f.name
                            }
                        } catch (e: Exception) {
                            // Non-fatal for optional files
                            LogManager.w(TAG, "Skipping ${f.name}: ${e.message}", e)
                        }
                    }
                }
            }
        }

        LogManager.i(TAG, "Backup completed. Files: $added")
    }

    suspend fun restoreDatabase(
        restoreUri: Uri,
        contentResolver: ContentResolver
    ) = runCatching {
        val dbName = repository.getDatabaseName()
        val dbFile = appContext.getDatabasePath(dbName)
        val dbDir = dbFile.parentFile ?: error("Database directory not found")

        // Close DB before touching the files
        LogManager.d(TAG, "Closing database for restore…")
        repository.closeDatabase()

        // Helper
        fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                LogManager.w(TAG, "Could not delete ${file.absolutePath} before restore.")
            }
        }

        val restored = mutableListOf<String>()
        var format = "zip"

        withContext(Dispatchers.IO) {
            // Peek first 4 bytes to detect ZIP
            val isZip = contentResolver.openInputStream(restoreUri)?.use { ins ->
                val header = ByteArray(4)
                val read = ins.read(header)
                read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            } ?: false

            val shm = File(dbDir, "$dbName-shm")
            val wal = File(dbDir, "$dbName-wal")

            if (isZip) {
                contentResolver.openInputStream(restoreUri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        // clean slate
                        deleteIfExists(dbFile)
                        deleteIfExists(shm)
                        deleteIfExists(wal)

                        var hasMain = false
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val out = File(dbDir, entry.name)

                            // Path traversal guard
                            if (!out.canonicalPath.startsWith(dbDir.canonicalPath)) {
                                LogManager.e(TAG, "Skipping ${entry.name} (path traversal)")
                                entry = zis.nextEntry
                                continue
                            }

                            deleteIfExists(out)
                            FileOutputStream(out).use { zis.copyTo(it) }
                            restored += entry.name
                            if (entry.name == dbName) hasMain = true
                            entry = zis.nextEntry
                        }
                        require(hasMain) { "Main DB file '$dbName' missing in ZIP" }
                    }
                } ?: throw IOException("Cannot open InputStream for Uri: $restoreUri")
            } else {
                // Legacy single-file: treat input as raw DB file
                format = "legacy"
                contentResolver.openInputStream(restoreUri)?.use { input ->
                    deleteIfExists(dbFile)
                    deleteIfExists(shm)
                    deleteIfExists(wal)

                    val tmp = File(dbDir, "$dbName.tmp-restore")
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    if (!tmp.renameTo(dbFile)) {
                        // If rename fails (FS boundaries), leave the copied file as final
                        tmp.copyTo(dbFile, overwrite = true)
                        tmp.delete()
                    }
                    restored += dbName
                } ?: throw IOException("Cannot open InputStream for Uri: $restoreUri")
            }
        }

        LogManager.i(TAG, "Restore completed. Format=$format, Files=$restored")
    }

    /** Close and delete the entire Room database (plus -shm/-wal). */
    suspend fun wipeDatabase() = runCatching {
        val dbName = repository.getDatabaseName()
        LogManager.d(TAG, "Closing database for wipe…")
        repository.closeDatabase()

        val dbFile = appContext.getDatabasePath(dbName)
        val dbDir = dbFile.parentFile

        var dbDeleted = false
        var shmDeleted = true
        var walDeleted = true

        if (dbDir != null && dbDir.exists()) {
            dbDeleted = appContext.deleteDatabase(dbName)

            val shm = File(dbDir, "$dbName-shm")
            if (shm.exists()) shmDeleted = shm.delete()

            val wal = File(dbDir, "$dbName-wal")
            if (wal.exists()) walDeleted = wal.delete()

            settings.setFirstAppStartCompleted(true)
            settings.setCurrentUserId(null)
        }

        LogManager.i(TAG, "Wipe complete. dbDeleted=$dbDeleted shmDeleted=$shmDeleted walDeleted=$walDeleted name=$dbName")
    }
}
