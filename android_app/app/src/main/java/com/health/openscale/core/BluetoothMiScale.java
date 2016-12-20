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

/*
 * Modified version of Mnkai <https://github.com/Mnkai/OpenXiaomiScale> original BTUtils.java source code
 * Copyright (C) Mnkai Apache 2.0 license <http://www.apache.org/licenses/LICENSE-2.0>
 */

package com.health.openscale.core;

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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

public class BluetoothMiScale extends BluetoothCommunication {

    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter.LeScanCallback scanCallback;

    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC = UUID.fromString("00002a2f-0000-3512-2118-0009af100700");
    private final UUID WEIGHT_MEASUREMENT_TIME_CHARACTERISTIC = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Handler searchHandler;
    private Context context;
    private String btDeviceName;
    private int nextState;
    private boolean restartDiscovery;;

    public BluetoothMiScale(Context con) {
        searchHandler = new Handler();
        context = con;
        scanCallback = null;
    }

    @Override
    void startSearching(String deviceName) {
        btDeviceName = deviceName;

        if (scanCallback == null)
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
                            Log.d("BluetoothMiScale", "Mi Scale found trying to connect...");
                            bluetoothGatt = device.connectGatt(context, false, gattCallback);

                            searchHandler.removeCallbacksAndMessages(null);
                            btAdapter.stopLeScan(scanCallback);
                        }
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

    private BluetoothGattCallback gattCallback= new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BluetoothMiScale", "Connection established");
                callbackBtHandler.obtainMessage(BluetoothCommunication.BT_CONNECTION_ESTABLISHED).sendToTarget();
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BluetoothMiScale", "Connection lost");
                callbackBtHandler.obtainMessage(BluetoothCommunication.BT_CONNECTION_LOST).sendToTarget();
                stopSearching();
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            restartDiscovery = false;
            nextState = 0;

