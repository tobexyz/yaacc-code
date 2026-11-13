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
package de.yaacc.multiroom.client;

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
import android.os.Process;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.multiroom.control.SocketTransport;
import de.yaacc.multiroom.discovery.SnapcastResolver;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.SnapcastConnection;
import de.yaacc.settings.SettingsActivity;
import de.yaacc.util.NotificationId;

/**
 * The phone as a Snapcast client, for the listen test (task 8.1): a foreground service of type {@code mediaPlayback}
 * that owns the {@link SnapcastConnection}, the {@link SnapcastClientEngine} and the output while a session runs.
 *
 * <p><b>Lifetime.</b> It is started by the spike screen (never by the system: {@code START_NOT_STICKY}, not exported) and
 * lives only while a session is on. A wake lock (partial, with a safety timeout of 8 hours, renewed while the session runs)
 * and a Wi-Fi lock are taken at the start and released at <b>every</b> end: the stop button, an error of the connection or of the engine, the
 * watchdog, and {@code onDestroy}. The notification is the standard one with a Stop action. No listener socket is
 * opened; the phone connects out to the one server the user typed in, by an address resolved once.
 *
 * <p><b>Disclosure.</b> The Hello carries the name of the phone as the UPnP server announces it, the program name, the
 * generic words "Android" and "unknown", and an id derived per server from a secret of this installation
 * ({@link SnapcastClientIdentity}); the MAC is the fixed placeholder. Nothing from the server is written to the log.
 */
public final class SnapcastClientService extends Service {

    static final String ACTION_START = "de.yaacc.multiroom.client.START";
    static final String ACTION_STOP = "de.yaacc.multiroom.client.STOP";
    static final String ACTION_LATENCY = "de.yaacc.multiroom.client.LATENCY";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_LATENCY_MS = "latencyMs";
    static final String EXTRA_WRITE_AHEAD_MS = "writeAheadMs";
    static final String EXTRA_FLAC_GUARD = "flacGuard";

    private static final long WATCHDOG_INTERVAL_MS = 2_000L;
    private static final long WATCHDOG_LIMIT_MS = 5_000L;
    private static final long NOTIFICATION_INTERVAL_MS = 1_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean sessionStarted = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final Object lockGuard = new Object();
    private ExecutorService worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile SnapcastConnection connection;
    private volatile EngineHost host;
    private volatile AudioSink sink;
    private volatile long startedAtMillis;
    private final NotificationPolicy notificationPolicy = new NotificationPolicy();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private SessionPowerPolicy powerPolicy; // main thread only
    private SnapcastClientRuntime.Phase lastPhaseShown;
    private volatile Integer pendingLatencyMs;
    private volatile int writeAheadMs = SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS;
    private volatile boolean flacGuard = true;

    // ---- entry points for the screen

    /** Starts a session. Call from the UI (a foreground service may be started from there). */
    public static void start(Context context, String hostText, int latencyMs) {
        start(context, hostText, latencyMs, SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS);
    }

    /** @param writeAheadMs how far ahead of the playback position the output is kept filled (applied at the start only) */
    public static void start(Context context, String hostText, int latencyMs, int writeAheadMs) {
        start(context, hostText, latencyMs, writeAheadMs, true);
    }

