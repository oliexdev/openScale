/* Copyright (C) 2017  olie.xdev <olie.xdev@googlemail.com>
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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Date;
import java.util.UUID;

public class BluetoothExingtechY1 extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("f433bd80-75b8-11e2-97d9-0002a5d5c51b");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("1a2ea400-75b9-11e2-be05-0002a5d5c51b"); // read, notify
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("29f11080-75b9-11e2-8bf6-0002a5d5c51b"); // write only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public BluetoothExingtechY1(Context context) {
        super(context);
    }

    @Override
    public String deviceName() {
        return "Exingtech Y1";
    }

    @Override
    public String defaultDeviceName() {
        return "VScale";
    }

    @Override
    boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

                byte gender = selectedUser.getGender().isMale() ? (byte)0x00 : (byte)0x01; // 00 - male; 01 - female
                byte height = (byte)(selectedUser.getBodyHeight() & 0xff); // cm
                byte age = (byte)(selectedUser.getAge(new Date()) & 0xff);

                int userId = selectedUser.getId();

                byte cmdByte[] = {(byte)0x10, (byte)userId, gender, age, height};

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, cmdByte);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    boolean nextCleanUpCmd(int stateNr) {
        return false;
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        if (data != null && data.length > 0) {
            // if data is body scale type
            if (data[0] == (byte)0x01 && data.length == 20) {
                parseBytes(data);
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        int userId = (int)(weightBytes[0] & 0x0F);
        int gender = (int)(weightBytes[1]); // 0x00 male; 0x01 female
        int age = (int)(weightBytes[2]); // 10 ~ 99
        int height = (int)(weightBytes[3]); // 0 ~ 255
        float weight = (float) (((weightBytes[4] & 0xFF) << 8) | (weightBytes[5] & 0xFF)) / 10.0f; // kg
        float fat = (float)(((weightBytes[6] & 0xFF) << 8) | (weightBytes[7] & 0xFF)) / 10.0f; // %
        float water = (float)(((weightBytes[8] & 0xFF) << 8) | (weightBytes[9] & 0xFF)) / 10.0f; // %
        float bone = (float)(((weightBytes[10] & 0xFF) << 8) | (weightBytes[11] & 0xFF)) / weight * 10.0f; // kg
        float muscle = (float)(((weightBytes[12] & 0xFF) << 8) | (weightBytes[13] & 0xFF)) / 10.0f; // %
        float visc_muscle = (float)(weightBytes[14] & 0xFF); // %
        float calorie = (float)(((weightBytes[15] & 0xFF) << 8) | (weightBytes[16] & 0xFF));
        float bmi = (float)(((weightBytes[17] & 0xFF) << 8) | (weightBytes[18] & 0xFF)) / 10.0f;

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

        scaleBtData.setConvertedWeight(weight, selectedUser.getScaleUnit());
        scaleBtData.setFat(fat);
        scaleBtData.setMuscle(muscle);
        scaleBtData.setWater(water);
        scaleBtData.setBone(bone);
        scaleBtData.setDateTime(new Date());

        addScaleData(scaleBtData);
    }
}
