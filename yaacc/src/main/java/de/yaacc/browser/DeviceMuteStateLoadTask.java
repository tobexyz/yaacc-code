package de.yaacc.browser;

import android.os.AsyncTask;
import android.os.SystemClock;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

/**
 * Reads a receiver's mute state once, when its row is bound, and shows it through
 * {@link ReceiverRowBinder#showMute}, which drops it while the user is acting on the row. Later changes
 * are read by {@link ReceiverVolumeLiveUpdater}. The box's click listener lives in {@link ReceiverRowBinder}.
 */
public class DeviceMuteStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Boolean> {
    private final ReceiverRowBinder.RowViews views;
    private final ReceiverRowBinder binder;
    private final UpnpClient upnpClient;
    private final long startedAt = SystemClock.elapsedRealtime();
    private Device<?, ?, ?> device;

    DeviceMuteStateLoadTask(ReceiverRowBinder.RowViews views, ReceiverRowBinder binder, UpnpClient upnpClient) {
        this.views = views;
        this.binder = binder;
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
        if (device == null) {
            return;
        }
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        if (!ReceiverRowBinder.isBoundTo(views, deviceId)) {
            return; // the row was recycled for another device meanwhile
        }
        if (result == null) {
            views.mute.setChecked(false);
            views.mute.setEnabled(false);
            return;
        }
        binder.showMute(views, deviceId, startedAt, result);
    }
}
