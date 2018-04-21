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
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.text.method.DigitsKeyListener;

import com.health.openscale.R;

public class GraphPreferences extends PreferenceFragment {

    private static final String PREFERENCE_KEY_REGRESSION_LINE_ORDER = "regressionLineOrder";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.graph_preferences);

        EditTextPreference regressionLineOrder =
                (EditTextPreference) findPreference(PREFERENCE_KEY_REGRESSION_LINE_ORDER);
        regressionLineOrder.getEditText().setKeyListener(new DigitsKeyListener());
        regressionLineOrder.getEditText().setSelectAllOnFocus(true);
        regressionLineOrder.setSummary(regressionLineOrder.getText());
        regressionLineOrder.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                preference.setSummary((String) newValue);
                return true;
            }
        });
    }
}
