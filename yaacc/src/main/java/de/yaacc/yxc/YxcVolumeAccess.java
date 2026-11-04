/*
 *
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
package de.yaacc.yxc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import de.yaacc.util.YaaccLogger;

/**
 * Volume and mute of the devices that speak the protocol, through their own interface. The generic UPnP volume
 * control of such a device does not change the volume while the device is in a group (measured by the owner), the
 * device's own {@code setVolume} does, and it changes only that device. The row's slider is a percentage: it maps onto
 * the device's own scale from 0 to its {@code max_volume}.
 * <p>
 * Reads block (they run on the reader threads of the rows). Writes return at once: the last value of a device wins
 * while an earlier one is still on its way, so a drag on the slider does not queue up requests.
 */
public final class YxcVolumeAccess {
    /** Where the clients of the devices come from; the monitor in the app. */
    public interface ClientSource {
        /** @return a client for the device, {@code null} when it does not speak the protocol or is not known */
        YxcClient clientFor(String udn);
    }

    /** The last known state of a device, without any network access. */
    public interface StateSource {
        /** @return the last state, {@code null} when the device is not known */
        YxcDeviceState stateOf(String udn);
    }

    private static final int MAX_REMEMBERED = 64;
    /** Writes of different devices run side by side, so one that does not answer does not hold the others up. */
    private static final int WRITER_THREADS = 4;

    private static YxcVolumeAccess shared;

    private final ClientSource clients;
    private final StateSource states;
    private final BooleanSupplier onMainThread;
    private final Executor writer;
    // the scale of each device, learned from the last read; guarded by this
    private final Map<String, Integer> maxVolumes = new LinkedHashMap<>();
    private final Map<String, Integer> pendingPercent = new HashMap<>();
    private final Map<String, Boolean> pendingMute = new HashMap<>();

    /**
     * @param states       where the last known state comes from, used instead of a read on the main thread
     * @param onMainThread whether the calling thread is the main thread, where no network access is allowed (the
     *                     media session asks the player for its volume there)
     */
    public YxcVolumeAccess(ClientSource clients, StateSource states, BooleanSupplier onMainThread, Executor writer) {
        this.clients = clients;
        this.states = states;
        this.onMainThread = onMainThread;
        this.writer = writer;
    }

