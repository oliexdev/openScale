package com.health.openscale.core.alarm;

import android.content.Context;

import com.health.openscale.core.IScaleDatabaseEntryListener;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.Util;

import java.util.Calendar;

public class AlarmDatabaseEntryListener implements IScaleDatabaseEntryListener
{
    @Override
    public void entryChanged(Context context, ScaleData data)
    {
        long dataMillis = data.date_time.getTime();

        Calendar dataTimestamp = Calendar.getInstance();
        dataTimestamp.setTimeInMillis(dataMillis);

        if(Util.isSameDate(dataTimestamp, Calendar.getInstance()))
        {
            AlarmHandler alarmHandler = new AlarmHandler();
            alarmHandler.cancelAlarmNotification(context);
            alarmHandler.cancelAndRescheduleAlarmForNextWeek( context, dataTimestamp );
        }
    }
}
