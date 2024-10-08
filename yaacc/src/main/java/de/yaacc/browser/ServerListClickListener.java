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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

/**
 * @author Tobias Schoene (the openbit)
 */
public class ServerListClickListener implements View.OnClickListener {

    private final UpnpClient upnpClient;
    private final Context parent;
    private final RecyclerView deviceListView;
    private final BrowseDeviceAdapter adapter;

    public ServerListClickListener(RecyclerView deviceListView, BrowseDeviceAdapter adapter, UpnpClient upnpClient, Context parent) {
        this.upnpClient = upnpClient;
        this.parent = parent;
        this.deviceListView = deviceListView;
        this.adapter = adapter;
    }


    @Override
    public void onClick(View view) {
        upnpClient.setProviderDevice((Device<?, ?, ?>) adapter.getItem(deviceListView.getChildAdapterPosition(view)));
        if (parent instanceof TabBrowserActivity) {
            ((TabBrowserActivity) parent).setCurrentTab(BrowserTabs.CONTENT);
        }
    }
}
