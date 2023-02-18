package de.yaacc.browser;

import android.os.AsyncTask;
import android.widget.SeekBar;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class DeviceVolumeStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Integer> {
    SeekBar targetWidget;
    UpnpClient upnpClient;

    public DeviceVolumeStateLoadTask(SeekBar targetWidget, UpnpClient upnpClient) {
        this.targetWidget = targetWidget;
        this.upnpClient = upnpClient;
    }

    @Override
    protected Integer doInBackground(Device<?, ?, ?>... devices) {
        if (devices == null || devices.length < 1) {
            return 0;
        }
        return upnpClient.getVolume(devices[0]);
    }

    @Override
    protected void onPostExecute(Integer result) {
        targetWidget.setProgress(result);
    }
}
