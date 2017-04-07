package com.health.openscale.core.alarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.gui.MainActivity;

import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static android.content.Context.NOTIFICATION_SERVICE;

public class AlarmHandler
{
    public static final String INTENT_EXTRA_ALARM = "alarmIntent";
    private static final int ALARM_NOTIFICATION_ID = 0x01;
    private static final String LOG_TAG = "AlarmBuilder";

    public void scheduleAlarms(Context context)
    {
        AlarmEntryReader reader = AlarmEntryReader.construct(context);
        Set<AlarmEntry> alarmEntries = reader.getEntries();

        disableAllAlarms(context);
        enableAlarms(context, alarmEntries);
    }

    public void entryChanged(Context context, ScaleData data)
    {
        long dataMillis = data.date_time.getTime();

        Calendar dataTimestamp = Calendar.getInstance();
        dataTimestamp.setTimeInMillis(dataMillis);

        if(AlarmHandler.isSameDate(dataTimestamp, Calendar.getInstance()))
        {
            cancelAlarmNotification(context);
            cancelAndRescheduleAlarmForNextWeek( context, dataTimestamp );
        }
    }

    public static boolean isSameDate(Calendar c1, Calendar c2)
    {
        int[] dateFields = {Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH};
        for (int dateField : dateFields)
        {
            if (c1.get(dateField) != c2.get(dateField)) return false;
        }
        return true;
    }

    private void enableAlarms(Context context, Set<AlarmEntry> alarmEntries)
    {
        for (AlarmEntry alarmEntry : alarmEntries)
            enableAlarm(context, alarmEntry);
    }

    private void enableAlarm(Context context, AlarmEntry alarmEntry)
    {
        int dayOfWeek = alarmEntry.getDayOfWeek();
        Calendar nextAlarmTimestamp = alarmEntry.getNextTimestamp();

        setRepeatingAlarm(context, dayOfWeek, nextAlarmTimestamp);
    }

    private void setRepeatingAlarm(Context context, int dayOfWeek, Calendar nextAlarmTimestamp)
    {
        Log.d(LOG_TAG, "Set repeating alarm for " + nextAlarmTimestamp.getTime());
        PendingIntent alarmPendingIntent = getPendingAlarmIntent(context, dayOfWeek);
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextAlarmTimestamp.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY * 7, alarmPendingIntent);
    }

    private List<PendingIntent> getWeekdaysPendingAlarmIntent(Context context)
    {
        final int[] dayOfWeeks =
                {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
                        Calendar.SATURDAY, Calendar.SUNDAY};
        List<PendingIntent> pendingIntents = new LinkedList<>();
        for (int dayOfWeek : dayOfWeeks)
            pendingIntents.add(getPendingAlarmIntent(context, dayOfWeek));
        return pendingIntents;
    }

    private PendingIntent getPendingAlarmIntent(Context context, int dayOfWeek)
    {
        Intent alarmIntent = new Intent(context, ReminderBootReceiver.class);
        alarmIntent.putExtra(INTENT_EXTRA_ALARM, true);

        return PendingIntent.getBroadcast(context, dayOfWeek, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void disableAllAlarms(Context context)
    {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        List<PendingIntent> pendingIntents = getWeekdaysPendingAlarmIntent(context);
        for (PendingIntent pendingIntent : pendingIntents)
            alarmMgr.cancel(pendingIntent);
    }

    public void cancelAndRescheduleAlarmForNextWeek(Context context, Calendar timestamp)
    {
        AlarmEntryReader reader = AlarmEntryReader.construct(context);
        Set<AlarmEntry> alarmEntries = reader.getEntries();
        for (AlarmEntry entry : alarmEntries)
        {
            Calendar nextAlarmTimestamp = entry.getNextTimestamp();

            if (isSameDate(timestamp, nextAlarmTimestamp))
            {
                int dayOfWeek = entry.getDayOfWeek();
                PendingIntent alarmPendingIntent = getPendingAlarmIntent(context, dayOfWeek);
                AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarmMgr.cancel(alarmPendingIntent);

                nextAlarmTimestamp.add(Calendar.DATE, 7);
                setRepeatingAlarm(context, dayOfWeek, nextAlarmTimestamp);
            }
        }
    }

    public void showAlarmNotification(Context context)
    {
        AlarmEntryReader reader = AlarmEntryReader.construct(context);
        String notifyText = reader.getNotificationText();

        Intent notifyIntent = new Intent(context, MainActivity.class);

        PendingIntent notifyPendingIntent =
                PendingIntent.getActivity(context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
        Notification notification = mBuilder.setSmallIcon(R.drawable.ic_launcher)
                                            .setContentTitle(context.getString(R.string.app_name))
                                            .setContentText(notifyText)
                                            .setAutoCancel(true)
                                            .setContentIntent(notifyPendingIntent)
                                            .build();

        NotificationManager mNotifyMgr = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        mNotifyMgr.notify(ALARM_NOTIFICATION_ID, notification);
    }

    public void cancelAlarmNotification(Context context)
    {
        NotificationManager mNotifyMgr = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            StatusBarNotification[] activeNotifications = mNotifyMgr.getActiveNotifications();
            for (StatusBarNotification notification : activeNotifications)
            {
                if (notification.getId() == ALARM_NOTIFICATION_ID) mNotifyMgr.cancel(ALARM_NOTIFICATION_ID);
            }
        }
        else mNotifyMgr.cancel(ALARM_NOTIFICATION_ID);
    }


}
