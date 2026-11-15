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
package de.yaacc.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.preference.PreferenceManager;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.multiroom.client.SnapcastClientRuntime;
import de.yaacc.player.SnapcastPlayer;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Process-wide background idle timer: exits the app five minutes after the last browser activity
 * stopped, unless something is still running (the rule is {@link AutoCloseRule}: the UPnP services, a
 * player, a Snapcast client session or a Snapcast feed). Browser activities call
 * {@link #browserStarted(Context)} from {@code onStart} and {@link #browserStopped(Context)} from
 * {@code onStop}; the counting rules are in {@link StartedBrowserCounter}.
 * <p>
 * Only the application context is kept. When the timer fires, the preferences and the player
 * state are read again, so a change made while the app was in the background is respected.
 */
public final class BackgroundIdleTimer {

    static final long BACKGROUND_IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static StartedBrowserCounter counter;

    private BackgroundIdleTimer() {
    }

    /** Call from {@code onStart} of a browser activity. */
    public static void browserStarted(Context context) {
        counterFor(context).onBrowserStarted();
    }

    /** Call from {@code onStop} of a browser activity. */
    public static void browserStopped(Context context) {
        counterFor(context).onBrowserStopped();
    }

    private static synchronized StartedBrowserCounter counterFor(Context context) {
        if (counter == null) {
            final Context appContext = context.getApplicationContext();
            counter = new StartedBrowserCounter(new MainLooperScheduler(),
                    BACKGROUND_IDLE_TIMEOUT_MS, () -> exitIfIdle(appContext));
        }
        return counter;
    }

    // SnapcastPlayer is marked unstable only because it extends the Media3 based local player; it is only tested by type here
    @OptIn(markerClass = UnstableApi.class)
    private static void exitIfIdle(Context appContext) {
        if (!(appContext instanceof Yaacc)) {
            return;
        }
        Yaacc yaacc = (Yaacc) appContext;
        UpnpClient client = yaacc.getUpnpClient();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        boolean playersActive = client != null && !client.getCurrentPlayers().isEmpty();
        // a player that feeds a Snapcast group is also a player; it is asked on its own so that the rule does not depend on it
        boolean feedActive = client != null && client.getCurrentPlayers().stream().anyMatch(p -> p instanceof SnapcastPlayer);
        AutoCloseRule.Facts facts = new AutoCloseRule.Facts()
                .playersActive(playersActive)
                .serverEnabled(preferences.getBoolean(appContext.getString(R.string.settings_local_server_chkbx), false))
                .rendererEnabled(preferences.getBoolean(appContext.getString(R.string.settings_local_server_receiver_chkbx), false))
                .proxyEnabled(preferences.getBoolean(appContext.getString(R.string.settings_local_server_proxy_chkbx), false))
                .snapcastClientSessionActive(SnapcastClientRuntime.isActive())
                .snapcastFeedActive(feedActive);
        AutoCloseRule.Keep keep = AutoCloseRule.reasonToStay(facts);
        if (keep == AutoCloseRule.Keep.NONE) {
            YaaccLogger.d(BackgroundIdleTimer.class.getName(), "Idle in background, exiting");
            yaacc.exit();
        } else {
            YaaccLogger.d(BackgroundIdleTimer.class.getName(), "Idle in background, staying open: " + keep);
        }
    }

    private static final class MainLooperScheduler implements StartedBrowserCounter.Scheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void schedule(Runnable task, long delayMillis) {
            handler.postDelayed(task, delayMillis);
        }

        @Override
        public void cancel(Runnable task) {
            handler.removeCallbacks(task);
        }
    }
}
