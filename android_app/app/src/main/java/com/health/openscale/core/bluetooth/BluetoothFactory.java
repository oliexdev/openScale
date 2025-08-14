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
import android.util.SparseArray;

import java.util.Locale;

import com.health.openscale.core.bluetooth.driver.*;

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
                || name.startsWith("RT-Libra-B".toLowerCase(Locale.US))
                || name.startsWith("RT-Libra-W".toLowerCase(Locale.US))
                || name.startsWith("Libra-B".toLowerCase(Locale.US))
                || name.startsWith("Libra-W".toLowerCase(Locale.US))) {
            return new BluetoothBeurerSanitas(context, deviceName);
        }
        if (name.startsWith("BEURER BF710".toLowerCase(Locale.US))
                || name.equals("BF700".toLowerCase(Locale.US))) {
            return new BluetoothBeurerSanitas(context, deviceName);
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
        if (deviceName.startsWith("013197") || deviceName.startsWith("013198") || deviceName.startsWith("0202B6")) {
            return new BluetoothMedisanaBS44x(context, deviceName);
        }

        //BS430
        if (deviceName.startsWith("0203B")) {
            return new BluetoothMedisanaBS44x(context, deviceName);
        }

        if (deviceName.startsWith("SWAN") || name.equals("icomon".toLowerCase(Locale.US)) || name.equals("YG".toLowerCase(Locale.US))) {
            return new BluetoothMGB(context);
        }
        if (name.equals("MI_SCALE".toLowerCase(Locale.US)) || name.equals("MI SCALE2".toLowerCase(Locale.US))) {
            return new BluetoothMiScale(context);
        }
        if (name.equals("MIBCS".toLowerCase(Locale.US)) || name.equals("MIBFS".toLowerCase(Locale.US))) {
            return new BluetoothMiScale2(context);
        }
        if (name.equals("Health Scale".toLowerCase(Locale.US))) {
            return new BluetoothOneByone(context);
        }
        if(name.equals("1byone scale".toLowerCase(Locale.US))) {
            return new BluetoothOneByoneNew(context);
        }

        if (name.equals("SENSSUN FAT".toLowerCase(Locale.US))) {
            return new BluetoothSenssun(context);
        }
        if (name.startsWith("SANITAS SBF70".toLowerCase(Locale.US)) || name.startsWith("sbf75") || name.startsWith("AICDSCALE1".toLowerCase(Locale.US))) {
            return new BluetoothBeurerSanitas(context, deviceName);
        }
        if (deviceName.startsWith("YUNMAI-SIGNAL") || deviceName.startsWith("YUNMAI-ISM")) {
            return new BluetoothYunmaiSE_Mini(context, deviceName);
        }
        if (deviceName.startsWith("YUNMAI-ISSE")) {
            return new BluetoothYunmaiSE_Mini(context, deviceName);
        }
        if (deviceName.startsWith("01257B") || deviceName.startsWith("11257B")) {
            // Trisa Body Analyze 4.0, aka Transtek GBF-1257-B
            return new BluetoothTrisaBodyAnalyze(context);
        }
        if (deviceName.equals("000FatScale01") || deviceName.equals("000FatScale02")
                || deviceName.equals("042FatScale01")) {
            return new BluetoothInlife(context);
        }
        if (deviceName.startsWith("QN-Scale")) {
            return new BluetoothQNScale(context);
        }
        if (deviceName.startsWith("Shape200") || deviceName.startsWith("Shape100") || deviceName.startsWith("Shape50") || deviceName.startsWith("Style100")) {
            return new BluetoothSoehnle(context);
        }
        if (deviceName.equals("Hoffen BS-8107")) {
            return new BluetoothHoffenBBS8107(context);
        }
        if (deviceName.equals("ADV") || deviceName.equals("Chipsea-BLE")) {
            return new BluetoothOKOK(context);
        }
        if (deviceName.equals("NoName OkOk")) {
            return new BluetoothOKOK2(context);
        }
        if (deviceName.equals("BF105") || deviceName.equals("BF720")) {
            return new BluetoothBeurerBF105(context);
        }
        if (deviceName.equals("BF500")) {
            return new BluetoothBeurerBF500(context, deviceName);
        }
        if (deviceName.equals("BF600") || deviceName.equals("BF850")) {
            return new BluetoothBeurerBF600(context, deviceName);
        }
        if (deviceName.equals("SBF77") || deviceName.equals("SBF76") || deviceName.equals("BF950")) {
            return new BluetoothBeurerBF950(context, deviceName);
        }
        if (deviceName.equals("SBF72") || deviceName.equals("BF915") || deviceName.equals("SBF73")) {
            return new BluetoothSanitasSBF72(context, deviceName);
        }
        if (deviceName.equals("Weight Scale")) {
            return new BluetoothSinocare(context);
        }
        if (deviceName.equals("CH100")) {
            return new BluetoothHuaweiAH100(context);
        }
        if (deviceName.equals("ES-26BB-B")){
            return new BluetoothES26BBB(context);
        }
        if (deviceName.equals("Yoda1")){
            return new BluetoothYoda1Scale(context);
        }
        if (deviceName.equals("AAA002") || deviceName.equals("AAA007") || deviceName.equals("AAA013")) {
            return new BluetoothBroadcastScale(context);
        }
        if (deviceName.equals("AE BS-06")) {
            return new BluetoothActiveEraBF06(context);
        }
        if (deviceName.equals("Renpho-Scale")) {
            /* Driver for Renpho ES-WBE28, which has device name of "Renpho-Scale".
               "Renpho-Scale" is quite generic, not sure if other Renpho scales with different
               protocol match this name.
             */
            return new BluetoothESWBE28(context);
        }
        if(deviceName.equals("ES-CS20M")){
            return new BluetoothESCS20M(context);
        }
        return null;
    }

    public static String convertNoNameToDeviceName(SparseArray<byte[]> manufacturerSpecificData) {
        String deviceName = null;
        deviceName = BluetoothOKOK2.convertNoNameToDeviceName(manufacturerSpecificData);

        return deviceName;
    }
}
