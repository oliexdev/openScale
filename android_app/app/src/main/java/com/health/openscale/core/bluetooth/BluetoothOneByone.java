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

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothOneByone extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");

    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb"); // notify

    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"); // write only
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
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE,
                            WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION,
                            WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
                byte unit = 0x00; // kg
                switch (currentUser.getScaleUnit()) {
                    case LB:
                        unit = 0x01;
                        break;
                    case ST:
                        unit = 0x02;
                        break;
                }
                byte group = 0x01;
                byte[] magicBytes = {(byte)0xfd, (byte)0x37, unit, group,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00};
                magicBytes[magicBytes.length - 1] =
                        xorChecksum(magicBytes, 0, magicBytes.length - 1);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
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
        if (data == null) {
            return;
        }

        // if data is valid data
        if (data.length == 20 && data[0] == (byte)0xcf) {
            parseBytes(data);
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = Converters.fromUnsignedInt16Le(weightBytes, 3) / 100.0f;
        int impedanceCoeff = Converters.fromUnsignedInt24Le(weightBytes, 5);
        int impedanceValue = weightBytes[5] + weightBytes[6] + weightBytes[7];

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("received bytes [%s]", byteInHex(weightBytes));
        Timber.d("received decrypted bytes [weight: %.2f, impedanceCoeff: %d, impedanceValue: %d]", weight, impedanceCoeff, impedanceValue);
        Timber.d("user [%s]", scaleUser);

        int sex = 0, peopleType = 0;

        if (scaleUser.getGender() == Converters.Gender.MALE) {
            sex = 1;
        } else {
            sex = 0;
        }

        switch (scaleUser.getActivityLevel()) {
            case SEDENTARY:
                peopleType = 0;
                break;
            case MILD:
                peopleType = 0;
                break;
            case MODERATE:
                peopleType = 1;
                break;
            case HEAVY:
                peopleType = 2;
                break;
            case EXTREME:
                peopleType = 2;
                break;
        }

        //HoltekLib holtekLib = new HoltekLib(sex, scaleUser.getAge(), (int)scaleUser.getBodyHeight(), peopleType);

        ScaleMeasurement scaleBtData = new ScaleMeasurement();
        scaleBtData.setWeight(weight);
        /*scaleBtData.setFat(holtekLib.getBodyFat(weight, impedanceCoeff));
        scaleBtData.setWater(holtekLib.getWater(scaleBtData.getFat()));
        scaleBtData.setBone(holtekLib.getBoneMass(weight, impedanceValue));
        scaleBtData.setVisceralFat(holtekLib.getVisceralFat(weight));
        scaleBtData.setMuscle(holtekLib.getMuscle(weight, scaleBtData.getFat(), scaleBtData.getBone()));*/

        Timber.d("scale measurement [%s]", scaleBtData);

        addScaleData(scaleBtData);
    }
}
