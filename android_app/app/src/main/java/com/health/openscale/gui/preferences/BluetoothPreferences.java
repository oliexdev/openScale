/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.BaseAdapter;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.utils.PermissionHelper;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;


public class BluetoothPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_BLUETOOTH_SCANNER = "btScanner";

    public static final String PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME = "btDeviceName";
    public static final String PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS = "btHwAddress";

    private PreferenceScreen btScanner;
    private Map<String, BluetoothDevice> foundDevices = new HashMap<>();

    private Handler progressHandler;
    private BluetoothCentral central;

    private static final String formatDeviceName(String name, String address) {
        if (name.isEmpty() || address.isEmpty()) {
            return "-";
        }
        return String.format("%s [%s]", name, address);
    }

    private static final String formatDeviceName(BluetoothDevice device) {
        return formatDeviceName(device.getName(), device.getAddress());
    }

    private String getCurrentDeviceName() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        return formatDeviceName(
                prefs.getString(PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, ""),
                prefs.getString(PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, ""));
    }

    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            onDeviceFound(scanResult);
        }
    };

    private void startBluetoothDiscovery() {
        foundDevices.clear();
        btScanner.removeAll();

        central = new BluetoothCentral(getActivity().getApplicationContext(), bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
        central.scanForPeripherals();

        final Preference scanning = new Preference(getActivity());
        scanning.setEnabled(false);
        btScanner.addPreference(scanning);
        btScanner.getPreference(0).setTitle(R.string.label_bluetooth_searching);

        progressHandler = new Handler();

        // Draw a simple progressbar during the discovery/scanning
        final int progressUpdatePeriod = 150;
        final String[] blocks = {"▏","▎","▍","▌","▋","▊","▉","█"};
        scanning.setSummary(blocks[0]);
        progressHandler.postDelayed(new Runnable() {
            int index = 1;
            @Override
            public void run() {
                String summary = scanning.getSummary()
                        .subSequence(0, scanning.getSummary().length() - 1).toString();
                if (index == blocks.length) {
                    summary += blocks[blocks.length - 1];
                    index = 0;
                }
                summary += blocks[index++];
                scanning.setSummary(summary);
                progressHandler.postDelayed(this, progressUpdatePeriod);
            }
        }, progressUpdatePeriod);

        // Don't let the BLE discovery run forever
        progressHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopBluetoothDiscovery();

                Preference scanning = btScanner.getPreference(0);
                scanning.setTitle(R.string.label_bluetooth_searching_finished);
                scanning.setSummary("");

                Intent notSupportedIntent = new Intent(Intent.ACTION_VIEW);
                notSupportedIntent.setData(
                        Uri.parse("https://github.com/oliexdev/openScale/wiki/Supported-scales-in-openScale"));

                Preference notSupported = new Preference(getActivity());
                notSupported.setTitle(R.string.label_scale_not_supported);
                notSupported.setSummary(R.string.label_click_to_help_add_support);
                notSupported.setIntent(notSupportedIntent);
                btScanner.addPreference(notSupported);
            }
        }, 20 * 1000);

        // Abort discovery and scan if back is pressed or a scale is selected
        btScanner.getDialog().setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                stopBluetoothDiscovery();
            }
        });
    }

    private void stopBluetoothDiscovery() {
        if (progressHandler != null) {
            progressHandler.removeCallbacksAndMessages(null);
            progressHandler = null;
        }

        central.stopScan();
    }

    private void onDeviceFound(final ScanResult bleScanResult) {
        BluetoothDevice device = bleScanResult.getDevice();

        if (device.getName() == null || foundDevices.containsKey(device.getAddress())) {
            return;
        }

        Preference prefBtDevice = new Preference(getActivity());
        prefBtDevice.setTitle(formatDeviceName(bleScanResult.getDevice()));

        BluetoothCommunication btDevice = BluetoothFactory.createDeviceDriver(getActivity(), device.getName());
        if (btDevice != null) {
            Timber.d("Found supported device %s (driver: %s)",
                    formatDeviceName(device), btDevice.driverName());
            prefBtDevice.setOnPreferenceClickListener(new onClickListenerDeviceSelect());
            prefBtDevice.setKey(device.getAddress());
            prefBtDevice.setIcon(R.drawable.ic_bluetooth_device_supported);
            prefBtDevice.setSummary(btDevice.driverName());

            int tintColor = ColorUtil.getTextColor(getActivity().getApplicationContext());
            prefBtDevice.getIcon().setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        }
        else {
            Timber.d("Found unsupported device %s",
                    formatDeviceName(device));
            prefBtDevice.setIcon(R.drawable.ic_bluetooth_device_not_supported);
            prefBtDevice.setSummary(R.string.label_bt_device_no_support);
            prefBtDevice.setEnabled(false);

            if (OpenScale.DEBUG_MODE) {
                prefBtDevice.setEnabled(true);
                prefBtDevice.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        getDebugInfo(device);
                        return false;
                    }
                });
            }
        }

        foundDevices.put(device.getAddress(), btDevice != null ? device : null);
        btScanner.addPreference(prefBtDevice);
    }

    private void updateBtScannerSummary() {
        // Set summary text and trigger data set changed to make UI update
        btScanner.setSummary(getCurrentDeviceName());
        ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.bluetooth_preferences);

        btScanner = (PreferenceScreen) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);

        final Fragment fragment = this;
        btScanner.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (PermissionHelper.requestBluetoothPermission(getActivity(), fragment)) {
                    if (PermissionHelper.requestLocationServicePermission(getActivity())) {
                        startBluetoothDiscovery();
                    }
                }
                return true;
            }
        });

        updateBtScannerSummary();

        // Dummy preference to make screen open
        btScanner.addPreference(new Preference(getActivity()));

    }

    @Override
    public void onStart() {
        super.onStart();

        // Restart discovery after e.g. orientation change
        if (btScanner.getDialog() != null && btScanner.getDialog().isShowing()) {
            startBluetoothDiscovery();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PermissionHelper.ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                if (PermissionHelper.requestBluetoothPermission(getActivity(), this)) {
                    startBluetoothDiscovery();
                }
            }
            else {
                btScanner.getDialog().dismiss();
            }
        }
    }

    private void getDebugInfo(final BluetoothDevice device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
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
        OpenScale.getInstance().connectToBluetoothDeviceDebugMode(
                macAddress, btHandler);
    }

    private class onClickListenerDeviceSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(final Preference preference) {
            BluetoothDevice device = foundDevices.get(preference.getKey());

            preference.getSharedPreferences().edit()
                    .putString(PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, device.getAddress())
                    .putString(PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, device.getName())
                    .apply();

            updateBtScannerSummary();

            btScanner.getDialog().dismiss();

            return true;
        }
    }

    public void onMyOwnRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (PermissionHelper.requestLocationServicePermission(getActivity())) {
                        startBluetoothDiscovery();
                    }
                } else {
                    Toast.makeText(getActivity(), R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
                    btScanner.getDialog().dismiss();
                }
                break;
            }
        }
    }
}
