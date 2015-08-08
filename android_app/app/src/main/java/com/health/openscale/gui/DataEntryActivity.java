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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.ListIterator;

public class DataEntryActivity extends Activity {
    public static final int ADD_DATA_REQUEST = 0;
    public static final int EDIT_DATA_REQUEST = 1;

    private TextView txtDataNr;
	private EditText txtWeight;
	private EditText txtFat;
	private EditText txtWater;
	private EditText txtMuscle;
	private EditText txtDate;
	private EditText txtTime;
    private EditText txtComment;

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

        txtDataNr = (TextView) findViewById(R.id.txtDataNr);
		txtWeight = (EditText) findViewById(R.id.txtWeight);
        txtWeight.setHint(getResources().getString(R.string.info_enter_value_unit) + " " + ScaleUser.UNIT_STRING[OpenScale.getInstance(context).getSelectedScaleUser().scale_unit]);
		txtFat = (EditText) findViewById(R.id.txtFat);
		txtWater = (EditText) findViewById(R.id.txtWater);
		txtMuscle = (EditText) findViewById(R.id.txtMuscle);
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

        if(!prefs.getBoolean("fatEnable", true)) {
            TableRow row = (TableRow)findViewById(R.id.tableRowFat);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("muscleEnable", true)) {
            TableRow row = (TableRow)findViewById(R.id.tableRowMuscle);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waterEnable", true)) {
            TableRow row = (TableRow)findViewById(R.id.tableRowWater);
            row.setVisibility(View.GONE);
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
        txtWeight.setText(editScaleData.weight+"");
        txtFat.setText(editScaleData.fat+"");
        txtWater.setText(editScaleData.water+"");
        txtMuscle.setText(editScaleData.muscle+"");
        txtComment.setText(editScaleData.comment);

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

            txtFat.setText(Float.toString(lastScaleData.fat));
            txtWater.setText(Float.toString(lastScaleData.water));
            txtMuscle.setText(Float.toString(lastScaleData.muscle));
        } else {
            txtFat.setText(Float.toString(0.0f));
            txtWater.setText(Float.toString(0.0f));
            txtMuscle.setText(Float.toString(0.0f));
        }

        txtDate.setText(dateFormat.format(new Date()));
        txtTime.setText(timeFormat.format(new Date()));
    }

	private boolean validateInput()
	{
		boolean validate = true;
		
		if( txtWeight.getText().toString().length() == 0 )
		{
			txtWeight.setError(getResources().getString(R.string.error_weight_value_required));
			validate = false;
		} else if( !(Float.valueOf(txtWeight.getText().toString()) >= 0 && Float.valueOf(txtWeight.getText().toString()) <= 300) )
		{
			txtWeight.setError(getResources().getString(R.string.error_value_range_0_300));
			validate = false;
		}
		
		if( txtFat.getText().toString().length() == 0 )
		{
			txtFat.setError(getResources().getString(R.string.error_fat_value_required));
			validate = false;
		} else if(!isInRange(txtFat.getText().toString()))
		{
			txtFat.setError(getResources().getString(R.string.error_value_range_0_100));
			validate = false;
		}
		
		
		if( txtWater.getText().toString().length() == 0 )
		{
			txtWater.setError(getResources().getString(R.string.error_water_value_required));
			validate = false;
		} else if(!isInRange(txtWater.getText().toString()))
		{
			txtWater.setError(getResources().getString(R.string.error_value_range_0_100));
			validate = false;
		}
		
		if( txtMuscle.getText().toString().length() == 0 )
		{
			txtMuscle.setError(getResources().getString(R.string.error_muscle_value_required));
			validate = false;
		} else 	if(!isInRange(txtMuscle.getText().toString()))
		{
			txtMuscle.setError(getResources().getString(R.string.error_value_range_0_100));
			validate = false;
		}
		
		return validate;
	}
	
	private boolean isInRange(String value)
	{
		if (value.length() == 0)
			return false;
		
		float val = Float.valueOf(value);
		
		if (val >= 0 && val <= 100)
			return true;
		
		return false;
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
             txtDate.setText(String.format("%02d.%02d.%04d", selectedDay, selectedMonth+1, selectedYear));
           }
        };
        
    private TimePickerDialog.OnTimeSetListener timePickerListener = new TimePickerDialog.OnTimeSetListener() {
		@Override
		public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
			txtTime.setText(String.format("%02d:%02d", hourOfDay, minute));
		}
        };


    private class onClickListenerAdd implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (validateInput())
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
                    float weight = Float.valueOf(txtWeight.getText().toString());
                    float fat = Float.valueOf(txtFat.getText().toString());
                    float water = Float.valueOf(txtWater.getText().toString());
                    float muscle = Float.valueOf(txtMuscle.getText().toString());
                    String comment = txtComment.getText().toString();

                    String date = txtDate.getText().toString();
                    String time = txtTime.getText().toString();

                    openScale.addScaleData(selectedUserId, date + " " + time, weight, fat, water, muscle, comment);

                    finish();
                }
            }
        }
    }

    private class onClickListenerOk implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (validateInput()) {
                float weight = Float.valueOf(txtWeight.getText().toString());
                float fat = Float.valueOf(txtFat.getText().toString());
                float water = Float.valueOf(txtWater.getText().toString());
                float muscle = Float.valueOf(txtMuscle.getText().toString());
                String comment = txtComment.getText().toString();

                OpenScale openScale = OpenScale.getInstance(context);

                openScale.updateScaleData(id, weight, fat, water, muscle, comment);

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
