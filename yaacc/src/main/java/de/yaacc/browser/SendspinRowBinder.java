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
import android.widget.Toast;

import de.yaacc.R;
import de.yaacc.player.Player;
import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.sendspin.player.SendspinPlayerHost;
import de.yaacc.sendspin.player.SendspinPlayerRuntime;
import de.yaacc.sendspin.hub.HubConnection;
import de.yaacc.sendspin.session.ControllerMessages;
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
                    case CODE_PAIRING:
                        return context.getString(R.string.sendspin_state_code_pairing);
                    case PAIRING_REJECTED:
                        return context.getString(R.string.sendspin_state_pairing_rejected);
                    case PAIRING_FAILED:
                        return context.getString(R.string.sendspin_state_pairing_failed);
                    case READY_PAIRED:
                        return context.getString(R.string.sendspin_state_ready_paired);
                    case SERVER_CONNECTED:
                        return context.getString(R.string.sendspin_server_state_connected);
                    case SERVER_WAITING:
                        return context.getString(R.string.sendspin_server_state_waiting);
                    case SERVER_NEEDS_PAIRING:
                        return context.getString(R.string.sendspin_server_state_needs_pairing);
                    case SERVER_REFUSED:
                        return context.getString(R.string.sendspin_server_state_refused);
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

            @Override
            public String serverType() {
                return context.getString(R.string.sendspin_server_row_type);
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
        if (device.isSelf()) {
            bindSelf(views, device);
            return;
        }
        if (device.isServer()) {
            bindServer(views, device);
            return;
        }
        final String id = idOf(device);
        // nothing of MusicCast or OpenHome is shown for a Sendspin player, and there is no gear
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        showGear(views, () -> SendspinPlayerSheet.show(context, device));
        if (views.yxcToggle != null) {
            // the link icon of the MusicCast and Snapcast cards: it opens the sheet that links players into a group
            views.yxcToggle.setVisibility(View.VISIBLE);
            views.yxcToggle.setContentDescription(context.getString(R.string.sendspin_row_link));
            views.yxcToggle.setOnClickListener(v -> SendspinLinkSheet.show(context, device));
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
            SendspinHub hub = SendspinHub.getShared(context);
            if (hub.groups().isLeader(device.getReceiverId())) {
                hub.setGroupMuted(device.getReceiverId(), views.mute.isChecked());
                return;
            }
            if (connection == null || !connection.setMuted(views.mute.isChecked())) {
                views.mute.setChecked(!views.mute.isChecked()); // the player does not offer it (or is not ready): the symbol stays as it was
            }
        });
        show(views, device, upnp.playerOf(device));
    }

    private void sendVolume(SendspinReceiverDevice device, int percent) {
        SendspinHub hub = SendspinHub.getShared(context);
        if (hub.groups().isLeader(device.getReceiverId())) {
            hub.setGroupVolume(device.getReceiverId(), percent); // the leader's slider is the group's
            return;
        }
        HubConnection connection = connectionOf(device);
        if (connection != null) {
            connection.setVolume(percent);
        }
    }

    /** The gear of the card (as on the FCast and Snapcast cards): it opens the sheet of the card. */
    private void showGear(ReceiverRowBinder.RowViews views, final Runnable open) {
        if (views.yxcActions == null || views.yxcSettings == null) {
            return;
        }
        views.yxcActions.setVisibility(View.VISIBLE);
        views.yxcSettings.setVisibility(View.VISIBLE);
        views.yxcSettings.setContentDescription(context.getString(R.string.sendspin_row_settings));
        views.yxcSettings.setOnClickListener(v -> open.run());
    }

    private static void hide(View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ the card of a server (the controller)

    private ControllerSession sessionOf(SendspinReceiverDevice device) {
        return SendspinHub.getShared(context).controllers().session(device.getEndpoint());
    }

    /**
     * The card of a Sendspin server: it is not a place to play to. The play, pause and stop buttons, the volume slider and the mute
     * symbol are commands to the server for the group this phone is in; a tap on the text opens the sheet with the other commands.
     */
    private void bindServer(final ReceiverRowBinder.RowViews views, final SendspinReceiverDevice device) {
        final String id = device.getReceiverId();
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        hide(views.yxcToggle);
        showGear(views, () -> {
            ControllerSession session = sessionOf(device);
            if (session != null && session.phase() == ControllerSession.Phase.ACTIVE) {
                SendspinServerSheet.show(context, device);
            } else {
                SendspinClientPairDialog.show(context, device); // a server that is not active may be waiting for the pairing token
            }
        });
        hide(views.checkBox); // nothing is played to a server
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
                    ControllerSession session = sessionOf(device);
                    if (session != null) {
                        session.setVolume(progress);
                    }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ControllerSession session = sessionOf(device);
                if (session != null) {
                    session.setVolume(seekBar.getProgress()); // the last value always goes out
                }
                rows.refreshPlan().userTouchEnded(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        views.mute.setOnClickListener(v -> {
            ControllerSession session = sessionOf(device);
            rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
            if (session == null || !session.setMuted(views.mute.isChecked())) {
                views.mute.setChecked(!views.mute.isChecked()); // the server does not offer it: the symbol stays as it was
            }
        });
        showServer(views, device);
    }

    /** The transport buttons of a server card: commands to the server's group. */
    void controlServer(SendspinReceiverDevice device, String command) {
        SendspinHub hub = SendspinHub.getShared(context);
        ControllerSession session = hub.controllers().acquire(device.getEndpoint(), true); // a user action makes the session again
        if (session == null) {
            return;
        }
        switch (command) {
            case "play":
                session.play();
                break;
            case "pause":
                session.pause();
                break;
            default:
                session.stop();
                break;
        }
    }

    /** The names of the members of a group, for the line of its leader. */
    private String memberNames(SendspinHub hub, String leaderId) {
        StringBuilder names = new StringBuilder();
        for (String member : hub.groups().membersOf(leaderId)) {
            SendspinReceiverDevice other = hub.findReceiver(member);
            if (names.length() > 0) {
                names.append(", ");
            }
            String name = other == null ? member : SendspinRowText.clean(other.getName());
            names.append(other != null && other.isSelf() ? context.getString(R.string.sendspin_row_this_phone) : name);
        }
        return names.toString();
    }

    // ------------------------------------------------------------------ the card of this phone as a player

    private void bindSelf(final ReceiverRowBinder.RowViews views, final SendspinReceiverDevice device) {
        final String id = device.getReceiverId();
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        hide(views.yxcToggle);
        hide(views.checkBox); // nothing is played to this card: the servers play on the phone
        showGear(views, () -> SendspinSelfSheet.show(context, device));
        enable(views.btnPlay, false);
        enable(views.btnPause, false);
        enable(views.btnStop, false);
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
                SendspinPlayerHost host = SendspinPlayerRuntime.host();
                if (host != null) {
                    host.setVolumeOnPhone(progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rows.refreshPlan().userTouchEnded(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        views.mute.setOnClickListener(v -> {
            SendspinPlayerHost host = SendspinPlayerRuntime.host();
            rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
            if (host == null) {
                views.mute.setChecked(!views.mute.isChecked());
            } else {
                host.setMutedOnPhone(views.mute.isChecked());
            }
        });
        showSelf(views, device);
    }

    private void showSelf(ReceiverRowBinder.RowViews views, SendspinReceiverDevice device) {
        SendspinPlayerHost host = SendspinPlayerRuntime.host();
        String state;
        if (host == null) {
            state = context.getString(R.string.sendspin_self_state_off);
        } else {
            java.util.List<SendspinPlayerHost.SessionInfo> servers = host.sessionInfos();
            String playing = null;
            String connected = null;
            for (SendspinPlayerHost.SessionInfo info : servers) {
                String name = SendspinRowText.clean(info.serverName);
                if (info.playing) {
                    playing = name;
                } else if (connected == null) {
                    connected = name;
                }
            }
            state = playing != null ? context.getString(R.string.sendspin_self_state_playing, playing)
                    : connected != null ? context.getString(R.string.sendspin_self_state_connected, connected)
                    : context.getString(R.string.sendspin_self_state_waiting);
        }
        if (views.yxcText != null) {
            String text = device.getDisplayString() + "\n" + state;
            SendspinHub hub = SendspinHub.getShared(context);
            String leaderId = hub.groups().leaderOf(device.getReceiverId());
            if (leaderId != null) {
                SendspinReceiverDevice leader = hub.findReceiver(leaderId);
                text += "\n" + context.getString(R.string.sendspin_row_group_member,
                        leader == null ? leaderId : SendspinRowText.clean(leader.getName()));
            }
            if (!text.contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(text);
            }
            views.yxcText.setVisibility(View.VISIBLE);
            views.yxcText.setOnClickListener(null);
            views.yxcText.setClickable(false);
        }
        String id = device.getReceiverId();
        long now = SystemClock.elapsedRealtime();
        if (host != null) {
            rows.showVolume(views, id, now, host.volume());
        } else if (!views.volume.isPressed()) {
            views.volume.setProgress(0);
            views.volumeText.setText("");
        }
        views.volume.setEnabled(host != null);
        views.volume.setAlpha(host != null ? 1f : 0.5f);
        views.mute.setEnabled(host != null);
        views.mute.setAlpha(host != null ? 1f : 0.5f);
        boolean muted = host != null && host.muted();
        if (views.mute.isChecked() != muted) {
            views.mute.setChecked(muted);
        }
        SnapcastMuteToggle.syncState(context, views.mute);
    }

    /** What the album art view of a recycled row shows from here: "key#version" while it is a cover of this binder. */
    private final java.util.Map<android.widget.ImageView, String> artworkShown = new java.util.WeakHashMap<>();

    /**
     * The cover that a Sendspin server sent, as the soft background of the card (the same view and look as the cover of what a UPnP
     * player plays). The image is decoded again only when its version changes; a row that is recycled for another card is cleared by
     * the bind, so a stale entry here is harmless (it is checked against what the view really shows).
     *
     * @param key     which card and which source the cover is for
     * @param version rises when the cover changes
     */
    private void showArtwork(ReceiverRowBinder.RowViews views, String key, byte[] bytes, long version) {
        android.widget.ImageView view = views.albumArt;
        if (view == null) {
            return;
        }
        String have = artworkShown.get(view);
        if (bytes == null) {
            if (have != null && have.startsWith(key + "#")) {
                view.setImageDrawable(null);
                view.setVisibility(View.GONE);
                artworkShown.remove(view);
            }
            return;
        }
        String want = key + "#" + version;
        if (want.equals(have) && view.getDrawable() != null && view.getVisibility() == View.VISIBLE) {
            return;
        }
        android.graphics.Bitmap image = SendspinServerSheet.decode(bytes);
        if (image == null) {
            return;
        }
        view.setImageBitmap(image);
        view.setVisibility(View.VISIBLE);
        artworkShown.put(view, want);
    }

    private void showServer(ReceiverRowBinder.RowViews views, final SendspinReceiverDevice device) {
        ControllerSession session = sessionOf(device);
        showArtwork(views, "server:" + device.getReceiverId(), session == null ? null : session.artwork(),
                session == null ? 0L : session.artworkVersion());
        ControllerMessages.Group group = session == null ? null : session.group();
        SendspinRowText.State state = SendspinRowText.stateOfServer(session == null ? null : session.phase(),
                session == null ? null : session.endReason(), group != null && group.playing);
        ControllerMessages.Metadata metadata = session == null ? null : session.metadata();
        String nowPlaying = metadata == null ? "" : SendspinRowText.nowPlaying(metadata.title, metadata.artist);
        String text = SendspinRowText.ofServer(session == null ? null : session.serverName(), SendspinAddress.format(device.getEndpoint()),
                state, nowPlaying, words);
        if (views.yxcText != null) {
            if (!text.contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(text);
            }
            views.yxcText.setVisibility(View.VISIBLE);
            views.yxcText.setOnClickListener(null); // the gear opens the sheet; a recycled row must not keep the tap of another card
            views.yxcText.setClickable(false);
        }
        ControllerMessages.ControllerState controller = session == null ? null : session.controller();
        String id = device.getReceiverId();
        long now = SystemClock.elapsedRealtime();
        if (controller != null && controller.supports("volume")) {
            rows.showVolume(views, id, now, controller.volume);
        } else if (!views.volume.isPressed()) {
            views.volume.setProgress(0);
            views.volumeText.setText("");
        }
        boolean volumeWorks = controller != null && controller.supports("volume");
        views.volume.setEnabled(volumeWorks);
        views.volume.setAlpha(volumeWorks ? 1f : 0.5f);
        boolean muteWorks = controller != null && controller.supports("mute");
        views.mute.setEnabled(muteWorks);
        views.mute.setAlpha(muteWorks ? 1f : 0.5f);
        boolean muted = muteWorks && controller.muted;
        if (views.mute.isChecked() != muted) {
            views.mute.setChecked(muted);
        }
        enable(views.btnPlay, controller != null && controller.supports("play"));
        enable(views.btnPause, controller != null && controller.supports("pause"));
        enable(views.btnStop, controller != null && controller.supports("stop"));
        SnapcastMuteToggle.syncState(context, views.mute);
    }

    private static void enable(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    /** Shows the text, the volume and the mute state of the player in its row. Main thread. */
    void show(ReceiverRowBinder.RowViews views, SendspinReceiverDevice device, Player player) {
        if (device.isSelf()) {
            showSelf(views, device);
            return;
        }
        if (device.isServer()) {
            showServer(views, device);
            return;
        }
        HubConnection connection = connectionOf(device);
        boolean playing = player != null && player.isPlaying();
        boolean paused = player != null && player.isPaused();
        SendspinRowText.State state = SendspinRowText.stateOf(connection == null ? null : connection.phase(),
                connection == null ? null : connection.endReason(),
                connection == null ? HubConnection.PairingFailure.NONE : connection.pairingFailure(),
                connection != null && connection.isPaired(), playing, paused);
        if (player == null || player.getAlbumArt() == null) {
            // the cover of what the phone behind this card plays itself (its server, merged into this card), if this app plays no cover here
            SendspinHub merged = SendspinHub.getShared(context);
            de.yaacc.sendspin.hub.Endpoint remote = merged.remoteControlOf(device.getEndpoint());
            ControllerSession remoteSession = remote == null ? null : merged.controllers().session(remote);
            boolean active = remoteSession != null && remoteSession.phase() == ControllerSession.Phase.ACTIVE;
            showArtwork(views, "remote:" + device.getReceiverId(), active ? remoteSession.artwork() : null,
                    active ? remoteSession.artworkVersion() : 0L);
        }
        String title = player == null ? "" : player.getCurrentItemTitle();
        String announced = connection == null || connection.hello() == null ? null : connection.hello().name;
        String text = SendspinRowText.of(announced, SendspinAddress.format(device.getEndpoint()), state, title, words);
        SendspinHub hub = SendspinHub.getShared(context);
        String leaderId = hub.groups().leaderOf(device.getReceiverId());
        if (leaderId != null) {
            SendspinReceiverDevice leader = hub.findReceiver(leaderId);
            text += "\n" + context.getString(R.string.sendspin_row_group_member,
                    leader == null ? leaderId : SendspinRowText.clean(leader.getName()));
        } else if (hub.groups().isLeader(device.getReceiverId())) {
            text += "\n" + context.getString(R.string.sendspin_row_group_leader, memberNames(hub, device.getReceiverId()));
        }
        if (views.yxcText != null) {
            if (!text.contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(text);
            }
            views.yxcText.setVisibility(View.VISIBLE);
            // a card that needs a pairing token opens the entry of it; a recycled row must not keep the tap of another card
            if (SendspinRowText.offersPairing(state)) {
                views.yxcText.setOnClickListener(v -> SendspinPairDialog.show(context, device.getEndpoint(), announced));
            } else if (state == SendspinRowText.State.CODE_PAIRING) {
                views.yxcText.setOnClickListener(v -> SendspinCodePairDialog.show(context, device.getEndpoint(), announced));
            } else {
                views.yxcText.setOnClickListener(null);
                views.yxcText.setClickable(false);
            }
        }
        SendspinMessages.PlayerState playerState = connection == null || connection.state() == null ? null : connection.state().player;
        String id = idOf(device);
        long now = SystemClock.elapsedRealtime();
        boolean leads = hub.groups().isLeader(device.getReceiverId());
        Integer shownVolume = leads ? hub.groupVolumeOf(device.getReceiverId())
                : playerState == null ? null : playerState.volume;
        if (shownVolume != null) {
            rows.showVolume(views, id, now, shownVolume);
        } else if (!views.volume.isPressed()) {
            // nothing of the card that was in this row before stays on screen
            views.volume.setProgress(0);
            views.volumeText.setText("");
        }
        boolean muteWorks = playerState != null && playerState.supportedCommands.contains("mute");
        views.mute.setEnabled(muteWorks);
        views.mute.setAlpha(muteWorks ? 1f : 0.5f);
        boolean muted = leads ? hub.groupMutedOf(device.getReceiverId())
                : muteWorks && playerState.muted != null && playerState.muted;
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
