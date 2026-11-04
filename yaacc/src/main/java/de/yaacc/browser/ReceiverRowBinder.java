/*
 * Copyright (C) 2014 Tobias Schoene www.yaacc.de
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
package de.yaacc.browser;

import static de.yaacc.browser.RendererStatus.State.PAUSED;
import static de.yaacc.browser.RendererStatus.State.PLAYING;
import static de.yaacc.browser.RendererStatus.State.STOPPED;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.content.res.ResourcesCompat;
import androidx.media3.common.util.UnstableApi;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.avtransport.Pause;
import de.yaacc.upnp.callback.avtransport.Play;
import de.yaacc.upnp.callback.avtransport.Stop;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.util.ThemeHelper;
import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcDeviceSnapshot;
import de.yaacc.yxc.YxcDistributionInfo;
import de.yaacc.yxc.YxcGroupDirectory;
import de.yaacc.yxc.YxcGroupDrag;
import de.yaacc.yxc.YxcGroupMessages;
import de.yaacc.yxc.YxcGroupResult;
import de.yaacc.yxc.YxcGroupVolume;
import de.yaacc.yxc.YxcVolumeAccess;
import de.yaacc.yxc.YxcLinkCandidates;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Binds one {@code browse_receiver_device_item} row: selection checkbox (the Receiver tab only),
 * status badge and track text, album art, volume/mute load tasks and the
 * play/pause/stop buttons. Extracted from {@link BrowseReceiverDeviceAdapter}
 * so the Receiver tab and the device sheet bind receiver rows the same way.
 * <p>
 * Owns the per-device {@link RendererStatus} cache that live status updates
 * ({@link #updateStatus}, {@link #localDeviceStatus}) write and
 * {@link #bind} reads. When a device runs several players, the row shows
 * and controls the one {@link DeviceSessionGrouper#primarySession} picks.
 */
final class ReceiverRowBinder {
    private static final String ACTION_PLAY = "Play";
    private static final String ACTION_PAUSE = "Pause";
    private static final String ACTION_STOP = "Stop";

    /**
     * Payload of {@code notifyItemChanged(position, PAYLOAD_STATUS)}: only the status changed, so the host
     * rebinds the row with {@link #bindStatus} instead of {@link #bind}.
     */
    static final String PAYLOAD_STATUS = "status";

    /** Told after the user toggled a row's checkbox and the receiver set in {@link UpnpClient} changed. */
    interface SelectionListener {
        void onSelectionChanged(Device<?, ?, ?> device, boolean selected);
    }

    /** Views of one receiver row; the hosting adapter keeps it in its ViewHolder. */
    static final class RowViews {
        final ImageView icon;
        final ImageView albumArt;
        final TextView name;
        final TextView statusBadge;
        final TextView statusText;
        final TextView yxcText;
        final View yxcActions;
        final ImageButton yxcToggle;
        final SeekBar yxcGroupVolume;
        final TextView yxcGroupVolumeText;
        final TextView volumeText;
        final CheckBox checkBox;
        final CheckBox mute;
        final SeekBar volume;
        final ImageButton btnPlay;
        final ImageButton btnPause;
        final ImageButton btnStop;
        final View itemContainer;

        RowViews(@NonNull View itemView) {
            icon = itemView.findViewById(R.id.browseReceiverDeviceItemIcon);
            albumArt = itemView.findViewById(R.id.browseReceiverDeviceItemAlbumArt);
            name = itemView.findViewById(R.id.browseReceiverDeviceItemName);
            statusBadge = itemView.findViewById(R.id.status_badge);
            statusText = itemView.findViewById(R.id.status_text);
            yxcText = itemView.findViewById(R.id.yxc_text);
            yxcActions = itemView.findViewById(R.id.yxc_actions);
            yxcToggle = itemView.findViewById(R.id.yxc_btn_link);
            yxcGroupVolume = itemView.findViewById(R.id.yxc_group_volume);
            yxcGroupVolumeText = itemView.findViewById(R.id.yxc_group_volume_text);
            volumeText = itemView.findViewById(R.id.volume_text);
            checkBox = itemView.findViewById(R.id.browseReceiverDeviceItemCheckbox);
            mute = itemView.findViewById(R.id.browseReceiverDeviceItemMute);
            volume = itemView.findViewById(R.id.browseReceiverDeviceItemMuteVolumeSeekBar);
            btnPlay = itemView.findViewById(R.id.btn_play);
            btnPause = itemView.findViewById(R.id.btn_pause);
            btnStop = itemView.findViewById(R.id.btn_stop);
            itemContainer = itemView.findViewById(R.id.item_container);
            volume.setMax(100);
        }
    }

