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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewUpdateListener;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class DataEntryActivity extends BaseAppCompatActivity {
    public static final String EXTRA_ID = "id";
    private static final String PREF_EXPAND = "expandEvaluator";

    private MeasurementView.MeasurementViewMode measurementViewMode;

    private List<MeasurementView> dataEntryMeasurements;

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

    private boolean isAddActivity() {
        return !getIntent().hasExtra(EXTRA_ID);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_dataentry);

        Toolbar toolbar = findViewById(R.id.dataEntryToolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        context = this;

        TableLayout tableLayoutDataEntry = findViewById(R.id.tableLayoutDataEntry);

        dataEntryMeasurements = MeasurementView.getMeasurementList(
                context, MeasurementView.DateTimeOrder.LAST);

        txtDataNr = findViewById(R.id.txtDataNr);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);

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

        final MeasurementView.MeasurementViewMode mode = isAddActivity()
                ? MeasurementView.MeasurementViewMode.ADD
                : MeasurementView.MeasurementViewMode.VIEW;
        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.setEditMode(mode);
        }

        updateOnView();

        onMeasurementViewUpdateListener updateListener = new onMeasurementViewUpdateListener();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean expand = isAddActivity()
                ? false : prefs.getBoolean(PREF_EXPAND, false);

        for (MeasurementView measurement : dataEntryMeasurements) {
            tableLayoutDataEntry.addView(measurement);
            measurement.setOnUpdateListener(updateListener);
            measurement.setExpand(expand);
        }
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

        // Apply a tint to all icons in the toolbar
        for (int i = 0; i < menu.size(); ++i) {
            MenuItem item = menu.getItem(i);
            final Drawable drawable = item.getIcon();
            if (drawable == null) {
                continue;
            }

            final Drawable wrapped = DrawableCompat.wrap(drawable.mutate());

            if (item.getItemId() == R.id.saveButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#FFFFFF"));
            } else if (item.getItemId() == R.id.editButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#99CC00"));
            } else if (item.getItemId() == R.id.expandButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#FFBB33"));
            } else if (item.getItemId() == R.id.deleteButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#FF4444"));
            }

            item.setIcon(wrapped);
        }

        saveButton = menu.findItem(R.id.saveButton);
        editButton = menu.findItem(R.id.editButton);
        expandButton = menu.findItem(R.id.expandButton);
        deleteButton = menu.findItem(R.id.deleteButton);

        // Hide/show icons as appropriate for the view mode
        if (isAddActivity()) {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
        }
        else {
            setViewMode(MeasurementView.MeasurementViewMode.VIEW);
        }

        return super.onCreateOptionsMenu(menu);
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
                final boolean expand = !prefs.getBoolean(PREF_EXPAND, false);
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
            if (isDirty) {
                scaleMeasurement = null;
            }
            updateOnView();
        }
        else {
            super.onBackPressed();
        }
    }

    private void updateOnView() {
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

        OpenScale openScale = OpenScale.getInstance();

        if (id > 0) {
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

            // Measurements that aren't visible should not store any value. Since we use values from
            // the previous measurement there might be values for entries not shown. The loop below
            // clears these values.
            for (MeasurementView measurement : dataEntryMeasurements) {
                if (!measurement.isVisible()) {
                    measurement.clearIn(scaleMeasurement);
                }
            }
        }

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.loadFrom(scaleMeasurement, previousMeasurement);
        }

        txtDataNr.setMinWidth(txtDataNr.getWidth());
        txtDataNr.setText(DateFormat.getDateTimeInstance(
                DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));
    }

    private void setViewMode(MeasurementView.MeasurementViewMode viewMode) {
        measurementViewMode = viewMode;
        int dateTimeVisibility = View.VISIBLE;

        switch (viewMode) {
            case VIEW:
                getSupportActionBar().setTitle("");

                saveButton.setVisible(false);
                editButton.setVisible(true);
                expandButton.setVisible(true);
                deleteButton.setVisible(true);

                ((LinearLayout)txtDataNr.getParent()).setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                btnLeft.setEnabled(previousMeasurement != null);
                btnRight.setEnabled(nextMeasurement != null);

                dateTimeVisibility = View.GONE;
                break;
            case EDIT:
                getSupportActionBar().setTitle("");

                saveButton.setVisible(true);
                editButton.setVisible(false);
                expandButton.setVisible(true);
                deleteButton.setVisible(true);

                ((LinearLayout)txtDataNr.getParent()).setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                btnLeft.setEnabled(false);
                btnRight.setEnabled(false);
                break;
            case ADD:
                getSupportActionBar().setTitle(R.string.label_add_measurement);

                saveButton.setVisible(true);
                editButton.setVisible(false);
                expandButton.setVisible(false);
                deleteButton.setVisible(false);

                ((LinearLayout)txtDataNr.getParent()).setVisibility(View.GONE);
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

        OpenScale openScale = OpenScale.getInstance();
        if (openScale.getSelectedScaleUserId() == -1) {
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
        OpenScale.getInstance().deleteScaleData(scaleMeasurement.getId());
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

            // When weight is updated we may need to re-save some values that are stored
            // as percentages, but that the user may have set up to be shown as absolute.
            // Otherwise that measurement (e.g. fat) may change when weight is updated.
            if (view instanceof WeightMeasurementView) {
                for (MeasurementView measurement : dataEntryMeasurements) {
                    if (measurement != view) {
                        measurement.saveTo(scaleMeasurement);
                    }
                }
            }

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
