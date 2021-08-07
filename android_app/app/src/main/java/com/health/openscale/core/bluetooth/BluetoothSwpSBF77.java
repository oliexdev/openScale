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
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothBytesParser;

import java.util.Arrays;
import java.util.UUID;

import timber.log.Timber;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;

class BluetoothGattUuidSBF77 extends BluetoothGattUuid {
    public static final UUID SERVICE_CUSTOM_SBF77 = fromShortCode(0xffff);
    public static final UUID CHARACTERISTIC_SBF77_USER_LIST = fromShortCode(0x0001);
    public static final UUID CHARACTERISTIC_SBF77_INITIALS = fromShortCode(0x0002);
    public static final UUID CHARACTERISTIC_SBF77_ACTIVITY_LEVEL = fromShortCode(0x0004);
}

public class BluetoothSwpSBF77 extends BluetoothStandardWeightProfile {

    String deviceName;

    public BluetoothSwpSBF77(Context context, String name) {
        super(context);
        deviceName = name;
    }

    @Override
    public String driverName() {
        return deviceName;
    }

    @Override
    protected void writeBirthday() {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setDateTime(dateToCalender(this.selectedUser.getBirthday()));
        writeBytes(BluetoothGattUuid.SERVICE_USER_DATA, BluetoothGattUuid.CHARACTERISTIC_USER_DATE_OF_BIRTH,
                Arrays.copyOfRange(parser.getValue(), 0, 3));
    }

    @Override
    protected void writeActivityLevel() {
        Converters.ActivityLevel al = selectedUser.getActivityLevel();
        BluetoothBytesParser parser = new BluetoothBytesParser(new byte[]{0});
        parser.setIntValue(al.toInt() + 1, FORMAT_UINT8, 0);
        Timber.d(String.format("setCurrentUserData Activity level: %d", al.toInt() + 1));
        writeBytes(BluetoothGattUuidSBF77.SERVICE_CUSTOM_SBF77,
                BluetoothGattUuidSBF77.CHARACTERISTIC_SBF77_ACTIVITY_LEVEL, parser.getValue());
    }

    @Override
    protected void setNotifyVendorSpecificUserList() {
        if (setNotificationOn(BluetoothGattUuidSBF77.SERVICE_CUSTOM_SBF77,
                BluetoothGattUuidSBF77.CHARACTERISTIC_SBF77_USER_LIST)) {
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
        writeBytes(BluetoothGattUuidSBF77.SERVICE_CUSTOM_SBF77, BluetoothGattUuidSBF77.CHARACTERISTIC_SBF77_USER_LIST,
                parser.getValue());
        stopMachineState();
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (characteristic.equals(BluetoothGattUuidSBF77.CHARACTERISTIC_SBF77_USER_LIST)) {
            handleVendorSpecificUserList(value);
        }
        else {
            super.onBluetoothNotify(characteristic, value);
        }
    }
}
