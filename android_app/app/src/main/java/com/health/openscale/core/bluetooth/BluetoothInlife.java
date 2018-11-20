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

import java.util.Arrays;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothInlife extends BluetoothCommunication {
    private final UUID WEIGHT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1);
    private final UUID WEIGHT_CMD_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff2);

    private final byte START_BYTE = 0x02;
    private final byte END_BYTE = (byte)0xaa;

    private byte[] lastData = null;

    private int getActivityLevel(ScaleUser scaleUser) {
        switch (scaleUser.getActivityLevel()) {
            case SEDENTARY:
            case MILD:
                break;
            case MODERATE:
                return 1;
            case HEAVY:
            case EXTREME:
                return 2;
        }
        return 0;
    }

    private void sendCommand(int command, byte[] parameters) {
        byte[] data = {START_BYTE, (byte)command, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, END_BYTE};
        if (parameters != null) {
            for (int i = 0; i < parameters.length; ++i) {
                data[i + 2] = parameters[i];
            }
        }
        data[data.length - 2] = xorChecksum(data, 1, data.length - 3);
        writeBytes(WEIGHT_SERVICE, WEIGHT_CMD_CHARACTERISTIC, data);
    }

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
                ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
                byte level = (byte)(getActivityLevel(scaleUser) + 1);
                byte sex = (byte)scaleUser.getGender().toInt();
                byte userId = (byte)scaleUser.getId();
                byte age = (byte)scaleUser.getAge();
                byte height = (byte)scaleUser.getBodyHeight();

                sendCommand(0xd2, new byte[] {level, sex, userId, age, height});
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

        if (data[0] != START_BYTE || data[data.length - 1] != END_BYTE) {
            Timber.d("Wrong start or end byte");
            return;
        }

        if (xorChecksum(data, 1, data.length - 2) != 0) {
            Timber.d("Invalid checksum");
            return;
        }

        if (Arrays.equals(data, lastData)) {
            Timber.d("Ignoring duplicate data");
            return;
        }
        lastData = data;

        switch (data[1]) {
            case (byte) 0x0f:
                Timber.d("Scale disconnecting");
                break;
            case (byte) 0xd8:
                float weight = Converters.fromUnsignedInt16Be(data, 2) / 10.0f;
                Timber.d("Current weight %.2f kg", weight);
                break;
            case (byte) 0xdd:
                processMeasurementData(data);
                break;
            case (byte) 0xdf:
                Timber.d("Data received by scale: %s", data[2] == 0 ? "OK" : "error");
                break;
            default:
                Timber.d("Unknown command 0x%02x", data[1]);
                break;
        }
    }

    void processMeasurementData(byte[] data) {
        float weight = Converters.fromUnsignedInt16Be(data, 2) / 10.0f;
        float lbm = Converters.fromUnsignedInt24Be(data, 4) / 1000.0f;
        float visceralFactor = Converters.fromUnsignedInt16Be(data, 7) / 10.0f;
        float bmr = Converters.fromUnsignedInt16Be(data, 9) / 10.0f;

        Timber.d("weight=%.1f, LBM=%.3f, visceral factor=%.1f, BMR=%.1f",
                weight, lbm, visceralFactor, bmr);

        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        switch (getActivityLevel(selectedUser)) {
            case 0:
                break;
            case 1:
                lbm *= 1.0427f;
                break;
            case 2:
                lbm *= 1.0958f;
                break;
        }

        float fatKg = weight - lbm;
        float fat = (fatKg / weight) * 100.0f;
        float water = (0.73f * (weight - fatKg) / weight) * 100.0f;
        float muscle = (0.548f * lbm / weight) * 100.0f;
        float bone = 0.05158f * lbm;

        double visceral;
        final float height = selectedUser.getBodyHeight();
        if (selectedUser.getGender().isMale()) {
            if (height >= 1.6 * weight + 63) {
                visceral = (0.765 - 0.002 * height) * (weight - 50) + visceralFactor;
            }
            else {
                visceral = 380 * weight / (((0.0826 * height * height) - 0.4 * height) + 48) - 50 + visceralFactor;
            }
        }
        else {
            if (weight <= height / 2 - 13) {
                visceral = (0.691 - 0.0024 * height) * (weight - 50) + visceralFactor;
            }
            else {
                visceral = 500 * weight / (((0.1158 * height * height) + 1.45 * height) - 120) - 50 + visceralFactor;
            }
        }

        if (getActivityLevel(selectedUser) != 0) {
            if (visceral >= 21) {
                visceral *= 0.85;
            }
            if (visceral >= 10) {
                visceral *= 0.8;
            }
            visceral -= getActivityLevel(selectedUser) * 2;
        }

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setWeight(weight);
        measurement.setFat(fat);
        measurement.setWater(water);
        measurement.setMuscle(muscle);
        measurement.setBone(bone);
        measurement.setLbm(lbm);
        measurement.setVisceralFat((float) visceral);

        addScaleData(measurement);

        sendCommand(0xd4, null);
    }
}
