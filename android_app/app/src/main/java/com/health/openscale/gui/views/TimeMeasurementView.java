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

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TimePicker;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class TimeMeasurementView extends MeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "time";

    private final DateFormat timeFormat;
    private Date time;

    public TimeMeasurementView(Context context) {
        super(context, R.string.label_time, R.drawable.ic_daysleft);
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
    }

    @Override
    public String getKey() {
        return KEY;
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

        target.set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY));
        target.set(Calendar.MINUTE, source.get(Calendar.MINUTE));
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        measurement.setDateTime(target.getTime());
    }

    @Override
    public void clearIn(ScaleMeasurement measurement) {
        // Ignore
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(new Date(state.getLong(getKey())), true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putLong(getKey(), time.getTime());
    }

    @Override
    public String getValueAsString(boolean withUnit) {
        return timeFormat.format(time);
    }

    @Override
    protected View getInputView() {
        TimePicker timePicker = new TimePicker(getContext());
        timePicker.setPadding(0, 15, 0, 0);

        Calendar cal = Calendar.getInstance();
        cal.setTime(time);

        timePicker.setCurrentHour(cal.get(Calendar.HOUR_OF_DAY));
        timePicker.setCurrentMinute(cal.get(Calendar.MINUTE));
        timePicker.setIs24HourView(android.text.format.DateFormat.is24HourFormat(getContext()));

        return timePicker;
    }

    @Override
    protected boolean validateAndSetInput(View view) {
        TimePicker timePicker = (TimePicker) view;

        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        cal.set(Calendar.HOUR_OF_DAY, timePicker.getCurrentHour());
        cal.set(Calendar.MINUTE, timePicker.getCurrentMinute());
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        setValue(cal.getTime(), true);

        return true;
    }
}
