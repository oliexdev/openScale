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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.UUID;

import timber.log.Timber;

import static com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib.convertJavaTimestampToDevice;
import static com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib.parseScaleMeasurementData;

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

    /**
     * Broadcast id, which the scale will include in its Bluetooth alias. This must be set to some
     * value to complete the pairing process (though the actual value doesn't seem to matter).
     */
    private static final int BROADCAST_ID = 0;

    /**
     * Prefix for {@link SharedPreferences} keys that store device passwords.
     *
     * @see #loadDevicePassword
     * @see #saveDevicePassword
     */
    private static final String SHARED_PREFERENCES_PASSWORD_KEY_PREFIX =
            "trisa_body_analyze_password_for_device_";

    /**
     * ASCII string that identifies the connected device (i.e. the hex-encoded Bluetooth MAC
     * address). Used in shared preference keys to store per-device settings.
     */
    @Nullable
    private String deviceId;

    /** Device password as a 32-bit integer, or {@code null} if the device password is unknown. */
    @Nullable
    private static Integer password;

    /**
     * Indicates whether we are pairing. If this is {@code true} then we have written the
     * set-broadcast-id command, and should disconnect after the write succeeds.
     *
     * @see #onPasswordReceived
     * @see #nextBluetoothCmd
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
        super.connect(hwAddress);
        this.deviceId = hwAddress;
        this.password = loadDevicePassword(context, hwAddress);
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
        password = Converters.fromSignedInt32Le(data, 1);
        if (deviceId == null) {
            Timber.e("Can't save password: device id not set!");
        } else {
            Timber.i("Saving password '%08x' for device id '%s'", password, deviceId);
            saveDevicePassword(context, deviceId, password);
        }

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
        int challenge = Converters.fromSignedInt32Le(data, 1);
        int response = challenge ^ password;
        writeCommand(DOWNLOAD_INFORMATION_RESULT_COMMAND, response);
        int deviceTimestamp = convertJavaTimestampToDevice(System.currentTimeMillis());
        writeCommand(DOWNLOAD_INFORMATION_UTC_COMMAND, deviceTimestamp);
    }

    private void onScaleMeasurumentReceived(byte[] data) {
        ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
        ScaleMeasurement scaleMeasurement = parseScaleMeasurementData(data, user);
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
        byte[] bytes = new byte[5];
        bytes[0] = commandByte;
        Converters.toInt32Le(bytes, 1, argument);
        writeCommandBytes(bytes);
    }

    private void writeCommandBytes(byte[] bytes) {
        Timber.d("writeCommand bytes=%s", byteInHex(bytes));
        writeBytes(WEIGHT_SCALE_SERVICE_UUID, DOWNLOAD_COMMAND_CHARACTERISTIC_UUID, bytes);
    }

    private static String getDevicePasswordKey(String deviceId) {
        return SHARED_PREFERENCES_PASSWORD_KEY_PREFIX + deviceId;
    }

    @Nullable
    private static Integer loadDevicePassword(Context context, String deviceId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String key = getDevicePasswordKey(deviceId);
        try {
            // Strictly speaking, there is a race condition between the calls to contains() and
            // getInt(), but it's not a problem because we never delete passwords.
            return prefs.contains(key) ? Integer.valueOf(prefs.getInt(key, 0)) : null;
        } catch (ClassCastException e) {
            Timber.e(e, "Password preference value is not an integer.");
            return null;
        }
    }

    private static void saveDevicePassword(Context context, String deviceId, int password) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt(getDevicePasswordKey(deviceId), password).apply();
    }
}