    private final Context context;
    private final UpnpClient upnpClient;
    private final Map<Device, RendererStatus> statusMap = new HashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    // What the user is doing to the rows and when a receiver is read next; see ReceiverVolumeLiveUpdater.
    private final ReceiverStateRefreshPlan refreshPlan = new ReceiverStateRefreshPlan();

    ReceiverRowBinder(Context context, UpnpClient upnpClient) {
        this.context = context;
        this.upnpClient = upnpClient;
    }

    /**
     * Binds a row without any selection UI: the checkbox is hidden and writes nothing. For the
     * device sheet, whose rows only control receivers and players; the receivers are picked per
     * action in the sheet's pick mode.
     */
    void bind(RowViews holder, Device<?, ?, ?> device) {
        bind(holder, device, false, null);
    }

    /**
     * @param selectionListener told after the row's checkbox wrote through to {@link UpnpClient}; null
     *                          hides the checkbox (see {@link #bind(RowViews, Device)})
     */
    void bind(RowViews holder, Device<?, ?, ?> device, boolean selected, SelectionListener selectionListener) {
        // Tag the icon with device identity to prevent wrong icon loading
        holder.icon.setTag(device.getIdentity().getUdn().getIdentifierString());

        // Check if device supports volume control
        boolean supportsVolume = supportsVolumeControl(device);
        boolean supportsMute = supportsMuteControl(device);

        // Disable controls if not supported
        holder.volume.setEnabled(supportsVolume);
        holder.mute.setEnabled(supportsMute);
        if (!supportsVolume) {
            holder.volume.setAlpha(0.5f);
            holder.volume.setProgress(50); // Show neutral position
        }
        if (!supportsMute) {
            holder.mute.setAlpha(0.5f);
            holder.mute.setEnabled(false);
        }

        // Check if there's an active player for this device
        Player player = primaryPlayer(device);

        // Load album art as background if available
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        if (player != null && player.getAlbumArt() != null) {
            holder.albumArt.setTag(deviceId); // Tag to prevent wrong image on reorder
            new IconDownloadTask(holder.albumArt, 512, 512).execute(Uri.parse(player.getAlbumArt().toString()));
        } else {
            // Clear album art for devices without active playback
            holder.albumArt.setTag(null);
            holder.albumArt.setImageDrawable(null);
            holder.albumArt.setVisibility(View.GONE);
        }

        // Show device icon
        if (device instanceof RemoteDevice && device.hasIcons()) {
            Icon[] icons = device.getIcons();
            for (Icon icon : icons) {
                if (48 == icon.getHeight() && 48 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                    URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
                    if (iconUri != null) {
                        YaaccLogger.d(getClass().getName(), "Device icon uri:" + iconUri);
                        new IconDownloadTask(holder.icon, device.getIdentity().getUdn().getIdentifierString()).execute(Uri.parse(iconUri.toString()));
                        break;
                    }
                }
            }
        } else if (isLocalDevice(device)) {
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
        } else {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_baseline_devices_48, context.getTheme()), context.getTheme()));
        }
        holder.name.setText(device.getDetails().getFriendlyName());
        if (selectionListener == null) {
            // no selection UI (the device sheet): the receiver is picked per action, not stored here
            holder.checkBox.setOnClickListener(null);
            holder.checkBox.setVisibility(View.GONE);
        } else {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setOnClickListener((it) -> {
                boolean checked = ((CheckBox) it).isChecked();
                if (!checked) {
                    YaaccLogger.d(getClass().getName(), "isNotChecked:" + device.getDisplayString());
                    upnpClient.removeReceiverDevice(device);
                } else {
                    YaaccLogger.d(getClass().getName(), "isChecked:" + device.getDisplayString());
                    upnpClient.addReceiverDevice(device);
                }
                selectionListener.onSelectionChanged(device, checked);
            });
            holder.checkBox.setChecked(selected);
        }
        wireVolumeAndMute(holder, device, deviceId);
        new DeviceVolumeStateLoadTask(holder, this, upnpClient).execute(device);
        new DeviceMuteStateLoadTask(holder, this, upnpClient).execute(device);

        // Wire up playback controls
        holder.btnPlay.setOnClickListener(v -> executeAction(device, ACTION_PLAY));
        holder.btnPause.setOnClickListener(v -> executeAction(device, ACTION_PAUSE));
        holder.btnStop.setOnClickListener(v -> executeAction(device, ACTION_STOP));

        // Set device name (always shown)
        holder.name.setText(device.getDetails().getFriendlyName());

        bindStatus(holder, device);
    }

    /**
     * Binds what a {@link RendererStatus} changes in a row: highlight, badge and track text. Volume and
     * mute are not part of it; they are loaded when the row is bound and kept current by
     * {@link ReceiverVolumeLiveUpdater}. The hosts rebind a row with this alone when only the status
     * changed ({@code notifyItemChanged(position, payload)}), so a status update neither re-reads the
     * receiver's volume and mute nor touches the slider the user may be dragging.
     */
    void bindStatus(RowViews holder, Device<?, ?, ?> device) {
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        bindGroupState(holder, deviceId);
        Player player = primaryPlayer(device);
        RendererStatus status = statusMap.get(device);
        if (status != null) {
            // Highlight if playing
            holder.itemContainer.setSelected(status.isPlaying());
            holder.statusBadge.setVisibility(View.VISIBLE);
            holder.statusBadge.setText(getStatusBadge(status.getState()));

            // Show status text with track info
            String statusText = getStatusText(status);
            if (statusText != null && !statusText.isEmpty()) {
                holder.statusText.setVisibility(View.VISIBLE);
                holder.statusText.setText(statusText);
            } else {
                holder.statusText.setVisibility(View.GONE);
            }
        } else {
            // No status from monitor - check if local device has active player
            holder.statusText.setVisibility(View.GONE);

            // For local device, create status from player
            if (deviceId.equals(UpnpClient.LOCAL_UID) && player != null) {
                RendererStatus.State state = player.isPlaying() ? RendererStatus.State.PLAYING :
                        player.isPaused() ? RendererStatus.State.PAUSED :
                                RendererStatus.State.STOPPED;
                String trackTitle = player.getCurrentItemTitle();
                RendererStatus localStatus = new RendererStatus(device, state.name(), trackTitle);

                // Add to status map so sorting works
                statusMap.put(device, localStatus);

                // Update UI
                holder.itemContainer.setSelected(localStatus.isPlaying());
                if (localStatus.isPlaying()) {
                    holder.statusBadge.setVisibility(View.VISIBLE);
                    holder.statusBadge.setText(getStatusBadge(RendererStatus.State.PLAYING));
                } else {
                    holder.statusBadge.setVisibility(View.GONE);
                }

                // Show track info
                String statusText = getStatusText(localStatus);
                if (statusText != null && !statusText.isEmpty()) {
                    holder.statusText.setVisibility(View.VISIBLE);
                    holder.statusText.setText(statusText);
                }
            } else if (player != null) {
                holder.itemContainer.setSelected(true);
                holder.statusBadge.setVisibility(View.VISIBLE);
                if (player.isPlaying()) {
                    holder.statusBadge.setText(getStatusBadge(PLAYING));
                } else if (player.isPaused()) {
                    holder.statusBadge.setText(getStatusBadge(PAUSED));
                } else {
                    holder.statusBadge.setText(getStatusBadge(STOPPED));
                }
            } else {
                holder.itemContainer.setSelected(false);
                holder.statusBadge.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Re-reads volume and mute for an already bound row.
     */
    void refreshVolumeAndMute(View rowView, Device<?, ?, ?> device) {
        RowViews views = new RowViews(rowView);
        new DeviceVolumeStateLoadTask(views, this, upnpClient).execute(device);
        new DeviceMuteStateLoadTask(views, this, upnpClient).execute(device);
    }

    ReceiverStateRefreshPlan refreshPlan() {
        return refreshPlan;
    }

    /**
     * What the user does to a row is reported to the {@link #refreshPlan} (so a value read meanwhile does
     * not take it back) and sent to the receiver. Only a change {@code fromUser} is sent: setting the
     * slider or box from a read ({@link #showVolume}, {@link #showMute}) calls the listener with
     * {@code fromUser == false} or not at all and must not write the value back.
     */
    private void wireVolumeAndMute(RowViews holder, Device<?, ?, ?> device, String deviceId) {
        holder.volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                refreshPlan.userChanged(deviceId, SystemClock.elapsedRealtime());
                holder.volumeText.setText(progress + "%");
                upnpClient.setVolume(device, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                refreshPlan.userTouchStarted(deviceId, SystemClock.elapsedRealtime());
                // Prevent ViewPager2 interception during drag
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                refreshPlan.userTouchEnded(deviceId, SystemClock.elapsedRealtime());
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        holder.mute.setOnClickListener(v -> {
            refreshPlan.userChanged(deviceId, SystemClock.elapsedRealtime());
            upnpClient.setMute(device, holder.mute.isChecked());
        });
    }

    /** Whether the row still shows the device (a load task may finish after the row was recycled). */
    static boolean isBoundTo(RowViews views, String deviceId) {
        return deviceId.equals(views.icon.getTag());
    }

    /**
     * Shows a volume that was read from the receiver (main thread). Dropped, returning false, while the
     * user is touching the row or just did ({@link ReceiverStateRefreshPlan#shouldIgnoreRead}). Setting the
     * progress here does not send anything: the listener only sends changes made by the user.
     *
     * @param readStartedAt {@link SystemClock#elapsedRealtime()} when the read was started
     */
    boolean showVolume(RowViews views, String deviceId, long readStartedAt, int percent) {
        if (views.volume.isPressed()
                || refreshPlan.shouldIgnoreRead(deviceId, readStartedAt, SystemClock.elapsedRealtime())) {
            return false;
        }
        int value = ReceiverStateRefreshPlan.clampPercent(percent);
        // Restore the controls if the first check timed out and dimmed them (see bind)
        views.volume.setEnabled(true);
        views.volume.setAlpha(1.0f);
        if (views.volume.getProgress() != value) {
            views.volume.setProgress(value);
        }
        String text = value + "%";
        if (!text.contentEquals(views.volumeText.getText())) {
            views.volumeText.setText(text);
        }
        return true;
    }

    /** Shows a mute state that was read (main thread); dropped like {@link #showVolume} while the user acts. */
    boolean showMute(RowViews views, String deviceId, long readStartedAt, boolean muted) {
        if (views.mute.isPressed()
                || refreshPlan.shouldIgnoreRead(deviceId, readStartedAt, SystemClock.elapsedRealtime())) {
            return false;
        }
        views.mute.setEnabled(true);
        views.mute.setAlpha(1.0f);
        if (views.mute.isChecked() != muted) {
            views.mute.setChecked(muted);
        }
        return true;
    }

    /**
     * Shows power, input and group of a device with group support, from {@link YxcMonitor}; hidden for every
     * other receiver (the monitor knows no state for them).
     */
    private void bindGroupState(RowViews holder, String udn) {
        if (holder.yxcText == null) {
            return;
        }
        YxcMonitor monitor = YxcMonitor.getShared();
        YxcDeviceState state = monitor.getState(udn);
        String serverName = null;
        List<String> clientNames = new ArrayList<>();
        if (state != null) {
            YxcGroupDirectory directory = new YxcGroupDirectory(monitor.snapshot());
            String server = directory.serverOf(udn);
            serverName = server == null ? null : friendlyName(server);
            for (String client : directory.clientsOf(udn)) {
                clientNames.add(friendlyName(client));
            }
        }
        YxcGroupController controller = YxcGroupController.getShared();
        boolean busy = controller.isBusy(udn);
        String text = busy ? context.getString(R.string.yxc_busy)
                : YxcRowText.describe(state, yxcWords, serverName, clientNames);
        if (text == null) {
            holder.yxcText.setVisibility(View.GONE);
        } else {
            holder.yxcText.setText(text);
            holder.yxcText.setVisibility(View.VISIBLE);
        }
        bindGroupActions(holder, udn, busy ? null : state);
    }

    /**
     * The one toggle of a device that supports groups: the link icon (always the same: the row text says whether
     * the device serves a group), disabled while it is a client of another device. Both open the picker (a server's
     * clients are checked; unchecking some removes them, unchecking all ends the group). The group is changed from the
     * server only, so a client has no way to leave on its own.
     */
    private void bindGroupActions(RowViews holder, String udn, YxcDeviceState state) {
        if (holder.yxcActions == null || holder.yxcToggle == null) {
            return;
        }
        Set<YxcLinkCandidates.Action> actions = YxcLinkCandidates.actionsFor(state);
        boolean link = actions.contains(YxcLinkCandidates.Action.LINK_DEVICES);
        boolean unlink = actions.contains(YxcLinkCandidates.Action.UNLINK_GROUP);
        boolean blocked = actions.contains(YxcLinkCandidates.Action.LINK_UNAVAILABLE);
        ImageButton toggle = holder.yxcToggle;
        holder.yxcActions.setVisibility(link || unlink || blocked ? View.VISIBLE : View.GONE);
        toggle.setContentDescription(context.getString(unlink ? R.string.yxc_action_edit_group : R.string.yxc_action_link));
        toggle.setEnabled(!blocked);
        toggle.setAlpha(blocked ? 0.4f : 1f);
        toggle.setOnClickListener(v -> {
            if (link || unlink) {
                showLinkDialog(udn); // for a server too: the clients can be unchecked one by one, all of them ends the group
            }
        });
        bindGroupVolume(holder, udn, unlink);
    }

    // The drag of each server's group volume slider, by UDN. It lives here and not in the listener: the row is rebound
    // while the finger is down, and the new listener must go on with the drag it did not see begin.
    private final Map<String, YxcGroupDrag> groupDrags = new HashMap<>();
    // Starts the reads of a drag (one at a time); the reads themselves run on the pool below.
    private final ExecutorService groupVolumeStarter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yxc-group-volume-start");
        thread.setDaemon(true);
        return thread;
    });
    // Reads the members' start levels side by side; one member that does not answer holds nobody up.
    private final ExecutorService groupVolumeReaders = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "yxc-group-volume");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The group volume of a server with clients: the slider shows the average of the members' volumes (from the states
     * the monitor keeps live); while it is dragged every member moves by the same number of points from where it was when
     * the drag began ({@link YxcGroupVolume}), each through its own interface. A little later the row is rebound so the
     * slider shows the real average again.
     */
    private void bindGroupVolume(RowViews holder, String serverUdn, boolean serves) {
        if (holder.yxcGroupVolume == null || holder.yxcGroupVolumeText == null) {
            return;
        }
        SeekBar slider = holder.yxcGroupVolume;
        TextView text = holder.yxcGroupVolumeText;
        YxcGroupDrag running = groupDrags.get(serverUdn);
        if (running != null && running.isActive()) {
            return; // a finger is on the slider: neither its position nor its listener is touched
        }
        YxcMonitor monitor = YxcMonitor.getShared();
        YxcGroupDirectory directory = serves ? new YxcGroupDirectory(monitor.snapshot()) : null;
        List<String> members = directory == null ? new ArrayList<String>() : directory.membersOf(serverUdn);
        if (members.size() < 2) {
            slider.setVisibility(View.GONE);
            text.setVisibility(View.GONE);
            slider.setOnSeekBarChangeListener(null);
            return;
        }
        YxcGroupDrag drag = running != null ? running : new YxcGroupDrag();
        groupDrags.put(serverUdn, drag); // only a device with a group has a drag
        slider.setVisibility(View.VISIBLE);
        text.setVisibility(View.VISIBLE);
        int average = YxcGroupVolume.average(YxcGroupVolume.levels(snapshotsOf(directory, members)));
        slider.setEnabled(average >= 0);
        if (average >= 0) {
            slider.setProgress(average);
            text.setText(average + "%");
        }
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
                final int dragId = drag.begin(seekBar.getProgress());
                // the start levels come from the devices (a forged event must not decide what a drag sends)
                final List<String> memberUdns = new YxcGroupDirectory(YxcMonitor.getShared().snapshot()).membersOf(serverUdn);
                final YxcVolumeAccess access = YxcVolumeAccess.getShared();
                try {
                    groupVolumeStarter.execute(() -> {
                        Map<String, Integer> read = YxcGroupVolume.readLevels(memberUdns, access::readPercent,
                                groupVolumeReaders, GROUP_VOLUME_READ_MILLIS);
                        sendGroupLevels(drag.levelsArrived(dragId, read));
                    });
                } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                    // the host is gone (see shutdown()): nothing is sent
                }
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                text.setText(progress + "%");
                sendGroupLevels(drag.moved(progress));
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                drag.end();
                // the devices report their new volumes by events; then the slider shows the real average again
                YxcGroupController.getShared().requestRebind(java.util.Collections.singleton(serverUdn), 1500);
            }
        });
    }

    private static final long GROUP_VOLUME_READ_MILLIS = 4000L;

    /** Sends each member its level; each write returns at once. {@code null}: nothing to send yet. */
    private static void sendGroupLevels(Map<String, Integer> levels) {
        if (levels == null) {
            return;
        }
        YxcVolumeAccess access = YxcVolumeAccess.getShared();
        for (Map.Entry<String, Integer> target : levels.entrySet()) {
            access.setPercent(target.getKey(), target.getValue());
        }
    }

    private static List<YxcDeviceSnapshot> snapshotsOf(YxcGroupDirectory directory, List<String> udns) {
        List<YxcDeviceSnapshot> result = new ArrayList<>();
        for (String udn : udns) {
            YxcDeviceSnapshot snapshot = directory.get(udn);
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return result;
    }

    // the open picker, so that a second tap on the button does not open another one
    private android.app.Dialog linkDialog;

    /**
     * The picker of the devices a server can take as clients, in the look of the receiver picker. Devices in another
     * group or not reachable are listed disabled with the reason; a member among them stays in the group.
     */
    private void showLinkDialog(String serverUdn) {
        if (linkDialog != null && linkDialog.isShowing()) {
            return;
        }
        YxcGroupDirectory directory = new YxcGroupDirectory(YxcMonitor.getShared().snapshot());
        List<YxcLinkCandidates.Candidate> lines = YxcLinkCandidates.forServer(serverUdn, directory);
        List<YxcLinkSheet.Row> rows = new ArrayList<>();
        for (YxcLinkCandidates.Candidate line : lines) {
            String name = displayName(directory, line.getUdn());
            String text = name;
            if (!line.isSelectable()) {
                int reason = line.getDisabledBecause() == YxcLinkCandidates.Reason.IN_OTHER_GROUP
                        ? R.string.yxc_picker_in_other_group : R.string.yxc_picker_not_reachable;
                text = context.getString(reason, name);
            }
            rows.add(new YxcLinkSheet.Row(line.getUdn(), text, line.isSelected(), line.isToggleable()));
        }
        linkDialog = YxcLinkSheet.show(context, displayName(directory, serverUdn), rows,
                selection -> YxcGroupController.getShared().applySelection(serverUdn, selection, this::showFailure));
    }

    /** A failed operation is told with one short message that names the device to blame; a success needs no message (the row changes). */
    private void showFailure(YxcGroupResult result) {
        YxcGroupMessages.Kind kind = YxcGroupMessages.kindOf(result);
        if (kind == null) {
            return;
        }
        String device = deviceNameAt(result.getDeviceIp());
        String message;
        switch (kind) {
            case NOT_POSSIBLE:
                message = context.getString(R.string.yxc_error_validate);
                break;
            case UNREACHABLE:
                message = context.getString(R.string.yxc_error_unreachable, device);
                break;
            case INPUT_NOT_RESTORED:
                message = context.getString(R.string.yxc_error_restore, device);
                break;
            default:
                message = context.getString(R.string.yxc_error_failed, device);
                break;
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** The receiver's name for an address of a watched device, else the address itself (or a dash when there is none). */
    private String deviceNameAt(String address) {
        if (address == null) {
            return "-";
        }
        for (YxcDeviceSnapshot device : YxcMonitor.getShared().snapshot()) {
            if (device.getHost().equalsIgnoreCase(address)) {
                String name = YxcRowText.clean(friendlyName(device.getUdn()));
                return name == null ? address : name;
            }
        }
        return address;
    }

    private String displayName(YxcGroupDirectory directory, String udn) {
        String name = YxcRowText.clean(friendlyName(udn));
        if (name != null) {
            return name;
        }
        YxcDeviceSnapshot snapshot = directory.get(udn);
        return snapshot == null ? udn : snapshot.getHost();
    }

    /**
     * @return the name of the server a send to this receiver would go to, when it is a linked client whose server is
     * known (the receiver picker says "plays via ..."), else {@code null}
     */
    String playsViaServerName(String udn) {
        YxcMonitor monitor = YxcMonitor.getShared();
        YxcDeviceState state = monitor.getState(udn);
        if (state == null || state.getRole() != YxcDistributionInfo.Role.CLIENT || state.isStuckClient()) {
            return null;
        }
        String server = new YxcGroupDirectory(monitor.snapshot()).serverOf(udn);
        return server == null ? null : YxcRowText.clean(friendlyName(server));
    }

    /** The name the receiver announces, {@code null} if the registry does not know it (the row then uses the generic words). */
    private String friendlyName(String udn) {
        Device<?, ?, ?> device = upnpClient.getDevice(udn);
        if (device == null || device.getDetails() == null) {
            return null;
        }
        return device.getDetails().getFriendlyName();
    }

    private final YxcRowText.Words yxcWords = new YxcRowText.Words() {
        @Override
        public String on() {
            return context.getString(R.string.yxc_state_on);
        }

        @Override
        public String standby() {
            return context.getString(R.string.yxc_state_standby);
        }

        @Override
        public String notReachable() {
            return context.getString(R.string.yxc_state_unreachable);
        }

        @Override
        public String linkedWith(String group) {
            return context.getString(R.string.yxc_group_linked_with, group);
        }

        @Override
        public String linked() {
            return context.getString(R.string.yxc_group_linked);
        }

        @Override
        public String noGroupOnLinkInput() {
            return context.getString(R.string.yxc_group_stuck);
        }

        @Override
        public String groupWith(String names) {
            return context.getString(R.string.yxc_group_with, names);
        }

        @Override
        public String groupLeader() {
            return context.getString(R.string.yxc_group_leader);
        }
    };

    /**
     * Stores a status from {@code RendererStatusMonitor} (or the local poll).
     *
     * @return true if the device's playing state changed, so the caller re-sorts
     */
    boolean updateStatus(RendererStatus status) {
        RendererStatus oldStatus = statusMap.get(status.getDevice());
        boolean wasPlaying = oldStatus != null && oldStatus.isPlaying();
        statusMap.put(status.getDevice(), status);
        return wasPlaying != status.isPlaying();
    }

    /** @return true if there was a status to clear, so the row needs a rebind */
    boolean clearStatus(Device<?, ?, ?> device) {
        return statusMap.remove(device) != null;
    }

    /**
     * Status of the device's primary player session, or null if the device
     * runs no player. Used to poll the local device, which has no
     * {@code RendererStatusMonitor} feed.
     */
    RendererStatus localDeviceStatus(Device<?, ?, ?> device) {
        Player player = primaryPlayer(device);
        if (player == null) {
            return null;
        }
        // Use UPnP state strings that will be parsed to State enum
        String state = player.isPlaying() ? "PLAYING" :
                player.isPaused() ? "PAUSED_PLAYBACK" :
                        "STOPPED";
        return new RendererStatus(device, state, player.getCurrentItemTitle());
    }

    /**
     * Opens the full player activity of the device's primary session. The
     * {@code LOCAL_UID} row does nothing on tap, as before the extraction;
     * every other device, including yaacc's own renderer (a {@code LocalDevice}
     * controlled through an {@code AVTransportPlayer}), opens its player.
     */
    void openPlayerActivity(Context context, Device<?, ?, ?> device) {
        if (UpnpClient.LOCAL_UID.equals(device.getIdentity().getUdn().getIdentifierString())) {
            return;
        }
        Player player = primaryPlayer(device);
        if (player != null) {
            openPlayer(context, player);
        }
    }

    /**
     * Opens a player's full activity the way the Player tab does
     * ({@code PlayerListItemClickListener}): by sending the player's own
     * explicit, immutable notification {@code PendingIntent}. No new intent is
     * built, so nothing outside the player decides where it goes.
     */
    @OptIn(markerClass = UnstableApi.class)
    static void openPlayer(Context context, Player player) {
        PendingIntent intent = player.getNotificationIntent();
        if (intent == null) {
            return; // No activity to open - do nothing
        }
        try {
            intent.send(context, 0, new Intent());
        } catch (PendingIntent.CanceledException e) {
            YaaccLogger.e(ReceiverRowBinder.class.getName(), "Failed to open player activity", e);
        }
    }

    /**
     * Stops the transport-action thread once queued actions have run. A host
     * that is created and destroyed repeatedly (the device sheet) must call
     * this when its view goes away; afterwards remote play/pause/stop taps are
     * ignored.
     */
    void shutdown() {
        executorService.shutdown();
        // the group volume pools too: a sheet that is created and destroyed repeatedly must not leave threads behind
        groupVolumeStarter.shutdownNow();
        groupVolumeReaders.shutdownNow();
    }

    /**
     * The player the row shows and controls. A device can run several
     * players at once (e.g. image show plus background music), and
     * {@code getCurrentPlayers()} iterates in HashMap order, so the pick goes
     * through {@link DeviceSessionGrouper#primarySession} to be deterministic.
     */
    private Player primaryPlayer(Device<?, ?, ?> device) {
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        List<PlayerRef> refs = new ArrayList<>();
        Map<Integer, Player> playersById = new HashMap<>();
        for (Player player : upnpClient.getCurrentPlayers()) {
            refs.add(new PlayerRef(player.getId(), player.getDeviceId(), player.isPlaying(),
                    upnpClient.getPlayerCreationSequence(player)));
            playersById.put(player.getId(), player);
        }
        PlayerRef primary = DeviceSessionGrouper.primarySession(deviceId, refs);
        return primary == null ? null : playersById.get(primary.playerId());
    }

    /** This phone's volume: the dummy device of the local row and yaacc's own renderer; both are {@code SystemVolume}. */
    static boolean isLocalDevice(Device<?, ?, ?> device) {
        return device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice;
    }

    private String getStatusBadge(RendererStatus.State state) {
        String result = "";
        switch (state) {
            case PLAYING:
                result = context.getString(R.string.playing);
                break;
            case PAUSED:
                result = context.getString(R.string.paused);
                break;
            case STOPPED:
                result = context.getString(R.string.stopped);
                break;
            case NO_MEDIA:
                result = context.getString(R.string.no_media);
                break;
        }
        return result;
    }

    private String getStatusText(RendererStatus status) {
        String text = "";
        if (status.getTrackTitle() != null) {
            text = status.getTrackTitle();
        }
        return text;
    }

    private void executeAction(Device<?, ?, ?> device, String action) {
        // Handle local device differently
        if (isLocalDevice(device)) {
            Player player = primaryPlayer(device);
            if (player == null) return;

            switch (action) {
                case ACTION_PLAY:
                    player.play();
                    break;
                case ACTION_PAUSE:
                    player.pause();
                    break;
                case ACTION_STOP:
                    player.stop();
                    break;
            }
            return;
        }

        if (executorService.isShutdown()) {
            return; // host is gone (see shutdown())
        }

        // Handle remote UPnP device: talk to its AVTransport service directly,
        // so playback started by another controller can be controlled too.
        org.fourthline.cling.model.meta.Service avTransport = device.findService(new org.fourthline.cling.model.types.UDAServiceId("AVTransport"));
        if (avTransport == null) return;

        HttpRequestSender httpRequestSender = new HttpRequestSender();

        switch (action) {
            case ACTION_PLAY:
                executorService.execute(new Play(avTransport, httpRequestSender) {
                    @Override
                    public void failure(org.fourthline.cling.model.action.ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse response, String msg) {
                        YaaccLogger.e(getClass().getName(), "Play failed: " + msg);
                    }
                });
                break;
            case ACTION_PAUSE:
                executorService.execute(new Pause(avTransport, httpRequestSender) {
                    @Override
                    public void failure(org.fourthline.cling.model.action.ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse response, String msg) {
                        YaaccLogger.e(getClass().getName(), "Pause failed: " + msg);
                    }
                });
                break;
            case ACTION_STOP:
                executorService.execute(new Stop(avTransport, httpRequestSender) {
                    @Override
                    public void failure(org.fourthline.cling.model.action.ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse response, String msg) {
                        YaaccLogger.e(getClass().getName(), "Stop failed: " + msg);
                    }
                });
                break;
        }
    }

    /**
     * Check if device supports volume control (GetVolume action).
     */
    boolean supportsVolumeControl(Device<?, ?, ?> device) {
        org.fourthline.cling.model.meta.Service renderingControl = device.findService(new org.fourthline.cling.model.types.UDAServiceId("RenderingControl"));
        return renderingControl != null && renderingControl.getAction("GetVolume") != null;
    }

    /**
     * Check if device supports mute control (GetMute action).
     */
    boolean supportsMuteControl(Device<?, ?, ?> device) {
        org.fourthline.cling.model.meta.Service renderingControl = device.findService(new org.fourthline.cling.model.types.UDAServiceId("RenderingControl"));
        return renderingControl != null && renderingControl.getAction("GetMute") != null;
    }
}
