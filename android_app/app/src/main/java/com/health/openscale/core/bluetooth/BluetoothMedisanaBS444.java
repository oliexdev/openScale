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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleData;

import java.util.Date;
import java.util.UUID;

import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED;
import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_CONNECTION_LOST;
import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_NO_DEVICE_FOUND;

public class BluetoothMedisanaBS444 extends BluetoothCommunication {
    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter.LeScanCallback scanCallback;

    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("000078b2-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a21-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID FEATURE_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a22-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID CUSTOM3_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a20-0000-1000-8000-00805f9b34fb"); // read-only
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a81-0000-1000-8000-00805f9b34fb"); // write-only
    private final UUID CUSTOM5_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00008a82-0000-1000-8000-00805f9b34fb"); // indication, read-only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private String btDeviceName;
    private Handler searchHandler;
    private ScaleData btScaleData;
    private int nextCmdState;

    public BluetoothMedisanaBS444(Context context) {
        super(context);
        searchHandler = new Handler();
        btScaleData = new ScaleData();
        scanCallback = null;
    }

    @Override
    public String deviceName() {
        return "Medisana BS444";
    }

    @Override
    public String defaultDeviceName() {
        return "Medisana BS444";
    }

    public boolean initSupported() {
        return false;
    }

    @Override
    public void startSearching(String deviceName) {
        btDeviceName = deviceName;

        if (scanCallback == null) {
            scanCallback = new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    if (device.getAddress().replace(":", "").toUpperCase().startsWith("E454EB")) {
                        //if (device.getName().equals(btDeviceName)) {
                            bluetoothGatt = device.connectGatt(context, false, gattCallback);

                            searchHandler.removeCallbacksAndMessages(null);
                            btAdapter.stopLeScan(scanCallback);
                        //}
                    }
                }
            };
        }

        searchHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                btAdapter.stopLeScan(scanCallback);
                setBtStatus(BT_NO_DEVICE_FOUND);
            }
        }, 10000);

        btAdapter.startLeScan(scanCallback);
    }

    @Override
    public void stopSearching() {
        if (bluetoothGatt != null)
        {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        searchHandler.removeCallbacksAndMessages(null);
        btAdapter.stopLeScan(scanCallback);
    }

    private BluetoothGattCallback gattCallback= new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setBtStatus(BT_CONNECTION_ESTABLISHED);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setBtStatus(BT_CONNECTION_LOST);
                stopSearching();
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            nextCmdState = 0;

            invokeNextBluetoothCmd(gatt);
        }

        private void invokeNextBluetoothCmd(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            BluetoothGattDescriptor descriptor;

            switch (nextCmdState) {
                case 0:
                    // set indication on for feature characteristic
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(FEATURE_MEASUREMENT_CHARACTERISTIC);

                    gatt.setCharacteristicNotification(characteristic, true);

                    descriptor = characteristic.getDescriptor(WEIGHT_MEASUREMENT_CONFIG);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);

                    gatt.writeDescriptor(descriptor);
                    break;
                case 1:
                    // set indication on for weight measurement
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_CHARACTERISTIC);

                    gatt.setCharacteristicNotification(characteristic, true);

                    descriptor = characteristic.getDescriptor(WEIGHT_MEASUREMENT_CONFIG);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    break;
                case 2:
                    // set indication on for custom5 measurement
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(CUSTOM5_MEASUREMENT_CHARACTERISTIC);

                    gatt.setCharacteristicNotification(characteristic, true);

                    descriptor = characteristic.getDescriptor(WEIGHT_MEASUREMENT_CONFIG);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    break;
                case 3:
                    // send magic number to receive weight data
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(CMD_MEASUREMENT_CHARACTERISTIC);

                    characteristic.setValue(new byte[]{(byte)0x02, (byte)0x7B, (byte)0x7B, (byte)0xF6, (byte)0x0D}); // 02:7b:7b:f6:0d
                    gatt.writeCharacteristic(characteristic);
                    break;
                case 4:
                    // end of command mode
                    break;
                default:
                    Log.e("BluetoothMedisanaScale", "Error invalid Bluetooth State");
                    break;
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            nextCmdState++;
            invokeNextBluetoothCmd(gatt);
        }

        @Override
        public void onCharacteristicWrite (BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            nextCmdState++;
            invokeNextBluetoothCmd(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            final byte[] data = characteristic.getValue();

            if (characteristic.getUuid().equals(WEIGHT_MEASUREMENT_CHARACTERISTIC)) {
                parseWeightData(data);
            }

            if (characteristic.getUuid().equals(FEATURE_MEASUREMENT_CHARACTERISTIC)) {
                parseFeatureData(data);

                addScaleData(btScaleData);
            }
        }
    };


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

    private void printByteInHex(byte[] data) {
        if (data == null) {
            Log.e("BluetoothMedisanaScale", "Data is null");
            return;
        }

        final StringBuilder stringBuilder = new StringBuilder(data.length);
        for(byte byteChar : data) {
            stringBuilder.append(String.format("%02X ", byteChar));
        }

        Log.d("BluetoothMedisanaScale", "Raw hex data: " + stringBuilder);
    }
}
