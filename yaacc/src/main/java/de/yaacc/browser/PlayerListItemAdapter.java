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

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.R;
import de.yaacc.player.Player;
import de.yaacc.util.ThemeHelper;

/**
 * Adapter for browsing player.
 *
 * @author Tobias Schoene (the openbit)
 */
public class PlayerListItemAdapter extends RecyclerView.Adapter<PlayerListItemAdapter.ViewHolder> {
    private final RecyclerView playerListView;
    private final List<Player> players;


    public PlayerListItemAdapter(RecyclerView playerList, Collection<Player> players) {
        this.playerListView = playerList;
        this.players = new ArrayList<>(players);
        notifyDataSetChanged();
    }


    @Override
    public long getItemId(int position) {
        return position;
    }


    @Override
    public int getItemCount() {
        if (players == null) {
            return 0;
        }
        return players.size();
    }

    public Player getItem(int position) {
        return players.get(position);
    }

    public void setItems(Collection<Player> newObjects) {
        Log.d(getClass().getName(), "set objects; " + newObjects);
        players.clear();
        players.addAll(newObjects);
        notifyDataSetChanged();

    }


    @Override
    public PlayerListItemAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                               int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.browse_player_item, parent, false);
        view.setOnClickListener(new PlayerListItemClickListener(playerListView, this));
        return new PlayerListItemAdapter.ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final PlayerListItemAdapter.ViewHolder holder, final int listPosition) {

        Player player = getItem(listPosition);
        if (player != null) {
            holder.name.setText(player.getName());
            int resId = android.R.attr.colorForeground;
            if (Configuration.UI_MODE_NIGHT_YES == (playerListView.getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)) {
                resId = android.R.attr.colorForegroundInverse;
            }
            TypedValue typedValue = new TypedValue();
            playerListView.getContext().getApplicationContext().getTheme().resolveAttribute(resId, typedValue, true);
            int color = typedValue.data;
            holder.name.setTextColor(color);
            if (player.getIcon() != null) {
                holder.icon.setImageBitmap(Bitmap.createScaledBitmap(player.getIcon(), 48, 48, false));
            } else {
                if (R.drawable.yaacc48_24_png != player.getIconResourceId()) {
                    holder.icon.setImageDrawable(ThemeHelper.tintDrawable(playerListView.getContext().getResources().getDrawable(player.getIconResourceId(), playerListView.getContext().getTheme()), playerListView.getContext().getTheme()));
                } else {
                    holder.icon.setImageDrawable(playerListView.getContext().getResources().getDrawable(R.drawable.yaacc48_24_png, playerListView.getContext().getTheme()));
                }

            }
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.browsePlayerItemIcon);
            name = itemView.findViewById(R.id.browsePlayerItemName);
        }
    }


}