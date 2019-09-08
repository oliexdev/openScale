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

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.UUID;

public class BluetoothGattUuid {
    private static final String STANDARD_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    public static final UUID fromShortCode(long code) {
        return UUID.fromString(String.format("%08x%s", code, STANDARD_SUFFIX));
    }

    public static final String prettyPrint(UUID uuid) {
        if (uuid == null) {
            return "null";
        }

        String str = uuid.toString();

        if (str.endsWith(STANDARD_SUFFIX)) {
            String code = str.substring(0, str.length() - STANDARD_SUFFIX.length());
            if (code.startsWith("0000")) {
                code = code.substring(4);
            }
            str = "0x" + code;
        }

        for (Field field : BluetoothGattUuid.class.getFields()) {
            try {
                if (uuid.equals(field.get(null))) {
                    String name = field.getName();
                    name = name.substring(name.indexOf('_') + 1);
                    str = String.format("%s \"%s\"", str,
                            name.replace('_', ' ').toLowerCase(Locale.US));
                    break;
                }
            }
            catch (IllegalAccessException e) {
                // Ignore
            }
        }

        return str;
    }

    // https://www.bluetooth.com/specifications/gatt/services
    public static final UUID SERVICE_BODY_COMPOSITION = fromShortCode(0x181b);
    public static final UUID SERVICE_DEVICE_INFORMATION = fromShortCode(0x180a);
    public static final UUID SERVICE_GENERIC_ACCESS = fromShortCode(0x1800);
    public static final UUID SERVICE_GENERIC_ATTRIBUTE = fromShortCode(0x1801);
    public static final UUID SERVICE_WEIGHT_SCALE = fromShortCode(0x181d);
    public static final UUID SERVICE_CURRENT_TIME = fromShortCode(0x1805);
    public static final UUID SERVICE_USER_DATA = fromShortCode(0x181C);
    public static final UUID SERVICE_BATTERY_LEVEL = fromShortCode(0x180F);

    // https://www.bluetooth.com/specifications/gatt/characteristics
    public static final UUID CHARACTERISTIC_APPEARANCE = fromShortCode(0x2a01);
    public static final UUID CHARACTERISTIC_BODY_COMPOSITION_MEASUREMENT = fromShortCode(0x2a9c);
    public static final UUID CHARACTERISTIC_CURRENT_TIME = fromShortCode(0x2a2b);
    public static final UUID CHARACTERISTIC_DEVICE_NAME = fromShortCode(0x2a00);
    public static final UUID CHARACTERISTIC_FIRMWARE_REVISION_STRING = fromShortCode(0x2a26);
    public static final UUID CHARACTERISTIC_HARDWARE_REVISION_STRING = fromShortCode(0x2a27);
    public static final UUID CHARACTERISTIC_IEEE_11073_20601_REGULATORY_CERTIFICATION_DATA_LIST = fromShortCode(0x2a2a);
    public static final UUID CHARACTERISTIC_MANUFACTURER_NAME_STRING = fromShortCode(0x2a29);
    public static final UUID CHARACTERISTIC_MODEL_NUMBER_STRING = fromShortCode(0x2a24);
    public static final UUID CHARACTERISTIC_PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS = fromShortCode(0x2a04);
    public static final UUID CHARACTERISTIC_PERIPHERAL_PRIVACY_FLAG = fromShortCode(0x2a02);
    public static final UUID CHARACTERISTIC_PNP_ID = fromShortCode(0x2a50);
    public static final UUID CHARACTERISTIC_RECONNECTION_ADDRESS = fromShortCode(0x2a03);
    public static final UUID CHARACTERISTIC_SERIAL_NUMBER_STRING = fromShortCode(0x2a25);
    public static final UUID CHARACTERISTIC_SERVICE_CHANGED = fromShortCode(0x2a05);
    public static final UUID CHARACTERISTIC_SOFTWARE_REVISION_STRING = fromShortCode(0x2a28);
    public static final UUID CHARACTERISTIC_SYSTEM_ID = fromShortCode(0x2a23);
    public static final UUID CHARACTERISTIC_WEIGHT_MEASUREMENT = fromShortCode(0x2a9d);
    public static final UUID CHARACTERISTIC_BATTERY_LEVEL = fromShortCode(0x2A19);
    public static final UUID CHARACTERISTIC_CHANGE_INCREMENT = fromShortCode(0x2a99);
    public static final UUID CHARACTERISTIC_USER_CONTROL_POINT = fromShortCode(0x2A9F);
    public static final UUID CHARACTERISTIC_USER_AGE = fromShortCode(0x2A80);
    public static final UUID CHARACTERISTIC_USER_GENDER = fromShortCode(0x2A8C);
    public static final UUID CHARACTERISTIC_USER_HEIGHT = fromShortCode(0x2A8E);

    // https://www.bluetooth.com/specifications/gatt/descriptors
    public static final UUID DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION = fromShortCode(0x2902);
    public static final UUID DESCRIPTOR_CHARACTERISTIC_USER_DESCRIPTION = fromShortCode(0x2901);
}
