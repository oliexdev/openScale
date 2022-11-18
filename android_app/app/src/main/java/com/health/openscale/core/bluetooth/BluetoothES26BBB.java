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
            parseNotifyPacket(value);
        }
    }

    /**
     * Parse a packet sent by the scale, through characteristic NOTIFY_MEASUREMENT_CHARACTERISTIC.
     *
     * @param data The data payload (in bytes)
     */
    private void parseNotifyPacket(byte[] data) {
        String dataStr = byteInHex(data);
        Timber.d("Received measurement packet: %s", dataStr);

        if (!isChecksumValid(data)) {
            Timber.w("Checksum of packet did not match. Ignoring measurement. Packet: %s", dataStr);
            return;
        }

        // Bytes 0, 1, 3 and 4 seem to be ignored by the original implementation

        byte action = data[2];
        switch (action) {
            case 0x14:
                handleMeasurementPayload(data);
                break;
            case 0x11:
                // TODO this seems to be sent at the start and at the end of the measurement (?)
                // This sends scale information, such as power status, unit, precision, offline count and battery
                byte powerStatus = data[5];
                byte unit = data[6];
                byte precision = data[7];
                byte offlineCount = data[8];
                byte battery = data[9];

                Timber.d(
                        "Received scale information. Power status: %d, Unit: %d, Precision: %d, Offline count: %d, Battery: %d",
                        powerStatus,
                        unit,
                        precision,
                        offlineCount,
                        battery
                );
                // TODO

                break;
            case 0x15:
                // TODO this is sent near the start of the measurements
                // From reversing the APK, this is offline data
                break;
            case 0x10:
                // TODO this is callback from write actions (?)
                byte success = data[5];
                if (success == 1) {
                    // TODO success
                } else {
                    // TODO failure
                }
            default:
                Timber.w("Unknown action sent from scale: %x. Full packet: %s", action, dataStr);
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
     * Handle a packet of type "measurement" (0x14).
     * There are two types: real time (0x00/0x10) or final (0x01/0x11), indicated by byte 5.
     * Real time measurements only have weight, whereas final measurements can also have resistance.
     * <p>
     * This will create and save a measurement if it is final, discarding real time measurements.
     *
     * @param data The data payload (in bytes)
     */
    private void handleMeasurementPayload(byte[] data) {
        // TODO not sure what more options are available
        Timber.d("Parsing measurement");

        // 0x01 and 0x11 are final measurements, 0x00 and 0x10 are real-time measurements
        byte measurementType = data[5];

        if (data[5] != 0x01 && data[5] != 0x11) {
            // This byte indicates whether the measurement is final or not
            // Discard if it isn't, we only want the final value
            Timber.d("Discarded measurement since it is not final");
            return;
        }

        Timber.d("Saving measurement");
        // Weight (in kg) is stored as big-endian in bytes 6 to 9
        // It should fit in a byte, but original implementation uses a long (probably to avoid handling unsigned int)
        long weightKg = (data[6] << 24) | (data[7] << 16) | (data[8] << 8) | data[9];
        int resistance = (data[10] << 8) | data[11];

        Timber.d("Got measurement from scale. Weight: %d, Resistance: %d", weightKg, resistance);

        // FIXME weight might be in other units, investigate

        saveMeasurement(weightKg);
    }

    /**
     * Save a measurement from the scale to openScale.
     *
     * @param weightKg The weight, in kilograms, multiplied by 100 (that is, as an integer)
     */
    private void saveMeasurement(long weightKg) {
        // TODO add more measurements

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight((float) weightKg / 100);

        addScaleMeasurement(btScaleMeasurement);
    }
}
