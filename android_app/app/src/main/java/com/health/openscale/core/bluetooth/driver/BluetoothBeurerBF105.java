/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

import android.content.Context;

import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.welie.blessed.BluetoothBytesParser;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothBeurerBF105 extends BluetoothStandardWeightProfile {
    private static final UUID SERVICE_BF105_CUSTOM = BluetoothGattUuid.fromShortCode(0xffff);
    private static final UUID SERVICE_BF105_IMG = BluetoothGattUuid.fromShortCode(0xffc0);

    private static final UUID CHARACTERISTIC_SCALE_SETTINGS = BluetoothGattUuid.fromShortCode(0x0000);
    private static final UUID CHARACTERISTIC_USER_LIST = BluetoothGattUuid.fromShortCode(0x0001);
    private static final UUID CHARACTERISTIC_INITIALS = BluetoothGattUuid.fromShortCode(0x0002);
    private static final UUID CHARACTERISTIC_TARGET_WEIGHT = BluetoothGattUuid.fromShortCode(0x0003);
    private static final UUID CHARACTERISTIC_ACTIVITY_LEVEL = BluetoothGattUuid.fromShortCode(0x0004);
    private static final UUID CHARACTERISTIC_REFER_WEIGHT_BF = BluetoothGattUuid.fromShortCode(0x000b);
    private static final UUID CHARACTERISTIC_BT_MODULE = BluetoothGattUuid.fromShortCode(0x0005);
    private static final UUID CHARACTERISTIC_TAKE_MEASUREMENT = BluetoothGattUuid.fromShortCode(0x0006);
    private static final UUID CHARACTERISTIC_TAKE_GUEST_MEASUREMENT = BluetoothGattUuid.fromShortCode(0x0007);
    private static final UUID CHARACTERISTIC_BEURER_I = BluetoothGattUuid.fromShortCode(0x0008);
    private static final UUID CHARACTERISTIC_UPPER_LOWER_BODY = CHARACTERISTIC_BEURER_I;
    private static final UUID CHARACTERISTIC_BEURER_II = BluetoothGattUuid.fromShortCode(0x0009);
    private static final UUID CHARACTERISTIC_BEURER_III = BluetoothGattUuid.fromShortCode(0x000a);
    private static final UUID CHARACTERISTIC_IMG_IDENTIFY = BluetoothGattUuid.fromShortCode(0xffc1);
    private static final UUID CHARACTERISTIC_IMG_BLOCK = BluetoothGattUuid.fromShortCode(0xffc2);


    public BluetoothBeurerBF105(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Beurer BF105/720";
    }

    public static String driverId() {
        return "beurer_bf105";
    }

    @Override
    protected int getVendorSpecificMaxUserCount() {
        return 10;
    }

    @Override
    protected void writeUserDataToScale() {
        writeTargetWeight();
        super.writeUserDataToScale();
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (characteristic.equals(CHARACTERISTIC_USER_LIST)) {
            handleVendorSpecificUserList(value);
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
        if (setNotificationOn(SERVICE_BF105_CUSTOM, CHARACTERISTIC_USER_LIST)) {
            Timber.d("setNotifyVendorSpecificUserList() OK");
        }
        else {
            Timber.d("setNotifyVendorSpecificUserList() FAILED");
        }
    }

    @Override
    protected synchronized void requestVendorSpecificUserList() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_USER_LIST,
                parser.getValue());
    }

    @Override
    protected void writeActivityLevel() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int activityLevel = this.selectedUser.getActivityLevel().toInt() + 1;
        Timber.d(String.format("activityLevel: %d", activityLevel));
        parser.setIntValue(activityLevel, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_ACTIVITY_LEVEL,
                parser.getValue());
    }

    protected void writeTargetWeight() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        int targetWeight = (int) this.selectedUser.getGoalWeight();
        parser.setIntValue(targetWeight, FORMAT_UINT16);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_TARGET_WEIGHT,
                parser.getValue());
    }

    @Override
    protected void writeInitials() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        String initials = getInitials(this.selectedUser.getUserName());
        Timber.d("Initials: " + initials);
        parser.setString(initials);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_INITIALS,
                parser.getValue());
    }

    @Override
    protected synchronized void requestMeasurement() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0, FORMAT_UINT8);
        writeBytes(SERVICE_BF105_CUSTOM, CHARACTERISTIC_TAKE_MEASUREMENT,
                parser.getValue());
    }
}
