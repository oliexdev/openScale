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
package com.health.openscale.core.bluetooth.modern

import android.bluetooth.le.ScanResult
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import java.util.UUID

// -------------------------------------------------------------------------------------------------
// Broadcast adapter (no GATT)
// - scans and forwards advertisements to handler.onAdvertisement()
// - attaches handler with a no-op transport
// -------------------------------------------------------------------------------------------------

class BroadcastScaleAdapter(
    context: android.content.Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler) {

    private lateinit var central: BluetoothCentralManager
    private var broadcastAttached = false

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (peripheral.address != targetAddress) return

            if (!broadcastAttached) {
                val driverSettings = FacadeDriverSettings(
                    facade = settingsFacade,
                    scope = scope,
                    deviceAddress = peripheral.address,
                    handlerNamespace = handler::class.simpleName ?: "Handler"
                )
                handler.attach(noopTransport, appCallbacks, driverSettings, dataProvider)
                broadcastAttached = true
                _events.tryEmit(BluetoothEvent.Listening(peripheral.address))
            }

            val user = ensureSelectedUserOrFail(peripheral.address) ?: return
            when (handler.onAdvertisement(scanResult, user)) {
                BroadcastAction.IGNORED -> Unit
                BroadcastAction.CONSUMED_KEEP_SCANNING -> {
                    _events.tryEmit(
                        BluetoothEvent.DeviceMessage(
                            context.getString(R.string.bt_info_waiting_for_measurement),
                            peripheral.address
                        )
                    )
                }
                BroadcastAction.CONSUMED_STOP -> {
                    _events.tryEmit(BluetoothEvent.BroadcastComplete(peripheral.address))
                    doDisconnect()
                    cleanup(peripheral.address)
                    broadcastAttached = false
                }
            }
        }
    }

    private val noopTransport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) {}
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {}
        override fun read(service: UUID, characteristic: UUID) {}
        override fun disconnect() { doDisconnect() }
    }

    override fun doConnect(address: String, selectedUser: ScaleUser) {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }
        _isConnecting.value = false
        _isConnected.value = false
        try {
            central.scanForPeripheralsWithAddresses(arrayOf(address))
        } catch (e: Exception) {
            _events.tryEmit(BluetoothEvent.ConnectionFailed(address, e.message ?: context.getString(R.string.bt_error_generic)))
            cleanup(address)
        }
    }

    override fun doDisconnect() {
        runCatching { if (::central.isInitialized) central.stopScan() }
        runCatching { handler.handleDisconnected() }
        runCatching { handler.detach() }
    }
}
