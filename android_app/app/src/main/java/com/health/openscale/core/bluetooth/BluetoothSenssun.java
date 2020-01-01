/* Copyright (C) 2018  Marco Gittler <marco@gitma.de>
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
import com.welie.blessed.BluetoothPeripheral;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothSenssun extends BluetoothCommunication {
    private final UUID MODEL_A_MEASUREMENT_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final UUID MODEL_A_NOTIFICATION_CHARACTERISTIC = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private final UUID MODEL_A_WRITE_CHARACTERISTIC = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    private final UUID MODEL_B_MEASUREMENT_SERVICE = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb");
    private final UUID MODEL_B_NOTIFICATION_CHARACTERISTIC = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb");
    private final UUID MODEL_B_WRITE_CHARACTERISTIC = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb");

    private UUID writeService;
    private UUID writeCharacteristic;

    private int lastWeight, lastFat, lastHydration, lastMuscle, lastBone, lastKcal;
    private boolean weightStabilized, stepMessageDisplayed;

    private int values;

    public BluetoothSenssun(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Senssun Fat";
    }

    @Override
    protected void onBluetoothDiscovery(BluetoothPeripheral peripheral) {

            if (peripheral.getService(MODEL_A_MEASUREMENT_SERVICE) != null) {
                writeService = MODEL_A_MEASUREMENT_SERVICE;
                writeCharacteristic = MODEL_A_WRITE_CHARACTERISTIC;
                setNotificationOn(MODEL_A_MEASUREMENT_SERVICE, MODEL_A_NOTIFICATION_CHARACTERISTIC);
                Timber.d("Found a Model A");
            }

            if (peripheral.getService(MODEL_B_MEASUREMENT_SERVICE) != null) {
                writeService = MODEL_B_MEASUREMENT_SERVICE;
                writeCharacteristic = MODEL_B_WRITE_CHARACTERISTIC;
                setNotificationOn(MODEL_B_MEASUREMENT_SERVICE, MODEL_B_NOTIFICATION_CHARACTERISTIC);
                Timber.d("Found a Model B");
            }
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                weightStabilized = false;
                stepMessageDisplayed = false;
                values = 0;
                Timber.d("Sync Date");
                synchroniseDate();
                break;
            case 1:
                Timber.d("Sync Time");
                synchroniseTime();
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (value == null || value[0] != (byte)0xFF) {
            return;
        }

        System.arraycopy(value, 1, value, 0, value.length - 1);

        switch (value[0]) {
            case (byte)0xA5:
                parseMeasurement(value);
                break;
        }

    }

    private void parseMeasurement(byte[] data) {
        switch(data[5]) {
            case (byte)0xAA:
            case (byte)0xA0:
                if (values > 1) {
                    return;
                }
                if (!stepMessageDisplayed) {
                    sendMessage(R.string.info_step_on_scale, 0);
                    stepMessageDisplayed = true;
                }

                weightStabilized = data[5] == (byte)0xAA;
                Timber.d("the byte is %d stable is %s", (data[5] & 0xff), weightStabilized ? "true": "false");
                lastWeight = ((data[1] & 0xff) << 8) | (data[2] & 0xff);

                if (lastWeight > 0) {
                    sendMessage(R.string.info_measuring, lastWeight / 10.0f);

                }

                if (weightStabilized) {
                    values |= 1;
                    synchroniseUser();
                }
                break;
            case (byte)0xBE:
                setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR, "Fat Test Error");
                disconnect();
                break;

            case (byte)0xB0:
                lastFat = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                lastHydration = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
                values |= 2;
                Timber.d("got fat %d", values);

                break;

            case (byte)0xC0:
                lastMuscle = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                lastBone = ((data[4] & 0xff) << 8) | (data[3] & 0xff);
                values |= 4;
                Timber.d("got muscle %d", values);

                break;

            case (byte)0xD0:
                lastKcal = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                int unknown = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
                values |= 8;
                Timber.d("got kal %d", values);

                break;
        }

        if (values == 15) {
            ScaleMeasurement scaleBtData = new ScaleMeasurement();
            scaleBtData.setWeight((float)lastWeight / 10.0f);
            scaleBtData.setFat((float)lastFat / 10.0f);
            scaleBtData.setWater((float)lastHydration / 10.0f);
            scaleBtData.setBone((float)lastBone / 10.0f);
            scaleBtData.setMuscle((float)lastMuscle / 10.0f);
            scaleBtData.setDateTime(new Date());
            addScaleMeasurement(scaleBtData);
            disconnect();
        }
    }

    private void synchroniseDate() {
        Calendar cal = Calendar.getInstance();

        byte message[] = new byte[]{(byte)0xA5, (byte)0x30, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        message[2] = (byte)Integer.parseInt(Long.toHexString(Integer.valueOf(String.valueOf(cal.get(Calendar.YEAR)).substring(2))), 16);

        String DayLength=Long.toHexString(cal.get(Calendar.DAY_OF_YEAR));
        DayLength=DayLength.length()==1?"000"+DayLength:
                DayLength.length()==2?"00"+DayLength:
                        DayLength.length()==3?"0"+DayLength:DayLength;

        message[3]=(byte)Integer.parseInt(DayLength.substring(0,2), 16);
        message[4]=(byte)Integer.parseInt(DayLength.substring(2,4), 16);

        addChecksum(message);

        writeBytes(writeService, writeCharacteristic, message);
    }

    private void synchroniseTime() {
        Calendar cal = Calendar.getInstance();

        byte message[] = new byte[]{(byte)0xA5, (byte)0x31, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};

        message[2]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.HOUR_OF_DAY)), 16);
        message[3]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.MINUTE)), 16);
        message[4]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.SECOND)), 16);

        addChecksum(message);

        writeBytes(writeService, writeCharacteristic, message);
    }

    private void addChecksum(byte[] message) {
        byte verify = 0;
        for(int i=1;i<message.length-2;i++){
            verify=(byte) (verify+message[i] & 0xff);
        }
        message[message.length-2]=verify;
    }


    private void synchroniseUser() {
        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();

        byte message[] = new byte[]{(byte)0xA5, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        //message[2] = (byte)((selectedUser.getGender().isMale() ? (byte)0x80: (byte)0x00) + 1+selectedUser.getId());
        message[2] = (byte) ((selectedUser.getGender().isMale() ? 15 : 0) * 16 + selectedUser.getId());
        message[3] = (byte)selectedUser.getAge();
        message[4] = (byte)selectedUser.getBodyHeight();

        addChecksum(message);

        writeBytes(writeService, writeCharacteristic, message);
    }
}
