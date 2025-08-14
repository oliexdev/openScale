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

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

import android.content.Context;

import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothBeurerBF600 extends BluetoothStandardWeightProfile {
    private static final UUID SERVICE_BEURER_CUSTOM_BF600 = BluetoothGattUuid.fromShortCode(0xfff0);
    private static final UUID CHARACTERISTIC_BEURER_BF600_SCALE_SETTING = BluetoothGattUuid.fromShortCode(0xfff1);
    private static final UUID CHARACTERISTIC_BEURER_BF600_USER_LIST = BluetoothGattUuid.fromShortCode(0xfff2);
    private static final UUID CHARACTERISTIC_BEURER_BF600_ACTIVITY_LEVEL = BluetoothGattUuid.fromShortCode(0xfff3);
    private static final UUID CHARACTERISTIC_BEURER_BF600_TAKE_MEASUREMENT = BluetoothGattUuid.fromShortCode(0xfff4);
    private static final UUID CHARACTERISTIC_BEURER_BF600_REFER_WEIGHT_BF = BluetoothGattUuid.fromShortCode(0xfff5);
    private static final UUID CHARACTERISTIC_BEURER_BF850_INITIALS = BluetoothGattUuid.fromShortCode(0xfff6);

    private String deviceName;

    public BluetoothBeurerBF600(Context context, String name) {
        super(context);
        deviceName = name;
    }

    @Override
    public String driverName() {
        return "Beurer " + deviceName;
    }

    public static String driverId() {
        return "beurer_bf600";
    }

    @Override
    protected int getVendorSpecificMaxUserCount() {
        return 8;
    }

    @Override
    protected void writeActivityLevel() {
        Converters.ActivityLevel al = selectedUser.getActivityLevel();
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0});
        parser.setIntValue(al.toInt() + 1, FORMAT_UINT8, 0);
        Timber.d(String.format("setCurrentUserData Activity level: %d", al.toInt() + 1));
        writeBytes(SERVICE_BEURER_CUSTOM_BF600,
                CHARACTERISTIC_BEURER_BF600_ACTIVITY_LEVEL, parser.getValue());
    }

    @Override
    protected void writeInitials() {
        if (haveCharacteristic(SERVICE_BEURER_CUSTOM_BF600, CHARACTERISTIC_BEURER_BF850_INITIALS)) {
            BluetoothBytesParser parser = new BluetoothBytesParser();
            String initials = getInitials(this.selectedUser.getUserName());
            Timber.d("Initials: " + initials);
            parser.setString(initials);
            writeBytes(SERVICE_BEURER_CUSTOM_BF600, CHARACTERISTIC_BEURER_BF850_INITIALS,
                    parser.getValue());
        }
    }

    @Override
    protected void requestMeasurement() {
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0});
        parser.setIntValue(0x00, FORMAT_UINT8, 0);
        Timber.d(String.format("requestMeasurement BEURER 0xFFF4 magic: 0x00"));
        writeBytes(SERVICE_BEURER_CUSTOM_BF600,
                CHARACTERISTIC_BEURER_BF600_TAKE_MEASUREMENT, parser.getValue());
    }

    @Override
    protected void setNotifyVendorSpecificUserList() {
        if (setNotificationOn(SERVICE_BEURER_CUSTOM_BF600,
                CHARACTERISTIC_BEURER_BF600_USER_LIST)) {
            Timber.d("setNotifyVendorSpecificUserList() OK");
        }
        else {
            Timber.d("setNotifyVendorSpecificUserList() FAILED");
        }
    }

    @Override
    protected synchronized void requestVendorSpecificUserList() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(0x00, FORMAT_UINT8);
        writeBytes(SERVICE_BEURER_CUSTOM_BF600, CHARACTERISTIC_BEURER_BF600_USER_LIST,
                parser.getValue());
        stopMachineState();
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (characteristic.equals(CHARACTERISTIC_BEURER_BF600_USER_LIST)) {
            handleVendorSpecificUserList(value);
        }
        else {
            super.onBluetoothNotify(characteristic, value);
        }
    }
}
