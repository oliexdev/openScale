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

package com.health.openscale.gui;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;

import com.health.openscale.R;
import com.health.openscale.gui.preferences.ReminderPreferences;

import static android.content.Context.NOTIFICATION_SERVICE;

public class ReminderBootReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent)
    {
        if (intent.hasExtra(ReminderPreferences.INTENT_EXTRA_ALARM))
        {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            String notifyText = prefs.getString(ReminderPreferences.PREFERENCE_KEY_REMINDER_NOTIFY_TEXT,
                    context.getResources().getString(R.string.default_value_reminder_notify_text));

            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(context).setSmallIcon(R.drawable.ic_launcher)
                                                           .setContentTitle(context.getString(R.string.app_name))
                                                           .setContentText(notifyText)
                                                           .setAutoCancel(true);

            Intent notifyIntent = new Intent(context, MainActivity.class);

            PendingIntent notifyPendingIntent =
                    PendingIntent.getActivity(context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            mBuilder.setContentIntent(notifyPendingIntent);

            NotificationManager mNotifyMgr = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            mNotifyMgr.notify(0x01, mBuilder.build());
        }

        if (intent.getAction() != null)
        {
            if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED))
            {
                ReminderPreferences.scheduleAlarms(context);
            }
        }
    }
}
