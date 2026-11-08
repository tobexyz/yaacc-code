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
package de.yaacc.upnp.server.renderingcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.core.content.ContextCompat;

import de.yaacc.util.YaaccLogger;

/**
 * Watches the system's volume broadcasts. {@code android.media.VOLUME_CHANGED_ACTION} (volume of a stream
 * changed: hardware keys, the system panel, {@code setStreamVolume}) and
 * {@code android.media.STREAM_MUTE_CHANGED_ACTION} (mute of a stream changed) are not part of the public SDK
 * ({@code AudioManager} does not list them), but AudioService has sent them for many Android versions and many
 * apps rely on it. Nothing is trusted from the broadcast: it only says "look again", the receiver reads the real
 * value from the {@code AudioManager}. The receiver is not exported, so another app cannot trigger it, and it is
 * registered only between {@link #start} and {@link #stop}. If a device never sends them, only changes made
 * outside of the app and outside of the control point are missed.
 */
final class SystemVolumeBroadcastWatcher implements VolumeWatcher {

    static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    static final String STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION";
    static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Context context;
    private BroadcastReceiver receiver;

    SystemVolumeBroadcastWatcher(Context context) {
        this.context = context;
    }

    @Override
    public synchronized void start(Runnable onChange) {
        if (receiver != null) {
            return;
        }
        BroadcastReceiver newReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || streamIsMusic(intent)) {
                    onChange.run();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(VOLUME_CHANGED_ACTION);
        filter.addAction(STREAM_MUTE_CHANGED_ACTION);
        ContextCompat.registerReceiver(context, newReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiver = newReceiver;
    }

    @Override
    public synchronized void stop() {
        if (receiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            YaaccLogger.d(getClass().getName(), "Volume receiver was not registered", e);
        }
        receiver = null;
    }

    static boolean streamIsMusic(Intent intent) {
        // a broadcast without the stream is taken as "maybe"; reading the volume again is cheap
        return intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC) == AudioManager.STREAM_MUSIC;
    }
}
