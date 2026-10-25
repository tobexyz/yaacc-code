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
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.yaacc.browser.BrowserLauncher;
import de.yaacc.player.PlayerService;
import java.io.File;
import java.util.List;

import de.yaacc.share.IncomingCache;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.UnclaimedShares;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.SAFCacheManager;
import de.yaacc.util.NotificationId;
import de.yaacc.util.SafPermissionManager;
import de.yaacc.util.ShutdownTimerListener;
import de.yaacc.util.YaaccLogger;

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
    private CountDownTimer shutdownTimer;
    private final CopyOnWriteArrayList<ShutdownTimerListener> shutdownTimerListeners = new CopyOnWriteArrayList<>();
    private volatile boolean shutdownTimerRunning = false;
    private volatile long shutdownTimerRemainingMillis = 0L;
    // Shares served by the media proxy: in memory only, lives as long as the process.
    private final ShareRegistry shareRegistry = ShareRegistry.createDefault();
    // Keys that no player has taken yet: the sweeper removes them after the claim window.
    private final UnclaimedShares unclaimedShares = new UnclaimedShares();
    // The one shared file that waits for the user's receiver pick (memory only, lost with the process).
    private final PendingShare pendingShare = PendingShare.createDefault();
    // Tokens of the launcher's file forwards (memory only, valid for seconds, used once).
    private final LaunchNonces launchNonces = LaunchNonces.createDefault();
    private ScheduledExecutorService shareSweeper;


    @Override
    public void onCreate() {
        super.onCreate();
        YaaccLogger.initialize(this);
        createNotificationChannel();
        PreferenceManager.setDefaultValues(this, R.xml.preference, false);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean darkMode = preferences.getBoolean(getString(R.string.settings_dark_mode_key), true);
        if (darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }

        int numThreads = Integer.parseInt(preferences.getString(getString(R.string.settings_browse_load_threads_key), "10"));
        YaaccLogger.d(getClass().getName(), "Number of Threads used for content loading: " + numThreads);
        if (numThreads <= 0) {
            YaaccLogger.d(getClass().getName(), "Number of Threads invalid using 10 threads instead: " + numThreads);
            numThreads = 10;
        }
        contentLoadThreadPool = Executors.newFixedThreadPool(numThreads);

        // Always start with streaming disabled
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            preferences.edit()
                    .putBoolean(getString(R.string.settings_local_server_serve_system_audio_chkbx), false)
                    .putBoolean(getString(R.string.settings_local_server_serve_screen_cast_chkbx), false)
                    .apply();
        }

        // Validate and cleanup SAF permissions on app startup
        SafPermissionManager.validateAndCleanupPermissions(this);
        removeLegacyProxyLinks(preferences);
        sweepIncomingCopies();
        startShareSweeper();
        startService(new Intent(this, YaaccUpnpServerService.class));
        upnpClient = new UpnpClient(this);

    }

    /**
     * The shares the media proxy serves (links and held files), process wide. Never null.
     */
    public ShareRegistry getShareRegistry() {
        return shareRegistry;
    }

    /**
     * The shares that no player has claimed yet. Never null.
     */
    public UnclaimedShares getUnclaimedShares() {
        return unclaimedShares;
    }

    /**
     * The single-use tokens that the launcher puts into a forwarded file share. Never null.
     */
    public LaunchNonces getLaunchNonces() {
        return launchNonces;
    }

    /**
     * The one pending shared file (waiting for the receiver pick). Never null.
     */
    public PendingShare getPendingShare() {
        return pendingShare;
    }

    /**
     * The clock of the share registry (monotonic milliseconds), for the code that must agree with
     * it; see {@link ShareRegistry#createDefault()}.
     */
    public static long shareClockMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * The registry is in memory, so any copy of a shared file left in {@code cache/incoming/} is
     * an orphan of an earlier run. Deleted on a background thread; only files with the exact
     * shape of our own names are touched, links and directories never.
     */
    private void sweepIncomingCopies() {
        final File incoming = new File(getCacheDir(), IncomingCache.DIR_NAME);
        Thread thread = new Thread(() -> {
            try {
                int deleted = IncomingCache.sweep(incoming);
                if (deleted > 0) {
                    YaaccLogger.i(Yaacc.class.getName(), deleted + " old copy file(s) removed");
                }
            } catch (RuntimeException e) {
                YaaccLogger.w(Yaacc.class.getName(), "Sweep of old copies failed (" + e.getClass().getSimpleName() + ")");
            }
        }, "incoming-sweep");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Earlier versions kept the links that were shared through the media proxy in the
     * preferences. Nothing reads them any more; this removes the leftovers of an update once.
     */
    private void removeLegacyProxyLinks(SharedPreferences preferences) {
        try {
            Set<String> legacy = preferences.getAll().keySet().stream()
                    .filter(k -> k.startsWith(YaaccUpnpServerService.PROXY_LINK_KEY_PREFIX))
                    .collect(Collectors.toSet());
            if (!legacy.isEmpty()) {
                SharedPreferences.Editor editor = preferences.edit();
                legacy.forEach(editor::remove);
                editor.apply();
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not remove old proxy link settings", e);
        }
    }

    /**
     * Drops expired shares without waiting for a request, so that an expired share does not keep
     * its file open.
     */
    private void startShareSweeper() {
        shareSweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "share-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        shareSweeper.scheduleWithFixedDelay(() -> {
            try {
                shareRegistry.expire();
            } catch (RuntimeException e) {
                YaaccLogger.w(Yaacc.class.getName(), "Share sweep failed", e);
            }
            try {
                // a share no player took within the claim window gives its slot (and, for a file, its descriptor) back
                List<String> unclaimed = unclaimedShares.dueForRemoval(shareClockMillis());
                for (String key : unclaimed) {
                    shareRegistry.remove(key);
                }
                if (!unclaimed.isEmpty()) {
                    YaaccLogger.i(Yaacc.class.getName(), unclaimed.size() + " unclaimed share(s) removed");
                }
            } catch (RuntimeException e) {
                YaaccLogger.w(Yaacc.class.getName(), "Unclaimed share sweep failed (" + e.getClass().getSimpleName() + ")");
            }
            try {
                // readers that stalled or trickle give their slot back (a response that resumes asks for a slot again and only aborts when the share's cap is full)
                int idle = ShareConcurrencyLimiter.shared().expireIdle(ShareLimits.WRITE_IDLE_MILLIS);
                if (idle > 0) {
                    YaaccLogger.i(Yaacc.class.getName(), idle + " idle share response(s) lost their slot");
                }
            } catch (RuntimeException e) {
                YaaccLogger.w(Yaacc.class.getName(), "Idle response sweep failed", e);
            }
        }, ShareLimits.SWEEP_INTERVAL_MILLIS, ShareLimits.SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
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

    public void exit() {
        YaaccLogger.d(getClass().getName(), "Start shutdown and close");
        // close every share (held files, links) first, so that a failure further down cannot
        // leave a descriptor open or a share reachable
        try {
            shareRegistry.removeAll();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not close all shares (" + e.getClass().getSimpleName() + ")");
        }
        if (shareSweeper != null) {
            shareSweeper.shutdownNow();
        }
        upnpClient.shutdown();
        stopService(new Intent(this, PlayerService.class));
        stopService(new Intent(this, YaaccUpnpServerService.class));


        //FIXME work around to be fixed with new ui
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(NotificationId.UPNP_SERVER.getId());
        mNotificationManager.cancel(NotificationId.PLAYER_SERVICE.getId());
        mNotificationManager.cancel(NotificationId.YAACC.getId());
        ActivityManager am = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        am.getAppTasks().stream().forEach(t -> t.finishAndRemoveTask());
        clearCache();
        Runtime.getRuntime().exit(0);
    }

    private void clearCache() {
        // Trim cache to recommended size using LRU
        SAFCacheManager.getInstance(this).trimCache();
    }

    public void createNotificationChannel() {

        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        channel.setSound(null, null);


        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    public void createYaaccGroupNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        Intent notificationIntent = new Intent(this, BrowserLauncher.targetClass(this));
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

    /**
     * Starts the shutdown timer. The timer and its listener calls run on the main thread; a call
     * from another thread is posted there.
     */
    public void startShutdownTimer(long duration) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> startShutdownTimer(duration));
            return;
        }
        stopShutdownTimer();
        shutdownTimerRunning = true;
        shutdownTimerRemainingMillis = duration;
        shutdownTimer = new CountDownTimer(duration, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                YaaccLogger.d(getClass().getName(), "Shutdown in: " + millisUntilFinished + " millis");
                shutdownTimerRemainingMillis = millisUntilFinished;
                for (ShutdownTimerListener listener : shutdownTimerListeners) {
                    try {
                        listener.onTick(millisUntilFinished);
                    } catch (RuntimeException e) {
                        YaaccLogger.w(getClass().getName(), "Shutdown timer listener failed", e);
                    }
                }
            }

            @Override
            public void onFinish() {
                YaaccLogger.v(getClass().getName(), "Shutdown timer finished shutting down now!");
                shutdownTimerRunning = false;
                shutdownTimerRemainingMillis = 0L;
                exit();
            }
        };
        shutdownTimer.start();
    }

    public void stopShutdownTimer() {
        if (shutdownTimer != null) {
            shutdownTimer.cancel();
            shutdownTimer = null;
        }
        shutdownTimerRunning = false;
        shutdownTimerRemainingMillis = 0L;
    }

    /**
     * @return true while a shutdown timer is counting down
     */
    public boolean isShutdownTimerRunning() {
        return shutdownTimerRunning;
    }

    /**
     * @return the time left on the running shutdown timer as of the last tick, 0 if none runs
     */
    public long getShutdownTimerRemainingMillis() {
        return shutdownTimerRemainingMillis;
    }

    /**
     * Adds a listener that is called on the main thread on every tick. The application keeps it
     * until {@link #removeShutdownTimerListener(ShutdownTimerListener)}, so remove it when the UI
     * that owns it stops. Adding the same listener twice has no effect.
     */
    public void addShutdownTimerListener(ShutdownTimerListener listener) {
        if (listener != null) {
            shutdownTimerListeners.addIfAbsent(listener);
        }
    }

    public void removeShutdownTimerListener(ShutdownTimerListener listener) {
        shutdownTimerListeners.remove(listener);
    }
}
