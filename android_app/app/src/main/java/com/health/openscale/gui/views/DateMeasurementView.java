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
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.widget.DatePicker;
import android.widget.EditText;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateMeasurementView extends MeasurementView {
    private static DateFormat dateFormat = DateFormat.getDateInstance();
    private Date date;
    private static String DATE_KEY = "date";

    public DateMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_date), ContextCompat.getDrawable(context, R.drawable.ic_lastmonth));
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
    public void restoreState(Bundle state) {
        setValue(new Date(state.getLong(DATE_KEY)), true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putLong(DATE_KEY, date.getTime());
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        // Empty
    }

    @Override
    public String getValueAsString() {
        return dateFormat.format(date);
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

    private DatePickerDialog.OnDateSetListener datePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(selectedYear, selectedMonth, selectedDay);
            setValue(cal.getTime(), true);
        }
    };

    @Override
    protected AlertDialog getInputDialog() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);

        return new DatePickerDialog(
            getContext(), datePickerListener, cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
    }
}
