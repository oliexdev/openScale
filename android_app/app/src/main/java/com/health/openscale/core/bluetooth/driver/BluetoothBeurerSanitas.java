/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*                2017  jflesch <jflesch@kwain.net>
*                2017  Martin Nowack
*                2017  linuxlurak with help of Dododappere, see: https://github.com/oliexdev/openScale/issues/111
*                2018  Erik Johansson <erik@ejohansson.se>
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
package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothBeurerSanitas extends BluetoothCommunication {
    // < 0 means we are not actually waiting for data
    // any value >= 0 means we are waiting for data in that state
    private int waitForDataInStep = -1;

    enum DeviceType { BEURER_BF700_800_RT_LIBRA, BEURER_BF710, SANITAS_SBF70_70 }

    private static final UUID CUSTOM_SERVICE_1 = BluetoothGattUuid.fromShortCode(0xffe0);
    private static final UUID CUSTOM_CHARACTERISTIC_WEIGHT = BluetoothGattUuid.fromShortCode(0xffe1);

    private DeviceType deviceType;
    private byte startByte;

    private class RemoteUser {
        final public long remoteUserId;
        final public String name;
        final public int year;

        public int localUserId = -1;
        public boolean isNew = false;

        RemoteUser(long uid, String name, int year) {
            this.remoteUserId = uid;
            this.name = name;
            this.year = year;
        }
    }

    private class StoredData {
        public byte[] measurementData = null;
        public long storedUid = -1;
        public long candidateUid = -1;
    }

    private ArrayList<RemoteUser> remoteUsers = new ArrayList<>();
    private RemoteUser currentRemoteUser;
    private byte[] measurementData = null;
    private StoredData storedMeasurement = new StoredData();
    private boolean readyForData = false;
    private boolean dataReceived = false;

    private final int ID_START_NIBBLE_INIT = 6;
    private final int ID_START_NIBBLE_CMD = 7;
    private final int ID_START_NIBBLE_SET_TIME = 9;
    private final int ID_START_NIBBLE_DISCONNECT = 10;

    private final byte CMD_SET_UNIT = (byte)0x4d;
    private final byte CMD_SCALE_STATUS = (byte)0x4f;

    private final byte CMD_USER_ADD = (byte)0x31;
    private final byte CMD_USER_DELETE = (byte)0x32;
    private final byte CMD_USER_LIST = (byte)0x33;
    private final byte CMD_USER_INFO = (byte)0x34;
    private final byte CMD_USER_UPDATE = (byte)0x35;
    private final byte CMD_USER_DETAILS = (byte)0x36;

    private final byte CMD_DO_MEASUREMENT = (byte)0x40;
    private final byte CMD_GET_SAVED_MEASUREMENTS = (byte)0x41;
    private final byte CMD_SAVED_MEASUREMENT = (byte)0x42;
    private final byte CMD_DELETE_SAVED_MEASUREMENTS = (byte)0x43;

    private final byte CMD_GET_UNKNOWN_MEASUREMENTS = (byte)0x46;
    private final byte CMD_UNKNOWN_MEASUREMENT_INFO = (byte)0x47;
    private final byte CMD_ASSIGN_UNKNOWN_MEASUREMENT = (byte)0x4b;
    private final byte CMD_UNKNOWN_MEASUREMENT = (byte)0x4c;
    private final byte CMD_DELETE_UNKNOWN_MEASUREMENT = (byte)0x49;

    private final byte CMD_WEIGHT_MEASUREMENT = (byte)0x58;
    private final byte CMD_MEASUREMENT = (byte)0x59;

    private final byte CMD_SCALE_ACK = (byte)0xf0;
    private final byte CMD_APP_ACK = (byte)0xf1;

    private byte getAlternativeStartByte(int startNibble) {
        return (byte) ((startByte & 0xF0) | startNibble);
    }

    private long decodeUserId(byte[] data, int offset) {
        long high = Converters.fromUnsignedInt32Be(data, offset);
        long low = Converters.fromUnsignedInt32Be(data, offset + 4);
        return (high << 32) | low;
    }

    private byte[] encodeUserId(RemoteUser remoteUser) {
        long uid = remoteUser != null ? remoteUser.remoteUserId : 0;
        byte[] data = new byte[8];
        Converters.toInt32Be(data, 0, uid >> 32);
        Converters.toInt32Be(data, 4, uid & 0xFFFFFFFF);
        return data;
    }

    private String decodeString(byte[] data, int offset, int maxLength) {
        int length = 0;
        for (; length < maxLength; ++length) {
            if (data[offset + length] == 0) {
                break;
            }
        }
        return new String(data, offset, length);
    }

    private String normalizeString(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("[^A-Za-z0-9]", "");
    }

    private String convertUserNameToScale(ScaleUser user) {
        String normalized = normalizeString(user.getUserName());
        if (normalized.isEmpty()) {
            return String.valueOf(user.getId());
        }
        return normalized.toUpperCase(Locale.US);
    }

    public BluetoothBeurerSanitas(Context context, String deviceName) {
        super(context, deviceName);

        this.setDeviceType(deviceName);
        switch (deviceType) {
            case BEURER_BF700_800_RT_LIBRA:
                startByte = (byte) (0xf0 | ID_START_NIBBLE_CMD);
                break;
            case BEURER_BF710:
            case SANITAS_SBF70_70:
                startByte = (byte) (0xe0 | ID_START_NIBBLE_CMD);
                break;
        }
    }

    private void setDeviceType(String deviceName) {
        if (deviceName.startsWith("BEURER BF700".toLowerCase(Locale.US))
                || deviceName.startsWith("BEURER BF800".toLowerCase(Locale.US))
                || deviceName.startsWith("BF-800".toLowerCase(Locale.US))
                || deviceName.startsWith("BF-700".toLowerCase(Locale.US))
                || deviceName.startsWith("RT-Libra-B".toLowerCase(Locale.US))
                || deviceName.startsWith("RT-Libra-W".toLowerCase(Locale.US))
                || deviceName.startsWith("Libra-B".toLowerCase(Locale.US))
                || deviceName.startsWith("Libra-W".toLowerCase(Locale.US))) {
            this.deviceType = BluetoothBeurerSanitas.DeviceType.BEURER_BF700_800_RT_LIBRA;
        }
        if (deviceName.startsWith("BEURER BF710".toLowerCase(Locale.US))
                || deviceName.equals("BF700".toLowerCase(Locale.US))) {
            this.deviceType = BluetoothBeurerSanitas.DeviceType.BEURER_BF710;
        }
        if (deviceName.startsWith("SANITAS SBF70".toLowerCase(Locale.US))
                || deviceName.startsWith("sbf75")
                || deviceName.startsWith("AICDSCALE1".toLowerCase(Locale.US))) {
            this.deviceType = BluetoothBeurerSanitas.DeviceType.SANITAS_SBF70_70;
        }
    }

    @Override
    public String driverName() {
        switch (deviceType) {
            case BEURER_BF700_800_RT_LIBRA:
                return "Beurer BF700/800 / Runtastic Libra";
            case BEURER_BF710:
                return "Beurer BF710";
            case SANITAS_SBF70_70:
                return "Sanitas SBF70/SilverCrest SBF75/Crane";
        }

        return "Unknown device type";
    }

    public static String driverId() {
        return "beurer_sanitas";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // Fresh start, so reset everything
                measurementData = null;
                storedMeasurement.measurementData = null;
                readyForData = false;
                dataReceived = false;
                // Setup notification
                setNotificationOn(CUSTOM_SERVICE_1, CUSTOM_CHARACTERISTIC_WEIGHT);
                break;
            case 1:
                // we will be waiting for data in state 1
                waitForDataInStep = 1;
                // Say "Hello" to the scale and wait for ack
                Timber.d("Sending command: ID_START_NIBBLE_INIT");
                sendAlternativeStartCode(ID_START_NIBBLE_INIT, (byte) 0x01);
                stopMachineState();
                break;
            case 2:
                // Update time on the scale (no ack)
                long unixTime = System.currentTimeMillis() / 1000L;
                Timber.d("Sending command: ID_START_NIBBLE_SET_TIME");
                sendAlternativeStartCode(ID_START_NIBBLE_SET_TIME, Converters.toInt32Be(unixTime));
                break;
            case 3:
                // We will be waiting for data in state 3
                waitForDataInStep = 3;
                // Request scale status and wait for ack
                Timber.d("Sending command: CMD_SCALE_STATUS");
                sendCommand(CMD_SCALE_STATUS, encodeUserId(null));
                stopMachineState();
                break;
            case 4:
                // We will be waiting for data in state 4
                waitForDataInStep = 4;
                // Request list of all users and wait until all have been received
                Timber.d("Sending command: CMD_USER_LIST");
                sendCommand(CMD_USER_LIST);
                stopMachineState();
                break;
            case 5:
                // If currentRemoteUser is null, indexOf returns -1 and index will be 0
                int index = remoteUsers.indexOf(currentRemoteUser) + 1;
                currentRemoteUser = null;

                // Find the next remote user that exists locally
                for (; index < remoteUsers.size(); ++index) {
                    if (remoteUsers.get(index).localUserId != -1) {
                        currentRemoteUser = remoteUsers.get(index);
                        break;
                    }
                }

                // Fetch saved measurements
                if (currentRemoteUser != null) {
                    // We will be waiting for data in state 5
                    waitForDataInStep = 5;
                    Timber.d("Request saved measurements (CMD_GET_SAVED_MEASUREMENTS) for %s", currentRemoteUser.name);
                    sendCommand(CMD_GET_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));
                    stopMachineState();
                }
                // No user found, just continue to next step.
                break;
            case 6:
                // Create a remote user for selected openScale user if needed
                currentRemoteUser = null;
                final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                for (RemoteUser remoteUser : remoteUsers) {
                    if (remoteUser.localUserId == selectedUser.getId()) {
                        currentRemoteUser = remoteUser;
                        break;
                    }
                }
                if (currentRemoteUser == null) {
                    waitForDataInStep = 6;
                    createRemoteUser(selectedUser);
                    stopMachineState();
                }
                // Do not need to create new user, just continue to next step.
                break;
            case 7:
                waitForDataInStep = 7;
                Timber.d("Sending command: CMD_USER_DETAILS");
                sendCommand(CMD_USER_DETAILS, encodeUserId(currentRemoteUser));
                stopMachineState();
                break;
            case 8:
                // If we have unprocessed data available, store it now.
                if( storedMeasurement.measurementData != null ) {
                    Timber.d("Reached state 8 (end) and still have saved data available. Storing now.");
                    if( currentRemoteUser != null ) {
                        Timber.i("User has been identified in the meantime, so store the data for them.");
                        addMeasurement(measurementData, currentRemoteUser.localUserId);
                    }
                    else {
                        Timber.i("User still not identified, so storing the data for the selected user.");
                        addMeasurement(measurementData, OpenScale.getInstance().getSelectedScaleUser().getId());
                    }
                    storedMeasurement.measurementData = null;
                }
                else if (!dataReceived && currentRemoteUser != null && !currentRemoteUser.isNew) {
                    // Looks like we never received a fresh measurement in this run, so request it now.
                    // Chances are not good that this will work, but let's try it anyway.
                    waitForDataInStep = 8;
                    Timber.d("Sending command: CMD_DO_MEASUREMENT");
                    sendCommand(CMD_DO_MEASUREMENT, encodeUserId(currentRemoteUser));
                    stopMachineState();
                } else {
                    Timber.d("All finished, nothing to do.");
                    return false;
                }
                break;
            default:
                // Finish init if everything is done
                Timber.d("End of state flow reached.");
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        byte[] data = value;
        if (data == null || data.length == 0) {
            Timber.d("Received empty message.");
            return;
        }

        if (data[0] == getAlternativeStartByte(ID_START_NIBBLE_INIT)) {
            // this message should only happen in state 1
            if( waitForDataInStep == 1 ) {
                Timber.d("Received init ack (ID_START_NIBBLE_INIT) from scale; scale is ready");
            }
            else {
                Timber.w("Received init ack (ID_START_NIBBLE_INIT) from scale in wrong state. Scale or app is confused. Continue in state 2.");
                jumpNextToStepNr( 2 );
            }
            // All data received, no more waiting.
            waitForDataInStep = -1;
            // On to state 2
            resumeMachineState();
            return;
        }

        if (data[0] != startByte) {
            Timber.e("Got unknown start byte 0x%02x", data[0]);
            return;
        }

        try {
            switch (data[1]) {
                case CMD_USER_INFO:
                    Timber.d("Received: CMD_USER_INFO");
                    processUserInfo(data);
                    break;
                case CMD_SAVED_MEASUREMENT:
                    Timber.d("Received: CMD_SAVED_MEASUREMENT");
                    processSavedMeasurement(data);
                    break;
                case CMD_WEIGHT_MEASUREMENT:
                    Timber.d("Received: CMD_WEIGHT_MEASUREMENT");
                    processWeightMeasurement(data);
                    break;
                case CMD_MEASUREMENT:
                    Timber.d("Received: CMD_MEASUREMENT");
                    processMeasurement(data);
                    break;
                case CMD_SCALE_ACK:
                    Timber.d("Received: CMD_SCALE_ACK");
                    processScaleAck(data);
                    break;
                default:
                    Timber.d("Unknown command 0x%02x", data[1]);
                    break;
            }
        }
        catch (IndexOutOfBoundsException|NullPointerException e) {
            Timber.e(e);
        }
    }

    private void processUserInfo(byte[] data) {
        final int count = data[2] & 0xFF;
        final int current = data[3] & 0xFF;

        if (remoteUsers.size() == current - 1) {
            String name = decodeString(data, 12, 3);
            int year = 1900 + (data[15] & 0xFF);

            remoteUsers.add(new RemoteUser(decodeUserId(data, 4), name, year));

            Timber.d("Received user %d/%d: %s (%d)", current, count, name, year);
        }

        Timber.d("Sending ack for CMD_USER_INFO");
        sendAck(data);

        if (current != count) {
            Timber.d("Not all users received, waiting for more...");
            // More data should be incoming, so make sure we wait
            stopMachineState();
            return;
        }

        Calendar cal = Calendar.getInstance();

        for (ScaleUser scaleUser : OpenScale.getInstance().getScaleUserList()) {
            final String localName = convertUserNameToScale(scaleUser);
            cal.setTime(scaleUser.getBirthday());
            final int year = cal.get(Calendar.YEAR);

            for (RemoteUser remoteUser : remoteUsers) {
                if (localName.startsWith(remoteUser.name) && year == remoteUser.year) {
                    remoteUser.localUserId = scaleUser.getId();
                    Timber.d("Remote user %s (0x%x) is local user %s (%d)",
                            remoteUser.name, remoteUser.remoteUserId,
                            scaleUser.getUserName(), remoteUser.localUserId);
                    break;
                }
            }
        }

        if( waitForDataInStep != 4 ) {
            Timber.w("Received final CMD_USER_INFO in wrong state...");
            if( waitForDataInStep >= 0 ){
                Timber.w("...while waiting for other data. Retrying last step.");
                // We are in the wrong state.
                // This may happen, so let's just retry whatever we did before.
                jumpBackOneStep();
            }
            else {
                Timber.w("...ignored, no data expected.");
            }
        }
        // All data received, no more waiting.
        waitForDataInStep = -1;
        // All users received
        resumeMachineState();
    }

    private void processMeasurementData(byte[] data, int offset, boolean firstPart, boolean processingSavedMeasurements) {
        if (firstPart) {
            if( measurementData != null ) Timber.d("Discarding existing data.");
            measurementData = Arrays.copyOfRange(data, offset, data.length);
            return;
        }

        if( measurementData == null ) {
            Timber.w("Received second measurement part without receiving first part before. Discarding data.");
            return;
        }

        int oldEnd = measurementData.length;
        int toCopy = data.length - offset;

        measurementData = Arrays.copyOf(measurementData, oldEnd + toCopy);
        System.arraycopy(data, offset, measurementData, oldEnd, toCopy);

        // Store data, but only if we are ready and know the user. Otherwise leave it for later.
        if( currentRemoteUser != null && (readyForData || processingSavedMeasurements) ) {
            Timber.d("Measurement complete, user identified and app ready: Storing data.");
            addMeasurement(measurementData, currentRemoteUser.localUserId);
            // Do we have unsaved data?
            if( storedMeasurement.measurementData != null ) {
                // Does it belong to the current user
                if( currentRemoteUser.remoteUserId == storedMeasurement.storedUid ) {
                    // Does it have the same time stamp?
                    if( Converters.fromUnsignedInt32Be(measurementData, 0) == Converters.fromUnsignedInt32Be(storedMeasurement.measurementData, 0) ) {
                        // Then delete the unsaved data because it is already part of the received saved data
                        Timber.d("Discarding data saved for later, because it is already part of the received saved data from the scale.");
                        storedMeasurement.measurementData = null;
                    }
                }
            }
            // Data processed, so discard it.
            measurementData = null;
            // Also discard saved data, because we got and processed new data
            storedMeasurement.measurementData = null;
        }
        else if( !processingSavedMeasurements ) {
            if( !readyForData ) {
                Timber.d("New measurement complete, but not stored, because app not ready: Saving data for later.");
            }
            else {
                Timber.d("New measurement complete, but not stored, because user not identified: Saving data for later.");
            }
            storedMeasurement.measurementData = measurementData;
            storedMeasurement.storedUid = storedMeasurement.candidateUid;
        }
        else {
            // How the f*** did we end up here?
            Timber.e("Received saved measurement, but do not know for what user. This should not happen. Discarding data.");
            measurementData = null;
        }
    }

    private void processSavedMeasurement(byte[] data) {
        int count = data[2] & 0xFF;
        int current = data[3] & 0xFF;
        Timber.d("Received part %d (of 2) of saved measurement %d of %d.", current % 2 == 1 ? 1 : 2, current / 2, count / 2);

        processMeasurementData(data, 4, current % 2 == 1, true);

        Timber.d("Sending ack for CMD_SAVED_MEASUREMENT");
        sendAck(data);

        if (current != count) {
            Timber.d("Not all parts / saved measurements received, waiting for more...");
            // More data should be incoming, so make sure we wait
            stopMachineState();
            return;
        }

        Timber.i("All saved measurements received.");

        // This message should only be received in step 5
        if( waitForDataInStep != 5 ) {
            Timber.w("Received final CMD_SAVED_MEASUREMENT in wrong state...");
            if( waitForDataInStep >= 0 ){
                Timber.w("...while waiting for other data. Retrying last step.");
                // We are in the wrong state.
                // This may happen, so let's just retry whatever we did before.
                jumpBackOneStep();
                resumeMachineState();
            }
            else {
                Timber.w("...ignored, no data expected.");
            }
            // Let's not delete data we received unexpectedly, so just get out of here.
            return;
        }

        // We are done with saved measurements, from now on we can process unrequested measurement data.
        readyForData = true;

        Timber.d("Deleting saved measurements (CMD_DELETE_SAVED_MEASUREMENTS) for %s", currentRemoteUser.name);
        sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));
        // We sent a new command, so make sure we wait
        stopMachineState();

        /* Why do we want to resume the state machine, when we are not the last remote user?
         * In the moment I do not understand this code, so I'll comment it out but leave it here for reference.
        if (currentRemoteUser.remoteUserId != remoteUsers.get(remoteUsers.size() - 1).remoteUserId) {
            // Only jump back to state 5 if we are in 5
            if( jumpNextToStepNr( 5, 5 ) ) {
                // Now resume
                resumeMachineState();
            }
        }
        */
    }

    private void processWeightMeasurement(byte[] data) {
        boolean stableMeasurement = data[2] == 0;
        float weight = getKiloGram(data, 3);

        if (!stableMeasurement) {
            Timber.d("Active measurement, weight: %.2f", weight);
            sendMessage(R.string.info_measuring, weight);
            return;
        }

        Timber.i("Active measurement, stable weight: %.2f", weight);
    }

    private void processMeasurement(byte[] data) {
        int count = data[2] & 0xFF;
        int current = data[3] & 0xFF;
        Timber.d("Received measurement part %d of %d.", current, count );

        if (current == 1) {
            long uid = decodeUserId(data, 5);
            Timber.d("Receiving measurement data for remote UID %d.", uid);
            // Remember uid in case we need it to save data for later.
            storedMeasurement.candidateUid = uid;
            // Now search for user
            currentRemoteUser = null;
            for (RemoteUser remoteUser : remoteUsers) {
                if (remoteUser.remoteUserId == uid) {
                    currentRemoteUser = remoteUser;
                    Timber.d("Local user %s matches remote UID %d.", currentRemoteUser.name, uid);
                    break;
                }
            }
            if( currentRemoteUser == null ) {
                Timber.d("No local user identified for remote UID %d.", uid);
            }
        }
        else {
            processMeasurementData(data, 4, current == 2, false);
        }

        // Even if we did not process the data, always ack the message
        Timber.d("Sending ack for CMD_MEASUREMENT");
        sendAck(data);

        if (current != count) {
            Timber.d("Not all measurement parts received, waiting for more...");
            // More data should be incoming, so make sure we wait
            stopMachineState();
            return;
        }

        Timber.i("All measurement parts received.");

        // Delete saved measurement, but only when we processed it before
        if (currentRemoteUser != null && readyForData ) {
            Timber.d("Sending command: CMD_DELETE_SAVED_MEASUREMENTS");
            sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));
            // We sent a new command, so make sure we wait
            stopMachineState();
        }
        // This message should only be received in step 6 and 8
        else if( waitForDataInStep != 6 && waitForDataInStep != 8 ) {
            Timber.w("Received final CMD_MEASUREMENT in wrong state...");
            if( waitForDataInStep >= 0 ){
                Timber.w("...while waiting for other data. Retrying last step.");
                // We are in the wrong state.
                // This may happen, so let's just retry whatever we did before.
                jumpBackOneStep();
                resumeMachineState();
            }
            else {
                Timber.w("...ignored, no data expected.");
            }
        }
        else {
            resumeMachineState();
        }
    }


    private void processScaleAck(byte[] data) {
        switch (data[2]) {
            case CMD_SCALE_STATUS:
                Timber.d("ACK type: CMD_SCALE_STATUS");
                // data[3] != 0 if an invalid user id is given to the command,
                // but it still provides some useful information (e.g. current unit).
                final int batteryLevel = data[4] & 0xFF;
                final float weightThreshold = (data[5] & 0xFF) / 10f;
                final float bodyFatThreshold = (data[6] & 0xFF) / 10f;
                final int currentUnit = data[7] & 0xFF;
                final boolean userExists = data[8] == 0;
                final boolean userReferWeightExists = data[9] == 0;
                final boolean userMeasurementExist = data[10] == 0;
                final int scaleVersion = data[11] & 0xFF;

                Timber.d("Battery level: %d; threshold: weight=%.2f, body fat=%.2f;"
                                + " unit: %d; requested user: exists=%b, has reference weight=%b,"
                                + " has measurement=%b; scale version: %d",
                        batteryLevel, weightThreshold, bodyFatThreshold, currentUnit, userExists,
                        userReferWeightExists, userMeasurementExist, scaleVersion);

                if (batteryLevel <= 10) {
                    sendMessage(R.string.info_scale_low_battery, batteryLevel);
                }

                byte requestedUnit = (byte) currentUnit;
                ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
                switch (user.getScaleUnit()) {
                    case KG:
                        requestedUnit = 1;
                        break;
                    case LB:
                        requestedUnit = 2;
                        break;
                    case ST:
                        requestedUnit = 4;
                        break;
                }
                if (requestedUnit != currentUnit) {
                    Timber.d("Set scale unit (CMD_SET_UNIT) to %s (%d)", user.getScaleUnit(), requestedUnit);
                    sendCommand(CMD_SET_UNIT, requestedUnit);
                    // We send a new command, so make sure we wait
                    stopMachineState();
                } else {
                    // This should only be received in step 3
                    if( waitForDataInStep != 3 ) {
                        Timber.w("Received ACK for CMD_SCALE_STATUS in wrong state...");
                        if( waitForDataInStep >= 0 ){
                            Timber.w("...while waiting for other data. Retrying last step.");
                            // We are in the wrong state.
                            // This may happen, so let's just retry whatever we did before.
                            jumpBackOneStep();
                        }
                        else {
                            Timber.w("...ignored, no data expected.");
                        }
                    }
                    // All data received, no more waiting.
                    waitForDataInStep = -1;
                    resumeMachineState();
                }
                break;

            case CMD_SET_UNIT:
                Timber.d("ACK type: CMD_SET_UNIT");
                if (data[3] == 0) {
                    Timber.d("Scale unit successfully set");
                }
                // This should only be received in step 3
                if( waitForDataInStep != 3 ) {
                    Timber.w("Received ACK for CMD_SET_UNIT in wrong state...");
                    if( waitForDataInStep >= 0 ){
                        Timber.w("...while waiting for other data. Retrying last step.");
                        // We are in the wrong state.
                        // This may happen, so let's just retry whatever we did before.
                        jumpBackOneStep();
                    }
                    else {
                        Timber.w("...ignored, no data expected.");
                    }
                }
                // All data received, no more waiting.
                waitForDataInStep = -1;
                resumeMachineState();
                break;

            case CMD_USER_LIST:
                Timber.d("ACK type: CMD_USER_LIST");
                int userCount = data[4] & 0xFF;
                int maxUserCount = data[5] & 0xFF;
                Timber.d("Have %d users (max is %d)", userCount, maxUserCount);
                if (userCount == 0) {
                    // We expect no more data, because there are no stored users.
                    // This message should only be received in state 4.
                    if( waitForDataInStep != 4 ) {
                        Timber.w("Received ACK for CMD_USER_LIST in wrong state...");
                        if( waitForDataInStep >= 0 ){
                            Timber.w("...while waiting for other data.");
                            // We are in the wrong state.
                            // This may happen, so let's just retry whatever we did before.
                            jumpBackOneStep();
                        }
                        else {
                            Timber.w("...ignored, no data expected.");
                        }
                    }
                    // User list is empty, no more waiting.
                    waitForDataInStep = -1;
                    resumeMachineState();
                }
                else {
                    // More data should be incoming, so make sure we wait
                    stopMachineState();
                }
                break;

            case CMD_GET_SAVED_MEASUREMENTS:
                Timber.d("ACK type: CMD_GET_SAVED_MEASUREMENTS");
                int measurementCount = data[3] & 0xFF;
                Timber.d("Received ACK for CMD_GET_SAVED_MEASUREMENTS for %d measurements.", measurementCount/2);
                if (measurementCount == 0) {
                    // We expect no more data, because there are no measurements.
                    readyForData = true;
                    // This message should only be received in step 5.
                    if( waitForDataInStep != 5 ) {
                        Timber.w("Received ACK for CMD_GET_SAVED_MEASUREMENTS in wrong state...");
                        if( waitForDataInStep >= 0 ){
                            Timber.w("...while waiting for other data. Retrying last step.");
                            // We are in the wrong state.
                            // This may happen, so let's just retry whatever we did before.
                            jumpBackOneStep();
                        }
                        else {
                            Timber.w("...ignored, no data expected.");
                        }
                    }
                    // No saved data, no more waiting.
                    waitForDataInStep = -1;
                    resumeMachineState();
                }
                // Otherwise wait for CMD_SAVED_MEASUREMENT notifications which will,
                // once all measurements have been received, resume the state machine.
                else {
                    // More data should be incoming, so make sure we wait
                    stopMachineState();
                }
                break;

            case CMD_DELETE_SAVED_MEASUREMENTS:
                Timber.d("ACK type: CMD_DELETE_SAVED_MEASUREMENTS");
                if (data[3] == 0) {
                    Timber.d("Saved measurements successfully deleted for user " + currentRemoteUser.name);
                }
                // This message should only be received in state 5, 6 or 8
                if( waitForDataInStep != 5 && waitForDataInStep != 6 && waitForDataInStep != 8 ) {
                    Timber.w("Received ACK for CMD_DELETE_SAVED_MEASUREMENTS in wrong state...");
                    if( waitForDataInStep >= 0 ){
                        Timber.w("...while waiting for other data. Retrying last step.");
                        // We are in the wrong state.
                        // This may happen, so let's just retry whatever we did before.
                        jumpBackOneStep();
                    }
                    else {
                        Timber.w("...ignored, no data expected.");
                    }
                }
                // All data received, no more waiting.
                waitForDataInStep = -1;
                resumeMachineState();
                break;

            case CMD_USER_ADD:
                Timber.d("ACK type: CMD_USER_ADD");
                // This message should only be received in state 6
                if( waitForDataInStep != 6 ) {
                    Timber.w("Received ACK for CMD_USER_ADD in wrong state...");
                    if( waitForDataInStep >= 0 ){
                        Timber.w("...while waiting for other data. Retrying last step.");
                        // We are in the wrong state.
                        // This may happen, so let's just retry whatever we did before.
                        jumpBackOneStep();
                    }
                    else {
                        Timber.w("...ignored, no data expected.");
                    }
                    // No more data expected after this command.
                    waitForDataInStep = -1;
                    resumeMachineState();
                    // Get out of here, this wasn't supposed to happen.
                    break;
                }

                if (data[3] == 0) {
                    remoteUsers.add(currentRemoteUser);
                    // If we have unprocessed data available, store it now.
                    if( storedMeasurement.measurementData != null ) {
                        Timber.d("User identified, storing unprocessed data.");
                        addMeasurement(storedMeasurement.measurementData, currentRemoteUser.localUserId);
                        storedMeasurement.measurementData = null;
                    }
                    // We can now receive and process data, user has been identified and send to the scale.
                    readyForData = true;
                    // Try to start a measurement to make the scale learn the reference weight to recognize the user next time.
                    // If we already have data, this will most likely run into time-out and the scale switches off before finishing.
                    Timber.d("New user successfully added; time to step on scale");
                    sendMessage(R.string.info_step_on_scale_for_reference, 0);
                    Timber.d("Sending command: CMD_DO_MEASUREMENT");
                    sendCommand(CMD_DO_MEASUREMENT, encodeUserId(currentRemoteUser));
                    // We send a new command, so make sure we wait
                    stopMachineState();
                    break;
                }

                Timber.d("Cannot create additional scale user (error 0x%02x)", data[3]);
                sendMessage(R.string.error_max_scale_users, 0);
                // Force disconnect
                Timber.d("Terminating state machine.");
                jumpNextToStepNr( 9 );
                // All data received, no more waiting.
                waitForDataInStep = -1;
                resumeMachineState();
                break;

            case CMD_DO_MEASUREMENT:
                Timber.d("ACK type: CMD_DO_MEASUREMENT");
                if (data[3] != 0) {
                    Timber.d("Measure command rejected.");
                    // We expect no more data, because measure command was not accepted.
                    // This message should only be received in state 6 or 8
                    if( waitForDataInStep != 6 && waitForDataInStep != 8 ) {
                        Timber.w("Received ACK for CMD_DO_MEASUREMENT in wrong state...");
                        if( waitForDataInStep >= 0 ){
                            Timber.w("...while waiting for other data. Retrying last step.");
                            // We are in the wrong state.
                            // This may happen, so let's just retry whatever we did before.
                            jumpBackOneStep();
                        }
                        else {
                            Timber.w("...ignored, no data expected.");
                        }
                        // No more data expected after this command.
                        waitForDataInStep = -1;
                        resumeMachineState();
                        // Get out of here, this wasn't supposed to happen.
                        break;
                    }
                }
                else {
                    Timber.d("Measure command successfully received");
                    sendMessage(R.string.info_step_on_scale, 0);
                    // More data should be incoming, so make sure we wait
                    stopMachineState();
                }
                break;

            case CMD_USER_DETAILS:
                Timber.d("ACK type: CMD_USER_DETAILS");
                if (data[3] == 0) {
                    String name = decodeString(data, 4, 3);
                    int year = 1900 + (data[7] & 0xFF);
                    int month = 1 + (data[8] & 0xFF);
                    int day = data[9] & 0xFF;

                    int height = data[10] & 0xFF;
                    boolean male = (data[11] & 0xF0) != 0;
                    int activity = data[11] & 0x0F;

                    Timber.d("Name: %s, Birthday: %d-%02d-%02d, Height: %d, Sex: %s, activity: %d",
                            name, year, month, day, height, male ? "male" : "female", activity);
                }
                // This message should only be received in state 7
                if( waitForDataInStep != 7 ) {
                    Timber.w("Received ACK for CMD_USER_DETAILS in wrong state...");
                    if( waitForDataInStep >= 0 ){
                        Timber.w("...while waiting for other data. Retrying last step.");
                        // We are in the wrong state.
                        // This may happen, so let's just retry whatever we did before.
                        jumpBackOneStep();
                    }
                    else {
                        Timber.w("...ignored, no data expected.");
                    }
                }
                // All data received, no more waiting.
                waitForDataInStep = -1;
                resumeMachineState();
                break;

            default:
                Timber.d("Unhandled scale ack for command 0x%02x", data[2]);
                break;
        }
    }

    private float getKiloGram(byte[] data, int offset) {
        // Unit is 50 g
        return Converters.fromUnsignedInt16Be(data, offset) * 50.0f / 1000.0f;
    }

    private float getPercent(byte[] data, int offset) {
        // Unit is 0.1 %
        return Converters.fromUnsignedInt16Be(data, offset) / 10.0f;
    }

    private void addMeasurement(byte[] data, int userId) {
        long timestamp = Converters.fromUnsignedInt32Be(data, 0) * 1000;
        float weight = getKiloGram(data, 4);
        int impedance = Converters.fromUnsignedInt16Be(data, 6);
        float fat = getPercent(data, 8);
        float water = getPercent(data, 10);
        float muscle = getPercent(data, 12);
        float bone = getKiloGram(data, 14);
        int bmr = Converters.fromUnsignedInt16Be(data, 16);
        int amr = Converters.fromUnsignedInt16Be(data, 18);
        float bmi = Converters.fromUnsignedInt16Be(data, 20) / 10.0f;

        ScaleMeasurement receivedMeasurement = new ScaleMeasurement();
        receivedMeasurement.setUserId(userId);
        receivedMeasurement.setDateTime(new Date(timestamp));
        receivedMeasurement.setWeight(weight);
        receivedMeasurement.setFat(fat);
        receivedMeasurement.setWater(water);
        receivedMeasurement.setMuscle(muscle);
        receivedMeasurement.setBone(bone);

        addScaleMeasurement(receivedMeasurement);
    }

    private void writeBytes(byte[] data) {
        writeBytes(CUSTOM_SERVICE_1, CUSTOM_CHARACTERISTIC_WEIGHT, data);
    }

    private void sendCommand(byte command, byte... parameters) {
        byte[] data = new byte[parameters.length + 2];
        data[0] = startByte;
        data[1] = command;

        int i = 2;
        for (byte parameter : parameters) {
            data[i++] = parameter;
        }

        writeBytes(data);
    }

    private void sendAck(byte[] data) {
        sendCommand(CMD_APP_ACK, Arrays.copyOfRange(data, 1, 4));
    }

    private void sendAlternativeStartCode(int id, byte... parameters) {
        byte[] data = new byte[parameters.length + 1];
        data[0] = getAlternativeStartByte(id);

        int i = 1;
        for (byte parameter : parameters) {
            data[i++] = parameter;
        }

        writeBytes(data);
    }

    private void createRemoteUser(ScaleUser scaleUser) {
        Timber.d("Create user: %s", scaleUser.getUserName());

        Calendar cal = Calendar.getInstance();
        cal.setTime(scaleUser.getBirthday());

        // We can only use up to 3 characters (padding with 0 if needed)
        byte[] nick = Arrays.copyOf(convertUserNameToScale(scaleUser).getBytes(), 3);
        byte year = (byte) (cal.get(Calendar.YEAR) - 1900);
        byte month = (byte) cal.get(Calendar.MONTH);
        byte day = (byte) cal.get(Calendar.DAY_OF_MONTH);
        byte height = (byte) scaleUser.getBodyHeight();
        byte sex = scaleUser.getGender().isMale() ? (byte) 0x80 : 0;
        byte activity = (byte) (scaleUser.getActivityLevel().toInt() + 1); // activity level: 1 - 5

        long maxUserId = remoteUsers.isEmpty() ? 100 : 0;
        for (RemoteUser remoteUser : remoteUsers) {
            maxUserId = Math.max(maxUserId, remoteUser.remoteUserId);
        }

        currentRemoteUser = new RemoteUser(maxUserId + 1, new String(nick), 1900 + year);
        currentRemoteUser.localUserId = scaleUser.getId();
        currentRemoteUser.isNew = true;

        byte[] uid = encodeUserId(currentRemoteUser);

        Timber.d("Sending command: CMD_USER_ADD");
        sendCommand(CMD_USER_ADD, uid[0], uid[1], uid[2], uid[3], uid[4], uid[5], uid[6], uid[7],
                nick[0], nick[1], nick[2], year, month, day, height, (byte) (sex | activity));
    }
}
