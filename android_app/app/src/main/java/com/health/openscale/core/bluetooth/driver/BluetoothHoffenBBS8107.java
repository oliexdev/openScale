/* Copyright (C) 2021 Karol Werner <karol@ppkt.eu>
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

package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothHoffenBBS8107 extends BluetoothCommunication {

    private static final UUID UUID_SERVICE = BluetoothGattUuid.fromShortCode(0xffb0);
    private static final UUID UUID_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffb2);

    private static final byte MAGIC_BYTE = (byte) 0xFA;

    private static final byte RESPONSE_INTERMEDIATE_MEASUREMENT = (byte) 0x01;
    private static final byte RESPONSE_FINAL_MEASUREMENT = (byte) 0x02;
    private static final byte RESPONSE_ACK = (byte) 0x03;

    private static final byte CMD_MEASUREMENT_DONE = (byte) 0x82;
    private static final byte CMD_CHANGE_SCALE_UNIT = (byte) 0x83;
    private static final byte CMD_SEND_USER_DATA = (byte) 0x85;

    private ScaleUser user;

    public BluetoothHoffenBBS8107(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Hoffen BBS-8107";
    }

    public static String driverId() {
        return "hoffen_bbs8107";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                setNotificationOn(UUID_SERVICE, UUID_CHARACTERISTIC);
                user = OpenScale.getInstance().getSelectedScaleUser();
                break;

            case 1:
                // Send user data to the scale
                byte[] userData = {
                        (byte) 0x00,  // "plan" id?
                        user.getGender().isMale() ? (byte) 0x01 : (byte) 0x00,
                        (byte) user.getAge(),
                        (byte) user.getBodyHeight(),
                };
                sendPacket(CMD_SEND_USER_DATA, userData);

                // Wait for scale response for this packet
                stopMachineState();
                break;

            case 2:
                // Send preferred scale unit to the scale
                byte[] weightUnitData = {
                        (byte) (0x01 + user.getScaleUnit().toInt()),
                        (byte) 0x00,  // always empty
                };
                sendPacket(CMD_CHANGE_SCALE_UNIT, weightUnitData);

                // Wait for scale response for this packet
                stopMachineState();
                break;

            case 3:
                // Start measurement
                sendMessage(R.string.info_step_on_scale, 0);

                // Wait until measurement is done
                stopMachineState();
                break;

            case 4:
                // Indicate successful measurement to the scale
                byte[] terminateData = {
                        (byte) 0x00,  // always empty
                };
                sendPacket(CMD_MEASUREMENT_DONE, terminateData);

                // Wait for scale response for this packet
                stopMachineState();
                break;

            case 5:
                // Terminate the connection - scale will turn itself down after couple seconds
                disconnect();
                break;

            default:
                return false;
        }
        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (value == null || value.length < 2) {
            return;
        }

        if (!verifyData(value) && ((value[0] != MAGIC_BYTE) || (value[1] != RESPONSE_FINAL_MEASUREMENT))) {
            // For packet starting with 0xFA 0x02 checksum will be sent in next notify message so we
            // will disable checking checksum for this particular packet
            Timber.e("Checksum incorrect");
            return;
        }

        if (value[0] != MAGIC_BYTE) {
            Timber.w("Received unexpected, but correct data: %s", Arrays.toString(value));
            return;
        }

        float weight;
        switch (value[1]) {
            case RESPONSE_INTERMEDIATE_MEASUREMENT:
                // Got intermediate result
                weight = Converters.fromUnsignedInt16Le(value, 4) / 10.0f;
                Timber.d("Got intermediate weight: %.1f %s", weight, user.getScaleUnit().toString());
                break;

            case RESPONSE_FINAL_MEASUREMENT:
                // Got final result
                addScaleMeasurement(parseFinalMeasurement(value));
                resumeMachineState();
                break;

            case RESPONSE_ACK:
                // Got response from scale
                Timber.d("Got ack from scale, can proceed");
                resumeMachineState();
                break;

            default:
                Timber.e("Got unexpected response: %x", value[1]);
        }
    }

    private ScaleMeasurement parseFinalMeasurement(byte[] value) {
        float weight = Converters.fromUnsignedInt16Le(value, 3) / 10.0f;
        Timber.d("Got final weight: %.1f %s", weight, user.getScaleUnit().toString());
        sendMessage(R.string.info_measuring, weight);

        if (user.getScaleUnit() != Converters.WeightUnit.KG) {
            // For lb and st this scale will always return result in lb
            weight = Converters.toKilogram(weight, Converters.WeightUnit.LB);
        }

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setDateTime(new Date());
        measurement.setWeight(weight);

        if (value[5] == (byte) 0x00) {
            // If user stands bare foot on weight scale it will report more data
            measurement.setFat(Converters.fromUnsignedInt16Le(value, 6) / 10.0f);
            measurement.setWater(Converters.fromUnsignedInt16Le(value, 8) / 10.0f);
            measurement.setMuscle(Converters.fromUnsignedInt16Le(value, 10) / 10.0f);
            // Basal metabolic rate is not stored because it's calculated by app
            // Bone weight seems to be always returned in kg
            measurement.setBone(value[14] / 10.0f);
            // BMI is not stored because it's calculated by app
            measurement.setVisceralFat(Converters.fromUnsignedInt16Le(value, 17) / 10.0f);
            // Internal body age is not stored in app
        } else if (value[5] == (byte) 0x04) {
            Timber.w("No more data to store");
        } else {
            Timber.e("Received unexpected value: %x", value[5]);
        }
        return measurement;
    }

    private void sendPacket(byte command, byte[] payload) {
        // Add required fields to provided payload and send the packet
        byte[] outputArray = new byte[payload.length + 4];

        outputArray[0] = MAGIC_BYTE;
        outputArray[1] = command;
        outputArray[2] = (byte) payload.length;
        System.arraycopy(payload, 0, outputArray, 3, payload.length);
        // Calculate checksum skipping first element
        outputArray[outputArray.length - 1] = xorChecksum(outputArray, 1, outputArray.length - 2);

        writeBytes(UUID_SERVICE, UUID_CHARACTERISTIC, outputArray, true);
    }

    private boolean verifyData(byte[] data) {
        // First byte is skipped in calculated checksum
        return xorChecksum(data, 1, data.length - 1) == 0;
    }
}
