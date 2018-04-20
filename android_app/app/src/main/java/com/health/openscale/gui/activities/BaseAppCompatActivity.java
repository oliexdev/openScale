/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;

import com.health.openscale.R;

import java.util.Locale;

public class BaseAppCompatActivity extends AppCompatActivity {
    public static final String PREFERENCE_APP_THEME = "app_theme";
    public static final String PREFERENCE_LANGUAGE = "language";

    private static Locale systemDefaultLocale = null;

    public static Context createBaseContext(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String language = prefs.getString(PREFERENCE_LANGUAGE, "");
        if (language.isEmpty() || language.equals("default")) {
            if (systemDefaultLocale != null) {
                Locale.setDefault(systemDefaultLocale);
                systemDefaultLocale = null;
            }
            return context;
        }

        if (systemDefaultLocale == null) {
            systemDefaultLocale = Locale.getDefault();
        }

        Locale locale;
        String[] localeParts = TextUtils.split(language, "-");
        if (localeParts.length == 2) {
            locale = new Locale(localeParts[0], localeParts[1]);
        }
        else {
            locale = new Locale(localeParts[0]);
        }
        Locale.setDefault(locale);

        Configuration config = context.getResources().getConfiguration();
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }

    public static void applyTheme(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getString(PREFERENCE_APP_THEME, "").equals("Dark")) {
            context.setTheme(R.style.AppTheme_Dark);
        }
    }

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(createBaseContext(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme(this);
        super.onCreate(savedInstanceState);
    }
}
