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
package com.health.openscale.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.ListIterator;

public class DataEntryActivity extends Activity {
    public static final int ADD_DATA_REQUEST = 0;
    public static final int EDIT_DATA_REQUEST = 1;

    private ArrayList<MeasurementView> dataEntryMeasurements;
    private TableLayout tableLayoutDataEntry;

    private WeightMeasurementView weightMeasurement;
    private WaterMeasurementView waterMeasurement;
    private MuscleMeasurementView muscleMeasurement;
    private FatMeasurementView fatMeasurement;
    private WaistMeasurementView waistMeasurement;
    private HipMeasurementView hipMeasurement;

    private EditText txtDate;
    private EditText txtTime;
    private EditText txtComment;

    private TextView txtDataNr;
    private Button btnAdd;
    private Button btnOk;
    private Button btnCancel;
    private Button btnDelete;
    private Button btnLeft;
    private Button btnRight;

	private SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
	private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

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
        waterMeasurement = new WaterMeasurementView(context);
        muscleMeasurement = new MuscleMeasurementView(context);
        fatMeasurement = new FatMeasurementView(context);
        waistMeasurement = new WaistMeasurementView(context);
        hipMeasurement = new HipMeasurementView(context);

        dataEntryMeasurements = new ArrayList<>();
        dataEntryMeasurements.add(weightMeasurement);
        dataEntryMeasurements.add(waterMeasurement);
        dataEntryMeasurements.add(muscleMeasurement);
        dataEntryMeasurements.add(fatMeasurement);
        dataEntryMeasurements.add(waistMeasurement);
        dataEntryMeasurements.add(hipMeasurement);

        Collections.reverse(dataEntryMeasurements);

        for (MeasurementView measuremt : dataEntryMeasurements) {
            tableLayoutDataEntry.addView(measuremt, 0);
            measuremt.setEditMode(true);
        }

        txtDataNr = (TextView) findViewById(R.id.txtDataNr);
        txtDate = (EditText) findViewById(R.id.txtDate);
        txtTime = (EditText) findViewById(R.id.txtTime);
        txtComment = (EditText) findViewById(R.id.txtComment);

		btnAdd = (Button) findViewById(R.id.btnAdd);
		btnOk = (Button) findViewById(R.id.btnOk);
        btnCancel = (Button) findViewById(R.id.btnCancel);
        btnDelete = (Button) findViewById(R.id.btnDelete);
        btnLeft = (Button) findViewById(R.id.btnLeft);
        btnRight = (Button) findViewById(R.id.btnRight);

        btnAdd.setOnClickListener(new onClickListenerAdd());
        btnOk.setOnClickListener(new onClickListenerOk());
        btnCancel.setOnClickListener(new onClickListenerCancel());
        btnDelete.setOnClickListener(new onClickListenerDelete());
        btnLeft.setOnClickListener(new onClickListenerLeft());
        btnRight.setOnClickListener(new onClickListenerRight());

        txtDate.setOnFocusChangeListener(new onFocusChangeDate());
        txtTime.setOnFocusChangeListener(new onFocusChangeTime());

