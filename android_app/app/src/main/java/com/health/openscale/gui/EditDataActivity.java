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
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

import java.text.DateFormat;

public class EditDataActivity extends Activity {

    private EditText txtWeight;
    private EditText txtFat;
    private EditText txtWater;
    private EditText txtMuscle;
    private EditText txtComment;

    private Button btnOk;
    private Button btnCancel;

    private long id;

    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editdata);

        context = this;

        txtWeight = (EditText) findViewById(R.id.txtWeight);
        txtFat = (EditText) findViewById(R.id.txtFat);
        txtWater = (EditText) findViewById(R.id.txtWater);
        txtMuscle = (EditText) findViewById(R.id.txtMuscle);
        txtComment = (EditText) findViewById(R.id.txtComment);

        btnOk = (Button)findViewById(R.id.btnOk);
        btnCancel = (Button)findViewById(R.id.btnCancel);

        btnOk.setOnClickListener(new onClickListenerOk());
        btnCancel.setOnClickListener(new onClickListenerCancel());

        id = getIntent().getExtras().getLong("id");

        OpenScale openScale = OpenScale.getInstance(context);

        ScaleData editScaleData = openScale.getScaleData(id);

        txtWeight.setText(editScaleData.weight+"");
        txtFat.setText(editScaleData.fat+"");
        txtWater.setText(editScaleData.water+"");
        txtMuscle.setText(editScaleData.muscle+"");
        txtComment.setText(editScaleData.comment);

        setTitle(getResources().getString(R.string.title_edit_data_entry) + ": " + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(editScaleData.date_time));

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

    private class onClickListenerCancel implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            finish();
        }
    }
}
