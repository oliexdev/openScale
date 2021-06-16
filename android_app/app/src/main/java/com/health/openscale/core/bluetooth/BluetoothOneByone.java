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
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.OneByoneLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Calendar;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothOneByone extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);

    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION = BluetoothGattUuid.fromShortCode(0xfff4); // notify

    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1); // write only

    private boolean waitAck             = false; // if true, resume after receiving acknowledgement
    private boolean historicMeasurement = false; // processing real-time vs historic measurement
    private int     noHistoric          = 0;     // number of historic measurements received

    // don't save any measurements closer than 3 seconds to each other
    private Calendar  lastDateTime;
    private final int DATE_TIME_THRESHOLD = 3000;

    public BluetoothOneByone(Context context) {
        super(context);
        lastDateTime = Calendar.getInstance();
        lastDateTime.set(2000, 1, 1);
    }

    @Override
    public String driverName() {
        return "1byone";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION);
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
            case 2:
                Calendar dt = Calendar.getInstance();
                final byte[] setClockCmd = {(byte)0xf1, (byte)(dt.get(Calendar.YEAR) >> 8),
                        (byte)(dt.get(Calendar.YEAR) & 255), (byte)(dt.get(Calendar.MONTH) + 1),
                        (byte)dt.get(Calendar.DAY_OF_MONTH), (byte)dt.get(Calendar.HOUR_OF_DAY),
                        (byte)dt.get(Calendar.MINUTE), (byte)dt.get(Calendar.SECOND)};
                waitAck = true;
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, setClockCmd);
                // 2-byte notification value f1 00 will be received after this command
                stopMachineState(); // we will resume after receiving acknowledgement f1 00
                break;
            case 3:
                // request historic measurements; they are followed by real-time measurements
                historicMeasurement = true;
                final byte[] getHistoryCmd = {(byte)0xf2, (byte)0x00};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, getHistoryCmd);
                // multiple measurements will be received, they start cf ... and are 11 or 18 bytes long
                // 2-byte notification value f2 00 follows last historic measurement
                break;
            case 4:
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
        if (data == null) {
            return;
        }

        // if data is valid data
        if (data.length >= 11 && data[0] == (byte)0xcf) {
            if (historicMeasurement) {
                ++noHistoric;
            }
            parseBytes(data);
        } else {
            // show 2-byte ack messages in debug output:
            //   f1 00 setClockCmd acknowledgement
            //   f2 00 end of historic measurements, real-time measurements follow
            //   f2 01 clearHistoryCmd acknowledgement
            Timber.d("received bytes [%s]", byteInHex(data));

            if (waitAck && data.length == 2 && data[0] == (byte)0xf1 && data[1] == 0) {
                waitAck = false;
                resumeMachineState();
            } else if (data.length == 2 && data[0] == (byte)0xf2 && data[1] == 0) {
                historicMeasurement = false;
                if (noHistoric > 0) {
                    final byte[] clearHistoryCmd = {(byte)0xf2, (byte)0x01};
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, clearHistoryCmd);
                }
            }
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = Converters.fromUnsignedInt16Le(weightBytes, 3) / 100.0f;
        float impedanceValue = ((float)(((weightBytes[2] & 0xFF) << 8) + (weightBytes[1] & 0xFF))) * 0.1f;
        boolean impedancePresent = (weightBytes[9] != 1) && (impedanceValue != 0);
        boolean dateTimePresent = weightBytes.length >= 18;

        if (!impedancePresent || (!dateTimePresent && historicMeasurement)) {
            // unwanted, no impedance or historic measurement w/o time-stamp
            return;
        }

        Calendar dateTime = Calendar.getInstance();
        if (dateTimePresent) {
            // 18-byte or longer measurements contain date and time, used in history
            dateTime.set(Converters.fromUnsignedInt16Be(weightBytes, 11),
                         weightBytes[13] - 1, weightBytes[14], weightBytes[15],
                         weightBytes[16], weightBytes[17]);
        }

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("received bytes [%s]", byteInHex(weightBytes));
        Timber.d("received decoded bytes [weight: %.2f, impedanceValue: %f]", weight, impedanceValue);
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

        OneByoneLib oneByoneLib = new OneByoneLib(sex, scaleUser.getAge(), scaleUser.getBodyHeight(), peopleType);

        ScaleMeasurement scaleBtData = new ScaleMeasurement();
        scaleBtData.setWeight(weight);
        try {
            dateTime.setLenient(false);
            scaleBtData.setDateTime(dateTime.getTime());

            scaleBtData.setFat(oneByoneLib.getBodyFat(weight, impedanceValue));
            scaleBtData.setWater(oneByoneLib.getWater(scaleBtData.getFat()));
            scaleBtData.setBone(oneByoneLib.getBoneMass(weight, impedanceValue));
            scaleBtData.setVisceralFat(oneByoneLib.getVisceralFat(weight));
            scaleBtData.setMuscle(oneByoneLib.getMuscle(impedanceValue));
            scaleBtData.setLbm(oneByoneLib.getLBM(weight, scaleBtData.getFat()));

            Timber.d("scale measurement [%s]", scaleBtData);

            if (dateTime.getTimeInMillis() - lastDateTime.getTimeInMillis() < DATE_TIME_THRESHOLD) {
                return; // don't save measurements too close to each other
            }
            lastDateTime = dateTime;

            addScaleMeasurement(scaleBtData);
        }
        catch (IllegalArgumentException e) {
            if (historicMeasurement) {
                Timber.d("invalid time-stamp: year %d, month %d, day %d, hour %d, minute %d, second %d",
                        Converters.fromUnsignedInt16Be(weightBytes, 11),
                        weightBytes[13], weightBytes[14], weightBytes[15],
                        weightBytes[16], weightBytes[17]);
                return; // discard historic measurement with invalid time-stamp
            }
        }
    }
}
