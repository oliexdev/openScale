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
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the system-side BLE advertisement scan in sync with the "Background sync" setting.
 *
 * The scan is registered as a [PendingIntent] scan (see
 * [android.bluetooth.le.BluetoothLeScanner.startScan]) filtered on the saved scale's MAC address:
 *  - Matching happens in the Bluetooth chip/stack, so openScale's process may be dead. When the
 *    saved scale advertises, the system wakes the process by delivering the PendingIntent
 *    broadcast to [BackgroundSyncReceiver].
 *  - The registration survives process death (it lives in the Bluetooth stack); it is cleared by
 *    reboot, app update or [android.bluetooth.le.BluetoothLeScanner.stopScan].
 *
 * Registration is therefore driven from three places:
 *  - this class observes the setting + saved-device flows (toggle changes, re-pairing),
 *  - [BackgroundSyncBootstrapReceiver] calls [refresh] on boot, app update and Bluetooth on/off,
 *  - a force cleanup at process start removes any registration left over from a previous run.
 *
 * All registration changes funnel through a single mutex: the flow observer and the bootstrap
 * receiver can fire concurrently (e.g. right after boot).
 */
@Singleton
class BackgroundSyncRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsFacade: SettingsFacade,
) {
    companion object {
        private const val TAG = "BackgroundSync"
        private const val REQUEST_CODE = 4213
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registrationMutex = Mutex()

    @Volatile private var registeredMac: String? = null

    // False until the first stopScan of this process has cleared a possibly stale registration.
    @Volatile private var staleRegistrationCleared = false

    /**
     * The scan PendingIntent. Created on demand via FLAG_UPDATE_CURRENT, which makes repeated
     * creation idempotent (the system replaces the previous instance with an identical one).
     *
     * It MUST be FLAG_MUTABLE: the Bluetooth stack attaches the scan results as fill-in extras
     * when it delivers the broadcast. With FLAG_IMMUTABLE the fill-in is dropped — the receiver
     * still wakes up, but the delivery carries no ScanResult at all. Mutability is safe here
     * because the base intent targets openScale's own receiver component, which a fill-in
     * intent cannot change.
     *
     * Note: never call cancel() on it — a cancelled PendingIntent must not be reused, while
     * stopScan() alone is the correct teardown for PendingIntent scans.
     */
    private fun scanPendingIntent(): PendingIntent {
        val intent = Intent(context, BackgroundSyncReceiver::class.java)
            .setAction(BackgroundSyncReceiver.ACTION_SCALE_ADVERTISEMENT)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    init {
        scope.launch {
            registrationMutex.withLock { unregisterInternalLocked() }
            settingsFacade.backgroundSyncEnabled
                .combine(settingsFacade.observeSavedDevice()) { enabled, device ->
                    enabled to device?.address
                }
                .distinctUntilChanged()
                .collect { (enabled, address) -> applyRegistration(enabled, address) }
        }
    }

    /**
     * Re-evaluates the registration from current settings; used by the bootstrap receiver.
     * Forced: even if the cached state looks correct, the Bluetooth stack may have dropped the
     * scan (e.g. it was on when Bluetooth toggled off and on again).
     */
    suspend fun refresh() {
        val enabled = settingsFacade.backgroundSyncEnabled.first()
        val address = settingsFacade.observeSavedDevice().first()?.address
        applyRegistration(enabled, address, force = true)
    }

    private suspend fun applyRegistration(enabled: Boolean, address: String?, force: Boolean = false) {
        registrationMutex.withLock {
            if (!enabled || address == null || !hasScanPermission()) {
                unregisterInternalLocked()
                return@withLock
            }
            val scanner = bluetoothLeScanner()
            if (scanner == null) {
                unregisterInternalLocked()
                return@withLock
            }
            if (registeredMac == address && !force) return@withLock

            unregisterInternalLocked()
            try {
                val filter = ScanFilter.Builder().setDeviceAddress(address).build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build()
                scanner.startScan(listOf(filter), settings, scanPendingIntent())
                registeredMac = address
                LogManager.i(TAG, "Background advertisement scan registered for $address")
            } catch (e: Exception) {
                registeredMac = null
                LogManager.e(TAG, "Registering background advertisement scan failed", e)
            }
        }
    }

    /** Must be called while holding [registrationMutex]. */
    private fun unregisterInternalLocked() {
        if (registeredMac == null && staleRegistrationCleared) return
        staleRegistrationCleared = true
        registeredMac = null

        val scanner = bluetoothLeScanner() ?: return
        try {
            scanner.stopScan(scanPendingIntent())
            LogManager.d(TAG, "Background advertisement scan unregistered")
        } catch (e: Exception) {
            // stopScan is safe to fail here (e.g. Bluetooth just turned off); the next
            // applyRegistration pass will reconcile the state.
            LogManager.w(TAG, "Unregistering background advertisement scan failed: ${e.message}")
        }
    }

    private fun bluetoothLeScanner(): BluetoothLeScanner? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return try {
            adapter.bluetoothLeScanner
        } catch (e: Exception) {
            LogManager.w(TAG, "BluetoothLeScanner unavailable: ${e.message}")
            null
        }
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
}
