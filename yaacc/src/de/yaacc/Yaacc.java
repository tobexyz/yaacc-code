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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc;


import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;

import de.yaacc.upnp.UpnpClient;

/**
 * application which holds the global state
 * @author Tobias Schoene (tobexyz)
 *
 */
public class Yaacc extends Application {
    private UpnpClient upnpClient;
    private HashMap<String, PowerManager.WakeLock> wakeLocks  = new HashMap<>();



    @Override
    public void onCreate() {
        super.onCreate();
        upnpClient = new UpnpClient(this);
    }

    public UpnpClient getUpnpClient() {
        return upnpClient;
    }

    public boolean isUnplugged(){
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        return ! (plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS);

    }
    public void aquireWakeLock(long timeout, String tag) {

        if (!wakeLocks.containsKey(tag)) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLocks.put(tag,powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK,tag));
        }
        PowerManager.WakeLock wakeLock = wakeLocks.get(tag);
        if (wakeLock != null) {
            if (wakeLock.isHeld()){
                releaseWakeLock(tag);
            }
            while(!wakeLock.isHeld()) {
                wakeLock.acquire(timeout);
            }
            Log.d(getClass().getName(), "WakeLock aquired Tag:" + tag + " timeout: " + timeout);
        }




    }

    public void releaseWakeLock(String tag) {
        PowerManager.WakeLock wakeLock = wakeLocks.get(tag);
        if(wakeLock != null && wakeLock.isHeld()) {
            try{
                wakeLock.release();
                Log.d(getClass().getName(), "WakeLock released: " + tag );
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
                Log.d(getClass().getName(), "Ignoring exception on WakeLock ("+ tag + ") release maybe no wakelock?" );
            }
        }

    }


}