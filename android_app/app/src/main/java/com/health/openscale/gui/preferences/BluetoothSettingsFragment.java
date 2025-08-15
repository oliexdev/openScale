/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.preferences;

import static android.content.Context.LOCATION_SERVICE;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.List;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.gui.utils.ColorUtil;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

public class BluetoothSettingsFragment extends Fragment {
    public static final String PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME = "btDeviceName";
    public static final String PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS = "btHwAddress";
    public static final String PREFERENCE_KEY_BLUETOOTH_DRIVER_ID = "btDriverId";

    private Map<String, BluetoothDevice> foundDevices = new HashMap<>();

    private LinearLayout deviceListView;
    private TextView txtSearching;
    private ProgressBar progressBar;
    private Handler progressHandler;
    private BluetoothCentralManager central;
    private Context context;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_bluetoothsettings, container, false);

        setHasOptionsMenu(true);

        deviceListView = root.findViewById(R.id.deviceListView);
        txtSearching = root.findViewById(R.id.txtSearching);
        progressBar = root.findViewById(R.id.progressBar);

        context = root.getContext();

        return root;
    }

    @Override
    public void onPause() {
        stopBluetoothDiscovery();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        Timber.d("Bluetooth settings Bluetooth permission check");

        int targetSdkVersion = context.getApplicationInfo().targetSdkVersion;

        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = bluetoothManager.getAdapter();

        // Check if Bluetooth is enabled
        if (btAdapter == null || !btAdapter.isEnabled()) {
            Timber.d("Bluetooth is not enabled");
            Toast.makeText(getContext(), "Bluetooth " + getContext().getResources().getString(R.string.info_is_not_enable), Toast.LENGTH_SHORT).show();
            stepNavigationBack();
            return;
        }

            // Check if Bluetooth 4.x is available
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Timber.d("No Bluetooth 4.x available");
            Toast.makeText(getContext(), "Bluetooth 4.x " + getContext().getResources().getString(R.string.info_is_not_available), Toast.LENGTH_SHORT).show();
            stepNavigationBack();
            return;
        }

        // Check if GPS or Network location service is enabled
        LocationManager locationManager = (LocationManager) context.getSystemService(LOCATION_SERVICE);
        if (!(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
            Timber.d("No GPS or Network location service is enabled, ask user for permission");

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.permission_bluetooth_info_title);
            builder.setIcon(R.drawable.ic_preferences_about);
            builder.setMessage(R.string.permission_location_service_info);
            builder.setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialogInterface, int i) {
                    // Show location settings when the user acknowledges the alert dialog
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    context.startActivity(intent);
                }
            });
            builder.setNegativeButton(R.string.label_no, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    stepNavigationBack();
                }
            });

            Dialog alertDialog = builder.create();
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            return;
        }

        String[] requiredPermissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetSdkVersion >= Build.VERSION_CODES.S) {
            Timber.d("SDK >= 31 request for Bluetooth Scan and Bluetooth connect permissions");
            requiredPermissions = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) {
            Timber.d("SDK >= 29 request for Access fine location permission");
            requiredPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            Timber.d("SDK < 29 request for coarse location permission");
            requiredPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }

        if (hasPermissions(requiredPermissions)) {
            startBluetoothDiscovery();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            Timber.d("No access fine location permission granted");

            builder.setMessage(R.string.permission_bluetooth_info)
                    .setTitle(R.string.permission_bluetooth_info_title)
                    .setIcon(R.drawable.ic_preferences_about)
                    .setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.dismiss();
                            requestPermissionBluetoothLauncher.launch(requiredPermissions);
                        }
                    });

            Dialog alertDialog = builder.create();
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetSdkVersion >= Build.VERSION_CODES.S && shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        Timber.d("No access Bluetooth scan permission granted");

        builder.setMessage(R.string.permission_bluetooth_info)
                .setTitle(R.string.permission_bluetooth_info_title)
                .setIcon(R.drawable.ic_preferences_about)
                .setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        requestPermissionBluetoothLauncher.launch(requiredPermissions);
                    }
                });

        Dialog alertDialog = builder.create();
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.show();

        } else {
            requestPermissionBluetoothLauncher.launch(requiredPermissions);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }

    private static final String formatDeviceName(String name, String address) {
        if (TextUtils.isEmpty(name) && !address.isEmpty()) {
            return String.format("[%s]", address);
        }
        if (name.isEmpty() || address.isEmpty()) {
            return "-";
        }
        return String.format("%s [%s]", name, address);
    }

    private static final String formatDeviceName(BluetoothDevice device) {
        return formatDeviceName(device.getName(), device.getAddress());
    }

    private final BluetoothCentralManagerCallback bluetoothCentralCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    onDeviceFound(scanResult);
                }
            });
        }
    };

    private void startBluetoothDiscovery() {
        deviceListView.removeAllViews();
        foundDevices.clear();

        central = new BluetoothCentralManager(requireContext(), bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
        central.scanForPeripherals();

        txtSearching.setVisibility(View.VISIBLE);
        txtSearching.setText(R.string.label_bluetooth_searching);
        progressBar.setVisibility(View.VISIBLE);

        progressHandler = new Handler();

        // Don't let the BLE discovery run forever
        progressHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopBluetoothDiscovery();

                txtSearching.setText(R.string.label_bluetooth_searching_finished);
                progressBar.setVisibility(View.GONE);

                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BluetoothDeviceView notSupported = new BluetoothDeviceView(requireContext());
                            notSupported.setDeviceName(requireContext().getString(R.string.label_scale_not_supported));
                            notSupported.setSummaryText(requireContext().getString(R.string.label_click_to_help_add_support));
                            notSupported.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    Intent notSupportedIntent = new Intent(Intent.ACTION_VIEW);
                                    notSupportedIntent.setData(
                                            Uri.parse("https://github.com/oliexdev/openScale/wiki/Supported-scales-in-openScale"));

                                    startActivity(notSupportedIntent);
                                }
                            });
                            deviceListView.addView(notSupported);
                        } catch(IllegalStateException ex) {
                            Timber.e(ex.getMessage());
                        }
                        }
                    });
            }
        }, 20 * 1000);
    }

    private void stopBluetoothDiscovery() {
        if (progressHandler != null) {
            progressHandler.removeCallbacksAndMessages(null);
            progressHandler = null;
        }

        if (central != null) {
            central.stopScan();
        }
    }

    private void onDeviceFound(final ScanResult bleScanResult) {
        BluetoothDevice device = bleScanResult.getDevice();
        Context context = getContext();

        if (foundDevices.containsKey(device.getAddress()) || context == null) {
            return;
        }

        String deviceName = device.getName();
        if (deviceName == null) {
            deviceName = BluetoothFactory.convertNoNameToDeviceName(bleScanResult.getScanRecord().getManufacturerSpecificData());
        }
        if (deviceName == null) {
            return;
        }

        // Extract service UUIDs from scan result
        List<ParcelUuid> serviceUuids = null;
        if (bleScanResult.getScanRecord() != null) {
            serviceUuids = bleScanResult.getScanRecord().getServiceUuids();
        }

        BluetoothDeviceView deviceView = new BluetoothDeviceView(context);
        deviceView.setDeviceName(formatDeviceName(deviceName, device.getAddress()));
        deviceView.setAlias(deviceName);

        // Get driverId for this device
        String driverId = BluetoothFactory.getDriverIdFromDeviceName(deviceName, serviceUuids);
        BluetoothCommunication btDevice = BluetoothFactory.createDeviceDriver(context, deviceName, serviceUuids);
        if (btDevice != null) {
            Timber.d("Found supported device %s (driver: %s)",
                    formatDeviceName(device), btDevice.driverName());
            deviceView.setDeviceAddress(device.getAddress());
            deviceView.setDriverId(driverId);
            deviceView.setIcon(R.drawable.ic_bluetooth_device_supported);
            deviceView.setSummaryText(btDevice.driverName());
        }
        else {
            Timber.d("Found unsupported device %s",
                    formatDeviceName(device));
            deviceView.setIcon(R.drawable.ic_bluetooth_device_not_supported);
            deviceView.setSummaryText(context.getString(R.string.label_bt_device_no_support));
            deviceView.setEnabled(false);

            if (OpenScale.DEBUG_MODE) {
                deviceView.setEnabled(true);
                deviceView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        getDebugInfo(device);
                    }
                });
            }
        }

        foundDevices.put(device.getAddress(), btDevice != null ? device : null);
        deviceListView.addView(deviceView);
    }

    private void getDebugInfo(final BluetoothDevice device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Fetching info")
                .setMessage("Please wait while we fetch extended info from your scale...")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        OpenScale.getInstance().disconnectFromBluetoothDevice();
                        dialog.dismiss();
                    }
                });

        final AlertDialog dialog = builder.create();

        Handler btHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (BluetoothCommunication.BT_STATUS.values()[msg.what]) {
                    case CONNECTION_LOST:
                        OpenScale.getInstance().disconnectFromBluetoothDevice();
                        dialog.dismiss();
                        break;
                }
            }
        };

        dialog.show();

        String macAddress = device.getAddress();
        stopBluetoothDiscovery();
        OpenScale.getInstance().connectToBluetoothDeviceDebugMode(macAddress, btHandler);
    }

    private class BluetoothDeviceView extends LinearLayout implements View.OnClickListener {

        private TextView deviceName;
        private ImageView deviceIcon;
        private String deviceAddress;
        private String deviceAlias;
        private String driverId;

        public BluetoothDeviceView(Context context) {
            super(context);

            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            layoutParams.setMargins(0, 20, 0, 20);
            setLayoutParams(layoutParams);

            deviceName = new TextView(context);
            deviceName.setLines(2);
            deviceIcon = new ImageView(context);;

            LinearLayout.LayoutParams centerLayoutParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            layoutParams.gravity= Gravity.CENTER;

            deviceIcon.setLayoutParams(centerLayoutParams);
            deviceName.setLayoutParams(centerLayoutParams);

            deviceName.setOnClickListener(this);
            deviceIcon.setOnClickListener(this);
            setOnClickListener(this);

            addView(deviceIcon);
            addView(deviceName);
        }

        public void setAlias(String alias) {
            deviceAlias = alias;
        }

        public String getAlias() {
            return deviceAlias;
        }

        public void setDeviceAddress(String address) {
            deviceAddress = address;
        }

        public String getDeviceAddress() {
            return deviceAddress;
        }

        public void setDriverId(String driverId) {
            this.driverId = driverId;
        }

        public String getDriverId() {
            return driverId;
        }

        public void setDeviceName(String name) {
            deviceName.setText(name);
        }

        public void setSummaryText(String text) {
            SpannableStringBuilder stringBuilder = new SpannableStringBuilder(new String());

            stringBuilder.append(deviceName.getText());
            stringBuilder.append("\n");

            int deviceNameLength = deviceName.getText().length();

            stringBuilder.append(text);
            stringBuilder.setSpan(new ForegroundColorSpan(Color.GRAY), deviceNameLength, deviceNameLength + text.length()+1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            stringBuilder.setSpan(new RelativeSizeSpan(0.8f), deviceNameLength, deviceNameLength + text.length()+1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);

            deviceName.setText(stringBuilder);
        }

        public void setIcon(int resId) {
            deviceIcon.setImageResource(resId);

            int tintColor = ColorUtil.getTintColor(requireContext());
            deviceIcon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        }

        @Override
        public void setOnClickListener(OnClickListener listener) {
            super.setOnClickListener(listener);
            deviceName.setOnClickListener(listener);
            deviceIcon.setOnClickListener(listener);
        }

        @Override
        public void setEnabled(boolean status) {
            super.setEnabled(status);
            deviceName.setEnabled(status);
            deviceIcon.setEnabled(status);
        }

        @Override
        public void onClick(View view) {
            BluetoothDevice device = foundDevices.get(getDeviceAddress());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

            prefs.edit()
                    .putString(PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, device.getAddress())
                    .putString(PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, getAlias())
                    .putString(PREFERENCE_KEY_BLUETOOTH_DRIVER_ID, getDriverId())
                    .apply();

            Timber.d("Saved Bluetooth device " + getAlias() + " with address " + device.getAddress() + " and driver ID " + getDriverId());

            stopBluetoothDiscovery();

            stepNavigationBack();
            }
    }

    private void stepNavigationBack() {
        if (getActivity().findViewById(R.id.nav_host_fragment) != null) {
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).getPreviousBackStackEntry().getSavedStateHandle().set("update", true);
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigateUp();
        } else {
            getActivity().finish();
        }
    }

    private boolean hasPermissions(String[] permissions) {
        if (permissions != null) {
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(getContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    Timber.d("Permission is not granted: " + permission);
                    return false;
                }
                Timber.d("Permission already granted: " + permission);
            }
            return true;
        }
        return false;
    }

    private ActivityResultLauncher<String[]> requestPermissionBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), isGranted -> {
                if (isGranted.containsValue(false)) {
                    Timber.d("At least one Bluetooth permission was not granted");
                    Toast.makeText(requireContext(), getString(R.string.label_bluetooth_title) + ": " + getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                    stepNavigationBack();
                }
                else {
                    startBluetoothDiscovery();
                }
            });
}
