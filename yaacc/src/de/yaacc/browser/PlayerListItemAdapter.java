/*
 * Copyright (C) 2014 www.yaacc.de
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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.R;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * Adapter for browsing player.
 *
 * @author Tobias Schoene (the openbit)
 */
public class PlayerListItemAdapter extends BaseAdapter {
    private LayoutInflater inflator;
    private List<Player> players;
    private UpnpClient upnpClient;


    public PlayerListItemAdapter(UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
        initialize();
    }

    private void initialize() {
        inflator = LayoutInflater.from(upnpClient.getContext());
        players = new ArrayList<>(upnpClient.getCurrentPlayers());

    }

    @Override
    public int getCount() {
        if (players == null) {
            return 0;
        }
        return players.size();
    }

    @Override
    public Object getItem(int arg0) {
        return players.get(arg0);
    }

    @Override
    public long getItemId(int arg0) {
        return arg0;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        ViewHolder holder;
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(parent.getContext());
        Context context = parent.getContext();
        if (view == null) {
            view = inflator.inflate(R.layout.browse_item, parent, false);
            holder = new ViewHolder();
            holder.icon = (ImageView) view.findViewById(R.id.browseItemIcon);
            holder.name = (TextView) view.findViewById(R.id.browseItemName);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        Player player = null;
        if(position < players.size()) {
            player = players.get(position);
        }
        if (player != null) {
            holder = holder == null ? holder = new ViewHolder() : holder;
            holder.name.setText(player.getName() +
                    " : " + upnpClient.getDevice(player.getDeviceId()).getDetails().getFriendlyName());
            holder.icon.setImageResource(player.getIconResourceId());
        }
        return view;
    }

    static class ViewHolder {
        ImageView icon;
        TextView name;
    }


}