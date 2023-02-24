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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import de.yaacc.player.Player;

/**
 * Clicklistener when browsing players.
 *
 * @author Tobias Schöne (the openbit)
 */
public class PlayerListItemClickListener implements View.OnClickListener {
    private RecyclerView recyclerView;
    private PlayerListItemAdapter adapter;

    public PlayerListItemClickListener(RecyclerView recyclerView, PlayerListItemAdapter adapter) {
        this.adapter = adapter;
        this.recyclerView = recyclerView;
    }

    @Override
    public void onClick(View itemView) {
        Player player = adapter.getItem(recyclerView.getChildAdapterPosition(itemView));
        openIntent(itemView.getContext(), player);

    }

    private void openIntent(Context context, Player player) {
        if (player.getNotificationIntent() != null) {
            Intent intent = new Intent();
            try {
                player.getNotificationIntent().send(context, 0, intent);

            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.  Just log the exception message.
                Log.e(this.getClass().getName(), "Sending contentIntent failed", e);
            }

        }
    }

} 