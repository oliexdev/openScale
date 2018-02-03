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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
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

import lecho.lib.hellocharts.util.ChartUtils;

public class DataEntryActivity extends Activity {
    public static String EXTRA_ID = "id";

    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private TextView txtDataNr;
    private Button btnAdd;
    private Button btnOk;
    private Button btnCancel;
    private Button btnLeft;
    private Button btnRight;
    private FloatingActionButton imageViewDelete;
    private FloatingActionButton switchEditMode;
    private FloatingActionButton expandButton;

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

        btnAdd = (Button) findViewById(R.id.btnAdd);
        btnOk = (Button) findViewById(R.id.btnOk);
        btnCancel = (Button) findViewById(R.id.btnCancel);
        btnLeft = (Button) findViewById(R.id.btnLeft);
        btnRight = (Button) findViewById(R.id.btnRight);
        imageViewDelete = (FloatingActionButton) findViewById(R.id.imgViewDelete);
        switchEditMode = (FloatingActionButton) findViewById(R.id.switchEditMode);
        expandButton = (FloatingActionButton) findViewById(R.id.expandButton);

        btnAdd.setOnClickListener(new onClickListenerAdd());
        btnOk.setOnClickListener(new onClickListenerOk());
        btnCancel.setOnClickListener(new onClickListenerCancel());
        imageViewDelete.setOnClickListener(new onClickListenerDelete());
        btnLeft.setOnClickListener(new onClickListenerLeft());
        btnRight.setOnClickListener(new onClickListenerRight());
        switchEditMode.setOnClickListener(new onClickListenerToggleButton());
        expandButton.setOnClickListener(new onClickListenerToggleButton());

        updateOnView();
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

    private void updateOnView()
    {
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
            // keep edit mode state if we are moving to left or right
            if (prefs.getBoolean(String.valueOf(switchEditMode.getId()), false)) {
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_GREEN));
            } else {
                setViewMode(MeasurementView.MeasurementViewMode.VIEW);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

            doExpand = prefs.getBoolean(String.valueOf(expandButton.getId()), false);
            if (doExpand) {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_ORANGE));
            } else {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

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
            setViewMode(MeasurementView.MeasurementViewMode.ADD);

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
        }

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.loadFrom(scaleMeasurement, previousMeasurement);
            measurement.setExpand(doExpand);
        }

        txtDataNr.setText(DateFormat.getDateTimeInstance(
            DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));
    }

    private void setViewMode(MeasurementView.MeasurementViewMode viewMode)
    {
        int dateTimeVisibility = View.VISIBLE;

        switch (viewMode) {
            case VIEW:
                btnOk.setVisibility(View.VISIBLE);
                btnAdd.setVisibility(View.GONE);
                imageViewDelete.setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                expandButton.setVisibility(View.VISIBLE);
                switchEditMode.setVisibility(View.VISIBLE);
                dateTimeVisibility = View.GONE;
                break;
            case EDIT:
                btnOk.setVisibility(View.VISIBLE);
                btnAdd.setVisibility(View.GONE);
                imageViewDelete.setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                expandButton.setVisibility(View.VISIBLE);
                switchEditMode.setVisibility(View.VISIBLE);
                break;
            case ADD:
                btnOk.setVisibility(View.GONE);
                btnAdd.setVisibility(View.VISIBLE);
                imageViewDelete.setVisibility(View.GONE);
                btnLeft.setVisibility(View.GONE);
                btnRight.setVisibility(View.GONE);
                expandButton.setVisibility(View.GONE);
                switchEditMode.setVisibility(View.GONE);
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
        if (isDirty) {
            OpenScale openScale = OpenScale.getInstance(getApplicationContext());
            openScale.updateScaleData(scaleMeasurement);
            isDirty = false;
        }
    }

    private boolean moveLeft() {
        if (previousMeasurement != null) {
            saveScaleData();
            getIntent().putExtra(EXTRA_ID, previousMeasurement.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private boolean moveRight() {
        if (nextMeasurement != null) {
            saveScaleData();
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

            for (MeasurementView measurement : dataEntryMeasurements) {
                if (measurement != view) {
                    measurement.loadFrom(scaleMeasurement, previousMeasurement);
                }
            }
        }
    }

    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            OpenScale openScale = OpenScale.getInstance(getApplicationContext());

            int selectedUserId = openScale.getSelectedScaleUserId();

            if (selectedUserId == -1) {
                AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

                infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                infoDialog.show();
            } else {
                for (MeasurementView measurement : dataEntryMeasurements) {
                    measurement.saveTo(scaleMeasurement);
                }

                openScale.addScaleData(scaleMeasurement);

                finish();
            }
        }
    }

    private class onClickListenerOk implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            saveScaleData();
            finish();
        }
    }

    private class onClickListenerLeft implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            moveLeft();
        }
    }

    private class onClickListenerRight implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            moveRight();
        }
    }

    private class onClickListenerCancel implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            finish();
        }
    }

    private class onClickListenerDelete implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());
            boolean deleteConfirmationEnable  = prefs.getBoolean("deleteConfirmationEnable", true);

            if (deleteConfirmationEnable) {
                AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(v.getContext());
                deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete));

                deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        deleteMeasurement();
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
                deleteMeasurement();
            }
        }

        void deleteMeasurement() {
            int delId = scaleMeasurement.getId();

            OpenScale.getInstance(getApplicationContext()).deleteScaleData(delId);
            Toast.makeText(context, getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

            isDirty = false;
            final boolean hasNext = moveLeft() || moveRight();

            if (!hasNext) {
                finish();
            }
        }
    }

    private class onClickListenerToggleButton implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            FloatingActionButton actionButton = (FloatingActionButton) v;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(v.getContext());

            if (prefs.getBoolean(String.valueOf(actionButton.getId()), false)) {
                prefs.edit().putBoolean(String.valueOf(actionButton.getId()), false).commit();
            } else {
                prefs.edit().putBoolean(String.valueOf(actionButton.getId()), true).commit();
            }

            updateOnView();
        }
    }
}
