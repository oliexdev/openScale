package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothES26BBB extends BluetoothCommunication {

    private static final UUID WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0x1a10);

    /**
     * Notify
     */
    private static final UUID NOTIFY_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0x2a10);
    /**
     * Write
     */
    private static final UUID WRITE_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0x2a11);

    public BluetoothES26BBB(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        // TODO idk what to put here
        return "RENPHO ES-26BB-B";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        Timber.i("onNextStep(%d)", stepNr);

        switch (stepNr) {
            case 0:
                // set notification on for custom characteristic 1 (weight, time, and others)
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, NOTIFY_MEASUREMENT_CHARACTERISTIC);
                break;
            case 1:
                // TODO investigate what these mean
                byte[] ffe3magicBytes = new byte[]{(byte) 0x55, (byte) 0xaa, (byte) 0x90, (byte) 0x00, (byte) 0x04, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x94};
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WRITE_MEASUREMENT_CHARACTERISTIC, ffe3magicBytes);
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (characteristic.equals(NOTIFY_MEASUREMENT_CHARACTERISTIC)) {
            parseMeasurementPacket(value);
        }
    }

    /**
     * Parse a measurement sent by the scale, through characteristic NOTIFY_MEASUREMENT_CHARACTERISTIC.
     *
     * @param value The data payload (in bytes)
     */
    private void parseMeasurementPacket(byte[] value) {
        Timber.d("Received measurement packet: %s", byteInHex(value));

        if (!isChecksumValid(value)) {
            Timber.w("Checksum of packet did not match. Ignoring measurement. Packet: %s", byteInHex(value));
            return;
        }

        // All packets seem to start with this
        if (value[0] != (byte) 0x55 || value[1] != (byte) 0xAA) {
            // Warn us if they don't
            Timber.w("Unknown packet structure: %s", byteInHex(value));
            return;
        }

        switch (value[2]) {
            case 0x14:
                // TODO not sure what more options are available
                Timber.d("Parsing measurement");

                if (value[5] != 0x01) {
                    // This byte indicates whether the measurement is final or not
                    // Discard if it isn't
                    Timber.d("Discarded measurement since it is not final");
                    return;
                }

                Timber.d("Saving measurement");
                // Weight (in kg) is stored as big-endian in bytes 8 and 9
                int weightKg = (value[8] << 8) | value[9];
                // TODO get other stuff

                saveMeasurement(weightKg);

                break;
            case 0x11:
                // TODO this seems to be sent at the start and at the end of the measurement (?)
                break;
            case 0x15:
                // TODO this is send near the start of the measurements
                break;
        }
    }

    /**
     * The last byte of the payload is a checksum.
     * It is calculated by summing all the other bytes and AND'ing it with 255 (that is, truncate to byte).
     *
     * @param data The payload to check, where the last byte is the checksum
     * @return True if the checksum matches, false otherwise
     */
    private boolean isChecksumValid(byte[] data) {
        if (data.length == 0) {
            Timber.d("Could not validate checksum because payload is empty");
            return false;
        }

        byte checksum = data[data.length - 1];
        byte sum = 0;
        for (int i = 0; i < data.length - 1; ++i) {
            sum += data[i];
        }
        Timber.d("Comparing checksum (%x == %x)", sum, checksum);
        return sum == checksum;
    }

    /**
     * Save a measurement from the scale to openScale.
     *
     * @param weightKg The weight, in kilograms, multiplied by 100 (that is, as an integer)
     */
    private void saveMeasurement(int weightKg) {
        // TODO add more measurements

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight((float) weightKg / 100);

        addScaleMeasurement(btScaleMeasurement);
    }
}
