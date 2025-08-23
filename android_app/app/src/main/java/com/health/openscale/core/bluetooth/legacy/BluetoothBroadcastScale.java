/* Copyright (C) 2024  olie.xdev <olie.xdev@googlemail.com>
*                2024  Duncan Overbruck <mail@duncano.de>
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

package com.health.openscale.core.bluetooth.legacy;

import android.Manifest;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.utils.LogManager;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import java.util.Date;


public class BluetoothBroadcastScale extends BluetoothCommunication {
    private final String TAG = "BluetoothBroadcastScale";
    private Context context;
    
    private ScaleMeasurement measurement;

    private boolean connected = false;

    private final BluetoothCentralManager central;

    public BluetoothBroadcastScale(Context context)
    {
        super(context);
        this.context = context;
        this.central = new BluetoothCentralManager(context, bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
    }

    // Callback for central
    private final BluetoothCentralManagerCallback bluetoothCentralCallback = new BluetoothCentralManagerCallback() {

        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            ScanRecord record = scanResult.getScanRecord();
            if (record == null)
                return;

            SparseArray<byte[]> manufacturerData = record.getManufacturerSpecificData();
            if (manufacturerData.size() != 1)
                return;

            int companyId = manufacturerData.keyAt(0);
            byte[] data = manufacturerData.get(companyId);
            if (data.length < 12) {
                LogManager.d(TAG, String.format("Unexpected data length, got %d, expected %d", data.length, 12));
                return;
            }

            // lower byte of the two byte companyId is the xor byte,
            // its used on the last 6 bytes of the data, the first 6 bytes
            // are just the mac address and can be ignored.
            byte xor = (byte) (companyId >> 8);
            byte[] buf = new byte[6];
            for (int i = 0; i < 6; i++) {
                buf[i] = (byte) (data[i + 6] ^ xor);
            }

            // chk is the sum of the first 5 bytes, its 5 lower bits are compared to the 5 lower
            // bites of the last byte in the packet.
            int chk = 0;
            for (int i = 0; i < 5; i++) {
                chk += buf[i];
            }
            if ((chk & 0x1F) != (buf[5] & 0x1F)) {
                LogManager.d(TAG, String.format("Checksum error, got %x, expected %x", chk & 0x1F, buf[5] & 0x1F));
                return;
            }

            if (!connected) {
                // "fake" a connection, since we've got valid data.
                setBluetoothStatus(BT_STATUS.CONNECTION_ESTABLISHED);
                connected = true;
            }

            switch (buf[4]) {
                case (byte) 0xAD:
                    int value = (((buf[3] & 0xFF) << 0) | ((buf[2] & 0xFF) << 8) |
                                 ((buf[1] & 0xFF) << 16) | ((buf[0] & 0xFF) << 24));
                    byte state = (byte)(value >> 0x1F);
                    int grams = value & 0x3FFFF;
                    LogManager.d(TAG, String.format("Got weight measurement weight=%.2f state=%d", (float)grams/1000, state));
                    if (state != 0 && measurement == null) {
                        measurement = new ScaleMeasurement();
                        measurement.setDateTime(new Date());
                        measurement.setWeight((float)grams / 1000);

                        // stop now since we don't support any further data.
                        addScaleMeasurement(measurement);
                        disconnect();
                        measurement = null;
                    }
                    break;
                case (byte) 0xA6:
                    // this is the impedance package, not yet supported.
                    break;
                default:
                    StringBuilder sb = new StringBuilder();
                    for (byte b : buf) {
                        sb.append(String.format("0x%02X ", b));
                    }
                    LogManager.d(TAG, String.format("Unsupported packet type %x, xor key %x data: %s", buf[4], xor, sb.toString()));
            }
        }
    };

    @Override
    public void connect(String macAddress) {
        // Android 12+ (minSdk=31): scanning requires BLUETOOTH_SCAN, no location needed.
        boolean canScan = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;

        if (!canScan) {
            LogManager.e(TAG, "Missing BLUETOOTH_SCAN → cannot start LE scan for broadcast data.", null);
            setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR);
            // choose a better string if you have one, this is just a visible hint:
            sendMessage(com.health.openscale.R.string.info_bluetooth_connection_error_scale_offline, 0);
            return;
        }

        try {
            LogManager.d(TAG, "Starting LE scan for broadcast scale (no location required)");
            // We scan by address; when advertising is seen, onDiscoveredPeripheral parses manufacturer data.
            central.scanForPeripheralsWithAddresses(new String[]{ macAddress });
        } catch (Exception e) {
            LogManager.e(TAG, "Failed to start LE scan: " + e.getMessage(), e);
            setBluetoothStatus(BT_STATUS.UNEXPECTED_ERROR);
        }
    }


    @Override
    public void disconnect() {
        LogManager.d(TAG, "Bluetooth disconnect");
        setBluetoothStatus(BT_STATUS.CONNECTION_DISCONNECT);
        try {
            central.stopScan();
        } catch (Exception ex) {
            LogManager.e(TAG, "Error on Bluetooth disconnecting " + ex.getMessage(), ex);
        }
        connected = false;
    }

    @Override
    public String driverName() {
        return "BroadcastScale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }

}
