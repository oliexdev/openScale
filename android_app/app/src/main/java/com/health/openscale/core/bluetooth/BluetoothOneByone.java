/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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
import android.widget.Toast;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.UUID;

public class BluetoothOneByone extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE_BODY_COMPOSITION = UUID.fromString("0000181B-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION = UUID.fromString("00002A9C-0000-1000-8000-00805f9b34fb"); // read, indication

    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"); // write only
    private final UUID CMD_MEASUREMENT_CUSTOM_CHARACTERISTIC = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb"); // notify only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public BluetoothOneByone(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "1byone";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CUSTOM_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 2:
                byte[] magicBytes = {(byte)0xfd,(byte)0x37,(byte)0x01,(byte)0x01,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00};
                magicBytes[magicBytes.length - 1] =
                        xorChecksum(magicBytes, 0, magicBytes.length - 1);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            case 3:
                final ScaleUser selectedUser = OpenScale.getInstance(context).getSelectedScaleUser();

                byte userId = (byte) 0x01;
                byte sex = selectedUser.getGender().isMale() ? (byte) 0x01 : (byte) 0x00;
                // 0x00 = ordinary, 0x01 = amateur, 0x02 = professional
                byte exerciseLevel = (byte) 0x00;
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

                byte[] magicBytes2 = {(byte) 0xfe, userId, sex, exerciseLevel, height, age, unit, (byte) 0x00};
                magicBytes2[magicBytes2.length - 1] =
                        xorChecksum(magicBytes2, 1, magicBytes2.length - 2);

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes2);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        return false;
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        Toast.makeText(context, "Log Data: " + byteInHex(data), Toast.LENGTH_LONG).show();

        // if data is valid data
        if (data != null && data.length == 16) {
            parseBytes(data);
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = Converters.fromUnsignedInt16Be(weightBytes, 4) / 10.0f; // kg
        float fat = Converters.fromUnsignedInt16Be(weightBytes, 6) / 10.0f; // %
        float bone = weightBytes[8] & 0xFF; // %
        float muscle = Converters.fromUnsignedInt16Be(weightBytes, 9) / 10.0f; // %
        float visceralFat = weightBytes[11] & 0xFF; // %
        float water = Converters.fromUnsignedInt16Be(weightBytes, 12) / 10.0f; // %
        float bmr = Converters.fromUnsignedInt16Be(weightBytes, 14); // kCal

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        scaleBtData.setWeight(weight);
        scaleBtData.setFat(fat);
        scaleBtData.setMuscle(muscle);
        scaleBtData.setWater(water);
        scaleBtData.setBone(bone);

        addScaleData(scaleBtData);
    }
}
