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
package com.health.openscale.gui.activities;

import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.EditText;

import com.health.openscale.R;
import com.health.openscale.gui.preferences.BackupPreferences;
import com.health.openscale.gui.preferences.BluetoothPreferences;
import com.health.openscale.gui.utils.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static List<String> fragments = new ArrayList<>();
    private Fragment currentFragment;

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(BaseAppCompatActivity.createBaseContext(context));
        if (!fragments.isEmpty()) {
            invalidateHeaders();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        BaseAppCompatActivity.applyTheme(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

    }

    @Override
    public void onPause() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (key.equals(BaseAppCompatActivity.PREFERENCE_APP_THEME)
                || key.equals(BaseAppCompatActivity.PREFERENCE_LANGUAGE)) {
            recreate();
        }
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        loadHeadersFromResource(R.xml.header_preferences, target);

        int tintColor = new EditText(this).getCurrentTextColor();

        fragments.clear();
        for (Header header : target) {
            Drawable icon = getResources().getDrawable(header.iconRes);
            icon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);

            fragments.add(header.fragment);
        }
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return fragments.contains(fragmentName);
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        currentFragment = fragment;
        super.onAttachFragment(fragment);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        // HACK to call RequestPermissionResult(...) in PreferenceFragment otherwise API level > 23 is required
        switch(requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION:
                BluetoothPreferences bluetoothPreferences = (BluetoothPreferences)currentFragment;
                bluetoothPreferences.onMyOwnRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_READ_STORAGE:
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE:
                BackupPreferences backupPreferences = (BackupPreferences)currentFragment;
                backupPreferences.onMyOwnRequestPermissionsResult(requestCode, permissions, grantResults);
                break;
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
