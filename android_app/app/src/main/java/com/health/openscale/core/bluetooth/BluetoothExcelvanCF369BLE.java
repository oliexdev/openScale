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
import com.health.openscale.core.utils.Converters;

import java.util.Arrays;
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
    protected boolean nextInitCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

                byte userId = (byte) 0x01;
                byte sex = selectedUser.getGender().isMale() ? (byte) 0x01 : (byte) 0x00;
                // 0x00 = ordinary, 0x01 = amateur, 0x02 = professional
                byte exerciseLevel = (byte) 0x01;
                byte height = (byte) selectedUser.getBodyHeight();
                byte age = (byte) selectedUser.getAge();

                byte unit = 0x01; // kg
                switch (selectedUser.getScaleUnit()) {
                    case LB:
                        unit = 0x02;
                        break;
                    case ST:
                        unit = 0x04;
                        break;
                }

                byte[] configBytes = {(byte) 0xfe, userId, sex, exerciseLevel, height, age, unit, (byte) 0x00};
                configBytes[configBytes.length - 1] =
                        xorChecksum(configBytes, 1, configBytes.length - 2);

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
    protected boolean nextCleanUpCmd(int stateNr) {
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
        float weight = Converters.fromUnsignedInt16Be(weightBytes, 4) / 10.0f;
        float fat = Converters.fromUnsignedInt16Be(weightBytes, 6) / 10.0f;
        float bone = (weightBytes[8] & 0xFF) / 10.0f;
        float muscle = Converters.fromUnsignedInt16Be(weightBytes, 9) / 10.0f;
        float visceralFat = weightBytes[11] & 0xFF;
        float water = Converters.fromUnsignedInt16Be(weightBytes, 12) / 10.0f;
        float bmr = Converters.fromUnsignedInt16Be(weightBytes, 14);

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

        scaleBtData.setConvertedWeight(weight, selectedUser.getScaleUnit());
        scaleBtData.setFat(fat);
        scaleBtData.setMuscle(muscle);
        scaleBtData.setWater(water);
        scaleBtData.setBone(bone);

        addScaleData(scaleBtData);
    }
}
