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

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.alarm.AlarmBackupHandler;
import com.health.openscale.core.alarm.ReminderBootReceiver;

import java.io.IOException;

import timber.log.Timber;

public class BackupPreferences extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFERENCE_KEY_IMPORT_BACKUP = "importBackup";
    private static final String PREFERENCE_KEY_EXPORT_BACKUP = "exportBackup";
    private static final String PREFERENCE_KEY_AUTO_BACKUP = "autoBackup";
    private static final String PREFERENCE_KEY_AUTO_BACKUP_DIR = "exportDir";
    private static final String PREFERENCE_KEY_AUTO_BACKUP_TEST = "autoBackupTest";

    private static final int IMPORT_DATA_REQUEST = 100;
    private static final int EXPORT_DATA_REQUEST = 101;

    private Preference importBackup;
    private Preference exportBackup;
    private Preference autoBackupDir;
    private Preference autoBackupTest;

    private CheckBoxPreference autoBackup;

    private boolean isAutoBackupAskForPermission;

    private SharedPreferences prefs;

    private Fragment fragment;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.backup_preferences, rootKey);

        setHasOptionsMenu(true);

        fragment = this;

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        importBackup = (Preference) findPreference(PREFERENCE_KEY_IMPORT_BACKUP);
        importBackup.setOnPreferenceClickListener(new onClickListenerImportBackup());

        exportBackup = (Preference) findPreference(PREFERENCE_KEY_EXPORT_BACKUP);
        exportBackup.setOnPreferenceClickListener(new onClickListenerExportBackup());

        autoBackup = (CheckBoxPreference) findPreference(PREFERENCE_KEY_AUTO_BACKUP);
        autoBackup.setOnPreferenceClickListener(new onClickListenerAutoBackup());

        // Auto backup preference
        autoBackupDir = (Preference) findPreference(PREFERENCE_KEY_AUTO_BACKUP_DIR);
        autoBackupDir.setOnPreferenceClickListener(new onClickListenerAutoBackupDir());
        // Setting auto backup preference's summary to location or message that none is selected
        String autoBackupDirString = prefs.getString("exportDir", null);
        autoBackupDir.setSummary(autoBackupDirString != null ? Uri.parse(autoBackupDirString).getLastPathSegment() : getString(R.string.label_auto_backup_lacation));

        autoBackupTest = (Preference) findPreference(PREFERENCE_KEY_AUTO_BACKUP_TEST);
        autoBackupTest.setOnPreferenceClickListener(new onClickListenerAutoBackupTest());

        updateBackupPreferences();
    }

    void updateBackupPreferences() {
        ComponentName receiver = new ComponentName(getActivity().getApplicationContext(), ReminderBootReceiver.class);
        PackageManager pm = getActivity().getApplicationContext().getPackageManager();

        AlarmBackupHandler alarmBackupHandler = new AlarmBackupHandler();

        isAutoBackupAskForPermission = false;

        if (autoBackup.isChecked()) {
            Timber.d("Auto-Backup enabled");
            alarmBackupHandler.scheduleAlarms(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else {
            Timber.d("Auto-Backup disabled");
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
        updateBackupPreferences();
    }


    private class onClickListenerAutoBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (autoBackup.isChecked()) {
                autoBackup.setChecked(true);
                // If exportDir location already saved user won't be prompted to select a new location
                if (prefs.getString("exportDir", null) == null) {
                    Toast.makeText(getContext(), R.string.info_select_auto_backup_export_dir, Toast.LENGTH_SHORT).show();
                    selectAutoBackupDir.launch(null);
                }
            } else {
                autoBackup.setChecked(false);
            }
            return true;
        }
    }

    /**
     *  Function for "Export directory" setting
     */
    private class onClickListenerAutoBackupDir implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(@NonNull Preference preference) {
            selectAutoBackupDir.launch(null);
            return true;
        }
    }

    /**
     * Launches Android File Picker to choose a directory where automatic backups should be saved
     * If user exits File Picker without selecting an directory
     * and previously none where select a "Auto backup" checkbox is removed
     */
    ActivityResultLauncher<Uri> selectAutoBackupDir = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), result -> {
        if (result != null) {
            getActivity().getContentResolver().takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            autoBackupDir.setSummary(result.getLastPathSegment());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("exportDir", result.toString());
            editor.commit();
        } else {
            if (prefs.getString("exportDir", null) == null) {
                this.autoBackup.setChecked(false);
            }
        }
    });

    /**
     * Function for "Test auto backup" setting
     */
    private class onClickListenerAutoBackupTest implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(@NonNull Preference preference) {
            AlarmBackupHandler alarmBackupHandler = new AlarmBackupHandler();
            alarmBackupHandler.executeBackup(getContext());
            return true;
        }
    }

    private class onClickListenerImportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                importBackup();
            } else {
                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) {
                    importBackup();
                } else {
                    requestPermissionImportLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            return true;
        }
    }

    private class onClickListenerExportBackup implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportBackup();
            } else {
                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) {
                    exportBackup();
                } else {
                    requestPermissionExportLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
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
        intent.putExtra(Intent.EXTRA_TITLE, "openScale.db");
        intent.setType("*/*");

        startActivityForResult(intent, EXPORT_DATA_REQUEST);

        return true;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }

    private ActivityResultLauncher<String> requestPermissionImportLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    importBackup();
                }
                else {
                    Toast.makeText(getContext(), getResources().getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                }
            });

    private ActivityResultLauncher<String> requestPermissionExportLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    if (isAutoBackupAskForPermission) {
                        autoBackup.setChecked(true);
                    } else {
                        exportBackup();
                    }
                }
                else {
                    if (isAutoBackupAskForPermission) {
                        autoBackup.setChecked(false);
                    }

                    Toast.makeText(getContext(), getResources().getString(R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
                }
            });
}
