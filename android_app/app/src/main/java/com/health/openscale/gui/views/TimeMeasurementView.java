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
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.widget.TimePicker;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class TimeMeasurementView extends MeasurementView {
    private DateFormat timeFormat;

    public TimeMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_time), ContextCompat.getDrawable(context, R.drawable.ic_daysleft));
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
    }

    private TimePickerDialog.OnTimeSetListener timePickerListener = new TimePickerDialog.OnTimeSetListener() {
        @Override
        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            Date date = cal.getTime();
            setValueOnView(date, timeFormat.format(date));
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(getDateTime());

        TimePickerDialog timePicker = new TimePickerDialog(
            getContext(), timePickerListener,
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(getContext()));

        return timePicker;
    }

    @Override
    public void updateValue(ScaleMeasurement newMeasurement) {
        setValueOnView(newMeasurement.getDateTime(), timeFormat.format(newMeasurement.getDateTime()));
    }

    @Override
    public void updateDiff(ScaleMeasurement newMeasurement, ScaleMeasurement lastMeasurement) {

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
