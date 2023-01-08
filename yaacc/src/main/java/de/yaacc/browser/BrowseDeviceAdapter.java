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

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
import de.yaacc.browser.BrowseItemAdapter.ViewHolder;
import de.yaacc.util.image.IconDownloadTask;

/**
 * @author Christoph Hähnel (eyeless)
 */
public class BrowseDeviceAdapter extends BaseAdapter {

    private final LayoutInflater inflator;
    private LinkedList<Device<?, ?, ?>> devices;

    public BrowseDeviceAdapter(Context ctx, LinkedList<Device<?, ?, ?>> devices) {
        super();

        this.devices = devices;

        inflator = LayoutInflater.from(ctx);
        notifyDataSetChanged();
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
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflator.inflate(R.layout.browse_item, parent, false);

            holder = new ViewHolder();
            holder.icon = (ImageView) convertView.findViewById(R.id.browseItemIcon);
            holder.name = (TextView) convertView.findViewById(R.id.browseItemName);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Device<?, ?, ?> device = (Device<?, ?, ?>) getItem(position);
        if (device instanceof RemoteDevice) {
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (Icon icon : icons) {
                    if (48 == icon.getHeight() && 48 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
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
        } else if (device instanceof LocalDevice) {
            //We know our icon
            holder.icon.setImageResource(R.drawable.yaacc48_24_png);
        }

        holder.name.setText(device.getDetails().getFriendlyName());

        return convertView;
    }

    public void setDevices(Collection<Device<?, ?, ?>> devices) {
        this.devices = new LinkedList<>();
        this.devices.addAll(devices);
        notifyDataSetChanged();
    }

}
