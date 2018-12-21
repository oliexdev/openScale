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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.Converters;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    public BluetoothQNScale(Context context) {
        super(context);
    }

    // Includes FITINDEX ES-26M
    @Override
    public String driverName() {
        return "QN Scale";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                // set notification on for custom characteristic 1 (weight, time, and others)
                setNotificationOn(CUSTOM1_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                // set indication on for weight measurement
                setIndicationOn(CUSTOM2_MEASUREMENT_CHARACTERISTIC);
                break;
            case 2:
                // write magicnumber 0x130915011000000042 to 0xffe3
                byte[] ffe3magicBytes = new byte[] {(byte)0x13, (byte)0x09, (byte)0x15, (byte)0x01, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x42};
                writeBytes(CUSTOM3_MEASUREMENT_CHARACTERISTIC, ffe3magicBytes);
                break;
            case 3:
                // send time magic number to receive weight data
                long timestamp = new Date().getTime() / 1000;
                timestamp -= SCALE_UNIX_TIMESTAMP_OFFSET;
                byte[] date = new byte[4];
                Converters.toInt32Le(date, 0, timestamp);
                byte[] timeMagicBytes = new byte[] {(byte)0x02, date[0], date[1], date[2], date[3]};
                writeBytes(CUSTOM4_MEASUREMENT_CHARACTERISTIC, timeMagicBytes);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {

        switch (stateNr) {
            case 0:
                // send stop command to scale (0x1f05151049)
                writeBytes(CUSTOM3_MEASUREMENT_CHARACTERISTIC, new byte[]{(byte)0x1f, (byte)0x05, (byte)0x15, (byte)0x10, (byte)0x49});
                break;
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

    private void parseCustom1Data(byte[] custom1Data){
        int firstByte = custom1Data[0] & 0xFF;
        int secondByte = custom1Data[1] & 0xFF;
        int thirdByte = custom1Data[2] & 0xFF;
        Timber.d("First byte %d", firstByte);
        Timber.d("Second byte %d", secondByte);
        Timber.d("Third byte %d", thirdByte);
        //int fourthByte = custom1Data[3] & 0xFF;
        //int fifthByte = custom1Data[4] & 0xFF;

        // If this is a weight byte
        if (firstByte == 0x10 && secondByte == 0x0b && thirdByte == 0x15){
            ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
            byte[] weightBytes = new byte[]{custom1Data[3], custom1Data[4]};
            int rawWeight = ((weightBytes[0] & 0xff) <<8 | weightBytes[1] & 0xff);
            float weight = rawWeight / 100.0f;
            //float weight = Converters.fromUnsignedInt16Le(weightBytes, 0) / 100.0f;
            int weightByteOne = custom1Data[3] & 0xFF;
            int weightByteTwo = custom1Data[4] & 0xFF;
            Timber.d("Weight byte 1 %d", weightByteOne);
            Timber.d("Weight byte 2 %d", weightByteTwo);
            Timber.d("Raw Weight: %d", rawWeight);
            btScaleMeasurement.setWeight(weight);
            //setBtMachineState(BT_MACHINE_STATE.BT_CLEANUP_STATE)
            addScaleData(btScaleMeasurement);
        }
    }
}
