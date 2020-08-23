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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.health.openscale.R;


public class BluetoothPreferences extends PreferenceFragmentCompat {
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
                prefs.getString(BluetoothSettingsFragment.PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, ""),
                prefs.getString(BluetoothSettingsFragment.PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, ""));
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.bluetooth_preferences, rootKey);

        setHasOptionsMenu(true);

        btScanner = (Preference) findPreference(PREFERENCE_KEY_BLUETOOTH_SCANNER);

        btScanner.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = BluetoothPreferencesDirections.actionNavBluetoothPreferencesToNavBluetoothSettings();
                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        btScanner.setSummary(getCurrentDeviceName());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = super.onCreateView(inflater, container, savedInstanceState);

        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment).getCurrentBackStackEntry().getSavedStateHandle().getLiveData("update", false).observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    btScanner.setSummary(getCurrentDeviceName());
                }
            }
        });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
