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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.lib.TrisaBodyAnalyzeLib;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Date;
import java.util.UUID;

import timber.log.Timber;

/**
 * Driver for Trisa Body Analyze 4.0.
 *
 * @see <a href="https://github.com/maksverver/trisa-body-analyze">Protocol details</a>
 */
public class BluetoothTrisaBodyAnalyze extends BluetoothCommunication {

    // GATT service UUID
    private static final UUID WEIGHT_SCALE_SERVICE_UUID =
            BluetoothGattUuid.fromShortCode(0x7802);

    // GATT service characteristics.
    private static final UUID MEASUREMENT_CHARACTERISTIC_UUID =
            BluetoothGattUuid.fromShortCode(0x8a21);
    private static final UUID DOWNLOAD_COMMAND_CHARACTERISTIC_UUID =
            BluetoothGattUuid.fromShortCode(0x8a81);
    private static final UUID UPLOAD_COMMAND_CHARACTERISTIC_UUID =
            BluetoothGattUuid.fromShortCode(0x8a82);

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
     * @see #onNextStep
     */
    private boolean pairing = false;

    /**
     *  Timestamp of 2010-01-01 00:00:00 UTC (or local time?)
     */
    private static final long TIMESTAMP_OFFSET_SECONDS = 1262304000L;

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
    protected boolean onNextStep(int stepNr) {
        Timber.i("onNextStep(%d)", stepNr);
        switch (stepNr) {
            case 0:
                // Register for notifications of the measurement characteristic.
                setIndicationOn(WEIGHT_SCALE_SERVICE_UUID, MEASUREMENT_CHARACTERISTIC_UUID);
                break;  // more commands follow
            case 1:
                // Register for notifications of the command upload characteristic.
                //
                // This is the last init command, which causes a switch to the main state machine
                // immediately after. This is important because we should be in the main state
                // to handle pairing correctly.
                setIndicationOn(WEIGHT_SCALE_SERVICE_UUID, UPLOAD_COMMAND_CHARACTERISTIC_UUID);
                break;
            case 2:
                // This state is triggered by the write in onPasswordReceived()
                if (pairing) {
                    pairing = false;
                    disconnect();
                }
                break;
            case 3:
                writeCommand(DOWNLOAD_INFORMATION_ENABLE_DISCONNECT_COMMAND);
                break;
            default:
                return false;  // no more commands
        }

        return true;
    }

    @Override
    protected void onBluetoothNotify(UUID characteristic, byte[] value) {

        Timber.i("onBluetoothdataChange() characteristic=%s value=%s", characteristic, byteInHex(value));
        if (UPLOAD_COMMAND_CHARACTERISTIC_UUID.equals(characteristic)) {
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
        if (MEASUREMENT_CHARACTERISTIC_UUID.equals(characteristic)) {
            onScaleMeasurumentReceived(value);
            return;
        }
        Timber.e("Unknown characteristic changed: %s", characteristic);
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
            disconnect();
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
        ScaleMeasurement measurement = parseScaleMeasurementData(data, user);

        if (measurement == null) {
            Timber.e("Failed to parse scale measure measurement data: %s", byteInHex(data));
            return;
        }

        addScaleMeasurement(measurement);
    }

    public ScaleMeasurement parseScaleMeasurementData(byte[] data, ScaleUser user) {
        // data contains:
        //
        //   1 byte: info about presence of other fields:
        //           bit 0: timestamp
        //           bit 1: resistance1
        //           bit 2: resistance2
        //           (other bits aren't used here)
        //   4 bytes: weight
        //   4 bytes: timestamp (if info bit 0 is set)
        //   4 bytes: resistance1 (if info bit 1 is set)
        //   4 bytes: resistance2 (if info bit 2 is set)
        //   (following fields aren't used here)

        // Check that we have at least weight & timestamp, which is the minimum information that
        // ScaleMeasurement needs.
        if (data.length < 9) {
            return null;  // data is too short
        }
        byte infoByte = data[0];
        boolean hasTimestamp = (infoByte & 1) == 1;
        boolean hasResistance1 = (infoByte & 2) == 2;
        boolean hasResistance2 = (infoByte & 4) == 4;
        if (!hasTimestamp) {
            return null;
        }
        float weightKg = getBase10Float(data, 1);
        int deviceTimestamp = Converters.fromSignedInt32Le(data, 5);

        ScaleMeasurement measurement = new ScaleMeasurement();
        measurement.setDateTime(new Date(convertDeviceTimestampToJava(deviceTimestamp)));
        measurement.setWeight((float) weightKg);

        // Only resistance 2 is used; resistance 1 is 0, even if it is present.
        int resistance2Offset = 9 + (hasResistance1 ? 4 : 0);
        if (hasResistance2 && resistance2Offset + 4 <= data.length && isValidUser(user)) {
            // Calculate body composition statistics from measured weight & resistance, combined
            // with age, height and sex from the user profile. The accuracy of the resulting figures
            // is questionable, but it's better than nothing. Even if the absolute numbers aren't
            // very meaningful, it might still be useful to track changes over time.
            float resistance2 = getBase10Float(data, resistance2Offset);
            float impedance = resistance2 < 410f ? 3.0f : 0.3f * (resistance2 - 400f);

            TrisaBodyAnalyzeLib trisaBodyAnalyzeLib = new TrisaBodyAnalyzeLib(user.getGender().isMale() ? 1 : 0, user.getAge(), user.getBodyHeight());

            measurement.setFat(trisaBodyAnalyzeLib.getFat(weightKg, impedance));
            measurement.setWater(trisaBodyAnalyzeLib.getWater(weightKg, impedance));
            measurement.setMuscle(trisaBodyAnalyzeLib.getMuscle(weightKg, impedance));
            measurement.setBone(trisaBodyAnalyzeLib.getBone(weightKg, impedance));
        }

        return measurement;
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

    /** Converts 4 bytes to a floating point number, starting from  {@code offset}.
     *
     * <p>The first three little-endian bytes form the 24-bit mantissa. The last byte contains the
     * signed exponent, applied in base 10.
     *
     * @throws IndexOutOfBoundsException if {@code offset < 0} or {@code offset + 4> data.length}
     */
    public float getBase10Float(byte[] data, int offset) {
        int mantissa = Converters.fromUnsignedInt24Le(data, offset);
        int exponent = data[offset + 3];  // note: byte is signed.
        return (float)(mantissa * Math.pow(10, exponent));
    }

    public int convertJavaTimestampToDevice(long javaTimestampMillis) {
        return (int)((javaTimestampMillis + 500)/1000 - TIMESTAMP_OFFSET_SECONDS);
    }

    public long convertDeviceTimestampToJava(int deviceTimestampSeconds) {
        return 1000 * (TIMESTAMP_OFFSET_SECONDS + (long)deviceTimestampSeconds);
    }

    private boolean isValidUser(@Nullable ScaleUser user) {
        return user != null && user.getAge() > 0 && user.getBodyHeight() > 0;
    }
}
