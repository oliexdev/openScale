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
import com.health.openscale.gui.activities.BluetoothSettingsActivity;
import com.health.openscale.gui.activities.UserSettingsActivity;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.utils.PermissionHelper;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

import static android.app.Activity.RESULT_OK;


public class BluetoothPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_BLUETOOTH_SCANNER = "btScanner";

    private PreferenceScreen btScanner;

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

        btScanner = (PreferenceScreen) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);

        btScanner.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(preference.getContext(), BluetoothSettingsActivity.class);
                startActivityForResult(intent, BluetoothSettingsActivity.GET_SCALE_REQUEST);
                return true;
            }
        });

        updateBtScannerSummary();

        // Dummy preference to make screen open
        btScanner.addPreference(new Preference(getActivity()));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == BluetoothSettingsActivity.GET_SCALE_REQUEST) {
            updateBtScannerSummary();
        }
    }
}
