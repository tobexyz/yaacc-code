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
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import de.yaacc.util.YaaccLogger;

import androidx.preference.PreferenceManager;

import de.yaacc.R;

/**
 * The boot receiver. It is switched by the setting "autostart on boot": the component is enabled when the setting is ticked and
 * disabled otherwise ({@link #applySetting}), so no boot start happens unless the user ticked it. The process creation for the
 * broadcast already runs the normal startup route ({@code Yaacc.onCreate}); the call of the service here is the fallback when that
 * route's background start was refused.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class ServerAutostart extends BroadcastReceiver {

    /**
     * Enables or disables this receiver to match the setting. Called when the application starts (so an installation that existed
     * before has the right state) and when the setting changes. The app keeps running ({@code DONT_KILL_APP}).
     */
    public static void applySetting(Context context, boolean autostartTicked) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, ServerAutostart.class),
                    AutostartReceiverState.componentState(autostartTicked),
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            // the setting stays stored; the state is applied again at the next start of the app
            YaaccLogger.w(ServerAutostart.class.getName(), "Could not switch the boot receiver: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);

        if (preferences.getBoolean(
                context.getString(R.string.settings_local_server_autostart_chkbx), false) && "android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
            YaaccLogger.d(this.getClass().toString(), "Starting YAACC server on device start");
            Intent serviceIntent = new Intent(context, YaaccUpnpServerService.class);
            try {
                context.startForegroundService(serviceIntent);
            } catch (IllegalStateException e) {
                // a refused background start must not crash the receiver; the app startup route already started the service
                YaaccLogger.w(this.getClass().getName(), "Boot start of the server service was refused: " + e.getClass().getSimpleName());
            }
        } else {
            YaaccLogger.d(this.getClass().toString(), "Not starting YAACC server on device start");
        }
    }

}
