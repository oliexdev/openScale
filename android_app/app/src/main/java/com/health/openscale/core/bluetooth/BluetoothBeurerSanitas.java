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
package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
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
    enum DeviceType { BEURER_BF700_800_RT_LIBRA, BEURER_BF710, SANITAS_SBF70_70 }

    private static final UUID CUSTOM_SERVICE_1 = BluetoothGattUuid.fromShortCode(0xffe0);
    private static final UUID CUSTOM_CHARACTERISTIC_WEIGHT = BluetoothGattUuid.fromShortCode(0xffe1);

    private final DeviceType deviceType;
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

    private ArrayList<RemoteUser> remoteUsers = new ArrayList<>();
    private RemoteUser currentRemoteUser;
    private byte[] measurementData;

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

    public BluetoothBeurerSanitas(Context context, DeviceType deviceType) {
        super(context);

        this.deviceType = deviceType;
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

    @Override
    public String driverName() {
        switch (deviceType) {
            case BEURER_BF700_800_RT_LIBRA:
                return "Beurer BF700/800 / Runtastic Libra";
            case BEURER_BF710:
                return "Beurer BF710";
            case SANITAS_SBF70_70:
                return "Sanitas SBF70/SilverCrest SBF75";
        }

        return "Unknown device type";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // Setup notification
                setNotificationOn(CUSTOM_SERVICE_1, CUSTOM_CHARACTERISTIC_WEIGHT);
                break;
            case 1:
                // Say "Hello" to the scale and wait for ack
                sendAlternativeStartCode(ID_START_NIBBLE_INIT, (byte) 0x01);
                stopMachineState();
                break;
            case 2:
                // Update time on the scale (no ack)
                long unixTime = System.currentTimeMillis() / 1000L;
                sendAlternativeStartCode(ID_START_NIBBLE_SET_TIME, Converters.toInt32Be(unixTime));
                break;
            case 3:
                // Request scale status and wait for ack
                sendCommand(CMD_SCALE_STATUS, encodeUserId(null));
                stopMachineState();
                break;
            case 4:
                // Request list of all users and wait until all have been received
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
                    Timber.d("Request saved measurements for %s", currentRemoteUser.name);
                    sendCommand(CMD_GET_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));
                    stopMachineState();
                }
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
                    createRemoteUser(selectedUser);
                    stopMachineState();
                }
                break;
            case 7:
                sendCommand(CMD_USER_DETAILS, encodeUserId(currentRemoteUser));
                stopMachineState();
                break;
            case 8:
                if (currentRemoteUser != null && !currentRemoteUser.isNew) {
                    sendCommand(CMD_DO_MEASUREMENT, encodeUserId(currentRemoteUser));
                    stopMachineState();
                } else {
                    return false;
                }
                break;
            default:
                // Finish init if everything is done
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        byte[] data = value;
        if (data == null || data.length == 0) {
            return;
        }

        if (data[0] == getAlternativeStartByte(ID_START_NIBBLE_INIT)) {
            Timber.d("Got init ack from scale; scale is ready");
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
                    processUserInfo(data);
                    break;
                case CMD_SAVED_MEASUREMENT:
                    processSavedMeasurement(data);
                    break;
                case CMD_WEIGHT_MEASUREMENT:
                    processWeightMeasurement(data);
                    break;
                case CMD_MEASUREMENT:
                    processMeasurement(data);
                    break;
                case CMD_SCALE_ACK:
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

        sendAck(data);

        if (current != count) {
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

        // All users received
        resumeMachineState();
    }

    private void processMeasurementData(byte[] data, int offset, boolean firstPart) {
        if (firstPart) {
            measurementData = Arrays.copyOfRange(data, offset, data.length);
            return;
        }

        int oldEnd = measurementData.length;
        int toCopy = data.length - offset;

        measurementData = Arrays.copyOf(measurementData, oldEnd + toCopy);
        System.arraycopy(data, offset, measurementData, oldEnd, toCopy);

        addMeasurement(measurementData, currentRemoteUser.localUserId);
        measurementData = null;
    }

    private void processSavedMeasurement(byte[] data) {
        int count = data[2] & 0xFF;
        int current = data[3] & 0xFF;

        processMeasurementData(data, 4, current % 2 == 1);

        sendAck(data);

        if (current == count) {
            Timber.d("Deleting saved measurements for %s", currentRemoteUser.name);
            sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));

            if (currentRemoteUser.remoteUserId != remoteUsers.get(remoteUsers.size() - 1).remoteUserId) {
                jumpNextToStepNr(5);
                resumeMachineState();
            }
        }
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

        if (current == 1) {
            long uid = decodeUserId(data, 5);
            currentRemoteUser = null;
            for (RemoteUser remoteUser : remoteUsers) {
                if (remoteUser.remoteUserId == uid) {
                    currentRemoteUser = remoteUser;
                    break;
                }
            }
        }
        else {
            processMeasurementData(data, 4, current == 2);
        }

        sendAck(data);

        if (current == count) {
            sendCommand(CMD_DELETE_SAVED_MEASUREMENTS, encodeUserId(currentRemoteUser));
        }
    }

    private void processScaleAck(byte[] data) {
        switch (data[2]) {
            case CMD_SCALE_STATUS:
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
                    Timber.d("Set scale unit to %s (%d)", user.getScaleUnit(), requestedUnit);
                    sendCommand(CMD_SET_UNIT, requestedUnit);
                } else {
                    resumeMachineState();
                }
                break;

            case CMD_SET_UNIT:
                if (data[3] == 0) {
                    Timber.d("Scale unit successfully set");
                }
                resumeMachineState();
                break;

            case CMD_USER_LIST:
                int userCount = data[4] & 0xFF;
                int maxUserCount = data[5] & 0xFF;
                Timber.d("Have %d users (max is %d)", userCount, maxUserCount);
                if (userCount == 0) {
                    resumeMachineState();
                }
                // Otherwise wait for CMD_USER_INFO notifications
                break;

            case CMD_GET_SAVED_MEASUREMENTS:
                int measurementCount = data[3] & 0xFF;
                if (measurementCount == 0) {
                    // Skip delete all measurements step (since there are no measurements to delete)
                    Timber.d("No saved measurements found for user " + currentRemoteUser.name);
                    jumpNextToStepNr(5);
                    resumeMachineState();
                }
                // Otherwise wait for CMD_SAVED_MEASUREMENT notifications which will,
                // once all measurements have been received, resume the state machine.
                break;

            case CMD_DELETE_SAVED_MEASUREMENTS:
                if (data[3] == 0) {
                    Timber.d("Saved measurements successfully deleted for user " + currentRemoteUser.name);
                }
                resumeMachineState();
                break;

            case CMD_USER_ADD:
                if (data[3] == 0) {
                    Timber.d("New user successfully added; time to step on scale");
                    sendMessage(R.string.info_step_on_scale_for_reference, 0);
                    remoteUsers.add(currentRemoteUser);
                    sendCommand(CMD_DO_MEASUREMENT, encodeUserId(currentRemoteUser));
                    break;
                }

                Timber.d("Cannot create additional scale user (error 0x%02x)", data[3]);
                sendMessage(R.string.error_max_scale_users, 0);
                // Force disconnect
                Timber.d("Send disconnect command to scale");
                jumpNextToStepNr(8);
                resumeMachineState();
                break;

            case CMD_DO_MEASUREMENT:
                if (data[3] == 0) {
                    Timber.d("Measure command successfully received");
                }
                break;

            case CMD_USER_DETAILS:
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

        sendCommand(CMD_USER_ADD, uid[0], uid[1], uid[2], uid[3], uid[4], uid[5], uid[6], uid[7],
                nick[0], nick[1], nick[2], year, month, day, height, (byte) (sex | activity));
    }
}
