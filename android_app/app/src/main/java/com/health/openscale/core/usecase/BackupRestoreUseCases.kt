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
import android.database.sqlite.SQLiteDatabase
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
                            FileInputStream(f).use { input ->
                                zip.putNextEntry(ZipEntry(f.name))
                                input.copyTo(zip)
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
        val restored = mutableListOf<String>()
        // Restore into a temporary workspace first so the live database is untouched
        // until the incoming payload has been staged and validated.
        val restoreSessionDir = File(dbDir, "$dbName.restore-${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val stagingDir = File(restoreSessionDir, "staging").apply { mkdirs() }
        val rollbackDir = File(restoreSessionDir, "rollback").apply { mkdirs() }

        try {
            val format = withContext(Dispatchers.IO) {
                stageRestorePayload(
                    restoreUri = restoreUri,
                    contentResolver = contentResolver,
                    stagingDir = stagingDir,
                    dbName = dbName,
                    restored = restored
                )
            }

            LogManager.d(TAG, "Closing database for restore...")
            repository.closeDatabase()

            withContext(Dispatchers.IO) {
                swapStagedDatabaseFiles(
                    dbDir = dbDir,
                    stagingDir = stagingDir,
                    rollbackDir = rollbackDir,
                    dbName = dbName
                )
            }

            LogManager.i(TAG, "Restore completed. Format=$format, Files=$restored")
        } finally {
            if (restoreSessionDir.exists() && !restoreSessionDir.deleteRecursively()) {
                LogManager.w(
                    TAG,
                    "Could not fully delete temporary restore session dir: ${restoreSessionDir.absolutePath}"
                )
            }
        }
    }

    /** Close and delete the entire Room database (plus -shm/-wal). */
    suspend fun wipeDatabase() = runCatching {
        val dbName = repository.getDatabaseName()
        LogManager.d(TAG, "Closing database for wipe...")
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

        LogManager.i(
            TAG,
            "Wipe complete. dbDeleted=$dbDeleted shmDeleted=$shmDeleted walDeleted=$walDeleted name=$dbName"
        )
    }

    private fun stageRestorePayload(
        restoreUri: Uri,
        contentResolver: ContentResolver,
        stagingDir: File,
        dbName: String,
        restored: MutableList<String>
    ): String {
        val mainDb = File(stagingDir, dbName)
        val allowedNames = setOf(dbName, "$dbName-shm", "$dbName-wal")
        // Support both the current ZIP backup format and older single-file database exports.
        val isZip = contentResolver.openInputStream(restoreUri)?.use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            read == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        } ?: false

        if (isZip) {
            contentResolver.openInputStream(restoreUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        when {
                            entry.isDirectory -> Unit
                            // ZIP restores only accept the database files at the archive root.
                            entryName.contains('/') || entryName.contains('\\') -> {
                                LogManager.w(TAG, "Skipping nested ZIP entry '$entryName' during restore.")
                            }
                            entryName !in allowedNames -> {
                                LogManager.w(TAG, "Skipping unexpected ZIP entry '$entryName' during restore.")
                            }
                            else -> {
                                val out = File(stagingDir, entryName)
                                FileOutputStream(out).use { zis.copyTo(it) }
                                restored += entryName
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IOException("Cannot open InputStream for Uri: $restoreUri")
        } else {
            contentResolver.openInputStream(restoreUri)?.use { input ->
                FileOutputStream(mainDb).use { output -> input.copyTo(output) }
                restored += dbName
            } ?: throw IOException("Cannot open InputStream for Uri: $restoreUri")
        }

        // The staged main database must both look like SQLite and match the openScale schema
        // before the live files are closed or replaced.
        require(mainDb.exists()) { "Main DB file '$dbName' missing in backup" }
        require(isValidOpenScaleMainDb(mainDb)) {
            "Main DB file '$dbName' is not a valid openScale database"
        }

        return if (isZip) "zip" else "legacy"
    }

    private fun swapStagedDatabaseFiles(
        dbDir: File,
        stagingDir: File,
        rollbackDir: File,
        dbName: String
    ) {
        val managedNames = listOf(dbName, "$dbName-shm", "$dbName-wal")
        val liveFiles = managedNames.associateWith { name -> File(dbDir, name) }
        val rollbackFiles = managedNames.associateWith { name -> File(rollbackDir, name) }
        val stagedFiles = managedNames.associateWith { name -> File(stagingDir, name) }

        val movedLiveNames = mutableListOf<String>()
        try {
            // Move the current live files aside first so they can be restored if the swap fails.
            managedNames.forEach { name ->
                val live = liveFiles.getValue(name)
                if (live.exists()) {
                    moveReplacing(live, rollbackFiles.getValue(name))
                    movedLiveNames += name
                }
            }

            // Promote the staged files into the live database location.
            managedNames.forEach { name ->
                val staged = stagedFiles.getValue(name)
                if (staged.exists()) {
                    moveReplacing(staged, liveFiles.getValue(name))
                }
            }
        } catch (swapError: Exception) {
            // Remove any partially restored files before moving the previous live files back.
            managedNames.forEach { name ->
                val live = liveFiles.getValue(name)
                if (live.exists() && !live.delete()) {
                    LogManager.w(
                        TAG,
                        "Could not delete partially restored file ${live.absolutePath} during rollback."
                    )
                }
            }

            movedLiveNames.asReversed().forEach { name ->
                val rollback = rollbackFiles.getValue(name)
                if (!rollback.exists()) return@forEach

                try {
                    moveReplacing(rollback, liveFiles.getValue(name))
                } catch (rollbackError: Exception) {
                    swapError.addSuppressed(rollbackError)
                    LogManager.e(
                        TAG,
                        "Failed to roll back database file '$name' after restore error.",
                        rollbackError
                    )
                }
            }

            throw swapError
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            // Keep the swap atomic when the filesystem supports it.
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun isValidOpenScaleMainDb(file: File): Boolean {
        if (!hasSqliteHeader(file)) return false

        return try {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            try {
                val tableNames = mutableSetOf<String>()
                val cursor = database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'",
                    null
                )
                cursor.use {
                    while (it.moveToNext()) {
                        tableNames += it.getString(0)
                    }
                }

                // Accept both the current Room schema and the legacy schema that older
                // openScale backups may still contain.
                tableNames.containsAll(CURRENT_OPEN_SCALE_TABLES) ||
                    tableNames.containsAll(LEGACY_OPEN_SCALE_TABLES)
            } finally {
                database.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER_PREFIX.size) return false

        val header = ByteArray(SQLITE_HEADER_PREFIX.size)
        FileInputStream(file).use { input ->
            val read = input.read(header)
            if (read != header.size) return false
        }

        return header.contentEquals(SQLITE_HEADER_PREFIX)
    }

    private companion object {
        private val CURRENT_OPEN_SCALE_TABLES = setOf(
            "User",
            "Measurement",
            "MeasurementType",
            "MeasurementValue"
        )
        private val LEGACY_OPEN_SCALE_TABLES = setOf(
            "scaleUsers",
            "scaleMeasurements"
        )
        private val SQLITE_HEADER_PREFIX = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
