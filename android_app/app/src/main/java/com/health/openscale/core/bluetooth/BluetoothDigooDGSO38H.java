/* Copyright (C) 2017  Murgi <fabian@murgi.de>
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

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothDigooDGSO38H extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private final UUID EXTRA_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public BluetoothDigooDGSO38H(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Digoo DG-SO38H";
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        if (data != null && data.length > 0) {

            if (data.length == 20) {
                parseBytes(data);
            }
        }
    }


    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                //Tell device to send us weight measurements
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_DESCRIPTOR);
                return false;
        }

        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        switch (stateNr) {
            default:
                return false;
        }
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {

        switch (stateNr) {
            default:
                return false;
        }
    }

    private void parseBytes(byte[] weightBytes) {
            float weight, fat, water, muscle, boneWeight, visceralFat;
            //float subcutaneousFat, metabolicBaseRate, biologicalAge,  boneWeight;

            final byte ctrlByte = weightBytes[5];
            final boolean allValues = isBitSet(ctrlByte, 1);
            final boolean weightStabilized = isBitSet(ctrlByte, 0);
            final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();

            if (weightStabilized) {
                //The weight is stabilized, now we want to measure all available values
                byte gender = selectedUser.getGender().isMale() ? (byte)0x00: (byte)0x01;
                byte height = (byte) (((int)selectedUser.getBodyHeight()) & 0xFF);
                byte age = (byte)(selectedUser.getAge() & 0xff);
                byte unit = 0x01; // kg
                switch (selectedUser.getScaleUnit()) {
                    case LB:
                        unit = 0x02;
                        break;
                    case ST:
                        unit = 0x8;
                        break;
                }
                byte configBytes[] = new byte[]{(byte)0x09, (byte)0x10, (byte)0x12, (byte)0x11, (byte)0x0d, (byte)0x01, height, age, gender, unit, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
                //Write checksum is sum of all bytes % 256
                int checksum = 0x00;
                for (int i=3; i<configBytes.length-1; i++) {
                    checksum += configBytes[i];
                }
                configBytes[15] = (byte)(checksum & 0xFF);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, EXTRA_MEASUREMENT_CHARACTERISTIC, configBytes);
            } else if (allValues) {
                ScaleMeasurement scaleBtData = new ScaleMeasurement();
                weight = (float) (((weightBytes[3] & 0xFF) << 8) | (weightBytes[4] & 0xFF)) / 100.0f;
                fat = (float) (((weightBytes[6] & 0xFF) << 8) | (weightBytes[7] & 0xFF)) / 10.0f;
                if (Math.abs(fat - 0.0) < 0.00001) {
                        Timber.d("Scale signaled that measurement of all data " +
                                "is done, but fat is still zero. Settling for just adding weight.");
                } else {
                    //subcutaneousFat = (float) (((weightBytes[8] & 0xFF) << 8) | (weightBytes[9] & 0xFF)) / 10.0f;
                    visceralFat = (float) (weightBytes[10] & 0xFF) / 10.0f;
                    water = (float) (((weightBytes[11] & 0xFF) << 8) | (weightBytes[12] & 0xFF)) / 10.0f;
                    //metabolicBaseRate = (float) (((weightBytes[13] & 0xFF) << 8) | (weightBytes[14] & 0xFF));
                    //biologicalAge = (float) (weightBytes[15] & 0xFF) + 1;
                    muscle = (float) (((weightBytes[16] & 0xFF) << 8) | (weightBytes[17] & 0xFF)) / 10.0f;
                    boneWeight = (float) (weightBytes[18] & 0xFF) / 10.0f;

                    scaleBtData.setDateTime(new Date());
                    scaleBtData.setFat(fat);
                    scaleBtData.setMuscle(muscle);
                    scaleBtData.setWater(water);
                    scaleBtData.setBone(boneWeight);
                    scaleBtData.setVisceralFat(visceralFat);
                }
                scaleBtData.setWeight(weight);
                addScaleData(scaleBtData);
            }
    }
}
