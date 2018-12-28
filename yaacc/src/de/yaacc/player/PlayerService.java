/*
 * Copyright (C) 2018 www.yaacc.de
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

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.widget.Toast;


import java.util.Collection;
import java.util.HashMap;

import java.util.Map;

import de.yaacc.Yaacc;

/**
 * @author Tobias Schoene (tobexyz)
 */
public class PlayerService extends Service {

    private IBinder binder = new PlayerServiceBinder();
    private Map<Integer,Player> currentActivePlayer = new HashMap<>();
    private HandlerThread playerHandlerThread;


    public PlayerService() {
    }

    public void addPlayer(Player player) {
        currentActivePlayer.put(player.getId(),player);
    }

    public void removePlayer(AbstractPlayer player) {

        currentActivePlayer.remove(player.getId());
    }

    public class PlayerServiceBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "On Destroy");
    }



    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this.getClass().getName(), "On Bind");
        return binder;
    }

    public Collection<Player> getPlayer(){
        return currentActivePlayer.values();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        initialize(intent);
        return START_STICKY;
    }


    private void initialize(Intent intent) {
        playerHandlerThread = new HandlerThread("de.yaacc.PlayerService.HandlerThread");
        playerHandlerThread.start();
    }


    public HandlerThread getPlayerHandlerThread(){
        return playerHandlerThread;
    }

    public Player getPlayer(int playerId) {
        Log.d(this.getClass().getName(), "Get Player for id " + playerId);
        if (currentActivePlayer.get(playerId) == null){
            Log.d(this.getClass().getName(), "Get Player not found");
        }
        return currentActivePlayer.get(playerId);
    }






}
