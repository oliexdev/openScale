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
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.CommentMeasurementView;
import com.health.openscale.gui.views.DateMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.TimeMeasurementView;
import com.health.openscale.gui.views.WHRMeasurementView;
import com.health.openscale.gui.views.WHtRMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.ListIterator;

public class DataEntryActivity extends Activity {
    public static final int ADD_DATA_REQUEST = 0;
    public static final int EDIT_DATA_REQUEST = 1;

    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private WeightMeasurementView weightMeasurement;
    private BMIMeasurementView bmiMeasurementView;
    private WaterMeasurementView waterMeasurement;
    private MuscleMeasurementView muscleMeasurement;
    private FatMeasurementView fatMeasurement;
    private WaistMeasurementView waistMeasurement;
    private WHtRMeasurementView wHtRMeasurementView;
    private HipMeasurementView hipMeasurement;
    private WHRMeasurementView whrMeasurementView;
    private CommentMeasurementView commentMeasurement;
    private DateMeasurementView dateMeasurement;
    private TimeMeasurementView timeMeasurement;

    private TextView txtDataNr;
    private Button btnAdd;
    private Button btnOk;
    private Button btnCancel;
    private Button btnLeft;
    private Button btnRight;
    private ImageView imageViewDelete;
    private Switch switchEditMode;

    private long id;

	private Context context;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

		setContentView(R.layout.activity_dataentry);

		context = this;

        tableLayoutDataEntry = (TableLayout) findViewById(R.id.tableLayoutDataEntry);

        weightMeasurement = new WeightMeasurementView(context);
        bmiMeasurementView = new BMIMeasurementView(context);
        waterMeasurement = new WaterMeasurementView(context);
        muscleMeasurement = new MuscleMeasurementView(context);
        fatMeasurement = new FatMeasurementView(context);
        waistMeasurement = new WaistMeasurementView(context);
        wHtRMeasurementView = new WHtRMeasurementView(context);
        hipMeasurement = new HipMeasurementView(context);
        whrMeasurementView = new WHRMeasurementView(context);
        commentMeasurement = new CommentMeasurementView(context);
        dateMeasurement = new DateMeasurementView(context);
        timeMeasurement = new TimeMeasurementView(context);

        dataEntryMeasurements = new ArrayList<>();
        dataEntryMeasurements.add(weightMeasurement);
        dataEntryMeasurements.add(bmiMeasurementView);
        dataEntryMeasurements.add(waterMeasurement);
        dataEntryMeasurements.add(muscleMeasurement);
        dataEntryMeasurements.add(fatMeasurement);
        dataEntryMeasurements.add(waistMeasurement);
        dataEntryMeasurements.add(wHtRMeasurementView);
        dataEntryMeasurements.add(hipMeasurement);
        dataEntryMeasurements.add(whrMeasurementView);
        dataEntryMeasurements.add(commentMeasurement);
        dataEntryMeasurements.add(dateMeasurement);
        dataEntryMeasurements.add(timeMeasurement);

        Collections.reverse(dataEntryMeasurements);

        for (MeasurementView measuremt : dataEntryMeasurements) {
            tableLayoutDataEntry.addView(measuremt, 0);
        }

        txtDataNr = (TextView) findViewById(R.id.txtDataNr);

		btnAdd = (Button) findViewById(R.id.btnAdd);
		btnOk = (Button) findViewById(R.id.btnOk);
        btnCancel = (Button) findViewById(R.id.btnCancel);
        btnLeft = (Button) findViewById(R.id.btnLeft);
        btnRight = (Button) findViewById(R.id.btnRight);
        imageViewDelete = (ImageView) findViewById(R.id.imgViewDelete);
        switchEditMode = (Switch) findViewById(R.id.switchEditMode);

        btnAdd.setOnClickListener(new onClickListenerAdd());
        btnOk.setOnClickListener(new onClickListenerOk());
        btnCancel.setOnClickListener(new onClickListenerCancel());
        imageViewDelete.setOnClickListener(new onClickListenerDelete());
        btnLeft.setOnClickListener(new onClickListenerLeft());
        btnRight.setOnClickListener(new onClickListenerRight());
        switchEditMode.setOnCheckedChangeListener(new onCheckedChangeEditMode());

