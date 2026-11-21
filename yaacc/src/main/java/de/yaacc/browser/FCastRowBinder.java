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
package de.yaacc.browser;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;

import de.yaacc.R;
import de.yaacc.fcast.FCastReceiverLinks;
import de.yaacc.fcast.FCastRowText;
import de.yaacc.fcast.discovery.FCastHub;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.player.FCastPlayer;
import de.yaacc.player.Player;

/**
 * The FCast part of a receiver row (task 5.2, thin Android glue; the text is in {@link FCastRowText}). The card shows the type
 * "FCast", the address and the state (not reachable, idle, playing, paused, with the title). The play, pause and stop buttons act on the
 * player of the receiver; the volume slider goes to the player, or, when there is none, to a short-lived session that is opened for
 * it (an idle card has no connection, D11); the mute symbol works with a player only (the mute is emulated by the player, which
 * remembers the old volume). The gear opens {@link FCastSettingsSheet}. There is no power button and no MusicCast or OpenHome part.
 *
 * <p>{@link #show} is cheap (no network): the row binder calls it when a row is bound and the poll of the live volume calls it every
 * two seconds. A volume is not shown while the user touches the slider ({@link ReceiverStateRefreshPlan}, as for the other rows).
 */
final class FCastRowBinder {

    private static final long SEND_GAP_MS = 150L;

    private final Context context;
    private final ReceiverRowBinder rows;
    private final FCastRowText.Words words;
    private android.app.Dialog sheet;
    private long lastSend;

    FCastRowBinder(Context context, ReceiverRowBinder rows) {
        this.context = context;
        this.rows = rows;
        this.words = new FCastRowText.Words() {
            @Override
            public String type() {
                return context.getString(R.string.fcast_row_type);
            }

            @Override
            public String notReachable() {
                return context.getString(R.string.fcast_row_not_reachable);
            }

            @Override
            public String idle() {
                return context.getString(R.string.fcast_row_idle);
            }

            @Override
            public String playing() {
                return context.getString(R.string.playing);
            }

            @Override
            public String paused() {
                return context.getString(R.string.paused);
            }

            @Override
            public String stateWithTitle(String state, String title) {
                return title.isEmpty() ? state : context.getString(R.string.fcast_row_state_title, state, title);
            }
        };
    }

    private static String idOf(FCastReceiverDevice device) {
        return device.getIdentity().getUdn().getIdentifierString();
    }

    /** The facts of the card as they are now: from the player if there is one, else from an open session, else unknown. */
    FCastRowText.Row rowOf(FCastReceiverDevice device, Player player) {
        FCastRowText.Facts facts = new FCastRowText.Facts().endpoint(device.getEndpoint().toString());
        if (player instanceof FCastPlayer) {
            FCastPlayer fcast = (FCastPlayer) player;
            facts.player(fcast.isPlaying(), fcast.isPaused(), fcast.getCurrentItemTitle());
            facts.volume(fcast.getVolume(), fcast.getMute());
            return FCastRowText.of(facts, words);
        }
        FCastSession session = FCastHub.getShared(context).links().peek(device.getEndpoint());
        if (session != null) {
            FCastReceiverState state = session.getState();
            FCastReceiverState.Playback playback = state.getPlayback();
            facts.session(state.getLink() == FCastReceiverState.Link.FAILED,
                    playback != FCastReceiverState.Playback.UNKNOWN,
                    playback == FCastReceiverState.Playback.PLAYING, playback == FCastReceiverState.Playback.PAUSED, state.getItemTitle());
            Double volume = state.getVolume();
            if (volume != null) {
                facts.volume((int) Math.round(Math.max(0.0, Math.min(1.0, volume)) * 100), false);
            }
        }
        return FCastRowText.of(facts, words);
    }

