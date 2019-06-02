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

import android.content.Context;

import com.health.openscale.R;
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

    private int getAthleteLevel(ScaleUser scaleUser) {
        switch (scaleUser.getActivityLevel()) {
            case SEDENTARY:
            case MILD:
                return 0; // General
            case MODERATE:
                return 1; // Amateur
            case HEAVY:
            case EXTREME:
                return 2; // Profession
        }
        return 0;
    }

    private void sendCommand(int command, byte... parameters) {
        byte[] data = {START_BYTE, (byte)command, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, END_BYTE};
        int i = 2;
        for (byte parameter : parameters) {
            data[i++] = parameter;
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
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                setNotificationOn(WEIGHT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
                byte level = (byte)(getAthleteLevel(scaleUser) + 1);
                byte sex = (byte)scaleUser.getGender().toInt();
                byte userId = (byte)scaleUser.getId();
                byte age = (byte)scaleUser.getAge();
                byte height = (byte)scaleUser.getBodyHeight();

                sendCommand(0xd2, level, sex, userId, age, height);
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

        if (data == null || data.length != 14) {
            return;
        }

        if (data[0] != START_BYTE || data[data.length - 1] != END_BYTE) {
            Timber.e("Wrong start or end byte");
            return;
        }

        if (xorChecksum(data, 1, data.length - 2) != 0) {
            Timber.e("Invalid checksum");
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
                sendMessage(R.string.info_measuring, weight);
                break;
            case (byte) 0xdd:
                if (data[11] == (byte) 0x80 || data[11] == (byte) 0x81) {
                    processMeasurementDataNewVersion(data);
                }
                else {
                    processMeasurementData(data);
                }
                break;
            case (byte) 0xdf:
                Timber.d("User data acked by scale: %s", data[2] == 0 ? "OK" : "error");
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

        if (lbm == 0xffffff / 1000.0f) {
            Timber.e("Measurement failed; feet not correctly placed on scale?");
            return;
        }

        Timber.d("weight=%.1f, LBM=%.3f, visceral factor=%.1f, BMR=%.1f",
                weight, lbm, visceralFactor, bmr);

        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        switch (getAthleteLevel(selectedUser)) {
            case 0:
                break;
            case 1:
                lbm *= 1.0427f;
                break;
            case 2:
                lbm *= 1.0958f;
                break;
        }

        final float fatKg = weight - lbm;
        final double fat = (fatKg / weight) * 100.0;
        final double water = (0.73f * (weight - fatKg) / weight) * 100.0;
        final double muscle = (0.548 * lbm / weight) * 100.0;
        final double bone = 0.05158 * lbm;

        final float height = selectedUser.getBodyHeight();

        double visceral = visceralFactor - 50;
        if (selectedUser.getGender().isMale()) {
            if (height >= 1.6 * weight + 63) {
                visceral += (0.765 - 0.002 * height) * weight;
            }
            else {
                visceral += 380 * weight / (((0.0826 * height * height) - 0.4 * height) + 48);
            }
        }
        else {
            if (weight <= height / 2 - 13) {
                visceral += (0.691 - 0.0024 * height) * weight;
            }
            else {
                visceral += 500 * weight / (((0.1158 * height * height) + 1.45 * height) - 120);
            }
        }

        if (getAthleteLevel(selectedUser) != 0) {
            if (visceral >= 21) {
                visceral *= 0.85;
            }
            if (visceral >= 10) {
                visceral *= 0.8;
            }
            visceral -= getAthleteLevel(selectedUser) * 2;
        }

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setWeight(weight);
        measurement.setFat(clamp(fat, 5, 80));
        measurement.setWater(clamp(water, 5, 80));
        measurement.setMuscle(clamp(muscle, 5, 80));
        measurement.setBone(clamp(bone, 0.5, 8));
        measurement.setLbm(lbm);
        measurement.setVisceralFat(clamp(visceral, 1, 50));

        addScaleMeasurement(measurement);

        sendCommand(0xd4);
    }

    void processMeasurementDataNewVersion(byte[] data) {
        float weight = Converters.fromUnsignedInt16Be(data, 2) / 10.0f;
        long impedance = Converters.fromUnsignedInt32Be(data, 4);
        Timber.d("weight=%.2f, impedance=%d", weight, impedance);

        // Uses the same library as 1byone, but we need someone that has the scale to be able to
        // test if it works the same way.
    }
}
