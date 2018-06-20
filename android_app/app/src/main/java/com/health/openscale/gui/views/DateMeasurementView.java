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
import android.widget.DatePicker;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateMeasurementView extends MeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "date";

    private final DateFormat dateFormat;
    private Date date;

    public DateMeasurementView(Context context) {
        super(context, R.string.label_date, R.drawable.ic_lastmonth);
        dateFormat = DateFormat.getDateInstance();
    }

    @Override
    public String getKey() {
        return KEY;
    }

    private void setValue(Date newDate, boolean callListener) {
        if (!newDate.equals(date)) {
            date = newDate;
            if (getUpdateViews()) {
                setValueView(dateFormat.format(date), callListener);
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
        source.setTime(date);

        target.set(source.get(Calendar.YEAR), source.get(Calendar.MONTH),
                source.get(Calendar.DAY_OF_MONTH));

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
        state.putLong(getKey(), date.getTime());
    }

    @Override
    public String getValueAsString(boolean withUnit) {
        return dateFormat.format(date);
    }

    @Override
    protected View getInputView() {
        DatePicker datePicker = new DatePicker(getContext());
        datePicker.setPadding(0, 15, 0, 0);

        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        datePicker.updateDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH));

        return datePicker;
    }

    @Override
    protected boolean validateAndSetInput(View view) {
        DatePicker datePicker = (DatePicker) view;

        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth());
        setValue(cal.getTime(), true);

        return true;
    }
}
