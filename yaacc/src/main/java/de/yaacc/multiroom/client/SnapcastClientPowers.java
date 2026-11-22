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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.ui.SnapcastClientServerChoice;
import de.yaacc.multiroom.ui.SnapcastServerChoices;
import de.yaacc.util.YaaccLogger;

/**
 * The {@link SnapcastClientPower} of the app: its state in the default preferences, its session the process-wide
 * {@link SnapcastClientRuntime} and the {@link SnapcastClientService}. One object per process (it counts the restore attempts of the
 * process). Thin Android glue; the decisions are in {@link SnapcastClientPower} and {@link SnapcastClientRestore}.
 */
public final class SnapcastClientPowers {

    private static SnapcastClientPower shared;

    private SnapcastClientPowers() {
    }

    public static synchronized SnapcastClientPower of(Context context) {
        if (shared == null) {
            shared = create(context.getApplicationContext());
        }
        return shared;
    }

    /**
     * The application starts the client again from its state: at the end of the startup route and when the user starts the app. Never
     * throws (the startup of the app must go on).
     */
    public static void restore(Context context, SnapcastClientRestore.Point point, boolean exitHold) {
        try {
            of(context).restore(point, exitHold);
        } catch (RuntimeException e) {
            YaaccLogger.w(SnapcastClientPowers.class.getName(), "Snapcast client restore failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The servers the client can use, as {@link SnapcastServerChoices} builds them from the server list (typed in, remembered, found)
     * and the address the listen test used last: host only, the client connects to the stream port 1704 (the limit of the list, see
     * the notes). In memory, no network. At most {@link SnapcastServerChoices#MAX_CHOICES}.
     */
    public static List<SnapcastServerChoices.Choice> knownServers(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
        try {
            return SnapcastServerChoices.of(SnapcastHub.getShared(app).newServerListModel().rows(), string(preferences, SnapcastClient.KEY_LAST_HOST));
        } catch (RuntimeException unexpected) {
            YaaccLogger.w(SnapcastClientPowers.class.getName(), "Snapcast servers not read: " + unexpected.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    /**
     * The server the client uses now: the last one it was connected to, else the first known one; null when none is known.
     */
    public static SnapcastServerChoices.Choice currentServer(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return SnapcastClientServerChoice.resolve(string(preferences, SnapcastClient.KEY_LAST_SERVER), knownServers(context));
    }

    /**
     * The position of the current server in {@code known} (to mark it in the list); -1 when the list is empty.
     */
    public static int currentServerIndex(Context context, List<SnapcastServerChoices.Choice> known) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return SnapcastClientServerChoice.currentIndex(string(preferences, SnapcastClient.KEY_LAST_SERVER), known);
    }

    private static String string(SharedPreferences preferences, String key) {
        try {
            return preferences.getString(key, "");
        } catch (RuntimeException damaged) {
            return "";
        }
    }

    private static SnapcastClientPower create(final Context app) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
        SnapcastClientPower.Store store = new SnapcastClientPower.Store() {
            @Override
            public boolean readSwitchedOn() {
                return SnapcastClient.isSwitchedOn(preferences::getBoolean);
            }

            @Override
            @SuppressLint("ApplySharedPref")
            // commit on purpose: a tap on the switch and the exit of the app can follow each other at once
            public void writeSwitchedOn(boolean on) {
                preferences.edit().putBoolean(SnapcastClient.KEY_SWITCHED_ON, on).commit();
            }

            @Override
            public boolean optionEnabled() {
                return SnapcastClient.isEnabled(preferences::getBoolean);
            }

            @Override
            public String host() {
                // the server list decides, not the address that the listen test saved (task 12.10); every start checks the text again
                SnapcastServerChoices.Choice chosen = currentServer(app);
                return chosen == null ? "" : chosen.hostText();
            }

            @Override
            public void writeLastServer(String hostText) {
                preferences.edit().putString(SnapcastClient.KEY_LAST_SERVER, hostText).apply();
            }

            @Override
            public int latencyMs() {
                try {
                    return preferences.getInt(SnapcastClient.KEY_LAST_LATENCY, 0);
                } catch (RuntimeException damaged) {
                    return 0;
                }
            }

            @Override
            public int writeAheadMs() {
                try {
                    return preferences.getInt(SnapcastClient.KEY_LAST_WRITE_AHEAD, SnapcastClient.DEFAULT_WRITE_AHEAD_MS);
                } catch (RuntimeException damaged) {
                    return SnapcastClient.DEFAULT_WRITE_AHEAD_MS;
                }
            }

            @Override
            public boolean flacGuard() {
                try {
                    return preferences.getBoolean(SnapcastClient.KEY_LAST_FLAC_GUARD, true);
                } catch (RuntimeException damaged) {
                    return true;
                }
            }

            @Override
            public String lastEnding() {
                try {
                    return preferences.getString(SnapcastClient.KEY_LAST_ENDING, null);
                } catch (RuntimeException damaged) {
                    return null;
                }
            }

            @Override
            public boolean endingSeen() {
                try {
                    return preferences.getBoolean(SnapcastClient.KEY_LAST_ENDING_SEEN, true);
                } catch (RuntimeException damaged) {
                    return true;
                }
            }

            @Override
            public void markEndingSeen() {
                if (!endingSeen()) {
                    preferences.edit().putBoolean(SnapcastClient.KEY_LAST_ENDING_SEEN, true).apply();
                }
            }

            @Override
            public void recordEnding(String endingName) {
                preferences.edit().putString(SnapcastClient.KEY_LAST_ENDING, endingName)
                        .putLong(SnapcastClient.KEY_LAST_ENDING_AT, System.currentTimeMillis())
                        .putBoolean(SnapcastClient.KEY_LAST_ENDING_SEEN, false).apply();
            }
        };
        final Restarter restarter = new Restarter(app);
        SnapcastClientPower.Session session = new SnapcastClientPower.Session() {
            @Override
            public boolean isActive() {
                return SnapcastClientRuntime.isActive();
            }

            @Override
            public SnapcastClientRuntime.Phase phase() {
                return SnapcastClientRuntime.get().phase;
            }

            @Override
            public void start(String host, int latencyMs, int writeAheadMs, boolean flacGuard) {
                SnapcastClientService.start(app, host, latencyMs, writeAheadMs, flacGuard);
            }

            @Override
            public void stop() {
                restarter.cancel(); // a switch off ends a restart that still waits
                SnapcastClientService.stop(app);
            }

            @Override
            public boolean restartPending() {
                return restarter.isPending();
            }

            @Override
            public void restart(String host, int latencyMs, int writeAheadMs, boolean flacGuard, Consumer<RuntimeException> onFailure) {
                restarter.restart(new Restarter.Request(host, latencyMs, writeAheadMs, flacGuard, onFailure));
            }
        };
        return new SnapcastClientPower(store, session, line -> YaaccLogger.i(SnapcastClientPowers.class.getName(), line));
    }

    /**
     * Ends the session of the client and starts it again on another server: stop, wait until the session AND the service instance are
     * gone (the service answers a start with "busy" while it still tears the old session down), then start. The waiting is a poll on
     * the main thread, bounded ({@link SnapcastClientServerSwitch#waitStep}); a second request while it waits replaces the target
     * (one start in the end); {@link #cancel} removes it. Thin Android glue.
     */
    private static final class Restarter {
        static final long POLL_MS = 200L;
        static final long MAX_WAIT_MS = 8_000L;

        static final class Request {
            final String host;
            final int latencyMs;
            final int writeAheadMs;
            final boolean flacGuard;
            final Consumer<RuntimeException> onFailure;
            final long requestedAt = SystemClock.elapsedRealtime();

            Request(String host, int latencyMs, int writeAheadMs, boolean flacGuard, Consumer<RuntimeException> onFailure) {
                this.host = host;
                this.latencyMs = latencyMs;
                this.writeAheadMs = writeAheadMs;
                this.flacGuard = flacGuard;
                this.onFailure = onFailure;
            }
        }

        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicReference<Request> pending = new AtomicReference<>();
        private final Runnable poll = this::step;

        Restarter(Context app) {
            this.app = app;
        }

        boolean isPending() {
            return pending.get() != null;
        }

        void restart(Request request) {
            Request before = pending.getAndSet(request);
            if (before == null) {
                SnapcastClientService.stop(app); // the old session ends; the poll starts the new one
                main.postDelayed(poll, POLL_MS);
            }
        }

        void cancel() {
            pending.set(null);
            main.removeCallbacks(poll);
        }

        private void step() {
            Request request = pending.get();
            if (request == null) {
                return;
            }
            boolean gone = !SnapcastClientRuntime.isActive() && !SnapcastClientRuntime.isServiceAlive();
            switch (SnapcastClientServerSwitch.waitStep(gone, SystemClock.elapsedRealtime() - request.requestedAt, MAX_WAIT_MS)) {
                case START:
                    if (pending.compareAndSet(request, null)) {
                        try {
                            SnapcastClientService.start(app, request.host, request.latencyMs, request.writeAheadMs, request.flacGuard);
                        } catch (RuntimeException refused) {
                            request.onFailure.accept(refused);
                        }
                    } else {
                        main.post(poll); // replaced meanwhile: look at the newer request
                    }
                    break;
                case GIVE_UP:
                    if (pending.compareAndSet(request, null)) {
                        YaaccLogger.w(SnapcastClientPowers.class.getName(), "Snapcast client restart gave up waiting for the old session");
                    }
                    break;
                case WAIT:
                default:
                    main.postDelayed(poll, POLL_MS);
                    break;
            }
        }
    }
}
