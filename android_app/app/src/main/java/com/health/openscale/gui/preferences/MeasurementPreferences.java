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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;

public class MeasurementPreferences extends PreferenceFragment {
    public static final String PREFERENCE_KEY_DELETE_ALL = "deleteAll";

    private Preference deleteAll;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.measurement_preferences);

        deleteAll = (Preference) findPreference(PREFERENCE_KEY_DELETE_ALL);
        deleteAll.setOnPreferenceClickListener(new onClickListenerDeleteAll());
    }

    private class onClickListenerDeleteAll implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {

            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());

            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_all));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
                    int selectedUserId  = prefs.getInt("selectedUserId", -1);

                    OpenScale.getInstance(getActivity().getApplicationContext()).clearScaleData(selectedUserId);

                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();

            return false;
        }
    }
}
