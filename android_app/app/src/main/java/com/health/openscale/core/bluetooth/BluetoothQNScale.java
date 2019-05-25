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

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothQNScale extends BluetoothCommunication {
    // accurate. Indication means requires ack. notification does not
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    //private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"); // notify, read-only
    //private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("29f11080-75b9-11e2-8bf6-0002a5d5c51b"); // write only
    // Client Characteristic Configuration Descriptor, constant value of 0x2902
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    ///////////// explore
    // Read value notification to get weight. Some other payload structures as well 120f15 & 140b15
    // Also handle 14. Send write requests that are empty? Subscribes to notification on 0xffe1
    private final UUID CUSTOM1_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"); // notify, read-only
    // Receive value indication. Is always magic value 210515013c. Message occurs before or after last weight...almost always before.
    // Also send (empty?) write requests on handle 17? Subscribes to indication on 0xffe2
    private final UUID CUSTOM2_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb"); // indication, read-only
    // Sending write with magic 1f05151049 terminates connection. Sending magic 130915011000000042 only occurs after receiveing a 12 or 14 on 0xffe1 and is always followed by receiving a 14 on 0xffe1
    private final UUID CUSTOM3_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb"); // write-only
    // Send write of value like 20081568df4023e7 (constant until 815. assuming this is time?). Always sent following the receipt of a 14 on 0xffe1. Always prompts the receipt of a value indication on 0xffe2. This has to be sending time, then prompting for scale to send time for host to finally confirm
    private final UUID CUSTOM4_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb"); // write-only
    // Never used
    private final UUID CUSTOM5_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"); // write-only
    /////////////

    /** API
     connectDevice(device, "userId", 170, 1, birthday, new new QNBleCallback(){
     void onConnectStart(QNBleDevice bleDevice);
     void onConnected(QNBleDevice bleDevice);
     void onDisconnected(QNBleDevice bleDevice,int status);
     void onUnsteadyWeight(QNBleDevice bleDevice, float weight);
     void onReceivedData(QNBleDevice bleDevice, QNData data);
     void onReceivedStoreData(QNBleDevice bleDevice, List<QNData> datas);
     void onLowPower();
     **/

    // Scale time is in seconds since 2000-01-01 00:00:00 (utc).
    private static final long SCALE_UNIX_TIMESTAMP_OFFSET = 946702800;


    private static long MILLIS_2000_YEAR = 949334400000L;
    private boolean hasReceived;
    private float weightScale=100.0f;


    public BluetoothQNScale(Context context) {
        super(context);
    }

    // Includes FITINDEX ES-26M
    @Override
    public String driverName() {
        return "QN Scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // set notification on for custom characteristic 1 (weight, time, and others)
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM1_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                // set indication on for weight measurement
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM2_MEASUREMENT_CHARACTERISTIC);
                break;
            case 2:
                final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
                final Converters.WeightUnit scaleUserWeightUnit = scaleUser.getScaleUnit();
                // Value of 0x01 = KG. 0x02 = LB. Requests with stones unit are sent as LB, with post-processing in vendor app.
                byte weightUnitByte = (byte) 0x01;
                // Default weight unit KG. If user config set to LB or ST, scale will show LB units, consistent with vendor app
                if (scaleUserWeightUnit == Converters.WeightUnit.LB || scaleUserWeightUnit == Converters.WeightUnit.ST){
                    weightUnitByte = (byte) 0x02;
                }
                // write magicnumber 0x130915[WEIGHT_BYTE]10000000[CHECK_SUM] to 0xffe3
                // 0x01 weight byte = KG. 0x02 weight byte = LB.
                byte[] ffe3magicBytes = new byte[]{(byte) 0x13, (byte) 0x09, (byte) 0x15, weightUnitByte, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
                // Set last byte to be checksum
                ffe3magicBytes[ffe3magicBytes.length -1] = sumChecksum(ffe3magicBytes, 0, ffe3magicBytes.length - 1);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CUSTOM3_MEASUREMENT_CHARACTERISTIC, ffe3magicBytes);
                break;
            case 3:
                // send time magic number to receive weight data
                long timestamp = new Date().getTime() / 1000;
                timestamp -= SCALE_UNIX_TIMESTAMP_OFFSET;
                byte[] date = new byte[4];
                Converters.toInt32Le(date, 0, timestamp);
                byte[] timeMagicBytes = new byte[]{(byte) 0x02, date[0], date[1], date[2], date[3]};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CUSTOM4_MEASUREMENT_CHARACTERISTIC, timeMagicBytes);
                break;
            case 4:
                sendMessage(R.string.info_step_on_scale, 0);
                break;
            /*case 5:
                // send stop command to scale (0x1f05151049)
                writeBytes(CUSTOM3_MEASUREMENT_CHARACTERISTIC, new byte[]{(byte)0x1f, (byte)0x05, (byte)0x15, (byte)0x10, (byte)0x49});
                break;*/
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;

        if (characteristic.equals(CUSTOM1_MEASUREMENT_CHARACTERISTIC)) {
            parseCustom1Data(data);
        }
    }

    private void parseCustom1Data(byte[] data){
        StringBuilder sb = new StringBuilder();

        int len = data.length;
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", new Object[]{Byte.valueOf(data[i])}));

        }
        Timber.d(sb.toString());
        float weightKg=0;
        switch (data[0]) {
            case (byte) 16:
                if (data[5] == (byte) 0) {
                    this.hasReceived = false;
                    //this.callback.onUnsteadyWeight(this.qnBleDevice, decodeWeight(data[3],  data[4]));
                } else if (data[5] == (byte) 1) {
                    //        writeData(CmdBuilder.buildOverCmd(this.protocolType, 16));
                    if (!this.hasReceived) {
                        this.hasReceived = true;
                        weightKg = decodeWeight(data[3], data[4]);
                        int weightByteOne = data[3] & 0xFF;
                        int weightByteTwo = data[4] & 0xFF;

                        Timber.d("Weight byte 1 %d", weightByteOne);
                        Timber.d("Weight byte 2 %d", weightByteTwo);
                        Timber.d("Raw Weight: %f", weightKg);

                        if (weightKg > 0.0f) {
                            //QNData md = buildMeasuredData(this.qnUser, weight, decodeIntegerValue
                            // (data[6], data[7]), decodeIntegerValue(data[8], data[9]),
                            // new  Date(), data);

                            int resistance1 = decodeIntegerValue   (data[6], data[7]);
                            int resistance2 = decodeIntegerValue(data[8], data[9]);
                            Timber.d("resistance1: %d", resistance1);
                            Timber.d("resistance2: %d", resistance2);

                            final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
                            Timber.d("scale user " + scaleUser);
                            ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
                            //TrisaBodyAnalyzeLib gives almost simillar values for QNScale body fat calcualtion
                            TrisaBodyAnalyzeLib qnscalelib = new TrisaBodyAnalyzeLib(scaleUser.getGender().isMale() ? 1 : 0, scaleUser.getAge(), (int)scaleUser.getBodyHeight());

                            //Now much difference between resistance1 and resistance2.
                            //Will use resistance 1 for now
                            float impedance = resistance1 < 410f ? 3.0f : 0.3f * (resistance1 - 400f);
                            btScaleMeasurement.setFat(qnscalelib.getFat(weightKg, impedance));
                            btScaleMeasurement.setWater(qnscalelib.getWater(weightKg, impedance));
                            btScaleMeasurement.setMuscle(qnscalelib.getMuscle(weightKg, impedance));
                            btScaleMeasurement.setBone(qnscalelib.getBone(weightKg, impedance));
                            btScaleMeasurement.setWeight(weightKg);
                            addScaleMeasurement(btScaleMeasurement);
                        }
                    }
                }
            break;
            case (byte) 18:
                byte protocolType = data[2];
                this.weightScale = data[10] == (byte) 1 ? 100.0f : 10.0f;
                int[] iArr = new int[5];
                //TODO
                //writeData(CmdBuilder.buildCmd(19, this.protocolType, 1, 16, 0, 0, 0));
                break;
            case (byte) 33:
                //  TODO
                //writeBleData(CmdBuilder.buildCmd(34, this.protocolType, new int[0]));
                break;
            case (byte) 35:
                weightKg = decodeWeight(data[9], data[10]);
                if (weightKg > 0.0f) {
                    int resistance = decodeIntegerValue(data[11], data[12]);
                    int resistance500 = decodeIntegerValue(data[13], data[14]);
                    long differTime = 0;
                    for (int i = 0; i < 4; i++) {
                        differTime |= (((long) data[i + 5]) & 255) << (i * 8);
                    }
                    Date date = new Date(MILLIS_2000_YEAR + (1000 * differTime));

                    //  TODO
                    // QNData qnData = buildMeasuredData(user, weight, resistance,
                    //                                resistance500, date, data);


                    if (data[3] == data[4]) {
                        //  TODO
                    }
                }
                break;
        }
    }

    private float decodeWeight(byte a, byte b) {
        return ((float) (((a & 255) << 8) + (b & 255))) / this.weightScale;
    }

    private int decodeIntegerValue(byte a, byte b) {
        return ((a & 255) << 8) + (b & 255);
    }



}