        updateOnView();
	}


    private void updateOnView()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (MeasurementView measuremt : dataEntryMeasurements) {
            measuremt.updatePreferences(prefs);
        }

        id = getIntent().getExtras().getLong("id");

        if (id > 0) {
            if (switchEditMode.isChecked()) {
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
            } else {
                setViewMode(MeasurementView.MeasurementViewMode.VIEW);
            }
            OpenScale openScale = OpenScale.getInstance(context);

            ScaleData selectedScaleData = openScale.getScaleData(id);

            txtDataNr.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(selectedScaleData.date_time));

            ArrayList<ScaleData> scaleDataList = OpenScale.getInstance(context).getScaleDataList();
            ListIterator<ScaleData> scaleDataIterator = scaleDataList.listIterator();

            ScaleData lastData = new ScaleData();

            while(scaleDataIterator.hasNext()) {
                ScaleData scaleData = scaleDataIterator.next();

                if (scaleData.id == id) {
                    if (scaleDataIterator.hasNext()) {
                        lastData = scaleDataIterator.next();
                    }
                }
            }

            // show selected scale data
            for (MeasurementView measuremt : dataEntryMeasurements) {
                measuremt.updateValue(selectedScaleData);
                measuremt.updateDiff(selectedScaleData, lastData);
            }

            return;
        }


        if (!OpenScale.getInstance(this).getScaleDataList().isEmpty())
        {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
            ScaleData lastScaleData = OpenScale.getInstance(this).getScaleDataList().get(0);

            // show as default last scale data
            for (MeasurementView measuremt : dataEntryMeasurements) {
                lastScaleData.date_time = new Date();
                lastScaleData.comment = "";
                measuremt.updateValue(lastScaleData);
            }
        } else {
            setViewMode(MeasurementView.MeasurementViewMode.ADD);
            // show default values
            for (MeasurementView measuremt : dataEntryMeasurements) {
                measuremt.updateValue(new ScaleData());
            }
        }
    }

    private void setViewMode(MeasurementView.MeasurementViewMode viewMode)
    {
        switch (viewMode) {
            case VIEW:
            case EDIT:
                btnOk.setVisibility(View.VISIBLE);
                btnAdd.setVisibility(View.GONE);
                imageViewDelete.setVisibility(View.VISIBLE);
                btnLeft.setVisibility(View.VISIBLE);
                btnRight.setVisibility(View.VISIBLE);
                txtDataNr.setVisibility(View.VISIBLE);
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
                txtDataNr.setVisibility(View.GONE);
                switchEditMode.setVisibility(View.GONE);
                dateMeasurement.setVisibility(View.GONE);
                timeMeasurement.setVisibility(View.GONE);
                break;
        }

        for (MeasurementView measuremt : dataEntryMeasurements) {
            measuremt.setEditMode(viewMode);
        }
    }

    private void saveScaleData() {
        OpenScale openScale = OpenScale.getInstance(context);

        openScale.updateScaleData(id,
                dateMeasurement.getValueAsString() + " " + timeMeasurement.getValueAsString(),
                weightMeasurement.getValue(),
                fatMeasurement.getValue(),
                waterMeasurement.getValue(),
                muscleMeasurement.getValue(),
                waistMeasurement.getValue(),
                hipMeasurement.getValue(),
                commentMeasurement.getValueAsString());
    }

    private boolean moveLeft() {
        ArrayList<ScaleData> scaleDataList = OpenScale.getInstance(context).getScaleDataList();

        ListIterator<ScaleData> scaleDataIterator = scaleDataList.listIterator();

        while(scaleDataIterator.hasNext())
        {
            ScaleData scaleData = scaleDataIterator.next();

            if (scaleData.id == id)
            {
                if (scaleDataIterator.hasNext()) {
                    saveScaleData();
                    getIntent().putExtra("id",scaleDataIterator.next().id );
                    updateOnView();
                    return true;
                } else {
                    return false;
                }

            }
        }

        return false;
    }

    private boolean moveRight()
    {
        ArrayList<ScaleData> scaleDataList = OpenScale.getInstance(context).getScaleDataList();

        ListIterator<ScaleData> scaleDataIterator = scaleDataList.listIterator(scaleDataList.size());

        while(scaleDataIterator.hasPrevious())
        {
            ScaleData scaleData = scaleDataIterator.previous();

            if (scaleData.id == id)
            {
                if (scaleDataIterator.hasPrevious()) {
                    saveScaleData();
                    getIntent().putExtra("id", scaleDataIterator.previous().id);
                    updateOnView();
                    return true;
                } else {
                    return false;
                }
            }
        }

        return false;
    }


    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            OpenScale openScale = OpenScale.getInstance(context);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int selectedUserId  = prefs.getInt("selectedUserId", -1);

            if (selectedUserId == -1) {
                AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

                infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                infoDialog.show();
            } else {
                openScale.addScaleData(selectedUserId,
                        dateMeasurement.getValueAsString() + " " + timeMeasurement.getValueAsString(),
                        weightMeasurement.getValue(),
                        fatMeasurement.getValue(),
                        waterMeasurement.getValue(),
                        muscleMeasurement.getValue(),
                        waistMeasurement.getValue(),
                        hipMeasurement.getValue(),
                        commentMeasurement.getValueAsString());

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

            OpenScale.getInstance(context).deleteScaleData(delId);
            Toast.makeText(context, getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

            if (!hasNext) {
                finish();
            }
        }
    }

    private class onCheckedChangeEditMode implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                setViewMode(MeasurementView.MeasurementViewMode.EDIT);
            } else {
                setViewMode(MeasurementView.MeasurementViewMode.VIEW);
            }
        }
    }
}
