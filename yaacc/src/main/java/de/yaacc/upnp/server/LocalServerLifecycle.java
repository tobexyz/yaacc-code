/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;

/**
 * Starts or stops the local UPnP server service according to the preferences. Shared by the
 * browser activities, which call it from {@code onResume}.
 */
public final class LocalServerLifecycle {

    private LocalServerLifecycle() {
    }

    /**
     * @param context any context; only the application context is used
     * @param restart true after the user visited the settings, so that changed server settings
     *                take effect (the service is stopped first)
     */
    public static void apply(Context context, boolean restart) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        boolean serverOn = preferences.getBoolean(appContext.getString(R.string.settings_local_server_chkbx), false);
        Intent serverService = new Intent(appContext, YaaccUpnpServerService.class);
        if (serverOn) {
            // (Re)Start upnpserver service for avtransport
            if (restart) {
                appContext.stopService(serverService);
            }
            appContext.startForegroundService(serverService);
            YaaccLogger.d(LocalServerLifecycle.class.getName(), "Starting local service");
        } else {
            appContext.stopService(serverService);
            YaaccLogger.d(LocalServerLifecycle.class.getName(), "Stopping local service");
        }
    }
}
