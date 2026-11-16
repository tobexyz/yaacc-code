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

import androidx.preference.PreferenceManager;

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

    private static SnapcastClientPower create(final Context app) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(app);
        SnapcastClientPower.Store store = new SnapcastClientPower.Store() {
            @Override
            public boolean readSwitchedOn() {
                return SnapcastClientSpike.isSwitchedOn(preferences::getBoolean);
            }

            @Override
            @SuppressLint("ApplySharedPref") // commit on purpose: a tap on the switch and the exit of the app can follow each other at once
            public void writeSwitchedOn(boolean on) {
                preferences.edit().putBoolean(SnapcastClientSpike.KEY_SWITCHED_ON, on).commit();
            }

            @Override
            public boolean optionEnabled() {
                return SnapcastClientSpike.isEnabled(preferences::getBoolean);
            }

            @Override
            public String host() {
                try {
                    return preferences.getString(SnapcastClientSpike.KEY_LAST_HOST, "");
                } catch (RuntimeException damaged) {
                    return "";
                }
            }

            @Override
            public int latencyMs() {
                try {
                    return preferences.getInt(SnapcastClientSpike.KEY_LAST_LATENCY, 0);
                } catch (RuntimeException damaged) {
                    return 0;
                }
            }

            @Override
            public int writeAheadMs() {
                try {
                    return preferences.getInt(SnapcastClientSpike.KEY_LAST_WRITE_AHEAD, SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS);
                } catch (RuntimeException damaged) {
                    return SnapcastClientSpike.DEFAULT_WRITE_AHEAD_MS;
                }
            }

            @Override
            public boolean flacGuard() {
                try {
                    return preferences.getBoolean(SnapcastClientSpike.KEY_LAST_FLAC_GUARD, true);
                } catch (RuntimeException damaged) {
                    return true;
                }
            }

            @Override
            public String lastEnding() {
                try {
                    return preferences.getString(SnapcastClientSpike.KEY_LAST_ENDING, null);
                } catch (RuntimeException damaged) {
                    return null;
                }
            }

            @Override
            public boolean endingSeen() {
                try {
                    return preferences.getBoolean(SnapcastClientSpike.KEY_LAST_ENDING_SEEN, true);
                } catch (RuntimeException damaged) {
                    return true;
                }
            }

            @Override
            public void markEndingSeen() {
                if (!endingSeen()) {
                    preferences.edit().putBoolean(SnapcastClientSpike.KEY_LAST_ENDING_SEEN, true).apply();
                }
            }

            @Override
            public void recordEnding(String endingName) {
                preferences.edit().putString(SnapcastClientSpike.KEY_LAST_ENDING, endingName)
                        .putLong(SnapcastClientSpike.KEY_LAST_ENDING_AT, System.currentTimeMillis())
                        .putBoolean(SnapcastClientSpike.KEY_LAST_ENDING_SEEN, false).apply();
            }
        };
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
                SnapcastClientService.stop(app);
            }
        };
        return new SnapcastClientPower(store, session, line -> YaaccLogger.i(SnapcastClientPowers.class.getName(), line));
    }
}
