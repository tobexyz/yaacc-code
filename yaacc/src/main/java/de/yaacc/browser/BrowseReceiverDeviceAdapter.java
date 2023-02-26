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

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.image.IconDownloadTask;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseReceiverDeviceAdapter extends RecyclerView.Adapter<BrowseReceiverDeviceAdapter.ViewHolder> {
    private final List<Device<?, ?, ?>> selectedDevices;
    private final Context context;
    private List<Device<?, ?, ?>> devices;
    private UpnpClient upnpClient;
    private RecyclerView devicesListView;


    public BrowseReceiverDeviceAdapter(Context ctx, UpnpClient upnpClient, RecyclerView devicesListView, Collection<Device<?, ?, ?>> devices, Collection<Device<?, ?, ?>> selectedDevices) {
        super();
        this.devices = new ArrayList<>(devices);
        this.selectedDevices = new LinkedList<>(selectedDevices);
        context = ctx;
        this.upnpClient = upnpClient;
        this.devicesListView = devicesListView;
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

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final BrowseReceiverDeviceAdapter.ViewHolder holder, final int listPosition) {
        Device<?, ?, ?> device = getItem(listPosition);
        if (device instanceof RemoteDevice && device.hasIcons()) {
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (Icon icon : icons) {
                    if (48 == icon.getHeight() && 48 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
                        if (iconUri != null) {
                            Log.d(getClass().getName(), "Device icon uri:" + iconUri);
                            new IconDownloadTask(holder.icon).execute(Uri.parse(iconUri.toString()));
                            break;
                        }
                    }
                }
            } else {
                holder.icon.setImageDrawable(ThemeHelper.tintDrawable(context.getResources().getDrawable(R.drawable.ic_baseline_devices_48, context.getTheme()), context.getTheme()));
            }
        } else if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            //We know our icon
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
        }
        holder.name.setText(device.getDetails().getFriendlyName());
        holder.checkBox.setOnClickListener((it) -> {
            if (!((CheckBox) it).isChecked()) {
                Log.d(getClass().getName(), "isNotChecked:" + device.getDisplayString());
                removeSelectedDevice(device);
                upnpClient.removeReceiverDevice(device);
            } else {
                Log.d(getClass().getName(), "isChecked:" + device.getDisplayString());
                addSelectedDevice(device);
                upnpClient.addReceiverDevice(device);
            }
        });
        holder.checkBox.setChecked(selectedDevices.contains(device));
        new DeviceVolumeStateLoadTask(holder.volume, upnpClient).execute(device);
        new DeviceMuteStateLoadTask(holder.mute, upnpClient).execute(device);


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

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        CheckBox checkBox;
        CheckBox mute;
        SeekBar volume;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.browseReceiverDeviceItemIcon);
            name = itemView.findViewById(R.id.browseReceiverDeviceItemName);
            checkBox = itemView.findViewById(R.id.browseReceiverDeviceItemCheckbox);
            mute = itemView.findViewById(R.id.browseReceiverDeviceItemMute);
            volume = itemView.findViewById(R.id.browseReceiverDeviceItemMuteVolumeSeekBar);
            volume.setMax(100);
        }
    }
} 