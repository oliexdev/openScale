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

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.ScanFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

// Beispielhafte UUIDs - DIESE MÜSSEN DURCH DIE KORREKTEN UUIDs IHRER ZIELGERÄTE ERSETZT WERDEN!
object ScaleGattAttributes {
    // Beispiel: Body Composition Service
    val BODY_COMPOSITION_SERVICE_UUID: UUID = UUID.fromString("0000181B-0000-1000-8000-00805F9B34FB")
    // Beispiel: Body Composition Measurement Characteristic
    val BODY_COMPOSITION_MEASUREMENT_UUID: UUID = UUID.fromString("00002A9C-0000-1000-8000-00805F9B34FB")
    // Beispiel: Weight Scale Service
    val WEIGHT_SCALE_SERVICE_UUID: UUID = UUID.fromString("0000181D-0000-1000-8000-00805F9B34FB")
    // Beispiel: Weight Measurement Characteristic
    val WEIGHT_MEASUREMENT_UUID: UUID = UUID.fromString("00002A9D-0000-1000-8000-00805F9B34FB")
    // Beispiel: Current Time Service (oft für Bonding oder User-Setup verwendet)
    val CURRENT_TIME_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805F9B34FB")
    val CURRENT_TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A2B-0000-1000-8000-00805F9B34FB")
    // Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}


class ModernScaleAdapter(
    private val context: Context
) : ScaleCommunicator {
    private val TAG = "ModernScaleAdapter"

    private val adapterScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var central: BluetoothCentralManager // Initialisiert in init

    private var currentPeripheral: BluetoothPeripheral? = null
    private var targetAddress: String? = null
    private var currentScaleUser: ScaleUser? = null // von der Schnittstelle
    private var currentAppUserId: Int? = null

    private val _eventsFlow = MutableSharedFlow<BluetoothEvent>(replay = 1, extraBufferCapacity = 5)
    override fun getEventsFlow(): SharedFlow<BluetoothEvent> = _eventsFlow.asSharedFlow()
    override fun processUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    ) {
        LogManager.w(TAG, "Error not implemented processUserInteractionFeedback received: Type=$interactionType, UserID=$appUserId, Data=$feedbackData", null)
    }

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val bluetoothCentralManagerCallback: BluetoothCentralManagerCallback =
        object : BluetoothCentralManagerCallback() {
            override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
                adapterScope.launch {
                    LogManager.i(TAG, "Verbunden mit ${peripheral.name} (${peripheral.address})")
                    currentPeripheral = peripheral
                    _isConnected.value = true
                    _isConnecting.value = false
                    _eventsFlow.tryEmit(BluetoothEvent.Connected(peripheral.name ?: "Unbekannt", peripheral.address))

                    // Nachdem verbunden, Services entdecken
                    LogManager.d(TAG, "Starte Service Discovery für ${peripheral.address}")
                }
            }

