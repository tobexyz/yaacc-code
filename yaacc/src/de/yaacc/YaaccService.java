package de.yaacc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class YaaccService extends Service {
    public YaaccService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        return START_STICKY;
    }

}
