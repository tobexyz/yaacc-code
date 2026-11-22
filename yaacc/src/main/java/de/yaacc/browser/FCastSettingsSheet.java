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

import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.fcast.FCastReceiverLinks;
import de.yaacc.fcast.FCastSheetModel;
import de.yaacc.fcast.FCastTrackChoices;
import de.yaacc.fcast.FCastSheetTrace;
import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.discovery.FCastHub;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.fcast.protocol.FCastCapabilities;
import de.yaacc.fcast.protocol.FCastReceiverState;
import de.yaacc.fcast.protocol.FCastSession;
import de.yaacc.fcast.protocol.FCastTrack;
import de.yaacc.fcast.protocol.FCastText;
import de.yaacc.player.FCastPlayer;
import de.yaacc.player.Player;
import de.yaacc.settings.SensitiveClip;
import de.yaacc.upnp.UpnpClient;

/**
 * The settings of an FCast receiver in a bottom sheet (task 5.3). The protocol cannot read or change a receiver's own settings, so
 * the sheet shows what it can control and know: <b>device</b> (name, address, how the receiver was found, the receiver app and the
 * protocol in use, with the note that version 3 is unencrypted), <b>playback</b> (volume; mute, speed and stop where they apply),
 * <b>connection</b> (state, check, reconnect), <b>troubleshooting</b> (a bounded trace of what the sheet saw, with a copy button) and
 * <b>manage</b> (remove a receiver that was added by hand). The decisions are in {@link FCastSheetModel}; this class is the glue.
 *
 * <p>One connection per receiver: if a player plays to the receiver the sheet uses the player's session, otherwise it opens a link
 * ({@link FCastReceiverLinks}) for as long as it is shown (the connection is made when something needs it, and closed a little after
 * the sheet). Everything that touches the network runs on a worker; answers that come after the sheet was closed are dropped. The
 * texts of the receiver (its name, its app) were cleaned by the protocol layer and are shown as plain text.
 */
final class FCastSettingsSheet {

    private static final long REFRESH_MS = 1000L;
    private static final long SEND_GAP_MS = 150L;
    private static final long CHECK_TIMEOUT_MS = 8000L;

