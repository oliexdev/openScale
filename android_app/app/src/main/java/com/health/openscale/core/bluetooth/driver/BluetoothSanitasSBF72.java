/* Copyright (C) 2021  olie.xdev <olie.xdev@googlemail.com>
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

/*
* Based on source-code by weliem/blessed-android
*/

package com.health.openscale.core.bluetooth.driver;

import android.content.Context;

import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.welie.blessed.BluetoothBytesParser;

import java.util.UUID;

import timber.log.Timber;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

public class BluetoothSanitasSBF72 extends BluetoothStandardWeightProfile {
    private String deviceName;

    private static final UUID SERVICE_SBF72_CUSTOM = BluetoothGattUuid.fromShortCode(0xffff);

    private static final UUID CHARACTERISTIC_SCALE_SETTINGS = BluetoothGattUuid.fromShortCode(0x0000);
    private static final UUID CHARACTERISTIC_USER_LIST = BluetoothGattUuid.fromShortCode(0x0001);
    private static final UUID CHARACTERISTIC_ACTIVITY_LEVEL = BluetoothGattUuid.fromShortCode(0x0004);
    private static final UUID CHARACTERISTIC_REFER_WEIGHT_BF = BluetoothGattUuid.fromShortCode(0x000b);
    private static final UUID CHARACTERISTIC_TAKE_MEASUREMENT = BluetoothGattUuid.fromShortCode(0x0006);

    public BluetoothSanitasSBF72(Context context, String deviceName) {
        super(context, deviceName);
        this.deviceName = deviceName;
    }

    @Override
    public String driverName() {
        return deviceName;
    }

    public static String driverId() {
        return "sanitas_sbf72";
    }

    @Override
    protected int getVendorSpecificMaxUserCount() {
        return 8;
    }

    @Override
    protected void enterScaleUserConsentUi(int appScaleUserId, int scaleUserIndex) {
        //Requests the scale to display the pin for the user in it's display.
        //As parameter we need to send a pin-index to the custom user-list characteristic.
        //For user with index 1 the pin-index is 0x11, for user with index 2 it is 0x12 and so on.
        int scalePinIndex = scaleUserIndex + 16;
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(scalePinIndex, FORMAT_UINT8);
        writeBytes(SERVICE_SBF72_CUSTOM, CHARACTERISTIC_USER_LIST, parser.getValue());

        //opens the input screen for the pin in the app
        super.enterScaleUserConsentUi(appScaleUserId, scaleUserIndex);
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (characteristic.equals(CHARACTERISTIC_USER_LIST)) {
            //the if condition is to catch the response to "display-pin-on-scale", because this response would produce an error in handleVendorSpecificUserList().
            if (value != null && value.length > 0 && value[0] != 17) {
                handleVendorSpecificUserList(value);
            }
        }
        else {
            super.onBluetoothNotify(characteristic, value);
        }
    }

    @Override
    protected ScaleMeasurement bodyCompositionMeasurementToScaleMeasurement(byte[] value) {
        ScaleMeasurement measurement = super.bodyCompositionMeasurementToScaleMeasurement(value);
        float weight = measurement.getWeight();
        if (weight == 0.f && previousMeasurement != null) {
            weight = previousMeasurement.getWeight();
        }
        if (weight != 0.f) {
            float water = Math.round(((measurement.getWater() / weight) * 10000.f))/100.f;
            measurement.setWater(water);
        }
        return measurement;
    }

    @Override
    protected void setNotifyVendorSpecificUserList() {
        if (setNotificationOn(SERVICE_SBF72_CUSTOM, CHARACTERISTIC_USER_LIST)) {
            Timber.d("setNotifyVendorSpecificUserList() OK");
        } else {
            Timber.d("setNotifyVendorSpecificUserList() FAILED");
        }
    }

    @Override
    protected synchronized void requestVendorSpecificUserList() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0, FORMAT_UINT8);
        writeBytes(SERVICE_SBF72_CUSTOM, CHARACTERISTIC_USER_LIST, parser.getValue());
    }

    @Override
    protected void writeActivityLevel() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int activityLevel = this.selectedUser.getActivityLevel().toInt() + 1;
        Timber.d(String.format("activityLevel: %d", activityLevel));
        parser.setIntValue(activityLevel, FORMAT_UINT8);
        writeBytes(SERVICE_SBF72_CUSTOM, CHARACTERISTIC_ACTIVITY_LEVEL, parser.getValue());
    }

    @Override
    protected void writeInitials() {
        Timber.d("Write user initials is not supported by " + deviceName + "!");
    }

    @Override
    protected synchronized void requestMeasurement() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0, FORMAT_UINT8);
        writeBytes(SERVICE_SBF72_CUSTOM, CHARACTERISTIC_TAKE_MEASUREMENT, parser.getValue());
    }

}
