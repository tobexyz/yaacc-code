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
import de.yaacc.player.Player;
import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.session.SendspinMessages;

/**
 * The Sendspin part of a receiver row (issue #325, first slice; thin Android glue, the text is in {@link SendspinRowText}). The
 * card shows the type "Sendspin", the name the player announced, its address and its state in words (connecting, not approved yet,
 * needs pairing, ready, playing with the title, in use by another server, not reachable ...). The play, pause and stop buttons act
 * on the player of the receiver (the row binder's transport code); the volume slider and the mute symbol send a command to the
 * player of the Sendspin device through the connection, which works without a player of this app when the connection is ready and
 * the device lists the command. There is no gear, no power button and no MusicCast or OpenHome part.
 *
 * <p>{@link #show} is cheap (memory only): the row binder calls it when a row is bound and the poll of the live volume calls it
 * every two seconds. A volume is not shown while the user touches the slider ({@link ReceiverStateRefreshPlan}).
 */
final class SendspinRowBinder {

    private static final long SEND_GAP_MS = 150L;

    private final Context context;
    private final ReceiverRowBinder rows;
    private final SendspinRowText.Words words;
    private long lastSend;

    SendspinRowBinder(Context context, ReceiverRowBinder rows) {
        this.context = context;
        this.rows = rows;
        this.words = new SendspinRowText.Words() {
            @Override
            public String type() {
                return context.getString(R.string.sendspin_row_type);
            }

            @Override
            public String state(SendspinRowText.State state) {
                switch (state) {
                    case CONNECTING:
                        return context.getString(R.string.sendspin_state_connecting);
                    case NEEDS_APPROVAL:
                        return context.getString(R.string.sendspin_state_needs_approval);
                    case PAIRING_REQUIRED:
                        return context.getString(R.string.sendspin_state_pairing_required);
                    case RE_PAIR_NEEDED:
                        return context.getString(R.string.sendspin_state_re_pair_needed);
                    case PAIRING:
                        return context.getString(R.string.sendspin_state_pairing);
                    case PAIRING_REJECTED:
                        return context.getString(R.string.sendspin_state_pairing_rejected);
                    case PAIRING_FAILED:
                        return context.getString(R.string.sendspin_state_pairing_failed);
                    case READY_PAIRED:
                        return context.getString(R.string.sendspin_state_ready_paired);
                    case UNUSABLE:
                        return context.getString(R.string.sendspin_state_unusable);
                    case READY:
                        return context.getString(R.string.sendspin_state_ready);
                    case PLAYING:
                        return context.getString(R.string.playing);
                    case PAUSED:
                        return context.getString(R.string.paused);
                    case IN_USE_BY_ANOTHER_SERVER:
                        return context.getString(R.string.sendspin_state_in_use);
                    case UNREACHABLE:
                        return context.getString(R.string.sendspin_state_unreachable);
                    case DISCONNECTED:
                        return context.getString(R.string.sendspin_state_disconnected);
                    case NOT_CONNECTED:
                    default:
                        return context.getString(R.string.sendspin_state_not_connected);
                }
            }

            @Override
            public String stateWithTitle(String state, String title) {
                return context.getString(R.string.sendspin_row_state_title, state, title);
            }
        };
    }

    private static String idOf(SendspinReceiverDevice device) {
        return device.getIdentity().getUdn().getIdentifierString();
    }

    private HubConnection connectionOf(SendspinReceiverDevice device) {
        return SendspinHub.getShared(context).connection(device.getEndpoint());
    }

    /** Wires the parts of the row that belong to a Sendspin player: the volume slider and the mute symbol. */
    void bind(final ReceiverRowBinder.RowViews views, final SendspinReceiverDevice device, final UpnpClientAccess upnp) {
        final String id = idOf(device);
        // nothing of MusicCast or OpenHome is shown for a Sendspin player, and there is no gear
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        hide(views.yxcToggle);
        hide(views.yxcActions);
        hide(views.yxcSettings);
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
                    sendVolume(device, progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sendVolume(device, seekBar.getProgress()); // the last value always goes out
                rows.refreshPlan().userTouchEnded(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        views.mute.setOnClickListener(v -> {
            HubConnection connection = connectionOf(device);
            rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
            if (connection == null || !connection.setMuted(views.mute.isChecked())) {
                views.mute.setChecked(!views.mute.isChecked()); // the player does not offer it (or is not ready): the symbol stays as it was
            }
        });
        show(views, device, upnp.playerOf(device));
    }

    private void sendVolume(SendspinReceiverDevice device, int percent) {
        HubConnection connection = connectionOf(device);
        if (connection != null) {
            connection.setVolume(percent);
        }
    }

    private static void hide(View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    /** Shows the text, the volume and the mute state of the player in its row. Main thread. */
    void show(ReceiverRowBinder.RowViews views, SendspinReceiverDevice device, Player player) {
        HubConnection connection = connectionOf(device);
        boolean playing = player != null && player.isPlaying();
        boolean paused = player != null && player.isPaused();
        SendspinRowText.State state = SendspinRowText.stateOf(connection == null ? null : connection.phase(),
                connection == null ? null : connection.endReason(),
                connection == null ? HubConnection.PairingFailure.NONE : connection.pairingFailure(),
                connection != null && connection.isPaired(), playing, paused);
        String title = player == null ? "" : player.getCurrentItemTitle();
        String announced = connection == null || connection.hello() == null ? null : connection.hello().name;
        String text = SendspinRowText.of(announced, SendspinAddress.format(device.getEndpoint()), state, title, words);
        if (views.yxcText != null) {
            if (!text.contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(text);
            }
            views.yxcText.setVisibility(View.VISIBLE);
            // a card that needs a pairing token opens the entry of it; a recycled row must not keep the tap of another card
            if (SendspinRowText.offersPairing(state)) {
                views.yxcText.setOnClickListener(v -> SendspinPairDialog.show(context, device.getEndpoint(), announced));
            } else {
                views.yxcText.setOnClickListener(null);
                views.yxcText.setClickable(false);
            }
        }
        SendspinMessages.PlayerState playerState = connection == null || connection.state() == null ? null : connection.state().player;
        String id = idOf(device);
        long now = SystemClock.elapsedRealtime();
        if (playerState != null && playerState.volume != null) {
            rows.showVolume(views, id, now, playerState.volume);
        } else if (!views.volume.isPressed()) {
            // nothing of the card that was in this row before stays on screen
            views.volume.setProgress(0);
            views.volumeText.setText("");
        }
        boolean muteWorks = playerState != null && playerState.supportedCommands.contains("mute");
        views.mute.setEnabled(muteWorks);
        views.mute.setAlpha(muteWorks ? 1f : 0.5f);
        boolean muted = muteWorks && playerState.muted != null && playerState.muted;
        if (views.mute.isChecked() != muted) {
            views.mute.setChecked(muted);
        }
        SnapcastMuteToggle.syncState(context, views.mute);
    }

    /** What the binder needs of its host to find the player of a receiver. */
    interface UpnpClientAccess {
        Player playerOf(SendspinReceiverDevice device);
    }
}
