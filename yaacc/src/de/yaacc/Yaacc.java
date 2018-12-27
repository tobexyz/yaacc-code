package de.yaacc;

import android.app.Application;

import de.yaacc.upnp.UpnpClient;

public class Yaacc extends Application {
    private UpnpClient upnpClient;
    @Override
    public void onCreate() {
        super.onCreate();
        upnpClient = new UpnpClient(this);

    }

    public UpnpClient getUpnpClient() {
        return upnpClient;
    }
}
