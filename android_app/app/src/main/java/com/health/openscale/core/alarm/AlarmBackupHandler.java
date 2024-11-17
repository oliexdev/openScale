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
import android.net.Uri;
import android.preference.PreferenceManager;

import androidx.documentfile.provider.DocumentFile;

import com.health.openscale.core.OpenScale;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import timber.log.Timber;

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

        return PendingIntent.getBroadcast(context, ALARM_NOTIFICATION_ID, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void disableAlarm(Context context) {
        AlarmManager alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        alarmMgr.cancel(getPendingAlarmIntent(context));
    }

    public void executeBackup(Context context) {
        OpenScale openScale = OpenScale.getInstance();

        String databaseName = "openScale.db";

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Extra check that there is a backupDir saved in settings
        String backupDirString = prefs.getString("backupDir", null);
        if (backupDirString == null) {
            return;
        }

        DocumentFile backupDir = DocumentFile.fromTreeUri(context, Uri.parse(backupDirString));
        // Check if it is possible to read and write to auto export dir
        // If it is not possible auto backup function will be disabled
        if (!backupDir.canRead() || !backupDir.canWrite()) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("autoBackup", false);
            editor.apply();
            return;
        }

        if (!prefs.getBoolean("overwriteBackup", false)) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            databaseName = dateFormat.format(new Date()) + "_" + databaseName;
        }

        DocumentFile exportFile = backupDir.findFile(databaseName);
        if (exportFile == null) {
            exportFile = backupDir.createFile("application/x-sqlite3", databaseName);
        }

        try {
            openScale.exportDatabase(exportFile.getUri());
            Timber.d("openScale Auto Backup to %s", exportFile);
        } catch (IOException e) {
            Timber.e(e, "Error while exporting database");
        }
    }
}
