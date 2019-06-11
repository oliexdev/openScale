package com.health.openscale.core.bluetooth;

import android.content.Context;
import android.os.Handler;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BluetoothSalter extends BluetoothCommunication {

    private static final UUID MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xFFCC);
    private static final UUID MEASUREMENT_NOTIFICATION = BluetoothGattUuid.fromShortCode(0xFFC3);

    private AtomicInteger lastWeight = new AtomicInteger(0);
    private AtomicBoolean measurementSent = new AtomicBoolean(false);

    private final Handler delayedMeasurementHandler = new Handler();

    private final Runnable addMeasurement = new Runnable() {
        @Override
        public void run() {
            ScaleMeasurement scaleBtData = new ScaleMeasurement();
            scaleBtData.setWeight((float) lastWeight.get() / 10.0f);
            scaleBtData.setDateTime(new Date());
            addScaleMeasurement(scaleBtData);
            disconnect();
        }
    };

    public BluetoothSalter(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Salter MiBody";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                lastWeight.set(0);
                measurementSent.set(false);

                setNotificationOn(MEASUREMENT_SERVICE, MEASUREMENT_NOTIFICATION);
                break;

            case 1:
                sendMessage(R.string.info_step_on_scale, 0);
                break;

            default:
                return false;
        }

        return true;
    }

    @Override
    protected void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (value == null || characteristic.compareTo(MEASUREMENT_NOTIFICATION) != 0 ||
                measurementSent.get()) {
            return;
        }

        if (value.length == 7) {
            lastWeight.set(((value[6] & 0xFF) << 8) | (value[5] & 0xFF));

            // Weight measurements arrive every 500ms, so wait 1000ms for the last measurement to
            // arrive.
            delayedMeasurementHandler.removeCallbacks(addMeasurement);
            delayedMeasurementHandler.postDelayed(addMeasurement, 1000);
        }
    }
}
