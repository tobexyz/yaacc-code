/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import de.yaacc.R;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class ServerAutostart extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        if (preferences.getBoolean(
                context.getString(R.string.settings_local_server_autostart_chkbx), false) && "android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
            Log.d(this.getClass().toString(), "Starting YAACC server on device start");
            Intent serviceIntent = new Intent(context, YaaccUpnpServerService.class);
            context.startForegroundService(serviceIntent);
        } else {
            Log.d(this.getClass().toString(), "Not starting YAACC server on device start");
        }
    }

}