    private final Context context;
    private final UpnpClient upnpClient;
    private final FCastReceiverDevice device;
    private final FCastHub hub;
    private final FCastEndpoint endpoint;
    private final BottomSheetDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fcast-sheet");
        thread.setDaemon(true);
        return thread;
    });
    private final FCastSheetTrace trace = new FCastSheetTrace();

    private FCastReceiverLinks.Link link;
    private FCastPlayer player;

    private final TextView appValue;
    private final TextView protocolValue;
    private final TextView protocolNote;
    private final View formatsRow;
    private final View tracksSection;
    private final TextView audioValue;
    private final View audioRow;
    private final TextView subtitleValue;
    private final View subtitleRow;
    private final Button addSubtitle;
    private final TextView stateValue;
    private final TextView checkResult;
    private final TextView traceView;
    private final SeekBar volume;
    private final TextView volumeText;
    private final CheckBox mute;
    private final TextView speedValue;
    private final View speedRow;
    private final View muteRow;
    private final Button stop;
    private final Button reconnect;
    private final View removeRow;
    private final Button check;

    private long lastSend;
    private boolean shownOnce;
    private boolean checking;
    private FCastReceiverState.Link lastLink;
    private FCastReceiverState.Playback lastPlayback;
    private int lastVersion = -1;
    private int lastTrackCount = -1;
    private int lastErrorSerial = -1;
    private String lastTitle = "";

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (!dialog.isShowing()) {
                return;
            }
            refresh();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private FCastSettingsSheet(Context context, UpnpClient upnpClient, FCastReceiverDevice device) {
        this.context = context;
        this.upnpClient = upnpClient;
        this.device = device;
        this.hub = FCastHub.getShared(context);
        this.endpoint = device.getEndpoint();
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (8 * density), 0, pad);
        TextView title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setText(device.getName());
        title.setPadding(pad, (int) (4 * density), pad, (int) (8 * density));
        root.addView(title);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(context);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- device
        body.addView(section(R.string.fcast_sheet_section_device, pad, density));
        body.addView(valueRow(R.string.fcast_sheet_name, device.getName(), pad, density));
        body.addView(valueRow(R.string.fcast_sheet_address, endpoint.toString(), pad, density));
        body.addView(valueRow(R.string.fcast_sheet_source, context.getString(kindText()), pad, density));
        appValue = new TextView(context);
        body.addView(valueRow(R.string.fcast_sheet_app, appValue, pad, density));
        protocolValue = new TextView(context);
        body.addView(valueRow(R.string.fcast_sheet_protocol, protocolValue, pad, density));
        protocolNote = new TextView(context);
        protocolNote.setTextSize(12);
        protocolNote.setPadding(pad, 0, pad, (int) (4 * density));
        protocolNote.setVisibility(View.GONE);
        body.addView(protocolNote);
        TextView formatsValue = new TextView(context);
        formatsValue.setText(R.string.fcast_sheet_formats_show);
        formatsRow = valueRow(R.string.fcast_sheet_formats, formatsValue, pad, density);
        formatsRow.setClickable(true);
        formatsRow.setFocusable(true);
        formatsRow.setOnClickListener(v -> showFormats());
        formatsRow.setVisibility(View.GONE);
        body.addView(formatsRow);

        // ---- playback
        body.addView(section(R.string.fcast_sheet_section_playback, pad, density));
        LinearLayout volumeRow = new LinearLayout(context);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setPadding(pad, 0, pad, 0);
        // the mute symbol and the slider side by side, as on the cards of the other receivers (the symbol only with a player: the mute is emulated by it)
        mute = SnapcastMuteToggle.create(context, context.getString(R.string.fcast_sheet_mute));
        muteRow = mute;
        volumeRow.addView(mute);
        volume = new SeekBar(context);
        volume.setMax(100);
        volume.setContentDescription(context.getString(R.string.fcast_sheet_volume));
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volumeText = new TextView(context);
        volumeText.setMinWidth((int) (44 * density));
        volumeRow.addView(volumeText);
        body.addView(volumeRow);
        speedValue = new TextView(context);
        speedRow = valueRow(R.string.fcast_sheet_speed, speedValue, pad, density);
        speedRow.setClickable(true);
        speedRow.setFocusable(true);
        speedRow.setOnClickListener(v -> chooseSpeed());
        body.addView(speedRow);
        stop = new Button(context);
        stop.setText(R.string.fcast_sheet_stop);
        stop.setOnClickListener(v -> stopPlayback());
        body.addView(stop, buttonParams(pad));

        tracksSection = section(R.string.fcast_sheet_section_tracks, pad, density);
        tracksSection.setVisibility(View.GONE);
        body.addView(tracksSection);
        audioValue = new TextView(context);
        audioRow = valueRow(R.string.fcast_sheet_audio, audioValue, pad, density);
        audioRow.setClickable(true);
        audioRow.setFocusable(true);
        audioRow.setOnClickListener(v -> chooseTrack(FCastTrack.Type.AUDIO));
        audioRow.setVisibility(View.GONE);
        body.addView(audioRow);
        subtitleValue = new TextView(context);
        subtitleRow = valueRow(R.string.fcast_sheet_subtitles, subtitleValue, pad, density);
        subtitleRow.setClickable(true);
        subtitleRow.setFocusable(true);
        subtitleRow.setOnClickListener(v -> chooseTrack(FCastTrack.Type.SUBTITLE));
        subtitleRow.setVisibility(View.GONE);
        body.addView(subtitleRow);
        addSubtitle = new Button(context);
        addSubtitle.setText(R.string.fcast_sheet_add_subtitle);
        addSubtitle.setOnClickListener(v -> askSubtitleAddress());
        addSubtitle.setVisibility(View.GONE);
        body.addView(addSubtitle, buttonParams(pad));

        // ---- connection
        body.addView(section(R.string.fcast_sheet_section_connection, pad, density));
        stateValue = new TextView(context);
        body.addView(valueRow(R.string.fcast_sheet_state, stateValue, pad, density));
        check = new Button(context);
        check.setText(R.string.fcast_sheet_check);
        check.setOnClickListener(v -> checkConnection());
        body.addView(check, buttonParams(pad));
        checkResult = new TextView(context);
        checkResult.setPadding(pad, 0, pad, 0);
        body.addView(checkResult);
        reconnect = new Button(context);
        reconnect.setText(R.string.fcast_sheet_reconnect);
        reconnect.setOnClickListener(v -> reconnect());
        body.addView(reconnect, buttonParams(pad));

        // ---- troubleshooting
        body.addView(section(R.string.fcast_sheet_section_trouble, pad, density));
        traceView = new TextView(context);
        traceView.setTypeface(Typeface.MONOSPACE);
        traceView.setTextSize(10);
        traceView.setTextIsSelectable(true);
        traceView.setPadding(pad, 0, pad, 0);
        body.addView(traceView);
        Button copy = new Button(context);
        copy.setText(R.string.fcast_sheet_trace_copy);
        copy.setOnClickListener(v -> copyTrace());
        body.addView(copy, buttonParams(pad));
        TextView note = new TextView(context);
        note.setText(R.string.fcast_sheet_trace_note);
        note.setTextSize(12);
        note.setPadding(pad, 0, pad, 0);
        body.addView(note);

        // ---- manage
        LinearLayout manage = new LinearLayout(context);
        manage.setOrientation(LinearLayout.VERTICAL);
        manage.addView(section(R.string.fcast_sheet_section_manage, pad, density));
        Button remove = new Button(context);
        remove.setText(R.string.fcast_sheet_remove);
        remove.setOnClickListener(v -> removeReceiver());
        manage.addView(remove, buttonParams(pad));
        removeRow = manage;
        body.addView(manage);

        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    volumeText.setText(progress + "%");
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSend >= SEND_GAP_MS) {
                        lastSend = now;
                        sendVolume(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sendVolume(seekBar.getProgress()); // the last value always goes out
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        mute.setOnClickListener(v -> {
            FCastPlayer current = player;
            if (current != null) {
                current.setMute(mute.isChecked());
            }
        });
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(refreshLoop);
            worker.shutdownNow();
            if (link != null) {
                link.close();
                link = null;
            }
        });
    }

    /** Opens the sheet; null if no connection can be made for it (too many receivers are open). */
    static Dialog show(Context context, UpnpClient upnpClient, FCastReceiverDevice device) {
        FCastSettingsSheet sheet = new FCastSettingsSheet(context, upnpClient, device);
        if (!sheet.ensureSource()) {
            Toast.makeText(context, R.string.fcast_sheet_too_many, Toast.LENGTH_LONG).show();
            return null;
        }
        sheet.dialog.show();
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.refreshLoop.run();
        return sheet.dialog;
    }

    // ------------------------------------------------------------------ the source of the state

    /** The player of the receiver if there is one (its session is the receiver's one connection), else a link of the sheet. */
    private boolean ensureSource() {
        FCastPlayer found = null;
        for (Player candidate : upnpClient.getCurrentPlayers()) {
            if (candidate instanceof FCastPlayer && device.getReceiverId().equals(candidate.getDeviceId())) {
                found = (FCastPlayer) candidate;
                break;
            }
        }
        player = found;
        if (found != null) {
            if (link != null) {
                link.close();
                link = null;
            }
            return true;
        }
        if (link == null) {
            link = hub.links().open(endpoint);
        }
        return link != null;
    }

    private FCastSession session() {
        FCastPlayer current = player;
        if (current != null) {
            return current.getSession();
        }
        FCastReceiverLinks.Link current2 = link;
        return current2 == null ? null : current2.getSession();
    }

    // ------------------------------------------------------------------ showing

    private void refresh() {
        if (!ensureSource()) {
            return;
        }
        FCastSession session = session();
        if (session == null) {
            return;
        }
        FCastReceiverState state = session.getState();
        noteChanges(state);
        stateValue.setText(stateText(state));
        appValue.setText(appText(state));
        protocolValue.setText(protocolText(state));
        FCastSheetModel.Facts facts = new FCastSheetModel.Facts().manual(device.isManual())
                .connected(state.getLink() == FCastReceiverState.Link.CONNECTED);
        if (player != null) {
            facts.player(player.isPlaying() || player.isPaused());
        }
        facts.session(state.getPlayback() == FCastReceiverState.Playback.PLAYING || state.getPlayback() == FCastReceiverState.Playback.PAUSED);
        FCastSheetModel.Layout layout = FCastSheetModel.layout(facts);
        muteRow.setVisibility(layout.showsMute() ? View.VISIBLE : View.GONE);
        speedRow.setVisibility(layout.showsSpeed() ? View.VISIBLE : View.GONE);
        stop.setVisibility(layout.showsStop() ? View.VISIBLE : View.GONE);
        check.setVisibility(layout.showsCheckConnection() && player == null ? View.VISIBLE : View.GONE);
        checkResult.setVisibility(player == null && checkResult.getText().length() > 0 ? View.VISIBLE : View.GONE);
        reconnect.setVisibility(layout.showsReconnect() || (player == null && state.getLink() == FCastReceiverState.Link.FAILED) ? View.VISIBLE : View.GONE);
        removeRow.setVisibility(layout.showsRemove() ? View.VISIBLE : View.GONE);
        if (!volume.isPressed()) {
            Double reported = state.getVolume();
            if (reported != null) {
                int percent = (int) Math.round(Math.max(0.0, Math.min(1.0, reported)) * 100);
                volume.setProgress(percent);
                volumeText.setText(percent + "%");
            } else if (!shownOnce) {
                volumeText.setText("");
            }
        }
        FCastPlayer current = player;
        if (current != null) {
            mute.setChecked(current.getMute());
            SnapcastMuteToggle.syncState(context, mute);
            Double speed = state.getSpeed();
            int index = FCastSheetModel.nearestSpeed(speed == null ? 1.0 : speed);
            speedValue.setText(FCastSheetModel.speedLabels().get(Math.max(0, index)));
        }
        showTracks(state);
        formatsRow.setVisibility(state.getCapabilities() != null ? View.VISIBLE : View.GONE);
        List<String> lines = trace.lines();
        traceView.setText(lines.isEmpty() ? context.getString(R.string.fcast_sheet_trace_empty) : trace.text());
        shownOnce = true;
    }

    /** Adds what changed since the last look to the trace (the position is not traced: it changes every second). */
    private void noteChanges(FCastReceiverState state) {
        if (state.getLink() != lastLink) {
            lastLink = state.getLink();
            trace.add("link: " + state.getLink());
        }
        if (state.getProtocolVersion() != lastVersion) {
            lastVersion = state.getProtocolVersion();
            trace.add("protocol version: " + lastVersion);
        }
        if (state.getPlayback() != lastPlayback) {
            lastPlayback = state.getPlayback();
            trace.add("receiver reports: " + state.getPlayback());
        }
        String title = state.getItemTitle() == null ? "" : state.getItemTitle();
        if (!title.equals(lastTitle)) {
            lastTitle = title;
            trace.add("item: " + title);
        }
        if (state.getErrorSerial() != lastErrorSerial) {
            lastErrorSerial = state.getErrorSerial();
            if (state.getLastError() != null) {
                trace.add("error: " + state.getLastError());
            }
        }
    }

    private String stateText(FCastReceiverState state) {
        switch (state.getLink()) {
            case CONNECTING:
                return context.getString(R.string.fcast_sheet_state_connecting);
            case CONNECTED:
                return context.getString(R.string.fcast_sheet_state_connected);
            case RECONNECTING:
                return context.getString(R.string.fcast_sheet_state_reconnecting);
            case FAILED:
                return context.getString(R.string.fcast_sheet_state_failed, state.getLastError() == null ? "" : state.getLastError());
            case IDLE:
            default:
                return context.getString(R.string.fcast_sheet_state_not_connected);
        }
    }

    private String appText(FCastReceiverState state) {
        String app = state.getReceiverApp();
        if (app == null || app.isEmpty()) {
            return context.getString(R.string.fcast_sheet_not_known);
        }
        String version = state.getReceiverAppVersion();
        String text = version == null || version.isEmpty() ? app : app + " " + version;
        return FCastText.clean(text, 80);
    }

    private String protocolText(FCastReceiverState state) {
        FCastSheetModel.ProtocolText kind = FCastSheetModel.protocolText(state.getProtocolVersion(), state.getPeerVersion(), state.getPinSource(),
                android.os.Build.VERSION.SDK_INT);
        int noteRes = 0;
        String text;
        switch (kind) {
            case PLAIN_V4_NEEDS_LINK:
                noteRes = R.string.fcast_sheet_protocol_note_link;
                text = context.getString(R.string.fcast_sheet_protocol_v3);
                break;
            case PLAIN_V4_NEEDS_ANDROID:
                noteRes = R.string.fcast_sheet_protocol_note_android;
                text = context.getString(R.string.fcast_sheet_protocol_v3);
                break;
            case PLAIN:
                text = context.getString(R.string.fcast_sheet_protocol_v3);
                break;
            case SECURE_LINK:
                text = context.getString(R.string.fcast_sheet_protocol_v4_link);
                break;
            case SECURE_DISCOVERY:
                text = context.getString(R.string.fcast_sheet_protocol_v4_discovery);
                break;
            case OTHER:
                text = context.getString(R.string.fcast_sheet_protocol_other, state.getProtocolVersion());
                break;
            case UNKNOWN:
            default:
                text = context.getString(R.string.fcast_sheet_not_known);
        }
        if (noteRes != 0) {
            protocolNote.setText(noteRes);
            protocolNote.setVisibility(View.VISIBLE);
        } else {
            protocolNote.setVisibility(View.GONE);
        }
        return text;
    }

    private int kindText() {
        if (device.isManual()) {
            return R.string.fcast_receivers_kind_manual;
        }
        return device.isOffline() ? R.string.fcast_receivers_kind_remembered : R.string.fcast_receivers_kind_found;
    }

    // ------------------------------------------------------------------ actions

    private void sendVolume(int percent) {
        FCastPlayer current = player;
        if (current != null) {
            current.setVolume(percent);
            return;
        }
        FCastSession session = session();
        if (session != null) {
            session.setVolume(Math.max(0, Math.min(100, percent)) / 100.0);
        }
    }

    private void stopPlayback() {
        FCastPlayer current = player;
        if (current != null) {
            current.stop();
            return;
        }
        FCastSession session = session();
        if (session != null) {
            session.stop();
        }
    }

    private void chooseSpeed() {
        final FCastPlayer current = player;
        if (current == null) {
            return;
        }
        Double reported = current.getSession().getState().getSpeed();
        int selected = FCastSheetModel.nearestSpeed(reported == null ? 1.0 : reported);
        List<String> labels = FCastSheetModel.speedLabels();
        new AlertDialog.Builder(context)
                .setTitle(R.string.fcast_sheet_speed)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (picker, which) -> {
                    picker.dismiss();
                    current.setSpeed(FCastSheetModel.SPEEDS.get(which));
                })
                .show();
    }

    private final FCastTrackChoices.Labels trackLabels = new FCastTrackChoices.Labels() {
        @Override
        public String off() {
            return context.getString(R.string.fcast_sheet_subtitles_off);
        }

        @Override
        public String track(long id) {
            return context.getString(R.string.fcast_sheet_track_number, (int) Math.min(id, Integer.MAX_VALUE));
        }

        @Override
        public String language(String code) {
            String name = new java.util.Locale(code).getDisplayLanguage();
            return name == null || name.isEmpty() || name.equalsIgnoreCase(code) ? null : name;
        }

        @Override
        public String group(FCastTrackChoices.Group group) {
            switch (group) {
                case PROTOCOLS:
                    return context.getString(R.string.fcast_caps_protocols);
                case CONTAINERS:
                    return context.getString(R.string.fcast_caps_containers);
                case VIDEO:
                    return context.getString(R.string.fcast_caps_video);
                case AUDIO:
                    return context.getString(R.string.fcast_caps_audio);
                case SUBTITLES:
                    return context.getString(R.string.fcast_caps_subtitles);
                case HDR:
                    return context.getString(R.string.fcast_caps_hdr);
                case IMAGES:
                    return context.getString(R.string.fcast_caps_images);
                case EXTERNAL_SUBTITLES:
                    return context.getString(R.string.fcast_caps_external_subtitles);
                case SCREEN:
                default:
                    return context.getString(R.string.fcast_caps_screen);
            }
        }

        @Override
        public String yes() {
            return context.getString(R.string.fcast_caps_yes);
        }

        @Override
        public String no() {
            return context.getString(R.string.fcast_caps_no);
        }

        @Override
        public String screen(int width, int height) {
            return width + " \u00d7 " + height;
        }
    };

    /** The tracks section: audio and subtitle pickers and the button for a subtitle file (version 4, an item that plays or is paused). */
    private void showTracks(FCastReceiverState state) {
        List<FCastTrackChoices.Choice> audio = FCastTrackChoices.audio(state.getTracks(), trackLabels);
        List<FCastTrackChoices.Choice> subtitles = FCastTrackChoices.subtitles(state.getTracks(), trackLabels);
        boolean external = state.getCapabilities() != null && state.getCapabilities().supportsExternalSubtitles();
        boolean active = state.getPlayback() == FCastReceiverState.Playback.PLAYING || state.getPlayback() == FCastReceiverState.Playback.PAUSED;
        boolean show = FCastSheetModel.showsTracks(state.getProtocolVersion(), active, audio.size(), subtitles.size() - 1, external);
        tracksSection.setVisibility(show ? View.VISIBLE : View.GONE);
        audioRow.setVisibility(show && !audio.isEmpty() ? View.VISIBLE : View.GONE);
        subtitleRow.setVisibility(show && subtitles.size() > 1 ? View.VISIBLE : View.GONE);
        addSubtitle.setVisibility(show && external ? View.VISIBLE : View.GONE);
        int audioIndex = FCastTrackChoices.selectedIndex(audio, state.getSelectedAudio());
        audioValue.setText(audioIndex < 0 ? "" : audio.get(audioIndex).getLabel());
        int subtitleIndex = FCastTrackChoices.selectedIndex(subtitles, state.getSelectedSubtitle());
        subtitleValue.setText(subtitles.get(Math.max(0, subtitleIndex)).getLabel());
        if (state.getTracks().size() != lastTrackCount) {
            lastTrackCount = state.getTracks().size();
            trace.add("tracks: " + lastTrackCount);
        }
    }

    private void chooseTrack(final FCastTrack.Type type) {
        final FCastSession session = session();
        if (session == null) {
            return;
        }
        FCastReceiverState state = session.getState();
        final List<FCastTrackChoices.Choice> choices = type == FCastTrack.Type.AUDIO ? FCastTrackChoices.audio(state.getTracks(), trackLabels)
                : FCastTrackChoices.subtitles(state.getTracks(), trackLabels);
        if (choices.isEmpty()) {
            return;
        }
        Long selectedId = type == FCastTrack.Type.AUDIO ? state.getSelectedAudio() : state.getSelectedSubtitle();
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choices.get(i).getLabel();
        }
        new AlertDialog.Builder(context)
                .setTitle(type == FCastTrack.Type.AUDIO ? R.string.fcast_sheet_audio : R.string.fcast_sheet_subtitles)
                .setSingleChoiceItems(labels, FCastTrackChoices.selectedIndex(choices, selectedId), (picker, which) -> {
                    picker.dismiss();
                    session.changeTrack(type, choices.get(which).getId());
                    trace.add("track selected: " + type);
                })
                .show();
    }

    private void askSubtitleAddress() {
        final FCastSession session = session();
        if (session == null) {
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.fcast_sheet_add_subtitle_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        final AlertDialog ask = new AlertDialog.Builder(context)
                .setTitle(R.string.fcast_sheet_add_subtitle_title)
                .setView(input)
                .setPositiveButton(R.string.fcast_sheet_add_subtitle_ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        ask.setOnShowListener(d -> ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (!FCastTrackChoices.isAcceptableSubtitleUrl(text)) {
                input.setError(context.getString(R.string.fcast_sheet_add_subtitle_bad));
                return;
            }
            session.addSubtitle(text, true, null);
            trace.add("subtitle file added: " + FCastSheetTrace.shorten(text));
            ask.dismiss();
        }));
        ask.show();
    }

    private void showFormats() {
        FCastSession session = session();
        FCastCapabilities caps = session == null ? null : session.getState().getCapabilities();
        String text = caps == null ? context.getString(R.string.fcast_sheet_formats_none)
                : String.join("\n", FCastTrackChoices.capabilityLines(caps, trackLabels));
        new AlertDialog.Builder(context).setTitle(R.string.fcast_sheet_formats_title).setMessage(text)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void checkConnection() {
        if (checking) {
            return;
        }
        final FCastSession session = session();
        if (session == null) {
            return;
        }
        checking = true;
        check.setEnabled(false);
        checkResult.setText(R.string.fcast_sheet_check_running);
        checkResult.setVisibility(View.VISIBLE);
        trace.add("check connection");
        try {
            worker.execute(() -> {
                long start = SystemClock.elapsedRealtime();
                session.connect();
                FCastReceiverState state = session.getState();
                while (SystemClock.elapsedRealtime() - start < CHECK_TIMEOUT_MS) {
                    state = session.getState();
                    if ((state.getLink() == FCastReceiverState.Link.CONNECTED && state.getProtocolVersion() > 0)
                            || state.getLink() == FCastReceiverState.Link.FAILED) {
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                final boolean ok = state.getLink() == FCastReceiverState.Link.CONNECTED;
                final long millis = SystemClock.elapsedRealtime() - start;
                final String reason = state.getLastError() == null ? "" : state.getLastError();
                postIfShown(() -> {
                    checking = false;
                    check.setEnabled(true);
                    checkResult.setText(ok ? context.getString(R.string.fcast_sheet_check_ok, millis)
                            : context.getString(R.string.fcast_sheet_check_failed, reason));
                    trace.add(ok ? "check: connected in " + millis + " ms" : "check: failed");
                    refresh();
                });
            });
        } catch (RejectedExecutionException closed) {
            checking = false;
        }
    }

    private void reconnect() {
        if (player != null) {
            return;
        }
        try {
            worker.execute(() -> {
                hub.links().handOverToPlayer(endpoint); // closes the connection of the screens; a link is opened again below
                postIfShown(() -> {
                    link = null;
                    trace.add("reconnect");
                    if (ensureSource() && session() != null) {
                        session().connect();
                    }
                    refresh();
                });
            });
        } catch (RejectedExecutionException closed) {
            // the sheet was closed
        }
    }

    private void removeReceiver() {
        try {
            worker.execute(() -> {
                hub.removeReceiver(endpoint);
                postIfShown(() -> {
                    Toast.makeText(context, R.string.fcast_sheet_removed, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            });
        } catch (RejectedExecutionException closed) {
            // the sheet was closed
        }
    }

    private void copyTrace() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = trace.text();
        if (clipboard == null || text.isEmpty()) {
            return;
        }
        clipboard.setPrimaryClip(SensitiveClip.plainText("fcast trace", text));
        Toast.makeText(context, R.string.fcast_sheet_trace_copied, Toast.LENGTH_SHORT).show();
    }

    private void postIfShown(Runnable action) {
        main.post(() -> {
            if (dialog.isShowing()) {
                action.run();
            }
        });
    }

    // ------------------------------------------------------------------ views

    private View section(int titleRes, int pad, float density) {
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setPadding(pad, (int) (16 * density), pad, (int) (4 * density));
        return title;
    }

    private View valueRow(int titleRes, String value, int pad, float density) {
        TextView text = new TextView(context);
        text.setText(value); // plain text: names and addresses came from the network or the user
        return valueRow(titleRes, text, pad, density);
    }

    private View valueRow(int titleRes, TextView value, int pad, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight((int) (48 * density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, (int) (4 * density), pad, (int) (4 * density));
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(12);
        row.addView(title);
        value.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        row.addView(value);
        return row;
    }

    private static LinearLayout.LayoutParams buttonParams(int pad) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(pad, 0, pad, 0);
        return params;
    }
}
