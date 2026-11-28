/*
 *
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
package de.yaacc.multiroom.server;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.UUID;

import de.yaacc.BuildConfig;
import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.NotificationId;
import de.yaacc.util.YaaccLogger;

/**
 * The built-in Snapcast server as a foreground service (issue #330, task 4.1): it opens the stream port (1704) and the control port
 * (1705), announces them by mDNS and keeps the state over a restart. It runs only while the switch in the settings is on (off by default,
 * rule S1) and is never restarted by the system. The listeners take only local-network addresses (S2). The CPU and the Wi-Fi are held awake
 * only while a stream plays for a connected client, not just because the server is on.
 */
public final class SnapcastServerService extends Service {

    static final String ACTION_START = "de.yaacc.multiroom.server.START";
    static final String ACTION_STOP = "de.yaacc.multiroom.server.STOP";

    private static final int STREAM_PORT = 1704;
    private static final int CONTROL_PORT = 1705;
    private static final int MAX_STREAM_CLIENTS = 16;
    private static final int MAX_CONTROL_CONNECTIONS = 4;
    private static final long TICK_MILLIS = 5_000L;
    private static final String STATE_KEY = "snapcast_server_state";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lockGuard = new Object();
    private SnapcastServer server;
    private ControlServer control;
    private ServerStore store;
    private ServerState state;
    private SnapcastServerAdvert advert;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String lastText;

    /** Starts the service if the switch is on, stops it if it is off. Call from the main thread (an action of the user or the app start). */
    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
        try {
            if (SnapcastServerSettings.isEnabled(preferences::getBoolean)) {
                ContextCompat.startForegroundService(app, new Intent(app, SnapcastServerService.class).setAction(ACTION_START));
            } else if (SnapcastServerRuntime.isRunning()) {
                app.startService(new Intent(app, SnapcastServerService.class).setAction(ACTION_STOP));
            }
        } catch (IllegalStateException backgroundStartRefused) {
            YaaccLogger.w(SnapcastServerService.class.getName(), "the start of the Snapcast server was refused: "
                    + backgroundStartRefused.getClass().getSimpleName());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            // the stop button of the notification switches the server off, so that it does not come back with the app
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(SnapcastServerSettings.KEY_ENABLED, false).apply();
            shutDown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showForeground(getString(R.string.snapcast_server_notification_text, 0));
        if (!SnapcastServerSettings.isEnabled(PreferenceManager.getDefaultSharedPreferences(this)::getBoolean)) {
            shutDown();
            stopSelf();
            return START_NOT_STICKY;
        }
        open();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutDown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the server

    private synchronized void open() {
        if (server != null) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String phone = preferences.getString(getString(R.string.settings_local_server_name_key), "Yaacc");
        state = new ServerState(phone, BuildConfig.VERSION_NAME, SnapcastServerSettings.DEFAULT_BUFFER_MS,
                () -> UUID.randomUUID().toString(), System::currentTimeMillis);
        store = new ServerStore(new ServerStore.Backing() {
            @Override
            public String read() {
                return preferences.getString(STATE_KEY, null);
            }

            @Override
            public void write(String text) {
                preferences.edit().putString(STATE_KEY, text).apply();
            }
        }, System::currentTimeMillis);
        store.restoreInto(state);
        SnapcastServer s = new SnapcastServer(state, MicroClock.monotonic(), SnapcastAddressFilter::isAcceptedForDiscovery,
                MAX_STREAM_CLIENTS, n -> { });
        ControlServer c = new ControlServer(s, SnapcastAddressFilter::isAcceptedForDiscovery, MAX_CONTROL_CONNECTIONS);
        c.setOnChange(() -> store.changed(state));
        try {
            openStreamPort(s);
            openControlPort(c);
        } catch (IOException failed) {
            YaaccLogger.w(getClass().getName(), "the Snapcast server could not open its ports");
            c.close();
            s.close();
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(SnapcastServerSettings.KEY_ENABLED, false).apply();
            stopSelf();
            return;
        }
        server = s;
        control = c;
        SnapcastServerRuntime.set(s, c);
        advert = new SnapcastServerAdvert(this, SnapcastServerSettings.serviceName(phone));
        advert.register(s.streamPort(), c.port());
        main.post(this::tick);
    }

    private static void openStreamPort(SnapcastServer s) throws IOException {
        try {
            s.start(STREAM_PORT);
        } catch (IOException taken) {
            s.start(0); // the usual port is taken: any free one, the announcement names it
        }
    }

    private static void openControlPort(ControlServer c) throws IOException {
        try {
            c.start(CONTROL_PORT);
        } catch (IOException taken) {
            c.start(0);
        }
    }

    private synchronized void shutDown() {
        main.removeCallbacksAndMessages(null);
        if (advert != null) {
            advert.unregister();
            advert = null;
        }
        if (control != null) {
            control.close();
            control = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        if (store != null && state != null) {
            store.flush(state);
        }
        SnapcastServerRuntime.clear();
        releaseLocks();
    }

    /** Every few seconds: the locks follow the work, the notification follows the clients. */
    private void tick() {
        ServerState s = state;
        if (server == null || s == null) {
            return;
        }
        if (s.hasPlayingStreamWithClients()) {
            acquireLocks();
        } else {
            releaseLocks();
        }
        String text = getString(R.string.snapcast_server_notification_text, s.connectedClientCount());
        if (!text.equals(lastText)) {
            lastText = text;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NotificationId.SNAPCAST_SERVER.getId(), buildNotification(text));
            }
        }
        main.postDelayed(this::tick, TICK_MILLIS);
    }

    // ------------------------------------------------------------------ locks and notification

    private void acquireLocks() {
        synchronized (lockGuard) {
            if (wakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (power != null) {
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaacc:snapcast-server");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire(10L * 60L * 1000L); // renewed by the next tick while the work goes on
                }
            } else if (!wakeLock.isHeld()) {
                wakeLock.acquire(10L * 60L * 1000L);
            }
            if (wifiLock == null) {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    wifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yaacc:snapcast-server");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
        }
    }

    private void releaseLocks() {
        synchronized (lockGuard) {
            try {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException alreadyReleased) {
                // nothing left to release
            }
            wakeLock = null;
            try {
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (RuntimeException alreadyReleased) {
                // nothing left to release
            }
            wifiLock = null;
        }
    }

    private void showForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationId.SNAPCAST_SERVER.getId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NotificationId.SNAPCAST_SERVER.getId(), notification);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SnapcastServerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle(getString(R.string.snapcast_server_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.snapcast_server_stop), stop)
                .build();
    }
}
