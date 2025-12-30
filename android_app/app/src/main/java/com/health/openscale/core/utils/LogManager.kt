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
package com.health.openscale.core.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.health.openscale.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manages logging for the application, providing methods to log messages
 * to Logcat and optionally to a file.
 */
object LogManager {

    private const val TAG = "openScaleLog"
    private const val LOG_SUB_DIRECTORY = "logs"
    private const val CURRENT_LOG_FILE_NAME_BASE = "openScale_current_log"
    private const val LOG_FILE_EXTENSION = ".txt"
    private const val MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024 // 10 MiB

    private val fileMutex = Mutex()

    private var isInitialized = false
    private var logToFileEnabled = false
    private lateinit var appContext: Context
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var isMarkdownBlockOpen = false

    /**
     * Initializes the LogManager. Must be called once, typically in Application.onCreate().
     * @param context The application context.
     * @param enableLoggingToFile True to enable logging to a file, false otherwise.
     */
    fun init(context: Context, enableLoggingToFile: Boolean) {
        if (isInitialized) {
            // Log a warning if already initialized, but don't re-initialize.
            Log.w(TAG, "LogManager already initialized. Ignoring subsequent init call.")
            return
        }

        appContext = context.applicationContext
        logToFileEnabled = enableLoggingToFile
        isInitialized = true

        // Log initialization status.
        if (logToFileEnabled) {
            coroutineScope.launch {
                resetLogFileOnStartup()
                i(
                    TAG,
                    "LogManager initialized. Logging to file: enabled. Log directory: ${getLogDirectory().absolutePath}"
                )
            }
        } else {
            i(TAG, "LogManager initialized. Logging to file: disabled.")
        }
    }

    /**
     * Deletes the current log file if it exists and writes initial headers.
     * This is called on startup if file logging is enabled.
     */
    private fun resetLogFileOnStartup() {
        val logDir = getLogDirectory()
        val currentLogFile = File(logDir, "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")

        if (currentLogFile.exists()) {
            if (currentLogFile.delete()) {
                d(TAG, "Previous log file deleted on startup: ${currentLogFile.name}")
            } else {
                w(TAG, "Failed to delete previous log file on startup: ${currentLogFile.name}")
            }
        }
        // Always attempt to write headers, ensures file is created if it didn't exist.
        writeInitialLogHeaders(currentLogFile)
    }

    /**
     * Updates the preference for logging to a file.
     * If logging is enabled and was previously disabled, it ensures log headers are written.
     * @param enabled True to enable file logging, false to disable.
     */
    fun updateLoggingPreference(enabled: Boolean) {
        val oldState = logToFileEnabled
        if (oldState == enabled) {
            d(TAG, "File logging preference is already set to: $enabled. No change.")
            return
        }
        logToFileEnabled = enabled
        if (!logToFileEnabled) {
            closeMarkdownBlock()
        }
        i(TAG, "File logging preference updated to: $logToFileEnabled (was: $oldState)")
        if (logToFileEnabled) {
            coroutineScope.launch {
                try { closeMarkdownBlock() } catch (_: Exception) {}
                val file = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")
                if (file.exists()) {
                    file.delete()
                }
                writeInitialLogHeaders(file)
                isMarkdownBlockOpen = true
                i(TAG, "File logging enabled during runtime – started fresh session log.")
            }
        }
    }

