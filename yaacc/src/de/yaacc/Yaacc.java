package de.yaacc;


import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;


import de.yaacc.upnp.UpnpClient;

public class Yaacc extends Application {
    private UpnpClient upnpClient;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean hasPlayer;


    @Override
    public void onCreate() {
        super.onCreate();
        upnpClient = new UpnpClient(this);
/*
        Intent yaaccServiceIntent = new Intent(this, YaaccService.class);
        if(Build.VERSION.SDK_INT >= 26){
            startForegroundService(yaaccServiceIntent);
        }else {
            startService(yaaccServiceIntent);
        }
*/
    }



   /* private void configureBatteryOptimisation()
    {
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String packageName = getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(getClass().getName(),"Battery optimisations are in effect please configure");
                if (true) {
                    intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                }
            }
        }
    }
*/
    public UpnpClient getUpnpClient() {
        return upnpClient;
    }

    public void aquireWakeLock() {
        if(!hasPlayer) return;
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

        if (! (plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)) {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

                wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK,
                        "Yaacc::WakeLockWhilePlaying");
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(600000);
                Log.d(getClass().getName(), "WakeLock aquired");
            }
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "Yaacc::WifiLockWhilePlaying");
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                Log.d(getClass().getName(), "WifiLock aquired");
            }
        }


    }

    public void releaseWakeLock() {
        if(wakeLock != null && wakeLock.isHeld()) {
            try{
                wakeLock.release();
                Log.d(getClass().getName(), "WakeLock released");
            } catch (Throwable th) {
                // ignoring this exception, probably wakeLock was already released
                Log.d(getClass().getName(), "Ignoring exception on WakeLock release maybe no wakelock?");
            }
        }

        if(wifiLock != null && wifiLock.isHeld()){
            try {
                wifiLock.release();
                Log.d(getClass().getName(), "WifiLock released");
            }catch(Throwable th){
                Log.d(getClass().getName(), "Ignoring exception on WifiLock release maybe no wifilock?");
            }

        }

    }

    public boolean isHasPlayer() {
        return hasPlayer;
    }

    public void setHasPlayer(boolean hasPlayer) {
        this.hasPlayer = hasPlayer;
    }
}
