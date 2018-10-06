/*  Copyright (C) 2018  Maks Verver <maks@verver.ch>
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
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.support.annotation.Nullable;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Driver for Trisa Body Analyze 4.0.
 *
 * @see <a href="https://github.com/maksverver/trisa-body-analyze">Protocol details</a>
 */
public class BluetoothTrisaBodyAnalyze extends BluetoothCommunication {

    // GATT service UUID
    private static final UUID WEIGHT_SCALE_SERVICE_UUID =
            UUID.fromString("00007802-0000-1000-8000-00805f9b34fb");

    // GATT descriptor.
    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // GATT service characteristics.
    private static final UUID MEASUREMENT_CHARACTERISTIC_UUID =
            UUID.fromString("00008a21-0000-1000-8000-00805f9b34fb");
    private static final UUID DOWNLOAD_COMMAND_CHARACTERISTIC_UUID =
            UUID.fromString("00008a81-0000-1000-8000-00805f9b34fb");
    private static final UUID UPLOAD_COMMAND_CHARACTERISTIC_UUID =
            UUID.fromString("00008a82-0000-1000-8000-00805f9b34fb");

    // Commands sent from device to host.
    private static final byte UPLOAD_PASSWORD = (byte) 0xa0;
    private static final byte UPLOAD_CHALLENGE = (byte) 0xa1;

    // Commands sent from host to device.
    private static final byte DOWNLOAD_INFORMATION_UTC_COMMAND = 0x02;
    private static final byte DOWNLOAD_INFORMATION_RESULT_COMMAND = 0x20;
    private static final byte DOWNLOAD_INFORMATION_BROADCAST_ID_COMMAND = 0x21;
    private static final byte DOWNLOAD_INFORMATION_ENABLE_DISCONNECT_COMMAND = 0x22;

    // Timestamp of 2010-01-01 00:00:00 UTC (or local time?)
    private static final long TIMESTAMP_OFFSET_SECONDS = 1262304000L;

    /**
     * Broadcast id, which the scale will include in its Bluetooth alias. This must be set to some
     * value to complete the pairing process (though the actual value doesn't seem to matter).
     */
    private static final int BROADCAST_ID = 0;

    /** Hardware address (i.e., Bluetooth mac) of the connected device. */
    @Nullable
    private String hwAddress;

    /**
     * Device password as a 32-bit integer, or {@code null} if the device password is unknown.
     *
     * <p>TODO: store this is in a database.</p>
     */
    @Nullable
    private static Integer password;

    /**
     * Indicates whether we are pairing. If this is {@code true} then we have written the
     * set-broadcast-id command, and should disconnect after the write succeeds.
     */
    private boolean pairing = false;

    public BluetoothTrisaBodyAnalyze(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Trisa Body Analyze 4.0";
    }

    @Override
    public void connect(String hwAddress) {
        Timber.i("connect(\"%s\")", hwAddress);
        this.hwAddress = hwAddress;
        super.connect(hwAddress);
    }

    @Override
    public void disconnect(boolean doCleanup) {
        Timber.i("disconnect(/*doCleanup=*/%s)", doCleanup);
        super.disconnect(doCleanup);
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        Timber.i("nextInitCmd(%d)", stateNr);
        switch (stateNr) {
            case 0:
                // Register for notifications of the measurement characteristic.
                setIndicationOn(
                        WEIGHT_SCALE_SERVICE_UUID,
                        MEASUREMENT_CHARACTERISTIC_UUID,
                        CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);
                return true;  // more commands follow
            case 1:
                // Register for notifications of the command upload characteristic.
                //
                // This is the last init command, which causes a switch to the main state machine
                // immediately after. This is important because we should be in the main state
                // to handle pairing correctly.
                setIndicationOn(
                        WEIGHT_SCALE_SERVICE_UUID,
                        UPLOAD_COMMAND_CHARACTERISTIC_UUID,
                        CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);
                // falls through
            default:
                return false;  // no more commands
        }
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        Timber.i("nextBluetoothCmd(%d)", stateNr);
        switch (stateNr) {
            case 0:
            default:
                return false;  // no more commands

            case 1:
                // This state is triggered by the write in onPasswordReceived()
                if (pairing) {
                    pairing = false;
                    disconnect(true);
                }
                return false;  // no more commands;
        }
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        Timber.i("nextCleanUpCmd(%d)", stateNr);
        switch (stateNr) {
            case 0:
                writeCommand(DOWNLOAD_INFORMATION_ENABLE_DISCONNECT_COMMAND);
                // falls through
            default:
                return false;  // no more commands
        }
    }