    /** The access of the app, on the shared monitor. */
    public static synchronized YxcVolumeAccess getShared() {
        if (shared == null) {
            YxcMonitor monitor = YxcMonitor.getShared();
            shared = new YxcVolumeAccess(monitor::newClient, monitor::getState,
                    () -> android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                    Executors.newFixedThreadPool(WRITER_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "yxc-volume");
                thread.setDaemon(true);
                return thread;
            }));
        }
        return shared;
    }

    /** The client of the device; {@code null} for an unknown device and for one whose client can not be made. */
    private YxcClient clientOrNull(String udn) {
        try {
            return clients.clientFor(udn);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return whether the device speaks the protocol (so that the row uses this instead of the UPnP controls) */
    public boolean handles(String udn) {
        return udn != null && clientOrNull(udn) != null;
    }

    /**
     * @return the volume as a percentage of the device's scale, -1 if it can not be read. On the main thread nothing is
     * read from the network: the last known state answers.
     */
    public int readPercent(String udn) {
        if (onMainThread.getAsBoolean()) {
            YxcDeviceState state = states.stateOf(udn);
            if (state == null || state.getVolume() == null) {
                return -1;
            }
            int max = state.getMaxVolume() == null || state.getMaxVolume() <= 0 ? 100 : state.getMaxVolume();
            return toPercent(state.getVolume(), max);
        }
        YxcClient client = clientOrNull(udn);
        if (client == null) {
            return -1;
        }
        try {
            YxcZoneStatus status = client.getZoneStatus(YxcDeviceState.MAIN_ZONE);
            if (status.getVolume() == null) {
                return -1;
            }
            int max = status.getMaxVolume() == null || status.getMaxVolume() <= 0 ? 100 : status.getMaxVolume();
            remember(udn, max);
            return toPercent(status.getVolume(), max);
        } catch (YxcException e) {
            YaaccLogger.d(getClass().getName(), "Volume not readable: " + e.getMessage());
            return -1;
        }
    }

    /** @return the mute state, {@code null} if it can not be read; on the main thread the last known state answers */
    public Boolean readMute(String udn) {
        if (onMainThread.getAsBoolean()) {
            YxcDeviceState state = states.stateOf(udn);
            return state == null ? null : state.getMute();
        }
        YxcClient client = clientOrNull(udn);
        if (client == null) {
            return null;
        }
        try {
            return client.getZoneStatus(YxcDeviceState.MAIN_ZONE).getMute();
        } catch (YxcException e) {
            return null;
        }
    }

    /** Sets the volume from a percentage; returns at once, the latest value of a device is the one that is sent. */
    public void setPercent(String udn, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        boolean start;
        synchronized (this) {
            start = !pendingPercent.containsKey(udn);
            pendingPercent.put(udn, clamped);
        }
        if (start) {
            writer.execute(() -> sendVolume(udn));
        }
    }

    /** Sets the mute state; returns at once. */
    public void setMute(String udn, boolean muted) {
        boolean start;
        synchronized (this) {
            start = !pendingMute.containsKey(udn);
            pendingMute.put(udn, muted);
        }
        if (start) {
            writer.execute(() -> sendMute(udn));
        }
    }

    private void sendVolume(String udn) {
        boolean finished = false;
        try {
            sendVolumeLoop(udn);
            finished = true;
        } finally {
            if (!finished) {
                synchronized (this) {
                    pendingPercent.remove(udn); // an unexpected failure: the next write must start a new task
                }
            }
        }
    }

    private void sendVolumeLoop(String udn) {
        while (true) {
            int percent;
            synchronized (this) {
                Integer next = pendingPercent.get(udn);
                if (next == null) {
                    return;
                }
                percent = next;
            }
            try {
                YxcClient client = clientOrNull(udn);
                if (client != null) {
                    client.setVolume(YxcDeviceState.MAIN_ZONE, fromPercent(percent, scaleOf(udn, client)));
                }
            } catch (YxcException e) {
                YaaccLogger.d(getClass().getName(), "Volume not set: " + e.getMessage());
            }
            synchronized (this) {
                // a newer value arrived meanwhile: send that one too, otherwise this device is done
                if (pendingPercent.get(udn) == null || pendingPercent.get(udn) == percent) {
                    pendingPercent.remove(udn);
                    return;
                }
            }
        }
    }

    private void sendMute(String udn) {
        boolean finished = false;
        try {
            sendMuteLoop(udn);
            finished = true;
        } finally {
            if (!finished) {
                synchronized (this) {
                    pendingMute.remove(udn);
                }
            }
        }
    }

    private void sendMuteLoop(String udn) {
        while (true) {
            boolean muted;
            synchronized (this) {
                Boolean next = pendingMute.get(udn);
                if (next == null) {
                    return;
                }
                muted = next;
            }
            try {
                YxcClient client = clientOrNull(udn);
                if (client != null) {
                    client.setMute(YxcDeviceState.MAIN_ZONE, muted);
                }
            } catch (YxcException e) {
                YaaccLogger.d(getClass().getName(), "Mute not set: " + e.getMessage());
            }
            synchronized (this) {
                if (pendingMute.get(udn) == null || pendingMute.get(udn) == muted) {
                    pendingMute.remove(udn);
                    return;
                }
            }
        }
    }

    /** The device's {@code max_volume}: from the last read, else read now; 100 when the device does not say. */
    private int scaleOf(String udn, YxcClient client) throws YxcException {
        synchronized (this) {
            Integer known = maxVolumes.get(udn);
            if (known != null) {
                return known;
            }
        }
        YxcZoneStatus status = client.getZoneStatus(YxcDeviceState.MAIN_ZONE);
        int max = status.getMaxVolume() == null || status.getMaxVolume() <= 0 ? 100 : status.getMaxVolume();
        remember(udn, max);
        return max;
    }

    private synchronized void remember(String udn, int max) {
        if (maxVolumes.size() >= MAX_REMEMBERED && !maxVolumes.containsKey(udn)) {
            maxVolumes.remove(maxVolumes.keySet().iterator().next());
        }
        maxVolumes.put(udn, max);
    }

    /** The slider's percentage for a value of the device's scale. */
    static int toPercent(int volume, int max) {
        return (int) Math.max(0, Math.min(100, Math.round(volume * 100d / max)));
    }

    /** The device's value for the slider's percentage. */
    static int fromPercent(int percent, int max) {
        return (int) Math.max(0, Math.min(max, Math.round(percent * (double) max / 100d)));
    }
}
