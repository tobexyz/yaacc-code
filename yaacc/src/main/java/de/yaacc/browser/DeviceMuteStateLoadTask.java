package de.yaacc.browser;

import android.os.AsyncTask;
import android.widget.CheckBox;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class DeviceMuteStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Boolean> {
    CheckBox targetWidget;
    UpnpClient upnpClient;

    public DeviceMuteStateLoadTask(CheckBox targetWidget, UpnpClient upnpClient) {
        this.targetWidget = targetWidget;
        this.upnpClient = upnpClient;
    }

    @Override
    protected Boolean doInBackground(Device<?, ?, ?>... devices) {
        if (devices == null || devices.length < 1) {
            return false;
        }
        return upnpClient.getMute(devices[0]);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        targetWidget.setChecked(result);
    }
}
