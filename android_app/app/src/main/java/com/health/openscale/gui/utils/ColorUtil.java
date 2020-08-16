/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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

package com.health.openscale.gui.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.preference.PreferenceManager;

public class ColorUtil {
    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_VIOLET = Color.parseColor("#AA66CC");
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_ORANGE = Color.parseColor("#FFBB33");
    public static final int COLOR_RED = Color.parseColor("#FF4444");
    public static final int COLOR_GRAY = Color.parseColor("#d3d3d3");
    public static final int COLOR_WHITE = Color.parseColor("#ffffff");
    public static final int COLOR_BLACK = Color.parseColor("#000000");
    public static final int[] COLORS = new int[]{COLOR_BLUE, COLOR_VIOLET, COLOR_GREEN, COLOR_ORANGE, COLOR_RED};

    public static int getTintColor(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

        if (prefs.getString("app_theme", "Light").equals("Dark") || nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            return Color.parseColor("#b3ffffff");
        }

        return Color.parseColor("#de000000");
    }
}
