/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;

import java.util.UUID;

import timber.log.Timber;

import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
import static android.content.Context.LOCATION_SERVICE;
import static com.welie.blessed.BluetoothPeripheral.GATT_SUCCESS;

public abstract class BluetoothCommunication {
    public enum BT_STATUS {
        RETRIEVE_SCALE_DATA,
        INIT_PROCESS,
        CONNECTION_RETRYING,
        CONNECTION_ESTABLISHED,
        CONNECTION_DISCONNECT,
        CONNECTION_LOST,
        NO_DEVICE_FOUND,
        UNEXPECTED_ERROR,
        SCALE_MESSAGE
    }

    private int stepNr;
    private boolean stopped;

    protected Context context;

    private Handler callbackBtHandler;
    private Handler disconnectHandler;

    private BluetoothCentral central;
    private BluetoothPeripheral btPeripheral;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        this.disconnectHandler = new Handler();
        this.stepNr = 0;
        this.stopped = false;
        this.central = new BluetoothCentral(context, bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
    }

    /**
     * Register a callback Bluetooth handler that notify any BT_STATUS changes for GUI/CORE.
     *
     * @param cbBtHandler a handler that is registered
     */
    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param status the status code that should be set
     */
    protected void setBluetoothStatus(BT_STATUS status) {
        setBluetoothStatus(status, "");
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     * @param infoText the information text that is displayed to the status code.
     */
    protected void setBluetoothStatus(BT_STATUS statusCode, String infoText) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    statusCode.ordinal(), infoText).sendToTarget();
        }
    }

    /**
     * Add a new scale data to openScale
     *
     * @param scaleMeasurement the scale data that should be added to openScale
     */
    protected void addScaleMeasurement(ScaleMeasurement scaleMeasurement) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS.RETRIEVE_SCALE_DATA.ordinal(), scaleMeasurement).sendToTarget();
        }
    }

    /**
     * Send message to openScale user
     *
     * @param msg the string id to be send
     * @param value the value to be used
     */
    protected void sendMessage(int msg, Object value) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS.SCALE_MESSAGE.ordinal(), msg, 0, value).sendToTarget();
        }
    }

    /**
     * Return the Bluetooth driver name
     *
     * @return a string in a human readable name
     */
    abstract public String driverName();

    /**
     * State machine for the initialization process of the Bluetooth device.
     *
     * @param stepNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean onNextStep(int stepNr);

    /**
     * Method is triggered if a Bluetooth data from a device is notified or indicated.
     *
     * @param characteristic
     * @param value the Bluetooth characteristic
     */
    protected void onBluetoothNotify(UUID characteristic, byte[] value) {}

    /**
     * Method is triggered if a Bluetooth services from a device is discovered.
     *
     * @param peripheral
     */
    protected void onBluetoothDiscovery(BluetoothPeripheral peripheral) { }

    protected synchronized void stopMachineState() {
        Timber.d("Stop machine state");
        stopped = true;
    }

    protected synchronized void resumeMachineState() {
        Timber.d("Resume machine state");
        stopped = false;
        nextMachineStep();
    }

    protected synchronized void jumpNextToStepNr(int nr) {
        Timber.d("Jump next to step nr " + nr);
        stepNr = nr;
    }

    /**
     * Write a byte array to a Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     * @param bytes          the bytes that should be write
     */
    protected void writeBytes(UUID service, UUID characteristic, byte[] bytes) {
        writeBytes(service, characteristic, bytes, false);
    }

    /**
     * Write a byte array to a Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     * @param bytes          the bytes that should be write
     * @param noResponse     true if no response is required
     */
    protected void writeBytes(UUID service, UUID characteristic, byte[] bytes, boolean noResponse) {
        Timber.d("Invoke write bytes [" + byteInHex(bytes) + "] on " + BluetoothGattUuid.prettyPrint(characteristic));
        btPeripheral.writeCharacteristic(btPeripheral.getCharacteristic(service, characteristic), bytes,
                noResponse ? WRITE_TYPE_NO_RESPONSE : WRITE_TYPE_DEFAULT);
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothRead() will be triggered if read command was successful. nextMachineStep() needs to manually called!
     *@param characteristic the Bluetooth UUID characteristic
     */
    void readBytes(UUID service, UUID characteristic) {
        Timber.d("Invoke read bytes on " + BluetoothGattUuid.prettyPrint(characteristic));

        btPeripheral.readCharacteristic(btPeripheral.getCharacteristic(service, characteristic));
    }

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setIndicationOn(UUID service, UUID characteristic) {
        Timber.d("Invoke set indication on " + BluetoothGattUuid.prettyPrint(characteristic));
        if(btPeripheral.getService(service) != null) {
            stopMachineState();
            BluetoothGattCharacteristic currentTimeCharacteristic = btPeripheral.getCharacteristic(service, characteristic);
            btPeripheral.setNotify(currentTimeCharacteristic, true);
        }
    }

    /**
     * Set notification flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setNotificationOn(UUID service, UUID characteristic) {
        Timber.d("Invoke set notification on " + BluetoothGattUuid.prettyPrint(characteristic));
        if(btPeripheral.getService(service) != null) {
            stopMachineState();
            BluetoothGattCharacteristic currentTimeCharacteristic = btPeripheral.getCharacteristic(service, characteristic);
            btPeripheral.setNotify(currentTimeCharacteristic, true);
        }
    }

    /**
     * Disconnect from a Bluetooth device
     */
    public void disconnect() {
        Timber.d("Bluetooth disconnect");
        setBluetoothStatus(BT_STATUS.CONNECTION_DISCONNECT);
        try {
            central.stopScan();
        } catch (Exception ex) {
            Timber.e("Error on Bluetooth disconnecting " + ex.getMessage());
        }

        if (btPeripheral != null) {
            central.cancelConnection(btPeripheral);
        }
        callbackBtHandler = null;
        disconnectHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Convert a byte array to hex for debugging purpose
     *
     * @param data data we want to make human-readable (hex)
     * @return a human-readable string representing the content of 'data'
     */
    protected String byteInHex(byte[] data) {
        if (data == null) {
            Timber.e("Data is null");
            return "";
        }

        if (data.length == 0) {
            return "";
        }

        final StringBuilder stringBuilder = new StringBuilder(3 * data.length);
        for (byte byteChar : data) {
            stringBuilder.append(String.format("%02X ", byteChar));
        }

        return stringBuilder.substring(0, stringBuilder.length() - 1);
    }

    protected float clamp(double value, double min, double max) {
        if (value < min) {
            return (float)min;
        }
        if (value > max) {
            return (float)max;
        }
        return (float)value;
    }

    protected byte xorChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = offset; i < offset + length; ++i) {
            checksum ^= data[i];
        }
        return checksum;
    }

    protected byte sumChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = offset; i < offset + length; ++i) {
            checksum += data[i];
        }
        return checksum;
    }

    /**
     * Test in a byte if a bit is set (1) or not (0)
     *
     * @param value byte which is tested
     * @param bit bit position which is tested
     * @return true if bit is set (1) otherwise false (0)
     */
    protected boolean isBitSet(byte value, int bit) {
        return (value & (1 << bit)) != 0;
    }

    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(BluetoothPeripheral peripheral) {
            Timber.d("Successful Bluetooth services discovered");
            onBluetoothDiscovery(peripheral);
            resumeMachineState();
        }

        @Override
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, int status) {
            if( status == GATT_SUCCESS) {
                if(peripheral.isNotifying(characteristic)) {
                    Timber.d(String.format("SUCCESS: Notify set for %s", characteristic.getUuid()));
                    resumeMachineState();
                }
            } else {
                Timber.e(String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            if( status == GATT_SUCCESS) {
                Timber.d(String.format("SUCCESS: Writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()));
                nextMachineStep();

            } else {
                Timber.e(String.format("ERROR: Failed writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()));
            }
        }

        @Override
        public void onCharacteristicUpdate(final BluetoothPeripheral peripheral, byte[] value, final BluetoothGattCharacteristic characteristic, final int status) {
            resetDisconnectTimer();
            onBluetoothNotify(characteristic.getUuid(), value);
        }
    };

    // Callback for central
    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {

        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            Timber.d(String.format("connected to '%s'", peripheral.getName()));
            setBluetoothStatus(BT_STATUS.CONNECTION_ESTABLISHED);
            btPeripheral = peripheral;
            nextMachineStep();
            resetDisconnectTimer();
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, final int status) {
            Timber.e(String.format("connection '%s' failed with status %d", peripheral.getName(), status ));
            setBluetoothStatus(BT_STATUS.CONNECTION_LOST);

            if (status == 8) {
                sendMessage(R.string.info_bluetooth_connection_error_scale_offline, 0);
            }
        }

        @Override
        public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, final int status) {
            Timber.d(String.format("disconnected '%s' with status %d", peripheral.getName(), status));
        }

        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            Timber.d(String.format("Found peripheral '%s'", peripheral.getName()));
            central.stopScan();
            connectToDevice(peripheral);
        }
    };

    /**
     * Connect to a Bluetooth device.
     *
     * On successfully connection Bluetooth machine state is automatically triggered.
     * If the device is not found the process is automatically stopped.
     *
     * @param macAddress the Bluetooth address to connect to
     */
    public void connect(String macAddress) {
        // Running an LE scan during connect improves connectivity on some phones
        // (e.g. Sony Xperia Z5 compact, Android 7.1.1). For some scales (e.g. Medisana BS444)
        // it seems to be a requirement that the scale is discovered before connecting to it.
        // Otherwise the connection almost never succeeds.
        LocationManager locationManager = (LocationManager)context.getSystemService(LOCATION_SERVICE);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        ) {
            Timber.d("Do LE scan before connecting to device");
            central.scanForPeripheralsWithAddresses(new String[]{macAddress});
            stopMachineState();
        }
        else {
            Timber.d("No location permission, connecting without LE scan");
            BluetoothPeripheral peripheral = central.getPeripheral(macAddress);
            connectToDevice(peripheral);
        }
    }

    private void connectToDevice(BluetoothPeripheral peripheral) {

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("Try to connect to BLE device " + peripheral.getAddress());

                stepNr = 0;

                central.connectPeripheral(peripheral, peripheralCallback);
            }
        }, 1000);
    }

    private void resetDisconnectTimer() {
        disconnectHandler.removeCallbacksAndMessages(null);
        disconnectWithDelay();
    }

    private void disconnectWithDelay() {
        disconnectHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Timber.d("Timeout Bluetooth disconnect");
                disconnect();
            }
        }, 60000); // 60s timeout
    }

    private synchronized void nextMachineStep() {
        if (!stopped) {
            Timber.d("Step Nr " + stepNr);
            if (onNextStep(stepNr)) {
                stepNr++;
                nextMachineStep();
            } else {
                Timber.d("Invoke delayed disconnect in 60s");
                disconnectWithDelay();
            }
        }
    }
}
