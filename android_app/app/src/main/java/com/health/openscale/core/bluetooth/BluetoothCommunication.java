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
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleData;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public abstract class BluetoothCommunication {
    public enum BT_STATUS_CODE {BT_RETRIEVE_SCALE_DATA, BT_INIT_PROCESS, BT_CONNECTION_ESTABLISHED,
        BT_CONNECTION_LOST, BT_NO_DEVICE_FOUND, BT_UNEXPECTED_ERROR, BT_SCALE_MESSAGE
    };
    public enum BT_MACHINE_STATE {BT_INIT_STATE, BT_CMD_STATE, BT_CLEANUP_STATE}
    public enum BT_DEVICE_ID {CUSTOM_OPENSCALE, MI_SCALE_V1, MI_SCALE_V2, SANITAS_SBF70, MEDISANA_BS444, DIGOO_DGS038H, EXCELVANT_CF369BLE, YUNMAI_MINI, YUNMAI_SE, MGB, EXINGTECH_Y1}

    protected Context context;

    private Handler callbackBtHandler;
    private BluetoothGatt bluetoothGatt;
    protected BluetoothGattCallback gattCallback;
    private BluetoothAdapter.LeScanCallback scanCallback;
    protected BluetoothAdapter btAdapter;
    private Handler searchHandler;
    private String btDeviceName;

    private int cmdStepNr;
    private int initStepNr;
    private int cleanupStepNr;
    private BT_MACHINE_STATE btMachineState;

    private Queue<BluetoothGattDescriptor> descriptorRequestQueue;
    private Queue<BluetoothGattCharacteristic> characteristicRequestQueue;
    private Boolean openRequest;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        searchHandler = new Handler();
        scanCallback = null;
        gattCallback = new GattCallback();
    }

    /**
     * Create and return a new Bluetooth object.
     *
     * @param context In which context should the Bluetooth device created
     * @param btDeviceID the specific device ID of which Bluetooth device should be created
     * @return created object specified by the number i otherwise null
     */
    public static BluetoothCommunication getBtDevice(Context context, BT_DEVICE_ID btDeviceID) {
        switch (btDeviceID) {
            case CUSTOM_OPENSCALE:
                return new BluetoothCustomOpenScale(context);
            case MI_SCALE_V1:
                return new BluetoothMiScale(context);
            case MI_SCALE_V2:
                return new BluetoothMiScale2(context);
            case SANITAS_SBF70:
                return new BluetoothSanitasSbf70(context);
            case MEDISANA_BS444:
                return new BluetoothMedisanaBS444(context);
            case DIGOO_DGS038H:
                return new BluetoothDigooDGSO38H(context);
            case EXCELVANT_CF369BLE:
                return new BluetoothExcelvanCF369BLE(context);
            case YUNMAI_MINI:
                return new BluetoothYunmaiMini(context);
            case YUNMAI_SE:
                return new BluetoothYunmaiSE(context);
            case MGB:
                return new BluetoothMGB(context);
            case EXINGTECH_Y1:
                return new BluetoothExingtechY1(context);
        }

        return null;
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
     * @param scaleData the scale data that should be added to openScale
     */
    protected void addScaleData(ScaleData scaleData) {
        callbackBtHandler.obtainMessage(BT_STATUS_CODE.BT_RETRIEVE_SCALE_DATA.ordinal(), scaleData).sendToTarget();
    }

    /**
     * Send message to openScale user
     *
     * @param msg the string id to be send
     * @param value the value to be used
     */
    protected void sendMessage(int msg,  Object value) {
        callbackBtHandler.obtainMessage(BT_STATUS_CODE.BT_SCALE_MESSAGE.ordinal(), msg, 0,  value).sendToTarget();
    }

    /**
     * Check if the a device name is supported of the scale
     *
     * @param btDeviceName the device name that is checked
     * @return true if it valid otherwise false
     */
    public boolean checkDeviceName(String btDeviceName) {
        if (btDeviceName.toLowerCase().equals(defaultDeviceName().toLowerCase())) {
            return true;
        }

        return false;
    }

    /**
     * Supports Bluetooth device BLE (Bluetooth 4.x/smart).
     *
     * @return true if it Bluetooth 4.x (smart) otherwise false
     */
    public boolean isBLE() {
        return true;
    }

    /**
     * Return the Bluetooth device name
     *
     * @return a string in a human readable name
     */
    abstract public String deviceName();

    /**
     * Return the Bluetooth default device name
     *
     * @return the Bluetooth default device name for the scale
     */
    abstract public String defaultDeviceName();

    /**
     * State machine for the initialization process of the Bluetooth device.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract boolean nextInitCmd(int stateNr);

    /**
     * State machine for the normal/command process of the Bluetooth device.
     *
     * This state machine is automatically triggered if initialization process is finished.
     *
     * @param stateNr the current step number
     * @return false if no next step is available otherwise true
     */
    abstract boolean nextBluetoothCmd(int stateNr);

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
    abstract boolean nextCleanUpCmd(int stateNr);

    /**
     * Method is triggered if a Bluetooth data is read from a device.
     *
     * @param bluetoothGatt the Bluetooth Gatt
     * @param gattCharacteristic the Bluetooth Gatt characteristic
     * @param status the status code
     */
    protected void onBluetoothDataRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic, int status) {};

    /**
     * Method is triggered if a Bluetooth data from a device is notified or indicated.
     *
     * @param bluetoothGatt the Bluetooth Gatt
     * @param gattCharacteristic the Bluetooth characteristic
     */
    protected void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {};

    /**
     * Set the Bluetooth machine state to a specific state.
     *
     * @note after setting a new state the next step is automatically triggered.
     *
     * @param btMachineState the machine state that should be set.
     */
    protected void setBtMachineState(BT_MACHINE_STATE btMachineState) {
        this.btMachineState = btMachineState;

        handleRequests();
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
        synchronized (openRequest) {
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

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param service the Bluetooth UUID device service
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setIndicationOn(UUID service, UUID characteristic, UUID descriptor) {
        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        synchronized (openRequest) {
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
        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        synchronized (openRequest) {
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
        BluetoothGattCharacteristic gattCharacteristic = bluetoothGatt.getService(service)
                .getCharacteristic(characteristic);

        bluetoothGatt.setCharacteristicNotification(gattCharacteristic, false);

        BluetoothGattDescriptor gattDescriptor = gattCharacteristic.getDescriptor(descriptor);
        gattDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        synchronized (openRequest) {
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
            Log.e("BluetoothCommunication", "Data is null");
            return new String();
        }

        final StringBuilder stringBuilder = new StringBuilder(data.length);
        for (byte byteChar : data) {
            stringBuilder.append(String.format("%02X ", byteChar));
        }

        return stringBuilder.toString();
    }

    /**
     * Test in a byte if a bit is set (1) or not (0)
     *
     * @param value byte which is tested
     * @param bit bit position which is tested
     * @return true if bit is set (1) ohterwise false (0)
     */
    protected boolean isBitSet(byte value, int bit) {
        return (value & (1 << bit)) != 0;
    }

    /**
     * Start searching for a Bluetooth device.
     *
     * @note the hardware address is checked. Bluetooth device address has to be start with one of hwAddresses().
     *
     * On successfully connection Bluetooth machine state is automatically triggered.
     * If no device was found the search process is automatically stopped.
     *
     * @param deviceName the Bluetooth device name that is compared to the found devices.
     */
    public void startSearching(String deviceName) {
        btDeviceName = deviceName;

        if (scanCallback == null)
        {
            scanCallback = new BluetoothAdapter.LeScanCallback()
            {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    try {
                        if (device.getName() == null) {
                            return;
                        }

                        if (device.getName().toLowerCase().equals(btDeviceName.toLowerCase())) {
                            Log.d("BluetoothCommunication", btDeviceName + " found trying to connect...");

                            searchHandler.removeCallbacksAndMessages(null);
                            btAdapter.stopLeScan(scanCallback);
                            bluetoothGatt = device.connectGatt(context, false, gattCallback);
                        }
                    } catch (Exception e) {
                        setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, e.getMessage());
                    }
                }
            };
        }


        searchHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                btAdapter.stopLeScan(scanCallback);
                setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
            }
        }, 10000);

        btAdapter.startLeScan(scanCallback);
    }

    /**
     * Stop searching for a Bluetooth device
     */
    public void stopSearching() {
        if (bluetoothGatt != null)
        {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        searchHandler.removeCallbacksAndMessages(null);
        btAdapter.stopLeScan(scanCallback);
    }

    /**
     * Invoke next step for internal Bluetooth state machine.
     */
    protected void nextMachineStateStep() {
        switch (btMachineState) {
            case BT_INIT_STATE:
                Log.d("BluetoothCommunication", "INIT STATE: " + initStepNr);
                if (!nextInitCmd(initStepNr)) {
                    btMachineState = BT_MACHINE_STATE.BT_CMD_STATE;
                    nextMachineStateStep();
                }
                initStepNr++;
                break;
            case BT_CMD_STATE:
                Log.d("BluetoothCommunication", "CMD STATE: " + cmdStepNr);
                nextBluetoothCmd(cmdStepNr);
                cmdStepNr++;
                break;
            case BT_CLEANUP_STATE:
                Log.d("BluetoothCommunication", "CLEANUP STATE: " + cleanupStepNr);
                nextCleanUpCmd(cleanupStepNr);
                cleanupStepNr++;
                break;
        }
    }

    private void handleRequests() {
        synchronized (openRequest) {
            // check for pending request
            if (openRequest)
                return; // yes, do nothing

            // handle descriptor requests first
            BluetoothGattDescriptor descriptorRequest = descriptorRequestQueue.poll();
            if (descriptorRequest != null) {
                if (!bluetoothGatt.writeDescriptor(descriptorRequest))
                    Log.d("BTC", "Descriptor Write failed(" + byteInHex(descriptorRequest.getValue()) + ")");
                openRequest = true;
                return;
            }

            // handle characteristics requests second
            BluetoothGattCharacteristic characteristicRequest = characteristicRequestQueue.poll();
            if (characteristicRequest != null) {
                if (!bluetoothGatt.writeCharacteristic(characteristicRequest))
                    Log.d("BTC", "Characteristic Write failed(" + byteInHex(characteristicRequest.getValue()) + ")");
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
                stopSearching();
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            cmdStepNr = 0;
            initStepNr = 0;
            cleanupStepNr = 0;

            // Clear from possible previous setups
            characteristicRequestQueue = new LinkedList<>();
            descriptorRequestQueue = new LinkedList<>();
            openRequest = false;


            btMachineState = BT_MACHINE_STATE.BT_INIT_STATE;
            nextMachineStateStep();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            synchronized (openRequest) {
                openRequest = false;
                handleRequests();
            }
        }

        @Override
        public void onCharacteristicWrite (BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            synchronized (openRequest) {
                openRequest = false;
                handleRequests();
            }
        }

        @Override
        public void onCharacteristicRead (BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            onBluetoothDataRead(gatt, characteristic, status);
            synchronized (openRequest) {
                openRequest = false;
                handleRequests();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            onBluetoothDataChange(gatt, characteristic);
        }
    };
}

