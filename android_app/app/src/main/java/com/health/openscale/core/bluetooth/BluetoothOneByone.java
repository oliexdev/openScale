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

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;
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
        return "1byone scale";
    }

    @Override
    boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CUSTOM_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 2:
                byte[] magicBytes = {(byte)0xfd,(byte)0x37,(byte)0x01,(byte)0x01,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0xca};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            case 3:
                byte[] magicBytes2 = {(byte)0xfe, (byte)0x01, (byte)0x01, (byte)0x00, (byte)0xaa, (byte)0x2d, (byte)0x02, (byte)0x85};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes2);
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

        Toast.makeText(context, "Log Data: " + byteInHex(data), Toast.LENGTH_LONG).show();

        if (data != null && data.length > 0) {
            // if data is valid data
            if (data.length == 16) {
                parseBytes(data);
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = (float) (((weightBytes[4] & 0xFF) << 8) | (weightBytes[5] & 0xFF)) / 10.0f; // kg
        float fat = (float)(((weightBytes[6] & 0xFF) << 8) | (weightBytes[7] & 0xFF)) / 10.0f; // %
        float bone = (float)(((weightBytes[8] & 0xFF) & 0xFF)) / 10.0f; // %
        float muscle = (float)(((weightBytes[9] & 0xFF) << 8) | (weightBytes[10] & 0xFF)) / 10.0f; // %
        float visfat = (float)(((weightBytes[11] & 0xFF) & 0xFF)) / 10.0f; // %
        float water = (float)(((weightBytes[12] & 0xFF) << 8) | (weightBytes[13] & 0xFF)) / 10.0f; // %
        float bmr = (float)(((weightBytes[14] & 0xFF) << 8) | (weightBytes[15] & 0xFF)); // kcal

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        scaleBtData.setWeight(weight);
        scaleBtData.setFat(fat);
        scaleBtData.setMuscle(muscle);
        scaleBtData.setWater(water);
        scaleBtData.setBone(bone);
        scaleBtData.setDateTime(new Date());

        addScaleData(scaleBtData);
    }
}
