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

package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
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

    private byte reportedAlg = 0xf;

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
                if (stabilized && !weightStabilized) {
                    weightStabilized = true;
                    stableWeightKg = weightKg;
                    reportedAlg = pkt[0x11];
                    Timber.i("Measured weight (stable): %.3f, alg: %x", stableWeightKg, reportedAlg);
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
                    if (impedance >= 10.0) {
                        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                        int height = (int) Math.ceil(selectedUser.getBodyHeight());
                        int age = selectedUser.getAge();

                        calculateBIA(reportedAlg, height, impedance, stableWeightKg, age, selectedUser.getGender());
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
     * Calculate and store BIA parameters if a supported algorithm is reported by scales.
     *
     * @param alg algorithm used on the scales
     * @param heightCm height in cm
     * @param impedanceOhm imp1, single impedance reading in Ohm
     * @param weightKg weight in kg
     * @param age in years
     * @param gender the scale user's gender
     */
    private void calculateBIA(byte alg, int heightCm, double impedanceOhm, float weightKg, int age, Converters.Gender gender) {
        if (alg != 0x07) {
            Timber.w("Unsupported alg reported by scales: %d. Can't calculate BIA parameters", alg);
            return;
        }

        LibICBIACalculatorWLA07 alg7 = new LibICBIACalculatorWLA07(weightKg, heightCm, impedanceOhm, age, gender == Converters.Gender.MALE);
        double bmi = alg7.getBMI();
        double bodyFat = alg7.getBodyFatPercent();
        double muscle = alg7.getMusclePercent();
        double subcut = alg7.getSubcutaneousFatPercent(bodyFat);
        double visceralFat = alg7.getVisceralFat();
        double boneMass = alg7.getBoneMass();
        double water = alg7.getMoisturePercent();
        double protein = alg7.getProtein();
        double skeletalMuscleMass = alg7.getSkeletalMuscleMass();
        int bmr = alg7.getBMR();
        int bodyAge = alg7.getPhysicalAge();

        Timber.i("[Alg7/WLA07] BMI: %.1f, bodyFat: %.1f%%, muscle: %.1f%%, subcut. fat: %.1f%%, visceral fat: %.1f, bone: %.2f kg, water: %.1f%%, protein: %.1f%%, SMM: %.1f%%, BMR: %d, physical age: %d",
                bmi, bodyFat, muscle, subcut, visceralFat, boneMass, water, protein, skeletalMuscleMass, bmr, bodyAge);

        scaleData.setFat((float) bodyFat);
        scaleData.setWater((float) water);
        scaleData.setMuscle((float) muscle);
        scaleData.setVisceralFat((float) visceralFat);
        scaleData.setBone((float) boneMass);
        scaleData.setLbm((float) (weightKg * (100.0 - bodyFat) / 100.0));
        scaleData.setCalories(bmr);
    }

    private void parseHistoricalPacket(byte[] pkt) {
        int userId = pkt[0x02];
        Instant time = Instant.ofEpochSecond(Converters.fromUnsignedInt24Be(pkt, 3));
        float weight = (Converters.fromUnsignedInt24Be(pkt, 0x08) & 0x03FFFF) / 1000.0f;
        float weightLeft = Converters.fromUnsignedInt16Be(pkt, 0x0b) / 100.0f;
        int hr = pkt[0x0d] & 0xff;
        int adc = Converters.fromUnsignedInt16Be(pkt, 0x0f);
        byte alg = pkt[0x11];
        Timber.i(
            "Historical measurement: [%d] %.3f kg, Weight Left: %.2f kg, HR: %d, ADC: %d, alg: %d",
            userId, weight, weightLeft, hr, adc, alg
        );
        // TODO: store historical results
    }

    /**
     * Reverse-engineered from libICBodyFatAlgorithms.so, class
     * ICBodyFatAlgorithmWLA07 (algType 6 mapped to ICBFATypeWLA07).
     **/
    private static class LibICBIACalculatorWLA07 {
        // row layout: {heightCoef, weightCoef, ageCoef, impCoef, constant}
        private static final int[][] TABLE = {
                /* row 0: bfr, female  */ {-3332, 7509, 196, 72, 227193},
                /* row 1: bfr, male    */ {-3315, 6216, 183, 85, 225540},
                /* row 2: muscleRaw, female */ {31860, 19340, -2060, -1320, -1645560},
                /* row 3: muscleRaw, male   */ {28670, 38940, -4080, -1235, -1576650},
                /* row 4: water/protein, female */ {87700, 297300, 12800, -6030, 517500},
                /* row 5: water/protein, male   */ {93900, 375800, -3200, -6925, 97000},
                /* row 6: bmr, female  */ {75432, 99474, -34382, -3090, -2882821},
                /* row 7: bmr, male    */ {75037, 131523, -43376, -3486, -3117751},
                /* row 8: visceralFat, female */ {-1651, 2628, 649, 24, 123445},
                /* row 9: visceralFat, male   */ {-2675, 4200, 1462, 123, 139871},
                /* row 10: physicalAge, female */ {-11165, 15784, 4615, 415, 832548},
                /* row 11: physicalAge, male   */ {-7471, 9161, 4184, 517, 542267},
        };

        private int heightCm = -1;
        private double impedanceOhm = -1d;
        private float weightKg = -1f;
        private int age = -1;
        private boolean isMale = false;

        private double bfrRaw = -1d;
        private double muscleRaw = -1d;

        LibICBIACalculatorWLA07(float weightKg, int heightCm, double impedanceOhm, int age, boolean isMale) {
            this.weightKg = weightKg;
            this.heightCm = heightCm;
            this.impedanceOhm = impedanceOhm;
            this.age = age;
            this.isMale = isMale;

            this.bfrRaw = bfrRaw();
            this.muscleRaw = muscleRaw();
        }

        private double roundToOneDecimalPlace(double value) {
            double fVar2 = value % 1.0;
            fVar2 = fVar2 * 10.0;
            double fVar3 = fVar2 % 1.0;
            if (fVar3 > 0.5) {
                fVar2 = Math.ceil(fVar2);
            } else {
                fVar2 = Math.floor(fVar2);
            }
            return ((int) value) + (fVar2 / 10.0);
        }

        private double clamp(double value, double min, double max) {
            return Math.min(Math.max(value, min), max);
        }

        /** The shared 5-term regression: (height*A + weight*B + age*C + imp*D + E) / 10000 */
        private double regress(int row, int heightCm, double weightKg, int age, double impedanceOhm) {
            int[] c = TABLE[row];
            return (heightCm * c[0] + weightKg * c[1] + age * c[2] + impedanceOhm * c[3] + c[4]) / 10000.0;
        }

        double getBMI() {
            double bmi = (weightKg * 10000.0) / (heightCm * heightCm);
            return clamp(bmi, 4.0, 185.5);
        }

        /** bfr regression clamped to [5,45]. */
        private double bfrRaw() {
            double raw = (regress(isMale ? 1 : 0, heightCm, weightKg, age, impedanceOhm) / weightKg) * 100.0;
            if (raw <= 45.0) {
                return Math.max(raw, 5.0);
            }
            return 45.0;
        }

        double getBodyFatPercent() {
            return roundToOneDecimalPlace(bfrRaw);
        }

        double getSubcutaneousFatPercent(double bfrPercent) {
            return roundToOneDecimalPlace(bfrPercent * (-0.0002 * bfrPercent + 0.72));
        }

        /** Absolute (kg-ish) muscle mass from the row2/3 regression, with an FFM-residual correction band. */
        private double muscleRaw() {
            double bfr = clamp(bfrRaw, 5.0, 45.0);
            double muscleRegression = regress(isMale ? 3 : 2, heightCm, weightKg, age, impedanceOhm) / 10.0;
            double ffmResidual = weightKg * (1.0 - bfr / 100.0) - muscleRegression;
            if (ffmResidual >= 4.0) {
                return muscleRegression + ffmResidual - 4.0;
            }
            if (ffmResidual > 1.0) {
                return muscleRegression;
            }
            return muscleRegression + ffmResidual - 1.0;
        }

        double getMusclePercent() {
            return roundToOneDecimalPlace(muscleRaw / weightKg * 100.0);
        }

        double getBoneMass() {
            double muscle = muscleRaw;
            double bfr = clamp(bfrRaw, 5.0, 45.0);
            double residual = weightKg - (bfr * weightKg) / 100.0 - muscle;
            return roundToOneDecimalPlace(clamp(residual, 1.0, 4.0));
        }

        /** Visceral fat uses its own quirky round-to-nearest-5 (not 0.1) step before the final clamp. */
        double getVisceralFat() {
            double raw = regress(isMale ? 9 : 8, heightCm, weightKg, age, impedanceOhm) * 10.0;
            int truncated = (int) raw;
            int base = (truncated / 10) * 10;
            int rounded = (truncated % 10 < 6) ? base : base + 5;
            return roundToOneDecimalPlace(clamp(rounded / 10.0, 1.0, 59.0));
        }

        int getBMR() {
            double raw = regress(isMale ? 7 : 6, heightCm, weightKg, age, impedanceOhm);
            return (int) Math.round(clamp(raw, 400.0, 3500.0));
        }

        int getPhysicalAge() {
            if (age <= 14) {
                return age;
            }
            double raw = regress(isMale ? 11 : 10, heightCm, weightKg, age, impedanceOhm);
            int physicalAge = (int) clamp(raw, Double.NEGATIVE_INFINITY, 80.0);
            return Math.max(physicalAge, 15);
        }

        /** Shared by water% and protein%: row4/5 regression run through an FFM-residual correction band. */
        private double moistureCorrected() {
            double muscle = muscleRaw;
            double musclePercent = muscle / weightKg * 100.0;
            double raw = regress(isMale ? 5 : 4, heightCm, weightKg, age, impedanceOhm) / weightKg;
            double residual = musclePercent - raw;
            if (residual >= 32.0) {
                return musclePercent - 32.0;
            }
            if (residual > 5.0) {
                return raw;
            }
            return musclePercent - 5.0;
        }

        double getMoisturePercent() {
            double corrected = moistureCorrected();
            return roundToOneDecimalPlace(clamp(corrected, 20.0, 85.0));
        }

        double getProtein() {
            double muscle = muscleRaw;
            double waterRaw = clamp(regress(isMale ? 5 : 4, heightCm, weightKg, age, impedanceOhm) / weightKg, 20.0, 85.0);
            double protein = muscle / weightKg * 100.0 - waterRaw;
            return roundToOneDecimalPlace(clamp(protein, 5.0, 32.0));
        }

        double getSkeletalMuscleMass() {
            double muscle = muscleRaw;
            double sexFlag = isMale ? 1.0 : 0.0;
            double raw = impedanceOhm * -0.017 + weightKg * 0.1745 + heightCm * 0.2573
                    + sexFlag * 2.4269 - age * 0.0161 - 20.2165;
            double ratio = raw / muscle;
            if (ratio >= 0.7) {
                raw = muscle * 0.7;
            } else if (ratio <= 0.45) {
                raw = muscle * 0.45;
            }
            return roundToOneDecimalPlace(raw / weightKg * 100.0);
        }
    }
}
