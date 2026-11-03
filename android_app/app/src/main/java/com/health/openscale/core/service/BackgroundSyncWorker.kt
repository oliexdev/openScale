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
package com.health.openscale.core.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.utils.LogManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one headless sync session ([BackgroundSyncController.runSession]) as expedited work.
 *
 * Expedited work is the reliable way to run a short, user-triggered task from a background
 * broadcast receiver: Android 12+ blocks starting a custom foreground service from a BLE scan
 * receiver (ForegroundServiceStartNotAllowedException), while WorkManager runs expedited work
 * through its own system-managed scheduling.
 */
@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val controller: BackgroundSyncController,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        LogManager.d(TAG, "Background sync worker started (attempt=$runAttemptCount)")
        val result = controller.runSession()

        if (result.outcome == BackgroundSyncController.Outcome.SAVED && result.summary != null) {
            showSavedNotification(result.summary)
        }
        return Result.success()
    }

    private fun showSavedNotification(summary: String) {
        val appContext = applicationContext

        // Create the channel on every use; createNotificationChannel is idempotent.
        NotificationManagerCompat.from(appContext).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.background_sync_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentTitle(appContext.getString(R.string.background_sync_notification_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Explicit permission check to satisfy Lint and avoid SecurityException on Android 13+
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (canNotify) {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "BackgroundSync"
        private const val CHANNEL_ID = "background_sync"
        // ReminderWorker uses 1001; keep the background-sync result notification distinct.
        private const val NOTIFICATION_ID = 1002
    }
}
