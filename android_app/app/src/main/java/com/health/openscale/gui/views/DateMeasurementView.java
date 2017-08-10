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
package com.health.openscale.gui.views;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.widget.DatePicker;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import java.text.SimpleDateFormat;
import java.util.Calendar;

public class DateMeasurementView extends MeasurementView {
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

    public DateMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_date), ContextCompat.getDrawable(context, R.drawable.ic_lastmonth));
    }

    private DatePickerDialog.OnDateSetListener datePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            setValueOnView(String.format("%02d.%02d.%04d", selectedDay, selectedMonth+1, selectedYear));
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();

        DatePickerDialog datePicker = new DatePickerDialog(getContext(), datePickerListener, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));

        return datePicker;
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(dateFormat.format(updateData.getDateTime()));
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {

    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {

    }

    @Override
    public String getUnit() {
        return null;
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return null;
    }

    @Override
    public float getMinValue() {
        return 0;
    }

    @Override
    public float getMaxValue() {
        return 0;
    }

}
