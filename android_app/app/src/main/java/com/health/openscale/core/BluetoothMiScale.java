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

package com.health.openscale.core;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class BluetoothMiScale extends BluetoothCommunication {

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic weightCharacteristic;

    private BluetoothAdapter.LeScanCallback scanCallback = null;
    private BluetoothGattCallback gattCallback = null;

    private Handler searchHandler;

    private Context context;

    private String btDeviceName;

    public BluetoothMiScale(Context con) {
        searchHandler = new Handler();
        context = con;
    }

    @Override
    void startSearching(String deviceName) {
        btDeviceName = deviceName;

        if (scanCallback == null)
        {
            Log.d("StartStopBLEScanning", "No callback method, making one...");
            prepareBLECallback();
        }


        searchHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                btAdapter.stopLeScan(scanCallback);
                callbackBtHandler.obtainMessage(BluetoothCommunication.BT_NO_DEVICE_FOUND).sendToTarget();
            }
        }, 10000);

        btAdapter.startLeScan(scanCallback);
    }

    @Override
    void stopSearching() {
        if (bluetoothGatt != null)
        {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        searchHandler.removeCallbacksAndMessages(null);
        btAdapter.stopLeScan(scanCallback);
    }

    private void prepareBLECallback()
    {
        scanCallback = new BluetoothAdapter.LeScanCallback()
        {
            @Override
            public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord)
            {
                if (device.getAddress().replace(":", "").startsWith("880f10") ||
                        device.getAddress().replace(":", "").startsWith("880F10")) // Xiaomi
                {
                    if (device.getName().equals(btDeviceName)) { // It really is scale
                        if (gattCallback == null) {
                            Log.d("StartGatt", "No callback method, making one...");
                            prepareGATTCallback();
                        }

                        bluetoothGatt = device.connectGatt(context, false, gattCallback);

                        searchHandler.removeCallbacksAndMessages(null);
                        btAdapter.stopLeScan(scanCallback);
                    }
                }
            }
        };
    }

    private void prepareGATTCallback()
    {
        gattCallback = new BluetoothGattCallback()
        {
            @Override
            public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState)
            {
                super.onConnectionStateChange(gatt, status, newState);

                if (newState == BluetoothProfile.STATE_CONNECTED)
                {
                    callbackBtHandler.obtainMessage(BluetoothCommunication.BT_CONNECTION_ESTABLISHED).sendToTarget();
                    gatt.discoverServices();
                }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                {
                    callbackBtHandler.obtainMessage(BluetoothCommunication.BT_CONNECTION_LOST).sendToTarget();
                    stopSearching();
                }
            }

            @Override
            public void onServicesDiscovered(final BluetoothGatt gatt, int status)
            {
                super.onServicesDiscovered(gatt, status);

                ArrayList<BluetoothGattService> serviceList = (ArrayList) gatt.getServices();

                for (BluetoothGattService one : serviceList)
                {
                    if (one != null)
                    {
                        ArrayList<BluetoothGattCharacteristic> characteristicsList = (ArrayList) one.getCharacteristics();
                        for (BluetoothGattCharacteristic two : characteristicsList)
                        {
                            if (two.getUuid().toString().startsWith("00002a9d"))
                            {
                                // Weight param discovered
                                Log.d(gatt.getDevice().getName(), "(" + gatt.getDevice().getAddress() + ")");

                                weightCharacteristic = two;

                                // Start constant notification
                                bluetoothGatt.setCharacteristicNotification(weightCharacteristic, true);

                                ArrayList<BluetoothGattDescriptor> descriptors = (ArrayList<BluetoothGattDescriptor>) weightCharacteristic.getDescriptors();

                                if ( descriptors.size() == 1 )
                                {
                                    descriptors.get(0).setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    bluetoothGatt.writeDescriptor(descriptors.get(0));
                                }
                                else
                                {
                                    Log.e("GattCallback", "Multiple descriptors found in weight characteristic, unhandled exception!");
                                    System.exit(-1);
                                }
                            }
                        }

                    }
                }

            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic)
            {
                super.onCharacteristicChanged(gatt, characteristic);

                final byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {

                    try {
                        float weight = 0.0f;

                        final byte ctrlByte = data[0];

                        final boolean isWeightRemoved = isBitSet(ctrlByte, 7);
                        final boolean isStabilized = isBitSet(ctrlByte, 5);
                        final boolean isLBSUnit = isBitSet(ctrlByte, 0);
                        final boolean isCattyUnit = isBitSet(ctrlByte, 4);

                       /* Log.d("GattCallback", "IsWeightRemoved: " + isBitSet(ctrlByte, 7));
                        Log.d("GattCallback", "6 LSB Unknown: " + isBitSet(ctrlByte, 6));
                        Log.d("GattCallback", "IsStabilized: " + isBitSet(ctrlByte, 5));
                        Log.d("GattCallback", "IsCattyOrKg: " + isBitSet(ctrlByte, 4));
                        Log.d("GattCallback", "3 LSB Unknown: " + isBitSet(ctrlByte, 3));
                        Log.d("GattCallback", "2 LSB Unknown: " + isBitSet(ctrlByte, 2));
                        Log.d("GattCallback", "1 LSB Unknown: " + isBitSet(ctrlByte, 1));
                        Log.d("GattCallback", "IsLBS: " + isBitSet(ctrlByte, 0));*/

                        // Only if the value is stabilized and the weight is *not* removed, the date is valid
                        if (isStabilized && !isWeightRemoved) {

                            final int year = ((data[4] & 0xFF) << 8) | (data[3] & 0xFF);
                            final int month = (int) data[5];
                            final int day = (int) data[6];
                            final int hours = (int) data[7];
                            final int min = (int) data[8];
                            final int sec = (int) data[9];

                            if (isLBSUnit || isCattyUnit) {
                                weight = (float) (((data[2] & 0xFF) << 8) | (data[1] & 0xFF)) / 100.0f;
                            } else {
                                weight = (float) (((data[2] & 0xFF) << 8) | (data[1] & 0xFF)) / 200.0f;
                            }

                            String date_string = year + "/" + month + "/" + day + "/" + hours + "/" + min;
                            Date date_time = new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string);

                            ScaleData scaleBtData = new ScaleData();

                            scaleBtData.weight = weight;
                            scaleBtData.date_time = date_time;

                            callbackBtHandler.obtainMessage(BluetoothCommunication.BT_RETRIEVE_SCALE_DATA, scaleBtData).sendToTarget();
                        }
                    } catch (ParseException e) {
                        callbackBtHandler.obtainMessage(BluetoothCommunication.BT_UNEXPECTED_ERROR, "Error while decoding bluetooth date string (" + e.getMessage() + ")").sendToTarget();
                    }
                }
            }
        };
    }

    private boolean isBitSet(byte value, int bit){
        return (value & (1 << bit)) != 0;
    }
}
