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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import java.util.ArrayList;

import de.yaacc.R;
import de.yaacc.player.Player;

/**
 * Clicklistener when browsing players.
 *
 * @author Tobias Schöne (the openbit)
 */
public class PlayerListItemClickListener implements OnItemClickListener {

    @Override
    public void onItemClick(AdapterView<?> listView, View arg1, int position,
                            long id) {
        ListView a = (ListView) listView.findViewById(R.id.playerList);
        PlayerListItemAdapter adapter = (PlayerListItemAdapter) listView.getAdapter();
        Player player = (Player) adapter.getItem(position);
        openIntent(a.getContext(), player);

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

    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {
        menu.setHeaderTitle(v.getContext().getString(R.string.browse_context_title));
        ArrayList<String> menuItems = new ArrayList<String>();
        menuItems.add(v.getContext().getString(R.string.open));
        menuItems.add(v.getContext().getString(R.string.exitActivity));

        for (int i = 0; i < menuItems.size(); i++) {
            menu.add(Menu.NONE, i, i, menuItems.get(i));
        }
    }


    public boolean onContextItemSelected(Player selectedPlayer, MenuItem item, Context applicationContext) {
        if (item.getTitle().equals(applicationContext.getString(R.string.open))) {
            openIntent(applicationContext, selectedPlayer);
        } else if (item.getTitle().equals(applicationContext.getString(R.string.exitActivity))) {
            selectedPlayer.exit();

        }
        return true;
    }
} 