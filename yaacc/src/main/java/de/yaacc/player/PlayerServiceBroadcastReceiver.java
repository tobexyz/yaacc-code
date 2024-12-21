/*
 * Copyright (C) 2024 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * @author tobexyz
 */
public class PlayerServiceBroadcastReceiver extends BroadcastReceiver {

    public static String ACTION_NEXT = "de.yaacc.player.ActionNext";

    private final PlayerService playerService;


    public PlayerServiceBroadcastReceiver(PlayerService playerService) {
        Log.d(this.getClass().getName(), "Starting Broadcast Receiver...");
        assert (playerService != null);
        this.playerService = playerService;

    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(this.getClass().getName(), "Received Action: " + intent.getAction());
        if (playerService == null) return;
        Log.w(this.getClass().getName(), "Execute Action on playerService: " + playerService);
        if (ACTION_NEXT.equals(intent.getAction())) {
            Integer playerId = intent.getIntExtra(AbstractPlayer.PLAYER_ID, -1);
            Log.w(this.getClass().getName(), "Player of intent not found: " + playerId + " Intent: " + intent.getStringExtra("ID"));
            Player player = playerService.getCurrentPlayerById(playerId);
            if (player != null) {
                player.next();
            } else {
                Log.w(this.getClass().getName(), "Player of intent not found: " + playerId);
            }
            ;
        }
    }

    public void registerReceiver() {
        Log.d(this.getClass().getName(), "Register PlayerServiceBroadcastReceiver");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_NEXT);
        playerService.registerReceiver(this, intentFilter);
    }

}
