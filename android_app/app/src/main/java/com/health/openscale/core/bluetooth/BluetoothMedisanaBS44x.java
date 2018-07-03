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

import java.util.Date;
import java.util.UUID;

public class BluetoothMedisanaBS44x extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("000078b2-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a21-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID FEATURE_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a22-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID CUSTOM3_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a20-0000-1000-8000-00805f9b34fb"); // read-only
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a81-0000-1000-8000-00805f9b34fb"); // write-only
    private final UUID CUSTOM5_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a82-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private ScaleMeasurement btScaleMeasurement;

    // Scale time is in seconds since 2010-01-01
    private static final long SCALE_UNIX_TIMESTAMP_OFFSET = 1262304000;

    public BluetoothMedisanaBS44x(Context context) {
        super(context);
        btScaleMeasurement = new ScaleMeasurement();
    }

    @Override
    public String driverName() {
        return "Medisana BS44x";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                // set indication on for feature characteristic
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE, FEATURE_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                // set indication on for weight measurement
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 2:
                // set indication on for custom5 measurement
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE, CUSTOM5_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 3:
                // send magic number to receive weight data
                long timestamp = new Date().getTime() / 1000;
                timestamp -= SCALE_UNIX_TIMESTAMP_OFFSET;
                byte[] date = Converters.toUnsignedInt32Le(timestamp);

                byte[] magicBytes = new byte[] {(byte)0x02, date[0], date[1], date[2], date[3]};

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        return false;
    }


    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        if (gattCharacteristic.getUuid().equals(WEIGHT_MEASUREMENT_CHARACTERISTIC)) {
            parseWeightData(data);
        }

        if (gattCharacteristic.getUuid().equals(FEATURE_MEASUREMENT_CHARACTERISTIC)) {
            parseFeatureData(data);

            addScaleData(btScaleMeasurement);
        }
    }

    private void parseWeightData(byte[] weightData) {
        float weight = Converters.fromUnsignedInt16Le(weightData, 1) / 100.0f;
        long timestamp = Converters.fromUnsignedInt32Le(weightData, 5);
        timestamp += SCALE_UNIX_TIMESTAMP_OFFSET;

        btScaleMeasurement.setDateTime(new Date(timestamp * 1000));
        btScaleMeasurement.setWeight(weight);
    }

    private void parseFeatureData(byte[] featureData) {
        //btScaleData.setKCal(Converters.fromUnsignedInt16Le(featureData, 6));
        btScaleMeasurement.setFat(decodeFeature(featureData, 8));
        btScaleMeasurement.setWater(decodeFeature(featureData, 10));
        btScaleMeasurement.setMuscle(decodeFeature(featureData, 12));
        btScaleMeasurement.setBone(decodeFeature(featureData, 14));
    }

    private float decodeFeature(byte[] featureData, int offset) {
        return (Converters.fromUnsignedInt16Le(featureData, offset) & 0x0FFF) / 10.0f;
    }
}
