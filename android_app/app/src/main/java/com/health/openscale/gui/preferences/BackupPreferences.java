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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.alarm.AlarmBackupHandler;
import com.health.openscale.core.alarm.ReminderBootReceiver;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.utils.PermissionHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BackupPreferences extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFERENCE_KEY_IMPORT_BACKUP = "importBackup";
    private static final String PREFERENCE_KEY_EXPORT_BACKUP = "exportBackup";
    private static final String PREFERENCE_KEY_AUTO_BACKUP = "autoBackup";
    private static final String PREFERENCE_KEY_OVERWRITE_BACKUP = "overwriteBackup";
    private static final String PREFERENCE_KEY_AUTO_BACKUP_SCHEDULE = "autoBackup_Schedule";

    private Preference importBackup;
    private Preference exportBackup;

    private CheckBoxPreference autoBackup;
    private CheckBoxPreference overwriteBackup;
    private ListPreference autoBackupSchedule;

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
        overwriteBackup = (CheckBoxPreference) findPreference(PREFERENCE_KEY_OVERWRITE_BACKUP);
        autoBackupSchedule = (ListPreference) findPreference(PREFERENCE_KEY_AUTO_BACKUP_SCHEDULE);

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

            autoBackupSchedule.setEnabled(true);
            overwriteBackup.setEnabled(true);
        } else {
            alarmBackupHandler.disableAlarm(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);

            autoBackupSchedule.setEnabled(false);
            overwriteBackup.setEnabled(false);
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

    private boolean importBackup() {
        File exportDir = new File(Environment.getExternalStorageDirectory(), PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext()).getString("exportDir", "openScale Backup"));

        String databaseName = "openScale.db";

        OpenScale openScale = OpenScale.getInstance(getActivity().getApplicationContext());

        if (!isExternalStoragePresent())
            return false;

        File importFile = new File(exportDir, databaseName);

        if (!importFile.exists()) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_importing) + " "  + exportDir + "/" + databaseName  + " " + getResources().getString(R.string.label_not_found), Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            openScale.importDatabase(importFile);
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_imported) + " " + exportDir + "/" + databaseName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_importing) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        openScale.reopenDatabase();

        List<ScaleUser> scaleUserList = openScale.getScaleUserList();

        if (!scaleUserList.isEmpty()) {
            openScale.selectScaleUser(scaleUserList.get(0).getId());
            openScale.updateScaleData();
        }

        return true;
    }

    private boolean exportBackup() {
        File exportDir = new File(Environment.getExternalStorageDirectory(), PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext()).getString("exportDir", "openScale Backup"));

        String databaseName = "openScale.db";

        if (!isExternalStoragePresent())
            return false;

        OpenScale openScale = OpenScale.getInstance(getActivity().getApplicationContext());

        File exportFile = new File(exportDir, databaseName);

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        try {
            openScale.exportDatase(exportFile);
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_exported) +  " " + exportDir + "/" + databaseName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private boolean isExternalStoragePresent() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
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
