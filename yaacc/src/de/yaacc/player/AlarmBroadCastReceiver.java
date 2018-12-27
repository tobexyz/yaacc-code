package de.yaacc.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;


public class AlarmBroadCastReceiver extends BroadcastReceiver {

    public AlarmBroadCastReceiver() {

    }

    @Override
    public void onReceive(Context context, Intent i) {
        Log.d(getClass().getName(),"Received prevent doze alarm");
        Toast.makeText(context,"Hello world", Toast.LENGTH_SHORT);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmBroadCastReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                PlayerService.ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);
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


        Intent myIntent = new Intent(context, PlayerService.class);
        if(Build.VERSION.SDK_INT >= 26){
            context.startForegroundService(myIntent);
        }else {
            context.startService(myIntent);
        }

    }
}