            override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
                adapterScope.launch {
                    LogManager.e(TAG, "Verbindung zu ${peripheral.address} fehlgeschlagen. Status: $status")
                    _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(peripheral.address, "Verbindung fehlgeschlagen: $status"))
                    cleanupAfterDisconnect(peripheral.address)
                }
            }

            override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
                adapterScope.launch {
                    LogManager.i(TAG, "Getrennt von ${peripheral.name} (${peripheral.address}). Status: $status")
                    val reason = "Getrennt: $status"
                    // Nur Event senden, wenn es das aktuell verbundene/verbindende Gerät war
                    if (targetAddress == peripheral.address) {
                        _eventsFlow.tryEmit(BluetoothEvent.Disconnected(peripheral.address, reason))
                        cleanupAfterDisconnect(peripheral.address)
                    } else {
                        LogManager.w(TAG, "Disconnected Event für nicht-Zielgerät ${peripheral.address} ignoriert (Ziel war $targetAddress).")
                    }
                }
            }

            override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
                // Wir scannen spezifisch nach Adresse, also sollte dies unser Gerät sein.
                if (peripheral.address == targetAddress) {
                    LogManager.i(TAG, "Gerät ${peripheral.name} (${peripheral.address}) gefunden. Stoppe Scan und verbinde.")
                    central.stopScan()
                    central.connectPeripheral(peripheral, peripheralCallback)
                    // _isConnecting bleibt true, bis onConnectedPeripheral oder onConnectionFailed aufgerufen wird
                } else {
                    LogManager.d(TAG, "Scan hat anderes Gerät gefunden: ${peripheral.address}. Ignoriere.")
                }
            }

            override fun onScanFailed(scanFailure: ScanFailure) {
                adapterScope.launch {
                    LogManager.e(TAG, "Scan fehlgeschlagen: $scanFailure")
                    if (targetAddress != null && _isConnecting.value) {
                        _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed(targetAddress!!, "Scan fehlgeschlagen: $scanFailure"))
                        cleanupAfterDisconnect(targetAddress) // targetAddress könnte null sein, wenn connect nie erfolgreich war
                    }
                }
            }
        }


    init {
        if (!hasRequiredBluetoothPermissions()) {
            LogManager.e(TAG, "Fehlende Bluetooth-Berechtigungen. Adapter kann nicht initialisiert werden.")
            // Sende einen Fehler-Event oder werfe eine Exception, um das Problem anzuzeigen.
            // _eventsFlow.tryEmit(BluetoothEvent.ConnectionFailed("initialization", "Missing Bluetooth permissions"))
        } else {
            central = BluetoothCentralManager(
                context,
                bluetoothCentralManagerCallback,
                mainHandler
            )
            LogManager.d(TAG, "BlessedScaleAdapter instanziiert und BluetoothCentralManager initialisiert.")
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        val required = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        return required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    // Call this after runtime grant or lazily before first connect
    private fun ensureCentralReady(): Boolean {
        if (!::central.isInitialized) {
            val hasScan = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!(hasScan || hasConnect)) return false // at least one check; we hard-fail later if CONNECT is missing
            central = BluetoothCentralManager(context, bluetoothCentralManagerCallback, mainHandler)
            LogManager.d(TAG, "BluetoothCentralManager initialized")
        }
        return true
    }


    override fun connect(address: String, scaleUser: ScaleUser?) {
        adapterScope.launch {
            // Ensure central exists (may be created after runtime grant)
            if (!ensureCentralReady()) {
                _eventsFlow.tryEmit(
                    BluetoothEvent.ConnectionFailed(address, "Bluetooth permissions missing (SCAN/CONNECT)")
                )
                return@launch
            }

            // Ignore duplicate connects
            if (_isConnecting.value || (_isConnected.value && targetAddress == address)) {
                LogManager.d(TAG, "connect($address) ignored: already connecting/connected")
                if (_isConnected.value && targetAddress == address) {
                    val deviceName = currentPeripheral?.name ?: "Unknown"
                    _eventsFlow.tryEmit(BluetoothEvent.Connected(deviceName, address))
                }
                return@launch
            }

            // Switch target: tear down old connection attempt
            if ((_isConnected.value || _isConnecting.value) && targetAddress != address) {
                LogManager.d(TAG, "Switching from $targetAddress to $address")
                disconnectLogic()
            }

            _isConnecting.value = true
            _isConnected.value = false
            targetAddress = address
            currentScaleUser = scaleUser

            LogManager.i(TAG, "Connecting to $address (user=${scaleUser?.id})")

            // Stop any previous scans
            runCatching { central.stopScan() }

            val hasScan = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val hasConnect = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasConnect) {
                // Without CONNECT we can't perform GATT ops reliably
                LogManager.e(TAG, "Missing BLUETOOTH_CONNECT")
                _eventsFlow.tryEmit(
                    BluetoothEvent.ConnectionFailed(address, "Missing BLUETOOTH_CONNECT permission")
                )
                _isConnecting.value = false
                targetAddress = null
                return@launch
            }

            try {
                if (hasScan) {
                    // Preferred path on 12+: short pre-scan by address
                    central.scanForPeripheralsWithAddresses(arrayOf(address))
                    LogManager.d(TAG, "Pre-scan started for $address")
                    // onDiscoveredPeripheral will stop scan and connect
                } else {
                    // Fallback: connect without pre-scan (may be less reliable on some OEMs)
                    LogManager.w(TAG, "BLUETOOTH_SCAN not granted → connecting without pre-scan")
                    val p = central.getPeripheral(address)
                    central.connectPeripheral(p, peripheralCallback)
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to start connect/scan for $address", e)
                _eventsFlow.tryEmit(
                    BluetoothEvent.ConnectionFailed(address, "Failed to start scan/connect: ${e.message}")
                )
                _isConnecting.value = false
                targetAddress = null
            }
        }
    }


    private val peripheralCallback: BluetoothPeripheralCallback =
        object : BluetoothPeripheralCallback() {
            override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
                LogManager.i(TAG, "Services entdeckt für ${peripheral.address}")
                // HIER kommt die Logik, um die relevanten Characteristics zu abonnieren (Notifications/Indications)
                // Beispiel für Weight Measurement und Body Composition Measurement:
                enableNotifications(peripheral, ScaleGattAttributes.WEIGHT_SCALE_SERVICE_UUID, ScaleGattAttributes.WEIGHT_MEASUREMENT_UUID)
                enableNotifications(peripheral, ScaleGattAttributes.BODY_COMPOSITION_SERVICE_UUID, ScaleGattAttributes.BODY_COMPOSITION_MEASUREMENT_UUID)

                // Optional: Benutzerdaten schreiben oder andere Initialisierungssequenzen
                // sendUserDataIfNeeded(peripheral)
            }

            override fun onCharacteristicUpdate(
                peripheral: BluetoothPeripheral,
                value: ByteArray,
                characteristic: BluetoothGattCharacteristic,
                status: GattStatus
            ) {
                if (status == GattStatus.SUCCESS) {
                    LogManager.d(TAG, "Characteristic ${characteristic.uuid} Update von ${peripheral.address}: ${value.toHexString()}")
                    // HIER PARSEN SIE DIE `value` (ByteArray) basierend auf der `characteristic.uuid`
                    // und erstellen ein `com.health.openscale.core.datatypes.ScaleMeasurement`
                    val measurement = parseMeasurementData(characteristic.uuid, value, peripheral.address)
                    if (measurement != null) {
                        adapterScope.launch {
                            _eventsFlow.tryEmit(BluetoothEvent.MeasurementReceived(measurement, peripheral.address))
                        }
                    } else {
                        LogManager.w(TAG, "Konnte Daten von ${characteristic.uuid} nicht parsen.")
                        adapterScope.launch {
                            _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Unbekannte Daten empfangen von ${characteristic.uuid}", peripheral.address))
                        }
                    }
                } else {
                    LogManager.e(TAG, "Characteristic ${characteristic.uuid} Update Fehler: $status von ${peripheral.address}")
                }
            }

            override fun onCharacteristicWrite(
                peripheral: BluetoothPeripheral,
                value: ByteArray,
                characteristic: BluetoothGattCharacteristic,
                status: GattStatus
            ) {
                if (status == GattStatus.SUCCESS) {
                    LogManager.i(TAG, "Erfolgreich auf Characteristic ${characteristic.uuid} geschrieben: ${value.toHexString()}")
                    // Hier ggf. weitere Logik, falls ein Schreibvorgang Teil einer Sequenz ist
                } else {
                    LogManager.e(TAG, "Fehler beim Schreiben auf Characteristic ${characteristic.uuid}: $status")
                    adapterScope.launch {
                        _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Fehler beim Schreiben (${characteristic.uuid}): $status", peripheral.address))
                    }
                }
            }

            override fun onNotificationStateUpdate(
                peripheral: BluetoothPeripheral,
                characteristic: BluetoothGattCharacteristic,
                status: GattStatus
            ) {
                if (status == GattStatus.SUCCESS) {
                    if (peripheral.isNotifying(characteristic)) {
                        LogManager.i(TAG, "Notifications erfolgreich aktiviert für ${characteristic.uuid} auf ${peripheral.address}")
                    } else {
                        LogManager.i(TAG, "Notifications erfolgreich deaktiviert für ${characteristic.uuid} auf ${peripheral.address}")
                    }
                } else {
                    LogManager.e(TAG, "Fehler beim Aktualisieren des Notification Status für ${characteristic.uuid}: $status")
                    adapterScope.launch {
                        _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Fehler bei Notif. für ${characteristic.uuid}: $status", peripheral.address))
                    }
                }
            }
        }

    private fun enableNotifications(peripheral: BluetoothPeripheral, serviceUUID: UUID, characteristicUUID: UUID) {
        val characteristic = peripheral.getCharacteristic(serviceUUID, characteristicUUID)
        if (characteristic != null) {
            if (peripheral.setNotify(characteristic, true)) {
                LogManager.d(TAG, "Versuche Notifications für ${characteristicUUID} zu aktivieren.")
            } else {
                LogManager.e(TAG, "Fehler beim Versuch, Notifications für ${characteristicUUID} zu aktivieren (setNotify gab false zurück).")
                adapterScope.launch {
                    _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Konnte Notif. nicht aktivieren für ${characteristic.uuid}", peripheral.address))
                }
            }
        } else {
            LogManager.w(TAG, "Characteristic ${characteristicUUID} nicht gefunden im Service ${serviceUUID}.")
        }
    }

    /**
     * Parst die Rohdaten einer BLE Characteristic und konvertiert sie in ein ScaleMeasurement Objekt.
     * DIES IST EINE SEHR SPEZIFISCHE FUNKTION UND MUSS FÜR JEDE WAAGE/PROTOKOLL IMPLEMENTIERT WERDEN.
     *
     * @param characteristicUuid Die UUID der Characteristic, von der die Daten stammen.
     * @param value Das ByteArray mit den Rohdaten.
     * @param deviceAddress Die Adresse des Geräts.
     * @return Ein [ScaleMeasurement] Objekt oder null, wenn das Parsen fehlschlägt.
     */
    private fun parseMeasurementData(characteristicUuid: UUID, value: ByteArray, deviceAddress: String): ScaleMeasurement? {
        // Beispielhafte, sehr vereinfachte Parsing-Logik.
        // Die tatsächliche Implementierung hängt STARK vom jeweiligen Waagenprotokoll ab!
        val measurement = ScaleMeasurement()
        measurement.dateTime = Date() // Zeitstempel der App, Waage könnte eigenen haben

        try {
            when (characteristicUuid) {
                ScaleGattAttributes.WEIGHT_MEASUREMENT_UUID -> {
                    // Annahme: Bluetooth SIG Weight Scale Characteristic
                    // Byte 0: Flags
                    // Byte 1-2: Gewicht (LSB, MSB)
                    // ... weitere Felder je nach Flags (Timestamp, UserID, BMI, Height)
                    val flags = value[0].toInt()
                    val isImperial = (flags and 0x01) != 0 // Bit 0: 0 für kg/m, 1 für lb/in
                    val hasTimestamp = (flags and 0x02) != 0 // Bit 1
                    val hasUserID = (flags and 0x04) != 0    // Bit 2

                    var offset = 1
                    var weight = ((value[offset++].toInt() and 0xFF) or ((value[offset++].toInt() and 0xFF) shl 8)) / if (isImperial) 100.0f else 200.0f
                    if (isImperial) {
                        weight *= 0.453592f // lb in kg umrechnen
                    }
                    measurement.weight = weight.takeIf { it.isFinite() } ?: 0.0f
                    LogManager.d(TAG, "Geparsed Weight: ${measurement.weight} kg")

                    if (hasTimestamp) {
                        // Hier Timestamp parsen (7 Bytes)
                        offset += 7
                    }
                    if (hasUserID) {
                        val userId = value[offset].toInt()
                        // measurement.scaleUserIndex = userId // oder ähnliches Feld
                        LogManager.d(TAG, "Geparsed UserID from scale: $userId")
                    }
                    return measurement
                }
                ScaleGattAttributes.BODY_COMPOSITION_MEASUREMENT_UUID -> {
                    // Annahme: Bluetooth SIG Body Composition Characteristic
                    // Ähnlich komplex wie Weight, mit vielen optionalen Feldern
                    // Byte 0-1: Flags
                    // Byte 2-3: Body Fat Percentage
                    // ... viele weitere Felder (Timestamp, UserID, Basal Metabolism, Muscle Percentage, etc.)
                    // Diese Implementierung ist nur ein Platzhalter!
                    LogManager.d(TAG, "Body Composition Data empfangen, Parsing noch nicht voll implementiert.")
                    val bodyFatPercentage = ((value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)) / 10.0f
                    measurement.fat = bodyFatPercentage.takeIf { it.isFinite() } ?: 0.0f
                    // Setze ein beliebiges Gewicht, da Body Comp oft kein Gewicht enthält
                    // Besser wäre es, Messungen zu kombinieren oder auf eine vorherige Gewichtsmessung zu warten.
                    if (measurement.weight == 0.0f) measurement.weight = 70.0f // Platzhalter
                    return measurement
                }
                else -> {
                    LogManager.w(TAG, "Keine Parsing-Logik für UUID: $characteristicUuid")
                    return null
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Fehler beim Parsen der Messdaten für $characteristicUuid: ${value.toHexString()}", e)
            return null
        }
    }


    override fun disconnect() {
        adapterScope.launch {
            LogManager.i(TAG, "Disconnect aufgerufen für $targetAddress")
            disconnectLogic()
        }
    }

    private fun disconnectLogic() {
        currentPeripheral?.let {
            // Notifications deaktivieren, bevor die Verbindung getrennt wird? Optional.
            // disableNotifications(it, ScaleGattAttributes.WEIGHT_SCALE_SERVICE_UUID, ScaleGattAttributes.WEIGHT_MEASUREMENT_UUID)
            // disableNotifications(it, ScaleGattAttributes.BODY_COMPOSITION_SERVICE_UUID, ScaleGattAttributes.BODY_COMPOSITION_MEASUREMENT_UUID)
            central.cancelConnection(it)
        }
        // Cleanup wird im onDisconnectedPeripheral Callback oder hier als Fallback gemacht,
        // falls der Callback aus irgendeinem Grund nicht kommt.
        val addr = targetAddress
        if (_isConnected.value || _isConnecting.value) {
            if (addr != null) {
                // Event wird in onDisconnectedPeripheral gesendet, aber als Fallback, falls der Callback nicht kommt
                // _eventsFlow.tryEmit(BluetoothEvent.Disconnected(addr, "Manuell getrennt durch disconnectLogic"))
            }
        }
        cleanupAfterDisconnect(addr) // Rufe cleanup auf, um sicherzustellen, dass die Zustände zurückgesetzt werden.
    }

    private fun cleanupAfterDisconnect(disconnectedAddress: String?) {
        // Nur aufräumen, wenn die Adresse mit dem Ziel übereinstimmt oder wenn targetAddress null ist (z.B. nach fehlgeschlagenem Scan)
        if (targetAddress == null || targetAddress == disconnectedAddress) {
            _isConnected.value = false
            _isConnecting.value = false
            currentPeripheral = null // Referenz auf Peripheral entfernen
            targetAddress = null
            currentScaleUser = null
            currentAppUserId = null
            LogManager.d(TAG, "Blessed Communicator aufgeräumt für Adresse: $disconnectedAddress.")
        } else {
            LogManager.d(TAG, "Cleanup übersprungen: Disconnected Address ($disconnectedAddress) stimmt nicht mit Target ($targetAddress) überein.")
        }
    }

    override fun requestMeasurement() {
        adapterScope.launch {
            if (!_isConnected.value || currentPeripheral == null) {
                LogManager.w(TAG, "requestMeasurement: Nicht verbunden oder kein Peripheral.")
                _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Nicht verbunden für Messanfrage.", targetAddress ?: "unbekannt"))
                return@launch
            }
            // Die meisten BLE-Waagen senden Daten automatisch nach Aktivierung der Notifications.
            // Eine explizite "Anfrage" ist oft nicht nötig oder nicht standardisiert.
            // Falls Ihr Gerät eine spezielle Characteristic zum Anfordern von Daten hat,
            // könnten Sie hier darauf schreiben.
            LogManager.d(TAG, "requestMeasurement aufgerufen. Für BLE typischerweise keine explizite Aktion nötig, Daten kommen über Notifications.")
            _eventsFlow.tryEmit(BluetoothEvent.DeviceMessage("Messdaten werden erwartet (BLE Notifications).", targetAddress!!))
        }
    }

    fun release() {
        LogManager.d(TAG, "BlessedScaleAdapter wird freigegeben.")
        disconnectLogic()
        // Blessed CentralManager hat keine explizite close() oder release() Methode für sich selbst,
        // die Verbindungen werden über cancelConnection() verwaltet.
        // Der Handler wird implizit mit dem Context-Lifecycle verwaltet.
        adapterScope.cancel()
    }
}

// Hilfsfunktion zum Konvertieren eines ByteArrays in einen Hex-String (nützlich für Logging)
fun ByteArray.toHexString(): String = joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
