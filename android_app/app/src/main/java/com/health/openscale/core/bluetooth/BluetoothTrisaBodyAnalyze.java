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
    private static final UUID APPEND_MEASUREMENT_CHARACTERISTIC_UUID =
            UUID.fromString("00008a22-0000-1000-8000-00805f9b34fb");
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
    private final long TIMESTAMP_OFFSET_SECONDS = 1262304000L;

    // TODO: don't hardcode this.
    //private byte[] PASSWORD = new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};

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
                setIndicationOn(
                        WEIGHT_SCALE_SERVICE_UUID,
                        MEASUREMENT_CHARACTERISTIC_UUID,
                        CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);
                return true;
            case 1:
                setIndicationOn(
                        WEIGHT_SCALE_SERVICE_UUID,
                        UPLOAD_COMMAND_CHARACTERISTIC_UUID,
                        CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR_UUID);
                return true;

            default:
                return false;
        }
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        Timber.i("nextBluetoothCmd(%d)", stateNr);
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        Timber.i("nextCleanUpCmd(%d)", stateNr);
        switch (stateNr) {
            case 0:
                writeCommand(disconnectCommand());
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        UUID characteristicUud = gattCharacteristic.getUuid();
        byte[] value = gattCharacteristic.getValue();
        Timber.i("onBluetoothdataChange() characteristic=%s value=%s", characteristicUud, byteInHex(value));
        byte commandByte = value.length > 0 ? value[0] : 0;
        if (UPLOAD_COMMAND_CHARACTERISTIC_UUID.equals(characteristicUud)) {
            switch (commandByte) {
                case UPLOAD_PASSWORD:
                    // TODO: support pairing, then store this somewhere.
                    break;
                case UPLOAD_CHALLENGE:
                    if (value.length < 5) {
                        break;
                    }
                    byte[] authCommand = new byte[] {
                            DOWNLOAD_INFORMATION_RESULT_COMMAND,
                            (byte)(value[1] ^ PASSWORD[0]),
                            (byte)(value[2] ^ PASSWORD[1]),
                            (byte)(value[3] ^ PASSWORD[2]),
                            (byte)(value[4] ^ PASSWORD[3])};
                    writeCommand(authCommand);
                    int timestamp = (int)(System.currentTimeMillis()/1000 - TIMESTAMP_OFFSET_SECONDS);
                    byte[] setUtcCommand = new byte[]{
                            DOWNLOAD_INFORMATION_UTC_COMMAND,
                            (byte)(timestamp >> 0),
                            (byte)(timestamp >> 8),
                            (byte)(timestamp >> 16),
                            (byte)(timestamp >> 24),
                    };
                    writeCommand(setUtcCommand);
                    return;
            }

        } else if (MEASUREMENT_CHARACTERISTIC_UUID.equals(characteristicUud)) {
            ScaleMeasurement scaleMeasurement = parseScaleMeasurementData(value);
            if (scaleMeasurement != null) {
                addScaleData(scaleMeasurement);
                return;
            }
        }
        Timber.w("Unhandled data!");
    }

    private byte[] disconnectCommand() {
        return new byte[]{DOWNLOAD_INFORMATION_ENABLE_DISCONNECT_COMMAND};
    }

    private void writeCommand(byte[] bytes) {
        writeBytes(WEIGHT_SCALE_SERVICE_UUID, DOWNLOAD_COMMAND_CHARACTERISTIC_UUID, bytes);
    }

    @Nullable
    private ScaleMeasurement parseScaleMeasurementData(byte[] data) {
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
    private double getBase10Float(byte[] data, int offset) {
        int mantissa = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16);
        int exponent = data[offset + 3];  // note: byte is signed.
        return mantissa * Math.pow(10, exponent);
    }
}
