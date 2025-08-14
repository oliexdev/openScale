/* Copyright (C) 2024  olie.xdev <olie.xdev@googlemail.com>
*                2024  Duncan Overbruck <mail@duncano.de>
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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import timber.log.Timber;

/**
 * Support for Active Era BS-06 scales
 *
 * based on reverse-engineered BLE protocol known as `ICBleProtocolVerScaleNew2` from the vendor APP
 */
public class BluetoothActiveEraBF06 extends BluetoothCommunication {
    private static final byte MAGIC_BYTE = (byte) 0xAC;
    private static final byte DEVICE_TYPE = (byte) 0x27;

    private final UUID MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xffb0);
    private final UUID WRITE_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffb1);
    private final UUID NOTIFICATION_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffb2);

    private boolean weightStabilized = false;
    private float stableWeightKg = 0.0f;

    private boolean isSupportPH = false;
    private boolean isSupportHR = false;

    private boolean balanceStabilized = false;
    private float stableBalanceL = 0.0f;

    private double impedance = 0.0f;

    private ScaleMeasurement scaleData;

    public BluetoothActiveEraBF06(Context context) {
        super(context);
    }

    private byte[] getConfigurationPacket() {
        // current time
        long now = Instant.now().toEpochMilli() / 1000;
        byte[] time = Converters.toInt32Be(now);

        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
        int height = (int) Math.ceil(selectedUser.getBodyHeight());
        int age = selectedUser.getAge();
        int gender = selectedUser.getGender() == Converters.Gender.FEMALE ? 0x02 : 0x01;

        int units = 0; // KG
        switch(selectedUser.getScaleUnit()) {
            case LB:
                units = 1;
                break;
            case ST:
                units = 2;
                break;
        };

        int initialWeight = (int) Math.ceil(selectedUser.getInitialWeight() * 100);
        byte[] initialWeightBytes = Converters.toInt16Be(initialWeight);

        byte[] targetWeightBytes;
        float goalWeight = selectedUser.getGoalWeight();
        if (goalWeight > -1) {
            int targetWeight = (int) Math.ceil(goalWeight * 100);
            targetWeightBytes = Converters.toInt16Be(targetWeight);
        } else {
            targetWeightBytes = initialWeightBytes;
        }

        byte[] configBytes = new byte[]{
                /* 0x00 */ MAGIC_BYTE,
                /* 0x01 */ DEVICE_TYPE,
                /* 0x02 */ time[0],
                /* 0x03 */ time[1],
                /* 0x04 */ time[2],
                /* 0x05 */ time[3],
                /* 0x06 */ 0x04,
                /* 0x07 */ (byte)units,
                /* 0x08 */ 0x01, // user id ?
                /* 0x09 */ (byte)(height & 0xFF),
                /* 0x0a */ initialWeightBytes[0],
                /* 0x0b */ initialWeightBytes[1],
                /* 0x0c */ (byte)(age & 0xFF),
                /* 0x0d */ (byte)gender,
                /* 0x0e */ targetWeightBytes[0],
                /* 0x0f */ targetWeightBytes[1],
                /* 0x10 */ 0x03,
                /* 0x11 */ 0x00,
                /* 0x12 */ (byte)0xd0,
                /* 0x13 */ (byte)0x00 // checksum
        };

        return withCorrectCS(configBytes);
    }

    private void sendConfigurationPacket() {
        byte[] packet = getConfigurationPacket();

        Timber.d("sending configuration packet: %s", byteInHex(packet));
        writeBytes(MEASUREMENT_SERVICE, WRITE_CHARACTERISTIC, packet);
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        decodePacket(value);
    }

    @Override
    public String driverName() {
        return "Active Era BF-06";
    }

    public static String driverId() {
        return "active_era_bf06";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                //Tell device to send us measurements
                setNotificationOn(MEASUREMENT_SERVICE, NOTIFICATION_CHARACTERISTIC);

                // reset old values
                stableWeightKg = 0.0f;
                stableBalanceL = 0.0f;
                impedance = 0;
                weightStabilized = false;
                balanceStabilized = false;
                scaleData = new ScaleMeasurement();

                break;

            case 1:
                sendConfigurationPacket();
                break;

            case 2: // weighting ...
                sendMessage(R.string.info_step_on_scale, 0);
                stopMachineState();
                break;

            case 3: // weighted ! measuring balance ...
                stopMachineState();
                break;

            case 4: // balanced ! reporting ADC and measuring HR ...
                stopMachineState();
                break;

            case 5: // HR measured! Maybe some historical will follow
                Timber.i("Measuring all done!");

                scaleData.setDateTime(Calendar.getInstance().getTime());
                addScaleMeasurement(scaleData);
            default:
                return false;
        }

        return true;
    }


    private void decodePacket(byte[] pkt) {
        if (pkt == null) {
            return;
        } else if (pkt[0] != MAGIC_BYTE) {
            Timber.w("Wrong packet MAGIC");
            return;
        } else if (pkt.length != 20) {
            Timber.w("Wrong packet length %s expected 20", pkt.length);
            return;
        }

        int packetType = pkt[0x12] & 0xFF;
        switch (packetType) {
            case 0xD5: // weight measurement
                byte flags = pkt[0x02];
                boolean stabilized = isBitSet(flags, 8);
                isSupportHR = isBitSet(flags, 2);
                isSupportPH = isBitSet(flags, 3);

                float weightKg = (Converters.fromUnsignedInt24Be(pkt, 3) & 0x3FFFF) / 1000.0f;
                // TODO: test if it's always in grams ?
                if (stabilized && !weightStabilized) {
                    weightStabilized = true;
                    stableWeightKg = weightKg;
                    Timber.i("Measured weight (stable): %.3f", stableWeightKg);
                    scaleData.setWeight(weightKg);
                    resumeMachineState();
                }

                break;

            case 0xD0: // balance measuring
                byte state = pkt[0x02];
                boolean isFinal = state == 0x01;

                int weightLRaw = Converters.fromUnsignedInt16Be(pkt, 3);
                int percentLRaw = Converters.fromUnsignedInt16Be(pkt, 5);
                float weightL = (float)weightLRaw / 100.0f;
                float percentL = (float)percentLRaw / 10.0f;

                if (isFinal && !balanceStabilized) {
                    balanceStabilized = true;
                    stableBalanceL = percentL;
                    Timber.i("Measured balance (stable): L %.1f R: %.1f [%.2f]", percentL, 100.0f - percentL, weightL);
                    resumeMachineState();
                }
                break;

            case 0xD6: // reporting ADCs
                byte number = pkt[0x02];
                if (number == 1) {
                    double imp = Converters.fromUnsignedInt16Be(pkt, 4);
                    if (imp >= 1500.0d) {
                        imp = (((imp  - 1000.0d) + ((stableWeightKg * 10.0d) * (-0.4d))) / 0.6d) / 10.0d;
                    }
                    impedance = imp;
                    Timber.i("Measured impedance: %.1f", impedance);

                    // calculate BIA using measure weight and impedance
                    if (impedance > 0.0) {
                        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                        int height = (int) Math.ceil(selectedUser.getBodyHeight());
                        int age = selectedUser.getAge();
                        int gender = selectedUser.getGender() == Converters.Gender.FEMALE ? 0 : 1;

                        calculateBIA(height, impedance, stableWeightKg, age, gender);
                        // TODO: report results
                    }

                } else {
                    Timber.w("Unsupported number of ADCs: %s", number);
                }

                stopMachineState();
                break;

            case 0xD7: // HR measured
                int hr = pkt[0x03] & 0xff;
                Timber.i("Measured heart rate: %d", hr);
                resumeMachineState();

                break;

            case 0xD8: // historical measurement
                parseHistoricalPacket(pkt);

            default:
                Timber.w("Unsupported packet [%d]: %s", packetType, byteInHex(pkt));
        }

    }

    private byte[] withCorrectCS(byte[] pkt) {
        byte[] fixed = Arrays.copyOf(pkt, pkt.length);
        fixed[fixed.length - 1] = sumChecksum(fixed, 2, fixed.length - 3);
        return fixed;
    }

    /**
     * Calculate BIA parameters
     * for now, using forumlas from
     * <a href="https://isn.ucsd.edu/courses/beng186b/project/2021/Raj_Sunku_Tsujimoto_Measuring_body_composition_via_body_impedance.pdf">paper</a>
     *
     * TODO: replace with reverse-engineered library version
     *
     * @param heightCm
     * @param impedanceOhm
     * @param weightKg
     * @param age - in years
     * @param gender - 0 - female, 1 - male
     */
    private void calculateBIA(int heightCm, double impedanceOhm, float weightKg, int age, int gender) {
        // FFM = 0.36(H2/Z) + 0.162H + 0.289W − 0.134A + 4.83G − 6.83
        double fatFreeMass = (0.36d * (Math.pow(heightCm, 2) / impedanceOhm))
                + (0.162d * heightCm)
                + (0.289d * weightKg)
                - (0.134 * age)
                + (4.83 * gender)
                - 6.83;

        double fatMass = weightKg - fatFreeMass;
        double bodyFat = fatMass / weightKg * 100.0;
        Timber.i("FFM: %.2f, FM: %.2f, BF: %.1f%%", fatFreeMass, fatMass, bodyFat);
    }

    private void parseHistoricalPacket(byte[] pkt) {
        Instant time = Instant.ofEpochSecond(Converters.fromUnsignedInt24Be(pkt, 3));
        float weight = (Converters.fromUnsignedInt24Be(pkt, 0x08) & 0x03FFFF) / 1000.0f;
        float weightLeft = Converters.fromUnsignedInt16Be(pkt, 0x0b) / 100.0f;
        int hr = pkt[0x0d] & 0xff;
        int adc = Converters.fromUnsignedInt16Be(pkt, 0x0f);
        Timber.i("Historical measurement: %.3f kg, Weight Left: %.2f kg, HR: %d, ADC: %d", weight, weightLeft, hr, adc);
        // TODO: store historical results
    }
}
