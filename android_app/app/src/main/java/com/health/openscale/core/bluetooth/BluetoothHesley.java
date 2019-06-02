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

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;
import java.util.UUID;

public class BluetoothHesley extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff4); // read, notify
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1); // write only

    public BluetoothHesley(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Hesley scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                byte[] magicBytes = {(byte)0xa5, (byte)0x01, (byte)0x2c, (byte)0xab, (byte)0x50, (byte)0x5a, (byte)0x29};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            case 2:
                sendMessage(R.string.info_step_on_scale, 0);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;


        if (data != null && data.length > 0) {
            if (data.length == 20) {
                parseBytes(data);


            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        int bodyage = (int)(weightBytes[17]); // 10 ~ 99

        float weight = (float) (((weightBytes[2] & 0xFF) << 8) | (weightBytes[3] & 0xFF)) / 100.0f; // kg
        float fat = (float)(((weightBytes[4] & 0xFF) << 8) | (weightBytes[5] & 0xFF)) / 10.0f; // %
        float water = (float)(((weightBytes[8] & 0xFF) << 8) | (weightBytes[9] & 0xFF)) / 10.0f; // %
        float muscle = (float)(((weightBytes[10] & 0xFF) << 8) | (weightBytes[11] & 0xFF)) / 10.0f; // %
        float bone = (float)(((weightBytes[12] & 0xFF) << 8) | (weightBytes[13] & 0xFF)) / 10.0f; // %
        float calorie = (float)(((weightBytes[14] & 0xFF) << 8) | (weightBytes[15] & 0xFF)); // kcal

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        scaleBtData.setWeight(weight);
        scaleBtData.setFat(fat);
        scaleBtData.setMuscle(muscle);
        scaleBtData.setWater(water);
        scaleBtData.setBone(bone);
        scaleBtData.setDateTime(new Date());

        addScaleMeasurement(scaleBtData);
    }
}