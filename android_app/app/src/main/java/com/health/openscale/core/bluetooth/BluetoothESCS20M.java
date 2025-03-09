package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothESCS20M extends BluetoothCommunication{

    private static final UUID SERV_CUR_TIME = BluetoothGattUuid.fromShortCode(0x1a10);

    private static final UUID CHAR_CUR_TIME = BluetoothGattUuid.fromShortCode(0x2a11);
    private static final UUID CHAR_RESULTS = BluetoothGattUuid.fromShortCode(0x2a10);

    private static final byte MESSAGE_ID_WEIGHT_RESP = 0x14;

    private static final byte[] MAGIC_BYTES_START_MEASUREMENT = new byte[]{
            (byte) 0x55, (byte) 0xaa, (byte) 0x90, (byte) 0x00, (byte) 0x04, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x94
    };

    public BluetoothESCS20M(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "ES-CS20M";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        Timber.i("onNextStep(%d)", stepNr);

        switch (stepNr){
            case 0:
                setNotificationOn(SERV_CUR_TIME, CHAR_CUR_TIME);
                break;
            case 1:
                setNotificationOn(SERV_CUR_TIME, CHAR_RESULTS);
                break;
            case 2:
                writeBytes(SERV_CUR_TIME, CHAR_CUR_TIME, MAGIC_BYTES_START_MEASUREMENT);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value){
        Timber.d("Received notification on UUID = %s", characteristic.toString());

        for(int i = 0; i < value.length; i++) {
            Timber.d("Byte %d = 0x%02x", i, value[i]);
        }

        if(getStepNr() == 3){
            final boolean stableValue = Byte.toUnsignedInt(value[5]) != 0;
            if(stableValue && value[2] == MESSAGE_ID_WEIGHT_RESP){
                float weight_kg = (Byte.toUnsignedInt(value[8])*256 + Byte.toUnsignedInt(value[9])) / 100.0f;
                saveMeasurement(weight_kg);
            }

        }
    }

    private void saveMeasurement(float weightKg) {
        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight(weightKg);

        addScaleMeasurement(btScaleMeasurement);
    }
}
