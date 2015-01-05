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
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TimePicker;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class NewEntryActivity extends Activity {

	private EditText txtWeight;
	private EditText txtFat;
	private EditText txtWater;
	private EditText txtMuscle;
	private EditText txtDate;
	private EditText txtTime;
	
	private SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
	private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
	
	private Context context;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_newentry);
		context = this;
		
		txtWeight = (EditText) findViewById(R.id.txtWeight);
        txtWeight.setHint(getResources().getString(R.string.info_enter_value_unit) + " " + ScaleUser.UNIT_STRING[OpenScale.getInstance(context).getSelectedScaleUser().scale_unit]);
		txtFat = (EditText) findViewById(R.id.txtFat);
		txtWater = (EditText) findViewById(R.id.txtWater);
		txtMuscle = (EditText) findViewById(R.id.txtMuscle);
		txtDate = (EditText) findViewById(R.id.txtDate);
		txtTime = (EditText) findViewById(R.id.txtTime);
		Button btnDateSet = (Button) findViewById(R.id.btnDateSet);
		Button btnTimeSet = (Button) findViewById(R.id.btnTimeSet);
		
		findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickAdd();
            }
        });
		
		findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickCancel(); 
            }
        });
		
		btnDateSet.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Calendar cal = Calendar.getInstance();
				DatePickerDialog datePicker = new DatePickerDialog(context, datePickerListener, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
				datePicker.show();
			}
		});
		
		btnTimeSet.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Calendar cal = Calendar.getInstance();
				TimePickerDialog timePicker = new TimePickerDialog(context, timePickerListener, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true);
				timePicker.show();
			}
		});

        if (!OpenScale.getInstance(this).getScaleDataList().isEmpty())
        {
            ScaleData lastScaleData = OpenScale.getInstance(this).getScaleDataList().get(0);

            txtFat.setText(Float.toString(lastScaleData.fat));
            txtWater.setText(Float.toString(lastScaleData.water));
            txtMuscle.setText(Float.toString(lastScaleData.muscle));
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
	
	public void btnOnClickAdd()
	{
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

                String date = txtDate.getText().toString();
                String time = txtTime.getText().toString();

                openScale.addScaleData(selectedUserId, date + " " + time, weight, fat, water, muscle);

                finish();
            }
		}
	}

	public void btnOnClickCancel()
	{
		finish();
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
	
}
