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
import java.util.Random;
import java.util.UUID;

public class BluetoothYunmaiSE extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_CMD_SERVICE = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_CMD_CHARACTERISTIC = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb");

    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public BluetoothYunmaiSE(Context context) {
        super(context);
    }

    @Override
    public String deviceName() {
        return "Yunmai SE";
    }

    @Override
    public String defaultDeviceName() {
        return "YUNMAI-ISSE-US";
    }

    @Override
    public boolean checkDeviceName(String btDeviceName) {
        if (btDeviceName.startsWith("YUNMAI-ISSE")) {
            return true;
        }

        return false;
    }

    @Override
    boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                int user_id = getUniqueNumber();

                final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();
                byte sex = selectedUser.isMale() ? (byte)0x01 : (byte)0x02;
                byte display_unit = selectedUser.getScaleUnit() == 0 ? (byte) 0x01 : (byte) 0x02;

                byte[] user_add_or_query = new byte[]{(byte)0x0d, (byte)0x12, (byte)0x10, (byte)0x01, (byte)0x00, (byte) 0x00, (byte) ((user_id & 0xFF00) >> 8), (byte) ((user_id & 0xFF) >> 0), (byte)selectedUser.getBodyHeight(), (byte)sex, (byte) selectedUser.getAge(new Date()), (byte) 0x55, (byte) 0x5a, (byte) 0x00, (byte)0x00, (byte) display_unit, (byte) 0x03, (byte) 0x00 };
                user_add_or_query[17] = xor_checksum(user_add_or_query);
                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, user_add_or_query);
                break;
            case 1:
                long unix_time = new Date().getTime() / 1000;

                byte[] set_time = new byte[]{(byte)0x0d, (byte) 0x0d, (byte) 0x11, (byte)((unix_time & 0xFF000000) >> 32), (byte)((unix_time & 0xFF0000) >> 16), (byte)((unix_time & 0xFF00) >> 8), (byte)((unix_time & 0xFF) >> 0), (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};

                set_time[12] = xor_checksum(set_time);
                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, set_time);
                break;
            case 2:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 3:
                byte[] magic_bytes = new byte[]{(byte)0x0d, (byte)0x05, (byte)0x13, (byte)0x00, (byte)0x16};
                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, magic_bytes);
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

            // if finished weighting?
            if (data[3] == 0x02) {
                parseBytes(data);
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        long unix_timestamp = ((weightBytes[5] & 0xFF) << 24) | ((weightBytes[6] & 0xFF) << 16) | ((weightBytes[7] & 0xFF) << 8) | (weightBytes[8] & 0xFF);
        Date btDate = new Date();
        btDate.setTime(unix_timestamp*1000);

        float weight = (float) (((weightBytes[13] & 0xFF) << 8) | (weightBytes[14] & 0xFF)) / 100.0f;

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

        scaleBtData.setConvertedWeight(weight, selectedUser.getScaleUnit());
        scaleBtData.setDateTime(btDate);

        addScaleData(scaleBtData);
    }

    private int getUniqueNumber() {
        int uniqueNumber;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        uniqueNumber = prefs.getInt("uniqueNumber", 0x00);

        if (uniqueNumber == 0x00) {
            Random r = new Random();
            uniqueNumber = r.nextInt(65535 - 100 + 1) + 100;

            prefs.edit().putInt("uniqueNumber", uniqueNumber).commit();
        }

        int userId = prefs.getInt("selectedUserId", -1);

        return uniqueNumber + userId;
    }

    private byte xor_checksum(byte[] data) {
        byte checksum = 0x00;

        for (int i=0; i<data.length-1; i++) {
            checksum ^= data[i];
        }

        return checksum;
    }
}
