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
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.os.PowerManager;


import java.util.Collection;
import java.util.HashMap;

import java.util.Map;

/**
 * @author Tobias Schoene (tobexyz)
 */
public class PlayerService extends Service {

    static final int ALARM_REQUEST_CODE = 234562;
    private static final int PALYER_SERVICE_FOREGROUND_ID=432587632;
    private IBinder binder = new PlayerServiceBinder();
    private Map<Integer,Player> currentActivePlayer = new HashMap<>();
    private volatile HandlerThread playerHandlerThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    public PlayerService() {
    }

    public void addPlayer(Player player) {
        aquireWakeLock();
        currentActivePlayer.put(player.getId(),player);
    }

    public void removePlayer(AbstractPlayer player) {

        currentActivePlayer.remove(player.getId());
        if(currentActivePlayer.isEmpty()){
            releaseWakeLock();
        }
    }

    public class PlayerServiceBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "On Destroy");
        if(wakeLock != null || wifiLock != null) {
            releaseWakeLock();
        }
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
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        initialize(intent);
        return START_STICKY;
    }


    private void initialize(Intent intent) {
       /* if (Build.VERSION.SDK_INT >= 26) {
            Log.d(this.getClass().getName(), "Start foreground service" + intent);
           NotificationCompat.Builder b=new NotificationCompat.Builder(this);

            b.setOngoing(true)
                    .setContentTitle(getString(R.string.title_activity_main))
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    ;

            startForeground(PALYER_SERVICE_FOREGROUND_ID,b.build());
        }else{
            Log.d(this.getClass().getName(), "Start as service" + intent);
        }
        */
        // An Android handler thread internally operates on a looper.
        playerHandlerThread = new HandlerThread("de.yaacc.PlayerService.HandlerThread");
        playerHandlerThread.start();
        //startPreventDozeAlarm();
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

    private void startPreventDozeAlarm(){
        AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmBroadCastReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= 23)
        {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 30000, pendingIntent);
        }
        else if (Build.VERSION.SDK_INT >= 19)
        {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, 30000, pendingIntent);
        }
        else
        {
            alarmManager.set(AlarmManager.RTC_WAKEUP, 30000, pendingIntent);
        }
    }



    protected void aquireWakeLock() {
        if(wakeLock == null ){
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Yaacc::WakeLockWhilePlaying");
        }
        if(wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60000);
            Log.d(getClass().getName(), "WakeLock aquired");
        }
        if (wifiLock == null) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL,"Yaacc::WifiLockWhilePlaying");
        }
        if (wifiLock != null && !wifiLock.isHeld()){
            wifiLock.acquire();
            Log.d(getClass().getName(), "WifiLock aquired");
        }
    }

    protected void releaseWakeLock() {
        if(wakeLock != null) {
            try{
                wakeLock.release();
                Log.d(getClass().getName(), "WakeLock released");
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
                Log.d(getClass().getName(), "Ignoring exception on WakeLock release maybe no wakelock?");
            }
        }

        if(wifiLock != null){
            try {
                wifiLock.release();
                Log.d(getClass().getName(), "WifiLock released");
            }catch(Throwable th){
                Log.d(getClass().getName(), "Ignoring exception on WifiLock release maybe no wifilock?");
            }

        }
    }
}