    /**
     * @param flacGuard the stall guard of the flac path (it only acts when the positions of the chunks are read from the
     *                  FLAC frame headers; with this off nothing is ever filled)
     */
    public static void start(Context context, String hostText, int latencyMs, int writeAheadMs, boolean flacGuard) {
        Intent intent = new Intent(context, SnapcastClientService.class).setAction(ACTION_START)
                .putExtra(EXTRA_HOST, hostText).putExtra(EXTRA_LATENCY_MS, latencyMs)
                .putExtra(EXTRA_WRITE_AHEAD_MS, writeAheadMs).putExtra(EXTRA_FLAC_GUARD, flacGuard);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Ends the session, if there is one. */
    public static void stop(Context context) {
        try {
            context.startService(new Intent(context, SnapcastClientService.class).setAction(ACTION_STOP));
        } catch (IllegalStateException backgroundStartRefused) {
            // not running, and the app may not start a service from the background: nothing to stop
        }
    }

    /** Changes the manual latency of the running session. */
    public static void setLatency(Context context, int latencyMs) {
        try {
            context.startService(new Intent(context, SnapcastClientService.class).setAction(ACTION_LATENCY)
                    .putExtra(EXTRA_LATENCY_MS, latencyMs));
        } catch (IllegalStateException backgroundStartRefused) {
            // not running
        }
    }

    // ---- service

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
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (sessionStarted.get()) {
                endSession(SnapcastClientRuntime.Ending.USER);
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_LATENCY.equals(action)) {
            int latency = clampLatency(intent.getIntExtra(EXTRA_LATENCY_MS, 0));
            EngineHost current = host;
            if (current != null) {
                current.setManualLatencyMs(latency);
            } else if (sessionStarted.get() && !ended.get()) {
                pendingLatencyMs = latency; // the host is not built yet: connect() applies it
            } else if (!sessionStarted.get()) {
                stopSelf(); // nothing to apply it to; do not linger
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            if (!sessionStarted.get()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        try {
            // started with startForegroundService(): the notification must come before anything else
            // started with startForegroundService(): a notification must be posted at once. For the call that starts the
            // session that is "connecting"; for a second Start it is the text that is shown already (and the policy is told),
            // so a rejected Start does not change what the user sees (review S20 of Group 8)
            showForeground(sessionStarted.get() ? currentForegroundText() : getString(R.string.snapcast_client_notification_connecting));
            if (ended.get()) {
                // this instance is still tearing down the session before: the pending stop removes the notification again
                Toast.makeText(this, R.string.snapcast_client_busy, Toast.LENGTH_LONG).show();
                return START_NOT_STICKY;
            }
            if (!sessionStarted.compareAndSet(false, true)) {
                return START_NOT_STICKY; // a session runs already
            }
            if (!SnapcastClientSpike.isEnabled(PreferenceManager.getDefaultSharedPreferences(this)::getBoolean)) {
                endSession(SnapcastClientRuntime.Ending.DISABLED); // "default off" holds for every caller, not only the dialog
                return START_NOT_STICKY;
            }
            writeAheadMs = SnapcastClientSpike.clampWriteAhead(
                    intent.getIntExtra(EXTRA_WRITE_AHEAD_MS, SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS));
            flacGuard = intent.getBooleanExtra(EXTRA_FLAC_GUARD, true);
            beginSession(intent.getStringExtra(EXTRA_HOST), intent.getIntExtra(EXTRA_LATENCY_MS, 0));
        } catch (RuntimeException unexpected) {
            endSession(SnapcastClientRuntime.Ending.SERVICE_ERROR);
        }
        return START_NOT_STICKY;
    }

    private static int clampLatency(int value) {
        return Math.max(SnapcastClientSpike.MIN_LATENCY_MS, Math.min(SnapcastClientSpike.MAX_LATENCY_MS, value));
    }

    private void beginSession(String hostText, int latencyMs) {
        startedAtMillis = System.currentTimeMillis();
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NotificationId.SNAPCAST_CLIENT_ENDED.getId()); // the "Stopped: ..." of the last session is old news
            }
        } catch (RuntimeException ignored) {
            // a courtesy
        }
        final SnapcastClientSpike.Request request = SnapcastClientSpike.check(hostText, String.valueOf(latencyMs));
        if (request.getOutcome() != SnapcastClientSpike.Outcome.OK) {
            endSession(SnapcastClientRuntime.Ending.BAD_ADDRESS);
            return;
        }
        acquireLocks();
        powerPolicy = new SessionPowerPolicy(SystemClock.elapsedRealtime());
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.CONNECTING, SnapcastClientRuntime.Ending.NONE, startedAtMillis);
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapcast-client-start");
            thread.setDaemon(true);
            return thread;
        });
        try {
            worker.execute(() -> connect(request));
        } catch (RejectedExecutionException closed) {
            endSession(SnapcastClientRuntime.Ending.SERVICE_ERROR);
        }
    }

    /** On the worker: resolve once, build the pieces, start them. */
    private void connect(SnapcastClientSpike.Request request) {
        try {
            InetAddress address = SnapcastResolver.system().resolve(request.getEndpoint());
            String serverKey = SnapcastServerKey.of(request.getEndpoint()).asString();
            HelloMessage hello = HelloMessage.create(SnapcastClientIdentity.deriveId(installSecret(), serverKey),
                    SnapcastClientSpike.helloHostName(address, phoneName()),
                    "YAACC", "Android", "unknown", "spike");
            ClientSession session = ClientSession.assemble(new SocketTransport(address, request.getEndpoint().getPort()), hello,
                    request.getLatencyMs(), AudioTrackSink::new, new DefaultDecoderFactory(new AndroidDecoderFactory()),
                    SystemClock::elapsedRealtimeNanos, SnapcastClientSpike.engineConfig(writeAheadMs, flacGuard),
                    SnapcastClientService::audioThread,
                    new EngineHost.Observer() {
                        @Override
                        public void onStatus(EngineStatus status) {
                            SnapcastClientRuntime.engine(status);
                            requestNotificationRefresh();
                        }

                        @Override
                        public void onEnded(EngineStatus status) {
                            SnapcastClientRuntime.engine(status);
                            if (status.failure != null) {
                                endSession(SnapcastClientRuntime.Ending.ENGINE_FAILED);
                            }
                        }
                    });
            startServerPoll(address, hello.getId());
            SnapcastConnection newConnection = session.connection();
            EngineHost newHost = session.host();
            AudioSink newSink = session.sink();
            synchronized (this) {
                if (ended.get()) {
                    newConnection.close();
                    newSink.release();
                    return;
                }
                sink = newSink;
                host = newHost;
                connection = newConnection;
            }
            Integer latencyAsked = pendingLatencyMs;
            if (latencyAsked != null) {
                newHost.setManualLatencyMs(latencyAsked); // Apply was tapped while the session was still being built
            }
            newHost.start();
            newConnection.addListener(newHost);
            newConnection.addListener(new Lifecycle());
            newConnection.start();
            main.post(() -> {
                if (!ended.get()) {
                    SnapcastClientRuntime.phaseWhileActive(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, startedAtMillis);
                }
            });
        } catch (IOException unresolved) {
            endSession(SnapcastClientRuntime.Ending.UNRESOLVED);
        } catch (RuntimeException unexpected) {
            endSession(SnapcastClientRuntime.Ending.SERVICE_ERROR);
        }
    }

    private volatile java.util.concurrent.ScheduledExecutorService serverPoll;

    /**
     * Asks the control port what the server says about this client: every 5 s while no chunk has arrived, every 30 s
     * afterwards. Read-only (Server.GetStatus), one short connection per question, bounded by the control library.
     */
    private void startServerPoll(InetAddress address, String clientId) {
        final java.util.concurrent.ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snapcast-server-says");
            thread.setDaemon(true);
            return thread;
        });
        serverPoll = poll;
        final long[] signature = {Long.MIN_VALUE};
        final long startedAt = SystemClock.elapsedRealtime();
        Runnable ask = new Runnable() {
            @Override
            public void run() {
                if (ended.get() || serverPoll != poll) {
                    return;
                }
                long delay = 30_000L;
                try {
                    ServerSays says = ServerControl.fetch(address, clientId);
                    if (ended.get() || serverPoll != poll) {
                        return; // the session ended while the question was on its way: the answer is not for the next one
                    }
                    SnapcastClientRuntime.server(says);
                    long found = says.isClientFound() ? 1 : 0;
                    long playing = says.isOwnStreamPlaying() ? 1 : 0;
                    long now = found * 2 + playing + says.getStreams().size() * 4L;
                    EngineHost h = host;
                    if (h != null && now != signature[0]) {
                        signature[0] = now;
                        h.traceServerSays(found, playing, says.getStreams().size());
                    }
                    SnapcastClientRuntime.View view = SnapcastClientRuntime.get();
                    // every 5 s while nothing has come, but not for ever: a stream that is idle on purpose is asked every 30 s
                    // after five minutes
                    if ((view.engine == null || view.engine.chunksReceived == 0)
                            && SystemClock.elapsedRealtime() - startedAt < 300_000L) {
                        delay = 5_000L;
                    }
                } catch (RuntimeException ignored) {
                    delay = 10_000L;
                }
                try {
                    poll.schedule(this, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException closed) {
                    // the session ended
                }
            }
        };
        try {
            poll.schedule(ask, 2_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // ended already
        }
    }

    private static Thread audioThread(Runnable body) {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            body.run();
        }, "snapcast-audio");
        thread.setDaemon(true);
        return thread;
    }

    /** What the connection reports besides the audio: phases and the end of the retries. */
    private final class Lifecycle implements SnapcastConnection.Listener {
        @Override
        public void onServerSettings(ServerSettingsMessage settings) {
            // A server can send these as fast as the connection accepts frames: only the first one after a connect or a
            // reconnect changes anything, and nothing is posted to the main thread for the rest (security review W2).
            if (SnapcastClientRuntime.get().phase == SnapcastClientRuntime.Phase.RUNNING) {
                return;
            }
            main.post(() -> {
                if (!ended.get()) {
                    SnapcastClientRuntime.phaseWhileActive(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, startedAtMillis);
                    refreshNotification();
                }
            });
        }

        @Override
        public void onLost(SnapcastConnection.Loss loss) {
            main.post(() -> {
                if (!ended.get()) {
                    SnapcastClientRuntime.phaseWhileActive(SnapcastClientRuntime.Phase.RECONNECTING, SnapcastClientRuntime.Ending.NONE, startedAtMillis);
                    refreshNotification();
                }
            });
        }

        @Override
        public void onGaveUp(SnapcastConnection.Loss loss) {
            endSession(loss.isPermanent() ? SnapcastClientRuntime.Ending.SERVER_REFUSED
                    : SnapcastClientRuntime.Ending.CONNECTION_GAVE_UP);
        }
    }

    /**
     * Every 2 seconds on the main thread: the watchdog of the engine thread, and the power policy (locks only while the
     * engine plays, an end after 15 minutes without audio, see {@link SessionPowerPolicy}).
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            // a lock call that throws must end the session, not the process (review S19 of Group 8)
            SafeRun.run(this::step, () -> endSession(SnapcastClientRuntime.Ending.SERVICE_ERROR));
        }

        private void step() {
            if (ended.get()) {
                return;
            }
            EngineHost current = host;
            if (current != null && current.isStalled(WATCHDOG_LIMIT_MS)) {
                AudioSink output = sink;
                current.abandon(); // reports the end, which ends the session
                if (output != null) {
                    output.release();
                }
                return;
            }
            powerTick();
            if (!ended.get()) {
                main.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    private void powerTick() {
        SnapcastClientRuntime.View view = SnapcastClientRuntime.get();
        boolean playing = view.phase == SnapcastClientRuntime.Phase.RUNNING && view.engine != null
                && view.engine.state == EngineState.PLAYING;
        SessionPowerPolicy.Decision decision = powerPolicy.update(SystemClock.elapsedRealtime(), playing);
        if (decision.end) {
            endSession(SnapcastClientRuntime.Ending.NO_AUDIO);
            return;
        }
        if (decision.releaseNow) {
            releaseLocks();
        }
        if (decision.acquireNow) {
            acquireLocks();
        }
        if (decision.renewNow) {
            renewWakeLock();
        }
    }

    /** Ends the session once, from any thread: locks go first, the blocking parts run on a thread of their own. */
    private void endSession(SnapcastClientRuntime.Ending ending) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        main.removeCallbacks(watchdog);
        recordEnding(ending);
        SnapcastClientRuntime.phase(ending == SnapcastClientRuntime.Ending.USER ? SnapcastClientRuntime.Phase.STOPPED
                : SnapcastClientRuntime.Phase.FAILED, ending, startedAtMillis);
        releaseLocks();
        Thread teardown = new Thread(() -> {
            teardown();
            main.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }, "snapcast-client-teardown");
        teardown.setDaemon(true);
        teardown.start();
    }

    /**
     * The service never ends silently: the reason and the time are kept (the dialog shows them later, also after the
     * process is gone), the trace has a line, and a notification says why when it was not the user.
     */
    private void recordEnding(SnapcastClientRuntime.Ending ending) {
        try {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(SnapcastClientSpike.KEY_LAST_ENDING, ending.name())
                    .putLong(SnapcastClientSpike.KEY_LAST_ENDING_AT, System.currentTimeMillis()).apply();
            EngineHost h = host;
            if (h != null) {
                h.traceEnding(ending.ordinal());
            }
            if (ending != SnapcastClientRuntime.Ending.USER) {
                notifyEnded(ending);
            }
        } catch (RuntimeException ignored) {
            // the record is a courtesy; ending the session must not fail because of it
        }
    }

    private void notifyEnded(SnapcastClientRuntime.Ending ending) {
        String reason = SnapcastClientStatusText.endingText(ending);
        Notification notification = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle(getString(R.string.snapcast_client_notification_title))
                .setContentText(getString(R.string.snapcast_client_notification_stopped, reason))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.snapcast_client_notification_stopped, reason)))
                .setSmallIcon(R.drawable.ic_notification_default)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NotificationId.SNAPCAST_CLIENT_ENDED.getId(), notification);
            }
        } catch (SecurityException noPermission) {
            // no notification permission: the dialog keeps the reason
        }
    }

    /** Closes the connection, ends the engine thread, frees the output. Blocks for up to a few seconds. */
    private void teardown() {
        SnapcastConnection c;
        EngineHost h;
        AudioSink s;
        synchronized (this) {
            c = connection;
            h = host;
            s = sink;
        }
        java.util.concurrent.ScheduledExecutorService poll = serverPoll;
        if (poll != null) {
            poll.shutdownNow();
        }
        if (c != null) {
            c.close();
        }
        if (h != null) {
            h.stop();
        }
        if (s != null) {
            s.release();
        }
    }

    @Override
    public void onDestroy() {
        if (ended.compareAndSet(false, true)) {
            main.removeCallbacks(watchdog);
            if (sessionStarted.get()) {
                // nobody asked for this (a stop by the user ends the session first): the system or the app ended the service
                SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.Ending.SYSTEM, startedAtMillis);
                recordEnding(SnapcastClientRuntime.Ending.SYSTEM);
            }
            Thread teardown = new Thread(this::teardown, "snapcast-client-teardown");
            teardown.setDaemon(true);
            teardown.start();
        }
        releaseLocks();
        if (worker != null) {
            worker.shutdownNow();
        }
        super.onDestroy();
    }

    // ---- locks

    /** The wake lock has a safety timeout of 8 hours; while the session plays it is renewed every 30 minutes. */
    private void renewWakeLock() {
        synchronized (lockGuard) {
            if (wakeLock != null && !ended.get()) {
                wakeLock.acquire(LockPolicy.WAKE_LOCK_SAFETY_TIMEOUT_MS);
            }
        }
    }

    /**
     * Wi-Fi lock: {@code FULL_HIGH_PERF} up to Android 13 (it holds Wi-Fi out of power save with the screen off), and
     * {@code FULL_LOW_LATENCY} from Android 14, where the platform replaced the first by the second and that one is only
     * active with the screen on and the app in the foreground (see {@link LockPolicy}). So from API 34 the lock is
     * taken but does nothing for a screen-off session; that is a limit of the platform, not something the app can fix.
     */
    private void acquireLocks() {
        synchronized (lockGuard) {
            if (ended.get() || wakeLock != null || wifiLock != null) {
                return; // the session is over, or the locks are held
            }
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaacc:snapcast-client");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(LockPolicy.WAKE_LOCK_SAFETY_TIMEOUT_MS);
            }
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(LockPolicy.wifiMode(Build.VERSION.SDK_INT), "yaacc:snapcast-client");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
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

    // ---- notification

    private void showForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationId.SNAPCAST_CLIENT.getId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NotificationId.SNAPCAST_CLIENT.getId(), notification);
        }
    }

    /** Asks for a refresh from any thread; at most one request waits on the main thread at any time. */
    private void requestNotificationRefresh() {
        if (refreshPending.compareAndSet(false, true)) {
            main.post(() -> {
                refreshPending.set(false);
                refreshNotification();
            });
        }
    }

    /** Main thread. Rebuilds the notification only when its text changed (see {@link NotificationPolicy}). */
    private void refreshNotification() {
        if (ended.get()) {
            return;
        }
        SnapcastClientRuntime.View view = SnapcastClientRuntime.get();
        NotificationText text = currentText(view);
        boolean phaseChange = view.phase != lastPhaseShown;
        if (!notificationPolicy.shouldRefresh(text, phaseChange, SystemClock.elapsedRealtime())) {
            return;
        }
        lastPhaseShown = view.phase;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NotificationId.SNAPCAST_CLIENT.getId(), buildNotification(getString(textResource(text))));
        }
    }

    private String currentForegroundText() {
        NotificationText text = currentText(SnapcastClientRuntime.get());
        notificationPolicy.shouldRefresh(text, true, SystemClock.elapsedRealtime()); // records it as the one shown
        return getString(textResource(text));
    }

    private static NotificationText currentText(SnapcastClientRuntime.View view) {
        return NotificationText.of(view.phase, view.engine == null ? null : view.engine.state);
    }

    private static int textResource(NotificationText text) {
        switch (text) {
            case PLAYING:
                return R.string.snapcast_client_notification_playing;
            case RECONNECTING:
                return R.string.snapcast_client_notification_reconnecting;
            default:
                return R.string.snapcast_client_notification_connecting;
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SnapcastClientService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle(getString(R.string.snapcast_client_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.snapcast_client_stop), stop)
                .build();
    }

    // ---- identity

    private String installSecret() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String secret = preferences.getString(SnapcastClientSpike.KEY_SECRET, null);
        if (!SnapcastClientIdentity.isSecret(secret)) {
            secret = SnapcastClientIdentity.newSecret(new SecureRandom());
            preferences.edit().putString(SnapcastClientSpike.KEY_SECRET, secret).apply();
        }
        return secret;
    }

    /** The name the UPnP server announces (the same preference and default as the Snapcast feed uses). */
    private String phoneName() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.settings_local_server_name_key), "Yaacc");
    }
}