    @Override
    protected void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        UUID characteristicUud = gattCharacteristic.getUuid();
        byte[] value = gattCharacteristic.getValue();
        Timber.i("onBluetoothdataChange() characteristic=%s value=%s", characteristicUud, byteInHex(value));
        if (UPLOAD_COMMAND_CHARACTERISTIC_UUID.equals(characteristicUud)) {
            if (value.length == 0) {
                Timber.e("Missing command byte!");
                return;
            }
            byte command = value[0];
            switch (command) {
                case UPLOAD_PASSWORD:
                    onPasswordReceived(value);
                    break;
                case UPLOAD_CHALLENGE:
                    onChallengeReceived(value);
                    break;
                default:
                    Timber.e("Unknown command byte received: %d", command);
            }
            return;
        }
        if (MEASUREMENT_CHARACTERISTIC_UUID.equals(characteristicUud)) {
            onScaleMeasurumentReceived(value);
            return;
        }
        Timber.e("Unknown characteristic changed: %s", characteristicUud);
    }

    private void onPasswordReceived(byte[] data) {
        if (data.length < 5) {
            Timber.e("Password data too short");
            return;
        }
        int newPassword = getInt32(data, 1);
        if (password != null && password != newPassword) {
            Timber.w("Replacing old password '%08x'", password);
        }
        Timber.i("Storing password '%08x'", newPassword);
        password = newPassword;

        sendMessage(R.string.trisa_scale_pairing_succeeded, null);

        // To complete the pairing process, we must set the scale's broadcast id, and then
        // disconnect. The writeCommand() call below will trigger the next state machine transition,
        // which will disconnect when `pairing == true`.
        pairing = true;
        writeCommand(DOWNLOAD_INFORMATION_BROADCAST_ID_COMMAND, BROADCAST_ID);
    }

    private void onChallengeReceived(byte[] data) {
        if (data.length < 5) {
            Timber.e("Challenge data too short");
            return;
        }
        if (password == null) {
            Timber.w("Received challenge, but password is unknown.");
            sendMessage(R.string.trisa_scale_not_paired, null);
            disconnect(true);
            return;
        }
        int challenge = getInt32(data, 1);
        int response = challenge ^ password;
        writeCommand(DOWNLOAD_INFORMATION_RESULT_COMMAND, response);
        int timestamp = (int)(System.currentTimeMillis()/1000 - TIMESTAMP_OFFSET_SECONDS);
        writeCommand(DOWNLOAD_INFORMATION_UTC_COMMAND, timestamp);
    }

    private void onScaleMeasurumentReceived(byte[] data) {
        ScaleMeasurement scaleMeasurement = parseScaleMeasurementData(data);
        if (scaleMeasurement == null) {
            Timber.e("Failed to parse scale measure measurement data: %s", byteInHex(data));
            return;
        }
        addScaleData(scaleMeasurement);
    }

    /** Write a single command byte, without any arguments. */
    private void writeCommand(byte commandByte) {
        writeCommandBytes(new byte[]{commandByte});
    }

    /**
     * Write a command with a 32-bit integer argument.
     *
     * <p>The command string consists of the command byte followed by 4 bytes: the argument
     * encoded in little-endian byte order.</p>
     */
    private void writeCommand(byte commandByte, int argument) {
        writeCommandBytes(new byte[]{
                commandByte,
                (byte) (argument >> 0),
                (byte) (argument >> 8),
                (byte) (argument >> 16),
                (byte) (argument >> 24),
        });
    }

    private void writeCommandBytes(byte[] bytes) {
        Timber.d("writeCommand bytes=%s", byteInHex(bytes));
        writeBytes(WEIGHT_SCALE_SERVICE_UUID, DOWNLOAD_COMMAND_CHARACTERISTIC_UUID, bytes);
    }

    @Nullable
    private static ScaleMeasurement parseScaleMeasurementData(byte[] data) {
        // Byte 0 contains info.
        // Byte 1-4 contains weight.
        // Byte 5-8 contains timestamp, if bit 0 in info byte is set.
        // Check that we have at least weight & timestamp, which is the minimum information that
        // ScaleMeasurement needs.
        if (data.length < 9 || (data[0] & 1) == 0) {
            return null;
        }

        double weight = getBase10Float(data, 1);
        long timestamp_seconds = TIMESTAMP_OFFSET_SECONDS + (long)getInt32(data, 5);

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setDateTime(new Date(MILLISECONDS.convert(timestamp_seconds, SECONDS)));
        measurement.setWeight((float)weight);
        // TODO: calculate body composition (if possible) and set those fields too
        return measurement;
    }

    /** Converts 4 little-endian bytes to a 32-bit integer. */
    private static int getInt32(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }

    /** Converts 4 bytes to a floating point number.
     *
     * <p>The first three little-endian bytes form the 24-bit mantissa. The last byte contains the
     * signed exponent, applied in base 10.
     */
    private static double getBase10Float(byte[] data, int offset) {
        int mantissa = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16);
        int exponent = data[offset + 3];  // note: byte is signed.
        return mantissa * Math.pow(10, exponent);
    }
}
