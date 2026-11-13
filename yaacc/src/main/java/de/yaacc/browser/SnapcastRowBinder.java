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

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.ui.SnapcastControls;
import de.yaacc.multiroom.ui.SnapcastKeyFocus;
import de.yaacc.multiroom.ui.SnapcastRowText;
import de.yaacc.multiroom.ui.SnapcastVolumeKeys;

/**
 * The Snapcast part of a receiver row (thin Android glue; the text and the decisions are in
 * {@link SnapcastRowText}). The row of a group shows two lines of text in the field that MusicCast rows use for their
 * state (stream, clients connected, volume, the server's endpoint, and the markers "server unreachable" and "stream
 * unavailable"), the volume slider and the mute symbol are the <b>group</b> volume and mute (the symbol of the other cards, 11.1),
 * the link icon opens the sheet that links clients to the group and unlinks them (as on the MusicCast card, 11.3), and the
 * settings button opens the group sheet with the members' volume, mute, name and latency. The hardware volume keys go to the
 * group that has the focus or was touched last ({@link #onVolumeKey}, 11.4).
 *
 * <p>{@link #show} reads the model of the server (in memory, no network) and is cheap: the row binder calls it when a row is
 * bound and on every status rebind, and the volume poll calls it every 2 s. The slider and the symbol obey the same rule as the
 * other rows: a value is not shown while the user touches the row ({@code ReceiverStateRefreshPlan}).
 */
final class SnapcastRowBinder {

    private final Context context;
    private final ReceiverRowBinder rows;
    private final SnapcastRowText.Words words;
    // the open group sheet and link sheet, so that a second tap does not open another one
    private android.app.Dialog sheet;
    private android.app.Dialog linkSheet;
    // which group the hardware volume keys go to: the touched one, when no row has the input focus
    private final SnapcastKeyFocus keyFocus = new SnapcastKeyFocus();

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
        return SnapcastUi.rowOf(context, idOf(device), words);
    }

    /** The user did something to the row of this group (a button of the card): the volume keys go to it from now on. */
    void touched(SnapcastGroupDevice device) {
        keyFocus.touched(idOf(device));
    }

    /** Wires the parts of the row that belong to a group: the buttons, the group volume and mute. */
    void bind(final ReceiverRowBinder.RowViews views, final SnapcastGroupDevice device) {
        final String id = idOf(device);
        // nothing of MusicCast or OpenHome is shown for a group
        hide(views.yxcSource);
        hide(views.ohActions);
        hide(views.yxcPresets);
        hide(views.yxcGroupVolume);
        hide(views.yxcGroupVolumeText);
        if (views.yxcActions != null && views.yxcSettings != null) {
            views.yxcActions.setVisibility(View.VISIBLE);
            views.yxcSettings.setVisibility(View.VISIBLE);
            views.yxcSettings.setContentDescription(context.getString(R.string.snapcast_row_settings));
            views.yxcSettings.setOnClickListener(v -> {
                keyFocus.touched(id);
                if (sheet != null && sheet.isShowing()) {
                    return; // a second tap does not open another sheet
                }
                sheet = SnapcastGroupSheet.show(context, device);
            });
            if (views.yxcToggle != null) {
                // the link icon of the MusicCast card: it opens the sheet that links clients to the group and unlinks them
                views.yxcToggle.setVisibility(View.VISIBLE);
                views.yxcToggle.setContentDescription(context.getString(R.string.snapcast_row_link));
                views.yxcToggle.setOnClickListener(v -> {
                    keyFocus.touched(id);
                    if (linkSheet != null && linkSheet.isShowing()) {
                        return; // a second tap does not open another sheet
                    }
                    linkSheet = SnapcastLinkSheet.show(context, device);
                });
            }
        }
        for (View button : new View[]{views.btnPlay, views.btnPause, views.btnStop}) {
            button.setEnabled(true);
            button.setAlpha(1f);
        }
        final SnapcastControls controls = SnapcastUi.controls(context);
        views.volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                keyFocus.touched(id);
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
        // the mute is the symbol of the other cards; it is described for accessibility and says its state
        SnapcastMuteToggle.describe(context, views.mute, context.getString(R.string.snapcast_sheet_group_mute));
        views.mute.setOnClickListener(v -> {
            keyFocus.touched(id);
            SnapcastMuteToggle.syncState(context, views.mute);
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
        if (linkSheet != null && linkSheet.isShowing()) {
            linkSheet.dismiss();
        }
    }

    /** The host is no longer on screen: the touched group is forgotten, the volume keys are the system's again. */
    void forgetKeyFocus() {
        keyFocus.forget();
    }

    /**
     * A hardware volume key (11.4). The target is the Snapcast group row that has the input focus, else the one touched last,
     * and only a row that is on screen; the key is taken when that group can be controlled. The card shows the new volume (and
     * the cleared mute) at once; the server's answer follows through the model, and the grace time of the refresh plan keeps an
     * older value from taking it back in the meantime.
     *
     * @param visible the receiver rows that are laid out right now
     * @return true when the key was taken
     */
    boolean onVolumeKey(Map<Device<?, ?, ?>, ReceiverRowBinder.RowViews> visible, SnapcastVolumeKeys.Direction direction) {
        Map<String, ReceiverRowBinder.RowViews> groups = new LinkedHashMap<>();
        String focused = null;
        for (Map.Entry<Device<?, ?, ?>, ReceiverRowBinder.RowViews> row : visible.entrySet()) {
            if (row.getKey() instanceof SnapcastGroupDevice) {
                String id = idOf((SnapcastGroupDevice) row.getKey());
                groups.put(id, row.getValue());
                if (row.getValue().root != null && row.getValue().root.hasFocus()) {
                    focused = id;
                }
            }
        }
        String target = keyFocus.target(new ArrayList<>(groups.keySet()), focused);
        if (target == null) {
            return false;
        }
        SnapcastVolumeKeys.Result result = SnapcastUi.volumeKeys(context).press(target, direction);
        if (!result.isHandled()) {
            return false;
        }
        ReceiverRowBinder.RowViews views = groups.get(target);
        if (result.getVolume() >= 0 || result.isUnmuted()) {
            rows.refreshPlan().userChanged(target, SystemClock.elapsedRealtime());
        }
        if (result.getVolume() >= 0 && !views.volume.isPressed()) {
            views.volume.setProgress(result.getVolume());
            views.volumeText.setText(result.getVolume() + "%");
        }
        if (result.isUnmuted() && views.mute.isChecked()) {
            views.mute.setChecked(false);
            SnapcastMuteToggle.syncState(context, views.mute);
        }
        return true;
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
        if (views.yxcToggle != null) {
            // the link sheet needs a server that answers and a group that is still there
            SnapcastReceivers.GroupState state = receivers().stateOf(id);
            boolean linkable = state != null && state.isOnline() && state.getGroup() != null;
            views.yxcToggle.setEnabled(linkable);
            views.yxcToggle.setAlpha(linkable ? 1f : 0.4f);
        }
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
        SnapcastMuteToggle.syncState(context, views.mute);
    }

    /** For the host's own look at a group name (the title of the sheet): cleaned display text. */
    static String cleanName(String name) {
        return SnapcastDisplayText.of(name, 64).trim();
    }
}
