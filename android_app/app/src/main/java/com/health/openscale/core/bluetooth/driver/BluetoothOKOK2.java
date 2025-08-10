/* Copyright (C) 2024  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.core.bluetooth.driver;

import static com.health.openscale.core.utils.Converters.WeightUnit.LB;
import static com.health.openscale.core.utils.Converters.WeightUnit.ST;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import timber.log.Timber;

public class BluetoothOKOK2 extends BluetoothCommunication {
    private static final int IDX_WEIGHT_MSB = 0;
    private static final int IDX_WEIGHT_LSB = 1;
    private static final int IDX_IMPEDANCE_MSB = 2;
    private static final int IDX_IMPEDANCE_LSB = 3;
    private static final int IDX_PRODUCTID_MSB = 4;
    private static final int IDX_PRODUCTID_LSB = 5;
    private static final int IDX_ATTRIB = 6;
    private static final int IDX_MAC_1 = 7;
    private static final int IDX_MAC_2 = 8;
    private static final int IDX_MAC_3 = 9;
    private static final int IDX_MAC_4 = 10;
    private static final int IDX_MAC_5 = 11;
    private static final int IDX_MAC_6 = 12;

    private static final int UNIT_KG = 0;
    private static final int UNIT_LB = 2;
    private static final int UNIT_STLB = 3;

    private BluetoothCentralManager central;
    private String mMacAddress;
    private float mLastWeight = 0f;

    public BluetoothOKOK2(Context context, String deviceName) {
        super(context, deviceName);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    public static String convertNoNameToDeviceName(SparseArray<byte[]> manufacturerSpecificData) {
        int vendorIndex = -1;
        for (int i = 0; i < manufacturerSpecificData.size(); i++) {
            int vendorId = manufacturerSpecificData.keyAt(i);
            if ((vendorId & 0xff) == 0xc0) { // 0x00c0-->0xffc0
                vendorIndex = vendorId;
            }
        }
        if (vendorIndex == -1) {
            return null;
        }

        return "NoName OkOk";
    }

    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            int vendorIndex = -1;
            for (int i = 0; i < manufacturerSpecificData.size(); i++) {
                int vendorId = manufacturerSpecificData.keyAt(i);
                if ((vendorId & 0xff) == 0xc0) { // 0x00c0-->0xffc0
                    vendorIndex = vendorId;
                    break;
                }
            }
            if (vendorIndex == -1) {
                return;
            }
            byte[] data = manufacturerSpecificData.get(vendorIndex);

            StringBuilder sb = new StringBuilder(data.length * 3);
            for (byte b : data) {
                sb.append(String.format("%02x ", b));
            }
            Timber.d("manufacturerSpecificData: [VID=%04x] %s", vendorIndex, sb.toString());

            if (data[IDX_MAC_1] != (byte) ((Character.digit(mMacAddress.charAt(0), 16) << 4) + Character.digit(mMacAddress.charAt(1), 16))
            || data[IDX_MAC_2] != (byte) ((Character.digit(mMacAddress.charAt(3), 16) << 4) + Character.digit(mMacAddress.charAt(4), 16))
            || data[IDX_MAC_3] != (byte) ((Character.digit(mMacAddress.charAt(6), 16) << 4) + Character.digit(mMacAddress.charAt(7), 16))
            || data[IDX_MAC_4] != (byte) ((Character.digit(mMacAddress.charAt(9), 16) << 4) + Character.digit(mMacAddress.charAt(10), 16))
            || data[IDX_MAC_5] != (byte) ((Character.digit(mMacAddress.charAt(12), 16) << 4) + Character.digit(mMacAddress.charAt(13), 16))
            || data[IDX_MAC_6] != (byte) ((Character.digit(mMacAddress.charAt(15), 16) << 4) + Character.digit(mMacAddress.charAt(16), 16)))
                return;

            if ((data[IDX_ATTRIB] & 1) == 0) // in progress
                return;

            float divider = 10f;
            switch ((data[IDX_ATTRIB] >> 1) & 3) {
                case 0:
                    divider = 10f;
                    break;
                case 1:
                    divider = 1f;
                    break;
                case 2:
                    divider = 100f;
                    break;
            }

            float weight = 0f;
            switch ((data[IDX_ATTRIB] >> 3) & 3) {
                case UNIT_KG: {
                    float val = ((data[IDX_WEIGHT_MSB] & 0xff) << 8) | (data[IDX_WEIGHT_LSB] & 0xff);
                    weight = val / divider;
                    break;
                }
                case UNIT_LB: {
                    float val = ((data[IDX_WEIGHT_MSB] & 0xff) << 8) | (data[IDX_WEIGHT_LSB] & 0xff);
                    weight = Converters.toKilogram(val / divider, LB);
                    break;
                }
                case UNIT_STLB: {
                    float val = data[IDX_WEIGHT_MSB] /*ST*/ + data[IDX_WEIGHT_LSB] /*LB*/ / divider / 14f;
                    weight = Converters.toKilogram(val, ST);
                    break;
                }
            }

            if (mLastWeight != weight) {
                ScaleMeasurement entry = new ScaleMeasurement();
                entry.setWeight(weight);
                addScaleMeasurement(entry);
                mLastWeight = weight;
                // disconnect();
            }
        }
    };

    @Override
    public void connect(String macAddress) {
        mMacAddress = macAddress;
        List<ScanFilter> filters = new LinkedList<>();

        byte[] data = new byte[13];
        data[IDX_MAC_1] = (byte) ((Character.digit(macAddress.charAt(0), 16) << 4) + Character.digit(macAddress.charAt(1), 16));
        data[IDX_MAC_2] = (byte) ((Character.digit(macAddress.charAt(3), 16) << 4) + Character.digit(macAddress.charAt(4), 16));
        data[IDX_MAC_3] = (byte) ((Character.digit(macAddress.charAt(6), 16) << 4) + Character.digit(macAddress.charAt(7), 16));
        data[IDX_MAC_4] = (byte) ((Character.digit(macAddress.charAt(9), 16) << 4) + Character.digit(macAddress.charAt(10), 16));
        data[IDX_MAC_5] = (byte) ((Character.digit(macAddress.charAt(12), 16) << 4) + Character.digit(macAddress.charAt(13), 16));
        data[IDX_MAC_6] = (byte) ((Character.digit(macAddress.charAt(15), 16) << 4) + Character.digit(macAddress.charAt(16), 16));
        byte[] mask = new byte[13];
        mask[IDX_MAC_1] = mask[IDX_MAC_2] = mask[IDX_MAC_3] = mask[IDX_MAC_4] = mask[IDX_MAC_5] = mask[IDX_MAC_6] = (byte) 0xff;

        for (int i = 0x00; i <= 0xff; i++) {
            ScanFilter.Builder b = new ScanFilter.Builder();
            b.setDeviceAddress(macAddress);
            b.setManufacturerData((i << 8) | 0xc0, data, mask);
            filters.add(b.build());
        }

        central.scanForPeripheralsUsingFilters(filters);
    }

    @Override
    public void disconnect() {
        if (central != null)
            central.stopScan();
        central = null;
        super.disconnect();
    }

    @Override
    public String driverName() {
        return "OKOK (nameless)";
    }

    public static String driverId() {
        return "okok2";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}
