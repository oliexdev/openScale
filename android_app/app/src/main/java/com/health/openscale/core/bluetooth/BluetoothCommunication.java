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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;

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

    protected Context context;

    private Handler callbackBtHandler;
    private BluetoothGatt bluetoothGatt;
    private boolean connectionEstablished;
    private BluetoothGattCallback gattCallback;
    protected BluetoothAdapter btAdapter;

    private int cmdStepNr;
    private int initStepNr;
    private int cleanupStepNr;
    private BT_MACHINE_STATE btMachineState;

    private Queue<BluetoothGattDescriptor> descriptorRequestQueue;
    private Queue<BluetoothGattCharacteristic> characteristicRequestQueue;
    private boolean openRequest;
    private final Object lock = new Object();

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        gattCallback = new GattCallback();
        bluetoothGatt = null;
        connectionEstablished = false;
    }

    protected List<BluetoothGattService> getBluetoothGattServices() {
        if (bluetoothGatt == null) {
            return new ArrayList<>();
        }

        return bluetoothGatt.getServices();
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
        callbackBtHandler.obtainMessage(statusCode.ordinal(), infoText).sendToTarget();
    }

    /**
     * Add a new scale data to openScale
     *
     * @param scaleMeasurement the scale data that should be added to openScale
     */
    protected void addScaleData(ScaleMeasurement scaleMeasurement) {
        callbackBtHandler.obtainMessage(BT_STATUS_CODE.BT_RETRIEVE_SCALE_DATA.ordinal(), scaleMeasurement).sendToTarget();
    }

    /**
     * Send message to openScale user
     *
     * @param msg the string id to be send
     * @param value the value to be used
     */
    protected void sendMessage(int msg, Object value) {
        callbackBtHandler.obtainMessage(BT_STATUS_CODE.BT_SCALE_MESSAGE.ordinal(), msg, 0,  value).sendToTarget();
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
        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        gattCharacteristic.setValue(bytes);
        synchronized (lock) {
             characteristicRequestQueue.add(gattCharacteristic);
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

        bluetoothGatt.readCharacteristic(gattCharacteristic);
    }

    protected void readBytes(UUID service, UUID characteristic, UUID descriptor) {
        BluetoothGattDescriptor gattDescriptor = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic).getDescriptor(descriptor);

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

        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        synchronized (lock) {
            descriptorRequestQueue.add(gattDescriptor);
            handleRequests();
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

        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        synchronized (lock) {
            descriptorRequestQueue.add(gattDescriptor);
            handleRequests();
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

        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, false);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        synchronized (lock) {
            descriptorRequestQueue.add(gattDescriptor);
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

        final StringBuilder stringBuilder = new StringBuilder(3 * data.length);
        for (byte byteChar : data) {
            stringBuilder.append(String.format("%02X ", byteChar));
        }

        return stringBuilder.toString();
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
        Timber.i("Connecting to [%s] (driver: %s)", hwAddress, driverName());

        btAdapter.cancelDiscovery();

        // Don't do any cleanup if disconnected before fully connected
        btMachineState = BT_MACHINE_STATE.BT_CLEANUP_STATE;

        BluetoothDevice device = btAdapter.getRemoteDevice(hwAddress);
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    /**
     * Disconnect from a Bluetooth device
     */
    public void disconnect(boolean doCleanup) {
        if (bluetoothGatt == null) {
            return;
        }

        Timber.i("Disconnecting%s", doCleanup ? " (with cleanup)" : "");

        if (doCleanup) {
            synchronized (lock) {
                if (btMachineState != BT_MACHINE_STATE.BT_CLEANUP_STATE) {
                    setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE);
                    nextMachineStateStep();
                }
            }
        }

        bluetoothGatt.disconnect();
        bluetoothGatt.close();
        bluetoothGatt = null;
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
                return; // yes, do nothing
            }

            // handle descriptor requests first
            BluetoothGattDescriptor descriptorRequest = descriptorRequestQueue.poll();
            if (descriptorRequest != null) {
                Timber.d("Write descriptor %s: %s",
                        descriptorRequest.getUuid(), byteInHex(descriptorRequest.getValue()));
                if (!bluetoothGatt.writeDescriptor(descriptorRequest)) {
                    Timber.e("Failed to initiate write of descriptor %s",
                            descriptorRequest.getUuid());
                }
                openRequest = true;
                return;
            }

            // handle characteristics requests second
            BluetoothGattCharacteristic characteristicRequest = characteristicRequestQueue.poll();
            if (characteristicRequest != null) {
                Timber.d("Write characteristic %s: %s",
                        characteristicRequest.getUuid(), byteInHex(characteristicRequest.getValue()));
                if (!bluetoothGatt.writeCharacteristic(characteristicRequest)) {
                    Timber.e("Failed to initiate write of characteristic %s",
                            characteristicRequest.getUuid());
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
                connectionEstablished = true;
                setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);
                gatt.discoverServices();
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
            Timber.d("onServicesDiscovered: status=%d", status);

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

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            synchronized (lock) {
                openRequest = false;
                handleRequests();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            synchronized (lock) {
                openRequest = false;
                handleRequests();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Timber.d("onCharacteristicRead %s (status=%d): %s",
                    characteristic.getUuid(), status, byteInHex(characteristic.getValue()));

            synchronized (lock) {
                onBluetoothDataRead(gatt, characteristic, status);
                openRequest = false;
                handleRequests();
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

            synchronized (lock) {
                openRequest = false;
                handleRequests();
            }
        }

    }
}
