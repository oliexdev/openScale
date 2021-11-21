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

package com.health.openscale.core.bluetooth;

import android.content.Context;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothTanita extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("273e5100-6b90-4779-83b8-b8bf1dadac35");
    private final UUID WEIGHT_MEASUREMENT_NOTIFY_1 = UUID.fromString("273e510f-6b90-4779-83b8-b8bf1dadac35"); // NOTIFY
    private final UUID WEIGHT_MEASUREMENT_WRITE_1 = UUID.fromString("273e5108-6b90-4779-83b8-b8bf1dadac35"); // WRITE
    private final UUID WEIGHT_MEASUREMENT_NOTIFY_2 = UUID.fromString("273e510e-6b90-4779-83b8-b8bf1dadac35"); // NOTIFY
    private final UUID WEIGHT_MEASUREMENT_WRITE_2 = UUID.fromString("273e5107-6b90-4779-83b8-b8bf1dadac35"); // WRITE
    private final UUID WEIGHT_MEASUREMENT_NOTIFY_3 = UUID.fromString("273e510d-6b90-4779-83b8-b8bf1dadac35"); // NOTIFY

    public BluetoothTanita(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Tanita BC-401";
    }


    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        if (value != null && value.length > 0) {
            Timber.d("ON TANITA BLUETOOTH NOTIFY " + BluetoothGattUuid.prettyPrint(characteristic) + " value " + byteInHex(value) + "length" + value.length);
        }
    }


    @Override
    protected boolean onNextStep(int stepNr) {
        switch (stepNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_NOTIFY_1);
                break;
            case 1:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_NOTIFY_2);
                break;
            case 2:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_NOTIFY_3);
                break;
            case 3:
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_WRITE_1, new byte[]{0x01});
                break;
            case 4:
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_WRITE_2, new byte[]{0x01});
                break;
            default:
                return false;
        }

        return true;
    }
}
