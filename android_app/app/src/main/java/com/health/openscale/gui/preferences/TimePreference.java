/*
 * Copyright (C) 2020 olie.xdev <olie.xdev@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package com.health.openscale.gui.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.format.DateFormat;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;

import com.health.openscale.R;

import java.util.Calendar;

public class TimePreference extends DialogPreference {

    private long timeInMillis;

    public TimePreference(Context context) {
        this(context, null);
    }

    public TimePreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.preferenceStyle);
    }

    public TimePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, defStyleAttr);
    }

    public TimePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

    }

    public long getTimeInMillis() {
        return timeInMillis;
    }

    public void setTimeInMillis(long timeInMillis) {
        this.timeInMillis = timeInMillis;

        persistLong(this.timeInMillis);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        Calendar defaultTime = Calendar.getInstance();
        defaultTime.set(Calendar.HOUR_OF_DAY, 16);
        defaultTime.set(Calendar.MINUTE, 0);

        return defaultTime.getTimeInMillis();
    }

    @Override
    public int getDialogLayoutResource() {
        return R.layout.preference_timepicker;
    }


    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue) {
        setTimeInMillis(restore ? getPersistedLong(timeInMillis) : (long) defaultValue);
    }

    @Override
    public CharSequence getSummary() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeInMillis);

        return (DateFormat.getTimeFormat(getContext()).format(calendar.getTime()));
    }

}