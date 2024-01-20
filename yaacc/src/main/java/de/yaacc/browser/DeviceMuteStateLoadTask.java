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
            return null;
        }
        device = devices[0];
        if (!upnpClient.hasActionGetMute(device)) {
            return null;
        }
        return upnpClient.getMute(device);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (result == null) {
            targetWidget.setChecked(false);
            targetWidget.setEnabled(false);
            return;
        }
        targetWidget.setEnabled(true);
        targetWidget.setChecked(result);
        targetWidget.setOnClickListener((it) -> {
            upnpClient.setMute(device, targetWidget.isChecked());
        });
    }
}
