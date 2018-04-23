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

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.alarm.AlarmBackupHandler;
import com.health.openscale.core.alarm.ReminderBootReceiver;
import com.health.openscale.gui.utils.PermissionHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static android.app.Activity.RESULT_OK;

public class BackupPreferences extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFERENCE_KEY_IMPORT_BACKUP = "importBackup";
    private static final String PREFERENCE_KEY_EXPORT_BACKUP = "exportBackup";
    private static final String PREFERENCE_KEY_AUTO_BACKUP = "autoBackup";

    private static final int IMPORT_DATA_REQUEST = 100;
    private static final int EXPORT_DATA_REQUEST = 101;

    private Preference importBackup;
    private Preference exportBackup;

    private CheckBoxPreference autoBackup;

    private boolean isAutoBackupAskForPermission;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.backup_preferences);

        importBackup = (Preference) findPreference(PREFERENCE_KEY_IMPORT_BACKUP);
        importBackup.setOnPreferenceClickListener(new onClickListenerImportBackup());

        exportBackup = (Preference) findPreference(PREFERENCE_KEY_EXPORT_BACKUP);
        exportBackup.setOnPreferenceClickListener(new onClickListenerExportBackup());

        autoBackup = (CheckBoxPreference) findPreference(PREFERENCE_KEY_AUTO_BACKUP);
        autoBackup.setOnPreferenceClickListener(new onClickListenerAutoBackup());

        initSummary(getPreferenceScreen());
        updateBackupPreferences();
    }

    void updateBackupPreferences() {
        ComponentName receiver = new ComponentName(getActivity().getApplicationContext(), ReminderBootReceiver.class);
        PackageManager pm = getActivity().getApplicationContext().getPackageManager();

        AlarmBackupHandler alarmBackupHandler = new AlarmBackupHandler();

        isAutoBackupAskForPermission = false;

        if (autoBackup.isChecked()) {
            alarmBackupHandler.scheduleAlarms(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else {
            alarmBackupHandler.disableAlarm(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    @Override
    public void onResume()
    {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause()
    {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        updatePrefSummary(findPreference(key));
        updateBackupPreferences();
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

    private void updatePrefSummary(Preference p)
    {
        if (p instanceof ListPreference)
        {
            ListPreference listPref = (ListPreference) p;
            p.setSummary(listPref.getEntry());
        }

        if (p instanceof EditTextPreference)
        {
            EditTextPreference editTextPref = (EditTextPreference) p;
            if (p.getTitle().toString().contains("assword"))
            {
                p.setSummary("******");
            }
            else
            {
                p.setSummary(editTextPref.getText());
            }
        }

        if (p instanceof MultiSelectListPreference)
        {
            MultiSelectListPreference editMultiListPref = (MultiSelectListPreference) p;

            CharSequence[] entries = editMultiListPref.getEntries();
            CharSequence[] entryValues = editMultiListPref.getEntryValues();
            List<String> currentEntries = new ArrayList<>();
            Set<String> currentEntryValues = editMultiListPref.getValues();

            for (int i = 0; i < entries.length; i++)
            {
                if (currentEntryValues.contains(entryValues[i].toString())) currentEntries.add(entries[i].toString());
            }

            p.setSummary(currentEntries.toString());
        }
    }

    private class onClickListenerAutoBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (autoBackup.isChecked()) {
                isAutoBackupAskForPermission = true;

                PermissionHelper.requestWritePermission(getActivity());
            }

            return true;
        }
    }

    private class onClickListenerImportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (PermissionHelper.requestReadPermission(getActivity())) {
                importBackup();
            }

            return true;
        }
    }

    private class onClickListenerExportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (PermissionHelper.requestWritePermission(getActivity())) {
                exportBackup();
            }

            return true;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        OpenScale openScale = OpenScale.getInstance();

        switch (requestCode) {
            case IMPORT_DATA_REQUEST:
                Uri importURI = data.getData();

                try {
                    openScale.importDatabase(importURI);
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_imported) + " " + importURI.getPath(), Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_importing) + " " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                break;

            case EXPORT_DATA_REQUEST:
                Uri exportURI = data.getData();

                try {
                    openScale.exportDatabase(exportURI);
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_exported) +  " " + exportURI.getPath(), Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                break;
        }
    }

    private boolean importBackup() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("*/*");

        startActivityForResult(
                Intent.createChooser(intent, getResources().getString(R.string.label_import)),
                IMPORT_DATA_REQUEST);

        return true;
    }

    private boolean exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setType("*/*");

        startActivityForResult(intent, EXPORT_DATA_REQUEST);

        return true;
    }

    public void onMyOwnRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_READ_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    importBackup();
                } else {
                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                }
            break;
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (isAutoBackupAskForPermission) {
                        autoBackup.setChecked(true);
                    } else {
                        exportBackup();
                    }

                } else {
                    if (isAutoBackupAskForPermission) {
                        autoBackup.setChecked(false);
                    }

                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                }
            break;
        }
    }
}
