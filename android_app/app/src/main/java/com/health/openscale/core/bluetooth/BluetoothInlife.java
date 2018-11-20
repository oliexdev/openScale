/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

public class BluetoothInlife extends BluetoothCommunication {
    private final UUID WEIGHT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1);
    private final UUID WEIGHT_CMD_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff2);

    public BluetoothInlife(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Inlinfe";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setNotificationOn(WEIGHT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC,
                        BluetoothGattUuid.DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION);
                break;
            case 1:
                byte level = 1;
                byte sex = 0; // Male, 1 = female
                byte userId = 0;
                byte age = 30;
                byte height = (byte)156;
                byte[] data = {(byte)0x02, (byte)0xd2, level, sex, userId, age, height,
                0, 0, 0, 0, 0, 0, (byte)0xaa};
                data[data.length - 2] = xorChecksum(data, 1, 6);

                writeBytes(WEIGHT_SERVICE, WEIGHT_CMD_CHARACTERISTIC, data);
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

        if (data == null || data.length != 14) {
            return;
        }

        if (data[0] != (byte)0x02 || data[data.length - 1] != (byte)0xaa) {
            Timber.d("Wrong start or end byte");
            return;
        }

        if (data[1] != (byte)0xdd) {
            Timber.d("Measurement not done yet");
            return;
        }

        float weight = Converters.fromUnsignedInt16Be(data, 2) / 10.0f;
        float lbm = Converters.fromUnsignedInt24Be(data, 4) / 1000.0f;

        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        switch (selectedUser.getActivityLevel()) {
            case SEDENTARY:
            case MILD:
                break;
            case MODERATE:
                lbm *= 1.0427f;
                break;
            case HEAVY:
            case EXTREME:
                lbm *= 1.0958f;
                break;
        }

        float fatKg = weight - lbm;
        float fat = (fatKg / weight) * 100.0f;
        float water = (0.73f * (weight - fatKg) / weight) * 100.0f;
        float muscle = (0.548f * lbm / weight) * 100.0f;
        float bone = 0.05158f * lbm;

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setWeight(weight);
        measurement.setFat(fat);
        measurement.setWater(water);
        measurement.setMuscle(muscle);
        measurement.setBone(bone);

        addScaleData(measurement);
    }
}
