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
package de.yaacc.sendspin.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.security.SecureRandom;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.multiroom.client.AudioTrackSink;
import de.yaacc.multiroom.client.AndroidDecoderFactory;
import de.yaacc.multiroom.client.ClientEngineConfig;
import de.yaacc.multiroom.client.DefaultDecoderFactory;
import de.yaacc.multiroom.client.EngineHost;
import de.yaacc.multiroom.client.EngineStatus;
import de.yaacc.multiroom.client.SnapcastClientEngine;
import de.yaacc.multiroom.discovery.SnapcastAddressFilter;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinSettings;
import de.yaacc.sendspin.discovery.SendspinSelf;
import de.yaacc.sendspin.discovery.SendspinServerAdvert;
import de.yaacc.sendspin.discovery.SendspinServiceType;
import de.yaacc.sendspin.hub.InboundServer;
import de.yaacc.util.NotificationId;
import de.yaacc.util.YaaccLogger;

/**
 * The phone as a Sendspin player (use case P1): a foreground service that, while the switch <i>Let servers play on this phone</i> is on,
 * keeps a listening socket (port 8928, path {@code /sendspin}) and announces the phone as {@code _sendspin._tcp}; a server that dials
 * it is handed to a {@link SendspinPlayerHost}. The wake lock and the Wi-Fi lock are held only while a stream plays. Only addresses of
 * the local network are accepted, never the phone's own. Started and stopped by {@link #sync}.
 */
public final class SendspinPlayerService extends Service {

    private static final String ACTION_STOP = "de.yaacc.sendspin.player.STOP";
    static final int PORT = 8928;
    private static final String PATH = "/sendspin";
    private static final int MAX_CONNECTIONS = 8;
    private static final int UPGRADE_TIMEOUT_MILLIS = 5000;

    private volatile boolean destroyed;
    private SendspinPlayerHost host;
    private InboundServer server;
    private SendspinServerAdvert advert;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Starts the service if the switch is on, stops it if it is off. Call from the main thread (an action of the user or the app start). */
    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
        Intent intent = new Intent(app, SendspinPlayerService.class);
        if (SendspinSettings.isPlayerEnabled(preferences::getBoolean)) {
            try {
                ContextCompat.startForegroundService(app, intent);
            } catch (RuntimeException refused) {
                YaaccLogger.w(SendspinPlayerService.class.getName(), "the player service was not started: " + refused.getClass().getSimpleName());
            }
        } else {
            app.stopService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNow(); // a service that was started as a foreground service posts its notification at once
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putBoolean(SendspinSettings.KEY_PLAYER, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!SendspinSettings.isPlayerEnabled(PreferenceManager.getDefaultSharedPreferences(this)::getBoolean)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureRunning();
        return START_NOT_STICKY; // not restarted by the system after an exit or a kill: the app start brings it back (sync)
    }

    /**
     * Which remote addresses are read at all: the local network, never an address of the phone itself, with one exception: the
     * loopback address, which is the app's own hub playing on its own phone (the phone as a member of a group). Whoever connects
     * over it counts as the app itself and needs no pairing.
     */
    static boolean acceptsAddress(java.net.InetAddress address) {
        if (address != null && address.isLoopbackAddress()) {
            return true;
        }
        return SnapcastAddressFilter.isAcceptedForDiscovery(address) && !SendspinSelf.isThisDevice(address);
    }

    private synchronized void ensureRunning() {
        if (host != null) {
            return;
        }
        final SendspinHub hub = SendspinHub.getShared(this);
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final MicroClock micro = MicroClock.fromNanoSource(SystemClock::elapsedRealtimeNanos);
        host = new SendspinPlayerHost(hub.clientKeys(), hub.clientPairings(),
                () -> SendspinSettings.isPlayerUnpairedAllowed(preferences::getBoolean),
                () -> SendspinSettings.isPlayerFlacPreferred(preferences::getBoolean), phoneName(), (offsets, clock) -> openEngine(offsets, clock),
                micro, new SecureRandom(), new PreferenceStore(preferences), this::refresh);
        host.setCodePairing(new ClientCodePairing(staticPairingCode(preferences), System::currentTimeMillis));
        server = new InboundServer(PORT, PATH, SendspinPlayerService::acceptsAddress,
                (link, remote) -> host.accept(link, remote.isLoopbackAddress()), MAX_CONNECTIONS, UPGRADE_TIMEOUT_MILLIS);
        int port;
        try {
            server.start();
            port = server.port();
        } catch (IOException busy) {
            server = new InboundServer(0, PATH, SendspinPlayerService::acceptsAddress,
                    (link, remote) -> host.accept(link, remote.isLoopbackAddress()), MAX_CONNECTIONS, UPGRADE_TIMEOUT_MILLIS);
            try {
                server.start();
                port = server.port();
            } catch (IOException failed) {
                YaaccLogger.w(getClass().getName(), "the player could not open its port");
                stopSelf();
                return;
            }
        }
        SendspinPlayerRuntime.set(host);
        SendspinPlayerRuntime.setPort(port);
        advert = new SendspinServerAdvert(this);
        advert.register(SendspinServiceType.TYPE, hub.clientKeys().id(), phoneName(), PATH, port, hub.clientKeys().id());
        refresh();
    }

    /** The fixed code of the static flow: drawn once per installation and kept, so that a note made of it stays right. */
    private static String staticPairingCode(SharedPreferences preferences) {
        String code = preferences.getString("sendspin_player_static_code", null);
        if (!ClientCodePairing.isStaticCode(code)) {
            code = ClientCodePairing.newStaticCode(new SecureRandom());
            preferences.edit().putString("sendspin_player_static_code", code).apply();
        }
        return code;
    }

    private static final class PreferenceStore implements SendspinPlayerHost.Store {
        private final SharedPreferences preferences;

        PreferenceStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public int volume() {
            return Math.max(0, Math.min(100, preferences.getInt("sendspin_player_volume", 70)));
        }

        @Override
        public boolean muted() {
            return preferences.getBoolean("sendspin_player_muted", false);
        }

        @Override
        public int outputDelayMs() {
            return Math.max(0, Math.min(5000, preferences.getInt("sendspin_player_delay", 0)));
        }

        @Override
        public String lastPlaybackServer() {
            return preferences.getString("sendspin_player_last_playback", null);
        }

        @Override
        public void setLastPlaybackServer(String serverId) {
            preferences.edit().putString("sendspin_player_last_playback", serverId).apply();
        }

        @Override
        public void save(int volume, boolean muted, int outputDelayMs) {
            preferences.edit().putInt("sendspin_player_volume", volume).putBoolean("sendspin_player_muted", muted)
                    .putInt("sendspin_player_delay", outputDelayMs).apply();
        }
    }

    /** The name of this phone as a player, for the card and the announcement. */
    public static String phoneName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "YAACC" : "YAACC " + model;
    }

