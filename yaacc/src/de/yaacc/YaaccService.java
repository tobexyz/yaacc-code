package de.yaacc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;


public class YaaccService extends Service {
    static final int ALARM_REQUEST_CODE = 234562;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private IBinder binder = new YaaccServiceBinder();
    public YaaccService() {

    }

    public class YaaccServiceBinder extends Binder {
        public YaaccServiceBinder getService() {
            return YaaccServiceBinder.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this.getClass().getName(), "Received onBind: " + intent);
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        super.onStartCommand(intent, flags, startId);
        //startPreventDozeAlarm();
        aquireWakeLock();
        return START_STICKY;
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
                    "Yaacc::WakeLock");
        }
        if(wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60000);
            Log.d(getClass().getName(), "WakeLock aquired");
        }
        if (wifiLock == null) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL,"Yaacc::WifiLock");
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

    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "On Destroy");
        if(wakeLock != null || wifiLock != null) {
            releaseWakeLock();
        }
    }
}
