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

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.content.Context.LOCATION_SERVICE;

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
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionState;
import com.welie.blessed.GattStatus;
import com.welie.blessed.HciStatus;
import com.welie.blessed.WriteType;

import java.util.UUID;

import timber.log.Timber;

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
        SCALE_MESSAGE,
        CHOOSE_SCALE_USER,
        ENTER_SCALE_USER_CONSENT,
    }

    private int stepNr;
    private boolean stopped;

    protected Context context;

    private Handler callbackBtHandler;
    private Handler disconnectHandler;

    private BluetoothCentralManager central;
    private BluetoothPeripheral btPeripheral;

    public BluetoothCommunication(Context context, String deviceName)
    {
        this.context = context;
        this.disconnectHandler = new Handler();
        this.stepNr = 0;
        this.stopped = false;
        this.central = new BluetoothCentralManager(context, bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
    }

    public BluetoothCommunication(Context context)
    {
        this(context, "");
    }

    protected boolean needReConnect() {
        if (callbackBtHandler == null) {
            return true;
        }
        if (btPeripheral != null) {
            ConnectionState state = btPeripheral.getState();
            if (state.equals(ConnectionState.CONNECTED) || state.equals(ConnectionState.CONNECTING)) {
                return false;
            }
        }
        return true;
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

    protected void chooseScaleUserUi(Object userList) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS.CHOOSE_SCALE_USER.ordinal(), userList).sendToTarget();
        }
    }

    protected void enterScaleUserConsentUi(int appScaleUserId, int scaleUserIndex) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS.ENTER_SCALE_USER_CONSENT.ordinal(), appScaleUserId, scaleUserIndex).sendToTarget();
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

    /**
     * Stopped current state machine
     */
    protected synchronized void stopMachineState() {
        Timber.d("Stop machine state");
        stopped = true;
    }

    /**
     * resume current state machine
     */
    protected synchronized void resumeMachineState() {
        Timber.d("Resume machine state");
        stopped = false;
        nextMachineStep();
    }

    /**
     * This function only resumes the state machine if the current step equals curStep,
     * i.e. if the next step (stepNr) is 1 above curStep.
     */
    protected synchronized boolean resumeMachineState( int curStep ) {
        if( curStep == stepNr-1 ) {
            Timber.d("curStep " + curStep + " matches stepNr " + stepNr + "-1, resume state machine.");
            stopped = false;
            nextMachineStep();
            return true;
        }
        else {
            Timber.d("curStep " + curStep + " does not match stepNr " + stepNr + "-1, not resuming state machine.");
            return false;
        }
    }

    /**
     * This function jump to a specific step number
     * @param nr the step number which the state machine should jump to.
     */
    protected synchronized void jumpNextToStepNr(int nr) {
        Timber.d("Jump next to step nr " + nr);
        stepNr = nr;
    }

    /**
     * This function return the current step number
     * @return the current step number
     */
    protected synchronized int getStepNr() {
        return stepNr;
    }

    /**
     * This function jumps to the step newStepNr only if the current step equals curStepNr,
     * i.e. if the next step (stepNr) is 1 above curStepNr
     */
    protected synchronized boolean jumpNextToStepNr( int curStepNr, int newStepNr ) {
        if( curStepNr == stepNr-1 ) {
            Timber.d("curStepNr " + curStepNr + " matches stepNr " + stepNr + "-1, jumping next to step nr " + newStepNr);
            stepNr = newStepNr;
            return true;
        }
        else {
            Timber.d("curStepNr " + curStepNr + " does not match stepNr " + stepNr + "-1, keeping next at step nr " + stepNr);
            return false;
        }
    }

    /**
     * Call this function to decrement the current step counter of the state machine by one.
     * Usually, if you call this function followed by resumeMachineState(), the current step will be repeated.
     * Call multiple times to actually go back in time to previous steps.
     */
    protected synchronized void jumpBackOneStep() {
        stepNr--;
        Timber.d("Jumped back one step to " + stepNr);
    }

    /**
     * Check if specific characteristic exists on Bluetooth device.
     *
     * @param service the Bluetooth UUID service
     * @param characteristic the Bluetooth UUID characteristic
     * @return true if characteristic exists
     */
    protected boolean haveCharacteristic(UUID service, UUID characteristic) {
        return btPeripheral.getCharacteristic(service, characteristic) != null;
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
                noResponse ? WriteType.WITHOUT_RESPONSE : WriteType.WITH_RESPONSE);
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothRead() will be triggered if read command was successful. nextMachineStep() needs to manually called!
     *@param characteristic the Bluetooth UUID characteristic
     */
    protected void readBytes(UUID service, UUID characteristic) {
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
     * @return true if the operation was enqueued, false if the characteristic doesn't support notification or indications or
     */
    protected boolean setNotificationOn(UUID service, UUID characteristic) {
        Timber.d("Invoke set notification on " + BluetoothGattUuid.prettyPrint(characteristic));
        if(btPeripheral.getService(service) != null) {
            BluetoothGattCharacteristic currentTimeCharacteristic = btPeripheral.getCharacteristic(service, characteristic);
            if (currentTimeCharacteristic != null) {
                boolean notifySet;
                try {
                    notifySet = btPeripheral.setNotify(currentTimeCharacteristic, true);
                }
                catch (IllegalArgumentException e){
                    notifySet = false;
                };
                if (notifySet) {
                    stopMachineState();
                    return true;
                }
            }
        }
        return false;
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

    public void selectScaleUserIndexForAppUserId(int appUserId, int scaleUserIndex, Handler uiHandler) {
        Timber.d("Set scale user index for app user id: Not implemented!");
    }

    public void setScaleUserConsent(int appUserId, int scaleUserConsent, Handler uiHandler) {
        Timber.d("Set scale user consent for app user id: Not implemented!");
    }

    // +++
    public byte[] getScaleMacAddress() {
        String[] mac = btPeripheral.getAddress().split(":");
        byte[] macAddress = new byte[6];
        for(int i = 0; i < mac.length; i++) {
            macAddress[i] = Integer.decode("0x" + mac[i]).byteValue();
        }
        return macAddress;
    }
    // ---

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
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, GattStatus status) {
            if( status.value == GATT_SUCCESS) {
                if(peripheral.isNotifying(characteristic)) {
                    Timber.d(String.format("SUCCESS: Notify set for %s", characteristic.getUuid()));
                    resumeMachineState();
                }
            } else {
                Timber.e(String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, GattStatus status) {
            if( status.value == GATT_SUCCESS) {
                Timber.d(String.format("SUCCESS: Writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()));
                nextMachineStep();

            } else {
                Timber.e(String.format("ERROR: Failed writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()));
            }
        }

        @Override
        public void onCharacteristicUpdate(final BluetoothPeripheral peripheral, byte[] value, final BluetoothGattCharacteristic characteristic, GattStatus status) {
            resetDisconnectTimer();
            onBluetoothNotify(characteristic.getUuid(), value);
        }
    };

    // Callback for central
    private final BluetoothCentralManagerCallback bluetoothCentralCallback = new BluetoothCentralManagerCallback() {

        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            Timber.d(String.format("connected to '%s'", peripheral.getName()));
            setBluetoothStatus(BT_STATUS.CONNECTION_ESTABLISHED);
            btPeripheral = peripheral;
            nextMachineStep();
            resetDisconnectTimer();
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, HciStatus status) {
            Timber.e(String.format("connection '%s' failed with status %d", peripheral.getName(), status.value));
            setBluetoothStatus(BT_STATUS.CONNECTION_LOST);

            if (status.value == 8) {
                sendMessage(R.string.info_bluetooth_connection_error_scale_offline, 0);
            }
        }

        @Override
        public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, HciStatus status) {
            Timber.d(String.format("disconnected '%s' with status %d", peripheral.getName(), status.value));
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

        if ((ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)  == PackageManager.PERMISSION_GRANTED) ||
            (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ) &&
            (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)))
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

    protected boolean reConnectPreviousPeripheral(Handler uiHandler) {
        if (btPeripheral == null) {
            return false;
        }
        if (btPeripheral.getState() != ConnectionState.DISCONNECTED) {
            disconnect();
        }
        if (callbackBtHandler == null) {
            registerCallbackHandler(uiHandler);
        }
        connect(btPeripheral.getAddress());
        return true;
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
