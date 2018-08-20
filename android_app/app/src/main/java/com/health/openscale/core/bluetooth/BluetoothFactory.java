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

import android.content.Context;

import java.util.Locale;

public class BluetoothFactory {
    public static BluetoothCommunication createDebugDriver(Context context) {
        return new BluetoothDebug(context);
    }

    public static BluetoothCommunication createDeviceDriver(Context context, String deviceName) {
        final String name = deviceName.toLowerCase(Locale.US);

        if (name.startsWith("BEURER BF700".toLowerCase(Locale.US))
                || name.startsWith("BEURER BF800".toLowerCase(Locale.US))
                || name.startsWith("BF-800".toLowerCase(Locale.US))
                || name.startsWith("BF-700".toLowerCase(Locale.US))
                || name.startsWith("RT-Libra-B".toLowerCase(Locale.US))) {
            return new BluetoothBeurerSanitas(context, BluetoothBeurerSanitas.DeviceType.BEURER_BF700_800_RT_LIBRA);
        }
        if (name.startsWith("BEURER BF710".toLowerCase(Locale.US))) {
            return new BluetoothBeurerSanitas(context, BluetoothBeurerSanitas.DeviceType.BEURER_BF710);
        }
        if (name.equals("openScale".toLowerCase(Locale.US))) {
            return new BluetoothCustomOpenScale(context);
        }
        if (name.equals("Mengii".toLowerCase(Locale.US))) {
            return new BluetoothDigooDGSO38H(context);
        }
        if (name.equals("Electronic Scale".toLowerCase(Locale.US))) {
            return new BluetoothExcelvanCF36xBLE(context);
        }
        if (name.equals("VScale".toLowerCase(Locale.US))) {
            return new BluetoothExingtechY1(context);
        }
        if (name.equals("YunChen".toLowerCase(Locale.US))) {
            return new BluetoothHesley(context);
        }
        if (deviceName.startsWith("iHealth HS3")) {
            return new BluetoothIhealthHS3(context);
        }
        // BS444 || BS440
        if (deviceName.startsWith("013197") || deviceName.startsWith("0202B6")) {
            return new BluetoothMedisanaBS44x(context);
        }
        if (deviceName.startsWith("SWAN") || name.equals("icomon".toLowerCase(Locale.US))) {
            return new BluetoothMGB(context);
        }
        if (name.equals("MI_SCALE".toLowerCase(Locale.US))) {
            return new BluetoothMiScale(context);
        }
        if (name.equals("MIBCS".toLowerCase(Locale.US))) {
            return new BluetoothMiScale2(context);
        }
        if (name.equals("Health Scale".toLowerCase(Locale.US))) {
            return new BluetoothOneByone(context);
        }
        if (name.startsWith("SANITAS SBF70".toLowerCase(Locale.US)) || name.startsWith("sbf75")) {
            return new BluetoothBeurerSanitas(context, BluetoothBeurerSanitas.DeviceType.SANITAS_SBF70_70);
        }
        if (deviceName.startsWith("YUNMAI-SIGNAL-M") || deviceName.startsWith("YUNMAI-ISM2-")) {
            return new BluetoothYunmaiSE_Mini(context, true);
        }
        if (deviceName.startsWith("YUNMAI-ISSE")) {
            return new BluetoothYunmaiSE_Mini(context, false);
        }
        return null;
    }
}
