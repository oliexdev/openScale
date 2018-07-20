/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import java.util.HashMap;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothDebug extends BluetoothCommunication {
    HashMap<Integer, String> propertyString;

    BluetoothDebug(Context context) {
        super(context);

        propertyString = new HashMap<>();
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_BROADCAST, "BROADCAST");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_READ, "READ");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, "WRITE_NO_RESPONSE");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_WRITE, "WRITE");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_NOTIFY, "NOTIFY");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_INDICATE, "INDICATE");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE, "SIGNED_WRITE");
        propertyString.put(BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS, "EXTENDED_PROPS");
    }

    @Override
    public String driverName() {
        return "Debug";
    }

    private boolean isBlacklisted(BluetoothGattService service, BluetoothGattCharacteristic characteristic) {
        // Reading this triggers a pairing request on Beurer BF710
        if (service.getUuid().equals(UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"))
            && characteristic.getUuid().equals(UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"))) {
            return true;
        }

        return false;
    }

    private String propertiesToString(int properties) {
        StringBuilder names = new StringBuilder();
        for (int property : propertyString.keySet()) {
            if ((properties & property) != 0) {
                names.append(propertyString.get(property));
                names.append(", ");
            }
        }

        if (names.length() == 0) {
            return "<none>";
        }

        return names.substring(0, names.length() - 2);
    }

    private void logService(BluetoothGattService service, boolean included) {
        Timber.d("Service %s%s", service.getUuid(), included ? " (included)" : "");

        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            Timber.d("|- characteristic %s (instance %d): %s (permissions=0x%x)",
                    characteristic.getUuid(), characteristic.getInstanceId(),
                    propertiesToString(characteristic.getProperties()), characteristic.getPermissions());
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0) {
                Timber.d("|--> value: %s (%s)", byteInHex(value),
                        characteristic.getStringValue(0).replaceAll("\\p{Cntrl}", "?"));
            }

            for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                Timber.d("|--- descriptor %s (permissions=0x%x)",
                        descriptor.getUuid(), descriptor.getPermissions());

                value = descriptor.getValue();
                if (value != null && value.length > 0) {
                    Timber.d("|-----> value: %s", byteInHex(value));
                }
            }
        }

        for (BluetoothGattService includedService : service.getIncludedServices()) {
            logService(includedService, true);
        }
    }

    private int readServiceCharacteristics(BluetoothGattService service, int offset) {
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0
                && !isBlacklisted(service, characteristic)) {

                if (offset == 0) {
                    readBytes(service.getUuid(), characteristic.getUuid());
                    return -1;
                }

                offset -= 1;
            }

            for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                if (offset == 0) {
                    readBytes(service.getUuid(), characteristic.getUuid(), descriptor.getUuid());
                    return -1;
                }

                offset -= 1;
            }
        }

        for (BluetoothGattService included : service.getIncludedServices()) {
            offset = readServiceCharacteristics(included, offset);
            if (offset == -1) {
                return offset;
            }
        }

        return offset;
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        int offset = stateNr;

        for (BluetoothGattService service : getBluetoothGattServices()) {
            offset = readServiceCharacteristics(service, offset);
            if (offset == -1) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        for (BluetoothGattService service : getBluetoothGattServices()) {
            logService(service, false);
        }

        setBtStatus(BT_STATUS_CODE.BT_CONNECTION_LOST);
        disconnect(false);
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        return false;
    }
}
