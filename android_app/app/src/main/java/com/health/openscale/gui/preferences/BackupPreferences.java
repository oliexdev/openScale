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
import android.os.Environment;
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
import com.health.openscale.core.datatypes.ScaleUser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BackupPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_IMPORT_BACKUP = "importBackup";
    private static final String PREFERENCE_KEY_EXPORT_BACKUP = "exportBackup";

    private Preference importBackup;
    private Preference exportBackup;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.backup_preferences);

        importBackup = (Preference) findPreference(PREFERENCE_KEY_IMPORT_BACKUP);
        importBackup.setOnPreferenceClickListener(new onClickListenerImportBackup());

        exportBackup = (Preference) findPreference(PREFERENCE_KEY_EXPORT_BACKUP);
        exportBackup.setOnPreferenceClickListener(new onClickListenerExportBackup());

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

    private class onClickListenerImportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            File exportDir = new File(Environment.getExternalStorageDirectory(), PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext()).getString("exportDir", "openScale Backup"));

            importBackup("openScale.db", exportDir);

            OpenScale openScale = OpenScale.getInstance(getActivity().getApplicationContext());
            openScale.reopenDatabase();

            List<ScaleUser> scaleUserList = openScale.getScaleUserList();

            if (!scaleUserList.isEmpty()) {
                openScale.selectScaleUser(scaleUserList.get(0).getId());
                openScale.updateScaleData();
            }

            return true;
        }
    }

    private class onClickListenerExportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            File exportDir = new File(Environment.getExternalStorageDirectory(), PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext()).getString("exportDir", "openScale Backup"));

            exportBackup("openScale.db", exportDir);

            return true;
        }
    }

    private boolean importBackup(String databaseName, File exportDir) {
        if (!isExternalStoragePresent())
            return false;

        File exportFile = getActivity().getApplicationContext().getDatabasePath(databaseName);
        File importFile = new File(exportDir, databaseName);

        if (!importFile.exists()) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_importing) + " "  + exportDir + "/" + databaseName  + " " + getResources().getString(R.string.label_not_found), Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            exportFile.createNewFile();
            copyFile(importFile, exportFile);
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_imported) + " " + exportDir + "/" + databaseName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_importing) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private boolean exportBackup(String databaseName, File exportDir) {
        if (!isExternalStoragePresent())
            return false;

        File dbFile = getActivity().getApplicationContext().getDatabasePath(databaseName);
        File file = new File(exportDir, databaseName);

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        try {
            file.createNewFile();
            copyFile(dbFile, file);

            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_exported) +  " " + exportDir + "/" + databaseName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void copyFile(File src, File dst) throws IOException {
        FileChannel inChannel = new FileInputStream(src).getChannel();
        FileChannel outChannel = new FileOutputStream(dst).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null)
                inChannel.close();
            if (outChannel != null)
                outChannel.close();
        }
    }

    private boolean isExternalStoragePresent() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }
}
