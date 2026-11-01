package de.yaacc.browser;

import android.os.AsyncTask;
import android.os.SystemClock;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

/**
 * Reads a receiver's volume once, when its row is bound, and shows it through
 * {@link ReceiverRowBinder#showVolume}, which drops it while the user is touching the slider. Later
 * changes are read by {@link ReceiverVolumeLiveUpdater}. The slider's listener lives in
 * {@link ReceiverRowBinder}.
 */
public class DeviceVolumeStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Integer> {
    private final ReceiverRowBinder.RowViews views;
    private final ReceiverRowBinder binder;
    private final UpnpClient upnpClient;
    // The row shows whatever the receiver says at the time of the read; a user action after this wins.
    private final long startedAt = SystemClock.elapsedRealtime();
    private Device<?, ?, ?> device;

    DeviceVolumeStateLoadTask(ReceiverRowBinder.RowViews views, ReceiverRowBinder binder, UpnpClient upnpClient) {
        this.views = views;
        this.binder = binder;
        this.upnpClient = upnpClient;
    }

    @Override
    protected Integer doInBackground(Device<?, ?, ?>... devices) {
        if (devices == null || devices.length < 1) {
            return -1;
        }
        device = devices[0];
        if (!upnpClient.hasActionGetVolume(device)) {
            return -1;
        }
        return upnpClient.getVolume(device);
    }

    @Override
    protected void onPostExecute(Integer result) {
        if (device == null) {
            return;
        }
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        if (!ReceiverRowBinder.isBoundTo(views, deviceId)) {
            return; // the row was recycled for another device meanwhile
        }
        if (result == -1) {
            views.volume.setEnabled(false);
            return;
        }
        binder.showVolume(views, deviceId, startedAt, result);
    }
}