        updateOnView();
	}


    private void updateOnView()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (MeasurementView measuremt : dataEntryMeasurements) {
            measuremt.updatePreferences(prefs);
        }

        if (getIntent().getExtras().getInt("mode") == EDIT_DATA_REQUEST) {
            editMode();
        }
        else
        {
            addMode();
        }
    }

    private void editMode()
    {
        btnOk.setVisibility(View.VISIBLE);
        btnAdd.setVisibility(View.GONE);
        btnDelete.setVisibility(View.VISIBLE);
        btnLeft.setVisibility(View.VISIBLE);
        btnRight.setVisibility(View.VISIBLE);
        txtDataNr.setVisibility(View.VISIBLE);

        id = getIntent().getExtras().getLong("id");

        OpenScale openScale = OpenScale.getInstance(context);

        ScaleData editScaleData = openScale.getScaleData(id);

        txtDataNr.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(editScaleData.date_time));

        for (MeasurementView measuremt : dataEntryMeasurements) {
            measuremt.updateValue(editScaleData);
        }

        txtDate.setText(dateFormat.format(editScaleData.date_time));
        txtTime.setText(timeFormat.format(editScaleData.date_time));
    }

    private void addMode()
    {
        btnOk.setVisibility(View.GONE);
        btnAdd.setVisibility(View.VISIBLE);
        btnDelete.setVisibility(View.GONE);
        btnLeft.setVisibility(View.GONE);
        btnRight.setVisibility(View.GONE);
        txtDataNr.setVisibility(View.GONE);

        if (!OpenScale.getInstance(this).getScaleDataList().isEmpty())
        {
            ScaleData lastScaleData = OpenScale.getInstance(this).getScaleDataList().get(0);

            // show as default last scale data
            for (MeasurementView measuremt : dataEntryMeasurements) {
                measuremt.updateValue(lastScaleData);
            }
        } else {
            // show default values
            for (MeasurementView measuremt : dataEntryMeasurements) {
                measuremt.updateValue(new ScaleData());
            }
        }

        txtDate.setText(dateFormat.format(new Date()));
        txtTime.setText(timeFormat.format(new Date()));
    }

	private boolean validateAllInput()
	{
        boolean isValidate = true;

        for (MeasurementView measuremt : dataEntryMeasurements) {
            if (!measuremt.validateInput()) {
                isValidate = false;
            }
        }

		return isValidate;
	}

    private void saveScaleData() {
        if (validateAllInput()) {
            String comment = txtComment.getText().toString();

            String date = txtDate.getText().toString();
            String time = txtTime.getText().toString();

            OpenScale openScale = OpenScale.getInstance(context);

            openScale.updateScaleData(id,
                    date + " " + time,
                    weightMeasurement.getValue(),
                    fatMeasurement.getValue(),
                    waterMeasurement.getValue(),
                    muscleMeasurement.getValue(),
                    waistMeasurement.getValue(),
                    hipMeasurement.getValue(),
                    comment);
        }
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

   private DatePickerDialog.OnDateSetListener datePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
           //  txtDate.setText(String.format("%02d.%02d.%04d", selectedDay, selectedMonth+1, selectedYear));
           }
        };
        
    private TimePickerDialog.OnTimeSetListener timePickerListener = new TimePickerDialog.OnTimeSetListener() {
		@Override
		public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
		//	txtTime.setText(String.format("%02d:%02d", hourOfDay, minute));
		}
        };


    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (validateAllInput())
            {
                OpenScale openScale = OpenScale.getInstance(context);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                int selectedUserId  = prefs.getInt("selectedUserId", -1);

                if (selectedUserId == -1) {
                    AlertDialog.Builder infoDialog = new AlertDialog.Builder(context);

                    infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));

                    infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

                    infoDialog.show();
                } else {
                    String comment = txtComment.getText().toString();

                    String date = txtDate.getText().toString();
                    String time = txtTime.getText().toString();

                    openScale.addScaleData(selectedUserId,
                            date + " " + time,
                            weightMeasurement.getValue(),
                            fatMeasurement.getValue(),
                            waterMeasurement.getValue(),
                            muscleMeasurement.getValue(),
                            waistMeasurement.getValue(),
                            hipMeasurement.getValue(),
                            comment);

                    finish();
                }
            }
        }
    }

    private class onClickListenerOk implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (validateAllInput()) {
                saveScaleData();
                finish();
            }
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

    private class onFocusChangeDate implements View.OnFocusChangeListener {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                Calendar cal = Calendar.getInstance();

                if (getIntent().getExtras().getInt("mode") == EDIT_DATA_REQUEST) {
                    OpenScale openScale = OpenScale.getInstance(context);
                    ScaleData editScaleData = openScale.getScaleData(id);
                    cal.setTime(editScaleData.date_time);
                }

                DatePickerDialog datePicker = new DatePickerDialog(context, datePickerListener, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                datePicker.show();
            }
        }
    }

    private class onFocusChangeTime implements View.OnFocusChangeListener {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                Calendar cal = Calendar.getInstance();

                if (getIntent().getExtras().getInt("mode") == EDIT_DATA_REQUEST) {
                    OpenScale openScale = OpenScale.getInstance(context);
                    ScaleData editScaleData = openScale.getScaleData(id);
                    cal.setTime(editScaleData.date_time);
                }

                TimePickerDialog timePicker = new TimePickerDialog(context, timePickerListener, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true);
                timePicker.show();
            }
        }
    }
}
