/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.core.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.health.openscale.core.OpenScale;

public class AlarmBackupHandler
{
    public static final String INTENT_EXTRA_BACKUP_ALARM = "alarmBackupIntent";
    private static final int ALARM_NOTIFICATION_ID = 0x02;

    public void scheduleAlarms(Context context)
    {
        disableAlarm(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (prefs.getBoolean("autoBackup", true)) {

            String backupSchedule = prefs.getString("autoBackup_Schedule", "Monthly");

            long intervalDayMultiplicator = 0;

            switch (backupSchedule) {
                case "Daily":
                    intervalDayMultiplicator = 1;
                    break;
                case "Weekly":
                    intervalDayMultiplicator = 7;
                    break;
                case "Monthly":
                    intervalDayMultiplicator = 30;
                    break;
            }

            PendingIntent alarmPendingIntent = getPendingAlarmIntent(context);
            AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmMgr.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
                    AlarmManager.INTERVAL_DAY * intervalDayMultiplicator, alarmPendingIntent);
        }
    }

    private PendingIntent getPendingAlarmIntent(Context context)
    {
        Intent alarmIntent = new Intent(context, ReminderBootReceiver.class);
        alarmIntent.putExtra(INTENT_EXTRA_BACKUP_ALARM, true);

        return PendingIntent.getBroadcast(context, ALARM_NOTIFICATION_ID, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void disableAlarm(Context context) {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        alarmMgr.cancel(getPendingAlarmIntent(context));
    }

    public void executeBackup(Context context) {
        OpenScale openScale = OpenScale.getInstance(context);

        // TODO implement backup routine
        Log.d("ALARM HANDLER", "EXCECUTED BACKUP");
    }
}