    /**
     * Retrieves the directory for storing log files.
     * It prioritizes external storage and falls back to internal storage if external is not available.
     * Creates the directory if it doesn't exist.
     * @return The File object representing the log directory.
     */
    private fun getLogDirectory(): File {
        val externalLogDir = appContext.getExternalFilesDir(LOG_SUB_DIRECTORY)
        if (externalLogDir != null) {
            if (!externalLogDir.exists()) {
                if (!externalLogDir.mkdirs()) {
                    w(
                        TAG,
                        "Failed to create external log directory: ${externalLogDir.absolutePath}. Attempting internal storage."
                    )
                    // Fall through to internal storage if mkdirs fails
                } else {
                    d(TAG, "External log directory created: ${externalLogDir.absolutePath}")
                    return externalLogDir
                }
            }
            return externalLogDir
        }

        // Fallback to internal storage
        val internalLogDir = File(appContext.filesDir, LOG_SUB_DIRECTORY)
        if (!internalLogDir.exists()) {
            if (!internalLogDir.mkdirs()) {
                // If internal storage also fails, this is a more serious issue.
                e(
                    TAG,
                    "Failed to create internal log directory: ${internalLogDir.absolutePath}. Logging to file may not work."
                )
            } else {
                d(TAG, "Internal log directory created: ${internalLogDir.absolutePath}")
            }
        }
        // Log this fallback case if externalLogDir was null initially
        if (externalLogDir == null) {
            w(
                TAG,
                "External storage not available. Using internal storage for logs: ${internalLogDir.absolutePath}"
            )
        }
        return internalLogDir
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        log(Log.VERBOSE, tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        log(Log.DEBUG, tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        log(Log.INFO, tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String, throwable: Throwable? = null) {
        log(Log.WARN, tag, message, throwable)
    }

    @JvmStatic
    fun e(tag: String?, message: String, throwable: Throwable? = null) {
        log(Log.ERROR, tag, message, throwable)
    }

    /**
     * Core logging function. Logs to Logcat and, if enabled, to a file.
     * @param priority The log priority (e.g., Log.VERBOSE, Log.ERROR).
     * @param tag The tag for the log message. Defaults to DEFAULT_TAG if null.
     * @param message The message to log.
     * @param throwable An optional throwable to log with its stack trace.
     */
    private fun log(priority: Int, tag: String?, message: String, throwable: Throwable? = null) {
        if (!isInitialized) {
            // Use Android's Log directly if LogManager isn't initialized.
            // This ensures critical early errors or misconfigurations are visible.
            val initErrorMsg =
                "LogManager not initialized! Attempted to log: [${tag ?: TAG}] $message"
            Log.e("LogManager_NotInit", initErrorMsg, throwable)
            // Optionally, print to System.err as a last resort if Logcat is also problematic
            // System.err.println("$initErrorMsg ${throwable?.let { Log.getStackTraceString(it) }}")
            return
        }

        val currentTag = tag ?: TAG

        // Log to Android's Logcat
        when (priority) {
            Log.VERBOSE -> Log.v(currentTag, message, throwable)
            Log.DEBUG -> Log.d(currentTag, message, throwable)
            Log.INFO -> Log.i(currentTag, message, throwable)
            Log.WARN -> Log.w(currentTag, message, throwable)
            Log.ERROR -> Log.e(currentTag, message, throwable)
            // Default case for custom priorities, though less common with this setup.
            else -> Log.println(
                priority,
                currentTag,
                message + if (throwable != null) "\n${Log.getStackTraceString(throwable)}" else ""
            )
        }

        // Log to file if enabled
        if (logToFileEnabled) {
            val formatted = formatMessageForFile(priority, currentTag, message, throwable)
            coroutineScope.launch {
                fileMutex.withLock {
                    try {
                        val currentLogFile = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")

                        currentLogFile.parentFile?.mkdirs()

                        if (!currentLogFile.exists() || currentLogFile.length() == 0L) {
                            writeInitialLogHeaders(currentLogFile)
                            isMarkdownBlockOpen = true
                        }

                        checkAndRotateLog(currentLogFile)

                        OutputStreamWriter(
                            FileOutputStream(currentLogFile, true),
                            StandardCharsets.UTF_8
                        ).use { w ->
                            w.append(formatted)
                            w.append("\n")
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing to log file: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Writes initial header information to the specified log file.
     * Includes session start time, application info, and device info.
     * This method will create the file if it doesn't exist, or overwrite it if it does.
     * @param logFile The file to write the headers to.
     */
    private fun writeInitialLogHeaders(logFile: File) {
        try {
            // Ensure directory exists.
            logFile.parentFile?.mkdirs()
            OutputStreamWriter(
                FileOutputStream(logFile, false),
                StandardCharsets.UTF_8
            ).use { writer ->
                val sessionStartTime = dateFormat.format(Date())
                val sessionId = System.currentTimeMillis().toString(16)
                val currentTimeZone = TimeZone.getDefault()

                // GitHub-friendly Markdown header; copy-pasteable into issues/PRs.
                writer.append("| Field | Value |\n")
                writer.append("|---|---|\n")
                writer.append("| Time | $sessionStartTime |\n")
                writer.append("| Timezone | ${currentTimeZone.id} (UTC ${currentTimeZone.rawOffset / 3600000}h) |\n")
                writer.append("| Session ID | $sessionId |\n")
                writer.append("| App | openScale |\n")
                writer.append("| Version | ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) |\n")
                writer.append("| Package | ${BuildConfig.APPLICATION_ID} |\n")
                writer.append("| Build Type | ${BuildConfig.BUILD_TYPE} |\n")
                writer.append("| Device | ${Build.MANUFACTURER} ${Build.MODEL} |\n")
                writer.append("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |\n")
                writer.append("| Build ID | ${Build.DISPLAY} |\n")
                writer.append("| Build Time | ${BuildConfig.BUILD_TIME_UTC} |\n")
                writer.append("| Git SHA | ${BuildConfig.GIT_SHA} |\n")
                writer.append("\n")
                // Open a ```diff block; subsequent log lines use +/-/!/… prefixes.
                writer.append("```diff\n")
            }
            isMarkdownBlockOpen = true
            d(TAG, "Initial markdown-diff headers written to: ${logFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Error writing initial log headers to ${logFile.absolutePath}", e)
        }
    }

    /**
     * Formats a log message for file output.
     * Includes timestamp, priority character, tag, message, and stack trace if available.
     * @param priority The log priority.
     * @param tag The log tag.
     * @param message The log message.
     * @param throwable An optional throwable.
     * @return The formatted log string.
     */
    private fun formatMessageForFile(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable?
    ): String {
        // GitHub diff mapping: + INFO, ! WARN, - ERROR, ' ' DEBUG, ? VERBOSE
        val levelChar = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> '?'
        }
        val prefix = when (priority) {
            Log.ERROR -> "- "
            Log.WARN -> "! "
            Log.INFO -> "+ "
            Log.DEBUG -> "? "
            Log.VERBOSE -> ". "
            else -> "  "
        }
        val timestamp = dateFormat.format(Date())
        val base = "$timestamp $levelChar/$tag: $message"
        val withThrowable =
            if (throwable != null) "$base\n${Log.getStackTraceString(throwable)}" else base
        return prefix + withThrowable
    }

    /**
     * Checks if the current log file exceeds the maximum allowed size and rotates it if necessary.
     * Rotation currently means deleting the oversized log file and starting a new one
     * by writing the initial log headers.
     * @param currentLogFile The current log file.
     */
    private fun checkAndRotateLog(currentLogFile: File) {
        if (currentLogFile.exists() && currentLogFile.length() > MAX_LOG_SIZE_BYTES) {
            val oldFileSize = currentLogFile.length()
            i(TAG, "Log file '${currentLogFile.name}' (size: $oldFileSize bytes) exceeds limit ($MAX_LOG_SIZE_BYTES bytes). Rotating.")

            try {
                if (isMarkdownBlockOpen) {
                    OutputStreamWriter(FileOutputStream(currentLogFile, true), StandardCharsets.UTF_8).use {
                        it.append("\n```").append("\n")
                    }
                    isMarkdownBlockOpen = false
                }
            } catch (_: IOException) {}

            if (currentLogFile.delete()) {
                i(TAG, "Oversized log file deleted: ${currentLogFile.name}. A new log file will be started with headers.")
                writeInitialLogHeaders(currentLogFile)
                isMarkdownBlockOpen = true
            } else {
                e(TAG, "Failed to delete oversized log file '${currentLogFile.name}' for rotation.")
            }
        }
    }

    /**
     * Gets the current log file.
     * @return The File object for the current log file, or null if LogManager is not initialized
     *         or if the log file does not exist (and file logging is expected).
     */
    fun getLogFile(): File? {
        if (!isInitialized) {
            w(TAG, "getLogFile() called before LogManager was initialized.")
            return null
        }
        val logFile = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")
        return if (logFile.exists()) {
            logFile
        } else {
            // Log file might not exist if logging to file is disabled or no logs written yet.
            d(TAG, "Queried log file does not currently exist at path: ${logFile.absolutePath}")
            null
        }
    }

    /**
     * Clears the current log file(s).
     * If file logging is enabled, it then writes the initial log headers to the new empty log file.
     */
    fun clearLogFiles() {
        if (!isInitialized) {
            w(TAG, "clearLogFiles() called before LogManager was initialized.")
            return
        }
        coroutineScope.launch {
            val logDir = getLogDirectory() // Get directory first
            val currentLogFile = File(logDir, "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")

            try {
                if (currentLogFile.exists()) {
                    try {
                        OutputStreamWriter(
                            FileOutputStream(currentLogFile, true),
                            StandardCharsets.UTF_8
                        ).use {
                            it.append("\n```").append("\n")
                        }
                        isMarkdownBlockOpen = false
                    } catch (_: Exception) {
                    }

                    if (currentLogFile.delete()) {
                        i(TAG, "Log file cleared: ${currentLogFile.absolutePath}")
                    } else {
                        e(TAG, "Failed to clear log file: ${currentLogFile.absolutePath}")
                        // If deletion fails, do not proceed to write headers to a potentially problematic file.
                        return@launch
                    }
                } else {
                    i(
                        TAG,
                        "Log file already cleared or did not exist: ${currentLogFile.absolutePath}"
                    )
                }

                // If file logging is enabled, a new log session effectively starts, so write headers.
                if (logToFileEnabled) {
                    d(TAG, "File logging is enabled, writing initial headers after clearing.")
                    writeInitialLogHeaders(currentLogFile) // starts new ```diff block
                    isMarkdownBlockOpen = true
                }
            } catch (e: Exception) {
                // Catch any unexpected exception during file operations.
                Log.e(
                    TAG,
                    "Error during clearLogFiles operation for ${currentLogFile.absolutePath}",
                    e
                )
            }
        }
    }

    /**
     * Closes the ```diff code block at the end of the current log file (if any).
     * Safe to call multiple times; appends a fence only if the file exists.
     */
    @JvmStatic
    fun closeMarkdownBlock() {
        if (!isInitialized) return
        val logFile = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")
        if (!logFile.exists() || !isMarkdownBlockOpen) return

        coroutineScope.launch {
            fileMutex.withLock {
                try {
                    OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8).use {
                        it.append("\n```").append("\n")
                    }
                    isMarkdownBlockOpen = false
                    d(TAG, "Markdown diff block closed.")
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing markdown diff block", e)
                }
            }
        }
    }

    @JvmStatic
    fun ensureMarkdownBlockOpen() {
        if (!isInitialized || !logToFileEnabled || isMarkdownBlockOpen) return
        val logFile = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")
        if (!logFile.exists() || logFile.length() == 0L) return

        coroutineScope.launch {
            fileMutex.withLock {
                try {
                    OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8).use {
                        it.append("```diff\n")
                    }
                    isMarkdownBlockOpen = true
                    d(TAG, "Markdown diff block reopened after export.")
                } catch (e: IOException) {
                    Log.e(TAG, "Error reopening markdown diff block", e)
                }
            }
        }
    }

    @JvmStatic
    suspend fun exportLogToUri(context: Context, targetUri: Uri): Boolean {
        if (!isInitialized) return false

        return try {
            fileMutex.withLock {
                val logFile = File(getLogDirectory(), "$CURRENT_LOG_FILE_NAME_BASE$LOG_FILE_EXTENSION")
                if (!logFile.exists()) return@withLock false

                if (isMarkdownBlockOpen) {
                    try {
                        OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8).use {
                            it.append("\n```").append("\n")
                        }
                        isMarkdownBlockOpen = false
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing markdown block before export", e)
                    }
                }

                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    logFile.inputStream().use { it.copyTo(out) }
                } ?: return@withLock false

                if (logToFileEnabled) {
                    try {
                        OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8).use {
                            it.append("```diff\n")
                        }
                        isMarkdownBlockOpen = true
                    } catch (e: IOException) {
                        Log.e(TAG, "Error reopening markdown block after export", e)
                    }
                }
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting log file", t)
            false
        }
    }
}
