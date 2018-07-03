/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.UUID;

import timber.log.Timber;

public class BluetoothOneByone extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE_BODY_COMPOSITION = UUID.fromString("0000181B-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION = UUID.fromString("00002A9C-0000-1000-8000-00805f9b34fb"); // read, indication

    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private final UUID CMD_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"); // write only
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private float lastWeight;

    public BluetoothOneByone(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "1byone";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                lastWeight = 0;
                setIndicationOn(WEIGHT_MEASUREMENT_SERVICE_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CHARACTERISTIC_BODY_COMPOSITION, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                ScaleUser currentUser = OpenScale.getInstance().getSelectedScaleUser();
                byte unit = 0x00; // kg
                switch (currentUser.getScaleUnit()) {
                    case LB:
                        unit = 0x01;
                        break;
                    case ST:
                        unit = 0x02;
                        break;
                }
                byte group = 0x01;
                byte[] magicBytes = {(byte)0xfd, (byte)0x37, unit, group,
                        (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                        (byte)0x00, (byte)0x00, (byte)0x00};
                magicBytes[magicBytes.length - 1] =
                        xorChecksum(magicBytes, 0, magicBytes.length - 1);
                writeBytes(WEIGHT_MEASUREMENT_SERVICE, CMD_MEASUREMENT_CHARACTERISTIC, magicBytes);
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        return false;
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        // if data is valid data
        if (data != null && data.length == 20) {
            parseBytes(data);
        }
    }

    private void parseBytes(byte[] weightBytes) {
        float weight = Converters.fromUnsignedInt16Le(weightBytes, 11) / 100.0f;
        int impedance = Converters.fromUnsignedInt24Le(weightBytes, 15);

        Timber.d("weight: %.2f, impedance: %d", weight, impedance);

        // This check should be a bit more elaborate, but it works for now...
        if (weight != lastWeight) {
            lastWeight = weight;

            ScaleMeasurement scaleBtData = new ScaleMeasurement();
            scaleBtData.setWeight(weight);

            addScaleData(scaleBtData);
        }
    }
}
