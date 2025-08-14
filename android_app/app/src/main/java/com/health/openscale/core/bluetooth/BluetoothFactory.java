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

    public static String getDriverIdFromDeviceName(String deviceName) {
        final String name = deviceName.toLowerCase(Locale.US);

        if (name.startsWith("BEURER BF700".toLowerCase(Locale.US))
                || name.startsWith("BEURER BF800".toLowerCase(Locale.US))
                || name.startsWith("BF-800".toLowerCase(Locale.US))
                || name.startsWith("BF-700".toLowerCase(Locale.US))
                || name.startsWith("RT-Libra-B".toLowerCase(Locale.US))
                || name.startsWith("RT-Libra-W".toLowerCase(Locale.US))
                || name.startsWith("Libra-B".toLowerCase(Locale.US))
                || name.startsWith("Libra-W".toLowerCase(Locale.US))) {
            return BluetoothBeurerSanitas.driverId();
        }
        if (name.startsWith("BEURER BF710".toLowerCase(Locale.US))
                || name.equals("BF700".toLowerCase(Locale.US))) {
            return BluetoothBeurerSanitas.driverId();
        }
        if (name.equals("openScale".toLowerCase(Locale.US))) {
            return BluetoothCustomOpenScale.driverId();
        }
        if (name.equals("Mengii".toLowerCase(Locale.US))) {
            return BluetoothDigooDGSO38H.driverId();
        }
        if (name.equals("Electronic Scale".toLowerCase(Locale.US))) {
            return BluetoothExcelvanCF36xBLE.driverId();
        }
        if (name.equals("VScale".toLowerCase(Locale.US))) {
            return BluetoothExingtechY1.driverId();
        }
        if (name.equals("YunChen".toLowerCase(Locale.US))) {
            return BluetoothHesley.driverId();
        }
        if (deviceName.startsWith("iHealth HS3")) {
            return BluetoothIhealthHS3.driverId();
        }
        // BS444 || BS440 || BS430
        if (deviceName.startsWith("013197") || deviceName.startsWith("013198") || deviceName.startsWith("0202B6") || deviceName.startsWith("0203B")) {
            return BluetoothMedisanaBS44x.driverId();
        }

        if (deviceName.startsWith("SWAN") || name.equals("icomon".toLowerCase(Locale.US)) || name.equals("YG".toLowerCase(Locale.US))) {
            return BluetoothMGB.driverId();
        }
        if (name.equals("MI_SCALE".toLowerCase(Locale.US)) || name.equals("MI SCALE2".toLowerCase(Locale.US))) {
            return BluetoothMiScale.driverId();
        }
        if (name.equals("MIBCS".toLowerCase(Locale.US)) || name.equals("MIBFS".toLowerCase(Locale.US))) {
            return BluetoothMiScale2.driverId();
        }
        if (name.equals("Health Scale".toLowerCase(Locale.US))) {
            return BluetoothOneByone.driverId();
        }
        if(name.equals("1byone scale".toLowerCase(Locale.US))) {
            return BluetoothOneByoneNew.driverId();
        }
        if (name.equals("SENSSUN FAT".toLowerCase(Locale.US))) {
            return BluetoothSenssun.driverId();
        }
        if (name.startsWith("SANITAS SBF70".toLowerCase(Locale.US)) || name.startsWith("sbf75") || name.startsWith("AICDSCALE1".toLowerCase(Locale.US))) {
            return BluetoothBeurerSanitas.driverId();
        }
        if (deviceName.startsWith("YUNMAI-SIGNAL") || deviceName.startsWith("YUNMAI-ISM") || deviceName.startsWith("YUNMAI-ISSE")) {
            return BluetoothYunmaiSE_Mini.driverId();
        }
        if (deviceName.startsWith("01257B") || deviceName.startsWith("11257B")) {
            // Trisa Body Analyze 4.0, aka Transtek GBF-1257-B
            return BluetoothTrisaBodyAnalyze.driverId();
        }
        if (deviceName.equals("000FatScale01") || deviceName.equals("000FatScale02")
                || deviceName.equals("042FatScale01")) {
            return BluetoothInlife.driverId();
        }
        if (deviceName.startsWith("QN-Scale")) {
            return BluetoothQNScale.driverId();
        }
        if (deviceName.startsWith("Shape200") || deviceName.startsWith("Shape100") || deviceName.startsWith("Shape50") || deviceName.startsWith("Style100")) {
            return BluetoothSoehnle.driverId();
        }
        if (deviceName.equals("Hoffen BS-8107")) {
            return BluetoothHoffenBBS8107.driverId();
        }
        if (deviceName.equals("ADV") || deviceName.equals("Chipsea-BLE")) {
            return BluetoothOKOK.driverId();
        }
        if (deviceName.equals("NoName OkOk")) {
            return BluetoothOKOK2.driverId();
        }
        if (deviceName.equals("BF105") || deviceName.equals("BF720")) {
            return BluetoothBeurerBF105.driverId();
        }
        if (deviceName.equals("BF500")) {
            return BluetoothBeurerBF500.driverId();
        }
        if (deviceName.equals("BF600") || deviceName.equals("BF850")) {
            return BluetoothBeurerBF600.driverId();
        }
        if (deviceName.equals("SBF77") || deviceName.equals("SBF76") || deviceName.equals("BF950")) {
            return BluetoothBeurerBF950.driverId();
        }
        if (deviceName.equals("SBF72") || deviceName.equals("BF915") || deviceName.equals("SBF73")) {
            return BluetoothSanitasSBF72.driverId();
        }
        if (deviceName.equals("Weight Scale")) {
            return BluetoothSinocare.driverId();
        }
        if (deviceName.equals("CH100")) {
            return BluetoothHuaweiAH100.driverId();
        }
        if (deviceName.equals("ES-26BB-B")){
            return BluetoothES26BBB.driverId();
        }
        if (deviceName.equals("Yoda1")){
            return BluetoothYoda1Scale.driverId();
        }
        if (deviceName.equals("AAA002") || deviceName.equals("AAA007") || deviceName.equals("AAA013")) {
            return BluetoothBroadcastScale.driverId();
        }
        if (deviceName.equals("AE BS-06")) {
            return BluetoothActiveEraBF06.driverId();
        }
        if (deviceName.equals("Renpho-Scale")) {
            /* Driver for Renpho ES-WBE28, which has device name of "Renpho-Scale".
               "Renpho-Scale" is quite generic, not sure if other Renpho scales with different
               protocol match this name.
             */
            return BluetoothESWBE28.driverId();
        }
        if(deviceName.equals("ES-CS20M")){
            return BluetoothESCS20M.driverId();
        }
        return null;
    }

    public static BluetoothCommunication createDriverById(Context context, String targetDriverId) {
        if (targetDriverId == null) return null;
        
        // Check each driver class by calling their static driverId() method
        if (targetDriverId.equals(BluetoothActiveEraBF06.driverId())) {
            return new BluetoothActiveEraBF06(context);
        }
        if (targetDriverId.equals(BluetoothBeurerBF105.driverId())) {
            return new BluetoothBeurerBF105(context);
        }
        if (targetDriverId.equals(BluetoothBeurerBF500.driverId())) {
            return new BluetoothBeurerBF500(context, "BF500");
        }
        if (targetDriverId.equals(BluetoothBeurerBF600.driverId())) {
            return new BluetoothBeurerBF600(context, "BF600");
        }
        if (targetDriverId.equals(BluetoothBeurerBF950.driverId())) {
            return new BluetoothBeurerBF950(context, "BF950");
        }
        if (targetDriverId.equals(BluetoothBeurerSanitas.driverId())) {
            // Note: This driver requires DeviceType parameter - using default for now
            return new BluetoothBeurerSanitas(context, BluetoothBeurerSanitas.DeviceType.BEURER_BF700_800_RT_LIBRA);
        }
        if (targetDriverId.equals(BluetoothBroadcastScale.driverId())) {
            return new BluetoothBroadcastScale(context);
        }
        if (targetDriverId.equals(BluetoothCustomOpenScale.driverId())) {
            return new BluetoothCustomOpenScale(context);
        }
        if (targetDriverId.equals(BluetoothDebug.driverId())) {
            return new BluetoothDebug(context);
        }
        if (targetDriverId.equals(BluetoothDigooDGSO38H.driverId())) {
            return new BluetoothDigooDGSO38H(context);
        }
        if (targetDriverId.equals(BluetoothES26BBB.driverId())) {
            return new BluetoothES26BBB(context);
        }
        if (targetDriverId.equals(BluetoothESCS20M.driverId())) {
            return new BluetoothESCS20M(context);
        }
        if (targetDriverId.equals(BluetoothESWBE28.driverId())) {
            return new BluetoothESWBE28(context);
        }
        if (targetDriverId.equals(BluetoothExcelvanCF36xBLE.driverId())) {
            return new BluetoothExcelvanCF36xBLE(context);
        }
        if (targetDriverId.equals(BluetoothExingtechY1.driverId())) {
            return new BluetoothExingtechY1(context);
        }
        if (targetDriverId.equals(BluetoothHesley.driverId())) {
            return new BluetoothHesley(context);
        }
        if (targetDriverId.equals(BluetoothHoffenBBS8107.driverId())) {
            return new BluetoothHoffenBBS8107(context);
        }
        if (targetDriverId.equals(BluetoothHuaweiAH100.driverId())) {
            return new BluetoothHuaweiAH100(context);
        }
        if (targetDriverId.equals(BluetoothIhealthHS3.driverId())) {
            return new BluetoothIhealthHS3(context);
        }
        if (targetDriverId.equals(BluetoothInlife.driverId())) {
            return new BluetoothInlife(context);
        }
        if (targetDriverId.equals(BluetoothMGB.driverId())) {
            return new BluetoothMGB(context);
        }
        if (targetDriverId.equals(BluetoothMedisanaBS44x.driverId())) {
            // Note: This driver requires boolean parameter - using default for now
            return new BluetoothMedisanaBS44x(context, true);
        }
        if (targetDriverId.equals(BluetoothMiScale.driverId())) {
            return new BluetoothMiScale(context);
        }
        if (targetDriverId.equals(BluetoothMiScale2.driverId())) {
            return new BluetoothMiScale2(context);
        }
        if (targetDriverId.equals(BluetoothOKOK.driverId())) {
            return new BluetoothOKOK(context);
        }
        if (targetDriverId.equals(BluetoothOKOK2.driverId())) {
            return new BluetoothOKOK2(context);
        }
        if (targetDriverId.equals(BluetoothOneByone.driverId())) {
            return new BluetoothOneByone(context);
        }
        if (targetDriverId.equals(BluetoothOneByoneNew.driverId())) {
            return new BluetoothOneByoneNew(context);
        }
        if (targetDriverId.equals(BluetoothQNScale.driverId())) {
            return new BluetoothQNScale(context);
        }
        if (targetDriverId.equals(BluetoothSanitasSBF72.driverId())) {
            return new BluetoothSanitasSBF72(context, "SBF72");
        }
        if (targetDriverId.equals(BluetoothSenssun.driverId())) {
            return new BluetoothSenssun(context);
        }
        if (targetDriverId.equals(BluetoothSinocare.driverId())) {
            return new BluetoothSinocare(context);
        }
        if (targetDriverId.equals(BluetoothSoehnle.driverId())) {
            return new BluetoothSoehnle(context);
        }
        if (targetDriverId.equals(BluetoothStandardWeightProfile.driverId())) {
            return new BluetoothStandardWeightProfile(context);
        }
        if (targetDriverId.equals(BluetoothTrisaBodyAnalyze.driverId())) {
            return new BluetoothTrisaBodyAnalyze(context);
        }
        if (targetDriverId.equals(BluetoothYoda1Scale.driverId())) {
            return new BluetoothYoda1Scale(context);
        }
        if (targetDriverId.equals(BluetoothYunmaiSE_Mini.driverId())) {
            // Note: This driver requires boolean parameter - using default for now
            return new BluetoothYunmaiSE_Mini(context, true);
        }
        
        return null;
    }

    public static BluetoothCommunication createDeviceDriver(Context context, String deviceName) {
        String driverId = getDriverIdFromDeviceName(deviceName);
        if (driverId == null) return null;
        return createDriverById(context, driverId);
    }

    public static String convertNoNameToDeviceName(SparseArray<byte[]> manufacturerSpecificData) {
        String deviceName = null;
        deviceName = BluetoothOKOK2.convertNoNameToDeviceName(manufacturerSpecificData);

        return deviceName;
    }
}
