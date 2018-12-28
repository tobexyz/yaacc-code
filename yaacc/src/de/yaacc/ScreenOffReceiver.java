package de.yaacc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

public class ScreenOffReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            ((Yaacc)context.getApplicationContext()).aquireWakeLock();
        }
    }
}
