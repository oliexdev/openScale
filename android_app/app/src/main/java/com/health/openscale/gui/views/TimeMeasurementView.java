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
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import java.text.SimpleDateFormat;
import java.util.Calendar;

public class TimeMeasurementView extends MeasurementView {
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    public TimeMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_time), ContextCompat.getDrawable(context, R.drawable.ic_daysleft));
    }

    private TimePickerDialog.OnTimeSetListener timePickerListener = new TimePickerDialog.OnTimeSetListener() {
        @Override
        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
            	setValueOnView(String.format("%02d:%02d", hourOfDay, minute));
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();

        TimePickerDialog timePicker = new TimePickerDialog(getContext(), timePickerListener, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true);

        return timePicker;
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(timeFormat.format(updateData.getDateTime()));
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
