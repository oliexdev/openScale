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

public class BluetoothSinocare extends BluetoothCommunication {
    private static final int MANUFACTURER_DATA_ID = 0xff64; // 16-bit little endian "header"

    private static final int WEIGHT_MSB = 10;
    private static final int WEIGHT_LSB = 9;
    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
        SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] data = manufacturerSpecificData.get(MANUFACTURER_DATA_ID);//maybe FF64? 64FF?
            float divider = 100.0f;
            int weight = data[WEIGHT_MSB] & 0xff;
            weight = weight << 8 | (data[WEIGHT_LSB] & 0xff);
        }
    };

    public BluetoothSinocare(Context context)
    {
        super(context);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    @Override
    public String driverName() {
        return "Sinocare";
    }

    @Override
    public void connect(String macAddress) {
        Timber.d("Mac address: %s", macAddress);
        List<ScanFilter> filters = new LinkedList<ScanFilter>();

        ScanFilter.Builder b = new ScanFilter.Builder();
        b.setDeviceAddress(macAddress);


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
