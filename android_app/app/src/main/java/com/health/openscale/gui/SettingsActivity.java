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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleUser;

import java.util.ArrayList;

public class SettingsActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.preferences);
    initSummary(getPreferenceScreen());

    Preference prefClearBtData = (Preference) findPreference("btClearData");
    prefClearBtData.setOnPreferenceClickListener(new onClickListenerClearBtData());

    updateUserPreferences();
}


    private void updateUserPreferences()
    {
        PreferenceCategory usersCategory = (PreferenceCategory)findPreference("catUsers");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        usersCategory.removeAll();

        OpenScale openScale = OpenScale.getInstance(this);

        ArrayList<ScaleUser> scaleUserList = openScale.getScaleUserList();

        for (ScaleUser scaleUser : scaleUserList)
        {
            Preference prefUser = new Preference(this);
            prefUser.setOnPreferenceClickListener(new onClickListenerUserSelect());

            if (scaleUser.id == selectedUserId) {
                prefUser.setTitle("> " + scaleUser.user_name);
            } else
            {
                prefUser.setTitle(scaleUser.user_name);
            }

            prefUser.setKey(Integer.toString(scaleUser.id));

            usersCategory.addPreference(prefUser);
        }


        Preference prefAddUser = new Preference(this);

        prefAddUser.setOnPreferenceClickListener(new onClickListenerAddUser());
        prefAddUser.setTitle("+ Add User");

        usersCategory.addPreference(prefAddUser);
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
        findPreference("btClearData").setEnabled(true);
    } else {
    	findPreference("btDeviceName").setEnabled(false);
        findPreference("btClearData").setEnabled(false);
    }
}
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == UserSettingsActivity.ADD_USER_REQUEST) {
            if(resultCode == RESULT_OK){
                updateUserPreferences();
            }
        }


        if (requestCode == UserSettingsActivity.EDIT_USER_REQUEST) {
            if(resultCode == RESULT_OK){
                updateUserPreferences();
            }
        }
    }

    private class onClickListenerUserSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            Intent intent = new Intent(preference.getContext(), UserSettingsActivity.class);
            intent.putExtra("mode", UserSettingsActivity.EDIT_USER_REQUEST);
            intent.putExtra("id", Integer.parseInt(preference.getKey()));
            startActivityForResult(intent, UserSettingsActivity.EDIT_USER_REQUEST);

            return false;
        }
    }

    private class onClickListenerAddUser implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            Intent intent = new Intent(preference.getContext(), UserSettingsActivity.class);
            intent.putExtra("mode", UserSettingsActivity.ADD_USER_REQUEST);
            startActivityForResult(intent, UserSettingsActivity.ADD_USER_REQUEST);

            return false;
        }
    }

    private class onClickListenerClearBtData implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {

            if (OpenScale.getInstance(getApplicationContext()).clearBtScaleData()) {
                Toast.makeText(preference.getContext(), getResources().getString(R.string.info_delete_bluetooth_data_success), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(preference.getContext(), getResources().getString(R.string.info_bluetooth_not_established), Toast.LENGTH_SHORT).show();
            }

            return false;
        }
    }

}
