package de.yaacc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;
import android.widget.Toast;

import de.yaacc.player.PlayerService;


public class AlarmBroadCastReceiver extends WakefulBroadcastReceiver {

    private PowerManager.WakeLock screenWakeLock;

    public AlarmBroadCastReceiver() {

    }

    @Override
    public void onReceive(Context context, Intent i) {
        Log.d(getClass().getName(),"Received prevent doze alarm");
        if (screenWakeLock == null)
        {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            screenWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "de.yaacc:screenlock");
            Toast.makeText(context,"Received prevent doze alarm", Toast.LENGTH_SHORT);

        }
        if(screenWakeLock != null && !screenWakeLock.isHeld()){
            screenWakeLock.acquire();
        }
        Intent service = new Intent(context, YaaccService.class);
        startWakefulService(context, service);
        if (screenWakeLock != null && screenWakeLock.isHeld())
            screenWakeLock.release();
    }
}
