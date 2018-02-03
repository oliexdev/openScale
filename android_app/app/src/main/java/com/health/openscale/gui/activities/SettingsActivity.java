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

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

import com.health.openscale.R;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends PreferenceActivity {
    public static String EXTRA_TINT_COLOR = "tintColor";
    private static List<String> fragments = new ArrayList<String>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        String app_theme = PreferenceManager.getDefaultSharedPreferences(this).getString("app_theme", "Light");

        if (app_theme.equals("Dark")) {
            setTheme(R.style.AppTheme_Dark);
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        loadHeadersFromResource(R.xml.header_preferences, target);

        int tintColor = getIntent().getIntExtra(EXTRA_TINT_COLOR, 0);

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
}
