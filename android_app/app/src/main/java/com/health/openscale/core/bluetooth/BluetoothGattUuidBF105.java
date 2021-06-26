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

public class BluetoothGattUuidBF105 extends BluetoothGattUuid {

    public static final UUID SERVICE_BF105_CUSTOM = fromShortCode(0xffff);
    public static final UUID SERVICE_BF105_IMG = fromShortCode(0xffc0);

    public static final UUID CHARACTERISTIC_SCALE_SETTINGS = fromShortCode(0x0000);
    public static final UUID CHARACTERISTIC_USER_LIST = fromShortCode(0x0001);
    public static final UUID CHARACTERISTIC_INITIALS = fromShortCode(0x0002);
    public static final UUID CHARACTERISTIC_TARGET_WEIGHT = fromShortCode(0x0003);
    public static final UUID CHARACTERISTIC_ACTIVITY_LEVEL = fromShortCode(0x0004);
    public static final UUID CHARACTERISTIC_REFER_WEIGHT_BF = fromShortCode(0x000b);
    public static final UUID CHARACTERISTIC_BT_MODULE = fromShortCode(0x0005);
    public static final UUID CHARACTERISTIC_TAKE_MEASUREMENT = fromShortCode(0x0006);
    public static final UUID CHARACTERISTIC_TAKE_GUEST_MEASUREMENT = fromShortCode(0x0007);
    public static final UUID CHARACTERISTIC_BEURER_I = fromShortCode(0x0008);
    public static final UUID CHARACTERISTIC_UPPER_LOWER_BODY = CHARACTERISTIC_BEURER_I;
    public static final UUID CHARACTERISTIC_BEURER_II = fromShortCode(0x0009);
    public static final UUID CHARACTERISTIC_BEURER_III = fromShortCode(0x000a);
    public static final UUID CHARACTERISTIC_IMG_IDENTIFY = fromShortCode(0xffc1);
    public static final UUID CHARACTERISTIC_IMG_BLOCK = fromShortCode(0xffc2);

}
