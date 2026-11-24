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
package de.yaacc.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.util.YaaccLogger;

/**
 * The hardware volume keys on the receivers page, best effort (decision D32): if <b>exactly one</b> player of YAACC is active (playing
 * or paused) and it plays to a remote receiver (a UPnP renderer, an OpenHome device, an FCast receiver, a Snapcast group or a Sendspin player) that can
 * be asked for its volume, the keys change that receiver's volume by {@value #STEP} percent per press and a short message shows the
 * receiver and the new volume (the system's volume bar does not appear for a key that was taken). With no active player, with more than
 * one, or when the one that is active plays on this phone, the keys are the system's, as before: a local player uses the phone's own
 * stream, which the system already handles. A Snapcast group that has the focus on the page comes first ({@link
 * de.yaacc.browser.VolumeKeyHosts}). Only players of YAACC are considered: nothing is changed on a receiver that another sender plays.
 */
public final class PlayerVolumeKeys {
    /** Percent per key press. */
    public static final int STEP = 5;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "volume-keys");
        thread.setDaemon(true);
        return thread;
    });
    private static Toast lastToast;

    private PlayerVolumeKeys() {
    }

    /** Does the player play to a receiver (and not on this phone)? */
    static boolean isRemote(Player player) {
        return player instanceof AVTransportPlayer || player instanceof OpenHomePlayer || player instanceof FCastPlayer
                || player instanceof SnapcastPlayer || player instanceof SendspinPlayer;
    }

    private static boolean isActive(Player player) {
        return player.isPlaying() || player.isPaused();
    }

    /**
     * @return the one active player if it is remote and has a volume, else null (no player, more than one, a local one: the system's keys)
     */
    public static Player target(Collection<? extends Player> players) {
        Player found = null;
        if (players == null) {
            return null;
        }
        for (Player player : players) {
            if (player != null && isActive(player)) {
                if (found != null) {
                    return null;
                }
                found = player;
            }
        }
        return found != null && isRemote(found) && found.hasActionGetVolume() ? found : null;
    }

    /** The volume in percent after a press: one step up or down, between 0 and 100. */
    public static int step(int currentPercent, boolean up) {
        return Math.max(0, Math.min(100, currentPercent + (up ? STEP : -STEP)));
    }

    /**
     * @param up true for the volume up key
     * @return true if a receiver takes the key (the volume is changed on a worker thread, the message shows afterwards); false leaves
     * the key to the system
     */
    public static boolean onKeyDown(final Context context, final boolean up, Collection<? extends Player> players) {
        final Player player = target(players);
        if (player == null) {
            return false;
        }
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                int volume = step(player.getVolume(), up);
                player.setVolume(volume);
                String text = String.format(Locale.getDefault(), "%s: %d %%", player.getShortName(), volume);
                new Handler(Looper.getMainLooper()).post(() -> show(app, text));
            } catch (RuntimeException failed) {
                YaaccLogger.w(PlayerVolumeKeys.class.getName(), "Volume key: " + failed.getClass().getSimpleName());
            }
        });
        return true;
    }

    private static void show(Context context, String text) {
        if (lastToast != null) {
            lastToast.cancel(); // the next press replaces the message instead of queueing a row of them
        }
        lastToast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        lastToast.show();
    }
}
