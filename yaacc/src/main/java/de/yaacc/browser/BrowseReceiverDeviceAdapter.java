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
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.URL;
import java.util.Collection;
import java.util.LinkedList;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.image.IconDownloadTask;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseReceiverDeviceAdapter extends BaseAdapter {
    LinkedList<Device> devices;
    private LayoutInflater inflator;
    private LinkedList<Device> selectedDevices;

    public BrowseReceiverDeviceAdapter(Context ctx, Collection<Device> devices, Collection<Device> selectedDevices) {
        super();
        this.devices = new LinkedList<Device>(devices);
        this.selectedDevices = new LinkedList<Device>(selectedDevices);
        inflator = LayoutInflater.from(ctx);
    }

    @Override
    public int getCount() {
        return devices.size();
    }

    @Override
    public Object getItem(int position) {
        return devices.get(position);
    }

    @Override
    public long getItemId(int position) {
// TODO Auto-generated method stub
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflator.inflate(R.layout.browse_item_checkable, parent, false);
            Log.d(getClass().getName(), "New view created");
            holder = new ViewHolder();
            holder.icon = (ImageView) convertView
                    .findViewById(R.id.browseItemIcon);
            holder.name = (TextView) convertView
                    .findViewById(R.id.browseItemName);
            holder.checkBox = (CheckBox) convertView
                    .findViewById(R.id.browseItemCheckbox);
            convertView.setTag(holder);
        } else {
            Log.d(getClass().getName(), "view already there");
            holder = (ViewHolder) convertView.getTag();
        }
        Device device = (Device) getItem(position);
        if (device instanceof RemoteDevice && device.hasIcons()) {
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (int i = 0; i < icons.length; i++) {
                    if (48 == icons[i].getHeight() && 48 == icons[i].getWidth() && "image/png".equals(icons[i].getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icons[i].getUri());
                        if (iconUri != null) {
                            Log.d(getClass().getName(), "Device icon uri:" + iconUri);
                            new IconDownloadTask((ListView) parent, position).execute(Uri.parse(iconUri.toString()));
                            break;

                        }
                    }
                }
            } else {
                holder.icon.setImageResource(R.drawable.device);
            }
        } else if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            //We know our icon
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
        }
        holder.name.setText(device.getDetails().getFriendlyName());
        holder.checkBox.setChecked(selectedDevices.contains(device));
        Log.d(getClass().getName(), "checkBox isChecked (" + device.getDisplayString() + "):" + holder.checkBox.isChecked());
        return convertView;
    }

    public void setDevices(Collection<Device<?, ?, ?>> devices) {
        this.devices = new LinkedList<Device>();
        this.devices.addAll(devices);
    }


    public void addSelectedDevice(Device<?, ?, ?> device) {
        this.selectedDevices.add(device);
    }

    public void removeSelectedDevice(Device<?, ?, ?> device) {
        this.selectedDevices.remove(device);
    }

    static class ViewHolder {
        ImageView icon;
        TextView name;
        CheckBox checkBox;
    }
} 