    // ------------------------------------------------------------------ the engine of one stream

    private SendspinPlayerHost.Engine openEngine(OffsetSource offsets, MicroClock clock) {
        AudioTrackSink sink = new AudioTrackSink(clock);
        SnapcastClientEngine engine = new SnapcastClientEngine(new ClientEngineConfig().smoothCorrections(true), sink,
                new DefaultDecoderFactory(new AndroidDecoderFactory()), offsets, clock);
        final EngineHost engineHost = new EngineHost(engine, new ClientEngineConfig().getTickMs(), runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                runnable.run();
            }, "sendspin-audio");
            thread.setDaemon(true);
            return thread;
        }, new EngineHost.Observer() {
            @Override
            public void onStatus(EngineStatus status) {
                // the numbers are not shown yet
            }

            @Override
            public void onEnded(EngineStatus status) {
                // the host closes the engine when the stream ends
            }
        });
        engineHost.start();
        final SendspinAudioEngine.Target target = SendspinAudioEngine.onHost(engineHost);
        return new SendspinPlayerHost.Engine() {
            @Override
            public SendspinAudioEngine.Target target() {
                return target;
            }

            @Override
            public void close() {
                engineHost.stop();
            }
        };
    }

    // ------------------------------------------------------------------ notification and locks

    private void refresh() {
        if (destroyed) {
            return; // the sessions that end after the service ended must not post its notification again
        }
        SendspinPlayerHost current = host;
        boolean playing = current != null && current.isPlaying();
        updateLocks(playing);
        android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NotificationId.SENDSPIN_PLAYER.getId(), notification(current));
        }
    }

    private Notification notification(SendspinPlayerHost current) {
        String server = current == null ? null : current.playingServerName();
        String text = server != null ? getString(R.string.sendspin_player_notification_playing, de.yaacc.sendspin.SendspinRowText.clean(server))
                : getString(R.string.sendspin_player_notification_waiting);
        Intent stop = new Intent(this, SendspinPlayerService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, flags);
        return new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentTitle(getString(R.string.sendspin_player_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.sendspin_player_notification_stop), stopIntent)
                .build();
    }

    private void startForegroundNow() {
        Notification notification = notification(host);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationId.SENDSPIN_PLAYER.getId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NotificationId.SENDSPIN_PLAYER.getId(), notification);
        }
    }

    /** The locks are held only while a stream plays. */
    private synchronized void updateLocks(boolean playing) {
        try {
            if (playing) {
                if (wakeLock == null) {
                    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                    if (power != null) {
                        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yaacc:sendspin-player");
                        wakeLock.setReferenceCounted(false);
                        wakeLock.acquire();
                    }
                }
                if (wifiLock == null) {
                    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wifi != null) {
                        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yaacc:sendspin-player");
                        wifiLock.setReferenceCounted(false);
                        wifiLock.acquire();
                    }
                }
            } else {
                releaseLocks();
            }
        } catch (RuntimeException e) {
            YaaccLogger.d(getClass().getName(), "locks: " + e.getClass().getSimpleName());
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        SendspinServerAdvert a = advert;
        advert = null;
        if (a != null) {
            a.unregister();
        }
        InboundServer s = server;
        server = null;
        if (s != null) {
            s.close();
        }
        SendspinPlayerHost h = host;
        host = null;
        SendspinPlayerRuntime.set(null);
        SendspinPlayerRuntime.setPort(0);
        if (h != null) {
            h.close();
        }
        releaseLocks();
        super.onDestroy();
    }
}
