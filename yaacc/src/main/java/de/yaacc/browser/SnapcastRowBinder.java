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
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.ui.SnapcastControls;
import de.yaacc.multiroom.ui.SnapcastRowText;

/**
 * The Snapcast part of a receiver row (thin Android glue; the text and the decisions are in
 * {@link SnapcastRowText}). The row of a group shows two lines of text in the field that MusicCast rows use for their
 * state (stream, clients connected, volume, the server's endpoint, and the markers "server unreachable" and "stream
 * unavailable"), the volume slider and the mute box are the <b>group</b> volume and mute, and the settings button opens the
 * group sheet with the members.
 *
 * <p>{@link #show} reads the model of the server (in memory, no network) and is cheap: the row binder calls it when a row is
 * bound and on every status rebind, and the volume poll calls it every 2 s. The slider and the box obey the same rule as the
 * other rows: a value is not shown while the user touches the row ({@code ReceiverStateRefreshPlan}).
 */
final class SnapcastRowBinder {

    private final Context context;
    private final ReceiverRowBinder rows;
    private final SnapcastRowText.Words words;
    // the open group sheet, so that a second tap does not open another one
    private android.app.Dialog sheet;

    SnapcastRowBinder(Context context, ReceiverRowBinder rows) {
        this.context = context;
        this.rows = rows;
        this.words = SnapcastUi.rowWords(context);
    }

    private static String idOf(SnapcastGroupDevice device) {
        return device.getIdentity().getUdn().getIdentifierString();
    }

    private SnapcastReceivers receivers() {
        return SnapcastHub.getShared(context).getReceivers();
    }

    /** The row as it looks now; used to see whether a row needs a new look. */
    SnapcastRowText.Row rowOf(SnapcastGroupDevice device) {
        SnapcastReceivers.GroupState state = receivers().stateOf(idOf(device));
        if (state == null) {
            // the server is no longer listed: the endpoint is still shown, it is in the id (security review S20)
            return SnapcastRowText.of(null, null, false, SnapcastRowText.endpointOf(idOf(device)), words);
        }
        return SnapcastRowText.of(state.getGroup(), state.getStatus(), state.isOnline(), state.getEndpointText(), words);
    }

    /** Wires the parts of the row that belong to a group: the buttons, the group volume and mute. */
    void bind(final ReceiverRowBinder.RowViews views, final SnapcastGroupDevice device) {
        final String id = idOf(device);
        // nothing of MusicCast or OpenHome is shown for a group
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcToggle);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        if (views.yxcActions != null && views.yxcSettings != null) {
            views.yxcActions.setVisibility(View.VISIBLE);
            views.yxcSettings.setVisibility(View.VISIBLE);
            views.yxcSettings.setContentDescription(context.getString(R.string.snapcast_row_settings));
            views.yxcSettings.setOnClickListener(v -> {
                if (sheet != null && sheet.isShowing()) {
                    return; // a second tap does not open another sheet
                }
                sheet = SnapcastGroupSheet.show(context, device);
            });
        }
        for (View button : new View[]{views.btnPlay, views.btnPause, views.btnStop}) {
            button.setEnabled(true);
            button.setAlpha(1f);
        }
        final SnapcastControls controls = SnapcastUi.controls(context);
        views.volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                rows.refreshPlan().userTouchStarted(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
                SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
                if (parsed != null) {
                    controls.beginGroupGesture(parsed.getServerKey(), parsed.getGroupId());
                }
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
                views.volumeText.setText(progress + "%");
                SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
                if (parsed != null) {
                    controls.setGroupVolume(parsed.getServerKey(), parsed.getGroupId(), progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rows.refreshPlan().userTouchEnded(id, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        views.mute.setOnClickListener(v -> {
            rows.refreshPlan().userChanged(id, SystemClock.elapsedRealtime());
            SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
            if (parsed != null) {
                controls.setGroupMuted(parsed.getServerKey(), parsed.getGroupId(), views.mute.isChecked());
            }
        });
        show(views, device);
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

    /** Shows the text, the group volume and the mute state of a group in its row. Main thread. */
    void show(ReceiverRowBinder.RowViews views, SnapcastGroupDevice device) {
        SnapcastRowText.Row row = rowOf(device);
        if (views.yxcText != null) {
            // the endpoint and the stream name are the server's words: cleaned by SnapcastRowText, plain setText
            if (!row.getText().contentEquals(views.yxcText.getText())) {
                views.yxcText.setText(row.getText());
            }
            views.yxcText.setVisibility(View.VISIBLE);
        }
        String id = idOf(device);
        long now = SystemClock.elapsedRealtime();
        if (row.isControllable() && row.getVolume() >= 0) {
            rows.showVolume(views, id, now, row.getVolume());
            rows.showMute(views, id, now, row.isMuted());
        } else {
            // nothing to change on: no members that play, or a server that does not answer
            views.volume.setEnabled(false);
            views.volume.setAlpha(0.5f);
            views.mute.setEnabled(false);
            views.mute.setAlpha(0.5f);
            if (!views.volume.isPressed()) {
                // no value to show (no member plays): neither the slider nor the percentage of a recycled row stays
                views.volume.setProgress(Math.max(0, row.getVolume()));
                views.volumeText.setText(row.getVolume() >= 0 ? row.getVolume() + "%" : "");
            }
            if (views.mute.isChecked() != row.isMuted()) {
                views.mute.setChecked(row.isMuted());
            }
        }
    }

    /** For the host's own look at a group name (the title of the sheet): cleaned display text. */
    static String cleanName(String name) {
        return SnapcastDisplayText.of(name, 64).trim();
    }
}
