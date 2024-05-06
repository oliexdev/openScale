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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuInflater;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.DialogFragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.alarm.ReminderBootReceiver;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
                final String[] values = getResources().getStringArray(R.array.weekdays_values);
                final String[] translated = getResources().getStringArray(R.array.weekdays_entries);

                return IntStream.range(0, values.length)
                    .mapToObj(i -> new Pair<>(values[i], translated[i]))
                    .filter(p -> preference.getValues().contains(p.first))
                    .map(p -> p.second)
                    .collect(Collectors.joining(", "));
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
        if (reminderEnable.isChecked()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissionNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            } else {
                enableAlarmReminder();
            }
        }
        else {
            disableAlarmReminder();
        }
    }

    private void enableAlarmReminder() {
        ComponentName receiver = new ComponentName(getActivity().getApplicationContext(), ReminderBootReceiver.class);
        PackageManager pm = getActivity().getApplicationContext().getPackageManager();

        AlarmHandler alarmHandler = new AlarmHandler();

        alarmHandler.scheduleAlarms(getActivity());

        pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void disableAlarmReminder() {
        ComponentName receiver = new ComponentName(getActivity().getApplicationContext(), ReminderBootReceiver.class);
        PackageManager pm = getActivity().getApplicationContext().getPackageManager();

        AlarmHandler alarmHandler = new AlarmHandler();

        alarmHandler.disableAllAlarms(getActivity());

        pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }

    private ActivityResultLauncher<String> requestPermissionNotificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                            AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), R.style.AppTheme_Dialog);
                            builder.setTitle(R.string.permission_bluetooth_info_title);
                            builder.setIcon(R.drawable.ic_preferences_about);
                            builder.setMessage(R.string.permission_notification_info);
                            builder.setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    requestPermissionNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                                }
                            });

                            Dialog alertDialog = builder.create();
                            alertDialog.setCanceledOnTouchOutside(false);
                            alertDialog.show();
                        } else {
                            AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), R.style.AppTheme_Dialog);
                            builder.setTitle(R.string.permission_bluetooth_info_title);
                            builder.setIcon(R.drawable.ic_preferences_about);
                            builder.setMessage(R.string.permission_notification_info);
                            builder.setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    getContext().startActivity(intent);
                                }
                            });

                            Dialog alertDialog = builder.create();
                            alertDialog.setCanceledOnTouchOutside(false);
                            alertDialog.show();
                        }
                    }
                } else {
                    enableAlarmReminder();
                }
            });
}

