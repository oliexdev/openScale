package com.health.openscale.core.bluetooth;

import static com.health.openscale.core.utils.Converters.toCentimeter;

import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import java.time.LocalDateTime;

import timber.log.Timber;

public class BluetoothWiiBalanceBoard extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("idk");
    private final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC = UUID.fromString("still idk");

    private ScaleUser user;

    public BluetoothWiiBalanceBoard(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Wii Balance Board";
    }



//The data reported by the board seems to make no mention of the time of the measurement, so this will have(?) to be determined by the user's device.








// Below section stolen from other people's code because it seems moderately universal

    /**
     * Save a measurement from the scale to openScale.
     *
     * @param weightKg   The weight, in kilograms
     */
    private void saveMeasurement(float weightKg) {

        final ScaleUser scaleUser = OpenScale.getInstance().getSelectedScaleUser();

        Timber.d("Saving measurement for scale user %s", scaleUser);

        final ScaleMeasurement btScaleMeasurement = new ScaleMeasurement();
        btScaleMeasurement.setWeight(weightKg);

        addScaleMeasurement(btScaleMeasurement);
    }
}
















}
