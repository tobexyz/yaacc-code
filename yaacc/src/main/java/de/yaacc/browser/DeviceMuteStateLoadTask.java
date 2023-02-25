package de.yaacc.browser;

import android.os.AsyncTask;
import android.widget.CheckBox;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class DeviceMuteStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Boolean> {
    CheckBox targetWidget;
    UpnpClient upnpClient;
    Device<?, ?, ?> device;

    public DeviceMuteStateLoadTask(CheckBox targetWidget, UpnpClient upnpClient) {
        this.targetWidget = targetWidget;
        this.upnpClient = upnpClient;
    }

    @Override
    protected Boolean doInBackground(Device<?, ?, ?>... devices) {
        if (devices == null || devices.length < 1) {
            return false;
        }
        device = devices[0];
        return upnpClient.getMute(device);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        targetWidget.setChecked(result);
        targetWidget.setOnClickListener((it) -> {
            upnpClient.setMute(device, targetWidget.isChecked());
        });
    }
}
