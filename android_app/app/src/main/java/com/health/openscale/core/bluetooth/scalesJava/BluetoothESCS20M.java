package com.health.openscale.core.bluetooth.scalesJava;

import android.content.Context;

import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.bluetooth.libs.YunmaiLib;
import com.health.openscale.core.data.GenderType;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.LogManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class BluetoothESCS20M extends BluetoothCommunication {
    private static final String TAG = "BluetoothESCS20M";

    private static final UUID SERV_CUR_TIME = BluetoothGattUuid.fromShortCode(0x1a10);

    private static final UUID CHAR_CUR_TIME = BluetoothGattUuid.fromShortCode(0x2a11);
    private static final UUID CHAR_RESULTS = BluetoothGattUuid.fromShortCode(0x2a10);

    private static final byte MESSAGE_ID_START_STOP_RESP = 0x11;
    private static final byte MESSAGE_ID_WEIGHT_RESP = 0x14;
    private static final byte MESSAGE_ID_EXTENDED_RESP = 0x15;

    private static final byte MEASUREMENT_TYPE_START_WEIGHT_ONLY = 0x18;
    private static final byte MEASUREMENT_TYPE_STOP_WEIGHT_ONLY = 0x17;
    private static final byte MEASUREMENT_TYPE_START_ALL = 0x19;
    private static final byte MEASUREMENT_TYPE_STOP_ALL = 0x18;

    private static final byte[] MAGIC_BYTES_START_MEASUREMENT = new byte[]{
            (byte) 0x55, (byte) 0xaa, (byte) 0x90, (byte) 0x00, (byte) 0x04, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x94
    };
    private static final byte[] MAGIC_BYTES_DELETE_HISTORY_DATA = new byte[]{
            (byte)0x55, (byte) 0xaa, (byte) 0x95, (byte)0x00, (byte)0x01, (byte)0x01,(byte) 0x96
    };

    private List<byte[]> rawMeasurements = new ArrayList<>();
    private final ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

    public BluetoothESCS20M(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "ES-CS20M";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        LogManager.i(TAG, String.format("onNextStep(%d)", stepNr));

        switch (stepNr) {
            case 0:
                setNotificationOn(SERV_CUR_TIME, CHAR_CUR_TIME);
                break;
            case 1:
                setNotificationOn(SERV_CUR_TIME, CHAR_RESULTS);
                break;
            case 2:
                writeBytes(SERV_CUR_TIME, CHAR_CUR_TIME, MAGIC_BYTES_START_MEASUREMENT);
                writeBytes(SERV_CUR_TIME, CHAR_CUR_TIME, MAGIC_BYTES_DELETE_HISTORY_DATA);
                stopMachineState();
                break;
            case 3:
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        LogManager.d(TAG, String.format("Received notification on UUID = %s", characteristic.toString()));
        LogManager.d(TAG, String.format("Received in step(%d)", getStepNr()));

        for (int i = 0; i < value.length; i++) {
            LogManager.d(TAG, String.format("Byte %d = 0x%02x", i, value[i]));
        }

        rawMeasurements.add(value);

        final byte msgID = value[2];

        if (msgID != MESSAGE_ID_START_STOP_RESP)
            return;

        final byte measurementType = value[10];

        if (getStepNr() == 4 && (measurementType == MEASUREMENT_TYPE_STOP_WEIGHT_ONLY || measurementType == MEASUREMENT_TYPE_STOP_ALL)) {
            final ScaleUser scaleUser = getSelectedScaleUser();
            final int sex = scaleUser.getGender() == GenderType.MALE ? 1 : 0;
            YunmaiLib yunmaiLib = new YunmaiLib(sex, scaleUser.getBodyHeight(), scaleUser.getActivityLevel());

            rawMeasurements = rawMeasurements.stream().sorted(Comparator.comparingInt(a -> a[2])).collect(Collectors.toList());

            LogManager.d(TAG, "Parsing measurements");

            for (byte[] msg : rawMeasurements) {
                parseMsg(msg, yunmaiLib, scaleUser);
            }

            LogManager.d(TAG, String.format("Saving measurement for scale user %s", scaleUser));

            addScaleMeasurement(scaleMeasurement);
        }

        if (getStepNr() == 3 && (measurementType == MEASUREMENT_TYPE_START_WEIGHT_ONLY || measurementType == MEASUREMENT_TYPE_START_ALL))
            resumeMachineState();
    }

    private void parseMsg(byte[] msg, YunmaiLib calcLib, ScaleUser user) {
        final byte msgID = msg[2];

        switch (msgID) {
            case MESSAGE_ID_WEIGHT_RESP:
                LogManager.d(TAG, "Found weight measurement");

                final boolean stableValue = Byte.toUnsignedInt(msg[5]) != 0;
                if (stableValue) {
                    LogManager.d(TAG, "Found stable weight measurement");
                    scaleMeasurement.setWeight(Converters.fromUnsignedInt16Be(msg, 8) / 100.0f);

                    if (msg[10] != 0x00 && msg[11] != 0x00) {
                        LogManager.d(TAG, "Found embedded extended measurements in weight message");
                        if (rawMeasurements.stream().filter(a -> a[2] == 0x15).count() > 0) {
                            LogManager.d(TAG, "Ignore embedded extended measurements because separate message found");
                            return;
                        }

                        final int resistance = Converters.fromUnsignedInt16Be(msg, 10);
                        parseExtendedMeasurement(resistance, calcLib, user);
                    }
                }
                break;

            case MESSAGE_ID_EXTENDED_RESP:
                LogManager.d(TAG, "Found extended measurements message");
                final int resistance = Converters.fromUnsignedInt16Be(msg, 9);

                parseExtendedMeasurement(resistance, calcLib, user);
                break;
        }
    }

    private void parseExtendedMeasurement(final int resistance, YunmaiLib calcLib, ScaleUser user) {
        LogManager.d(TAG, "Found extended measurements");

        final float weight = scaleMeasurement.getWeight();
        if (weight == 0.0f) {
            LogManager.d(TAG, "Weight is zero, could not process extended measurements");
            return;
        }

        final float bodyFat = calcLib.getFat(user.getAge(), weight, resistance);
        final float muscle = calcLib.getMuscle(bodyFat) / weight * 100.0f;
        final float water = calcLib.getWater(bodyFat);
        final float bone = calcLib.getBoneMass(muscle, weight);
        final float lbm = calcLib.getLeanBodyMass(weight, bodyFat);
        final float visceralFal = calcLib.getVisceralFat(bodyFat, user.getAge());

        scaleMeasurement.setFat(bodyFat);
        scaleMeasurement.setMuscle(muscle);
        scaleMeasurement.setWater(water);
        scaleMeasurement.setBone(bone);
        scaleMeasurement.setLbm(lbm);
        scaleMeasurement.setVisceralFat(visceralFal);
    }
}
