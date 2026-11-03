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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.health.openscale.core.utils.LogManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the system's BLE scan deliveries for the PendingIntent scan kept alive by
 * [BackgroundSyncRegistrar].
 *
 * The receiver is declared with `android:exported="false"`: delivery still works because the
 * PendingIntent carries openScale's identity — the Bluetooth stack sends it on the app's behalf.
 * This is the component that wakes openScale up when the saved scale is used, even if the app's
 * process was killed earlier.
 */
class BackgroundSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCALE_ADVERTISEMENT) return

        // Error deliveries (scan failures, teardowns) carry ERROR_CODE instead of results.
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            LogManager.w(TAG, "Scan delivery reported error code $errorCode; ignored")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BackgroundSyncEntryPoint::class.java,
                )
                val controller = entryPoint.backgroundSyncController()

                val address = extractDeviceAddress(intent)
                if (address != null) {
                    controller.onScaleAdvertisement(address)
                } else {
                    // Payload unreadable but no error code: the delivery still proves the
                    // system-side MAC filter matched the saved scale's advertisement, so
                    // treat it as the saved scale rather than dropping the wake-up.
                    LogManager.w(
                        TAG,
                        "Extras unreadable; treating delivery as saved-scale advertisement",
                    )
                    controller.onScaleAdvertisementFromFilteredScan()
                }
            } catch (t: Throwable) {
                LogManager.e(TAG, "Handling scale advertisement failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Extracts the advertised device address from a scan delivery. The Bluetooth stack attaches
     * the results as fill-in extras under [BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT]; the
     * documented shape is an `ArrayList<ScanResult>`, but some stacks deliver a single
     * `ScanResult` under the same key. Both are tried; anything else is dumped to the log so
     * stack quirks show up in logcat instead of failing silently.
     */
    private fun extractDeviceAddress(intent: Intent): String? {
        // The Bundle was parceled by the Bluetooth process; make sure the framework BLE types
        // unparcel even if the default classloader of this delivery differs.
        intent.setExtrasClassLoader(ScanResult::class.java.classLoader)

        val fromList = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            )?.firstOrNull()?.device?.address
        }.getOrNull()
        if (fromList != null) return fromList

        val fromSingle = runCatching {
            IntentCompat.getParcelableExtra(
                intent,
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            )?.device?.address
        }.getOrNull()
        if (fromSingle != null) return fromSingle

        val dump = runCatching {
            val extras = intent.extras
            extras?.keySet()
                ?.sorted()
                ?.joinToString(", ") { key -> "$key=${extras.get(key)?.javaClass?.simpleName ?: "null"}" }
                ?.ifEmpty { "<empty>" } ?: "<none>"
        }.getOrDefault("<unreadable>")
        LogManager.w(TAG, "Scan delivery without readable results; extras: $dump")
        return null
    }

    companion object {
        private const val TAG = "BackgroundSync"
        const val ACTION_SCALE_ADVERTISEMENT =
            "com.health.openscale.action.BACKGROUND_SCALE_ADVERTISEMENT"
    }
}

/**
 * Re-establishes the background advertisement scan whenever an app-external event may have
 * invalidated it: device reboot (PendingIntent scans do not survive reboots), app update
 * (scans are dropped on update) and Bluetooth on/off toggles. All actions are protected system
 * broadcasts, so the receiver stays non-exported.
 */
class BackgroundSyncBootstrapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothAdapter.ACTION_STATE_CHANGED -> Unit
            else -> return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BackgroundSyncEntryPoint::class.java,
                )
                entryPoint.backgroundSyncRegistrar().refresh()
                LogManager.d(TAG, "Background scan registration refreshed (${intent.action})")
            } catch (t: Throwable) {
                LogManager.e(TAG, "Refreshing background scan registration failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BackgroundSync"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundSyncEntryPoint {
    fun backgroundSyncController(): BackgroundSyncController
    fun backgroundSyncRegistrar(): BackgroundSyncRegistrar
}
