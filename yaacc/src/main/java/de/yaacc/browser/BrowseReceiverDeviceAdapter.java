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


import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;

/**
 * Receiver tab list. Row binding lives in {@link ReceiverRowBinder}; this
 * adapter keeps the device list, its order and the selection.
 *
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseReceiverDeviceAdapter extends RecyclerView.Adapter<BrowseReceiverDeviceAdapter.ViewHolder> {
    private final List<Device<?, ?, ?>> selectedDevices;
    private List<Device<?, ?, ?>> devices;
    private RecyclerView devicesListView;
    private final ReceiverRowBinder rowBinder;
    // Selection re-sorts the device list, so row positions change wholesale.
    @SuppressLint("NotifyDataSetChanged")
    private final ReceiverRowBinder.SelectionListener selectionListener = (device, selected) -> {
        if (selected) {
            addSelectedDevice(device);
        } else {
            removeSelectedDevice(device);
        }
        // Re-sort after selection change
        sortDevices();
        notifyDataSetChanged();
    };


    public BrowseReceiverDeviceAdapter(Context ctx, UpnpClient upnpClient, RecyclerView devicesListView, Collection<Device<?, ?, ?>> devices, Collection<Device<?, ?, ?>> selectedDevices) {
        super();
        this.devices = new ArrayList<>(devices);
        this.selectedDevices = new LinkedList<>(selectedDevices);
        this.devicesListView = devicesListView;
        this.rowBinder = new ReceiverRowBinder(ctx, upnpClient);
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
                Device<?, ?, ?> device = getItem(position);
                rowBinder.openPlayerActivity(v.getContext(), device);
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
                    holder.views.checkBox.requestFocus();
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
        rowBinder.bind(holder.views, device, selectedDevices.contains(device), selectionListener);
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
                rowBinder.refreshVolumeAndMute(view, devices.get(i));
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
        // Re-sort if playing state changed (affects sort order)
        if (rowBinder.updateStatus(status)) {
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

    // Re-sorting moves rows arbitrarily, so there is no precise range to notify.
    @SuppressLint("NotifyDataSetChanged")
    public void sortAndNotify() {
        sortDevices();
        notifyDataSetChanged();
    }
    
    // Local device row position is not tracked here; rebinding all rows is intended.
    @SuppressLint("NotifyDataSetChanged")
    public void updateLocalDeviceStatus() {
        // Find local device and update its status
        for (Device<?, ?, ?> device : devices) {
            if (device.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID)) {
                RendererStatus localStatus = rowBinder.localDeviceStatus(device);
                if (localStatus != null) {
                    updateStatus(localStatus);
                } else {
                    rowBinder.clearStatus(device);
                    notifyDataSetChanged();
                }
                break;
            }
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ReceiverRowBinder.RowViews views;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            views = new ReceiverRowBinder.RowViews(itemView);
        }
    }
}
