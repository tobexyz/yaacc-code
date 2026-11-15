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
package de.yaacc.util;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.app.NotificationCompat;

import de.yaacc.R;
import de.yaacc.Yaacc;

/**
 * The {@link ExitRestartGuard} of the app, with its mark in a preferences file of its own (not the settings: it is no setting).
 */
public final class ExitRestartGuards {

    private static final String FILE = "yaacc_exit_guard";
    private static final String KEY_EXIT_REQUESTED_AT = "exit_requested_at";

    private ExitRestartGuards() {
    }

    private static ExitRestartGuard shared;

    /** The one guard of the process (the in-process "the user started" state is shared by the application, the services and the screens). */
    public static synchronized ExitRestartGuard of(Context context) {
        if (shared == null) {
            shared = create(context.getApplicationContext());
        }
        return shared;
    }

    private static ExitRestartGuard create(Context context) {
        final SharedPreferences preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new ExitRestartGuard(new ExitRestartGuard.Store() {
            @Override
            public long readExitRequestedAt() {
                return preferences.getLong(KEY_EXIT_REQUESTED_AT, 0L);
            }

            @Override
            @SuppressLint("ApplySharedPref") // commit on purpose: the process ends right after the mark is written
            public void writeExitRequestedAt(long millis) {
                SharedPreferences.Editor editor = preferences.edit();
                if (millis <= 0L) {
                    editor.remove(KEY_EXIT_REQUESTED_AT);
                } else {
                    editor.putLong(KEY_EXIT_REQUESTED_AT, millis);
                }
                if (!editor.commit()) {
                    throw new IllegalStateException("could not write the exit mark");
                }
            }
        }, System::currentTimeMillis);
    }

    /**
     * Ends a service that the system restarted right after an explicit exit (null intent in {@code onStartCommand}). A null intent
     * never comes from {@code startForegroundService()} (that always carries an intent), so the system's own restart is the only
     * entry and it is not "foreground required"; but a service that was started that way and does not call {@code startForeground()}
     * in time raises a {@code ForegroundServiceDidNotStartInTimeException} on Android 12 and newer, and this can not be verified
     * by reading. So the path is made safe where it costs little: the smallest possible notification goes up and comes down again in
     * the same call (a flash is acceptable, a crash is not), and then the service stops. Every step is guarded: a refused
     * {@code startForeground} (background start limits) must not keep the service from stopping.
     */
    public static void stopRestartedService(Service service, int notificationId) {
        try {
            Notification notification = new NotificationCompat.Builder(service, Yaacc.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_default)
                    .setSilent(true)
                    .build();
            service.startForeground(notificationId, notification);
        } catch (RuntimeException refused) {
            // not allowed in this state: nothing was promised either, stop all the same
        }
        try {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
            // not in the foreground
        }
        service.stopSelf();
    }
}
