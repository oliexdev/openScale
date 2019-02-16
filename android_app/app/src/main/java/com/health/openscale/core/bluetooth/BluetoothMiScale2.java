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

package com.health.openscale.core.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBMMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.LBMMeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;
import com.health.openscale.gui.views.WaterMeasurementView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

import timber.log.Timber;

import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS.UNEXPECTED_ERROR;

public class BluetoothMiScale2 extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC = UUID.fromString("00002a2f-0000-3512-2118-0009af100700");

    private final UUID WEIGHT_CUSTOM_SERVICE = UUID.fromString("00001530-0000-3512-2118-0009af100700");
    private final UUID WEIGHT_CUSTOM_CONFIG = UUID.fromString("00001542-0000-3512-2118-0009af100700");

    public BluetoothMiScale2(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Xiaomi Mi Scale v2";
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;

        if (data != null && data.length > 0) {
            Timber.d("DataChange hex data: %s", byteInHex(data));

            // Stop command from mi scale received
            if (data[0] == 0x03) {
                Timber.d("Scale stop byte received");
                // send stop command to mi scale
                writeBytes(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x03});
                // acknowledge that you received the last history data
                int uniqueNumber = getUniqueNumber();

                byte[] userIdentifier = new byte[]{(byte)0x04, (byte)0xFF, (byte)0xFF, (byte) ((uniqueNumber & 0xFF00) >> 8), (byte) ((uniqueNumber & 0xFF) >> 0)};
                writeBytes(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, userIdentifier);

                disconnect();

                resumeMachineState();
            }

            if (data.length == 26) {
                final byte[] firstWeight = Arrays.copyOfRange(data, 0, 10);
                final byte[] secondWeight = Arrays.copyOfRange(data, 10, 20);
                parseBytes(firstWeight);
                parseBytes(secondWeight);
            }

            if (data.length == 13) {
                parseBytes(data);
            }

        }
    }


    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // set scale units
                final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                byte[] setUnitCmd = new byte[]{(byte)0x06, (byte)0x04, (byte)0x00, (byte) selectedUser.getScaleUnit().toInt()};
                writeBytes(WEIGHT_CUSTOM_CONFIG, setUnitCmd);
                break;
            case 1:
                // set current time
                Calendar currentDateTime = Calendar.getInstance();
                int year = currentDateTime.get(Calendar.YEAR);
                byte month = (byte)(currentDateTime.get(Calendar.MONTH)+1);
                byte day = (byte)currentDateTime.get(Calendar.DAY_OF_MONTH);
                byte hour = (byte)currentDateTime.get(Calendar.HOUR_OF_DAY);
                byte min = (byte)currentDateTime.get(Calendar.MINUTE);
                byte sec = (byte)currentDateTime.get(Calendar.SECOND);

                byte[] dateTimeByte = {(byte)(year), (byte)(year >> 8), month, day, hour, min, sec, 0x03, 0x00, 0x00};

                writeBytes(BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME, dateTimeByte);
                break;
            case 2:
                // set notification on for weight measurement history
                setNotificationOn(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);
                break;
            case 3:
                // configure scale to get only last measurements
                int uniqueNumber = getUniqueNumber();

                byte[] userIdentifier = new byte[]{(byte)0x01, (byte)0xFF, (byte)0xFF, (byte) ((uniqueNumber & 0xFF00) >> 8), (byte) ((uniqueNumber & 0xFF) >> 0)};
                writeBytes(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, userIdentifier);
                break;
            case 4:
                // invoke receiving history data
                writeBytes(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC, new byte[]{0x02});
                stopMachineState();
                break;
            default:
                return false;
        }

        return true;
    }

    private void parseBytes(byte[] weightBytes) {
        try {
            final byte ctrlByte0 = weightBytes[0];
            final byte ctrlByte1 = weightBytes[1];

            final boolean isWeightRemoved = isBitSet(ctrlByte1, 7);
            final boolean isDateInvalid = isBitSet(ctrlByte1, 6);
            final boolean isStabilized = isBitSet(ctrlByte1, 5);
            final boolean isLBSUnit = isBitSet(ctrlByte0, 0);
            final boolean isCattyUnit = isBitSet(ctrlByte1, 6);

            if (isStabilized && !isWeightRemoved && !isDateInvalid) {

                final int year = ((weightBytes[3] & 0xFF) << 8) | (weightBytes[2] & 0xFF);
                final int month = (int) weightBytes[4];
                final int day = (int) weightBytes[5];
                final int hours = (int) weightBytes[6];
                final int min = (int) weightBytes[7];
                final int sec = (int) weightBytes[8];

                float weight;
                if (isLBSUnit || isCattyUnit) {
                    weight = (float) (((weightBytes[12] & 0xFF) << 8) | (weightBytes[11] & 0xFF)) / 100.0f;
                } else {
                    weight = (float) (((weightBytes[12] & 0xFF) << 8) | (weightBytes[11] & 0xFF)) / 200.0f;
                }

                String date_string = year + "/" + month + "/" + day + "/" + hours + "/" + min;
                Date date_time = new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string);

                // Is the year plausible? Check if the year is in the range of 20 years...
                if (validateDate(date_time, 20)) {
                    final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                    ScaleMeasurement scaleBtData = new ScaleMeasurement();

                    scaleBtData.setWeight(Converters.toKilogram(weight, selectedUser.getScaleUnit()));
                    scaleBtData.setDateTime(date_time);

                    // estimate fat, water and LBM until library is reversed engineered
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

                    MeasurementViewSettings settings = new MeasurementViewSettings(prefs, WaterMeasurementView.KEY);
                    EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(
                            EstimatedWaterMetric.FORMULA.valueOf(settings.getEstimationFormula()));
                    scaleBtData.setWater(waterMetric.getWater(selectedUser, scaleBtData));

                    settings = new MeasurementViewSettings(prefs, FatMeasurementView.KEY);
                    EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(
                            EstimatedFatMetric.FORMULA.valueOf(settings.getEstimationFormula()));
                    scaleBtData.setFat(fatMetric.getFat(selectedUser, scaleBtData));

                    settings = new MeasurementViewSettings(prefs, LBMMeasurementView.KEY);
                    EstimatedLBMMetric lbmMetric = EstimatedLBMMetric.getEstimatedMetric(
                            EstimatedLBMMetric.FORMULA.valueOf(settings.getEstimationFormula()));
                    scaleBtData.setLbm(lbmMetric.getLBM(selectedUser, scaleBtData));

                    addScaleMeasurement(scaleBtData);
                } else {
                    Timber.e("Invalid Mi scale weight year %d", year);
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

    private int getUniqueNumber() {
        int uniqueNumber;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        uniqueNumber = prefs.getInt("uniqueNumber", 0x00);

        if (uniqueNumber == 0x00) {
            Random r = new Random();
            uniqueNumber = r.nextInt(65535 - 100 + 1) + 100;

            prefs.edit().putInt("uniqueNumber", uniqueNumber).apply();
        }

        int userId = OpenScale.getInstance().getSelectedScaleUserId();

        return uniqueNumber + userId;
    }
}
