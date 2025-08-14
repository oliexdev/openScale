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

package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
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

    // 2nd Type Service and Characteristics (2nd Type doesn't need to indicate, and 4th characteristic is shared with 3rd.)
    private final UUID WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final UUID CUSTOM1_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"); // notify, read-only
    private final UUID CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"); // write-only

    private boolean useFirstType = true;


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
    // Have we received the final weight measurement
    private boolean finalMeasureReceived;
    // Weight scale of raw value
    private float weightScale = 100.0f;
    // Last seen protocol type, used in reply packets
    private int seenProtocolType = 0;


    public BluetoothQNScale(Context context) {
        super(context);
    }

    // Includes FITINDEX ES-26M
    // Includes some Renpho EC-CS20M
    @Override
    public String driverName() {
        return "QN Scale";
    }

    public static String driverId() {
        return "qn_scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                // Try writing bytes to 0xffe4 to check whether to use 1st or 2nd type
                try {
                    long timestamp = new Date().getTime() / 1000;
                    timestamp -= SCALE_UNIX_TIMESTAMP_OFFSET;
                    byte[] date = new byte[4];
                    Converters.toInt32Le(date, 0, timestamp);
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, CUSTOM4_MEASUREMENT_CHARACTERISTIC, new byte[]{(byte) 0x02, date[0], date[1], date[2], date[3]});
                } catch (NullPointerException e) {
                    useFirstType = false;
                }
                break;
            case 1:
                // Set indication on for weight measurement and for custom characteristic 1 (weight, time, and others)
                if (useFirstType) {
                    setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM1_MEASUREMENT_CHARACTERISTIC);
                    setIndicationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM2_MEASUREMENT_CHARACTERISTIC);
                } else {
                    setNotificationOn(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM1_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE);
                }
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
                // Send weight data
                byte[] msg = this.buildMsg(0x13, this.seenProtocolType, weightUnitByte, 0x10, 0x00, 0x00, 0x00);

                if (useFirstType) {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, CUSTOM3_MEASUREMENT_CHARACTERISTIC, msg);
                } else {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE, msg);
                }
                break;
            case 3:
                // Send time magic number to receive weight data
                long timestamp = new Date().getTime() / 1000;
                timestamp -= SCALE_UNIX_TIMESTAMP_OFFSET;
                byte[] date = new byte[4];
                Converters.toInt32Le(date, 0, timestamp);
                byte[] timeMagicBytes = new byte[]{(byte) 0x02, date[0], date[1], date[2], date[3]};

                if (useFirstType) {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE, CUSTOM4_MEASUREMENT_CHARACTERISTIC, timeMagicBytes);
                } else {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE, timeMagicBytes);
                }
                break;
            case 4:
                sendMessage(R.string.info_step_on_scale, 0);
                break;
            // Wait for final measurement
            case 5:
                // If we have not received data, wait.
                if (!this.finalMeasureReceived) {
                    stopMachineState();
                }
                break;
            // Send final message to scale
            case 6:
                // Send message to mark end of measurements
                byte[] end_msg = buildMsg(0x1f, this.seenProtocolType, 0x10);
                if (useFirstType) {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC, end_msg);
                } else {
                    writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE, end_msg);
                }
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;

        if (characteristic.equals(CUSTOM1_MEASUREMENT_CHARACTERISTIC) || characteristic.equals(CUSTOM1_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE)) {
            parseCustom1Data(data);
        }
    }

    // Parse custom1 data
    // Byte format:
    // 0 command
    // 1 byte count
    // 2 protocol type
    // .. message
    // _ checksum (sum of bytes mod 0xff)
    private void parseCustom1Data(byte[] data){
        {
            StringBuilder msgStr = new StringBuilder();

            for (byte b : data) {
                msgStr.append(String.format("%02X ", b));
            }
            Timber.d(msgStr.toString());
        }

        int command = data[0] & 0xff;
        int protocolType = data[2] & 0xff;
        if (this.seenProtocolType == 0) {
            this.seenProtocolType = protocolType;
        }

        // Weight in Kg
        float weightKg;
        // State for done byte
        int doneState;
        // Offset for date bytes
        int dateOff;
        // Offset for done byte
        int doneOff;
        // Offset for resistance bytes
        int resistOff;
        // Offset for weight bytes
        int weightOff;


        switch (command) {
            // Receive measurement
            case 0x10:
                doneState = 1;
                doneOff = 5;
                weightOff = 3;
                resistOff = 6;

                // Protocol 0xff appears to use different offsets
                // Observed:
                // 5-10 doneState 0 with weight and no resistance
                // 4 doneState 1 with weight and no resistance
                // 1 doneState 2 with weight and 2 (or 3) resistance values
                if (protocolType == 0xff) {
                    doneState = 2;
                    doneOff = 4;
                    weightOff = 5;
                    resistOff = 7;
                }

                int done = data[doneOff] & 0xff;
                // Unsteady measurement update, ignore and wait for final state.
                if (done == 0) {
                    this.finalMeasureReceived = false;
                // Final measurement, but already received.
                } else if (done == doneState && this.finalMeasureReceived) {
                    // Do nothing.
                    // (Is this needed?)
                // Final measurement, record results.
                } else if (done == doneState) {
                    this.finalMeasureReceived = true;

                    byte weight1 = data[weightOff];
                    byte weight2 = data[weightOff+1];
                    weightKg = decodeWeight(weight1, weight2);

                    // Weight needs to be divided by 10 for 2nd type
                    // (There is likely a bit in message that indicates this).
                    if (!useFirstType) {
                        weightKg /= 10;
                    }

                    Timber.d("Weight byte 1 %d", weight1);
                    Timber.d("Weight byte 2 %d", weight2);
                    Timber.d("Raw Weight: %f", weightKg);

                    if (weightKg > 0.0f) {
                        int resistance1 = decodeIntegerValue(data[resistOff], data[resistOff+1]);
                        int resistance2 = decodeIntegerValue(data[resistOff+2], data[resistOff+3]);
                        Timber.d("resistance1: %d", resistance1);
                        Timber.d("resistance2: %d", resistance2);

                        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();
                        Timber.d("scale user " + scaleUser);
                        ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
                        // TrisaBodyAnalyzeLib gives almost simillar values for QNScale body fat calcualtion
                        TrisaBodyAnalyzeLib qnscalelib = new TrisaBodyAnalyzeLib(scaleUser.getGender().isMale() ? 1 : 0, scaleUser.getAge(), (int)scaleUser.getBodyHeight());

                        // Not much difference between resistance1 and resistance2.
                        // Will use resistance 1 for now
                        float impedance = resistance1 < 410f ? 3.0f : 0.3f * (resistance1 - 400f);
                        btScaleMeasurement.setFat(qnscalelib.getFat(weightKg, impedance));
                        btScaleMeasurement.setWater(qnscalelib.getWater(weightKg, impedance));
                        btScaleMeasurement.setMuscle(qnscalelib.getMuscle(weightKg, impedance));
                        btScaleMeasurement.setBone(qnscalelib.getBone(weightKg, impedance));
                        btScaleMeasurement.setWeight(weightKg);
                        addScaleMeasurement(btScaleMeasurement);

                        // After receiving weight restart state machine
                        resumeMachineState();
                    } else {
                      // Do something if we don't get a valid weight?
                    }
                }
                break;
            // Unsure, scale settings / features?
            case 0x12:
                // Set weight scale
                // This appears to be wrong for protocol 0xff
                this.weightScale = (data[10] & 0xff) == (byte) 1 ? 100.0f : 10.0f;
                // Unsure what we're sending back, possibly settings like weight scale.
                writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE,
                           this.buildMsg(0x13, protocolType, 0x01, 0x10, 0x00, 0x00, 0x00));
                break;
            // Unsure
            case 0x14:
                // Received:
                // 0x140bff000001000000001f
                // Sent:
                // 0x2008ff2574183008
                writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE,
                           this.buildMsg(0x20, protocolType, 0x25, 0x74, 0x18, 0x30));
                break;
            // Unsure
            case 0x21:
                // Received:
                // 0x2105ff0126
                // Sent
                // 0xa00d02feffee011c0686030248
                // Unsure why protocol type is different here.
                writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE,
                           this.buildMsg(0xa0, 0x02, 0xfe, 0xff, 0xee, 0x01, 0x1c, 0x06, 0x86, 0x03, 0x02));
                break;
            // Unsure, maybe another weight measurement?
            case 0x23:
                dateOff = 5;
                weightOff = 9;
                resistOff = 11;

                // Protocol 0xff appears to use different offsets
                if (protocolType == 0xff) {
                    dateOff = 6;
                    weightOff = 10;
                    resistOff = 12;
                }

                weightKg = decodeWeight(data[weightOff], data[weightOff+1]);

                if (weightKg > 0.0f) {
                    int resistance = decodeIntegerValue(data[resistOff], data[resistOff+1]);
                    int resistance500 = decodeIntegerValue(data[resistOff+2], data[resistOff+3]);
                    long differTime = 0;
                    for (int i = 0; i < 4; i++) {
                        differTime |= (((long) data[i + dateOff]) & 0xff) << (i * 8);
                    }
                    Date date = new Date(MILLIS_2000_YEAR + (1000 * differTime));

                    // Further implementation not written.
                    //
                    // Possible this is historical data

                    //  TODO
                    // QNData qnData = buildMeasuredData(user, weight, resistance,
                    //                                resistance500, date, data);


                    // Appears that data[4] is count, and data[3] is total.
                    // So data[3] == data[4] means final message.
                    if (data[3] == data[4]) {
                        //  TODO
                    }
                }
                break;
            // Unsure
            case 0xa1:
                // Don't know this data, just reply
                // Received:
                // a10602fe01a8
                // Sent:
                // 2206ff000128
                writeBytes(WEIGHT_MEASUREMENT_SERVICE_ALTERNATIVE, CUSTOM3_MEASUREMENT_CHARACTERISTIC_ALTERNATIVE,
                           this.buildMsg(0x22, protocolType, 0x00, 0x01));
                break;
        }
    }

    private float decodeWeight(byte a, byte b) {
        return ((float) (((a & 0xff) << 8) + (b & 0xff))) / this.weightScale;
    }

    private int decodeIntegerValue(byte a, byte b) {
        return ((a & 0xff) << 8) + (b & 0xff);
    }

    // Builds message
    // 0 command
    // 1 byteCount
    // 2 protocolType
    // .. payload
    // _ checksum
    static byte[] buildMsg(int cmd, int protocolType, int... payload) {
        byte[] msg = new byte[(payload.length + 4)];
        msg[0] = (byte) cmd;
        msg[1] = (byte) (msg.length + 4);
        msg[2] = (byte) protocolType;
        for (int i = 0; i < payload.length; i++) {
            msg[i + 3] = (byte) payload[i];
        }
        int checkIndex = msg.length - 1;
        for (int i = 0; i < checkIndex; i++) {
            msg[checkIndex] = (byte) (msg[checkIndex] + msg[i]);
        }
        return msg;
    }

}
