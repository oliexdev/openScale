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
package com.health.openscale.core.bluetooth;

import android.content.Context;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.util.UUID;

import timber.log.Timber;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

class BluetoothGattUuidBF600 extends BluetoothGattUuid {
    public static final UUID SERVICE_BEURER_CUSTOM_BF600 = fromShortCode(0xfff0);
    public static final UUID CHARACTERISTIC_BEURER_BF600_SCALE_SETTING = fromShortCode(0xfff1);
    public static final UUID CHARACTERISTIC_BEURER_BF600_USER_LIST = fromShortCode(0xfff2);
    public static final UUID CHARACTERISTIC_BEURER_BF600_ACTIVITY_LEVEL = fromShortCode(0xfff3);
    public static final UUID CHARACTERISTIC_BEURER_BF600_TAKE_MEASUREMENT = fromShortCode(0xfff4);
    public static final UUID CHARACTERISTIC_BEURER_BF600_REFER_WEIGHT_BF = fromShortCode(0xfff5);
}

public class BluetoothBeurerBF600 extends BluetoothStandardWeightProfile {

    ScaleMeasurement scaleMeasurement;

    public BluetoothBeurerBF600(Context context) {
        super(context);
        scaleMeasurement = new ScaleMeasurement();
    }

    @Override
    public String driverName() {
        return "Beurer BF600";
    }

    @Override
    protected void writeActivityLevel() {
        Converters.ActivityLevel al = selectedUser.getActivityLevel();
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0});
        parser.setIntValue(al.toInt() + 1, FORMAT_UINT8, 0);
        Timber.d(String.format("setCurrentUserData Activity level: %d", al.toInt() + 1));
        writeBytes(BluetoothGattUuidBF600.SERVICE_BEURER_CUSTOM_BF600,
                BluetoothGattUuidBF600.CHARACTERISTIC_BEURER_BF600_ACTIVITY_LEVEL, parser.getValue());
    }

    @Override
    protected void requestMeasurement() {
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0});
        parser.setIntValue(0x00, FORMAT_UINT8, 0);
        Timber.d(String.format("requestMeasurement BEURER 0xFFF4 magic: 0x00"));
        writeBytes(BluetoothGattUuidBF600.SERVICE_BEURER_CUSTOM_BF600,
                BluetoothGattUuidBF600.CHARACTERISTIC_BEURER_BF600_TAKE_MEASUREMENT, parser.getValue());
    }

    @Override
    protected void handleWeightMeasurement(byte[] value) {
        scaleMeasurement = weightMeasurementToScaleMeasurement(value);
    }

    @Override
    protected void handleBodyCompositionMeasurement(byte[] value) {
        scaleMeasurement.merge(bodyCompositionMeasurementToScaleMeasurement(value));
        addScaleMeasurement(scaleMeasurement);
    }
}
