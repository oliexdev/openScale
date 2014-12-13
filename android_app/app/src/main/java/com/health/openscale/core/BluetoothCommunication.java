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
import java.util.Set;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.provider.SyncStateContract.Constants;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

public class BluetoothCommunication extends Thread {
    public static final int BT_MESSAGE_READ = 0;
    public static final int BT_SOCKET_CLOSED = 1;
    public static final int BT_NO_ADAPTER = 2;
	private final UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Standard SerialPortService ID
	
    private BluetoothSocket btSocket;
    private BluetoothDevice btDevice;
    private BluetoothAdapter btAdapter;
    private Handler btHandler;
    private volatile boolean isCancel;
 
    public BluetoothCommunication(Handler handler) {
    	btHandler = handler;
    	isCancel = false;
    }
 
	void findBT(String deviceName) throws IOException {
		btAdapter = BluetoothAdapter.getDefaultAdapter();

		if (btAdapter == null) {
			btHandler.obtainMessage(BluetoothCommunication.BT_NO_ADAPTER).sendToTarget();
			return;
		}

		Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();

		for (BluetoothDevice device : pairedDevices) {
			// check if we can found bluetooth device name in the pairing list
			if (device.getName().equals(deviceName)) {
				btDevice = device;
				
	            // Get a BluetoothSocket to connect with the given BluetoothDevice
	        	btSocket = btDevice.createRfcommSocketToServiceRecord(uuid);
				return;
			}
		}

		throw new IOException("Bluetooth device not found");
	}
    
    public void run() {    	
		while (!isCancel) {
			try {
				if (!btSocket.isConnected()) {
					// Connect the device through the socket. This will block
					// until it succeeds or throws an exception
					btSocket.connect();
				}
				
				// Bluetooth connection was successful 
				Log.d("BluetoothCommunication", "Bluetooth connection successful established!");
				BluetoothConnectedThread btConnectThread = new BluetoothConnectedThread(btSocket, btHandler);
				btConnectThread.start();
				return;

			} catch (IOException connectException) {
				try {
					sleep(4000);
				} catch (InterruptedException e) {
					Log.e("BluetoothCommuncation", "Sleep error " + e.getMessage());
				}
			}
			
		}
    }
 
    public void cancel() { 	
        try {
            btSocket.close();
            isCancel = true;
        } catch (IOException e) { }
    }
}