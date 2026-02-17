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
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import android.os.SystemClock
import androidx.compose.runtime.Composable
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

// -------------------------------------------------------------------------------------------------
// Broadcast adapter (no GATT)
// - uses Blessed to scan for a specific address and forwards advertisements to handler.onAdvertisement()
// - applies tuning: max scan window, retry/backoff, RSSI filter, packet de-dup, stabilization window
// - attaches handler with a no-op transport immediately on start
// -------------------------------------------------------------------------------------------------

class BroadcastScaleAdapter(
    context: android.content.Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
    profile: TuningProfile = TuningProfile.Balanced
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private val tuning: BleBroadcastTuning = profile.forBroadcast()

    private lateinit var central: BluetoothCentralManager
    private var broadcastAttached = false
    private var scanTimeoutJob: Job? = null
    private var attempt = 0
    private var isScanning = false

    // de-duplication: contentHash -> lastSeenMs
    private val dedupSeen = LinkedHashMap<Int, Long>(64, 0.75f, true)
    private var lastForwardAtMs = 0L

    private fun now() = SystemClock.elapsedRealtime()

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            // Filter: only target MAC
            if (peripheral.address != targetAddress) return

            // Filter: optional RSSI threshold
            val rssi = scanResult.rssi
            tuning.minRssiDbm?.let { if (rssi < it) return }

            // De-dup: collapse identical packets within packetDedupWindowMs
            val bytes = scanResult.scanRecord?.bytes

            LogManager.d(TAG,"Discovered advertisement from ${peripheral.address} RSSI=$rssi ${bytes?.toHexPreview(24)}")

            val hash = contentHash(bytes, rssi)
            val t = now()
            val last = dedupSeen[hash]
            if (last != null && (t - last) <= tuning.packetDedupWindowMs) {
                LogManager.w(TAG, "Deduplicated packet hash=$hash from ${peripheral.address}")
                return
            }
            dedupSeen[hash] = t
            trimDedup(t)

            // Attach handler as soon as we see the target device (if not already)
            ensureAttached(peripheral.address)

            // Optional stabilization: avoid forwarding bursts too quickly
            if (t - lastForwardAtMs < tuning.stabilizeWindowMs) {
                LogManager.w(TAG, "Skipping forwarding to handler (stabilize window) from ${peripheral.address}")
                return
            }

            val user = selectedUserSnapshot ?: return
            LogManager.d(TAG,"Forwarding advertisement to handler: ${peripheral.address} RSSI=$rssi ${bytes?.toHexPreview(24)}")
            val action = handler.onAdvertisement(scanResult, user)
            LogManager.d(TAG, "Handler returned $action for ${peripheral.address}")

            when (action) {
                BroadcastAction.IGNORED -> LogManager.d(TAG, "Advertisement IGNORED for ${peripheral.address}")
                BroadcastAction.CONSUMED_KEEP_SCANNING -> {
                    LogManager.d(TAG, "Advertisement CONSUMED for ${peripheral.address}")
                    lastForwardAtMs = t
                    _events.tryEmit(
                        BluetoothEvent.DeviceMessage(
                            context.getString(R.string.bt_info_waiting_for_measurement),
                            peripheral.address
                        )
                    )
                }
                BroadcastAction.CONSUMED_STOP -> {
                    lastForwardAtMs = t
                    LogManager.d(TAG, "Measurement stabilized → BroadcastComplete for ${peripheral.address}")
                    _events.tryEmit(BluetoothEvent.BroadcastComplete(peripheral.address))
                    stopScanInternal(peripheral.address)
                    cleanup(peripheral.address)
                    broadcastAttached = false
                }
            }
        }
    }

    @Composable
    override fun DeviceConfigurationUi() {
        // Delegate to the actual protocol handler
        handler.DeviceConfigurationUi()
    }

    private fun ensureCentral() {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }
    }

    private fun ensureAttached(address: String) {
        if (broadcastAttached) return
        val driverSettings = FacadeDriverSettings(
            facade = settingsFacade,
            scope = scope,
            handlerNamespace = handler::class.simpleName ?: "Handler"
        )
        handler.attach(noopTransport, appCallbacks, driverSettings, dataProvider)
        broadcastAttached = true
        _events.tryEmit(BluetoothEvent.Listening(address))
    }

    private fun startScanAttempt(address: String) {
        // reset per-attempt state
        isScanning = true
        lastForwardAtMs = 0
        dedupSeen.clear()

        // Blessed does not expose ScanSettings directly; we emulate tuning at our level
        try {
            central.scanForPeripheralsWithAddresses(setOf(address))
            LogManager.d(TAG, "Broadcast scan started (attempt ${attempt + 1}/${tuning.common.maxRetries}) for $address")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start broadcast scan: ${e.message}", e)
            _events.tryEmit(BluetoothEvent.ConnectionFailed(address, e.message ?: context.getString(R.string.bt_error_generic)))
            cleanup(address)
            return
        }

        // Arm scan timeout for this attempt
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(tuning.maxScanMs)
            if (!isScanning) return@launch
            LogManager.w(TAG, "Broadcast scan timed out for $address")
            stopScanInternal(address)

            attempt++
            if (attempt <= tuning.common.maxRetries) {
                delay(tuning.common.retryBackoffMs)
                startScanAttempt(address)
            } else {
                cleanup(address)
                // keep attached? we detach to be consistent with failure
                runCatching { handler.handleDisconnected() }
                runCatching { handler.detach() }
                broadcastAttached = false
            }
        }
    }

    private fun stopScanInternal(address: String) {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        runCatching { if (::central.isInitialized) central.stopScan() }
        isScanning = false
        lastDisconnectAtMs = now()
    }

    private fun trimDedup(t: Long) {
        // simple time-based eviction
        val it = dedupSeen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (t - e.value > tuning.packetDedupWindowMs) it.remove()
            else break // map is access-ordered, earliest first
        }
    }

    private fun contentHash(bytes: ByteArray?, rssi: Int): Int {
        if (bytes == null || bytes.isEmpty()) return rssi // fallback
        // A lightweight rolling hash (faster than Arrays.hashCode in hot path)
        var h = 1125899907
        for (b in bytes) h = (h * 131) xor (b.toInt() and 0xFF)
        // mix in coarse rssi bucket to avoid treating level-only jitter as new data
        val bucket = (rssi / 3) // bucketize
        return (h shl 1) xor bucket
    }

    private val noopTransport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {}
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {}
        override fun read(service: UUID, characteristic: UUID) {}
        override fun disconnect() { doDisconnect() }
        override fun getPeripheral(): BluetoothPeripheral? = null
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = false
    }

    override fun doConnect(address: String, selectedUser: ScaleUser) {
        ensureCentral()

        // Attach early so UI can show “Listening…”
        ensureAttached(address)

        _isConnecting.value = false
        _isConnected.value = false

        attempt = 0
        startScanAttempt(address)
    }

    override fun doDisconnect() {
        stopScanInternal(targetAddress ?: "")
        runCatching { handler.handleDisconnected() }
        runCatching { handler.detach() }
        broadcastAttached = false
    }
}
