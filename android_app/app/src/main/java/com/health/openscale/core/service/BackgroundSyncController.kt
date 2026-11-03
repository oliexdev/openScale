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
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.health.openscale.R
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.facade.BluetoothFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Headless "step on the scale → measurement lands in the database" pipeline.
 *
 * Flow of a weigh-in while openScale is closed:
 *  1. [BackgroundSyncRegistrar] keeps a system-side BLE advertisement scan alive (PendingIntent
 *     scan filtered on the saved scale's MAC). The scan survives process death: the Bluetooth
 *     stack matches the advertisement and wakes openScale by delivering a broadcast to
 *     [BackgroundSyncReceiver].
 *  2. The receiver calls [onScaleAdvertisement], which applies cheap gates (setting on, device
 *     still the saved one, permissions granted, cooldown) and enqueues an expedited
 *     [BackgroundSyncWorker]. An expedited worker is used instead of a custom foreground service
 *     because Android 12+ blocks background foreground-service starts from scan receivers.
 *  3. [runSession] drives the existing [BluetoothFacade] connection machinery exactly like the
 *     UI does: it primes the facade's saved-device flow, triggers
 *     [BluetoothFacade.connectToSavedDevice], waits for the scale handler to publish
 *     (ES-CS20M publishes on the scale's STOP frame) and then disconnects.
 *
 * The controller holds no protocol knowledge and never touches the database itself — measurement
 * saving and smart user assignment stay entirely inside the existing connector/facade path.
 */
@Singleton
class BackgroundSyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val facade: BluetoothFacade,
    private val settingsFacade: SettingsFacade,
) {
    companion object {
        private const val TAG = "BackgroundSync"
        const val UNIQUE_WORK_NAME = "background_scale_sync"

        // The facade caches its saved-device snapshot in a WhileSubscribed StateFlow that has no
        // collector in a cold process; subscribing primes it, this bounds how long we wait.
        private const val PRIME_SAVED_DEVICE_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        // The scale handler publishes on the scale's STOP frame. The window must cover a user
        // that was already standing on the scale when the advertisement woke us up.
        private const val MEASUREMENT_WINDOW_MS = 45_000L
        // After the first publish, linger briefly in case the session produces further entries.
        private const val LINGER_AFTER_SAVE_MS = 6_000L
        private const val DISCONNECT_TIMEOUT_MS = 8_000L
        private const val SESSION_TIMEOUT_MS = 100_000L
        // A scale keeps advertising for a while after a weigh-in. Without a cooldown every
        // advertisement would spawn another connect attempt (battery drain, log spam). 45 s
        // also stops a second connect from racing the scale's post-measurement sleep.
        private const val ATTEMPT_COOLDOWN_MS = 45_000L
        private const val POLL_INTERVAL_MS = 200L
    }

    enum class Outcome { SAVED, NO_NEW_DATA, FAILED, SKIPPED }

    data class SessionResult(val outcome: Outcome, val summary: String? = null)

    private val sessionMutex = Mutex()

    /**
     * Called by [BackgroundSyncReceiver] for every advertisement that passed the system-side MAC
     * filter. Must stay cheap; the heavyweight work happens in [BackgroundSyncWorker.runSession].
     */
    suspend fun onScaleAdvertisement(deviceAddress: String) {
        if (!settingsFacade.backgroundSyncEnabled.first()) return
        if (!hasBlePermissions()) return

        val saved = settingsFacade.observeSavedDevice().first() ?: return
        if (!saved.address.equals(deviceAddress, ignoreCase = true)) return

        val lastAttempt = settingsFacade.lastBackgroundSyncAttemptAt(saved.address)
        if (lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < ATTEMPT_COOLDOWN_MS) return

        LogManager.i(TAG, "Scale ${saved.name} ($deviceAddress) detected; scheduling background sync")
        enqueueExpeditedSync()
    }

    /**
     * Fallback path for scan deliveries whose payload could not be read: the PendingIntent scan
     * is MAC-filtered on the saved device, so the delivery itself already proves that the saved
     * scale advertised. Re-runs the same gates as [onScaleAdvertisement].
     */
    suspend fun onScaleAdvertisementFromFilteredScan() {
        val saved = settingsFacade.observeSavedDevice().first() ?: return
        onScaleAdvertisement(saved.address)
    }

    private fun enqueueExpeditedSync() {
        val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP: while a session is running, further advertisements must not restart it.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Runs one complete headless sync session. Invoked by [BackgroundSyncWorker] (expedited work,
     * roughly 3 minutes of runtime headroom — the session is capped well below that).
     */
    suspend fun runSession(): SessionResult = sessionMutex.withLock {
        val saved = settingsFacade.observeSavedDevice().first()
        if (saved == null || !settingsFacade.backgroundSyncEnabled.first() || !hasBlePermissions()) {
            return@withLock SessionResult(Outcome.SKIPPED)
        }

        // Authoritative cooldown re-check: the receiver's gate may be stale (e.g. the worker was
        // already enqueued before the setting was switched off, or a previous attempt just ran).
        val lastAttempt = settingsFacade.lastBackgroundSyncAttemptAt(saved.address)
        if (lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < ATTEMPT_COOLDOWN_MS) {
            return@withLock SessionResult(Outcome.SKIPPED)
        }
        settingsFacade.recordBackgroundSyncAttempt(saved.address, System.currentTimeMillis())

        LogManager.i(TAG, "Background sync starting for ${saved.name} (${saved.address})")
        val result = withTimeoutOrNull(SESSION_TIMEOUT_MS) { executeSession(saved) }
            ?: SessionResult(Outcome.FAILED)
        forceDisconnect()
        LogManager.i(TAG, "Background sync finished: ${result.outcome}${result.summary?.let { " ($it)" } ?: ""}")
        result
    }

    private suspend fun executeSession(saved: ScannedDeviceInfo): SessionResult = coroutineScope {
        // Counters shared with the event collector coroutine below.
        val saveCount = AtomicInteger(0)
        val summaryRef = AtomicReference<String?>(null)
        val failureRef = AtomicReference<String?>(null)

        val collector = launch {
            // Subscribe BEFORE connecting: the events flow has replay = 0.
            facade.snackbarEventsFromConnector.collect { event ->
                val text = event.message ?: event.messageResId?.let { resId ->
                    runCatching {
                        context.getString(resId, *event.messageFormatArgs.toTypedArray())
                    }.getOrNull()
                } ?: return@collect
                LogManager.d(TAG, "Connector event: $text")

                when (event.messageResId) {
                    R.string.bluetooth_connector_measurement_saved -> {
                        saveCount.incrementAndGet()
                        summaryRef.set(text)
                    }
                    R.string.saved_measurements_message -> {
                        saveCount.addAndGet((event.messageFormatArgs.firstOrNull() as? Int) ?: 1)
                        summaryRef.set(text)
                    }
                    R.string.bluetooth_connector_measurement_save_error,
                    R.string.bluetooth_connector_measurement_user_missing,
                    R.string.bluetooth_connector_measurement_no_values,
                    R.string.bluetooth_connector_measurement_types_not_loaded -> {
                        failureRef.compareAndSet(null, text)
                    }
                }
            }
        }

        try {
            val primed = withTimeoutOrNull(PRIME_SAVED_DEVICE_TIMEOUT_MS) {
                facade.savedDevice.first { it != null }
            }
            if (primed == null || !primed.address.equals(saved.address, ignoreCase = true)) {
                LogManager.w(TAG, "Saved device snapshot unavailable; aborting session")
                return@coroutineScope SessionResult(Outcome.FAILED)
            }

            facade.connectToSavedDevice()

            val connected = awaitStatus(
                targets = setOf(
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.BROADCAST_LISTENING,
                    ConnectionStatus.FAILED,
                ),
                timeoutMs = CONNECT_TIMEOUT_MS,
            )
            if (connected != ConnectionStatus.CONNECTED &&
                connected != ConnectionStatus.BROADCAST_LISTENING
            ) {
                LogManager.w(TAG, "Connection not established (status=$connected)")
                return@coroutineScope SessionResult(Outcome.FAILED)
            }

            // Measurement window: wait for a publish (first save ends the window after a short
            // linger), leave early when the device drops, or time out with no new data.
            awaitMeasurementOutcome(saveCount, summaryRef, failureRef)
        } finally {
            collector.cancel()
        }
    }

    /**
     * Resolves the measurement window. Deliberately a separate function (not an inline
     * `while (true)` loop at the end of the try block): a while loop types as `Unit` in
     * expression position, which would make the whole try/coroutineScope expression `Unit`
     * instead of [SessionResult].
     */
    private suspend fun awaitMeasurementOutcome(
        saveCount: AtomicInteger,
        summaryRef: AtomicReference<String?>,
        failureRef: AtomicReference<String?>,
    ): SessionResult {
        val deadline = SystemClock.elapsedRealtime() + MEASUREMENT_WINDOW_MS
        while (true) {
            failureRef.get()?.let {
                LogManager.w(TAG, "Save problem reported: $it")
                return SessionResult(Outcome.FAILED)
            }
            if (saveCount.get() > 0) {
                delay(LINGER_AFTER_SAVE_MS)
                return SessionResult(Outcome.SAVED, summaryRef.get())
            }
            val status = facade.connectionStatus.value
            if (status == ConnectionStatus.DISCONNECTED ||
                status == ConnectionStatus.FAILED ||
                status == ConnectionStatus.NONE
            ) {
                return SessionResult(Outcome.NO_NEW_DATA)
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return SessionResult(Outcome.NO_NEW_DATA)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitStatus(targets: Set<ConnectionStatus>, timeoutMs: Long): ConnectionStatus? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = facade.connectionStatus.value
            if (status in targets) return status
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private suspend fun forceDisconnect() {
        val status = facade.connectionStatus.value
        if (status == ConnectionStatus.CONNECTED ||
            status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.BROADCAST_LISTENING ||
            status == ConnectionStatus.DISCONNECTING
        ) {
            LogManager.i(TAG, "Forcing disconnect (status=$status)")
            facade.disconnect()
            awaitStatus(
                targets = setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED, ConnectionStatus.NONE),
                timeoutMs = DISCONNECT_TIMEOUT_MS,
            )
        }
    }

    fun hasBlePermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
