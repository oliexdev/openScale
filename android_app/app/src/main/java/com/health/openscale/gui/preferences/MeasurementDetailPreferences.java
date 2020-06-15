/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*  Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.health.openscale.R;
import com.health.openscale.gui.measurement.MeasurementView;

public class MeasurementDetailPreferences extends PreferenceFragmentCompat {

   private static MeasurementView measurementView;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.measurement_detail_preferences, rootKey);

        setHasOptionsMenu(true);

        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
        measurementView.prepareExtraPreferencesScreen(screen);
        setPreferenceScreen(screen);
    }

    public static void setMeasurementView(MeasurementView view) {
        measurementView = view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
