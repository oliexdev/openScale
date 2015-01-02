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
package com.health.openscale.gui;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;

public class SettingsActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.preferences);
    initSummary(getPreferenceScreen());
}

@Override
protected void onResume() {
    super.onResume();
    // Set up a listener whenever a key changes
    getPreferenceScreen().getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(this);
}

@Override
protected void onPause() {
    super.onPause();
    // Unregister the listener whenever a key changes
    getPreferenceScreen().getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(this);
}

@Override
public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    updatePrefSummary(findPreference(key));

    
	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
	
    if(prefs.getBoolean("btEnable", true))
    {
    	String deviceName = prefs.getString("btDeviceName", "openScale");
    	OpenScale.getInstance(getApplicationContext()).startBluetoothServer(deviceName);
    } else {
    	OpenScale.getInstance(getApplicationContext()).stopBluetoothServer();
    }
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

private void updatePrefSummary(Preference p) {
    if (p instanceof ListPreference) {
        ListPreference listPref = (ListPreference) p;
        p.setSummary(listPref.getEntry());
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
        EditTextPreference editTextPref = (EditTextPreference) p;
        p.setSummary(editTextPref.getText());
    }
    
	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    if(prefs.getBoolean("btEnable", true))
    {
    	findPreference("btDeviceName").setEnabled(true);
    } else {
    	findPreference("btDeviceName").setEnabled(false);
    }
}

}
