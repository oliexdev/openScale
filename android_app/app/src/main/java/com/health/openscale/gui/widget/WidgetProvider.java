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

package com.health.openscale.gui.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.MainActivity;
import com.health.openscale.gui.activities.BaseAppCompatActivity;
import com.health.openscale.gui.views.MeasurementView;

import java.text.DateFormat;
import java.util.List;

import timber.log.Timber;

public class WidgetProvider extends AppWidgetProvider {
    List<MeasurementView> measurementViews;

    public static final String getUserIdPreferenceName(int appWidgetId) {
        return String.format("widget_%d_userid", appWidgetId);
    }

    public static final String getMeasurementPreferenceName(int appWidgetId) {
        return String.format("widget_%d_measurement", appWidgetId);
    }

    private void updateWidget(Context context, AppWidgetManager appWidgetManager,
                              int appWidgetId, Bundle newOptions) {
        // Make sure we use the correct language
        context = BaseAppCompatActivity.createBaseContext(context);

        final int minWidth = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int userId = prefs.getInt(getUserIdPreferenceName(appWidgetId), -1);
        String key = prefs.getString(getMeasurementPreferenceName(appWidgetId), "");

        Timber.d("Update widget %d (%s) for user %d, min width %ddp",
                appWidgetId, key, userId, minWidth);

        if (measurementViews == null) {
            measurementViews = MeasurementView.getMeasurementList(
                    context, MeasurementView.DateTimeOrder.NONE);
        }

        MeasurementView measurementView = measurementViews.get(0);
        for (MeasurementView view : measurementViews) {
            if (view.getKey().equals(key)) {
                measurementView = view;
                break;
            }
        }

        OpenScale openScale = OpenScale.getInstance();
        ScaleMeasurement latest = openScale.getLatestScaleMeasurement(userId);
        if (latest != null) {
            ScaleMeasurement previous = openScale.getTupleScaleData(latest.getId())[0];
            measurementView.loadFrom(latest, previous);
        }

        // From https://developer.android.com/guide/practices/ui_guidelines/widget_design
        final int twoCellsMinWidth = 110;
        final int thirdCellsMinWidth = 180;
        final int fourCellsMinWidth = 250;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

        // Add some transparency to make the corners appear rounded
        int indicatorColor = measurementView.getIndicatorColor();
        indicatorColor = (180 << 24) | (indicatorColor & 0xffffff);
        views.setInt(R.id.indicator_view, "setBackgroundColor", indicatorColor);

        // Show icon in >= two cell mode
        if (minWidth >= twoCellsMinWidth) {
            views.setImageViewResource(R.id.widget_icon, measurementView.getIconResource());
            views.setViewVisibility(R.id.widget_icon, View.VISIBLE);
            views.setViewVisibility(R.id.widget_icon_vertical, View.GONE);
        }
        else {
            views.setImageViewResource(R.id.widget_icon_vertical, measurementView.getIconResource());
            views.setViewVisibility(R.id.widget_icon_vertical, View.VISIBLE);
            views.setViewVisibility(R.id.widget_icon, View.GONE);
        }

        // Show measurement name in >= four cell mode
        if (minWidth >= fourCellsMinWidth) {
            views.setTextViewText(R.id.widget_name, measurementView.getName());
            views.setTextViewText(R.id.widget_date,
                    latest != null
                            ? DateFormat.getDateTimeInstance(
                                    DateFormat.LONG, DateFormat.SHORT).format(latest.getDateTime())
                            : "");
            views.setViewVisibility(R.id.widget_name_date_layout, View.VISIBLE);
        }
        else {
            views.setViewVisibility(R.id.widget_name_date_layout, View.GONE);
        }

        // Always show value and delta, but adjust font size based on widget width
        views.setTextViewText(R.id.widget_value, measurementView.getValueAsString(true));
        SpannableStringBuilder delta = new SpannableStringBuilder();
        measurementView.appendDiffValue(delta, false);
        views.setTextViewText(R.id.widget_delta, delta);

        int textSize;
        if (minWidth >= thirdCellsMinWidth) {
            textSize = 18;
        }
        else if (minWidth >= twoCellsMinWidth) {
            textSize = 17;
        }
        else {
            textSize = 12;
        }
        views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_DIP, textSize);
        views.setTextViewTextSize(R.id.widget_delta, TypedValue.COMPLEX_UNIT_DIP, textSize);

        // Start main activity when widget is clicked
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
        views.setOnClickPendingIntent(R.id.widget_layout, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            Bundle newOptions = appWidgetManager.getAppWidgetOptions(appWidgetId);
            updateWidget(context, appWidgetManager, appWidgetId, newOptions);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        updateWidget(context, appWidgetManager, appWidgetId, newOptions);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (int appWidgetId : appWidgetIds) {
            editor.remove(getUserIdPreferenceName(appWidgetId));
            editor.remove(getMeasurementPreferenceName(appWidgetId));
        }
        editor.apply();
    }

    @Override
    public void onDisabled(Context context) {
        measurementViews = null;
    }

    @Override
    public void onRestored(Context context, int[] oldWidgetIds, int[] newWidgetIds) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();

        for (int i = 0; i < oldWidgetIds.length; ++i) {
            String oldKey = getUserIdPreferenceName(oldWidgetIds[i]);
            if (prefs.contains(oldKey)) {
                editor.putInt(getUserIdPreferenceName(newWidgetIds[i]),
                        prefs.getInt(oldKey, -1));
                editor.remove(oldKey);
            }

            oldKey = getMeasurementPreferenceName(oldWidgetIds[i]);
            if (prefs.contains(oldKey)) {
                editor.putString(getMeasurementPreferenceName(newWidgetIds[i]),
                        prefs.getString(oldKey, ""));
                editor.remove(oldKey);
            }
        }

        editor.apply();
    }
}
