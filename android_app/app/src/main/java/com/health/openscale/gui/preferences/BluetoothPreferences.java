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

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.widget.BaseAdapter;

import com.health.openscale.R;
import com.health.openscale.gui.activities.BluetoothSettingsActivity;


public class BluetoothPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_BLUETOOTH_SCANNER = "btScanner";

    private Preference btScanner;

    private static final String formatDeviceName(String name, String address) {
        if (name.isEmpty() || address.isEmpty()) {
            return "-";
        }
        return String.format("%s [%s]", name, address);
    }

    private String getCurrentDeviceName() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        return formatDeviceName(
                prefs.getString(BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, ""),
                prefs.getString(BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, ""));
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

        btScanner = (Preference) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);

        btScanner.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(preference.getContext(), BluetoothSettingsActivity.class);
                startActivityForResult(intent, BluetoothSettingsActivity.GET_SCALE_REQUEST);
                return true;
            }
        });

        updateBtScannerSummary();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == BluetoothSettingsActivity.GET_SCALE_REQUEST) {
            updateBtScannerSummary();
        }
    }
}
