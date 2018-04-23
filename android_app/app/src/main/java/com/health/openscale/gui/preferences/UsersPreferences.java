/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*                2018  Erik Johansson <erik@ejohansson.se>
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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.util.TypedValue;
import android.view.View;
import android.widget.RadioButton;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.UserSettingsActivity;

import static android.app.Activity.RESULT_OK;

public class UsersPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_ADD_USER = "addUser";
    private static final String PREFERENCE_KEY_USERS = "users";

    private PreferenceCategory users;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.users_preferences);

        Preference addUser = findPreference(PREFERENCE_KEY_ADD_USER);
        addUser.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent(preference.getContext(), UserSettingsActivity.class);
                intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.ADD_USER_REQUEST);
                startActivityForResult(intent, UserSettingsActivity.ADD_USER_REQUEST);
                return true;
            }
        });

        users = (PreferenceCategory) findPreference(PREFERENCE_KEY_USERS);
        updateUserPreferences();
    }

    private void updateUserPreferences() {
        users.removeAll();
        for (ScaleUser scaleUser : OpenScale.getInstance().getScaleUserList()) {
            users.addPreference(new UserPreference(getActivity(), users, scaleUser));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == UserSettingsActivity.ADD_USER_REQUEST
            || requestCode == UserSettingsActivity.EDIT_USER_REQUEST) {
            updateUserPreferences();
        }
    }

    class UserPreference extends Preference {
        PreferenceCategory preferenceCategory;
        ScaleUser scaleUser;
        RadioButton radioButton;

        UserPreference(Context context, PreferenceCategory category, ScaleUser scaleUser) {
            super(context);

            preferenceCategory = category;
            this.scaleUser = scaleUser;

            setTitle(scaleUser.getUserName());
            setWidgetLayoutResource(R.layout.user_preference_widget_layout);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                    intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.EDIT_USER_REQUEST);
                    intent.putExtra(UserSettingsActivity.EXTRA_ID, scaleUser.getId());
                    startActivityForResult(intent, UserSettingsActivity.EDIT_USER_REQUEST);
                }
            });

            TypedValue outValue = new TypedValue();
            getActivity().getTheme().resolveAttribute(R.attr.selectableItemBackground, outValue, true);
            view.setBackgroundResource(outValue.resourceId);

            radioButton = view.findViewById(R.id.user_radio_button);
            radioButton.setChecked(scaleUser.getId() == OpenScale.getInstance().getSelectedScaleUserId());

            radioButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int i = 0; i < preferenceCategory.getPreferenceCount(); ++i) {
                        UserPreference pref = (UserPreference) preferenceCategory.getPreference(i);
                        pref.setChecked(false);
                    }

                    radioButton.setChecked(true);
                    OpenScale.getInstance().selectScaleUser(scaleUser.getId());
                }
            });
        }

        public void setChecked(boolean checked) {
            radioButton.setChecked(checked);
        }
    }
}