    /** Wires the parts of the row that belong to an FCast receiver: the gear, the volume slider and the mute symbol. */
    void bind(final ReceiverRowBinder.RowViews views, final FCastReceiverDevice device, final UpnpClientAccess upnp) {
        final String id = idOf(device);
        // nothing of MusicCast or OpenHome is shown for an FCast receiver
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        hide(views.yxcToggle);
        if (views.yxcActions != null && views.yxcSettings != null) {
            views.yxcActions.setVisibility(View.VISIBLE);
            views.yxcSettings.setVisibility(View.VISIBLE);
            views.yxcSettings.setContentDescription(context.getString(R.string.fcast_row_settings));
            views.yxcSettings.setOnClickListener(v -> {
                if (sheet != null && sheet.isShowing()) {
                    return; // a second tap does not open another sheet
                }
                sheet = FCastSettingsSheet.show(context, upnp.client(), device);
            });
        }
        for (View button : new View[]{views.btnPlay, views.btnPause, views.btnStop}) {
            button.setEnabled(true);
            button.setAlpha(1f);
        }
        views.volume.setEnabled(true);
        views.volume.setAlpha(1f);
        views.volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                rows.refreshPlan().userTouchStarted(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
                views.volumeText.setText(progress + "%");
                long now = SystemClock.elapsedRealtime();
                if (now - lastSend >= SEND_GAP_MS) {
                    lastSend = now;
                    sendVolume(device, upnp.playerOf(device), progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sendVolume(device, upnp.playerOf(device), seekBar.getProgress()); // the last value always goes out
                rows.refreshPlan().userTouchEnded(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        views.mute.setOnClickListener(v -> {
            Player player = upnp.playerOf(device);
            if (player instanceof FCastPlayer) {
                rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
                player.setMute(views.mute.isChecked());
            }
        });
        show(views, device, upnp.playerOf(device));
    }

    /** The volume goes to the player, or through a link that is opened for it and given back at once (the session closes after a grace time). */
    private void sendVolume(FCastReceiverDevice device, Player player, int percent) {
        if (player instanceof FCastPlayer) {
            player.setVolume(percent);
            return;
        }
        FCastReceiverLinks.Link link = FCastHub.getShared(context).links().open(device.getEndpoint());
        if (link == null) {
            return; // too many receivers are open at once
        }
        try {
            link.getSession().setVolume(Math.max(0, Math.min(100, percent)) / 100.0);
        } finally {
            link.close();
        }
    }

    /** The host goes away: an open sheet would keep the activity alive. */
    void shutdown() {
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
        }
    }

    private static void hide(View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    /** Shows the text, the volume and the mute state of the receiver in its row. Main thread. */
    void show(ReceiverRowBinder.RowViews views, FCastReceiverDevice device, Player player) {
        FCastRowText.Row row = rowOf(device, player);
        if (views.yxcText != null) {
            // the name and the title are the receiver's words: cleaned by FCastRowText, plain setText
            if (!row.getText().contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(row.getText());
            }
            views.yxcText.setVisibility(View.VISIBLE);
        }
        String id = idOf(device);
        long now = SystemClock.elapsedRealtime();
        if (row.getVolume() >= 0) {
            rows.showVolume(views, id, now, row.getVolume());
        } else if (!views.volume.isPressed()) {
            // an idle card has no connection and no value: nothing of the card that was in this row before stays on screen
            views.volume.setProgress(0);
            views.volumeText.setText("");
        }
        boolean muteWorks = player instanceof FCastPlayer;
        views.mute.setEnabled(muteWorks);
        views.mute.setAlpha(muteWorks ? 1f : 0.5f);
        boolean muted = muteWorks && row.isMuted();
        if (views.mute.isChecked() != muted) {
            views.mute.setChecked(muted);
        }
        SnapcastMuteToggle.syncState(context, views.mute);
    }

    /** What the binder needs of its host to find the player of a receiver and to open the sheet. */
    interface UpnpClientAccess {
        de.yaacc.upnp.UpnpClient client();

        Player playerOf(FCastReceiverDevice device);
    }
}
