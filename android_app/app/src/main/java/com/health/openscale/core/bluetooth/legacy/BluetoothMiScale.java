/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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

package com.health.openscale.core.bluetooth.legacy;

import static com.health.openscale.core.bluetooth.legacy.BluetoothCommunication.BT_STATUS.UNEXPECTED_ERROR;

import android.content.Context;

import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.utils.ConverterUtils;
import com.health.openscale.core.utils.LogManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

public class BluetoothMiScale extends BluetoothCommunication {
    private final String TAG = "BluetoothMiScale";
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC = UUID.fromString("00002a2f-0000-3512-2118-0009af100700");

    public BluetoothMiScale(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Xiaomi Mi Scale v1";
    }


    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {

        if (characteristic.equals(BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME)) {
            byte[] data = value;

            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            int currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1;
            int currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            int scaleYear = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
            int scaleMonth = (int) data[2];
            int scaleDay = (int) data[3];

            if (!(currentYear == scaleYear && currentMonth == scaleMonth && currentDay == scaleDay)) {
                LogManager.d(TAG, "Current year and scale year is different");

                // set current time
                Calendar currentDateTime = Calendar.getInstance();
                int year = currentDateTime.get(Calendar.YEAR);
                byte month = (byte) (currentDateTime.get(Calendar.MONTH) + 1);
                byte day = (byte) currentDateTime.get(Calendar.DAY_OF_MONTH);
                byte hour = (byte) currentDateTime.get(Calendar.HOUR_OF_DAY);
                byte min = (byte) currentDateTime.get(Calendar.MINUTE);
                byte sec = (byte) currentDateTime.get(Calendar.SECOND);

                byte[] dateTimeByte = {(byte) (year), (byte) (year >> 8), month, day, hour, min, sec, 0x03, 0x00, 0x00};

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME, dateTimeByte);
            }
        } else {
            final byte[] data = value;

            if (data != null && data.length > 0) {

                // Stop command from mi scale received
                if (data[0] == 0x03) {
                    // send stop command to mi scale
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x03});
                    // acknowledge that you received the last history data
                    int uniqueNumber = getUniqueNumber();

                    byte[] userIdentifier = new byte[]{(byte) 0x04, (byte) 0xFF, (byte) 0xFF, (byte) ((uniqueNumber & 0xFF00) >> 8), (byte) ((uniqueNumber & 0xFF) >> 0)};
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, userIdentifier);

                    resumeMachineState();
                }

                if (data.length == 20) {
                    final byte[] firstWeight = Arrays.copyOfRange(data, 0, 10);
                    final byte[] secondWeight = Arrays.copyOfRange(data, 10, 20);
                    parseBytes(firstWeight);
                    parseBytes(secondWeight);
                }

                if (data.length == 10) {
                    parseBytes(data);
                }

            }
        }
    }


    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // read device time
                readBytes(WEIGHT_MEASUREMENT_SERVICE, BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME);
                break;
            case 1:
                // Set on history weight measurement
                byte[] magicBytes = new byte[]{(byte)0x01, (byte)0x96, (byte)0x8a, (byte)0xbd, (byte)0x62};

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, magicBytes);
                break;
            case 2:
                // set notification on for weight measurement history
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);
                break;
            case 3:
                // set notification on for weight measurement
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, BluetoothGattUuid.CHARACTERISTIC_WEIGHT_MEASUREMENT);
                break;
            case 4:
                // configure scale to get only last measurements
                int uniqueNumber = getUniqueNumber();

                byte[] userIdentifier = new byte[]{(byte)0x01, (byte)0xFF, (byte)0xFF, (byte) ((uniqueNumber & 0xFF00) >> 8), (byte) ((uniqueNumber & 0xFF) >> 0)};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, userIdentifier);
                break;
            case 5:
                // invoke receiving history data
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x02});
                stopMachineState();
                break;
            default:
                return false;
        }

        return true;
    }

    private void parseBytes(byte[] weightBytes) {
        try {
            final byte ctrlByte = weightBytes[0];

            final boolean isWeightRemoved = isBitSet(ctrlByte, 7);
            final boolean isStabilized = isBitSet(ctrlByte, 5);
            final boolean isLBSUnit = isBitSet(ctrlByte, 0);
            final boolean isCattyUnit = isBitSet(ctrlByte, 4);

            /*LogManager.d(TAG, "IsWeightRemoved: " + isBitSet(ctrlByte, 7));
            LogManager.d(TAG, "6 LSB Unknown: " + isBitSet(ctrlByte, 6));
            LogManager.d(TAG, "IsStabilized: " + isBitSet(ctrlByte, 5));
            LogManager.d(TAG, "IsCattyOrKg: " + isBitSet(ctrlByte, 4));
            LogManager.d(TAG, "3 LSB Unknown: " + isBitSet(ctrlByte, 3));
            LogManager.d(TAG, "2 LSB Unknown: " + isBitSet(ctrlByte, 2));
            LogManager.d(TAG, "1 LSB Unknown: " + isBitSet(ctrlByte, 1));
            LogManager.d(TAG, "IsLBS: " + isBitSet(ctrlByte, 0));*/

            // Only if the value is stabilized and the weight is *not* removed, the date is valid
            if (isStabilized && !isWeightRemoved) {

                final int year = ((weightBytes[4] & 0xFF) << 8) | (weightBytes[3] & 0xFF);
                final int month = (int) weightBytes[5];
                final int day = (int) weightBytes[6];
                final int hours = (int) weightBytes[7];
                final int min = (int) weightBytes[8];
                final int sec = (int) weightBytes[9];

                float weight;
                if (isLBSUnit || isCattyUnit) {
                    weight = (float) (((weightBytes[2] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 100.0f;
                } else {
                    weight = (float) (((weightBytes[2] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 200.0f;
                }

                String date_string = year + "/" + month + "/" + day + "/" + hours + "/" + min;
                Date date_time = new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string);

                // Is the year plausible? Check if the year is in the range of 20 years...
                if (validateDate(date_time, 20)) {
                    final ScaleUser selectedUser = getSelectedScaleUser();
                    ScaleMeasurement scaleBtData = new ScaleMeasurement();

                    scaleBtData.setWeight(ConverterUtils.toKilogram(weight, selectedUser.getScaleUnit()));
                    scaleBtData.setDateTime(date_time);

                    addScaleMeasurement(scaleBtData);
                } else {
                    LogManager.e(TAG, String.format("Invalid Mi scale weight year %d", year), null);
                }
            }
        } catch (ParseException e) {
            setBluetoothStatus(UNEXPECTED_ERROR, "Error while decoding bluetooth date string (" + e.getMessage() + ")");
        }
    }

    private boolean validateDate(Date weightDate, int range) {

        Calendar currentDatePos = Calendar.getInstance();
        currentDatePos.add(Calendar.YEAR, range);

        Calendar currentDateNeg = Calendar.getInstance();
        currentDateNeg.add(Calendar.YEAR, -range);

        if (weightDate.before(currentDatePos.getTime()) && weightDate.after(currentDateNeg.getTime())) {
            return true;
        }

        return false;
    }
}
