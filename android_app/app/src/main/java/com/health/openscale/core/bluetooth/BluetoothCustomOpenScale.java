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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.UUID;

public class BluetoothCustomOpenScale extends BluetoothCommunication {
    private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Standard SerialPortService ID

    private BluetoothSocket btSocket = null;
    private BluetoothDevice btDevice = null;

    private BluetoothConnectedThread btConnectThread = null;

    public BluetoothCustomOpenScale(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Custom Open Scale";
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
    public void connect(String hwAddress) {

        if (btAdapter == null) {
            setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
            return;
        }

        btDevice = btAdapter.getRemoteDevice(hwAddress);
        try {
            // Get a BluetoothSocket to connect with the given BluetoothDevice
            btSocket = btDevice.createRfcommSocketToServiceRecord(uuid);
        } catch (IOException e) {
            setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Can't get a bluetooth socket");
            btDevice = null;
            return;
        }

        Thread socketThread = new Thread() {
            @Override
            public void run() {
                try {
                    if (!btSocket.isConnected()) {
                        // Connect the device through the socket. This will block
                        // until it succeeds or throws an exception
                        btSocket.connect();

                        // Bluetooth connection was successful
                        setBtStatus(BT_STATUS_CODE.BT_CONNECTION_ESTABLISHED);

                        btConnectThread = new BluetoothConnectedThread();
                        btConnectThread.start();
                    }
                } catch (IOException connectException) {
                    // Unable to connect; close the socket and get out
                    disconnect(false);
                    setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
                }
            }
        };

        socketThread.start();
    }

    @Override
    public void disconnect(boolean doCleanup) {
        if (btSocket != null) {
            if (btSocket.isConnected()) {
                try {
                    btSocket.close();
                    btSocket = null;
                } catch (IOException closeException) {
                    setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Can't close bluetooth socket");
                }
            }
        }

        if (btConnectThread != null) {
            btConnectThread.cancel();
            btConnectThread = null;
        }

        btDevice = null;
    }

    public void clearEEPROM()
    {
        sendBtData("9");
    }

    private boolean sendBtData(String data) {
        if (btSocket.isConnected()) {
            btConnectThread = new BluetoothConnectedThread();
            btConnectThread.write(data.getBytes());

            btConnectThread.cancel();
            return true;
        }

        return false;
    }

    private class BluetoothConnectedThread extends Thread {
        private InputStream btInStream;
        private OutputStream btOutStream;
        private volatile boolean isCancel;

        public BluetoothConnectedThread() {

            isCancel = false;

            // Get the input and output bluetooth streams
            try {
                btInStream = btSocket.getInputStream();
                btOutStream = btSocket.getOutputStream();
            } catch (IOException e) {
                setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Can't get bluetooth input or output stream " + e.getMessage());
            }
        }

        public void run() {
            final StringBuilder btLine = new StringBuilder();

            // Keep listening to the InputStream until an exception occurs (e.g. device partner goes offline)
            while (!isCancel) {
                try {
                    // stream read is a blocking method
                    char btChar = (char) btInStream.read();

                    btLine.append(btChar);

                    if (btLine.charAt(btLine.length() - 1) == '\n') {
                        ScaleMeasurement scaleMeasurement = parseBtString(btLine.toString());

                        if (scaleMeasurement != null) {
                            addScaleData(scaleMeasurement);
                        }

                        btLine.setLength(0);
                    }

                } catch (IOException e) {
                    cancel();
                    setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
                }
            }
        }

        private ScaleMeasurement parseBtString(String btString) throws IOException {
            ScaleMeasurement scaleBtData = new ScaleMeasurement();
            btString = btString.substring(0, btString.length() - 1); // delete newline '\n' of the string

            if (btString.charAt(0) != '$' && btString.charAt(2) != '$') {
                setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Parse error of bluetooth string. String has not a valid format");
            }

            String btMsg = btString.substring(3, btString.length()); // message string

            switch (btString.charAt(1)) {
                case 'I':
                    Log.i("OpenScale", "MCU Information: " + btMsg);
                    break;
                case 'E':
                    Log.e("OpenScale", "MCU Error: " + btMsg);
                    break;
                case 'S':
                    Log.i("OpenScale", "MCU stored data size: " + btMsg);
                    break;
                case 'D':
                    String[] csvField = btMsg.split(",");

                    try {
                        int checksum = 0;

                        checksum ^= Integer.parseInt(csvField[0]);
                        checksum ^= Integer.parseInt(csvField[1]);
                        checksum ^= Integer.parseInt(csvField[2]);
                        checksum ^= Integer.parseInt(csvField[3]);
                        checksum ^= Integer.parseInt(csvField[4]);
                        checksum ^= Integer.parseInt(csvField[5]);
                        checksum ^= (int) Float.parseFloat(csvField[6]);
                        checksum ^= (int) Float.parseFloat(csvField[7]);
                        checksum ^= (int) Float.parseFloat(csvField[8]);
                        checksum ^= (int) Float.parseFloat(csvField[9]);

                        int btChecksum = Integer.parseInt(csvField[10]);

                        if (checksum == btChecksum) {
                            scaleBtData.setId(-1);
                            scaleBtData.setUserId(Integer.parseInt(csvField[0]));
                            String date_string = csvField[1] + "/" + csvField[2] + "/" + csvField[3] + "/" + csvField[4] + "/" + csvField[5];
                            scaleBtData.setDateTime(new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string));

                            scaleBtData.setWeight(Float.parseFloat(csvField[6]));
                            scaleBtData.setFat(Float.parseFloat(csvField[7]));
                            scaleBtData.setWater(Float.parseFloat(csvField[8]));
                            scaleBtData.setMuscle(Float.parseFloat(csvField[9]));

                            return scaleBtData;
                        } else {
                            setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error calculated checksum (" + checksum + ") and received checksum (" + btChecksum + ") is different");
                        }
                    } catch (ParseException e) {
                        setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error while decoding bluetooth date string (" + e.getMessage() + ")");
                    } catch (NumberFormatException e) {
                        setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error while decoding a number of bluetooth string (" + e.getMessage() + ")");
                    }
                    break;
                default:
                    setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error unknown MCU command");
            }

            return null;
        }

        public void write(byte[] bytes) {
            try {
                btOutStream.write(bytes);
            } catch (IOException e) {
                setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error while writing to bluetooth socket " + e.getMessage());
            }
        }

        public void cancel() {
            isCancel = true;
        }
    }
}
