package com.health.openscale.core.bluetooth.legacy;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.bluetooth.data.ScaleMeasurement;
import com.health.openscale.core.bluetooth.data.ScaleUser;
import com.health.openscale.core.utils.ConverterUtils;
import com.health.openscale.core.utils.LogManager;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

public class BluetoothYoda1Scale extends BluetoothCommunication {
    private final String TAG = "BluetoothYoda1Scale";

    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] weightBytes = manufacturerSpecificData.valueAt(0);

            //int featuresAndCounter = manufacturerSpecificData.keyAt(0);
            //LogManager.d("Feature: %d, Counter: %d", featuresAndCounter & 0xFF, featuresAndCounter >> 8);

            final byte ctrlByte = weightBytes[6];

            final boolean isStabilized = isBitSet(ctrlByte, 0);
            final boolean isKgUnit = isBitSet(ctrlByte, 2);
            final boolean isOneDecimal = isBitSet(ctrlByte, 3);

            if (isStabilized) {
                LogManager.d(TAG, String.format("One digit decimal: %s", isOneDecimal));
                LogManager.d(TAG, String.format("Unit Kg: %s", isKgUnit));

                float weight;

                if (isKgUnit) {
                    weight = (float) (((weightBytes[0] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 10.0f;
                } else {
                    // catty
                    weight = (float) (((weightBytes[0] & 0xFF) << 8) | (weightBytes[1] & 0xFF)) / 20.0f;
                }

                if (!isOneDecimal) {
                    weight /= 10.0;
                }

                LogManager.d(TAG, String.format("Got weight: %f", weight));

                final ScaleUser selectedUser = getSelectedScaleUser();
                ScaleMeasurement scaleBtData = new ScaleMeasurement();

                scaleBtData.setWeight(ConverterUtils.toKilogram(weight, selectedUser.getScaleUnit()));
                addScaleMeasurement(scaleBtData);

                disconnect();
            }
        }
    };

    public BluetoothYoda1Scale(Context context) {
        super(context);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));
    }

    @Override
    public void connect(String macAddress) {
        LogManager.d("Mac address: %s", macAddress);
        List<ScanFilter> filters = new LinkedList<ScanFilter>();

        ScanFilter.Builder b = new ScanFilter.Builder();
        b.setDeviceAddress(macAddress);

        b.setDeviceName("Yoda1");
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
    public String driverName() {
        return "Yoda1 Scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}
