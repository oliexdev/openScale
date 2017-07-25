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
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.gui.views.CommentMeasurementView;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import lecho.lib.hellocharts.util.ChartUtils;

public class DataEntryActivity extends Activity {
    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private WeightMeasurementView weightMeasurement;
    private WaterMeasurementView waterMeasurement;
    private MuscleMeasurementView muscleMeasurement;
    private FatMeasurementView fatMeasurement;
    private WaistMeasurementView waistMeasurement;
    private HipMeasurementView hipMeasurement;
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

    private long id;

	private Context context;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_dataentry);

		context = this;

        tableLayoutDataEntry = (TableLayout) findViewById(R.id.tableLayoutDataEntry);

        weightMeasurement = new WeightMeasurementView(context);
        waterMeasurement = new WaterMeasurementView(context);
        muscleMeasurement = new MuscleMeasurementView(context);
        fatMeasurement = new FatMeasurementView(context);
        waistMeasurement = new WaistMeasurementView(context);
        hipMeasurement = new HipMeasurementView(context);
        commentMeasurement = new CommentMeasurementView(context);
        dateMeasurement = new DateMeasurementView(context);
        timeMeasurement = new TimeMeasurementView(context);

        dataEntryMeasurements = new ArrayList<>();
        dataEntryMeasurements.add(weightMeasurement);
        dataEntryMeasurements.add(waterMeasurement);
        dataEntryMeasurements.add(muscleMeasurement);
        dataEntryMeasurements.add(fatMeasurement);
        dataEntryMeasurements.add(waistMeasurement);
        dataEntryMeasurements.add(hipMeasurement);
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
            measurement.updatePreferences(prefs);
        }

        if (getIntent().hasExtra("id")) {
            id = getIntent().getExtras().getLong("id");
        }

        if (id > 0) {
            // keep edit mode state if we are moving to left or right
            if (prefs.getBoolean(String.valueOf(switchEditMode.getId()), false)) {
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_GREEN));
            } else {
                setViewMode(MeasurementView.MeasurementViewMode.VIEW);
                switchEditMode.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

            if (prefs.getBoolean(String.valueOf(expandButton.getId()), false)) {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_ORANGE));
            } else {
                expandButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D3D3D3")));
            }

            OpenScale openScale = OpenScale.getInstance(context);

            ScaleData[] tupleScaleData = openScale.getTupleScaleData(id);
            ScaleData prevScaleData = tupleScaleData[0];
            ScaleData selectedScaleData = tupleScaleData[1];

            if (prevScaleData == null) {
                prevScaleData = new ScaleData();
            }

            txtDataNr.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(selectedScaleData.getDateTime()));

            // show selected scale data
            for (MeasurementView measurement : dataEntryMeasurements) {
                measurement.setExpand(prefs.getBoolean(String.valueOf(expandButton.getId()), false));
                measurement.updateValue(selectedScaleData);
                measurement.updateDiff(selectedScaleData, prevScaleData);
            }

            return;
        }


        if (!OpenScale.getInstance(getApplicationContext()).getScaleDataList().isEmpty())
        {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
            txtDataNr.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(new Date()));
            ScaleData lastScaleData = OpenScale.getInstance(getApplicationContext()).getScaleDataList().get(0);

            // show as default last scale data
            for (MeasurementView measurement : dataEntryMeasurements) {
                lastScaleData.setDateTime(new Date());
                lastScaleData.setComment("");
                measurement.updateValue(lastScaleData);
            }
        } else {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
            // show default values
            for (MeasurementView measurement : dataEntryMeasurements) {
                measurement.updateValue(new ScaleData());
            }
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

    private void saveScaleData() {
        OpenScale openScale = OpenScale.getInstance(getApplicationContext());

        ScaleData scaleData = new ScaleData();

        scaleData.setId(id);
        scaleData.setUserId(openScale.getSelectedScaleUser().id);
        scaleData.setDateTime(dateMeasurement.getValueAsString() + " " + timeMeasurement.getValueAsString());
        scaleData.setConvertedWeight(weightMeasurement.getValue(), openScale.getSelectedScaleUser().scale_unit);
        scaleData.setFat(fatMeasurement.getValue());
        scaleData.setWater(waterMeasurement.getValue());
        scaleData.setMuscle(muscleMeasurement.getValue());
        scaleData.setWaist(waistMeasurement.getValue());
        scaleData.setHip(hipMeasurement.getValue());
        scaleData.setComment(commentMeasurement.getValueAsString());

        openScale.updateScaleData(scaleData);
    }

    private boolean moveLeft() {
        ScaleData[] tupleScaleData = OpenScale.getInstance(getApplicationContext()).getTupleScaleData(id);
        ScaleData prevScaleData = tupleScaleData[0];

        if (prevScaleData != null) {
            saveScaleData();
            getIntent().putExtra("id", prevScaleData.getId());
            updateOnView();
            return true;
        }

        return false;
    }

    private boolean moveRight()
    {
        ScaleData[] tupleScaleData = OpenScale.getInstance(getApplicationContext()).getTupleScaleData(id);
        ScaleData nextScaleData = tupleScaleData[2];

        if (nextScaleData != null) {
            saveScaleData();
            getIntent().putExtra("id", nextScaleData.getId());
            updateOnView();
            return true;
        }

        return false;
    }


    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            OpenScale openScale = OpenScale.getInstance(getApplicationContext());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int selectedUserId  = prefs.getInt("selectedUserId", -1);

            if (selectedUserId == -1) {
                AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

                infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                infoDialog.show();
            } else {
                ScaleData scaleData = new ScaleData();

                scaleData.setUserId(selectedUserId);
                scaleData.setDateTime( dateMeasurement.getValueAsString() + " " + timeMeasurement.getValueAsString());
                scaleData.setConvertedWeight(weightMeasurement.getValue(), openScale.getSelectedScaleUser().scale_unit);
                scaleData.setFat(fatMeasurement.getValue());
                scaleData.setWater(waterMeasurement.getValue());
                scaleData.setMuscle(muscleMeasurement.getValue());
                scaleData.setWaist(waistMeasurement.getValue());
                scaleData.setHip(hipMeasurement.getValue());
                scaleData.setComment(commentMeasurement.getValueAsString());

                openScale.addScaleData(scaleData);

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
            long delId = id;

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
