package de.yaacc;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import de.yaacc.upnp.UpnpClient;

public class Yaacc extends Application {
    private UpnpClient upnpClient;
    @Override
    public void onCreate() {
        super.onCreate();
        upnpClient = new UpnpClient(this);

        startService(new Intent(this, YaaccService.class));

    }

    private void configureBatteryOptimisation()
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

    public UpnpClient getUpnpClient() {
        return upnpClient;
    }
}
