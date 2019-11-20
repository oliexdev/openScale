/* Copyright (C) 2019 olie.xdev <olie.xdev@googlemail.com>
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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.SoehnleLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothSoehnle extends BluetoothCommunication {
    private final UUID WEIGHT_CUSTOM_SERVICE = UUID.fromString("352e3000-28e9-40b8-a361-6db4cca4147c");
    private final UUID WEIGHT_CUSTOM_A_CHARACTERISTIC = UUID.fromString("352e3001-28e9-40b8-a361-6db4cca4147c"); // notify, read
    private final UUID WEIGHT_CUSTOM_B_CHARACTERISTIC = UUID.fromString("352e3004-28e9-40b8-a361-6db4cca4147c"); // notify, read
    private final UUID WEIGHT_CUSTOM_CMD_CHARACTERISTIC = UUID.fromString("352e3002-28e9-40b8-a361-6db4cca4147c"); // write

    SharedPreferences prefs;

    public BluetoothSoehnle(Context context) {
        super(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    public String driverName() {
        return "Soehnle Scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                List<ScaleUser> openScaleUserList = OpenScale.getInstance().getScaleUserList();

                int index = -1;

                // check if an openScale user is stored as a Soehnle user otherwise do a factory reset
                for (ScaleUser openScaleUser : openScaleUserList) {
                    index = getSoehnleUserIndex(openScaleUser.getId());
                    if (index != -1) {
                        break;
                    }
                }

                if (index == -1) {
                    invokeScaleFactoryReset();
                }
                break;
            case 1:
                setNotificationOn(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                readBytes(BluetoothGattUuid.SERVICE_BATTERY_LEVEL, BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL);
                break;
            case 2:
                // Write the current time
                BluetoothBytesParser parser = new BluetoothBytesParser();
                parser.setCurrentTime(Calendar.getInstance());
                writeBytes(BluetoothGattUuid.SERVICE_CURRENT_TIME, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME, parser.getValue());
                break;
            case 3:
                // Turn on notification for User Data Service
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT);
                break;
            case 4:
                int openScaleUserId = OpenScale.getInstance().getSelectedScaleUserId();
                int soehnleUserIndex = getSoehnleUserIndex(openScaleUserId);

                if (soehnleUserIndex == -1) {
                    // create new user
                    Timber.d("create new Soehnle scale user");
                    writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, new byte[]{(byte)0x01, (byte)0x00, (byte)0x00});
                } else {
                    // select user
                    Timber.d("select Soehnle scale user with index " + soehnleUserIndex);
                    writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, new byte[]{(byte) 0x02, (byte) soehnleUserIndex, (byte) 0x00, (byte) 0x00});
                }
                break;
            case 5:
                // set age
                writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_AGE, new byte[]{(byte)OpenScale.getInstance().getSelectedScaleUser().getAge()});
                break;
            case 6:
                // set gender
                writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_GENDER, new byte[]{OpenScale.getInstance().getSelectedScaleUser().getGender().isMale() ? (byte)0x00 : (byte)0x01});
                break;
            case 7:
                // set height
                writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_HEIGHT, Converters.toInt16Le((int)OpenScale.getInstance().getSelectedScaleUser().getBodyHeight()));
                break;
            case 8:
                setNotificationOn(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_A_CHARACTERISTIC);
                setNotificationOn(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_B_CHARACTERISTIC);
                //writeBytes(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_CMD_CHARACTERISTIC, new byte[] {(byte)0x0c, (byte)0xff});
                break;
            case 9:
                for (int i=1; i<8; i++) {
                    // get history data for soehnle user index i
                    writeBytes(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_CMD_CHARACTERISTIC, new byte[]{(byte) 0x09, (byte) i});
                }
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        Timber.d("on bluetooth notify change " + byteInHex(value) + " on " + characteristic.toString());

        if (value == null) {
            return;
        }

        if (characteristic.equals(WEIGHT_CUSTOM_A_CHARACTERISTIC) && value.length == 15) {
            if (value[0] == (byte) 0x09) {
                handleWeightMeasurement(value);
            }
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT)) {
            handleUserControlPoint(value);
        } else if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_BATTERY_LEVEL)) {
            int batteryLevel = value[0];

            Timber.d("Soehnle scale battery level is " + batteryLevel);
            if (batteryLevel <= 10) {
                sendMessage(R.string.info_scale_low_battery, batteryLevel);
            }
        }
    }

    private void handleUserControlPoint(byte[] value) {
        if (value[0] == (byte)0x20) {
            int cmd = value[1];

            if (cmd == (byte)0x01) { // user create
                int userId = OpenScale.getInstance().getSelectedScaleUserId();
                int success = value[2];
                int soehnleUserIndex = value[3];

                if (success == (byte)0x01) {
                    Timber.d("User control point index is " + soehnleUserIndex + " for user id " + userId);

                    prefs.edit().putInt("userScaleIndex" + soehnleUserIndex, userId).apply();
                    sendMessage(R.string.info_step_on_scale_for_reference, 0);
                } else {
                    Timber.e("Error creating new Sohnle user");
                }
            }
            else if (cmd == (byte)0x02) { // user select
                int success = value[2];

                if (success != (byte)0x01) {
                    Timber.e("Error selecting Soehnle user");

                    invokeScaleFactoryReset();
                    jumpNextToStepNr(0);
                }
            }
        }
    }

    private int getSoehnleUserIndex(int openScaleUserId) {
        for (int i= 1; i<8; i++) {
            int prefOpenScaleUserId = prefs.getInt("userScaleIndex"+i, -1);

            if (openScaleUserId == prefOpenScaleUserId) {
                return i;
            }
        }

        return -1;
    }

    private void invokeScaleFactoryReset() {
        Timber.d("Do a factory reset on Soehnle scale to swipe old users");
        // factory reset
        writeBytes(WEIGHT_CUSTOM_SERVICE, WEIGHT_CUSTOM_CMD_CHARACTERISTIC, new byte[]{(byte) 0x0b, (byte) 0xff});

        for (int i= 1; i<8; i++) {
            prefs.edit().putInt("userScaleIndex" + i, -1).apply();
        }
    }

    private void handleWeightMeasurement(byte[] value) {
        float weight = Converters.fromUnsignedInt16Be(value, 9) / 10.0f; // kg
        int soehnleUserIndex = (int) value[1];
        final int year = Converters.fromUnsignedInt16Be(value, 2);
        final int month = (int) value[4];
        final int day = (int) value[5];
        final int hours = (int) value[6];
        final int min = (int) value[7];
        final int sec = (int) value[8];

        final int imp5 = Converters.fromUnsignedInt16Be(value, 11);
        final int imp50 = Converters.fromUnsignedInt16Be(value, 13);

        String date_string = year + "/" + month + "/" + day + "/" + hours + "/" + min;
        Date date_time = new Date();
        try {
            date_time = new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string);
        } catch (ParseException e) {
            Timber.e("parse error " + e.getMessage());
        }

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        int activityLevel = 0;

        switch (scaleUser.getActivityLevel()) {
            case SEDENTARY:
                activityLevel = 0;
                break;
            case MILD:
                activityLevel = 1;
                break;
            case MODERATE:
                activityLevel = 2;
                break;
            case HEAVY:
                activityLevel = 4;
                break;
            case EXTREME:
                activityLevel = 5;
                break;
        }

        int openScaleUserId = prefs.getInt("userScaleIndex"+soehnleUserIndex, -1);

        if (openScaleUserId == -1) {
            Timber.e("Unknown Soehnle user index " + soehnleUserIndex);
        } else {
            SoehnleLib soehnleLib = new SoehnleLib(scaleUser.getGender().isMale(), scaleUser.getAge(), scaleUser.getBodyHeight(), activityLevel);

            ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

            scaleMeasurement.setUserId(openScaleUserId);
            scaleMeasurement.setWeight(weight);
            scaleMeasurement.setDateTime(date_time);
            scaleMeasurement.setWater(soehnleLib.getWater(weight, imp50));
            scaleMeasurement.setFat(soehnleLib.getFat(weight, imp50));
            scaleMeasurement.setMuscle(soehnleLib.getMuscle(weight, imp50, imp5));

            addScaleMeasurement(scaleMeasurement);
        }
    }
}
