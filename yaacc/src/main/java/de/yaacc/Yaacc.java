/*
 * Copyright (C) 2018 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc;


import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.yaacc.browser.TabBrowserActivity;
import de.yaacc.musicplayer.BackgroundMusicService;
import de.yaacc.player.PlayerService;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.UpnpRegistryService;
import de.yaacc.upnp.server.YaaccAudioRenderingControlService;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.NotificationId;

/**
 * application which holds the global state
 *
 * @author Tobias Schoene (tobexyz)
 */
public class Yaacc extends Application {
    public static final String NOTIFICATION_CHANNEL_ID = "YaaccNotifications";
    public static final String NOTIFICATION_GROUP_KEY = "Yaacc";
    private final HashMap<String, PowerManager.WakeLock> wakeLocks = new HashMap<>();
    private UpnpClient upnpClient;
    private Executor contentLoadThreadPool;


    @Override
    public void onCreate() {
        super.onCreate();
        upnpClient = new UpnpClient(this);
        createNotificationChannel();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean darkMode = preferences.getBoolean(getString(R.string.settings_dark_mode_key), true);
        if (darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }

        int numThreads = Integer.parseInt(preferences.getString(getString(R.string.settings_browse_load_threads_key), "10"));
        Log.d(getClass().getName(), "Number of Threads used for content loading: " + numThreads);
        if (numThreads <= 0) {
            Log.d(getClass().getName(), "Number of Threads invalid using 10 threads instead: " + numThreads);
            numThreads = 10;
        }
        contentLoadThreadPool = Executors.newFixedThreadPool(numThreads);

    }

    public Executor getContentLoadExecutor() {

        return contentLoadThreadPool;
    }

    public UpnpClient getUpnpClient() {
        return upnpClient;
    }

    public boolean isUnplugged() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        boolean unplugged = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        return !(plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                unplugged);

    }

    public void acquireWakeLock(long timeout, String tag) {

        if (!wakeLocks.containsKey(tag)) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLocks.put(tag, powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, tag));
        }
        PowerManager.WakeLock wakeLock = wakeLocks.get(tag);
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                releaseWakeLock(tag);
            }
            while (!wakeLock.isHeld()) {
                wakeLock.acquire(timeout);
            }
            Log.d(getClass().getName(), "WakeLock aquired Tag:" + tag + " timeout: " + timeout);
        }


    }

    public void releaseWakeLock(String tag) {
        PowerManager.WakeLock wakeLock = wakeLocks.get(tag);
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(getClass().getName(), "WakeLock released: " + tag);
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
                Log.d(getClass().getName(), "Ignoring exception on WakeLock (" + tag + ") release maybe no wakelock?");
            }
        }

    }

    public void exit() {
        Log.d(getClass().getName(), "Start shutdown and close");
        upnpClient.shutdown();
        stopService(new Intent(this, PlayerService.class));
        stopService(new Intent(this, BackgroundMusicService.class));
        stopService(new Intent(this, YaaccAudioRenderingControlService.class));
        stopService(new Intent(this, YaaccUpnpServerService.class));
        stopService(new Intent(this, UpnpRegistryService.class));

        //FIXME work around to be fixed with new ui
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(NotificationId.UPNP_SERVER.getId());
        mNotificationManager.cancel(NotificationId.PLAYER_SERVICE.getId());
        mNotificationManager.cancel(NotificationId.YAACC.getId());
        ActivityManager am = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        am.getAppTasks().stream().forEach(t -> t.finishAndRemoveTask());
        Runtime.getRuntime().exit(0);
    }

    public void createNotificationChannel() {

        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);


        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    public void createYaaccGroupNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        Intent notificationIntent = new Intent(this, TabBrowserActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
                getApplicationContext(), Yaacc.NOTIFICATION_CHANNEL_ID)
                .setSilent(true)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setGroupSummary(true)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentTitle("Yaacc")
                .setContentText("Yet Another Android Client Controller")
                .setContentIntent(pendingIntent);
        notificationManager.notify(NotificationId.YAACC.getId(), mBuilder.build());

    }

    public void cancelYaaccGroupNotification() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager.getActiveNotifications().length == 1) {
            mNotificationManager.cancel(NotificationId.YAACC.getId());
        }


    }
}
