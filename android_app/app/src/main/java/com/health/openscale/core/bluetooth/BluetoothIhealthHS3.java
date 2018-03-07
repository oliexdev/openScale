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
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.UUID;
import java.util.Date;
import java.util.Calendar;

public class BluetoothIhealthHS3 extends BluetoothCommunication {
    private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Standard SerialPortService ID

    private BluetoothSocket btSocket = null;
    private BluetoothDevice btDevice = null;

    private BluetoothConnectedThread btConnectThread = null;

    private byte[] lastWeight = new byte[2];
    private Date lastWeighed = 0;
    private final long maxTimeDiff = 2000;   // maximum time interval we will consider two identical
                                             // weight readings to be the same and hence ignored - 2 seconds in milliseconds

    public BluetoothIhealthHS3(Context context) {
        super(context);
    }

    @Override
    public String deviceName() {
        Log.w("openscale","ihealthHS3 - DeviceName - returning iHealth HS33FA4A");
        return "iHealth HS33FA4A";
    }

    @Override
    public String defaultDeviceName() {
        Log.w("openscale","ihealthHS3 - defaultDeviceName - returning iHealth HS3");
        return "iHealth HS3";
    }

    @Override
    public boolean checkDeviceName(String btDeviceName) {
        Log.w("openscale","ihealthHS3 - checkDeviceName "+btDeviceName);
        if (btDeviceName.startsWith("iHealth HS3")) {
            return true;
        }

        return false;
    }


    @Override
    boolean nextInitCmd(int stateNr) {
        Log.w("openscale","ihealthHS3 - nextInitCmd - returning false");
        return false;
    }

    @Override
    boolean nextBluetoothCmd(int stateNr) {
        Log.w("openscale","ihealthHS3 - nextBluetoothCmd - returning false");
        return false;
    }

    @Override
    boolean nextCleanUpCmd(int stateNr) {
        Log.w("openscale","ihealthHS3 - nextCleanUpCmd - returning false");
        return false;
    }

    public boolean isBLE() {
        Log.w("openscale","iHealth HS3 - isBLE - returning false");
        return false;
    }


        @Override
    public void startSearching(String deviceName) {

       Log.w("openscale","iHealth HS3 - startSearching "+deviceName);

        if (btAdapter == null) {
            setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
            return;
        }

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();

//       Log.w("openscale","about to start searching paired devices");

        for (BluetoothDevice device : pairedDevices) {
            // check if we can found bluetooth device name in the pairing list
            if (device != null ) { Log.w("openscale","Looking at device "+device.getName()); } ;
                
            if (device != null && device.getName().equals(deviceName)) {
                btDevice = device;

                try {
                    // Get a BluetoothSocket to connect with the given BluetoothDevice
                    btSocket = btDevice.createRfcommSocketToServiceRecord(uuid);
                } catch (IOException e) {
                    setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Can't get a bluetooth socket");
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
                            stopSearching();
                            setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
                        }
                    }
                };

                socketThread.start();
                return;
            }
        }

        setBtStatus(BT_STATUS_CODE.BT_NO_DEVICE_FOUND);
    }

    @Override
    public void stopSearching() {

        Log.w("openscale","HS3 - stopSearching");
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
    }


    private boolean sendBtData(String data) {
        Log.w("openscale","ihealthHS3 - sendBtData"+data);
        if (btSocket.isConnected()) {
            btConnectThread = new BluetoothConnectedThread();
            btConnectThread.write(data.getBytes());

            btConnectThread.cancel();

            return true;
        }
        Log.w("openscale","ihealthHS3 - sendBtData - socket is not connected");
        return false;
    }

    private class BluetoothConnectedThread extends Thread {
        private InputStream btInStream;
        private OutputStream btOutStream;
        private volatile boolean isCancel;

        public BluetoothConnectedThread() {
            Log.w("openscale","ihealthHS3 - BluetoothConnectedThread");
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
            Log.w("openscale","ihealthHS3 - run");
            // Keep listening to the InputStream until an exception occurs (e.g. device partner goes offline)
            while (!isCancel) {
                try {
                    // stream read is a blocking method

                    btByte = (byte) btInStream.read();
                    Log.w("openscale","iheathHS3 - seen a byte "+String.format("%02X",btByte));

                   if ( btByte == (byte) 0xA0 ) {
                     btByte = (byte) btInStream.read();
                     if ( btByte == (byte) 0x09 ) {
                        btByte = (byte) btInStream.read();
                        if ( btByte == (byte) 0xa6 ) {
                           btByte = (byte) btInStream.read();
                           if ( btByte == (byte) 0x28 ) {
                              Log.w("openscale","seen 0xa009a628 - Weight packet");
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
                                 Log.w("openscale","seen 0xa009a633 - time packet");
                                 // deal with a time packet, if needed
                                 } else {
                                 Log.w("openscale","seen some other data packet");
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

            Log.w("openscale","iHealthHS3 - ScaleMeasurement "+String.format("%02X",weightBytes[0])+String.format("%02X",weightBytes[1]));

            String ws = String.format("%02X",weightBytes[0])+String.format("%02X",weightBytes[1]);
            StringBuilder ws1 = new StringBuilder (ws);
            ws1.insert(ws.length()-1,".");
    

            float weight = Float.parseFloat(ws1.toString());
            Log.w("openscale","iHealthHS3 - ScaleMeasurement "+String.format("%f",weight));

            Date now = new Date();

// If the weight is the same as the lastWeight, and the time since the last reading is less than maxTimeDiff then return null
            if (Arrays.equals(weightBytes,lastWeight) && (now.getTime() - lastWeighted.getTime() < maxTimeDiff)) {   
                Log.w("openscale","iHealthHS3 - parseWeightArray returning null");
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
