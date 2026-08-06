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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.player.AVTransportPlayer;
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
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseReceiverDeviceAdapter extends RecyclerView.Adapter<BrowseReceiverDeviceAdapter.ViewHolder> {
    private static final String ACTION_PLAY = "Play";
    private static final String ACTION_PAUSE = "Pause";
    private static final String ACTION_STOP = "Stop";

    private final List<Device<?, ?, ?>> selectedDevices;
    private final Context context;
    private List<Device<?, ?, ?>> devices;
    private UpnpClient upnpClient;
    private RecyclerView devicesListView;
    private final Map<Device, RendererStatus> statusMap = new HashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();


    public BrowseReceiverDeviceAdapter(Context ctx, UpnpClient upnpClient, RecyclerView devicesListView, Collection<Device<?, ?, ?>> devices, Collection<Device<?, ?, ?>> selectedDevices) {
        super();
        this.devices = new ArrayList<>(devices);
        this.selectedDevices = new LinkedList<>(selectedDevices);
        context = ctx;
        this.upnpClient = upnpClient;
        this.devicesListView = devicesListView;
        sortDevices();
    }

    private void sortDevices() {
        devices.sort((d1, d2) -> {
            // 1. Local device first
            boolean d1Local = d1.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID);
            boolean d2Local = d2.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID);

            if (d1Local != d2Local) {
                return d1Local ? -1 : 1;
            }

            // 2. Then by name
            return d1.getDetails().getFriendlyName().compareTo(d2.getDetails().getFriendlyName());
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }


    public Device<?, ?, ?> getItem(int position) {
        return devices.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public BrowseReceiverDeviceAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                                     int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.browse_receiver_device_item, parent, false);

        // Add click listener to open player activity
        view.setOnClickListener(v -> {
            int position = devicesListView.getChildAdapterPosition(v);
            if (position != RecyclerView.NO_POSITION) {
                Device device = getItem(position);
                openPlayerActivity(v.getContext(), device);
            }
        });

        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;

            int position = devicesListView.getChildAdapterPosition(v);
            if (position == RecyclerView.NO_POSITION) return false;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    // Trigger normal click
                    v.performClick();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Focus first visible action button
                    BrowseReceiverDeviceAdapter.ViewHolder holder = (BrowseReceiverDeviceAdapter.ViewHolder) devicesListView.getChildViewHolder(v);
                    holder.checkBox.requestFocus();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    // Let parent handle; if we are on first column maybe switch tabs later
                    return false;
            }
            return false;
        });
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final BrowseReceiverDeviceAdapter.ViewHolder holder, final int listPosition) {
        Device<?, ?, ?> device = getItem(listPosition);

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
        Player player = getPlayerForDevice(device);

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
        } else if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
        } else {
            holder.icon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_devices_48, context.getTheme()), context.getTheme()));
        }
        holder.name.setText(device.getDetails().getFriendlyName());
        holder.checkBox.setOnClickListener((it) -> {
            if (!((CheckBox) it).isChecked()) {
                YaaccLogger.d(getClass().getName(), "isNotChecked:" + device.getDisplayString());
                removeSelectedDevice(device);
                upnpClient.removeReceiverDevice(device);
            } else {
                YaaccLogger.d(getClass().getName(), "isChecked:" + device.getDisplayString());
                addSelectedDevice(device);
                upnpClient.addReceiverDevice(device);
            }
            // Re-sort after selection change
            sortDevices();
            notifyDataSetChanged();
        });
        holder.checkBox.setChecked(selectedDevices.contains(device));
        new DeviceVolumeStateLoadTask(holder.volume, upnpClient).execute(device);
        new DeviceMuteStateLoadTask(holder.mute, upnpClient).execute(device);

        // Wire up playback controls
        wireUpControls(holder, device);

        // Set device name (always shown)
        holder.name.setText(device.getDetails().getFriendlyName());

        // Update status display
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

            // Update volume only if changed
            int newVolume = status.getVolume();
            if (holder.volume.getProgress() != newVolume) {
                holder.volume.setProgress(newVolume);
                holder.volumeText.setText(newVolume + "%");
            }
        } else {
            // No status from monitor - check if local device has active player
            holder.statusText.setVisibility(View.GONE);

            // For local device, create status from player
            if (device.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID) && player != null) {
                RendererStatus.State state = player.isPlaying() ? RendererStatus.State.PLAYING :
                        player.isPaused() ? RendererStatus.State.PAUSED :
                                RendererStatus.State.STOPPED;
                String trackTitle = player.getCurrentItemTitle();
                RendererStatus localStatus = new RendererStatus(device, state.name(), trackTitle, 50); // Default volume

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

    public void setDevices(List<Device<?, ?, ?>> devices) {
        final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DeviceDiffCallback(this.devices, devices));
        this.devices.clear();
        this.devices.addAll(devices);
        diffResult.dispatchUpdatesTo(this);
        updateDeviceStates();
    }

    private void updateDeviceStates() {
        for (int i = 0; i < devices.size(); i++
        ) {
            View view = devicesListView.getChildAt(i);
            if (view != null) {
                new DeviceVolumeStateLoadTask(view.findViewById(R.id.browseReceiverDeviceItemMuteVolumeSeekBar), upnpClient).execute(devices.get(i));
                new DeviceMuteStateLoadTask(view.findViewById(R.id.browseReceiverDeviceItemMute), upnpClient).execute(devices.get(i));
            }
        }


    }


    public void addSelectedDevice(Device<?, ?, ?> device) {
        selectedDevices.add(device);

    }

    public void removeSelectedDevice(Device<?, ?, ?> device) {
        this.selectedDevices.remove(device);
    }

    public void updateStatus(RendererStatus status) {
        RendererStatus oldStatus = statusMap.get(status.getDevice());
        boolean wasPlaying = oldStatus != null && oldStatus.isPlaying();
        statusMap.put(status.getDevice(), status);

        // Re-sort if playing state changed (affects sort order)
        if (wasPlaying != status.isPlaying()) {
            sortAndNotify();
        } else {
            // Just update the item
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).equals(status.getDevice())) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    public void sortAndNotify() {
        sortDevices();
        notifyDataSetChanged();
    }
    
    public void updateLocalDeviceStatus() {
        // Find local device and update its status
        for (Device device : devices) {
            if (device.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID)) {
                Player player = getPlayerForDevice(device);
                if (player != null) {
                    // Use UPnP state strings that will be parsed to State enum
                    String state = player.isPlaying() ? "PLAYING" :
                                   player.isPaused() ? "PAUSED_PLAYBACK" :
                                   "STOPPED";
                    String trackTitle = player.getCurrentItemTitle();
                    RendererStatus localStatus = new RendererStatus(device, state, trackTitle, 50);
                    updateStatus(localStatus);
                } else {
                    statusMap.remove(device);
                    notifyDataSetChanged();
                }
                break;
            }
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void openPlayerActivity(Context context, Device device) {
        // Find active player for this device
        for (Player player : upnpClient.getCurrentPlayers()) {
            if (player instanceof AVTransportPlayer) {
                AVTransportPlayer avPlayer = (AVTransportPlayer) player;
                if (device.getIdentity().getUdn().getIdentifierString().equals(avPlayer.getDeviceId())) {
                    // Found player - open its activity
                    if (player.getNotificationIntent() != null) {
                        try {
                            player.getNotificationIntent().send(context, 0, new Intent());
                        } catch (PendingIntent.CanceledException e) {
                            YaaccLogger.e(getClass().getName(), "Failed to open player activity", e);
                        }
                    }
                    return; // Take first match
                }
            }
        }
        // No active player found - do nothing
    }

    private Player getPlayerForDevice(Device device) {
        String deviceId = device.getIdentity().getUdn().getIdentifierString();

        for (Player player : upnpClient.getCurrentPlayers()) {
            if (player instanceof AVTransportPlayer) {
                AVTransportPlayer avPlayer = (AVTransportPlayer) player;
                if (deviceId.equals(avPlayer.getDeviceId())) {
                    return player;
                }
            } else if (deviceId.equals(UpnpClient.LOCAL_UID)) {
                // Local device - return any local player (LocalMediaSessionPlayer, LocalImagePlayer, etc.)
                if (!(player instanceof AVTransportPlayer)) {
                    return player;
                }
            }
        }
        return null;
    }

    private void wireUpControls(ViewHolder holder, Device device) {
        holder.btnPlay.setOnClickListener(v -> executeAction(device, ACTION_PLAY));
        holder.btnPause.setOnClickListener(v -> executeAction(device, ACTION_PAUSE));
        holder.btnStop.setOnClickListener(v -> executeAction(device, ACTION_STOP));
    }

    private void executeAction(Device device, String action) {
        // Handle local device differently
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            Player player = getPlayerForDevice(device);
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
        
        // Handle remote UPnP device
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

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ImageView albumArt;
        TextView name;
        TextView statusBadge;
        TextView statusText;
        TextView volumeText;
        CheckBox checkBox;
        CheckBox mute;
        SeekBar volume;
        ImageButton btnPlay;
        ImageButton btnPause;
        ImageButton btnStop;
        View itemContainer;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
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

    /**
     * Check if device supports volume control (GetVolume action).
     */
    private boolean supportsVolumeControl(Device<?, ?, ?> device) {
        org.fourthline.cling.model.meta.Service renderingControl = device.findService(new org.fourthline.cling.model.types.UDAServiceId("RenderingControl"));
        return renderingControl != null && renderingControl.getAction("GetVolume") != null;
    }

    /**
     * Check if device supports mute control (GetMute action).
     */
    private boolean supportsMuteControl(Device<?, ?, ?> device) {
        org.fourthline.cling.model.meta.Service renderingControl = device.findService(new org.fourthline.cling.model.types.UDAServiceId("RenderingControl"));
        return renderingControl != null && renderingControl.getAction("GetMute") != null;
    }
}