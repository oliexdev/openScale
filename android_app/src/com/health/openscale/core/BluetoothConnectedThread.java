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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

public class BluetoothConnectedThread extends Thread {
    private BluetoothSocket btSocket;
    private InputStream btInStream;
    private OutputStream btOutStream;
    private Handler btHandler;
 
    public BluetoothConnectedThread(BluetoothSocket socket, Handler handler) {
        btSocket = socket;
        btHandler = handler;
 
        // Get the input and output bluetooth streams
        try {
        	btInStream = socket.getInputStream();
        	btOutStream = socket.getOutputStream();
        } catch (IOException e) {
        	Log.e("BluetoothConnectedThread", "Can't get bluetooth input or output stream " + e.getMessage());
        }
    }
 
    public void run() {
        final StringBuilder btLine = new StringBuilder();
        
        // Keep listening to the InputStream until an exception occurs (e.g. device partner goes offline)
        while (true) {
            try {
            	// stream read is a blocking method
            	char btChar = (char)btInStream.read();
            	
            	btLine.append(btChar);
					
				if (btLine.charAt(btLine.length()-1) == '\n'){
					btHandler.obtainMessage(BluetoothCommunication.BT_MESSAGE_READ, btLine.toString()).sendToTarget();
		                
					btLine.setLength(0);
				}

            } catch (IOException e) {
                cancel();
                btHandler.obtainMessage(BluetoothCommunication.BT_SOCKET_CLOSED).sendToTarget();
                return;
            }
        }
    }
 
    public void write(byte[] bytes) {
        try {
            btOutStream.write(bytes);
        } catch (IOException e) { }
    }
 
    public void cancel() {
        try {
            btSocket.close();
        } catch (IOException e) { 
        	Log.e("BluetoothConnectedThread", "Error while closing bluetooth socket " + e.getMessage());
        }
    }
}