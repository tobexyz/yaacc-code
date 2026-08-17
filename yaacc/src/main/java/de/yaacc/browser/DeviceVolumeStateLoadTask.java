package de.yaacc.browser;

import android.os.AsyncTask;
import android.widget.SeekBar;
import android.widget.TextView;

import org.fourthline.cling.model.meta.Device;

import de.yaacc.upnp.UpnpClient;

public class DeviceVolumeStateLoadTask extends AsyncTask<Device<?, ?, ?>, Integer, Integer> {
    SeekBar targetWidget;
    TextView volumeText;
    UpnpClient upnpClient;
    Device<?, ?, ?> device;
    private int lastVolume = -1;

    public DeviceVolumeStateLoadTask(SeekBar targetWidget, TextView volumeText, UpnpClient upnpClient) {
        this.targetWidget = targetWidget;
        this.volumeText = volumeText;
        this.upnpClient = upnpClient;
    }

    public DeviceVolumeStateLoadTask(SeekBar targetWidget, UpnpClient upnpClient) {
        this(targetWidget, null, upnpClient);
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
        if (result == -1) {
            targetWidget.setEnabled(false);
            return;
        }
        targetWidget.setEnabled(true);
        // Restore alpha to full if we successfully got the volume
        // (may have been dimmed if the initial supportsVolumeControl() check timed out)
        targetWidget.setAlpha(1.0f);
        lastVolume = result;
        targetWidget.setProgress(result);
        // Update percentage text
        if (volumeText != null) {
            volumeText.setText(result + "%");
        }
        
        // Fix #2: Prevent ViewPager2 from intercepting during slider interaction
        // Using SeekBar's built-in touch tracking callbacks instead of manual motion events
        targetWidget.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Fix #1: Only update if value actually changed AND from user interaction
                if (fromUser && progress != lastVolume) {
                    lastVolume = progress;
                    // Update percentage text in real-time
                    if (volumeText != null) {
                        volumeText.setText(progress + "%");
                    }
                    upnpClient.setVolume(device, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Prevent ViewPager2 interception during drag
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Allow ViewPager2 interception again
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });

    }
}
