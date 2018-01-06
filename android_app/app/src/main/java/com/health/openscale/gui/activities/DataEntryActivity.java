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
import com.health.openscale.core.datatypes.ScaleUser;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import lecho.lib.hellocharts.util.ChartUtils;

public class DataEntryActivity extends Activity {
    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private WeightMeasurementView weightMeasurement;
    private BMIMeasurementView bmiMeasurementView;
    private WaterMeasurementView waterMeasurement;
    private MuscleMeasurementView muscleMeasurement;
    private LBWMeasurementView lbwMeasurement;
    private FatMeasurementView fatMeasurement;
    private WaistMeasurementView waistMeasurement;
    private WHtRMeasurementView wHtRMeasurementView;
    private HipMeasurementView hipMeasurement;
    private WHRMeasurementView whrMeasurementView;
    private BMRMeasurementView bmrMeasurementView;
    private BoneMeasurementView boneMeasurementView;
    private CommentMeasurementView commentMeasurement;
    private DateMeasurementView dateMeasurement;
    private TimeMeasurementView timeMeasurement;

    private TextView txtDataNr;
    private Button btnAdd;
    private Button btnOk;
    private Button btnCancel;
    private Button btnLeft;
    private Button btnRight;
    private FloatingActionButton imageViewDelete;
    private FloatingActionButton switchEditMode;
    private FloatingActionButton expandButton;

