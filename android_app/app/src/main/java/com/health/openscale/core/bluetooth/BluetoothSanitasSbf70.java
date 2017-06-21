/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*                2017  jflesch <jflesch@kwain.net>
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
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleData;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED;
import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_CONNECTION_LOST;
import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_UNEXPECTED_ERROR;

public class BluetoothSanitasSbf70 extends BluetoothCommunication {
    public final static String TAG = "BluetoothSanitasSbf70";

    private static final int PRIMARY_SERVICE = 0x180A;
    private static final UUID SYSTEM_ID = UUID.fromString("00002A23-0000-1000-8000-00805F9B34FB");
    private static final UUID MODEL_NUMBER_STRING =
            UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB");
    private static final UUID SERIAL_NUMBER_STRING =
            UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB");
    private static final UUID FIRMWARE_REVISION_STRING =
            UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB");
    private static final UUID HARDWARE_REVISION_STRING =
            UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB");
    private static final UUID SOFTWARE_REVISION_STRING =
            UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB");
    private static final UUID MANUFACTURER_NAME_STRING =
            UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB");
    private static final UUID IEEE_11073_20601_REGULATORY_CERTIFICATION_DATA_LIST =
            UUID.fromString("00002A2A-0000-1000-8000-00805F9B34FB");
    private static final UUID PNP_ID =
            UUID.fromString("00002A50-0000-1000-8000-00805F9B34FB");

    private static final UUID DEVICE_NAME =
            UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB");
    private static final UUID APPEARANCE =
            UUID.fromString("00002A01-0000-1000-8000-00805F9B34FB");
    private static final UUID PERIPHERICAL_PRIVACY_FLAG =
            UUID.fromString("00002A02-0000-1000-8000-00805F9B34FB");
    private static final UUID RECONNECTION_ADDRESS =
            UUID.fromString("00002A03-0000-1000-8000-00805F9B34FB");
    private static final UUID PERIPHERICAL_PREFERRED_CONNECTION_PARAMETERS =
            UUID.fromString("00002A04-0000-1000-8000-00805F9B34FB");

    private static final UUID GENERIC_ATTRIBUTE =
            UUID.fromString("00001801-0000-1000-8000-00805F9B34FB");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB");
    // descriptor ; handle = 0x000f
    private static final UUID CLIENT_CHARACTERISTICS_CONFIGURATION =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final UUID CUSTOM_SERVICE_1 =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID CUSTOM_CHARACTERISTIC_1 = // read-write
            UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB");
    private static final UUID CUSTOM_CHARACTERISTIC_2 = // read-only
            UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB");
    private static final UUID CUSTOM_CHARACTERISTIC_3 = // write-only
            UUID.fromString("0000FFE3-0000-1000-8000-00805F9B34FB");
    private static final UUID CUSTOM_CHARACTERISTIC_WEIGHT = // write-only, notify ; handle=0x002e
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID CUSTOM_CHARACTERISTIC_5 = // write-only, notify
            UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB");

    private static final UUID CUSTOM_SERVICE_2 =
            UUID.fromString("F000FFCD-0451-4000-8000-000000000000"); // primary service
    private static final UUID CUSTOM_CHARACTERISTIC_IMG_IDENTIFY = // write-only, notify
            UUID.fromString("F000FFC1-0451-4000-8000-000000000000");
    private static final UUID CUSTOM_CHARACTERISTIC_IMG_BLOCK = // write-only, notify
            UUID.fromString("F000FFC2-0451-4000-8000-000000000000");

    public BluetoothSanitasSbf70(Context context) {
        super(context);
        gattCallback = new BluetoothSanitasGattCallback();
    }

    @Override
    public String deviceName() {
        return "Sanitas SBF70";
    }

    @Override
    public String defaultDeviceName() {
        return "SANITAS SBF70";
    }

    @Override
    public ArrayList<String> hwAddresses() {
        ArrayList hwAddresses = new ArrayList();
        hwAddresses.add("C4BE84");
        hwAddresses.add("209148");

        return hwAddresses;
    }

