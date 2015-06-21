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

import java.text.SimpleDateFormat;

public class EditDataActivity extends Activity {

    private EditText txtWeight;
    private EditText txtFat;
    private EditText txtWater;
    private EditText txtMuscle;

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

        setTitle(getResources().getString(R.string.title_edit_data_entry) + ": " + new SimpleDateFormat("dd. MMM yyyy (EE) HH:mm").format(editScaleData.date_time));

    }


    private class onClickListenerOk implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            float weight = Float.valueOf(txtWeight.getText().toString());
            float fat = Float.valueOf(txtFat.getText().toString());
            float water = Float.valueOf(txtWater.getText().toString());
            float muscle = Float.valueOf(txtMuscle.getText().toString());

            OpenScale openScale = OpenScale.getInstance(context);

            openScale.updateScaleData(id, weight, fat, water, muscle);

            finish();
        }
    }

    private class onClickListenerCancel implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            finish();
        }
    }
}