            invokeNextBluetoothCmd(gatt);
        }

        private void invokeNextBluetoothCmd(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            BluetoothGattDescriptor descriptor;

            Log.d("BluetoothMiScale", "Bluetooth Cmd State: " + nextState);

            switch (nextState) {
                case 0:
                    // read device time
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_TIME_CHARACTERISTIC);

                    gatt.readCharacteristic(characteristic);
                    break;
                case 1:
                    // set notification on for weight measurement
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_CHARACTERISTIC);

                    gatt.setCharacteristicNotification(characteristic, true);

                    descriptor = characteristic.getDescriptor(WEIGHT_MEASUREMENT_CONFIG);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    break;
                case 2:
                    // set notification on for weight measurement history
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);

                    gatt.setCharacteristicNotification(characteristic, true);

                    descriptor = characteristic.getDescriptor(WEIGHT_MEASUREMENT_CONFIG);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    break;
                case 3:
                    // Invoke receiving history data
                    characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);

                    characteristic.setValue(new byte[]{0x02});
                    gatt.writeCharacteristic(characteristic);
                    break;
                case 4:
                    // Stop receiving history data
                    /*characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                            .getCharacteristic(WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC);

                    characteristic.setValue(new byte[]{0x03});
                    gatt.writeCharacteristic(characteristic);*/
                    break;
                case 5:
                    // Stop state machine
                    break;
                default:
                    Log.e("BluetoothMiScale", "Error invalid Bluetooth State");
                    break;
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            nextState++;
            invokeNextBluetoothCmd(gatt);
        }

        @Override
        public void onCharacteristicWrite (BluetoothGatt gatt,
                                    BluetoothGattCharacteristic characteristic,
                                    int status) {
            if (restartDiscovery) {
                stopSearching();
                startSearching(btDeviceName);
            }

            nextState++;
            invokeNextBluetoothCmd(gatt);
        }

        @Override
        public void onCharacteristicRead (BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            byte[] data = characteristic.getValue();

            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            int scaleYear = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);

            if (currentYear != scaleYear) {
                Log.d("BluetoothMiScale", "Current year and scale year is different");
                setCurrentTimeOnDevice(gatt);
            } else {
                nextState++;
                invokeNextBluetoothCmd(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            final byte[] data = characteristic.getValue();

            Log.d("BluetoothMiScale", "Char changed");

            printByteInHex(data);

            if (data != null && data.length > 0) {
                if (data.length == 20) {
                    final byte[] firstWeight = Arrays.copyOfRange(data, 0, 10);
                    final byte[] secondWeight = Arrays.copyOfRange(data, 10, 20);
                    parseBytes(firstWeight);
                    parseBytes(secondWeight);
                }

                if (data.length == 10) {
                    parseBytes(data);
                }

            }
        }

        private void setCurrentTimeOnDevice(BluetoothGatt gatt) {
            // set current time
            BluetoothGattCharacteristic characteristic = gatt.getService(WEIGHT_MEASUREMENT_SERVICE)
                    .getCharacteristic(WEIGHT_MEASUREMENT_TIME_CHARACTERISTIC);

            Log.d("BluetoothMiScale", "Set current time on Mi scale");

            Calendar currentDateTime = Calendar.getInstance();
            int year = currentDateTime.get(Calendar.YEAR);
            byte month = (byte)(currentDateTime.get(Calendar.MONTH)+1);
            byte day = (byte)currentDateTime.get(Calendar.DAY_OF_MONTH);
            byte hour = (byte)currentDateTime.get(Calendar.HOUR_OF_DAY);
            byte min = (byte)currentDateTime.get(Calendar.MINUTE);
            byte sec = (byte)currentDateTime.get(Calendar.SECOND);

            byte[] dateTimeByte = {(byte)(year), (byte)(year >> 8), month, day, hour, min, sec, 0x03, 0x00, 0x00};

            restartDiscovery = true;

            characteristic.setValue(dateTimeByte);
            gatt.writeCharacteristic(characteristic);
        }

        private void parseBytes(byte[] weightBytes) {
            try {
                float weight = 0.0f;

                final byte ctrlByte = weightBytes[0];

                final boolean isWeightRemoved = isBitSet(ctrlByte, 7);
                final boolean isStabilized = isBitSet(ctrlByte, 5);
                final boolean isLBSUnit = isBitSet(ctrlByte, 0);
                final boolean isCattyUnit = isBitSet(ctrlByte, 4);

                /*Log.d("GattCallback", "IsWeightRemoved: " + isBitSet(ctrlByte, 7));
                Log.d("GattCallback", "6 LSB Unknown: " + isBitSet(ctrlByte, 6));
                Log.d("GattCallback", "IsStabilized: " + isBitSet(ctrlByte, 5));
                Log.d("GattCallback", "IsCattyOrKg: " + isBitSet(ctrlByte, 4));
                Log.d("GattCallback", "3 LSB Unknown: " + isBitSet(ctrlByte, 3));
                Log.d("GattCallback", "2 LSB Unknown: " + isBitSet(ctrlByte, 2));
                Log.d("GattCallback", "1 LSB Unknown: " + isBitSet(ctrlByte, 1));
                Log.d("GattCallback", "IsLBS: " + isBitSet(ctrlByte, 0));*/

                // Only if the value is stabilized and the weight is *not* removed, the date is valid
                if (isStabilized && !isWeightRemoved) {

                    final int year = ((weightBytes[4] & 0xFF) << 8) | (weightBytes[3] & 0xFF);
                    final int month = (int) weightBytes[5];
                    final int day = (int) weightBytes[6];
                    final int hours = (int) weightBytes[7];
                    final int min = (int) weightBytes[8];
                    final int sec = (int) weightBytes[9];

                    if (isLBSUnit || isCattyUnit) {
                        weight = (float) (((weightBytes[2] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 100.0f;
                    } else {
                        weight = (float) (((weightBytes[2] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 200.0f;
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
        private boolean isBitSet(byte value, int bit) {
            return (value & (1 << bit)) != 0;
        }

        private void printByteInHex(byte[] data) {
            if (data == null) {
                Log.e("BluetoothMiScale", "Data is null");
                return;
            }

            final StringBuilder stringBuilder = new StringBuilder(data.length);
            for(byte byteChar : data) {
                stringBuilder.append(String.format("%02X ", byteChar));
            }

            Log.d("BluetoothMiScale", "Raw hex data: " + stringBuilder);
        }
    };
}
