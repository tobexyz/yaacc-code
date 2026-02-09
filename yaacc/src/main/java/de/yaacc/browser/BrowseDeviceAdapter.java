/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.configuration.YaaccUpnpServerControlActivity;
import de.yaacc.util.MediaStoreScanner;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadTask;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseDeviceAdapter extends RecyclerView.Adapter<BrowseDeviceAdapter.ViewHolder> {

    private final Context context;
    private LinkedList<Device<?, ?, ?>> devices;
    private UpnpClient upnpClient;
    private RecyclerView deviceList;

    // Track streaming state (transient, not persisted)
    private static boolean isAudioStreaming = false;
    private static boolean isVideoStreaming = false;

    // Track which button requested permission (for onActivityResult)
    private static boolean pendingAudioRequest = false;
    private static boolean pendingVideoRequest = false;

    // Callback for permission requests
    public interface StreamPermissionCallback {
        void requestMediaProjectionPermission();
    }

    private StreamPermissionCallback permissionCallback;

    // Track which button requested permission
    private static boolean audioButtonRequestedPermission = false;
    private static boolean videoButtonRequestedPermission = false;

    // Public accessors for streaming state
    public static void setAudioStreaming(boolean enabled) {
        isAudioStreaming = enabled;
    }

    public static void setVideoStreaming(boolean enabled) {
        isVideoStreaming = enabled;
    }

    public static boolean isPendingAudioRequest() {
        return pendingAudioRequest;
    }

    public static boolean isPendingVideoRequest() {
        return pendingVideoRequest;
    }

    public static void clearPendingRequests() {
        pendingAudioRequest = false;
        pendingVideoRequest = false;
    }


    public BrowseDeviceAdapter(Context ctx, RecyclerView deviceList, UpnpClient upnpClient, List<Device<?, ?, ?>> devices) {
        super();

        this.devices = new LinkedList<>(devices);
        if (this.devices == null) {
            this.devices = new LinkedList<>();
        }
        this.upnpClient = upnpClient;
        this.deviceList = deviceList;
        context = ctx;
        notifyDataSetChanged();
    }

    public void setPermissionCallback(StreamPermissionCallback callback) {
        this.permissionCallback = callback;
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public Device<?, ?, ?> getItem(int position) {
        return devices.get(position);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent,
                                         int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.browse_device_item, parent, false);
        view.setOnClickListener(new ServerListClickListener(deviceList, this, upnpClient, context));
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int position = deviceList.getChildAdapterPosition(v);
            if (position == RecyclerView.NO_POSITION) return false;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    // Trigger normal click
                    v.performClick();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Focus first visible action button
                    BrowseDeviceAdapter.ViewHolder holder = (BrowseDeviceAdapter.ViewHolder) deviceList.getChildViewHolder(v);
                    if (holder.configButton.getVisibility() == View.VISIBLE) {
                        holder.configButton.requestFocus();
                        return true;
                    }
                    return false;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    // Let parent handle; if we are on first column maybe switch tabs later
                    return false;
            }
            return false;
        });
        return new ViewHolder(view, context, this);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, final int listPosition) {
        Device<?, ?, ?> device = getItem(listPosition);
        if (device instanceof RemoteDevice) {
            holder.scanButton.setVisibility(View.GONE);
            holder.scanButtonLabel.setVisibility(View.GONE);
            holder.configButton.setVisibility(View.GONE);
            holder.streamAudioButton.setVisibility(View.GONE);
            holder.streamVideoButton.setVisibility(View.GONE);
            holder.scanButton.setFocusable(false);
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (Icon icon : icons) {
                    if (48 == icon.getHeight() && 48 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
                        if (iconUri != null) {
                            YaaccLogger.d(getClass().getName(), "Device icon uri:" + iconUri);
                            new IconDownloadTask(holder.icon).execute(Uri.parse(iconUri.toString()));
                            break;
                        }
                    }
                }
            } else {
                holder.icon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_sensors_48, context.getTheme()), context.getTheme()));
            }
        } else if (device instanceof LocalDevice) {
            //We know our icon
            holder.scanButton.setVisibility(View.VISIBLE);
            holder.scanButton.setFocusable(true);
            holder.scanButton.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_refresh_48, context.getTheme()), context.getTheme()));
            holder.scanButtonLabel.setVisibility(View.VISIBLE);
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
            holder.configButton.setVisibility(View.VISIBLE);
            holder.configButton.setFocusable(true);
            holder.configButton.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_settings_32, context.getTheme()), context.getTheme()));

            // Show stream buttons only on Android 10+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                holder.streamAudioButton.setVisibility(View.VISIBLE);
                holder.streamVideoButton.setVisibility(View.VISIBLE);
                // Apply theme tinting like other buttons
                holder.streamAudioButton.setImageDrawable(ThemeHelper.tintDrawable(
                        context.getResources().getDrawable(R.drawable.ic_live_audio_stream, context.getTheme()),
                        context.getTheme()));
                holder.streamVideoButton.setImageDrawable(ThemeHelper.tintDrawable(
                        context.getResources().getDrawable(R.drawable.ic_live_video_stream, context.getTheme()),
                        context.getTheme()));
                // Restore state from preferences
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                isAudioStreaming = prefs.getBoolean(context.getString(R.string.settings_local_server_serve_system_audio_chkbx), false);
                isVideoStreaming = prefs.getBoolean(context.getString(R.string.settings_local_server_serve_screen_cast_chkbx), false);
                // Update button states
                holder.updateStreamButtonState(holder.streamAudioButton, isAudioStreaming);
                holder.updateStreamButtonState(holder.streamVideoButton, isVideoStreaming);
            } else {
                holder.streamAudioButton.setVisibility(View.GONE);
                holder.streamVideoButton.setVisibility(View.GONE);
            }
        }

        holder.name.setText(device.getDetails().getFriendlyName());

    }


    public void setDevices(List<Device<?, ?, ?>> devices) {
        final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DeviceDiffCallback(this.devices, devices));
        this.devices.clear();
        this.devices.addAll(devices);
        diffResult.dispatchUpdatesTo(this);

    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        ImageButton scanButton;
        TextView scanButtonLabel;
        ImageButton configButton;
        ImageButton streamAudioButton;
        ImageButton streamVideoButton;

        Context context;
        BrowseDeviceAdapter adapter;
        private Timer timer;

        public ViewHolder(View itemView, Context context, BrowseDeviceAdapter adapter) {
            super(itemView);
            this.context = context;
            this.adapter = adapter;
            timer = new Timer();
            this.icon = itemView.findViewById(R.id.browseDeviceItemIcon);
            this.name = itemView.findViewById(R.id.browseDeviceItemName);
            this.scanButtonLabel = itemView.findViewById(R.id.browseDeviceItemMediaStoreScanLabel);
            this.scanButton = itemView.findViewById(R.id.browseDeviceItemRescan);
            scanButton.setOnClickListener((v) -> {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        new MediaStoreScanner().scanMediaFiles(getActivity(v.getContext()));
                    }
                }, 10L);

            });
            this.configButton = itemView.findViewById(R.id.browseDeviceItemConfig);
            configButton.setOnClickListener((v) -> {
                ViewHolder.this.context.startActivity(new Intent(ViewHolder.this.context, YaaccUpnpServerControlActivity.class));
            });

            this.streamAudioButton = itemView.findViewById(R.id.browseDeviceItemStreamAudio);
            this.streamVideoButton = itemView.findViewById(R.id.browseDeviceItemStreamVideo);

            streamAudioButton.setOnClickListener((v) -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    return;
                }

                if (!isAudioStreaming) {
                    // Turning ON - check permission
                    if (!de.yaacc.upnp.server.media.MediaProjectionHelper.hasPermission()) {
                        // Request permission - mark that audio button requested it
                        pendingAudioRequest = true;
                        pendingVideoRequest = false;
                        if (adapter.permissionCallback != null) {
                            adapter.permissionCallback.requestMediaProjectionPermission();
                        }
                        return;
                    }
                }

                // Toggle state
                isAudioStreaming = !isAudioStreaming;
                updateStreamButtonState(streamAudioButton, isAudioStreaming);

                // Save to preferences (hidden from UI)
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putBoolean(context.getString(R.string.settings_local_server_serve_system_audio_chkbx), isAudioStreaming).apply();

                // If both disabled, clear permission
                if (!isAudioStreaming && !isVideoStreaming) {
                    de.yaacc.upnp.server.media.MediaProjectionHelper.clearPermission();
                }

                // TODO: Start/stop audio capture service
                YaaccLogger.i(getClass().getName(), "Audio streaming: " + isAudioStreaming);
            });

            streamVideoButton.setOnClickListener((v) -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    return;
                }

                if (!isVideoStreaming) {
                    // Turning ON - check permission
                    if (!de.yaacc.upnp.server.media.MediaProjectionHelper.hasPermission()) {
                        // Request permission - mark that video button requested it
                        pendingAudioRequest = false;
                        pendingVideoRequest = true;
                        if (adapter.permissionCallback != null) {
                            adapter.permissionCallback.requestMediaProjectionPermission();
                        }
                        return;
                    }
                }

                // Toggle state
                isVideoStreaming = !isVideoStreaming;
                updateStreamButtonState(streamVideoButton, isVideoStreaming);

                // Save to preferences (hidden from UI)
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putBoolean(context.getString(R.string.settings_local_server_serve_screen_cast_chkbx), isVideoStreaming).apply();

                // If both disabled, clear permission
                if (!isAudioStreaming && !isVideoStreaming) {
                    de.yaacc.upnp.server.media.MediaProjectionHelper.clearPermission();
                }

                // TODO: Start/stop video capture service
                YaaccLogger.i(getClass().getName(), "Video streaming: " + isVideoStreaming);
            });
        }

        private void updateStreamButtonState(ImageButton button, boolean isActive) {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (isActive) {
                // Use accent/primary color for active state
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                button.setColorFilter(typedValue.data);
            } else {
                // Clear color filter to use default theme color
                button.clearColorFilter();
            }
        }

        private Activity getActivity(Context ctx) {
            Context context = ctx;
            while (context instanceof ContextWrapper) {
                if (context instanceof Activity) {
                    return (Activity) context;
                }
                context = ((ContextWrapper) context).getBaseContext();
            }
            return null;
        }
    }


}
