/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.renderingcontrol.GetMute;
import de.yaacc.upnp.callback.renderingcontrol.GetVolume;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.renderingcontrol.SystemVolume;
import de.yaacc.yxc.YxcVolumeAccess;
import de.yaacc.util.YaaccLogger;

/**
 * Keeps the volume slider and the mute box of the receiver rows that are on screen current while a host
 * (the device sheet, the Receiver tab) is visible. The host calls {@link #start()} and {@link #stop()}
 * with its visibility and {@link #tick()} from its 2 s poll. Thin Android part; what is read when and
 * when a read is dropped is decided by {@link ReceiverStateRefreshPlan}.
 * <ul>
 *   <li><b>This phone</b> (the local row and yaacc's own renderer): the value is the system's music stream
 *   ({@link SystemVolume}). A change through the app or through the RenderingControl service (Home
 *   Assistant) tells {@link SystemVolume}'s listener, which shows it at once; the hardware keys are only
 *   broadcast while a controller is subscribed, so every tick reads the stream again (an
 *   {@code AudioManager} call).</li>
 *   <li><b>Devices with group support</b>: read through their own interface ({@link YxcVolumeAccess}), the same one that
 *   writes the volume; the UPnP actions of a member of a group do not follow its volume.</li>
 *   <li><b>Other receivers</b>: {@code GetVolume} / {@code GetMute} as plain SOAP actions, not events (a
 *   callback address is often unreachable, for example behind a proxy). One request per receiver at a
 *   time, off the main thread on a small pool so one slow receiver does not hold up the others, and a
 *   receiver without the actions is not asked.</li>
 * </ul>
 * The change listener and the pool exist only between {@code start} and {@code stop}; late answers are
 * dropped. Main thread only, except the {@link SystemVolume} listener, which only posts to the main
 * thread.
 */
final class ReceiverVolumeLiveUpdater {
    private static final String TAG = ReceiverVolumeLiveUpdater.class.getName();
    private static final int PARALLEL_READS = 3;

    /** The rows currently on screen, implemented by the hosting adapter. */
    interface RowSource {
        /** Receiver rows that are laid out right now, by device. Main thread. */
        Map<Device<?, ?, ?>, ReceiverRowBinder.RowViews> visibleRows();
    }

    /** What one read of a receiver brought; a null value was not read (not supported, or it failed). */
    private static final class Reading {
        Integer volume;
        Boolean mute;
        boolean succeeded;
    }

    private final ReceiverRowBinder binder;
    private final UpnpClient upnpClient;
    private final RowSource rows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Called on whatever thread changed the volume (the RenderingControl service's, for Home Assistant).
    private final Runnable systemVolumeChanged;

    private boolean running;
    // Bumped on every start and stop: an answer from an earlier run is dropped.
    private int generation;
    private ExecutorService readers;

    ReceiverVolumeLiveUpdater(ReceiverRowBinder binder, UpnpClient upnpClient, RowSource rows) {
        this.binder = binder;
        this.upnpClient = upnpClient;
        this.rows = rows;
        this.systemVolumeChanged = () -> handler.post(() -> {
            if (this.running) {
                showLocalRows(this.rows.visibleRows());
            }
        });
    }

    /** Starts listening to the system volume and shows it at once (rows kept their old value while stopped). */
    void start() {
        if (running) {
            return;
        }
        running = true;
        generation++;
        binder.refreshPlan().reset();
        readers = Executors.newFixedThreadPool(PARALLEL_READS, runnable -> {
            Thread thread = new Thread(runnable, "ReceiverVolumeRead");
            thread.setDaemon(true);
            return thread;
        });
        SystemVolume.addChangeListener(systemVolumeChanged);
        showLocalRows(rows.visibleRows());
    }

    /** Removes the listener, stops the reads and forgets what is pending. Safe to call twice. */
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        generation++;
        SystemVolume.removeChangeListener(systemVolumeChanged);
        handler.removeCallbacksAndMessages(null);
        if (readers != null) {
            readers.shutdownNow();
            readers = null;
        }
        binder.refreshPlan().reset();
    }

