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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.content.ContextCompat;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import timber.log.Timber;

public abstract class BluetoothCommunication {
    public enum BT_STATUS_CODE {BT_RETRIEVE_SCALE_DATA, BT_INIT_PROCESS, BT_CONNECTION_ESTABLISHED,
        BT_CONNECTION_LOST, BT_NO_DEVICE_FOUND, BT_UNEXPECTED_ERROR, BT_SCALE_MESSAGE
    }

    public enum BT_MACHINE_STATE {BT_INIT_STATE, BT_CMD_STATE, BT_CLEANUP_STATE}

    private static final long LE_SCAN_TIMEOUT_MS = 10 * 1000;

    protected Context context;

    private Handler callbackBtHandler;
    private Handler handler;
    private BluetoothGatt bluetoothGatt;
    private boolean connectionEstablished;
    private BluetoothGattCallback gattCallback;
    private BluetoothAdapter.LeScanCallback leScanCallback;
    protected BluetoothAdapter btAdapter;

    private int cmdStepNr;
    private int initStepNr;
    private int cleanupStepNr;
    private BT_MACHINE_STATE btMachineState;

    private class GattObjectValue <GattObject> {
        public final GattObject gattObject;
        public final byte[] value;

        public GattObjectValue(GattObject gattObject, byte[] value) {
            this.gattObject = gattObject;
            this.value = value;
        }
    }

