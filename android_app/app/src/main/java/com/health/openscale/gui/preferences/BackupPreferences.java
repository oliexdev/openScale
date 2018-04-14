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

import android.content.pm.PackageManager;
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
import com.health.openscale.gui.utils.PermissionHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BackupPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_EXPORT_DIR = "exportDir";
    private static final String PREFERENCE_KEY_IMPORT_BACKUP = "importBackup";
    private static final String PREFERENCE_KEY_EXPORT_BACKUP = "exportBackup";

    EditTextPreference exportDir;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.backup_preferences);

        exportDir = (EditTextPreference) findPreference(PREFERENCE_KEY_EXPORT_DIR);
        exportDir.setSummary(exportDir.getText());
        exportDir.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                preference.setSummary((String) newValue);
                return true;
            }
        });

        Preference importBackup = findPreference(PREFERENCE_KEY_IMPORT_BACKUP);
        importBackup.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (PermissionHelper.requestReadPermission(getActivity())) {
                    importBackup();
                }
                return true;
            }
        });

        Preference exportBackup = findPreference(PREFERENCE_KEY_EXPORT_BACKUP);
        exportBackup.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (PermissionHelper.requestWritePermission(getActivity())) {
                    exportBackup();
                }
                return true;
            }
        });
    }

    private boolean isExternalStoragePresent() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    private File getExportDir() {
        if (!isExternalStoragePresent()) {
            return null;
        }

        return new File(Environment.getExternalStorageDirectory(), exportDir.getText());
    }

    private boolean importBackup() {
        File exportDir = getExportDir();
        if (exportDir == null) {
            return false;
        }

        File dbFile = getActivity().getDatabasePath(OpenScale.DATABASE_NAME);
        File importFile = new File(exportDir, OpenScale.DATABASE_NAME);

        if (!importFile.exists()) {
            Toast.makeText(getActivity(), getResources().getString(R.string.error_importing)
                    + " "  + importFile.getPath() + " "
                    + getResources().getString(R.string.label_not_found), Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            dbFile.createNewFile();
            copyFile(importFile, dbFile);

            Toast.makeText(getActivity(), getResources().getString(R.string.info_data_imported)
                    + " " + importFile.getPath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity(), getResources().getString(R.string.error_importing)
                    + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }

        OpenScale openScale = OpenScale.getInstance(getActivity());
        openScale.reopenDatabase();

        List<ScaleUser> scaleUserList = openScale.getScaleUserList();

        if (!scaleUserList.isEmpty()) {
            openScale.selectScaleUser(scaleUserList.get(0).getId());
            openScale.updateScaleData();
        }

        return true;
    }

    private boolean exportBackup() {
        File exportDir = getExportDir();
        if (exportDir == null) {
            return false;
        }

        // Make sure all changes are written to the file before exporting
        OpenScale.getInstance(getActivity()).reopenDatabase();

        File dbFile = getActivity().getDatabasePath(OpenScale.DATABASE_NAME);
        File file = new File(exportDir, OpenScale.DATABASE_NAME);

        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        try {
            file.createNewFile();
            copyFile(dbFile, file);

            Toast.makeText(getActivity(), getResources().getString(R.string.info_data_exported)
                    +  " " + file.getPath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity(), getResources().getString(R.string.error_exporting)
                    + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

    public void onMyOwnRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_READ_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    importBackup();
                } else {
                    Toast.makeText(getActivity(), R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
                }
            break;
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    exportBackup();
                } else {
                    Toast.makeText(getActivity(), R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
                }
            break;
        }
    }
}
