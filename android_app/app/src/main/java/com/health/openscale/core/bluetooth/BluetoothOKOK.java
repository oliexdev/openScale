package com.health.openscale.core.bluetooth;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import timber.log.Timber;

public class BluetoothOKOK extends BluetoothCommunication {
    private static final int MANUFACTURER_DATA_ID = 0x20ca; // 16-bit little endian "header" 0xca 0x20
    private static final int IDX_FINAL = 6;
    private static final int IDX_WEIGHT_MSB = 8;
    private static final int IDX_WEIGHT_LSB = 9;
    private static final int IDX_IMPEDANCE_MSB = 10;
    private static final int IDX_IMPEDANCE_LSB = 11;
    private static final int IDX_CHECKSUM = 12;

    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] data = scanResult.getScanRecord().getManufacturerSpecificData(MANUFACTURER_DATA_ID);
            float divider = 10.0f;
            byte checksum = 0x20; // Version field is part of the checksum, but not in array
            if (data == null || data.length != 19)
                return;
            if ((data[IDX_FINAL] & 1) == 0)
                return;
            for (int i = 0; i < IDX_CHECKSUM; i++)
                checksum ^= data[i];
            if (data[IDX_CHECKSUM] != checksum) {
                Timber.d("Checksum error, got %x, expected %x", data[12] & 0xff, checksum & 0xff);
                return;
            }
            if ((data[IDX_FINAL] & 4) == 4)
                divider = 100.0f;
            int weight = data[IDX_WEIGHT_MSB] & 0xff;
            weight = weight << 8 | (data[IDX_WEIGHT_LSB] & 0xff);
            int impedance = data[IDX_IMPEDANCE_MSB] & 0xff;
            impedance = impedance << 8 | (data[IDX_IMPEDANCE_LSB] & 0xff);
            Timber.d("Got weight: %f and impedance %f", weight / divider, impedance / 10f);
            ScaleMeasurement entry = new ScaleMeasurement();
            entry.setWeight(weight / divider);
            addScaleMeasurement(entry);
            disconnect();
        }
    };

    public BluetoothOKOK(Context context)
    {
        super(context);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    @Override
    public String driverName() {
        return "OKOK";
    }

    @Override
    public void connect(String macAddress) {
        Timber.d("Mac address: %s", macAddress);
        List<ScanFilter> filters = new LinkedList<ScanFilter>();
        ScanFilter.Builder b = new ScanFilter.Builder();
        b.setDeviceAddress(macAddress);
        b.setDeviceName("ADV");
        b.setManufacturerData(MANUFACTURER_DATA_ID, null, null);
        filters.add(b.build());
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
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}
