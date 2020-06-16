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
package com.health.openscale.gui.preferences;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;

import androidx.fragment.app.DialogFragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.alarm.ReminderBootReceiver;

public class ReminderPreferences extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String PREFERENCE_KEY_REMINDER_NOTIFY_TEXT = "reminderNotifyText";
    public static final String PREFERENCE_KEY_REMINDER_WEEKDAYS = "reminderWeekdays";
    public static final String PREFERENCE_KEY_REMINDER_TIME = "reminderTime";
    private static final String PREFERENCE_KEY_REMINDER_ENABLE = "reminderEnable";

    private CheckBoxPreference reminderEnable;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.reminder_preferences, rootKey);

        setHasOptionsMenu(true);

        reminderEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_REMINDER_ENABLE);

        final MultiSelectListPreference prefDays = findPreference("reminderWeekdays");

        prefDays.setSummaryProvider(new Preference.SummaryProvider<MultiSelectListPreference>() {
            @Override
            public CharSequence provideSummary(MultiSelectListPreference preference) {
                return preference.getValues().toString();
            }
        });

        updateAlarmPreferences();
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        DialogFragment dialogFragment = null;

        if (preference instanceof TimePreference) {
            dialogFragment = TimePreferenceDialog.newInstance(preference.getKey());
        }

        if (dialogFragment != null) {
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getParentFragmentManager(), "timePreferenceDialog");
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    @Override
    public void onResume()
    {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause()
    {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
    {
        updateAlarmPreferences();
    }

    private void updateAlarmPreferences()
    {
        ComponentName receiver = new ComponentName(getActivity().getApplicationContext(), ReminderBootReceiver.class);
        PackageManager pm = getActivity().getApplicationContext().getPackageManager();

        AlarmHandler alarmHandler = new AlarmHandler();
        if (reminderEnable.isChecked()) {
            alarmHandler.scheduleAlarms(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
        else {
            alarmHandler.disableAllAlarms(getActivity());

            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}

