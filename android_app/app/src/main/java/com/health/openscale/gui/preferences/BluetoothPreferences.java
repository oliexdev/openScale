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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.widget.BaseAdapter;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.bluetooth.BluetoothCommunication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BluetoothPreferences extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFERENCE_KEY_BLUETOOTH_SMARTUSERASSIGN = "smartUserAssign";
    private static final String PREFERENCE_KEY_BLUETOOTH_IGNOREOUTOFRANGE = "ignoreOutOfRange";
    private static final String PREFERENCE_KEY_BLUETOOTH_SCANNER = "btScanner";

    private CheckBoxPreference smartAssignEnable;
    private CheckBoxPreference ignoreOutOfRangeEnable;
    private PreferenceScreen btScanner;
    public boolean isReceiverRegistered;

    private BluetoothAdapter btAdapter  = null;

    private Map<String, String> foundDevices = new HashMap<>();

    public void startSearching() {
        foundDevices.clear();

        IntentFilter filter = new IntentFilter();

        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        getActivity().registerReceiver(mReceiver, filter);
        isReceiverRegistered = true;
        btAdapter.startDiscovery();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                //discovery starts, we can show progress dialog or perform other tasks
                Toast.makeText(getActivity().getApplicationContext(), R.string.label_bluetooth_searching, Toast.LENGTH_SHORT).show();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                //discovery finishes, dismis progress dialog
                Toast.makeText(getActivity().getApplicationContext(), R.string.label_bluetooth_searching_finished, Toast.LENGTH_SHORT).show();
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                //bluetooth device found
                BluetoothDevice device = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device.getName() == null) {
                    return;
                }

                foundDevices.put(device.getAddress(), device.getName());

                for (Map.Entry<String, String> entry : foundDevices.entrySet()) {
                    if (getActivity() != null) {
                        Preference prefBtDevice = new Preference(getActivity().getBaseContext());
                        prefBtDevice.setSummary(entry.getKey());

                        for (BluetoothCommunication.BT_DEVICE_ID btScaleID : BluetoothCommunication.BT_DEVICE_ID.values()) {
                            BluetoothCommunication btDevice = BluetoothCommunication.getBtDevice(getActivity().getBaseContext(), btScaleID);

                            if (btDevice.checkDeviceName(entry.getValue())) {
                                prefBtDevice.setOnPreferenceClickListener(new onClickListenerDeviceSelect());
                                prefBtDevice.setKey(entry.getKey());
                                prefBtDevice.setIcon(R.drawable.ic_bluetooth_connection_lost);
                                prefBtDevice.setTitle(entry.getValue() + " [" + btDevice.deviceName() + "]");
                                break;
                            } else {
                                prefBtDevice.setIcon(R.drawable.ic_bluetooth_disabled);
                                prefBtDevice.setTitle(entry.getValue() + " [" + getResources().getString(R.string.label_bt_device_no_support) + "]");
                            }
                        }

                        btScanner.addPreference(prefBtDevice);
                    }
                }
            }
        }
    };

    @Override
    public void onDestroy() {
        if (isReceiverRegistered) {
            getActivity().unregisterReceiver(mReceiver);
        }

        super.onDestroy();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        addPreferencesFromResource(R.xml.bluetooth_preferences);

        smartAssignEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_BLUETOOTH_SMARTUSERASSIGN);
        ignoreOutOfRangeEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_BLUETOOTH_IGNOREOUTOFRANGE);
        btScanner = (PreferenceScreen) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);

        btScanner.setOnPreferenceClickListener(new onClickListenerScannerSelect());
        isReceiverRegistered = false;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
        btScanner.setSummary(prefs.getString("btDeviceName", "-"));

        initSummary(getPreferenceScreen());
    }

    private void initSummary(Preference p) {
        if (p instanceof PreferenceGroup) {
            PreferenceGroup pGrp = (PreferenceGroup) p;
            for (int i = 0; i < pGrp.getPreferenceCount(); i++) {
                initSummary(pGrp.getPreference(i));
            }
        } else {
            updatePrefSummary(p);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updatePrefSummary(findPreference(key));
    }

    private void updatePrefSummary(Preference p) {
        if (smartAssignEnable.isChecked()) {
            ignoreOutOfRangeEnable.setEnabled(true);
        } else {
            ignoreOutOfRangeEnable.setEnabled(false);
        }

        if (p instanceof ListPreference) {
            ListPreference listPref = (ListPreference) p;

            p.setSummary(listPref.getTitle());
        }

        if (p instanceof EditTextPreference) {
            EditTextPreference editTextPref = (EditTextPreference) p;
            if (p.getTitle().toString().contains("assword"))
            {
                p.setSummary("******");
            } else {
                p.setSummary(editTextPref.getText());
            }
        }

        if (p instanceof MultiSelectListPreference) {
            MultiSelectListPreference editMultiListPref = (MultiSelectListPreference) p;

            CharSequence[] entries = editMultiListPref.getEntries();
            CharSequence[] entryValues = editMultiListPref.getEntryValues();
            List<String> currentEntries = new ArrayList<>();
            Set<String> currentEntryValues = editMultiListPref.getValues();

            for (int i = 0; i < entries.length; i++)
                if (currentEntryValues.contains(entryValues[i]))
                    currentEntries.add(entries[i].toString());

            p.setSummary(currentEntries.toString());
        }
    }

    private class onClickListenerScannerSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            startSearching();
            return true;
        }
    }

    private class onClickListenerDeviceSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());

            prefs.edit().putString("btHwAddress", preference.getKey()).commit();
            prefs.edit().putString("btDeviceName", foundDevices.get(preference.getKey())).commit();

            btScanner.setSummary(preference.getTitle());
            ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged(); // hack to change the summary text

            btScanner.getDialog().dismiss();
            return true;
        }
    }
}
