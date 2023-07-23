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

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;

import androidx.preference.DropDownPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.health.openscale.R;
import com.health.openscale.gui.measurement.ChartMeasurementView;

public class GraphPreferences extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.graph_preferences, rootKey);

        setHasOptionsMenu(true);

        DropDownPreference trendlinePreference = findPreference("trendlineComputationMethod");
        SeekBarPreference simpleMovingAveragePreference = findPreference("simpleMovingAverageNumDays");

        simpleMovingAveragePreference.setVisible(
                trendlinePreference.getValue().equals(
                        ChartMeasurementView.COMPUTATION_METHOD_SIMPLE_MOVING_AVERAGE
                )
        );

        trendlinePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String selectedValue = (String) newValue;
                boolean simpleMovingAverageEnabled = selectedValue.equals(
                        ChartMeasurementView.COMPUTATION_METHOD_SIMPLE_MOVING_AVERAGE
                );

                // hide selector of the number of days when simple moving average is not selected
                simpleMovingAveragePreference.setVisible(simpleMovingAverageEnabled);
                // scroll to the bottom to show the new preference to the user
                getListView().scrollToPosition(getListView().getChildCount());

                return true;
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
