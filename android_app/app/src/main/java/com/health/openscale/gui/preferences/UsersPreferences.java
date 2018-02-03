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

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.UserSettingsActivity;

import java.util.List;

import static android.app.Activity.RESULT_OK;

public class UsersPreferences extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.users_preferences);

        updateUserPreferences();
    }

    private void updateUserPreferences() {
        OpenScale openScale = OpenScale.getInstance(getActivity().getApplicationContext());

        int selectedUserId = openScale.getSelectedScaleUserId();

        getPreferenceScreen().removeAll();

        List<ScaleUser> scaleUserList = openScale.getScaleUserList();

        for (ScaleUser scaleUser : scaleUserList)
        {
            Preference prefUser = new Preference(getActivity().getBaseContext());
            prefUser.setOnPreferenceClickListener(new onClickListenerUserSelect());

            if (scaleUser.getId() == selectedUserId) {
                prefUser.setTitle("> " + scaleUser.getUserName());
            } else
            {
                prefUser.setTitle(scaleUser.getUserName());
            }

            prefUser.setKey(Integer.toString(scaleUser.getId()));

            getPreferenceScreen().addPreference(prefUser);
        }


        Preference prefAddUser = new Preference(getActivity().getBaseContext());

        prefAddUser.setOnPreferenceClickListener(new onClickListenerAddUser());
        prefAddUser.setTitle("+ " + getResources().getString(R.string.label_add_user));

        getPreferenceScreen().addPreference(prefAddUser);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == UserSettingsActivity.ADD_USER_REQUEST) {
            if (resultCode == RESULT_OK) {
                updateUserPreferences();
            }
        }


        if (requestCode == UserSettingsActivity.EDIT_USER_REQUEST) {
            if (resultCode == RESULT_OK) {
                updateUserPreferences();
            }
        }
    }

    private class onClickListenerUserSelect implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            Intent intent = new Intent(preference.getContext(), UserSettingsActivity.class);
            intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.EDIT_USER_REQUEST);
            intent.putExtra(UserSettingsActivity.EXTRA_ID, Integer.parseInt(preference.getKey()));
            startActivityForResult(intent, UserSettingsActivity.EDIT_USER_REQUEST);

            return false;
        }
    }

    private class onClickListenerAddUser implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {
            Intent intent = new Intent(preference.getContext(), UserSettingsActivity.class);
            intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.ADD_USER_REQUEST);
            startActivityForResult(intent, UserSettingsActivity.ADD_USER_REQUEST);

            return false;
        }
    }

}
