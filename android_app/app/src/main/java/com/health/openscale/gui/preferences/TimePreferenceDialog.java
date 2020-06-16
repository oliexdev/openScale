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

import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TimePicker;

import androidx.preference.DialogPreference;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.health.openscale.R;

import java.util.Calendar;

public class TimePreferenceDialog extends PreferenceDialogFragmentCompat {
    private Calendar calendar;
    private TimePicker timePicker;

    public static TimePreferenceDialog newInstance(String key) {
        final TimePreferenceDialog fragment = new TimePreferenceDialog();
        final Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        fragment.setArguments(b);

        return fragment;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);

        timePicker = view.findViewById(R.id.timePicker);
        calendar = Calendar.getInstance();

        Long timeInMillis = null;
        DialogPreference preference = getPreference();

        if (preference instanceof TimePreference) {
            TimePreference timePreference = (TimePreference) preference;
            timeInMillis = timePreference.getTimeInMillis();
        }

        if (timeInMillis != null) {
            calendar.setTimeInMillis(timeInMillis);
            boolean is24hour = DateFormat.is24HourFormat(getContext());

            timePicker.setIs24HourView(is24hour);
            timePicker.setCurrentHour(calendar.get(Calendar.HOUR_OF_DAY));
            timePicker.setCurrentMinute(calendar.get(Calendar.MINUTE));
        }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            int hours;
            int minutes;

            if (Build.VERSION.SDK_INT >= 23) {
                hours = timePicker.getHour();
                minutes = timePicker.getMinute();
            } else {
                hours = timePicker.getCurrentHour();
                minutes = timePicker.getCurrentMinute();
            }

            calendar.set(Calendar.HOUR_OF_DAY, hours);
            calendar.set(Calendar.MINUTE, minutes);

            long timeInMillis = calendar.getTimeInMillis();

            DialogPreference preference = getPreference();
            if (preference instanceof TimePreference) {
                TimePreference timePreference = ((TimePreference) preference);
                if (timePreference.callChangeListener(timeInMillis)) {
                    timePreference.setTimeInMillis(timeInMillis);
                    timePreference.setSummary(DateFormat.getTimeFormat(getContext()).format(calendar.getTime()));
                }
            }
        }
    }
}