    private int id;

    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_dataentry);

        context = this;

        tableLayoutDataEntry = (TableLayout) findViewById(R.id.tableLayoutDataEntry);

        weightMeasurement = new WeightMeasurementView(context);
        bmiMeasurementView = new BMIMeasurementView(context);
        waterMeasurement = new WaterMeasurementView(context);
        muscleMeasurement = new MuscleMeasurementView(context);
        lbwMeasurement = new LBWMeasurementView(context);
        fatMeasurement = new FatMeasurementView(context);
        boneMeasurementView = new BoneMeasurementView(context);
        waistMeasurement = new WaistMeasurementView(context);
        wHtRMeasurementView = new WHtRMeasurementView(context);
        hipMeasurement = new HipMeasurementView(context);
        whrMeasurementView = new WHRMeasurementView(context);
        bmrMeasurementView = new BMRMeasurementView(context);
        commentMeasurement = new CommentMeasurementView(context);
        dateMeasurement = new DateMeasurementView(context);
        timeMeasurement = new TimeMeasurementView(context);

        dataEntryMeasurements = new ArrayList<>();
        dataEntryMeasurements.add(weightMeasurement);
        dataEntryMeasurements.add(bmiMeasurementView);
        dataEntryMeasurements.add(waterMeasurement);
        dataEntryMeasurements.add(muscleMeasurement);
        dataEntryMeasurements.add(lbwMeasurement);
        dataEntryMeasurements.add(fatMeasurement);
        dataEntryMeasurements.add(boneMeasurementView);
        dataEntryMeasurements.add(waistMeasurement);
        dataEntryMeasurements.add(wHtRMeasurementView);
        dataEntryMeasurements.add(hipMeasurement);
        dataEntryMeasurements.add(whrMeasurementView);
        dataEntryMeasurements.add(bmrMeasurementView);
        dataEntryMeasurements.add(commentMeasurement);
        dataEntryMeasurements.add(dateMeasurement);
        dataEntryMeasurements.add(timeMeasurement);

        Collections.reverse(dataEntryMeasurements);

        for (MeasurementView measurement : dataEntryMeasurements) {
            tableLayoutDataEntry.addView(measurement, 0);
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


    private void updateOnView()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.setOnUpdateListener(null);
            measurement.updatePreferences(prefs);
        }

        if (getIntent().hasExtra("id")) {
            id = getIntent().getExtras().getInt("id");
        }

        ScaleMeasurement scaleMeasurement;

        if (id > 0) {
            // keep edit mode state if we are moving to left or right
            if (prefs.getBoolean(String.valueOf(switchEditMode.getId()), false)) {
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_GREEN));
            } else {
                setViewMode(MeasurementView.MeasurementViewMode.VIEW);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

            final boolean doExpand = prefs.getBoolean(String.valueOf(expandButton.getId()), false);
            if (doExpand) {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_ORANGE));
            } else {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

            OpenScale openScale = OpenScale.getInstance(context);

            ScaleMeasurement[] tupleScaleData = openScale.getTupleScaleData(id);
            ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];
            scaleMeasurement = tupleScaleData[1];

            if (prevScaleMeasurement == null) {
                prevScaleMeasurement = new ScaleMeasurement();
            }

            // show selected scale data
            for (MeasurementView measurement : dataEntryMeasurements) {
                measurement.updateValue(scaleMeasurement);
                measurement.updateDiff(scaleMeasurement, prevScaleMeasurement);
                measurement.setExpand(doExpand);
            }
        } else {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);

            if (OpenScale.getInstance(getApplicationContext()).getScaleMeasurementList().isEmpty()) {
                // Show default values
                scaleMeasurement = new ScaleMeasurement();
            }
            else {
                // Show the last scale data as default
                scaleMeasurement = OpenScale.getInstance(getApplicationContext()).getScaleMeasurementList().get(0);
                scaleMeasurement.setDateTime(new Date());
                scaleMeasurement.setComment("");
            }

            for (MeasurementView measurement : dataEntryMeasurements) {
                measurement.updateValue(scaleMeasurement);
            }
        }

        txtDataNr.setText(DateFormat.getDateTimeInstance(
            DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));

        onMeasurementViewUpdateListener updateListener = new onMeasurementViewUpdateListener();
        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.setOnUpdateListener(updateListener);
        }
    }

    private void setViewMode(MeasurementView.MeasurementViewMode viewMode)
    {
        switch (viewMode) {
            case VIEW:
                btnOk.setVisibility(View.VISIBLE);
                btnAdd.setVisibility(View.GONE);
                imageViewDelete.setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                expandButton.setVisibility(View.VISIBLE);
                switchEditMode.setVisibility(View.VISIBLE);
                dateMeasurement.setVisibility(View.GONE);
                timeMeasurement.setVisibility(View.GONE);
                break;
            case EDIT:
                btnOk.setVisibility(View.VISIBLE);
                btnAdd.setVisibility(View.GONE);
                imageViewDelete.setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                expandButton.setVisibility(View.VISIBLE);
                switchEditMode.setVisibility(View.VISIBLE);
                dateMeasurement.setVisibility(View.VISIBLE);
                timeMeasurement.setVisibility(View.VISIBLE);
                break;
            case ADD:
                btnOk.setVisibility(View.GONE);
                btnAdd.setVisibility(View.VISIBLE);
                imageViewDelete.setVisibility(View.GONE);
                btnLeft.setVisibility(View.GONE);
                btnRight.setVisibility(View.GONE);
                expandButton.setVisibility(View.GONE);
                switchEditMode.setVisibility(View.GONE);
                dateMeasurement.setVisibility(View.VISIBLE);
                timeMeasurement.setVisibility(View.VISIBLE);
                break;
        }

        for (MeasurementView measurement : dataEntryMeasurements) {
            measurement.setEditMode(viewMode);
        }
    }

    private ScaleMeasurement createScaleDataFromMeasurement() {
        OpenScale openScale = OpenScale.getInstance(getApplicationContext());
        ScaleUser user = openScale.getSelectedScaleUser();

        Calendar time = Calendar.getInstance();
        time.setTime(timeMeasurement.getDateTime());

        Calendar cal = Calendar.getInstance();
        cal.setTime(dateMeasurement.getDateTime());
        cal.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY));
        cal.set(Calendar.MINUTE, time.get(Calendar.MINUTE));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        scaleMeasurement.setUserId(user.getId());
        scaleMeasurement.setDateTime(cal.getTime());
        scaleMeasurement.setConvertedWeight(weightMeasurement.getValue(), user.getScaleUnit());
        scaleMeasurement.setFat(fatMeasurement.getValue());
        scaleMeasurement.setWater(waterMeasurement.getValue());
        scaleMeasurement.setMuscle(muscleMeasurement.getValue());
        scaleMeasurement.setLbw(lbwMeasurement.getValue());
        scaleMeasurement.setWaist(waistMeasurement.getValue());
        scaleMeasurement.setHip(hipMeasurement.getValue());
        scaleMeasurement.setBone(boneMeasurementView.getValue());
        scaleMeasurement.setComment(commentMeasurement.getValueAsString());

        return scaleMeasurement;
    }

    private void saveScaleData() {
        ScaleMeasurement scaleMeasurement = createScaleDataFromMeasurement();

        scaleMeasurement.setId(id);

        OpenScale openScale = OpenScale.getInstance(getApplicationContext());
        openScale.updateScaleData(scaleMeasurement);
    }

    private boolean moveLeft() {
        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance(getApplicationContext()).getTupleScaleData(id);
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        if (prevScaleMeasurement != null) {
            saveScaleData();
            getIntent().putExtra("id", prevScaleMeasurement.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private boolean moveRight()
    {
        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance(getApplicationContext()).getTupleScaleData(id);
        ScaleMeasurement nextScaleMeasurement = tupleScaleData[2];

        if (nextScaleMeasurement != null) {
            saveScaleData();
            getIntent().putExtra("id", nextScaleMeasurement.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private class onMeasurementViewUpdateListener implements MeasurementViewUpdateListener {
        @Override
        public void onMeasurementViewUpdate(MeasurementView view) {
            ArrayList<MeasurementView> viewsToUpdate = new ArrayList<>();
            if (view == weightMeasurement) {
                viewsToUpdate.add(bmiMeasurementView);
                viewsToUpdate.add(bmrMeasurementView);
            } else if (view == waistMeasurement) {
                viewsToUpdate.add(wHtRMeasurementView);
                viewsToUpdate.add(whrMeasurementView);
            } else if (view == hipMeasurement) {
                viewsToUpdate.add(whrMeasurementView);
            } else if (view == dateMeasurement) {
                viewsToUpdate.add(bmrMeasurementView);
            }

            if (!viewsToUpdate.isEmpty()) {
                ScaleMeasurement scaleMeasurement = createScaleDataFromMeasurement();
                for (MeasurementView measurement : viewsToUpdate) {
                    measurement.updateValue(scaleMeasurement);
                }
            }
        }
    }

    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int selectedUserId = prefs.getInt("selectedUserId", -1);

            if (selectedUserId == -1) {
                AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

                infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                infoDialog.show();
            } else {
                ScaleMeasurement scaleMeasurement = createScaleDataFromMeasurement();

                OpenScale openScale = OpenScale.getInstance(getApplicationContext());
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
            int delId = id;

            boolean hasNext = moveLeft();

            OpenScale.getInstance(getApplicationContext()).deleteScaleData(delId);
            Toast.makeText(context, getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

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
