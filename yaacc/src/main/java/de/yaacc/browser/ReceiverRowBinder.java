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
