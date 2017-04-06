package com.health.openscale.core.alarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.health.openscale.R;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.health.openscale.gui.preferences.ReminderPreferences.PREFERENCE_KEY_REMINDER_NOTIFY_TEXT;
import static com.health.openscale.gui.preferences.ReminderPreferences.PREFERENCE_KEY_REMINDER_TIME;
import static com.health.openscale.gui.preferences.ReminderPreferences.PREFERENCE_KEY_REMINDER_WEEKDAYS;

public class AlarmEntryReader
{
    private Set<AlarmEntry> alarmEntries;
    private String alarmNotificationText;

    private AlarmEntryReader(Set<AlarmEntry> alarmEntries, String alarmNotificationText)
    {
        this.alarmEntries = alarmEntries;
        this.alarmNotificationText = alarmNotificationText;
    }

    public Set<AlarmEntry> getEntries()
    {
        return alarmEntries;
    }

    public String getNotificationText()
    {
        return alarmNotificationText;
    }

    public static AlarmEntryReader construct(Context context)
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Set<String> reminderWeekdays = prefs.getStringSet(PREFERENCE_KEY_REMINDER_WEEKDAYS, new HashSet<String>());
        Long reminderTimeInMillis = prefs.getLong(PREFERENCE_KEY_REMINDER_TIME, System.currentTimeMillis());
        String notifyText = prefs.getString(PREFERENCE_KEY_REMINDER_NOTIFY_TEXT,
                context.getResources().getString(R.string.default_value_reminder_notify_text));

        Set<AlarmEntry> alarms = new TreeSet<>();

        for (String dayOfWeek : reminderWeekdays)
        {
            AlarmEntry alarm = getAlarmEntry(dayOfWeek, reminderTimeInMillis);
            alarms.add(alarm);
        }

        return new AlarmEntryReader(alarms, notifyText);
    }

    private static AlarmEntry getAlarmEntry(String dayOfWeek, Long reminderTimeInMillis)
    {
        AlarmEntry alarmEntry;
        switch (dayOfWeek)
        {
            case "Monday":
                alarmEntry = new AlarmEntry(Calendar.MONDAY, reminderTimeInMillis);
                break;
            case "Tuesday":
                alarmEntry = new AlarmEntry(Calendar.TUESDAY, reminderTimeInMillis);
                break;
            case "Wednesday":
                alarmEntry = new AlarmEntry(Calendar.WEDNESDAY, reminderTimeInMillis);
                break;
            case "Thursday":
                alarmEntry = new AlarmEntry(Calendar.THURSDAY, reminderTimeInMillis);
                break;
            case "Friday":
                alarmEntry = new AlarmEntry(Calendar.FRIDAY, reminderTimeInMillis);
                break;
            case "Saturday":
                alarmEntry = new AlarmEntry(Calendar.SATURDAY, reminderTimeInMillis);
                break;
            default:
            case "Sunday":
                alarmEntry = new AlarmEntry(Calendar.SUNDAY, reminderTimeInMillis);
                break;
        }
        return alarmEntry;
    }


}
