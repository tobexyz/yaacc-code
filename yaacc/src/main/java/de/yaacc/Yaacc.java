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
import android.os.SystemClock;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import de.yaacc.browser.BackgroundIdleTimer;
import de.yaacc.browser.BrowserLauncher;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.client.SnapcastClientPowers;
import de.yaacc.multiroom.client.SnapcastClientRestore;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerService;
import de.yaacc.settings.ThemeChoice;
import de.yaacc.share.IncomingCache;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.UnclaimedShares;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.ServerAutostart;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.ExitHandoff;
import de.yaacc.util.ExitRestartGuard;
import de.yaacc.util.ExitRestartGuards;
import de.yaacc.util.ExitSequence;
import de.yaacc.util.NotificationId;
import de.yaacc.util.SAFCacheManager;
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
    // The exit runs once; a second call (the button twice, the idle timer while the exit waits) finds it running.
    private final AtomicBoolean exiting = new AtomicBoolean(false);
    // The process was created right after an explicit exit: the server service and the client wait for the user (userStarted).
    private boolean startDeferredAfterExit;
    // How long the exit waits for the services and the Snapcast feeds to end, and how often it looks (milliseconds).
    private static final long EXIT_MAX_WAIT_MILLIS = 3000L;
    private static final long EXIT_POLL_MILLIS = 100L;
    // How long the exit waits for the players to end (each on its own thread) before it goes on without them.
    private static final long EXIT_PLAYERS_MAX_WAIT_MILLIS = 4000L;
    // The player service was stopped by the exit (only when every player ended): only then is it waited for.
    private volatile boolean playerServiceStopped;


    @Override
    public void onCreate() {
        super.onCreate();
        YaaccLogger.initialize(this);
        YaaccLogger.i(getClass().getName(), "YAACC " + BuildConfig.VERSION_NAME + " build " + BuildConfig.GIT_COMMIT + " started");
        createNotificationChannel();
        PreferenceManager.setDefaultValues(this, R.xml.preference, false);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        ThemeChoice.migrateLegacyDarkMode(preferences, getString(R.string.settings_dark_mode_key),
                getString(R.string.settings_theme_key));
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(preferences.getString(
                getString(R.string.settings_theme_key), ThemeChoice.DEFAULT_STORED_VALUE)));

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
        // The boot receiver follows the setting "autostart on boot" (disabled in the manifest): synchronised here so that an
        // installation that existed before has the right state after the first start of the app.
        ServerAutostart.applySetting(this, preferences.getBoolean(getString(R.string.settings_autostart_chkbx), false));
        sweepIncomingCopies();
        startShareSweeper();
        registerActivityLifecycleCallbacks(new UserStartCallbacks());
        // A process that is created right after an explicit exit (the system brought something back) is not the user opening the app:
        // the server service and the client are not started until the user starts it (LauncherActivity or a browser activity).
        if (ExitRestartGuards.of(this).decide(ExitRestartGuard.StartReason.PROCESS_START) == ExitRestartGuard.Decision.STOP_SELF) {
            YaaccLogger.i(getClass().getName(), "Process created right after an exit, nothing is started until the user starts the app");
            startDeferredAfterExit = true;
            upnpClient = new UpnpClient();
            upnpClient.attach(this); // no service is started or bound yet
        } else {
            try {
                startService(new Intent(this, YaaccUpnpServerService.class));
            } catch (IllegalStateException e) {
                // a process created in the background (boot) may not start a service; the boot receiver's startForegroundService
                // is the fallback, and the service starts with the first screen at the latest
                YaaccLogger.w(getClass().getName(), "Start of the server service was refused: " + e.getClass().getSimpleName());
            }
            upnpClient = new UpnpClient(this);
        }
        // The phone's own Snapcast client comes back when it was switched on (also in a process that a reboot created: the autostart
        // setting enables the boot receiver). Held back right after an exit; a start the system refuses is tried once more when a
        // screen starts (userStarted). Never throws.
        SnapcastClientPowers.restore(this, SnapcastClientRestore.Point.PROCESS_START, startDeferredAfterExit);
        // A process without a screen (boot, restart by the system) closes itself after the idle time like one whose last screen
        // went away; the first screen cancels it. The autostart setting and the running services are read when it fires.
        BackgroundIdleTimer.processStarted(this);
    }

    /**
     * The user starts the app (any screen created fresh, see {@link UserStartCallbacks}): whatever starts after this is a
     * normal start, and what was held back after an exit is started now. Main thread.
     */
    public void userStarted() {
        if (exiting.get()) {
            return; // the exit is running: a screen opened now must not clear the mark or start services in a process that ends
        }
        ExitRestartGuards.of(this).clearOnUserStart();
        if (startDeferredAfterExit) {
            startDeferredAfterExit = false;
            startService(new Intent(this, YaaccUpnpServerService.class));
            upnpClient.bindServerService();
        }
        // the held-back start of the Snapcast client, or the one more try after a start that the system refused in the background
        SnapcastClientPowers.restore(this, SnapcastClientRestore.Point.SCREEN_START, false);
    }

    /**
     * Any screen of the app that is created fresh (not restored from a saved state) is the user in the app, whichever way in it was
     * (launcher, share, a notification, the image viewer, a player screen). A restored screen right after an explicit exit is left
     * over from before it and ends.
     */
    private final class UserStartCallbacks implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(android.app.Activity activity, android.os.Bundle savedInstanceState) {
            if (savedInstanceState == null) {
                userStarted();
            } else if (dropRestoredScreen()) {
                activity.finish();
            }
        }

        @Override
        public void onActivityStarted(android.app.Activity activity) {
        }

        @Override
        public void onActivityResumed(android.app.Activity activity) {
        }

        @Override
        public void onActivityPaused(android.app.Activity activity) {
        }

        @Override
        public void onActivityStopped(android.app.Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(android.app.Activity activity) {
        }
    }

    /**
     * A browser activity that the system recreates from its saved state right after an explicit exit is left over from before the
     * exit: it finishes instead of bringing the app back.
     */
    public boolean dropRestoredScreen() {
        return ExitRestartGuards.of(this).decide(ExitRestartGuard.StartReason.RESTORED_SCREEN) == ExitRestartGuard.Decision.STOP_SELF;
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

    /**
     * Ends the app: the exit button and the notification action, the shutdown (sleep) timer and the idle timer. The order is the
     * point (tasks 12.2 and 12.9): mark the exit (persisted, so that a restart the system does afterwards is recognised), close the
     * shares, end the players (media session, player notification, the Snapcast feed gives the group its stream back), stop the
     * services and let go of the bindings, cancel the notifications, finish the tasks, then wait (bounded, without blocking the
     * main thread, which the releases need) for the services and the feeds to end, cancel the notifications once more and end the
     * process. A step that fails does not keep the others from running ({@link ExitSequence}).
     * <p>
     * The main thread never waits for a player (review W2 of Group 12A): ending a remote player sleeps and polls on the thread that
     * does it, so every player is ended on a thread of its own ({@link ExitHandoff}) and the rest of the exit continues on the main
     * thread when they ended or after {@link #EXIT_PLAYERS_MAX_WAIT_MILLIS}.
     */
    public void exit() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // the releases of the players and the services' onDestroy run on the main thread: the exit starts there
            new Handler(Looper.getMainLooper()).post(this::exit);
            return;
        }
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        YaaccLogger.d(getClass().getName(), "Start shutdown and close");
        ExitSequence.Result first = new ExitSequence()
                // before anything is stopped: whatever the system starts again afterwards is then recognised
                .add(ExitSequence.STEP_MARK, () -> ExitRestartGuards.of(this).markExitRequested())
                // close every share (held files, links) first, so that a failure further down cannot
                // leave a descriptor open or a share reachable
                .add(ExitSequence.STEP_SHARES, this::closeShares)
                // written before the process ends, and before the services go (the spike dialog finds the reason)
                .add(ExitSequence.STEP_SNAPCAST_NOTE, this::recordSnapcastClientEnding)
                .run();
        if (!first.allSucceeded()) {
            YaaccLogger.w(getClass().getName(), "Steps of the exit failed: " + first.failed());
        }
        exitPlayersThenContinue();
    }

    /**
     * Ends every active player on a thread of its own and goes on with {@link #continueExit} on the main thread.
     */
    private void exitPlayersThenContinue() {
        final Handler main = new Handler(Looper.getMainLooper());
        List<String> names = new ArrayList<>();
        List<ExitSequence.Step> steps = new ArrayList<>();
        UpnpClient client = upnpClient;
        if (client != null) {
            for (Player player : new ArrayList<>(client.getCurrentPlayers())) {
                names.add("player " + player.getId());
                steps.add(player::exit);
            }
        }
        ExitHandoff.run(names, steps, Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "yaacc-exit-player");
            thread.setDaemon(true);
            return thread;
        }), new ExitHandoff.MainThread() {
            @Override
            public void post(Runnable task) {
                main.post(task);
            }

            @Override
            public void postDelayed(Runnable task, long delayMillis) {
                main.postDelayed(task, delayMillis);
            }
        }, EXIT_PLAYERS_MAX_WAIT_MILLIS, outcome -> {
            if (!outcome.failed().isEmpty() || !outcome.allEnded()) {
                YaaccLogger.w(getClass().getName(), ExitSequence.STEP_PLAYERS + ": failed " + outcome.failed()
                        + ", not ended " + outcome.unfinished());
            }
            continueExit(outcome.allEnded());
        });
    }

    /**
     * The rest of the exit, on the main thread, after the players ended (or the bound was up).
     */
    private void continueExit(boolean playersEnded) {
        ExitSequence.Result rest = new ExitSequence()
                .add(ExitSequence.STEP_SERVICES, () -> stopServices(playersEnded))
                .add(ExitSequence.STEP_NOTIFICATIONS, this::cancelNotifications)
                .add(ExitSequence.STEP_TASKS, this::finishTasks)
                .run();
        if (!rest.allSucceeded()) {
            YaaccLogger.w(getClass().getName(), "Steps of the exit failed: " + rest.failed());
        }
        awaitEndOfServicesThenEndProcess(SystemClock.uptimeMillis());
    }

    private void awaitEndOfServicesThenEndProcess(long startedAt) {
        long waited = SystemClock.uptimeMillis() - startedAt;
        // posted, not slept: the main thread has to run what the exit queued (the players' releases, the services' onDestroy)
        if (ExitSequence.shouldKeepWaiting(servicesOrFeedsStillRunning(), waited, EXIT_POLL_MILLIS, EXIT_MAX_WAIT_MILLIS)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> awaitEndOfServicesThenEndProcess(startedAt), EXIT_POLL_MILLIS);
            return;
        }
        ExitSequence.Result last = new ExitSequence()
                // the media notification can be posted again by a release that ran late; the foreground ones go with their services
                .add(ExitSequence.STEP_NOTIFICATIONS, this::cancelNotifications)
                .add(ExitSequence.STEP_END, () -> Runtime.getRuntime().exit(0))
                .run();
        // only reached when the process end itself failed
        YaaccLogger.w(getClass().getName(), "The process did not end: " + last.failed());
    }

    private void closeShares() {
        try {
            shareRegistry.removeAll();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not close all shares (" + e.getClass().getSimpleName() + ")");
        }
        if (shareSweeper != null) {
            shareSweeper.shutdownNow();
        }
    }

    /**
     * Lets go of the bindings and stops the services. The player service is stopped only when every player ended: its onDestroy
     * ends the players that are left, on the main thread, and a remote player waits there.
     */
    private void stopServices(boolean playersEnded) {
        final UpnpClient client = upnpClient;
        ExitSequence sequence = new ExitSequence()
                // a bound service does not end when it is stopped
                .add("let go of the bindings", () -> {
                    if (client != null) {
                        client.unbindServices();
                    }
                })
                .add("stop the server service", () -> stopService(new Intent(this, YaaccUpnpServerService.class)));
        if (playersEnded) {
            sequence.add("stop the player service", () -> {
                stopService(new Intent(this, PlayerService.class));
                playerServiceStopped = true;
            });
        }
        ExitSequence.Result result = sequence.run();
        if (!result.allSucceeded()) {
            YaaccLogger.w(getClass().getName(), "Services that did not stop cleanly: " + result.failed());
        }
    }

    private void cancelNotifications() {
        NotificationManager notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifications.cancel(NotificationId.UPNP_SERVER.getId());
        notifications.cancel(NotificationId.PLAYER_SERVICE.getId());
        notifications.cancel(NotificationId.YAACC.getId());
        // the notification of a player (Media3's PlayerNotificationManager) has an id of its own; they are all this app's own
        notifications.cancelAll();
    }

    private void finishTasks() {
        ActivityManager am = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        am.getAppTasks().forEach(ActivityManager.AppTask::finishAndRemoveTask);
        clearCache();
    }

    /**
     * The services of the app that must be gone before the process ends, and the feeds that give a group its stream back.
     */
    @SuppressWarnings("deprecation")
    // getRunningServices still lists the caller's own services, which is all that is asked here
    private boolean servicesOrFeedsStillRunning() {
        try {
            SnapcastHub hub = SnapcastHub.peekShared();
            if (hub != null && hub.openFeedControllers() > 0) {
                return true;
            }
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service : am.getRunningServices(100)) {
                String name = service.service.getClassName();
                // "started": a service that only some other app has bound (a media controller of the system) is not ours to wait for
                boolean ours = YaaccUpnpServerService.class.getName().equals(name)
                        || (playerServiceStopped && PlayerService.class.getName().equals(name));
                if (ours && service.started) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not look for running services (" + e.getClass().getSimpleName() + ")");
        }
        return false;
    }

    /**
     * The exit action and the shutdown (sleep) timer end the whole process, and with it a running Snapcast client session,
     * without a word: keep the reason where the spike dialog finds it (written at once, the process ends right after).
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    // commit on purpose: the process ends right after
    private void recordSnapcastClientEnding() {
        try {
            if (de.yaacc.multiroom.client.SnapcastClientRuntime.isActive()) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putString(de.yaacc.multiroom.client.SnapcastClientSpike.KEY_LAST_ENDING,
                                de.yaacc.multiroom.client.SnapcastClientRuntime.Ending.APP_EXIT.name())
                        .putLong(de.yaacc.multiroom.client.SnapcastClientSpike.KEY_LAST_ENDING_AT, System.currentTimeMillis())
                        .commit();
            }
        } catch (RuntimeException ignored) {
            // the exit must go on
        }
    }

    private void clearCache() {
        // The SAF cache collects its writes in memory: write what is still waiting before the process ends
        SAFCacheManager.flushIfCreated(1000);
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
