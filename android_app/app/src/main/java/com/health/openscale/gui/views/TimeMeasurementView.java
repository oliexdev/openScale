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
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.widget.EditText;
import android.widget.TimePicker;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class TimeMeasurementView extends MeasurementView {
    private DateFormat timeFormat;
    private Date time;
    private static String TIME_KEY = "time";

    public TimeMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_time), ContextCompat.getDrawable(context, R.drawable.ic_daysleft));
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
    }

    private void setValue(Date newTime, boolean callListener) {
        if (!newTime.equals(time)) {
            time = newTime;
            if (getUpdateViews()) {
                setValueView(timeFormat.format(time), callListener);
            }
        }
    }

    @Override
    public void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement) {
        setValue(measurement.getDateTime(), false);
    }

    @Override
    public void saveTo(ScaleMeasurement measurement) {
        Calendar target = Calendar.getInstance();
        target.setTime(measurement.getDateTime());

        Calendar source = Calendar.getInstance();
        source.setTime(time);

        target.set(Calendar.HOUR, source.get(Calendar.HOUR));
        target.set(Calendar.MINUTE, source.get(Calendar.MINUTE));
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        measurement.setDateTime(target.getTime());
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(new Date(state.getLong(TIME_KEY)), true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putLong(TIME_KEY, time.getTime());
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        // Empty
    }

    @Override
    public String getValueAsString() {
        return timeFormat.format(time);
    }

    @Override
    protected boolean validateAndSetInput(EditText view) {
        return false;
    }

    @Override
    protected int getInputType() {
        return 0;
    }

    @Override
    protected String getHintText() {
        return null;
    }

    private TimePickerDialog.OnTimeSetListener timePickerListener = new TimePickerDialog.OnTimeSetListener() {
        @Override
        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(time);

            cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            setValue(cal.getTime(), true);
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);

        return new TimePickerDialog(
            getContext(), timePickerListener,
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(getContext()));
    }
}
