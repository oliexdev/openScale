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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.gui.utils.PermissionHelper;

import java.util.HashMap;
import java.util.Map;


public class BluetoothPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_BLUETOOTH_SCANNER = "btScanner";

    private PreferenceScreen btScanner;
    private BluetoothAdapter btAdapter = null;
    private Map<String, String> foundDevices = new HashMap<>();

    public void startSearching() {
        foundDevices.clear();
        btScanner.removeAll();

        Preference scanning = new Preference(getActivity());
        scanning.setEnabled(false);
        btScanner.addPreference(scanning);

        OpenScale.getInstance(getActivity()).stopSearchingForBluetooth();
        btAdapter.startDiscovery();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                btScanner.getPreference(0).setTitle(R.string.label_bluetooth_searching);
            }
            else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                btScanner.getPreference(0).setTitle(R.string.label_bluetooth_searching_finished);
            }
            else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device.getName() == null) {
                    return;
                }

                Preference prefBtDevice = new Preference(getActivity());
                prefBtDevice.setTitle(device.getName() + " [" + device.getAddress() + "]");

                BluetoothCommunication btDevice = BluetoothFactory.createDeviceDriver(getActivity(), device.getName());
                if (btDevice != null) {
                    prefBtDevice.setOnPreferenceClickListener(new onClickListenerDeviceSelect());
                    prefBtDevice.setKey(device.getAddress());
                    prefBtDevice.setIcon(R.drawable.ic_bluetooth_connection_lost);
                    prefBtDevice.setSummary(btDevice.deviceName());

                    int tintColor = new EditText(getActivity()).getCurrentTextColor();
                    prefBtDevice.getIcon().setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);

                    foundDevices.put(device.getAddress(), device.getName());
                }
                else {
                    prefBtDevice.setIcon(R.drawable.ic_bluetooth_disabled);
                    prefBtDevice.setSummary(R.string.label_bt_device_no_support);
                    prefBtDevice.setEnabled(false);
                }

                btScanner.addPreference(prefBtDevice);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Intent filter for the scanning process
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        getActivity().registerReceiver(mReceiver, filter);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        addPreferencesFromResource(R.xml.bluetooth_preferences);

        btScanner = (PreferenceScreen) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);
        if (btAdapter == null) {
            btScanner.setEnabled(false);
            btScanner.setSummary("Bluetooth " + getResources().getString(R.string.info_is_not_available));
        }
        else {
            btScanner.setOnPreferenceClickListener(new onClickListenerScannerSelect());
            String deviceName = btScanner.getSharedPreferences().getString("btDeviceName", "-");
            if (!deviceName.equals("-")) {
                deviceName += " [" +
                        btScanner.getSharedPreferences().getString("btHwAddress", "") +
                        "]";
            }
            btScanner.setSummary(deviceName);
            // Dummy preference to make screen open
            btScanner.addPreference(new Preference(getActivity()));
        }
    }

    @Override
    public void onDestroy() {
        getActivity().unregisterReceiver(mReceiver);

        super.onDestroy();
    }

    private class onClickListenerScannerSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
             if (PermissionHelper.requestBluetoothPermission(getActivity(), true)) {
                startSearching();
             }
            return true;
        }
    }

    private class onClickListenerDeviceSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(final Preference preference) {
            preference.getSharedPreferences().edit()
                    .putString("btHwAddress", preference.getKey())
                    .putString("btDeviceName", foundDevices.get(preference.getKey()))
                    .apply();

            btAdapter.cancelDiscovery();

            // Set summary text and trigger data set changed to make UI update
            btScanner.setSummary(preference.getTitle());
            ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();

            btScanner.getDialog().dismiss();
            return true;
        }
    }

    public void onMyOwnRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startSearching();
                } else {
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
    }
}
