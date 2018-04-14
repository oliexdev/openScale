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

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

public class BluetoothExcelvanCF369BLE extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000FFF1-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_CUSTOM0_CHARACTERISTIC = UUID.fromString("0000FFF4-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private byte[] receivedData = new byte[]{};

    public BluetoothExcelvanCF369BLE(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Excelvan CF369BLE";
    }

    @Override
    boolean nextInitCmd(int stateNr) {
        return false;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

                byte sex = selectedUser.getGender().isMale() ? (byte)0x01 : (byte)0x00; // 01 - male; 00 - female
                byte height = (byte)(selectedUser.getBodyHeight() & 0xff); // cm
                byte age = (byte)(selectedUser.getAge(new Date()) & 0xff);
                byte unit = 0x01; // kg

                switch (selectedUser.getScaleUnit()) {
                    case LB:
                        unit = 0x02;
                        break;
                    case ST:
                        unit = 0x04;
                        break;
                }

                byte xor_checksum = (byte)((byte)(0x01) ^ sex ^ (byte)(0x01) ^ height ^ age ^ unit);

                byte[] configBytes = {(byte)(0xfe), (byte)(0x01), sex, (byte)(0x01), height, age, unit, xor_checksum};

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, configBytes);
                break;
            case 1:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_CUSTOM0_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            default:
                return false;
        }

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
            if (data.length == 16 && data[0] == (byte)0xcf) {
                if (!Arrays.equals(data, receivedData)) { // accepts only one data of the same content
                    receivedData = data;
                    parseBytes(data);
                }
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = (float) (((weightBytes[4] & 0xFF) << 8) | (weightBytes[5] & 0xFF)) / 10.0f;
        float fat = (float)(((weightBytes[6] & 0xFF) << 8) | (weightBytes[7] & 0xFF)) / 10.0f;
        float bone = (float)(weightBytes[8]) / 10.0f;
        float muscle = (float)(((weightBytes[9] & 0xFF) << 8) | (weightBytes[10] & 0xFF)) / 10.0f;
        float viscal_fat = (float)(weightBytes[11]);
        float water = (float)(((weightBytes[12] & 0xFF) << 8) | (weightBytes[13] & 0xFF)) / 10.0f;
        float bmr = (float)(((weightBytes[14] & 0xFF) << 8) | (weightBytes[15] & 0xFF));

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
