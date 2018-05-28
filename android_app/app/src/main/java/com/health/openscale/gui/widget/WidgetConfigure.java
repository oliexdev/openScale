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

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TableRow;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.BaseAppCompatActivity;
import com.health.openscale.gui.views.MeasurementView;

import java.util.ArrayList;
import java.util.List;

public class WidgetConfigure extends BaseAppCompatActivity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
        }

        setContentView(R.layout.widget_configuration);

        OpenScale openScale = OpenScale.getInstance();

        // Set up user spinner
        final Spinner userSpinner = findViewById(R.id.widget_user_spinner);
        List<String> users = new ArrayList<>();
        final List<Integer> userIds = new ArrayList<>();
        for (ScaleUser scaleUser : openScale.getScaleUserList()) {
            users.add(scaleUser.getUserName());
            userIds.add(scaleUser.getId());
        }

        // Hide user selector when there's only one user
        if (users.size() == 1) {
            TableRow row = (TableRow) userSpinner.getParent();
            row.setVisibility(View.GONE);
        }
        else if (users.isEmpty()) {
            users.add(getResources().getString(R.string.info_no_selected_user));
            userIds.add(-1);
            findViewById(R.id.widget_save).setEnabled(false);
        }

        ArrayAdapter<String> userAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, users);
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userSpinner.setAdapter(userAdapter);

        // Set up measurement spinner
        final Spinner measurementSpinner = findViewById(R.id.widget_measurement_spinner);
        List<String> measurements = new ArrayList<>();
        final List<String> measurementKeys = new ArrayList<>();
        for (MeasurementView measurementView : MeasurementView.getMeasurementList(
                this, MeasurementView.DateTimeOrder.NONE)) {
            if (measurementView.isVisible()) {
                measurements.add(measurementView.getName().toString());
                measurementKeys.add(measurementView.getKey());
            }
        }
        ArrayAdapter<String> measurementAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, measurements);
        measurementAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        measurementSpinner.setAdapter(measurementAdapter);

        findViewById(R.id.widget_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int userId = userIds.get(userSpinner.getSelectedItemPosition());
                String measurementKey = measurementKeys.get(measurementSpinner.getSelectedItemPosition());

                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).edit()
                        .putInt(WidgetProvider.getUserIdPreferenceName(appWidgetId), userId)
                        .putString(WidgetProvider.getMeasurementPreferenceName(appWidgetId), measurementKey)
                        .apply();

                Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE, null);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {appWidgetId});
                sendBroadcast(intent);

                Intent resultValue = new Intent();
                resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                setResult(RESULT_OK, resultValue);

                finish();
            }
        });
    }
}
