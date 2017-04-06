package com.health.openscale.core.alarm;

import android.support.annotation.NonNull;

import java.util.Calendar;

public class AlarmEntry implements Comparable<AlarmEntry>
{
    private final int dayOfWeek;
    private final long timeInMillis;

    public AlarmEntry(int dayOfWeek, long timeInMillis)
    {
        this.dayOfWeek = dayOfWeek;
        this.timeInMillis = timeInMillis;
    }

    public int getDayOfWeek()
    {
        return dayOfWeek;
    }

    private long getTimeInMillis()
    {
        return timeInMillis;
    }

    public Calendar getNextTimestamp()
    {
        // We just want the time *not* the date
        Calendar nextAlarmTimestamp = Calendar.getInstance();
        nextAlarmTimestamp.setTimeInMillis(getTimeInMillis());

        Calendar alarmCal = Calendar.getInstance();
        alarmCal.set(Calendar.HOUR_OF_DAY, nextAlarmTimestamp.get(Calendar.HOUR_OF_DAY));
        alarmCal.set(Calendar.MINUTE, nextAlarmTimestamp.get(Calendar.MINUTE));
        alarmCal.set(Calendar.SECOND, 0);
        alarmCal.set(Calendar.DAY_OF_WEEK, getDayOfWeek());

        // Check we aren't setting it in the past which would trigger it to fire instantly
        if (alarmCal.before(Calendar.getInstance())) alarmCal.add(Calendar.DAY_OF_YEAR, 7);
        return alarmCal;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AlarmEntry that = (AlarmEntry) o;

        if (dayOfWeek != that.dayOfWeek) return false;
        return timeInMillis == that.timeInMillis;
    }

    @Override
    public int hashCode()
    {
        int result = dayOfWeek;
        result = 31 * result + (int) (timeInMillis ^ (timeInMillis >>> 32));
        return result;
    }

    @Override
    public int compareTo(@NonNull AlarmEntry o)
    {
        int rc = compare(dayOfWeek, o.dayOfWeek);
        if (rc == 0) rc = compare(timeInMillis, o.timeInMillis);
        return rc;
    }

    private int compare(long x, long y)
    {
        return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }
}
