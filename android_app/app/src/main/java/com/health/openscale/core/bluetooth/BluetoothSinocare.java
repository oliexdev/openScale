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

    // the number of consecutive times the same weight should be seen before it is considered "final"
    private static final int WEIGHT_TRIGGER_THRESHOLD = 9;

    //these values are used to check for whether the scale's weight reading has leveled out since
    // the scale doesnt appear to communicate when it has a solid reading.
    private static int last_seen_weight = 0;
    private static int last_wait_repeat_count = 0;

    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
        SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] data = manufacturerSpecificData.get(MANUFACTURER_DATA_ID);//maybe FF64? 64FF?
            float divider = 100.0f;
            int weight = data[WEIGHT_MSB] & 0xff;
            weight = weight << 8 | (data[WEIGHT_LSB] & 0xff);
            if (weight > 0){
                if (weight != last_seen_weight) {
                    //record the current weight and reset the count for mow many times that value has been seen
                    last_seen_weight = weight;
                    last_wait_repeat_count = 1;
                } else if (weight == last_seen_weight && last_wait_repeat_count >= WEIGHT_TRIGGER_THRESHOLD){
                    // record the weight
                    ScaleMeasurement entry = new ScaleMeasurement();
                    entry.setWeight(last_seen_weight / divider);
                    addScaleMeasurement(entry);
                    disconnect();
                } else {
                    //increment the counter for the number of times this weight value has been seen
                    last_wait_repeat_count += 1;
                }
            }
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

        b.setDeviceName("Weight Scale");
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
