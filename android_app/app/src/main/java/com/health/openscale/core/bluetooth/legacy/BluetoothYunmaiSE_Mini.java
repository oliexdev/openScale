/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.legacy;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.bluetooth.libs.YunmaiLib;
import com.health.openscale.core.data.GenderType;
import com.health.openscale.core.data.WeightUnit;
import com.health.openscale.core.utils.LogManager;
import com.health.openscale.core.utils.ConverterUtils;

import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class BluetoothYunmaiSE_Mini extends BluetoothCommunication {
    private final String TAG = "BluetoothYunmaiSE_Mini";
    private final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xffe0);
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffe4);
    private final UUID WEIGHT_CMD_SERVICE = BluetoothGattUuid.fromShortCode(0xffe5);
    private final UUID WEIGHT_CMD_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffe9);

    private boolean isMini;

    public BluetoothYunmaiSE_Mini(Context context, boolean isMini) {
        super(context);
        this.isMini = isMini;
    }

    @Override
    public String driverName() {
        return isMini ? "Yunmai Mini" : "Yunmai SE";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                byte[] userId = ConverterUtils.toInt16Be(getUniqueNumber());

                final ScaleUser selectedUser = getSelectedScaleUser();
                byte sex = selectedUser.getGender().isMale() ? (byte)0x01 : (byte)0x02;
                byte display_unit = selectedUser.getScaleUnit() == WeightUnit.KG ? (byte) 0x01 : (byte) 0x02;
                byte body_type = (byte) YunmaiLib.toYunmaiActivityLevel(selectedUser.getActivityLevel());

                byte[] user_add_or_query = new byte[]{
                        (byte) 0x0d, (byte) 0x12, (byte) 0x10, (byte) 0x01, (byte) 0x00, (byte) 0x00,
                        userId[0], userId[1], (byte) selectedUser.getBodyHeight(), sex,
                        (byte) selectedUser.getAge(), (byte) 0x55, (byte) 0x5a, (byte) 0x00,
                        (byte)0x00, display_unit, body_type, (byte) 0x00};
                user_add_or_query[user_add_or_query.length - 1] =
                        xorChecksum(user_add_or_query, 1, user_add_or_query.length - 1);
                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, user_add_or_query);
                try {
                    Thread.sleep(300);
                }
                catch (InterruptedException e)
                {}
                break;
            case 1:
                byte[] unixTime = ConverterUtils.toInt32Be(new Date().getTime() / 1000);

                byte[] set_time = new byte[]{(byte)0x0d, (byte) 0x0d, (byte) 0x11,
                        unixTime[0], unixTime[1], unixTime[2], unixTime[3],
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
                set_time[set_time.length - 1] =
                        xorChecksum(set_time, 1, set_time.length - 1);

                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, set_time);
                break;
            case 2:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC);
                break;
            case 3:
                byte[] magic_bytes = new byte[]{(byte)0x0d, (byte)0x05, (byte)0x13, (byte)0x00, (byte)0x16};
                writeBytes(WEIGHT_CMD_SERVICE, WEIGHT_CMD_CHARACTERISTIC, magic_bytes);
                sendMessage(R.string.bt_info_step_on_scale, 0);
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

            // if finished weighting?
            if (data[3] == 0x02) {
                parseBytes(data);
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        final ScaleUser scaleUser = getSelectedScaleUser();

        ScaleMeasurement scaleBtData = new ScaleMeasurement();

        long timestamp = ConverterUtils.fromUnsignedInt32Be(weightBytes, 5) * 1000;
        scaleBtData.setDateTime(new Date(timestamp));

        float weight = ConverterUtils.fromUnsignedInt16Be(weightBytes, 13) / 100.0f;
        scaleBtData.setWeight(weight);

        if (isMini) {
            int sex;

            if (scaleUser.getGender() == GenderType.MALE) {
                sex = 1;
            } else {
                sex = 0;
            }

            YunmaiLib yunmaiLib = new YunmaiLib(sex, scaleUser.getBodyHeight(), scaleUser.getActivityLevel());
            float bodyFat;
            int resistance = ConverterUtils.fromUnsignedInt16Be(weightBytes, 15);
            if (weightBytes[1] >= (byte)0x1E) {
                LogManager.d(TAG,"Extract the fat value from received bytes");
                bodyFat = ConverterUtils.fromUnsignedInt16Be(weightBytes, 17) / 100.0f;
            } else {
                LogManager.d(TAG,"Calculate the fat value using the Yunmai lib");
                bodyFat = yunmaiLib.getFat(scaleUser.getAge(), weight, resistance);
            }

            if (bodyFat != 0) {
                scaleBtData.setFat(bodyFat);
                scaleBtData.setMuscle(yunmaiLib.getMuscle(bodyFat));
                scaleBtData.setWater(yunmaiLib.getWater(bodyFat));
                scaleBtData.setBone(yunmaiLib.getBoneMass(scaleBtData.getMuscle(), weight));
                scaleBtData.setLbm(yunmaiLib.getLeanBodyMass(weight, bodyFat));
                scaleBtData.setVisceralFat(yunmaiLib.getVisceralFat(bodyFat, scaleUser.getAge()));
            } else {
                LogManager.e(TAG,"body fat is zero", null);
            }

            LogManager.d(TAG, "received bytes [" + byteInHex(weightBytes) + "]");
            String decryptedBytesLog = String.format(Locale.US, "received decrypted bytes [weight: %.2f, fat: %.2f, resistance: %d]", weight, bodyFat, resistance);
            LogManager.d(TAG, decryptedBytesLog);
            LogManager.d(TAG, "user [" + scaleUser + "]");
            LogManager.d(TAG, "scale measurement [" + scaleBtData + "]");
        }

        addScaleMeasurement(scaleBtData);
    }
}
