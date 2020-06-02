/* Copyright (C) 2020  olie.xdev <olie.xdev@googlemail.com>
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

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;

import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.health.openscale.R;

public class MainPreferences extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey);

        setHasOptionsMenu(true);

        final Preference prefBackup = findPreference("backup");
        prefBackup.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavBackupPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefBluetooth = findPreference("bluetooth");
        prefBluetooth.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavBluetoothPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefGeneral = findPreference("general");
        prefGeneral.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavGeneralPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefGraph = findPreference("graph");
        prefGraph.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavGraphPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefMeasurements = findPreference("measurements");
        prefMeasurements.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavMeasurementPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefReminder = findPreference("reminder");
        prefReminder.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavReminderPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefUsers = findPreference("users");
        prefUsers.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavUserPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        final Preference prefAbout = findPreference("about");
        prefAbout.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                NavDirections action = MainPreferencesDirections.actionNavMainPreferencesToNavAboutPreferences();
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
