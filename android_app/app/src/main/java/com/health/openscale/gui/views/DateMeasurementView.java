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

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateMeasurementView extends MeasurementView {
    private DateFormat dateFormat = DateFormat.getDateInstance();

    public DateMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_date), ContextCompat.getDrawable(context, R.drawable.ic_lastmonth));
    }

    private DatePickerDialog.OnDateSetListener datePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            Calendar cal = Calendar.getInstance();
            cal.set(selectedYear, selectedMonth, selectedDay);
            Date date = cal.getTime();
            setValueOnView(date, dateFormat.format(date));
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(getDateTime());

        DatePickerDialog datePicker = new DatePickerDialog(
            getContext(), datePickerListener, cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));

        return datePicker;
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(updateData.getDateTime(), dateFormat.format(updateData.getDateTime()));
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
    public float getMaxValue() {
        return 0;
    }

}