    private Queue<GattObjectValue<BluetoothGattDescriptor>> descriptorRequestQueue;
    private Queue<GattObjectValue<BluetoothGattCharacteristic>> characteristicRequestQueue;
    private boolean openRequest;
    private final Object lock = new Object();

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        handler = new Handler();
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        gattCallback = new GattCallback();
        bluetoothGatt = null;
        leScanCallback = null;
        connectionEstablished = false;
    }

    protected List<BluetoothGattService> getBluetoothGattServices() {
        if (bluetoothGatt == null) {
            return new ArrayList<>();
        }

        return bluetoothGatt.getServices();
    }

    protected boolean hasBluetoothGattService(UUID service) {
        return bluetoothGatt != null && bluetoothGatt.getService(service) != null;
    }

    /**
     * Register a callback Bluetooth handler that notify any BT_STATUS_CODE changes for GUI/CORE.
     *
     * @param cbBtHandler a handler that is registered
     */
    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     */
    protected void setBtStatus(BT_STATUS_CODE statusCode) {
        setBtStatus(statusCode, "");
    }

    /**
     * Set for the openScale GUI/CORE the Bluetooth status code.
     *
     * @param statusCode the status code that should be set
     * @param infoText the information text that is displayed to the status code.
     */
    protected void setBtStatus(BT_STATUS_CODE statusCode, String infoText) {
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
    protected void addScaleData(ScaleMeasurement scaleMeasurement) {
        if (callbackBtHandler != null) {
            callbackBtHandler.obtainMessage(
                    BT_STATUS_CODE.BT_RETRIEVE_SCALE_DATA.ordinal(), scaleMeasurement).sendToTarget();
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
                    BT_STATUS_CODE.BT_SCALE_MESSAGE.ordinal(), msg, 0, value).sendToTarget();
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
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextInitCmd(int stateNr);

    /**
     * State machine for the normal/command process of the Bluetooth device.
     *
     * This state machine is automatically triggered if initialization process is finished.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextBluetoothCmd(int stateNr);

    /**
     * Set the next command number of the current state.
     *
     * @param nextCommand next command to select
     */
    protected void setNextCmd(int nextCommand) {
        switch (btMachineState) {
            case BT_INIT_STATE:
                initStepNr = nextCommand - 1;
                break;
            case BT_CMD_STATE:
                cmdStepNr = nextCommand - 1;
                break;
            case BT_CLEANUP_STATE:
                cleanupStepNr = nextCommand - 1;
                break;
        }
    }

    /**
     * State machine for the clean up process for the Bluetooth device.
     *
     * This state machine is *not* automatically triggered. You have to setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE) to trigger this process if necessary.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract protected boolean nextCleanUpCmd(int stateNr);

    /**
     * Method is triggered if a Bluetooth data is read from a device.
     *
     * @param bluetoothGatt the Bluetooth Gatt
     * @param gattCharacteristic the Bluetooth Gatt characteristic
     * @param status the status code
     */
    protected void onBluetoothDataRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic, int status) {}

    /**
     * Method is triggered if a Bluetooth data from a device is notified or indicated.
     *
     * @param bluetoothGatt the Bluetooth Gatt
     * @param gattCharacteristic the Bluetooth characteristic
     */
    protected void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {}

    /**
     * Set the Bluetooth machine state to a specific state.
     *
     * @note after setting a new state the next step is automatically triggered.
     *
     * @param btMachineState the machine state that should be set.
     */
    protected void setBtMachineState(BT_MACHINE_STATE btMachineState) {
        synchronized (lock) {
            this.btMachineState = btMachineState;
            handleRequests();
        }
    }

    /**
     * Write a byte array to a Bluetooth device.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     * @param bytes the bytes that should be write
     */
    protected void writeBytes(UUID service, UUID characteristic, byte[] bytes) {
        synchronized (lock) {
             characteristicRequestQueue.add(
                     new GattObjectValue<>(
                             bluetoothGatt.getService(service).getCharacteristic(characteristic),
                             bytes));
             handleRequests();
        }
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothDataRead() will be triggered if read command was successful.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void readBytes(UUID service, UUID characteristic) {
        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        Timber.d("Read characteristic %s", characteristic);
        bluetoothGatt.readCharacteristic(gattCharacteristic);
    }

    protected void readBytes(UUID service, UUID characteristic, UUID descriptor) {
        BluetoothGattDescriptor gattDescriptor = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic).getDescriptor(descriptor);

        Timber.d("Read descriptor %s", descriptor);
        bluetoothGatt.readDescriptor(gattDescriptor);
    }

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setIndicationOn(UUID service, UUID characteristic, UUID descriptor) {
        Timber.d("Set indication on for %s", characteristic);

        try {
            BluetoothGattCharacteristic gattCharacteristic =
                    bluetoothGatt.getService(service).getCharacteristic(characteristic);
            bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

            synchronized (lock) {
                descriptorRequestQueue.add(
                        new GattObjectValue<>(
                                gattCharacteristic.getDescriptor(descriptor),
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE));
                handleRequests();
            }
        }
        catch (Exception e) {
            Timber.e(e);
        }
    }

    /**
     * Set notification flag on for the Bluetooth device.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setNotificationOn(UUID service, UUID characteristic, UUID descriptor) {
        Timber.d("Set notification on for %s", characteristic);

        try {
            BluetoothGattCharacteristic gattCharacteristic =
                    bluetoothGatt.getService(service).getCharacteristic(characteristic);
            bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

            synchronized (lock) {
                descriptorRequestQueue.add(
                        new GattObjectValue<>(
                                gattCharacteristic.getDescriptor(descriptor),
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
                handleRequests();
            }
        }
        catch (Exception e) {
            Timber.e(e);
        }
    }

    /**
     * Set notification flag off for the Bluetooth device.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setNotificationOff(UUID service, UUID characteristic, UUID descriptor) {
        Timber.d("Set notification off for %s", characteristic);

        BluetoothGattCharacteristic gattCharacteristic =
                bluetoothGatt.getService(service).getCharacteristic(characteristic);
        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, false);

        synchronized (lock) {
            descriptorRequestQueue.add(
                    new GattObjectValue<>(
                            gattCharacteristic.getDescriptor(descriptor),
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
            handleRequests();
        }
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

    protected byte xorChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = offset; i < offset + length; ++i) {
            checksum ^= data[i];
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

    /**
     * Connect to a Bluetooth device.
     *
     * On successfully connection Bluetooth machine state is automatically triggered.
     * If the device is not found the process is automatically stopped.
     *
     * @param hwAddress the Bluetooth address to connect to
     */
    public void connect(String hwAddress) {
        logBluetoothStatus();

        // Some good tips to improve BLE connections:
        // https://android.jlelse.eu/lessons-for-first-time-android-bluetooth-le-developers-i-learned-the-hard-way-fee07646624

        btAdapter.cancelDiscovery();
        stopLeScan();

        // Don't do any cleanup if disconnected before fully connected
        btMachineState = BT_MACHINE_STATE.BT_CLEANUP_STATE;

        // Running an LE scan during connect improves connectivity on some phones
        // (e.g. Sony Xperia Z5 compact, Android 7.1.1). For some scales (e.g. Medisana BS444)
        // it seems to be a requirement that the scale is discovered before connecting to it.
        // Otherwise the connection almost never succeeds.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLeScanForDevice(hwAddress);
        }
        else {
            Timber.d("No coarse location permission, connecting without LE scan");
            connectGatt(hwAddress);
        }
    }

    private void logBluetoothStatus() {
        Timber.d("BT is%s enabled, state=%d, scan mode=%d, is%s discovering",
                btAdapter.isEnabled() ? "" : " not", btAdapter.getState(),
                btAdapter.getScanMode(), btAdapter.isDiscovering() ? "" : " not");

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
            Timber.d("Connected GATT device: %s [%s]",
                    device.getName(), device.getAddress());
        }
        for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)) {
            Timber.d("Connected GATT_SERVER device: %s [%s]",
                    device.getName(), device.getAddress());
        }
    }

    private void connectGatt(BluetoothDevice device) {
        Timber.i("Connecting to [%s] (driver: %s)", device.getAddress(), driverName());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }
        else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    private void connectGatt(String hwAddress) {
        connectGatt(btAdapter.getRemoteDevice(hwAddress));
    }

    private void startLeScanForDevice(final String hwAddress) {
        leScanCallback = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                Timber.d("Found LE device %s [%s]", device.getName(), device.getAddress());
                if (!device.getAddress().equals(hwAddress)) {
                    return;
                }
                synchronized (lock) {
                    stopLeScan();
                    connectGatt(device);
                }
            }
        };

        Timber.d("Starting LE scan for device [%s]", hwAddress);
        btAdapter.startLeScan(leScanCallback);

        handler.postAtTime(new Runnable() {
            @Override
            public void run() {
                Timber.d("Device not found in LE scan, connecting directly");
                synchronized (lock) {
                    stopLeScan();
                    connectGatt(hwAddress);
                }
            }
        }, leScanCallback, SystemClock.uptimeMillis() + LE_SCAN_TIMEOUT_MS);
    }

    private void stopLeScan() {
        if (leScanCallback != null) {
            Timber.d("Stopping LE scan");
            btAdapter.stopLeScan(leScanCallback);
            handler.removeCallbacksAndMessages(leScanCallback);
            leScanCallback = null;
        }
    }

    /**
     * Disconnect from a Bluetooth device
     */
    public void disconnect(boolean doCleanup) {
        synchronized (lock) {
            stopLeScan();

            if (bluetoothGatt == null) {
                return;
            }

            Timber.i("Disconnecting%s", doCleanup ? " (with cleanup)" : "");

            handler.removeCallbacksAndMessages(null);
            callbackBtHandler = null;

            if (doCleanup) {
                if (btMachineState != BT_MACHINE_STATE.BT_CLEANUP_STATE) {
                    setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE);
                    nextMachineStateStep();
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            if (openRequest) {
                                handler.postDelayed(this, 10);
                            } else {
                                bluetoothGatt.close();
                                bluetoothGatt = null;
                            }
                        }
                    }
                });
            }
            else {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
    }

    /**
     * Invoke next step for internal Bluetooth state machine.
     */
    protected void nextMachineStateStep() {
        switch (btMachineState) {
            case BT_INIT_STATE:
                Timber.d("INIT STATE: %d", initStepNr);
                if (!nextInitCmd(initStepNr)) {
                    btMachineState = BT_MACHINE_STATE.BT_CMD_STATE;
                    nextMachineStateStep();
                }
                initStepNr++;
                break;
            case BT_CMD_STATE:
                Timber.d("CMD STATE: %d", cmdStepNr);
                nextBluetoothCmd(cmdStepNr);
                cmdStepNr++;
                break;
            case BT_CLEANUP_STATE:
                Timber.d("CLEANUP STATE: %d", cleanupStepNr);
                nextCleanUpCmd(cleanupStepNr);
                cleanupStepNr++;
                break;
        }
    }

    private void handleRequests() {
        synchronized (lock) {
            // check for pending request
            if (openRequest) {
                Timber.d("Request pending (queue %d %d)",
                        descriptorRequestQueue.size(), characteristicRequestQueue.size());
                return; // yes, do nothing
            }

            // handle descriptor requests first
            GattObjectValue<BluetoothGattDescriptor> descriptor = descriptorRequestQueue.poll();
            if (descriptor != null) {
                descriptor.gattObject.setValue(descriptor.value);

                Timber.d("Write descriptor %s: %s (queue: %d %d)",
                        descriptor.gattObject.getUuid(), byteInHex(descriptor.gattObject.getValue()),
                        descriptorRequestQueue.size(), characteristicRequestQueue.size());
                if (!bluetoothGatt.writeDescriptor(descriptor.gattObject)) {
                    Timber.e("Failed to initiate write of descriptor %s",
                            descriptor.gattObject.getUuid());
                }
                openRequest = true;
                return;
            }

            // handle characteristics requests second
            GattObjectValue<BluetoothGattCharacteristic> characteristic = characteristicRequestQueue.poll();
            if (characteristic != null) {
                characteristic.gattObject.setValue(characteristic.value);

                Timber.d("Write characteristic %s: %s (queue: %d %d)",
                        characteristic.gattObject.getUuid(), byteInHex(characteristic.gattObject.getValue()),
                        descriptorRequestQueue.size(), characteristicRequestQueue.size());
                if (!bluetoothGatt.writeCharacteristic(characteristic.gattObject)) {
                    Timber.e("Failed to initiate write of characteristic %s",
                            characteristic.gattObject.getUuid());
                }
                openRequest = true;
                return;
            }

            // After every command was executed, continue with the next step
            nextMachineStateStep();
        }
    }

    /**
     * Custom Gatt callback class to set up a Bluetooth state machine.
     */
    protected class GattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            Timber.d("onConnectionStateChange: status=%d, newState=%d", status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized (lock) {
                    stopLeScan();
                }

                connectionEstablished = true;
                setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);

                try {
                    Thread.sleep(1000);
                }
                catch (Exception e) {
                    // Empty
                }
                if (!gatt.discoverServices()) {
                    Timber.e("Could not start service discovery");
                    setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
                    disconnect(false);
                }
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setBtStatus(connectionEstablished
                        ? BT_STATUS_CODE.BT_CONNECTION_LOST
                        : BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
                disconnect(false);
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            Timber.d("onServicesDiscovered: status=%d (%d services)",
                    status, gatt.getServices().size());

            synchronized (lock) {
                cmdStepNr = 0;
                initStepNr = 0;
                cleanupStepNr = 0;

                // Clear from possible previous setups
                characteristicRequestQueue = new LinkedList<>();
                descriptorRequestQueue = new LinkedList<>();
                openRequest = false;
            }

            try {
                // Sleeping a while after discovering services fixes connection problems.
                // See https://github.com/NordicSemiconductor/Android-DFU-Library/issues/10
                // for some technical background.
                Thread.sleep(1000);
            }
            catch (Exception e) {
                // Empty
            }

            // Start the state machine
            setBtMachineState(BT_MACHINE_STATE.BT_INIT_STATE);
        }

        private void postDelayedHandleRequests() {
            // Wait a short while before starting the next operation as suggested
            // on the android.jlelse.eu link above.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        openRequest = false;
                        handleRequests();
                    }
                }
            }, 60);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            postDelayedHandleRequests();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            postDelayedHandleRequests();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Timber.d("onCharacteristicRead %s (status=%d): %s",
                    characteristic.getUuid(), status, byteInHex(characteristic.getValue()));

            synchronized (lock) {
                onBluetoothDataRead(gatt, characteristic, status);
                postDelayedHandleRequests();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            Timber.d("onCharacteristicChanged %s: %s",
                    characteristic.getUuid(), byteInHex(characteristic.getValue()));

            synchronized (lock) {
                onBluetoothDataChange(gatt, characteristic);
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt,
                                     BluetoothGattDescriptor descriptor,
                                     int status) {
            Timber.d("onDescriptorRead %s (status=%d): %s",
                    descriptor.getUuid(), status, byteInHex(descriptor.getValue()));

            postDelayedHandleRequests();
        }
    }
}
