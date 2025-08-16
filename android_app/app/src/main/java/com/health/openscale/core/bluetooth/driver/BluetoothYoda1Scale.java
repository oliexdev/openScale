package com.health.openscale.core.bluetooth.driver;

import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothGattUuid;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import timber.log.Timber;

public class BluetoothYoda1Scale extends BluetoothCommunication {

    private BluetoothCentralManager central;
    private final BluetoothCentralManagerCallback btCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull ScanResult scanResult) {
            SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
            byte[] weightBytes = manufacturerSpecificData.valueAt(0);

            //int featuresAndCounter = manufacturerSpecificData.keyAt(0);
            //Timber.d("Feature: %d, Counter: %d", featuresAndCounter & 0xFF, featuresAndCounter >> 8);

            final byte ctrlByte = weightBytes[6];

            final boolean isStabilized = isBitSet(ctrlByte, 0);
            final boolean isKgUnit = isBitSet(ctrlByte, 2);
            final boolean isOneDecimal = isBitSet(ctrlByte, 3);

            if (isStabilized) {
                Timber.d("One digit decimal: %s", isOneDecimal);
                Timber.d("Unit Kg: %s", isKgUnit);

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

                Timber.d("Got weight: %f", weight);

                final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();
                ScaleMeasurement scaleBtData = new ScaleMeasurement();

                scaleBtData.setWeight(Converters.toKilogram(weight, selectedUser.getScaleUnit()));
                addScaleMeasurement(scaleBtData);

                disconnect();
            }
        }
    };

    public BluetoothYoda1Scale(Context context, String deviceName) {
        super(context, deviceName);
        central = new BluetoothCentralManager(context, btCallback, new Handler(Looper.getMainLooper()));

    }

    @Override
    public void connect(String macAddress) {
        Timber.d("Mac address: %s", macAddress);
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

    public static String driverId() {
        return "yoda1_scale";
    }

    @Override
    protected boolean onNextStep(int stepNr) {
        return false;
    }
}
