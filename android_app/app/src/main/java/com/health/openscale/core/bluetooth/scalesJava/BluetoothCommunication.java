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
package com.health.openscale.core.bluetooth.scalesJava;

import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;

import android.Manifest;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.core.content.ContextCompat;


import com.health.openscale.R;
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType;
import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.data.User;
import com.health.openscale.core.utils.LogManager;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionState;
import com.welie.blessed.GattStatus;
import com.welie.blessed.HciStatus;
import com.welie.blessed.WriteType;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public abstract class BluetoothCommunication {
    private final String TAG = "BluetoothCommunication";
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
        USER_INTERACTION_REQUIRED
    }

    private int stepNr;
    private boolean stopped;

    protected Context context;

    private Handler callbackBtHandler;
    private Handler disconnectHandler;

    private BluetoothCentralManager central;
    private BluetoothPeripheral btPeripheral;

    private ScaleUser selectedScaleUser;

    private List<ScaleUser> cachedAppUserList;
    protected ScaleMeasurement cachedLastMeasurementForSelectedUser;

    private int uniqueBase = -1;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        this.disconnectHandler = new Handler(Looper.getMainLooper());
        this.stepNr = 0;
        this.stopped = false;
        this.central = new BluetoothCentralManager(context, bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
        this.selectedScaleUser = new ScaleUser();
    }

    public void setSelectedScaleUser(ScaleUser user) {
        selectedScaleUser = user;
    }

    public ScaleUser getSelectedScaleUser() {
        return selectedScaleUser;
    }

    public void setScaleUserList(List<ScaleUser> userList) {
        cachedAppUserList = userList;
    }

    public List<ScaleUser> getScaleUserList() { return cachedAppUserList; }

    public void setCachedLastMeasurementForSelectedUser(ScaleMeasurement measurement) {
        this.cachedLastMeasurementForSelectedUser = measurement;
        if (measurement != null) {
            LogManager.d(TAG, "Cached last measurement for selected user (ID: " + getSelectedScaleUser().getId() + ") set.");
        } else {
            LogManager.d(TAG, "Cached last measurement for selected user (ID: " + getSelectedScaleUser().getId() + ") cleared.");
        }
    }
    public ScaleMeasurement getLastScaleMeasurement(int userId) {
        if (getSelectedScaleUser().getId() == userId && this.cachedLastMeasurementForSelectedUser != null) {
            LogManager.d(TAG, "Returning cached last measurement for user ID: " + userId);
            return this.cachedLastMeasurementForSelectedUser;
        }
        if (getSelectedScaleUser().getId() != userId) {
            LogManager.w(TAG, "Requested last measurement for user ID " + userId +
                    ", but cached data is for selected user ID " + getSelectedScaleUser().getId() + ". Returning null.", null);
        } else { // cachedLastMeasurementForSelectedUser is null
            LogManager.d(TAG, "No cached last measurement available for user ID: " + userId + ". Returning null.");
        }
        return null;
    }

    public void setUniqueNumber(int base) {
        this.uniqueBase = base;
    }

    protected int getUniqueNumber() {
        if (uniqueBase == -1) {
            LogManager.w(TAG, "(Unique number base not set! Call setUniqueNumber() first.", null);
            uniqueBase = 99;
        }
        int userId = getSelectedScaleUser().getId();
        int finalUnique = uniqueBase + userId;
        LogManager.d(TAG, "Returning unique number " + finalUnique + " (base=" + uniqueBase + " + userId=" + userId + ")");
        return finalUnique;
    }

    protected void requestUserInteraction(UserInteractionType interactionType, Object data) {
        if (callbackBtHandler != null) {
            Message msg = callbackBtHandler.obtainMessage(BT_STATUS.USER_INTERACTION_REQUIRED.ordinal());

            Object[] payload = new Object[]{interactionType, data};
            msg.obj = payload;

            LogManager.d(TAG, "Sending USER_INTERACTION_REQUIRED (" + interactionType + ") to handler. Data: " + (data != null ? data.toString() : "null"));
            msg.sendToTarget();
        }
    }

    /**
     * Processes feedback received from the user in response to a requestUserInteraction call.
     * The specific implementation in subclasses will handle the data based on the original interaction type.
     *
     * @param interactionType The type of interaction this feedback corresponds to.
     * @param appUserId The ID of the application user this feedback is for.
     * @param feedbackData Data provided by the user (e.g., selected scaleUserIndex, entered consent code).
     *                     The type and structure depend on the interactionType.
     *                     For CHOOSE_USER, this would be the selected scaleUserIndex (Integer).
     *                     For ENTER_CONSENT, this would be the consent code (Integer).
     * @param uiHandler Handler for potential further UI updates or operations within the communicator.
     */
    public void processUserInteractionFeedback(UserInteractionType interactionType, int appUserId, Object feedbackData, Handler uiHandler) {
        LogManager.d(TAG, "processUserInteractionFeedback for " + interactionType + " not implemented in base class. AppUserId: " + appUserId);
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
        LogManager.d("BluetoothCommunication","Stop machine state");
        stopped = true;
    }

    /**
     * resume current state machine
     */
    protected synchronized void resumeMachineState() {
        LogManager.d("BluetoothCommunication","Resume machine state");
        stopped = false;
        nextMachineStep();
    }

    /**
     * This function only resumes the state machine if the current step equals curStep,
     * i.e. if the next step (stepNr) is 1 above curStep.
     */
    protected synchronized boolean resumeMachineState( int curStep ) {
        if( curStep == stepNr-1 ) {
            LogManager.d("BluetoothCommunication","curStep " + curStep + " matches stepNr " + stepNr + "-1, resume state machine.");
            stopped = false;
            nextMachineStep();
            return true;
        }
        else {
            LogManager.d("BluetoothCommunication","curStep " + curStep + " does not match stepNr " + stepNr + "-1, not resuming state machine.");
            return false;
        }
    }

    /**
     * This function jump to a specific step number
     * @param nr the step number which the state machine should jump to.
     */
    protected synchronized void jumpNextToStepNr(int nr) {
        LogManager.d("BluetoothCommunication","Jump next to step nr " + nr);
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
            LogManager.d("BluetoothCommunication","curStepNr " + curStepNr + " matches stepNr " + stepNr + "-1, jumping next to step nr " + newStepNr);
            stepNr = newStepNr;
            return true;
        }
        else {
            LogManager.d("BluetoothCommunication","curStepNr " + curStepNr + " does not match stepNr " + stepNr + "-1, keeping next at step nr " + stepNr);
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
        LogManager.d("BluetoothCommunication","Jumped back one step to " + stepNr);
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
        LogManager.d("BluetoothCommunication","Invoke write bytes [" + byteInHex(bytes) + "] on " + BluetoothGattUuid.prettyPrint(characteristic));
        btPeripheral.writeCharacteristic(btPeripheral.getCharacteristic(service, characteristic), bytes,
                noResponse ? WriteType.WITHOUT_RESPONSE : WriteType.WITH_RESPONSE);
    }

    /**
     * Read bytes from a Bluetooth device.
     *
     * @note onBluetoothRead() will be triggered if read command was successful. nextMachineStep() needs to manually called!
     *@param characteristic the Bluetooth UUID characteristic
     */
    void readBytes(UUID service, UUID characteristic) {
        LogManager.d("BluetoothCommunication","Invoke read bytes on " + BluetoothGattUuid.prettyPrint(characteristic));

        btPeripheral.readCharacteristic(btPeripheral.getCharacteristic(service, characteristic));
    }

    /**
     * Set indication flag on for the Bluetooth device.
     *
     * @param characteristic the Bluetooth UUID characteristic
     */
    protected void setIndicationOn(UUID service, UUID characteristic) {
        LogManager.d("BluetoothCommunication","Invoke set indication on " + BluetoothGattUuid.prettyPrint(characteristic));
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
        LogManager.d("BluetoothCommunication","Invoke set notification on " + BluetoothGattUuid.prettyPrint(characteristic));
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
        LogManager.d("BluetoothCommunication","Bluetooth disconnect");
        setBluetoothStatus(BT_STATUS.CONNECTION_DISCONNECT);
        try {
            central.stopScan();
        } catch (Exception ex) {
            LogManager.e("BluetoothCommunication", "Error on Bluetooth disconnecting " + ex.getMessage(), ex);
        }

        if (btPeripheral != null) {
            central.cancelConnection(btPeripheral);
        }
        callbackBtHandler = null;
        disconnectHandler.removeCallbacksAndMessages(null);
    }

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
            LogManager.e("BluetoothCommunication", "Data is null", null);
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
            LogManager.d("BluetoothCommunication","Successful Bluetooth services discovered");
            onBluetoothDiscovery(peripheral);
            resumeMachineState();
        }

        @Override
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, GattStatus status) {
            if( status.value == GATT_SUCCESS) {
                if(peripheral.isNotifying(characteristic)) {
                    LogManager.d("BluetoothCommunication",String.format("SUCCESS: Notify set for %s", characteristic.getUuid()));
                    resumeMachineState();
                }
            } else {
                LogManager.e("BluetoothCommunication",String.format("ERROR: Changing notification state failed for %s", characteristic.getUuid()), null);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, GattStatus status) {
            if( status.value == GATT_SUCCESS) {
                LogManager.d("BluetoothCommunication",String.format("SUCCESS: Writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()));
                nextMachineStep();

            } else {
                LogManager.e("BluetoothCommunication",String.format("ERROR: Failed writing <%s> to <%s>", byteInHex(value), characteristic.getUuid().toString()), null);
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
            LogManager.d("BluetoothCommunication",String.format("connected to '%s'", peripheral.getName()));
            setBluetoothStatus(BT_STATUS.CONNECTION_ESTABLISHED);
            btPeripheral = peripheral;
            nextMachineStep();
            resetDisconnectTimer();
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, HciStatus status) {
            LogManager.e("BluetoothCommunication", String.format("connection '%s' failed with status %d", peripheral.getName(), status.value),null);
            setBluetoothStatus(BT_STATUS.CONNECTION_LOST);

            if (status.value == 8) {
                sendMessage(R.string.info_bluetooth_connection_error_scale_offline, 0);
            }
        }

        @Override
        public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, HciStatus status) {
            LogManager.d("BluetoothCommunication",String.format("disconnected '%s' with status %d", peripheral.getName(), status.value));
        }

        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            LogManager.d("BluetoothCommunication",String.format("Found peripheral '%s'", peripheral.getName()));
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
        // Android 12+ (API 31+): SCAN needed for scanning, CONNECT needed for connect/GATT.
        final boolean isSPlus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S;

        if (isSPlus) {
            boolean canConnect = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean canScan = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;

            if (!canConnect) {
                LogManager.e("BluetoothCommunication",
                        "API≥31: Missing BLUETOOTH_CONNECT → cannot connect/GATT. Aborting.", null);
                setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR);
                sendMessage(R.string.info_bluetooth_connection_error_scale_offline, 0);
                return;
            }

            // Pre-scan improves connect reliability on some devices/scales
            if (canScan) {
                LogManager.d("BluetoothCommunication",
                        "API≥31: Do LE scan before connecting (no location needed)");
                central.scanForPeripheralsWithAddresses(new String[]{macAddress});
                stopMachineState(); // wait for onDiscoveredPeripheral → connect
                return;
            } else {
                LogManager.w("BluetoothCommunication",
                        "API≥31: BLUETOOTH_SCAN not granted → connecting without pre-scan (may be less reliable)", null);
                BluetoothPeripheral peripheral = central.getPeripheral(macAddress);
                try {
                    connectToDevice(peripheral);
                } catch (SecurityException se) {
                    LogManager.e("BluetoothCommunication",
                            "SecurityException during connect (missing CONNECT?): " + se.getMessage(), se);
                    setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR);
                }
                return;
            }
        }

        // Defensive fallback (won't run with minSdk=31, but good for clarity)
        LogManager.w("BluetoothCommunication","connect() called on API<31 path; no legacy handling active.", null);
    }


    private void connectToDevice(BluetoothPeripheral peripheral) {

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                LogManager.d("BluetoothCommunication","Try to connect to BLE device " + peripheral.getAddress());

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
                LogManager.d("BluetoothCommunication","Timeout Bluetooth disconnect");
                disconnect();
            }
        }, 60000); // 60s timeout
    }

    private synchronized void nextMachineStep() {
        if (!stopped) {
            LogManager.d("BluetoothCommunication","Step Nr " + stepNr);
            if (onNextStep(stepNr)) {
                stepNr++;
                nextMachineStep();
            } else {
                LogManager.d("BluetoothCommunication","Invoke delayed disconnect in 60s");
                disconnectWithDelay();
            }
        }
    }
}
