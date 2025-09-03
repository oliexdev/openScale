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

import android.content.Context;

import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;

import timber.log.Timber;

public class BluetoothBeurerBF950 extends BluetoothBeurerBF105 {
    private String deviceName;

    public BluetoothBeurerBF950(Context context, String deviceName) {
        super(context, deviceName);
        this.deviceName = deviceName;
    }

    @Override
    public String driverName() {
        return deviceName;
    }

    public static String driverId() {
        return "beurer_bf950";
    }

    @Override
    protected int getVendorSpecificMaxUserCount() {
        return 8;
    }

    @Override
    protected void writeTargetWeight() {
        Timber.d("Target Weight not supported on " + deviceName);
    }
}