    @Override
    boolean nextInitCmd(int stateNr) {
        return false;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    boolean nextCleanUpCmd(int stateNr) {
        return false;
    }

    @Override
    public boolean initSupported() {
        return false;
    }

    @Override
    public boolean historySupported() {
        return false;
    }

    private class BluetoothSanitasGattCallback extends BluetoothGattCallback {
        /**
         * @brief used to collect the data
         */
        private ScaleData scaleBtData;

        /**
         * @brief message to send.
         * Messages are sent by writing on a specific characteristic
         */
        private Queue<byte[]> msgQueue;

        /**
         * @brief true if the next message can be sent immediately. False if another is already
         * being sent
         */
        private boolean canSend;

        /**
         * @brief true if the communication must be closed after all the message have been sent
         */
        private boolean eof;

        public BluetoothSanitasGattCallback() {
            super();
            scaleBtData = new ScaleData();
            scaleBtData.setId(-1);
            msgQueue = new LinkedList<>();
            canSend = true;
            eof = false;
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStatechange(" + status + ", " + newState + ")");
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connection established");
                setBtStatus(BT_CONNECTION_ESTABLISHED);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Connection lost");
                setBtStatus(BT_CONNECTION_LOST);
                stopSearching();
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered(" + status + ")");
            //invokeNextBluetoothCmd(gatt);
            if (status == gatt.GATT_SUCCESS) {
                init(gatt);
            }
        }

        /**
         * @brief configure the scale
         */
        private void init(final BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            BluetoothGattDescriptor descriptor;

            characteristic = gatt.getService(CUSTOM_SERVICE_1)
                    .getCharacteristic(CUSTOM_CHARACTERISTIC_WEIGHT);
            gatt.setCharacteristicNotification(characteristic, true);

            descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTICS_CONFIGURATION);
            descriptor.setValue(new byte[] {
                    (byte)0x01, (byte)0x00,
            });
            gatt.writeDescriptor(descriptor);

            msgQueue.add(new byte[] {
                    (byte)0xe6, (byte)0x01,
            });
            canSend = false;
        }

        /**
         * @brief send the next message in the queue
         */
        private void nextMessage(final BluetoothGatt gatt) {
            if (!canSend) {
                return;
            }

            byte[] msg = msgQueue.poll();
            if (msg == null) {
                if (eof) {
                    stopSearching();
                }
                return;
            }

            canSend = false;

            BluetoothGattCharacteristic characteristic;
            characteristic = gatt.getService(CUSTOM_SERVICE_1)
                    .getCharacteristic(CUSTOM_CHARACTERISTIC_WEIGHT);
            characteristic.setValue(msg);
            gatt.writeCharacteristic(characteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "onDescriptorWrite(" + descriptor + ", " + status + ")");
            canSend = true;
            nextMessage(gatt);
        }

        @Override
        public void onCharacteristicWrite (BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            Log.d(TAG, "onCharacteristicWrite(" + characteristic + ", " + status + ")");
            canSend = true;
            nextMessage(gatt);
        }

        @Override
        public void onCharacteristicRead (BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            Log.d(TAG, "onCharacteristicRead(" + characteristic + ", " + status + ")");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            final UUID uuid = characteristic.getUuid();
            final byte[] data = characteristic.getValue();

            Log.d(TAG, "onCharacteristicChanged(" + uuid + "): " + byteInHex(data));

            if (!uuid.equals(CUSTOM_CHARACTERISTIC_WEIGHT)) {
                Log.d(TAG, "Got characteristic changed from unexpected UUID ?");
            }

            if ((data[0] & 0xFF) == 0xe6 && (data[1] & 0xFF) == 0x00) {
                Log.d(TAG, "ACK");
                msgQueue.add(new byte[] {
                        (byte)0xe9, (byte)0x59, (byte)0x07, (byte)0x84, (byte)0x4c,
                });
                nextMessage(gatt);
                return;

            } else if ((data[0] & 0xFF) == 0xe7 && (data[1] & 0xFF) == 0x58) {
                Log.d(TAG, "ACK");
                msgQueue.add(new byte[] {
                        (byte)0xe7, (byte)0xf1, (byte)(data[1] & 0xFF),
                        (byte)(data[2] & 0xFF), (byte)(data[3] & 0xFF),
                });
                nextMessage(gatt);

                // weight
                if ((data[2] & 0xFF) != 0x00) {
                    // temporary value;
                    return;
                }
                // stabilized value
                // little endian
                float weight = ((float)(
                        ((int)(data[3] & 0xFF) << 8) + ((int)(data[4] & 0xFF))
                )) * 50.0f / 1000.0f; // unit is 50g
                Log.i(TAG, "Got weight: " + weight);
                scaleBtData.setWeight(weight);
                return;

            } else if ((data[0] & 0xFF) == 0xe7 && (data[1] & 0xFF) == 0x59) {
                Log.d(TAG, "ACK Extra data " + ((int)data[3]));
                msgQueue.add(new byte[] {
                        (byte)0xe7, (byte)0xf1,
                        (byte)(data[1] & 0xFF), (byte)(data[2] & 0xFF),
                        (byte)(data[3] & 0xFF),
                });

                if ((data[2] & 0xFF) == 0x03 && (data[3] & 0xFF) == 0x02) {
                    // big endian
                    float fat = ((float)(
                            ((int)(data[12] & 0xFF) << 8) + ((int)(data[13] & 0xFF))
                    )) / 10.0f; // unit is 0.1kg
                    Log.i(TAG, "Got fat: " + fat + "%");
                    scaleBtData.setFat(fat);
                }

                if ((data[2] & 0xFF) == 0x03 && (data[3] & 0xFF) == 0x03) {
                    // little endian
                    float water = ((float)(
                            ((int)(data[5] & 0xFF) << 8) + ((int)(data[4] & 0xFF))
                    )) / 10.0f; // unit is 0.1kg
                    Log.i(TAG, "Got water: " + water + "%");
                    scaleBtData.setWater(water);

                    // little endian
                    float muscle = ((float)(
                            ((int)(data[7] & 0xFF) << 8) + ((int)(data[6] & 0xFF))
                    )) / 10.0f; // unit is 0.1kg
                    Log.i(TAG, "Got muscle: " + muscle + "%");
                    scaleBtData.setMuscle(muscle);

                    addScaleData(scaleBtData);

                    Log.d(TAG, "ACK Extra data (end)");
                    msgQueue.add(new byte[] {
                            (byte)0xe7, (byte)0x43, (byte)0x0, (byte)0x0, (byte)0x0,
                            (byte)0x0, (byte)0x0, (byte)0x0, (byte)0x0,
                            (byte)0x65
                    });

                    scaleBtData = new ScaleData();
                    scaleBtData.setId(-1);
                }

                nextMessage(gatt);
                return;

            } else if ((data[0] & 0xFF) == 0xe7 && (data[1] & 0xFF) == 0xf0) {
                Log.d(TAG, "ACK");
                msgQueue.add(new byte[]{
                        (byte) 0xea, (byte) 0x02,
                });
                nextMessage(gatt);
                eof = true;
                return;

            } else {
                Log.w(TAG, "Unidentified notification !");

                setBtStatus(BT_UNEXPECTED_ERROR, "Error while decoding bluetooth value");
                return;
            }
        }
    }
}
