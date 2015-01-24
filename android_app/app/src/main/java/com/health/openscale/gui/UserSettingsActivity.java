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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.RadioGroup;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleUser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

public class UserSettingsActivity extends Activity {

    public static final int ADD_USER_REQUEST = 0;
    public static final int EDIT_USER_REQUEST = 1;

	private EditText txtUserName;
	private EditText txtBodyHeight;
	private EditText txtBirthday;
    private RadioGroup radioScaleUnit;

    private Button btnBirthdaySet;
    private Button btnOk;
    private Button btnCancel;
    private Button btnDelete;

	private SimpleDateFormat birthdayFormat = new SimpleDateFormat("dd.MM.yyyy");

	private Context context;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_usersettings);
		context = this;

        txtUserName = (EditText) findViewById(R.id.txtUserName);
		txtBodyHeight = (EditText) findViewById(R.id.txtBodyHeight);
        radioScaleUnit = (RadioGroup) findViewById(R.id.groupScaleUnit);

        txtBirthday = (EditText) findViewById(R.id.txtBirthday);

        btnBirthdaySet = (Button) findViewById(R.id.btnDateSet);
        btnDelete = (Button) findViewById(R.id.btnDelete);
        btnOk = (Button)findViewById(R.id.btnOk);
        btnCancel = (Button)findViewById(R.id.btnCancel);

        btnOk.setOnClickListener(new onClickListenerOk());
        btnCancel.setOnClickListener(new onClickListenerCancel());
        btnDelete.setOnClickListener(new onClickListenerDelete());
		btnBirthdaySet.setOnClickListener(new onClickListenerBirthdaySet());

        txtBirthday.setText(birthdayFormat.format(new Date()));

        if (getIntent().getExtras().getInt("mode") == EDIT_USER_REQUEST)
        {
            editMode();
        } else
        {
            btnOk.setText(getResources().getString(R.string.label_add));
            btnDelete.setVisibility(View.GONE);
        }
	}

    private void editMode()
    {
        int id = getIntent().getExtras().getInt("id");

        OpenScale openScale = OpenScale.getInstance(context);

        ScaleUser scaleUser = openScale.getScaleUser(id);

        txtUserName.setText(scaleUser.user_name);
        txtBodyHeight.setText(Integer.toString(scaleUser.body_height));
        txtBirthday.setText(birthdayFormat.format(scaleUser.birthday));

         switch (scaleUser.scale_unit)
        {
            case 0:
                radioScaleUnit.check(R.id.btnRadioKG);
                break;
            case 1:
                radioScaleUnit.check(R.id.btnRadioLB);
                break;
            case 2:
                radioScaleUnit.check(R.id.btnRadioST);
                break;
        }
    }

    private boolean validateInput()
    {
        boolean validate = true;

        if( txtUserName.getText().toString().length() == 0 )
        {
            txtUserName.setError(getResources().getString(R.string.error_user_name_required));
            validate = false;
        }

        if( txtBodyHeight.getText().toString().length() == 0 )
        {
            txtBodyHeight.setError(getResources().getString(R.string.error_body_height_required));
            validate = false;
        }

        return validate;
    }

   private DatePickerDialog.OnDateSetListener datePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            txtBirthday.setText(String.format("%02d.%02d.%04d", selectedDay, selectedMonth + 1, selectedYear));
           }
        };

    private class onClickListenerDelete implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(v.getContext());

            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_user));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    int userId = getIntent().getExtras().getInt("id");

                    OpenScale openScale = OpenScale.getInstance(context);
                    openScale.deleteScaleUser(userId);
                    openScale.clearScaleData(userId);

                    ArrayList<ScaleUser> scaleUser = openScale.getScaleUserList();

                    int lastUserId = -1;

                    if (!scaleUser.isEmpty()) {
                        lastUserId = scaleUser.get(0).id;
                    }

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    prefs.edit().putInt("selectedUserId", lastUserId).commit();

                    Intent returnIntent = new Intent();
                    setResult(RESULT_OK, returnIntent);

                    finish();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
    }

    private class onClickListenerOk implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (validateInput())
            {
                OpenScale openScale = OpenScale.getInstance(context);

                String name = txtUserName.getText().toString();
                int body_height = Integer.valueOf(txtBodyHeight.getText().toString());
                int checkedRadioButtonId = radioScaleUnit.getCheckedRadioButtonId();

                int scale_unit = -1;

                switch (checkedRadioButtonId) {
                    case R.id.btnRadioKG:
                        scale_unit = 0;
                        break;
                    case R.id.btnRadioLB:
                        scale_unit = 1;
                        break;
                    case R.id.btnRadioST:
                        scale_unit = 2;
                        break;
                }

                String date = txtBirthday.getText().toString();

                int id = -1;

                if (getIntent().getExtras().getInt("mode") == EDIT_USER_REQUEST)
                {
                    id = getIntent().getExtras().getInt("id");
                    openScale.updateScaleUser(id, name, date, body_height, scale_unit);
                } else
                {
                    openScale.addScaleUser(name, date, body_height, scale_unit);

                    id = openScale.getScaleUserList().get(0).id;
                }

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putInt("selectedUserId", id).commit();

                Intent returnIntent = new Intent();
                setResult(RESULT_OK, returnIntent);

                finish();
            }
        }
    }

    private class onClickListenerBirthdaySet implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Calendar cal = Calendar.getInstance();
            DatePickerDialog datePicker = new DatePickerDialog(context, datePickerListener, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            datePicker.show();
        }
    }

    private class onClickListenerCancel implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Intent returnIntent = new Intent();
            setResult(RESULT_CANCELED, returnIntent);

            finish();
        }
    }
}
