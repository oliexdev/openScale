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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.BoneMeasurementView;
import com.health.openscale.gui.views.CommentMeasurementView;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.LBWMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewUpdateListener;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WHRMeasurementView;
import com.health.openscale.gui.views.WHtRMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

public class DataEntryActivity extends AppCompatActivity {
    public static String EXTRA_ID = "id";
    private static String PREF_EXPAND = "expandEvaluator";

    private MeasurementView.MeasurementViewMode measurementViewMode;

    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private TextView txtDataNr;
    private Button btnLeft;
    private Button btnRight;

    private MenuItem saveButton;
    private MenuItem editButton;
    private MenuItem expandButton;
    private MenuItem deleteButton;

    private ScaleMeasurement scaleMeasurement;
    private ScaleMeasurement previousMeasurement;
    private ScaleMeasurement nextMeasurement;
    private boolean isDirty;

    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String app_theme = PreferenceManager.getDefaultSharedPreferences(this).getString("app_theme", "Light");

        if (app_theme.equals("Dark")) {
            setTheme(R.style.AppTheme_Dark);
        }

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_dataentry);

        Toolbar toolbar = (Toolbar) findViewById(R.id.dataEntryToolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        context = this;

        tableLayoutDataEntry = (TableLayout) findViewById(R.id.tableLayoutDataEntry);

        dataEntryMeasurements = new ArrayList<>();
        dataEntryMeasurements.add(new WeightMeasurementView(context));
        dataEntryMeasurements.add(new BMIMeasurementView(context));
        dataEntryMeasurements.add(new WaterMeasurementView(context));
        dataEntryMeasurements.add(new MuscleMeasurementView(context));
        dataEntryMeasurements.add(new LBWMeasurementView(context));
        dataEntryMeasurements.add(new FatMeasurementView(context));
        dataEntryMeasurements.add(new BoneMeasurementView(context));
        dataEntryMeasurements.add(new WaistMeasurementView(context));
        dataEntryMeasurements.add(new WHtRMeasurementView(context));
        dataEntryMeasurements.add(new HipMeasurementView(context));
        dataEntryMeasurements.add(new WHRMeasurementView(context));
        dataEntryMeasurements.add(new BMRMeasurementView(context));
        dataEntryMeasurements.add(new CommentMeasurementView(context));
        dataEntryMeasurements.add(new DateMeasurementView(context));
        dataEntryMeasurements.add(new TimeMeasurementView(context));

        onMeasurementViewUpdateListener updateListener = new onMeasurementViewUpdateListener();
        for (MeasurementView measurement : dataEntryMeasurements) {
            tableLayoutDataEntry.addView(measurement);
            measurement.setOnUpdateListener(updateListener);
        }

        txtDataNr = (TextView) findViewById(R.id.txtDataNr);
        btnLeft = (Button) findViewById(R.id.btnLeft);
        btnRight = (Button) findViewById(R.id.btnRight);

        btnLeft.setVisibility(View.INVISIBLE);
        btnRight.setVisibility(View.INVISIBLE);

        btnLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveLeft();
            }
        });
        btnRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveRight();
            }
        });
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.saveState(outState);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.dataentry_menu, menu);

        saveButton = menu.findItem(R.id.saveButton);
        editButton = menu.findItem(R.id.editButton);
        expandButton = menu.findItem(R.id.expandButton);
        deleteButton = menu.findItem(R.id.deleteButton);

        if (getIntent().hasExtra(EXTRA_ID)) {
            setViewMode(MeasurementView.MeasurementViewMode.VIEW);
        }
        else {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
        }

        updateOnView();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.saveButton:
                final boolean isEdit = scaleMeasurement.getId() > 0;
                saveScaleData();
                if (isEdit) {
                    setViewMode(MeasurementView.MeasurementViewMode.VIEW);
                }
                else {
                    finish();
                }
                return true;

            case R.id.expandButton:
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                final boolean expand = !prefs.getBoolean(PREF_EXPAND, true);
                prefs.edit().putBoolean(PREF_EXPAND, expand).apply();

                for (MeasurementView measurement : dataEntryMeasurements) {
                    measurement.setExpand(expand);
                }
                return true;

            case R.id.editButton:
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
                return true;

            case R.id.deleteButton:
                deleteMeasurement();
                return true;

            // Override the default behaviour in order to return to the correct fragment
            // (e.g. the table view) and not always go to the overview.
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (measurementViewMode == MeasurementView.MeasurementViewMode.EDIT) {
            setViewMode(MeasurementView.MeasurementViewMode.VIEW);
            updateOnView();
        }
        else {
            super.onBackPressed();
        }
    }

    private void updateOnView() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.updatePreferences(prefs);
        }

        int id = 0;
        if (getIntent().hasExtra(EXTRA_ID)) {
            id = getIntent().getExtras().getInt(EXTRA_ID);
        }

        if (scaleMeasurement == null || scaleMeasurement.getId() != id) {
            isDirty = false;
            scaleMeasurement = null;
            previousMeasurement = null;
            nextMeasurement = null;
        }

        OpenScale openScale = OpenScale.getInstance(context);
        boolean doExpand = false;

        if (id > 0) {
            doExpand = prefs.getBoolean(PREF_EXPAND, true);

            // Show selected scale data
            if (scaleMeasurement ==  null) {
                ScaleMeasurement[] tupleScaleData = openScale.getTupleScaleData(id);
                previousMeasurement = tupleScaleData[0];
                scaleMeasurement = tupleScaleData[1].clone();
                nextMeasurement = tupleScaleData[2];

                btnLeft.setEnabled(previousMeasurement != null);
                btnRight.setEnabled(nextMeasurement != null);
            }
        } else {
            if (openScale.getScaleMeasurementList().isEmpty()) {
                // Show default values
                scaleMeasurement = new ScaleMeasurement();
                scaleMeasurement.setWeight(openScale.getSelectedScaleUser().getInitialWeight());
            }
            else {
                // Show the last scale data as default
                scaleMeasurement = openScale.getScaleMeasurementList().get(0).clone();
                scaleMeasurement.setId(0);
                scaleMeasurement.setDateTime(new Date());
                scaleMeasurement.setComment("");
            }

            isDirty = true;
        }

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.loadFrom(scaleMeasurement, previousMeasurement);
            measurement.setExpand(doExpand);
        }

        txtDataNr.setText(DateFormat.getDateTimeInstance(
            DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));
    }

    private void setViewMode(MeasurementView.MeasurementViewMode viewMode) {
        measurementViewMode = viewMode;
        int dateTimeVisibility = View.VISIBLE;

        switch (viewMode) {
            case VIEW:
                saveButton.setVisible(false);
                editButton.setVisible(true);
                expandButton.setVisible(true);
                deleteButton.setVisible(true);

                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);

                dateTimeVisibility = View.GONE;
                break;
            case EDIT:
                saveButton.setVisible(true);
                saveButton.setTitle(R.string.save);

                editButton.setVisible(false);
                expandButton.setVisible(true);
                deleteButton.setVisible(true);

                btnLeft.setVisibility(View.GONE);
                btnRight.setVisibility(View.GONE);
                break;
            case ADD:
                saveButton.setVisible(true);
                saveButton.setTitle(R.string.label_add);

                editButton.setVisible(false);
                expandButton.setVisible(false);
                deleteButton.setVisible(false);

                btnLeft.setVisibility(View.GONE);
                btnRight.setVisibility(View.GONE);
                break;
        }

        for (MeasurementView measurement : dataEntryMeasurements) {
            if (measurement instanceof DateMeasurementView || measurement instanceof TimeMeasurementView) {
                measurement.setVisibility(dateTimeVisibility);
            }
            measurement.setEditMode(viewMode);
        }
    }

    private void saveScaleData() {
        if (!isDirty) {
            return;
        }

        OpenScale openScale = OpenScale.getInstance(getApplicationContext());
        if (openScale.getSelectedScaleUserId() == -1) {
            AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

            infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));
            infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);
            infoDialog.show();
            return;
        }

        if (scaleMeasurement.getId() > 0) {
            openScale.updateScaleData(scaleMeasurement);
        }
        else {
            openScale.addScaleData(scaleMeasurement);
        }
        isDirty = false;
    }

    private void deleteMeasurement() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean deleteConfirmationEnable = prefs.getBoolean("deleteConfirmationEnable", true);

        if (deleteConfirmationEnable) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(context);
            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    doDeleteMeasurement();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
        else {
            doDeleteMeasurement();
        }
    }

    private void doDeleteMeasurement() {
        OpenScale.getInstance(getApplicationContext()).deleteScaleData(scaleMeasurement.getId());
        Toast.makeText(context, getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

        final boolean hasNext = moveLeft() || moveRight();
        if (!hasNext) {
            finish();
        }
        else if (measurementViewMode == MeasurementView.MeasurementViewMode.EDIT) {
            setViewMode(MeasurementView.MeasurementViewMode.VIEW);
        }
    }

    private boolean moveLeft() {
        if (previousMeasurement != null) {
            getIntent().putExtra(EXTRA_ID, previousMeasurement.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private boolean moveRight() {
        if (nextMeasurement != null) {
            getIntent().putExtra(EXTRA_ID, nextMeasurement.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private class onMeasurementViewUpdateListener implements MeasurementViewUpdateListener {
        @Override
        public void onMeasurementViewUpdate(MeasurementView view) {
            view.saveTo(scaleMeasurement);
            isDirty = true;

            txtDataNr.setText(DateFormat.getDateTimeInstance(
                    DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));

            for (MeasurementView measurement : dataEntryMeasurements) {
                if (measurement != view) {
                    measurement.loadFrom(scaleMeasurement, previousMeasurement);
                }
            }
        }
    }
}
