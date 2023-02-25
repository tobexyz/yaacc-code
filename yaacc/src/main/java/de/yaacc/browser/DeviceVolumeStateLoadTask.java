package de.yaacc.browser;

import android.os.AsyncTask;
import android.widget.SeekBar;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class DeviceVolumeStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Integer> {
    SeekBar targetWidget;
    UpnpClient upnpClient;
    Device<?, ?, ?> device;

    public DeviceVolumeStateLoadTask(SeekBar targetWidget, UpnpClient upnpClient) {
        this.targetWidget = targetWidget;
        this.upnpClient = upnpClient;
    }

    @Override
    protected Integer doInBackground(Device<?, ?, ?>... devices) {
        if (devices == null || devices.length < 1) {
            return 0;
        }
        device = devices[0];
        return upnpClient.getVolume(device);
    }

    @Override
    protected void onPostExecute(Integer result) {
        targetWidget.setProgress(result);
        targetWidget.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                upnpClient.setVolume(device, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }
}
