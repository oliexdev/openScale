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

package com.health.openscale.core.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderBootReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent)
    {
        if (intent.hasExtra(AlarmHandler.INTENT_EXTRA_ALARM)) handleAlarm(context);

        if (intent.hasExtra(AlarmBackupHandler.INTENT_EXTRA_BACKUP_ALARM)) handleBackupAlarm(context);

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) scheduleAlarms(context);
    }

    private void handleAlarm(Context context)
    {
        AlarmHandler alarmHandler = new AlarmHandler();
        alarmHandler.showAlarmNotification(context);
    }

    private void handleBackupAlarm(Context context)
    {
        AlarmBackupHandler alarmBackupHandler = new AlarmBackupHandler();
        alarmBackupHandler.executeBackup(context);
    }

    private void scheduleAlarms(Context context)
    {
        AlarmHandler alarmHandler = new AlarmHandler();
        AlarmBackupHandler alarmBackupHandler = new AlarmBackupHandler();

        alarmHandler.scheduleAlarms(context);
        alarmBackupHandler.scheduleAlarms(context);
    }
}
