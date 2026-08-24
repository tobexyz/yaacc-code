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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package de.yaacc.player;

import android.content.Context;
import android.net.Uri;
import android.support.v4.media.session.MediaSessionCompat;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * MediaSession callback that handles YouTube Music's playback requests.
 *
 * When YouTube Music can't use Cast protocol, it looks for a MediaSession
 * and delegates playback to it. This callback intercepts those requests
 * and plays the content on selected YAACC receivers.
 */
public class YaaccMediaSessionCallback extends MediaSessionCompat.Callback {
    private static final String TAG = YaaccMediaSessionCallback.class.getSimpleName();
    
    private final Context context;
    private final UpnpClient upnpClient;

    public YaaccMediaSessionCallback(Context context, UpnpClient upnpClient) {
        this.context = context;
        this.upnpClient = upnpClient;
    }

    @Override
    public void onPlay() {
        YaaccLogger.d(TAG, "onPlay: YouTube Music requested playback");
        playOnSelectedReceivers();
    }

    @Override
    public void onPause() {
        YaaccLogger.d(TAG, "onPause: YouTube Music requested pause");
        pauseOnSelectedReceivers();
    }

    @Override
    public void onStop() {
        YaaccLogger.d(TAG, "onStop: YouTube Music requested stop");
        stopOnSelectedReceivers();
    }

    @Override
    public void onSkipToNext() {
        YaaccLogger.d(TAG, "onSkipToNext: YouTube Music requested next");
        nextOnSelectedReceivers();
    }

    @Override
    public void onSkipToPrevious() {
        YaaccLogger.d(TAG, "onSkipToPrevious: YouTube Music requested previous");
        previousOnSelectedReceivers();
    }

    @Override
    public void onSeekTo(long pos) {
        YaaccLogger.d(TAG, "onSeekTo: YouTube Music requested seek to " + pos);
        seekOnSelectedReceivers(pos);
    }

    @Override
    public boolean onMediaButtonEvent(android.content.Intent mediaButtonEvent) {
        YaaccLogger.d(TAG, "onMediaButtonEvent: received media button event");
        return super.onMediaButtonEvent(mediaButtonEvent);
    }

    /**
     * Play on all currently selected receivers.
     */
    private void playOnSelectedReceivers() {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "playOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "playOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "playOnSelectedReceivers: calling play() on " + player.getName());
                    player.play();
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "playOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "playOnSelectedReceivers: " + e.getMessage());
        }
    }

    /**
     * Pause on all currently selected receivers.
     */
    private void pauseOnSelectedReceivers() {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "pauseOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "pauseOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "pauseOnSelectedReceivers: calling pause() on " + player.getName());
                    player.pause();
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "pauseOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "pauseOnSelectedReceivers: " + e.getMessage());
        }
    }

    /**
     * Stop on all currently selected receivers.
     */
    private void stopOnSelectedReceivers() {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "stopOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "stopOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "stopOnSelectedReceivers: calling stop() on " + player.getName());
                    player.stop();
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "stopOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "stopOnSelectedReceivers: " + e.getMessage());
        }
    }

    /**
     * Next track on all currently selected receivers.
     */
    private void nextOnSelectedReceivers() {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "nextOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "nextOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "nextOnSelectedReceivers: calling next() on " + player.getName());
                    player.next();
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "nextOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "nextOnSelectedReceivers: " + e.getMessage());
        }
    }

    /**
     * Previous track on all currently selected receivers.
     */
    private void previousOnSelectedReceivers() {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "previousOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "previousOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "previousOnSelectedReceivers: calling previous() on " + player.getName());
                    player.previous();
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "previousOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "previousOnSelectedReceivers: " + e.getMessage());
        }
    }

    /**
     * Seek on all currently selected receivers.
     */
    private void seekOnSelectedReceivers(long pos) {
        try {
            if (upnpClient == null) {
                YaaccLogger.w(TAG, "seekOnSelectedReceivers: upnpClient is null");
                return;
            }

            java.util.Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "seekOnSelectedReceivers: no active players");
                return;
            }

            for (Player player : players) {
                try {
                    YaaccLogger.d(TAG, "seekOnSelectedReceivers: calling seekTo to " + pos + " on " + player.getName());
                    player.seekTo(pos);
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "seekOnSelectedReceivers: error on player " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "seekOnSelectedReceivers: " + e.getMessage());
        }
    }
}
