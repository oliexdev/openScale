/*  Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*   Copyright (C) 2018  John Lines <john+openscale@paladyn.org>
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

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Date;
import java.util.Arrays;

import timber.log.Timber;

public class BluetoothIhealthHS3 extends BluetoothCommunication {
    private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Standard SerialPortService ID

    private BluetoothSocket btSocket = null;
    private BluetoothDevice btDevice = null;

    private BluetoothConnectedThread btConnectThread = null;

    private byte[] lastWeight = new byte[2];
    private Date lastWeighed = new Date();
    private final long maxTimeDiff = 60000;   // maximum time interval we will consider two identical
                                             // weight readings to be the same and hence ignored - 60 seconds in milliseconds

    public BluetoothIhealthHS3(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "iHealth HS33FA4A";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        Timber.w("ihealthHS3 - nextInitCmd - returning false");
        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        Timber.w("ihealthHS3 - nextBluetoothCmd - returning false");
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        Timber.w("ihealthHS3 - nextCleanUpCmd - returning false");
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

        Timber.w("HS3 - disconnect");
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


    private boolean sendBtData(String data) {
        Timber.w("ihealthHS3 - sendBtData %s", data);
        if (btSocket.isConnected()) {
            btConnectThread = new BluetoothConnectedThread();
            btConnectThread.write(data.getBytes());

            btConnectThread.cancel();

            return true;
        }
        Timber.w("ihealthHS3 - sendBtData - socket is not connected");
        return false;
    }

    private class BluetoothConnectedThread extends Thread {
        private InputStream btInStream;
        private OutputStream btOutStream;
        private volatile boolean isCancel;

        public BluetoothConnectedThread() {
//            Timber.w("ihealthHS3 - BluetoothConnectedThread");
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
 
            byte btByte;
            byte[] weightBytes = new byte[2];
//            Timber.w("ihealthHS3 - run");
            // Keep listening to the InputStream until an exception occurs (e.g. device partner goes offline)
            while (!isCancel) {
                try {
                    // stream read is a blocking method

                    btByte = (byte) btInStream.read();
//                    Timber.w("iheathHS3 - seen a byte "+String.format("%02X",btByte));

                   if ( btByte == (byte) 0xA0 ) {
                     btByte = (byte) btInStream.read();
                     if ( btByte == (byte) 0x09 ) {
                        btByte = (byte) btInStream.read();
                        if ( btByte == (byte) 0xa6 ) {
                           btByte = (byte) btInStream.read();
                           if ( btByte == (byte) 0x28 ) {
//                              Timber.w("seen 0xa009a628 - Weight packet");
                              // deal with a weight packet - read 5 bytes we dont care about
                                 btByte = (byte) btInStream.read();
                                 btByte = (byte) btInStream.read();
                                 btByte = (byte) btInStream.read();
                                 btByte = (byte) btInStream.read();
                                 btByte = (byte) btInStream.read();
// and the weight - which should follow
                                 weightBytes[0] = (byte) btInStream.read();
                                 weightBytes[1] = (byte) btInStream.read();

                                 ScaleMeasurement scaleMeasurement = parseWeightArray(weightBytes);

                                 if (scaleMeasurement != null) {
                                       addScaleData(scaleMeasurement);
                                }
                                 
                              }
                              else if (btByte == (byte) 0x33 ) {
                                 Timber.w("seen 0xa009a633 - time packet");
                                 // deal with a time packet, if needed
                                 } else {
                                 Timber.w("iHealthHS3 - seen byte after control leader %02X", btByte);
                                 }
                                 }
                             }
                        }



                } catch (IOException e) {
                    cancel();
                    setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
                }
            }
        }

        private ScaleMeasurement parseWeightArray(byte[] weightBytes ) throws IOException {
            ScaleMeasurement scaleBtData = new ScaleMeasurement();

//            Timber.w("iHealthHS3 - ScaleMeasurement "+String.format("%02X",weightBytes[0])+String.format("%02X",weightBytes[1]));

            String ws = String.format("%02X",weightBytes[0])+String.format("%02X",weightBytes[1]);
            StringBuilder ws1 = new StringBuilder (ws);
            ws1.insert(ws.length()-1,".");
    

            float weight = Float.parseFloat(ws1.toString());
//            Timber.w("iHealthHS3 - ScaleMeasurement "+String.format("%f",weight));

            Date now = new Date();

// If the weight is the same as the lastWeight, and the time since the last reading is less than maxTimeDiff then return null
            if (Arrays.equals(weightBytes,lastWeight) && (now.getTime() - lastWeighed.getTime() < maxTimeDiff)) {   
//                Timber.w("iHealthHS3 - parseWeightArray returning null");
                return null;
                }     
            

            scaleBtData.setDateTime(now);
            scaleBtData.setWeight(weight);
            lastWeighed = now;
            System.arraycopy(weightBytes,0,lastWeight,0,lastWeight.length);
            return scaleBtData;

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
