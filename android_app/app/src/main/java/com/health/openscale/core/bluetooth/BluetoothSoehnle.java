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
import java.util.UUID;

import timber.log.Timber;

public class BluetoothSoehnle extends BluetoothCommunication {
    public BluetoothSoehnle(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Soehnle Scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // Turn on notification for Weight Service
                setNotificationOn(BluetoothGattUuid.SERVICE_WEIGHT_SCALE, BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT);
                break;
            case 1:
                // Turn on notification for Body Composition Service
                setNotificationOn(BluetoothGattUuid.SERVICE_BODY_COMPOSITION, BluetoothGattUuid.CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT);
                break;
            case 2:
                // Write the current time
                BluetoothBytesParser parser = new BluetoothBytesParser();
                parser.setCurrentTime(Calendar.getInstance());
                writeBytes(BluetoothGattUuid.SERVICE_CURRENT_TIME, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME, parser.getValue());
                break;
            case 3:
                // Turn on notification for User Data Service
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_CHANGE_INCREMENT);
                setNotificationOn(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT);
                break;
            case 4:
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

                int userId = OpenScale.getInstance().getSelectedScaleUserId();
                int userScaleIndex = prefs.getInt("userScaleIndex"+userId, -1);

                if (userScaleIndex == -1) {
                    // create new user
                    Timber.d("create new scale user");
                    writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, new byte[]{(byte)0x01, (byte)0x00, (byte)0x00});
                } else {
                    // select user
                    Timber.d("select scale user with index " + userScaleIndex);
                    writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT, new byte[]{(byte) 0x02, (byte) userScaleIndex, (byte) 0x00, (byte) 0x00});
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
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        Timber.d("on bluetooth notify change " + byteInHex(value) + " on " + characteristic.toString());

        if (value != null && value.length == 14 ) {
            if (value[0] == (byte)0x09) {
                handleWeightMeasurement(value);
            }
        }

        if (value != null && characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_USER_CONTROL_POINT)) {
            handleUserControlPoint(value);
        }
    }

    private void handleUserControlPoint(byte[] value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int userId = OpenScale.getInstance().getSelectedScaleUserId();
        int index = value[0];

        Timber.d("User control point index is "+ index + " for user id " + userId);

        prefs.edit().putInt("userScaleIndex"+userId, index).apply();
    }

    private void handleWeightMeasurement(byte[] value) {
        float weight = Converters.fromUnsignedInt16Be(value, 9) / 10.0f; // kg
        final int year = Converters.fromUnsignedInt16Le(value, 2);
        final int month = (int) value[4];
        final int day = (int) value[5];
        final int hours = (int) value[6];
        final int min = (int) value[7];
        final int sec = (int) value[8];

        final int imp5 = Converters.fromUnsignedInt16Le(value, 11);
        final int imp50 = Converters.fromUnsignedInt16Le(value, 13);

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

        SoehnleLib soehnleLib = new SoehnleLib(scaleUser.getGender().isMale(), scaleUser.getAge(), scaleUser.getBodyHeight(), activityLevel);

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        scaleMeasurement.setWeight(weight);
        scaleMeasurement.setDateTime(date_time);
        scaleMeasurement.setWater(soehnleLib.getWater(weight, imp50));
        scaleMeasurement.setFat(soehnleLib.getFat(weight, imp50));
        scaleMeasurement.setMuscle(soehnleLib.getMuscle(weight, imp50, imp5));

        addScaleMeasurement(scaleMeasurement);
    }
}
