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

import com.health.openscale.core.datatypes.ScaleData;

import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;

public class BluetoothMedisanaBS444 extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("000078b2-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a21-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID FEATURE_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a22-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID CUSTOM3_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a20-0000-1000-8000-00805f9b34fb"); // read-only
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a81-0000-1000-8000-00805f9b34fb"); // write-only
    private final UUID CUSTOM5_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a82-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private ScaleData btScaleData;

    public BluetoothMedisanaBS444(Context context) {
        super(context);
        btScaleData = new ScaleData();
    }

    @Override
    public String deviceName() {
        return "Medisana BS444";
    }

    @Override
    public String defaultDeviceName() {
        return "Medisana BS444";
    }

    @Override
    public boolean isDeviceNameCheck() {
        return false;
    }

    @Override
    public ArrayList<String> hwAddresses() {
        ArrayList hwAddresses = new ArrayList();
        hwAddresses.add("E454EB");
        hwAddresses.add("F13A88");

        return hwAddresses;
    }

    public boolean initSupported() {
        return false;
    }

    @Override
    boolean nextInitCmd(int stateNr){
        return false;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr){
        switch (stateNr) {
            case 0:
                // set indication on for feature characteristic
                setInidicationOn(WEIGHT_MEASUREMENT_SERVICE, FEATURE_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                // set indication on for weight measurement
                setInidicationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC);
                break;
            case 2:
                // set indication on for custom5 measurement
                setInidicationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM5_MEASUREMENT_CHARACTERISTIC);
                break;
            case 3:
                // send magic number to receive weight data
                Date date = new Date();
                int unix_timestamp = (int) ((date.getTime() / 1000) - 1262304000) ; // -40 years because unix time starts in year 1970

                byte[] magicBytes = new byte[] {
                        (byte)0x02,
                        (byte)(unix_timestamp),
                        (byte)(unix_timestamp >>> 8),
                        (byte)(unix_timestamp >>> 16),
                        (byte)(unix_timestamp >>> 24)
                };
                //byte[] magicBytes = new byte[]{(byte)0x02, (byte)0x7B, (byte)0x7B, (byte)0xF6, (byte)0x0D}; // 02:7b:7b:f6:0d

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    boolean nextCleanUpCmd(int stateNr){
        return false;
    }


    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic){
        final byte[] data = gattCharacteristic.getValue();

        if (gattCharacteristic.getUuid().equals(WEIGHT_MEASUREMENT_CHARACTERISTIC)) {
            parseWeightData(data);
        }

        if (gattCharacteristic.getUuid().equals(FEATURE_MEASUREMENT_CHARACTERISTIC)) {
            parseFeatureData(data);

            addScaleData(btScaleData);
        }
    }

    private void parseWeightData(byte[] weightData) {
        float weight = (float)(((weightData[2] & 0xFF) << 8) | (weightData[1] & 0xFF)) / 100.0f;
        long unix_timestamp = ((weightData[8] & 0xFF) << 24) | ((weightData[7] & 0xFF) << 16) | ((weightData[6] & 0xFF) << 8) | (weightData[5] & 0xFF); // elapsed time in seconds since 2010

        Date btDate = new Date();
        unix_timestamp += 1262304000; // +40 years because unix time starts in year 1970
        btDate.setTime(unix_timestamp*1000); // multiply with 1000 to get milliseconds

        btScaleData.setDateTime(btDate);
        btScaleData.setWeight(weight);
    }

    private void parseFeatureData(byte[] featureData) {
        //btScaleData.setKCal(((featureData[7] & 0xFF) << 8) | (featureData[6] & 0xFF));
        btScaleData.setFat(decodeFeature(featureData[8], featureData[9]));
        btScaleData.setWater(decodeFeature(featureData[10], featureData[11]));
        btScaleData.setMuscle(decodeFeature(featureData[12], featureData[13]));
        //btScaleData.setBone(decodeFeature(featureData[14], featureData[15]));
    }

    private float decodeFeature(byte highByte, byte lowByte) {
        return (float)(((lowByte& 0x0F) << 8) | (highByte & 0xFF)) / 10.0f;
    }
}
