package de.yaacc.browser;

import android.os.AsyncTask;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class ControlPlayerTask extends AsyncTask<Integer, Void,Void> {
    private final Device device;
    private final UpnpClient upnpClient;

    public ControlPlayerTask(UpnpClient upnpClient, Device device){

        this.upnpClient = upnpClient;
        this.device = device;
    }
    @Override
    public Void doInBackground(Integer... integers){
        upnpClient.controlDevice(device);
        return null;
    }
}