    /** One poll cycle: the local rows from the stream, and every remote receiver that is due. */
    void tick() {
        if (!running) {
            return;
        }
        Map<Device<?, ?, ?>, ReceiverRowBinder.RowViews> visible = rows.visibleRows();
        showLocalRows(visible);

        Map<String, Device<?, ?, ?>> candidates = new LinkedHashMap<>();
        for (Device<?, ?, ?> device : visible.keySet()) {
            if (!ReceiverRowBinder.isLocalDevice(device)
                    && (binder.supportsVolumeControl(device) || binder.supportsMuteControl(device))) {
                candidates.put(device.getIdentity().getUdn().getIdentifierString(), device);
            }
        }
        long now = SystemClock.elapsedRealtime();
        List<String> due = binder.refreshPlan().dueForRefresh(candidates.keySet(), now);
        for (String id : due) {
            startRead(id, candidates.get(id), now);
        }
    }

    private void showLocalRows(Map<Device<?, ?, ?>, ReceiverRowBinder.RowViews> visible) {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Device<?, ?, ?>, ReceiverRowBinder.RowViews> row : visible.entrySet()) {
            Device<?, ?, ?> device = row.getKey();
            if (ReceiverRowBinder.isLocalDevice(device)) {
                String id = device.getIdentity().getUdn().getIdentifierString();
                binder.showVolume(row.getValue(), id, now, upnpClient.getVolume());
                binder.showMute(row.getValue(), id, now, upnpClient.isMute());
            }
        }
    }

    private void startRead(String id, Device<?, ?, ?> device, long startedAt) {
        int readGeneration = generation;
        boolean wantVolume = binder.supportsVolumeControl(device);
        boolean wantMute = binder.supportsMuteControl(device);
        ExecutorService pool = readers;
        pool.execute(() -> {
            Reading reading = read(id, device, wantVolume, wantMute);
            handler.post(() -> onRead(id, startedAt, readGeneration, reading));
        });
    }

    /** Runs on a pool thread: the two actions one after the other, so a receiver gets one request at a time. */
    private Reading read(String id, Device<?, ?, ?> device, boolean wantVolume, boolean wantMute) {
        Reading reading = new Reading();
        YxcVolumeAccess yxc = YxcVolumeAccess.getShared();
        if (yxc.handles(id)) {
            // a device with group support: its own interface, as for writing; the UPnP RenderingControl of a device that
            // is a member of a group does not follow its volume, so reading it put a wrong value back on the slider
            int percent = yxc.readPercent(id);
            reading.volume = percent < 0 ? null : percent;
            reading.mute = yxc.readMute(id);
            reading.succeeded = reading.volume != null || reading.mute != null;
            return reading;
        }
        HttpRequestSender sender = upnpClient.getHttpRequestSender();
        Service<?, ?> renderingControl = device.findService(new UDAServiceId("RenderingControl"));
        if (sender == null || renderingControl == null) {
            return reading;
        }
        try {
            if (wantVolume) {
                new GetVolume(renderingControl, sender) {
                    @Override
                    public void received(ActionInvocation invocation, int currentVolume) {
                        reading.volume = currentVolume;
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        YaaccLogger.d(TAG, "GetVolume failed: " + msg);
                    }
                }.run();
            }
            if (wantMute && !Thread.currentThread().isInterrupted()) {
                new GetMute(renderingControl, sender) {
                    @Override
                    public void received(ActionInvocation invocation, boolean currentMute) {
                        reading.mute = currentMute;
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        YaaccLogger.d(TAG, "GetMute failed: " + msg);
                    }
                }.run();
            }
        } catch (RuntimeException e) {
            // a receiver's odd answer must not kill the pool thread; the read counts as failed
            YaaccLogger.w(TAG, "Reading volume and mute failed", e);
        }
        reading.succeeded = reading.volume != null || reading.mute != null;
        return reading;
    }

    private void onRead(String id, long startedAt, int readGeneration, Reading reading) {
        if (!running || readGeneration != generation) {
            return; // stopped (or restarted) since the read was started
        }
        long now = SystemClock.elapsedRealtime();
        ReceiverStateRefreshPlan plan = binder.refreshPlan();
        if (!reading.succeeded) {
            plan.refreshFailed(id, now);
            return;
        }
        plan.refreshSucceeded(id, now);
        for (Map.Entry<Device<?, ?, ?>, ReceiverRowBinder.RowViews> row : rows.visibleRows().entrySet()) {
            if (id.equals(row.getKey().getIdentity().getUdn().getIdentifierString())) {
                if (reading.volume != null) {
                    binder.showVolume(row.getValue(), id, startedAt, reading.volume);
                }
                if (reading.mute != null) {
                    binder.showMute(row.getValue(), id, startedAt, reading.mute);
                }
                break;
            }
        }
    }
}
