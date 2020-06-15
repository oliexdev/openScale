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
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceViewHolder;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;

public class UsersPreferences extends PreferenceFragmentCompat {
    private static final String PREFERENCE_KEY_ADD_USER = "addUser";
    private static final String PREFERENCE_KEY_USERS = "users";

    private PreferenceCategory users;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.users_preferences, rootKey);

        setHasOptionsMenu(true);

        Preference addUser = findPreference(PREFERENCE_KEY_ADD_USER);
        addUser.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                UsersPreferencesDirections.ActionNavUserPreferencesToNavUsersettings action = UsersPreferencesDirections.actionNavUserPreferencesToNavUsersettings();
                action.setMode(UserSettingsFragment.USER_SETTING_MODE.ADD);
                action.setTitle(getString(R.string.label_add_user));

                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                return true;
            }
        });

        users = (PreferenceCategory) findPreference(PREFERENCE_KEY_USERS);
        updateUserPreferences();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

       View view = super.onCreateView(inflater, container, savedInstanceState);

        Navigation.findNavController(getActivity(), R.id.nav_host_fragment).getCurrentBackStackEntry().getSavedStateHandle().getLiveData("update", false).observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    updateUserPreferences();
                }
            }
        });

        return view;
    }

    private void updateUserPreferences() {
        users.removeAll();
        for (ScaleUser scaleUser : OpenScale.getInstance().getScaleUserList()) {
            users.addPreference(new UserPreference(getActivity(), users, scaleUser));
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
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UsersPreferencesDirections.ActionNavUserPreferencesToNavUsersettings action = UsersPreferencesDirections.actionNavUserPreferencesToNavUsersettings();
                    action.setMode(UserSettingsFragment.USER_SETTING_MODE.EDIT);
                    action.setTitle(scaleUser.getUserName());
                    action.setUserId(scaleUser.getId());
                    Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                }
            });

            TypedValue outValue = new TypedValue();
            getActivity().getTheme().resolveAttribute(R.attr.selectableItemBackground, outValue, true);
            holder.itemView.setBackgroundResource(outValue.resourceId);

            radioButton = holder.itemView.findViewById(R.id.user_radio_button);
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

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
