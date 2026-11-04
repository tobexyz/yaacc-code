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

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import de.yaacc.util.YaaccLogger;

/**
 * Keeps the {@link YxcDeviceState} of the devices that speak the protocol up to date for the receiver row.
 * <p>
 * <b>Lifetime.</b> Everything runs only while somebody is subscribed (a sheet or the receiver tab on screen). The
 * UDP event source is opened with the first subscriber and closed {@link #STOP_DELAY_MILLIS} after the last one
 * left, so switching between the tab and the sheet does not close and reopen it; polling stops at the same time.
 * <p>
 * <b>Reading.</b> A device is first looked at by {@link YxcDiscovery} (once per UDN); only devices that speak the
 * protocol are ever talked to again. Every {@link #POLL_INTERVAL_MILLIS} all of them are read completely: that is
 * the fallback when events do not arrive (a firewall, a device that dropped the registration) and it renews the
 * registration, which every request carries. Events are applied at once when they carry the value (power, volume,
 * mute, input); flags that only say "changed" ask for a read of the matching call, after
 * {@link #DEBOUNCE_MILLIS} without a further such event, so a burst of five datagrams makes one read.
 * <p>
 * <b>Trust.</b> An event counts only when it comes from the address of a device found by our own discovery and,
 * if it names a {@code device_id}, the one of that device. Everything else is dropped.
 * <p>
 * <b>Threads.</b> The listeners are called on a worker or scheduler thread, never with the lock held; a listener
 * that touches views posts to the main thread. The clock, the threads and the network are injected so the
 * behavior is tested on the JVM without waiting.
 */
public final class YxcMonitor {
    public static final long STOP_DELAY_MILLIS = 30_000L;
    public static final long POLL_INTERVAL_MILLIS = 30_000L;
    public static final long DEBOUNCE_MILLIS = 500L;
    /** The most devices watched; a household has a handful. */
    static final int MAX_DEVICES = 64;

    public interface Listener {
        /** The state of the device changed (also: it became unreachable). */
        void onStateChanged(String udn, YxcDeviceState state);
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);
    }

    public interface TransportFactory {
        /** @param eventPort the UDP port events should go to, 0 when events are not available */
        YxcTransport create(YxcEndpoint endpoint, int eventPort);
    }

    private static final class Watched {
        final String udn;
        String descriptorUrl;
        YxcEndpoint endpoint;
        YxcClient client;
        YxcDeviceState state = YxcDeviceState.unknown();
        final Set<YxcEventPlan.Refresh> pending = EnumSet.noneOf(YxcEventPlan.Refresh.class);
        Cancellable debounce;
        boolean reading;

        Watched(String udn, String descriptorUrl) {
            this.udn = udn;
            this.descriptorUrl = descriptorUrl;
        }
    }

    private static final class Notification {
        final String udn;
        final YxcDeviceState state;

        Notification(String udn, YxcDeviceState state) {
            this.udn = udn;
            this.state = state;
        }
    }

    private final YxcDiscovery discovery;
    private final YxcEventSource.Factory sourceFactory;
    private final TransportFactory transports;
    private final Executor io;
    private final Scheduler scheduler;

    // all below guarded by "this"
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Watched> watched = new LinkedHashMap<>();
    private boolean active;
    private int generation;
    private YxcEventSource source;
    private Cancellable stopTask;
    private int stopSequence;
    private Cancellable pollTask;

    private static YxcMonitor shared;

    public YxcMonitor(YxcDiscovery discovery, YxcEventSource.Factory sourceFactory, TransportFactory transports,
                      Executor io, Scheduler scheduler) {
        this.discovery = discovery;
        this.sourceFactory = sourceFactory;
        this.transports = transports;
        this.io = io;
        this.scheduler = scheduler;
    }

    /** The app-wide monitor with the real network, threads and clock. */
    public static synchronized YxcMonitor getShared() {
        if (shared == null) {
            ThreadFactory daemon = runnable -> {
                Thread thread = new Thread(runnable, "yxc-monitor");
                thread.setDaemon(true);
                return thread;
            };
            ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, daemon);
            timer.setRemoveOnCancelPolicy(true); // a cancelled debounce does not stay queued for its delay
            shared = new YxcMonitor(new YxcDiscovery(new YxcHttpDescriptionFetcher()), YxcEventListener::start,
                    (endpoint, port) -> new YxcHttpTransport(endpoint.getHost(), endpoint.getPort(), port,
                            YxcHttpTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS, YxcHttpTransport.DEFAULT_READ_TIMEOUT_MILLIS),
                    Executors.newFixedThreadPool(4, daemon),
                    (task, delay) -> {
                        ScheduledFuture<?> future = timer.schedule(task, delay, TimeUnit.MILLISECONDS);
                        return () -> future.cancel(false);
                    });
        }
        return shared;
    }

    /**
     * Registers the listener and watches the devices. Devices already watched are not read again at once, new
     * ones are; if the monitor had stopped, everything is read again (events were missed).
     *
     * @param devices UDN to the URL the registry fetched its description from
     */
    public void subscribe(Listener listener, Map<String, String> devices) {
        synchronized (this) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
            if (stopTask != null) {
                stopTask.cancel();
                stopTask = null;
            }
            stopSequence++; // a stop that is already running is out of date
            boolean starting = !active;
            if (starting) {
                startLocked();
            }
            for (Map.Entry<String, String> device : devices.entrySet()) {
                if (device.getKey() == null || device.getValue() == null) {
                    continue;
                }
                Watched known = watched.get(device.getKey());
                if (known == null) {
                    if (watched.size() >= MAX_DEVICES) {
                        continue;
                    }
                    known = new Watched(device.getKey(), device.getValue());
                    watched.put(device.getKey(), known);
                    requestLocked(known, EnumSet.allOf(YxcEventPlan.Refresh.class));
                } else if (!known.descriptorUrl.equals(device.getValue())) {
                    known.descriptorUrl = device.getValue();
                    known.endpoint = null;
                    known.client = null;
                    requestLocked(known, EnumSet.allOf(YxcEventPlan.Refresh.class));
                }
            }
        }
    }

    /** Removes the listener; the last one leaving stops the monitor after {@link #STOP_DELAY_MILLIS}. */
    public synchronized void unsubscribe(Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty() && active && stopTask == null) {
            final int sequence = ++stopSequence;
            stopTask = scheduler.schedule(() -> stopIfUnused(sequence), STOP_DELAY_MILLIS);
        }
    }

    /** @return the last state of a watched device that speaks the protocol, {@code null} for any other */
    public synchronized YxcDeviceState getState(String udn) {
        Watched device = watched.get(udn);
        return device == null || device.endpoint == null ? null : device.state;
    }

    /** @return whether the events are being received right now (for tests and diagnostics) */
    public synchronized boolean isActive() {
        return active;
    }

    /** Asks for a complete read of one device now, for example after the app changed something on it. */
    public synchronized void refreshNow(String udn) {
        Watched device = watched.get(udn);
        if (active && device != null) {
            requestLocked(device, EnumSet.allOf(YxcEventPlan.Refresh.class));
        }
    }

    // ---- lifetime

    private void startLocked() {
        active = true;
        generation++;
        try {
            source = sourceFactory.open(new YxcEventSource.Receiver() {
                @Override
                public boolean accepts(InetAddress from) {
                    return isWatchedAddress(from);
                }

                @Override
                public void onEvent(InetAddress from, YxcEvent event) {
                    YxcMonitor.this.onEvent(from, event);
                }
            });
        } catch (IOException | RuntimeException e) {
            // polling still works; the state is then up to 30 s old
            YaaccLogger.w(getClass().getName(), "Event source not available", e);
            source = null;
        }
        for (Watched device : watched.values()) {
            device.client = null; // the registration at the device carries the new port
            device.reading = false;
            device.pending.clear();
            requestLocked(device, EnumSet.allOf(YxcEventPlan.Refresh.class));
        }
        schedulePollLocked();
    }

    private synchronized void stopIfUnused(int sequence) {
        if (sequence != stopSequence) {
            return; // somebody subscribed since
        }
        stopTask = null;
        if (!listeners.isEmpty() || !active) {
            return;
        }
        active = false;
        generation++; // work in flight is dropped
        if (source != null) {
            source.close();
            source = null;
        }
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        for (Watched device : watched.values()) {
            if (device.debounce != null) {
                device.debounce.cancel();
                device.debounce = null;
            }
        }
    }

    private void schedulePollLocked() {
        final int poll = generation;
        pollTask = scheduler.schedule(() -> poll(poll), POLL_INTERVAL_MILLIS);
    }

    private synchronized void poll(int pollGeneration) {
        if (!active || pollGeneration != generation) {
            return;
        }
        for (Watched device : watched.values()) {
            requestLocked(device, EnumSet.allOf(YxcEventPlan.Refresh.class));
        }
        schedulePollLocked();
    }

    // ---- events

    private void onEvent(InetAddress from, YxcEvent event) {
        Notification notification = null;
        synchronized (this) {
            if (!active || from == null || event == null || !event.isParsable()) {
                return;
            }
            Watched device = deviceAt(from, event.getDeviceId());
            if (device == null) {
                return;
            }
            YxcDeviceState applied = device.state.withEvent(event);
            if (!applied.equals(device.state)) {
                // listeners hear of what the row shows; volume and mute only move the stored state
                notification = applied.sameForRow(device.state) ? null : new Notification(device.udn, applied);
                device.state = applied;
            }
            Set<YxcEventPlan.Refresh> refreshes = YxcEventPlan.refreshesFor(event);
            if (!refreshes.isEmpty()) {
                device.pending.addAll(refreshes);
                if (device.debounce != null) {
                    device.debounce.cancel();
                }
                final Watched target = device;
                final int flush = generation;
                device.debounce = scheduler.schedule(() -> flush(target, flush), DEBOUNCE_MILLIS);
            }
        }
        fire(notification);
    }

    private synchronized boolean isWatchedAddress(InetAddress from) {
        return active && from != null && deviceAt(from, null) != null;
    }

    private Watched deviceAt(InetAddress from, String eventDeviceId) {
        String address = from.getHostAddress();
        int scope = address.indexOf('%');
        if (scope >= 0) {
            address = address.substring(0, scope);
        }
        for (Watched device : watched.values()) {
            if (device.endpoint != null && device.endpoint.getHost().equalsIgnoreCase(address)
                    && device.endpoint.acceptsDeviceId(eventDeviceId)) {
                return device;
            }
        }
        return null;
    }

    private synchronized void flush(Watched device, int flushGeneration) {
        if (!active || flushGeneration != generation) {
            return;
        }
        device.debounce = null;
        requestLocked(device, EnumSet.noneOf(YxcEventPlan.Refresh.class));
    }

    // ---- reading

    private void requestLocked(Watched device, Set<YxcEventPlan.Refresh> what) {
        device.pending.addAll(what);
        if (device.reading || device.pending.isEmpty()) {
            return; // the running read takes the pending ones when it is done
        }
        device.reading = true;
        final int read = generation;
        io.execute(() -> readLoop(device, read));
    }

    private void readLoop(Watched device, int readGeneration) {
        try {
            readUntilDone(device, readGeneration);
        } catch (RuntimeException | StackOverflowError e) {
            // for example a host the transport refuses; without this the flag would stay set and the device would never be read again
            YaaccLogger.w(getClass().getName(), "Reading a device failed", e);
            Notification notification = null;
            synchronized (this) {
                if (active && readGeneration == generation) {
                    device.reading = false;
                    device.client = null;
                    YxcDeviceState gone = device.state.unreachable();
                    if (!gone.equals(device.state)) {
                        device.state = gone;
                        notification = new Notification(device.udn, gone);
                    }
                }
            }
            fire(notification);
        }
    }

    private void readUntilDone(Watched device, int readGeneration) {
        while (true) {
            Set<YxcEventPlan.Refresh> what;
            String url;
            YxcEndpoint endpoint;
            synchronized (this) {
                if (!active || readGeneration != generation) {
                    return; // the new generation owns the flag
                }
                what = EnumSet.copyOf(device.pending);
                device.pending.clear();
                url = device.descriptorUrl;
                endpoint = device.endpoint;
            }
            if (endpoint == null) {
                endpoint = discovery.find(device.udn, url);
            }
            YxcClient client;
            synchronized (this) {
                if (!active || readGeneration != generation) {
                    return;
                }
                if (!url.equals(device.descriptorUrl)) {
                    // the address changed while it was looked at: what was found belongs to the old one
                    device.pending.addAll(what);
                    continue;
                }
                if (endpoint == null) {
                    device.reading = false; // not a device of this kind, or not answering: asked again at the next poll
                    return;
                }
                if (device.endpoint == null || !device.endpoint.equals(endpoint)) {
                    device.endpoint = endpoint;
                    device.client = null;
                }
                if (device.client == null) {
                    device.client = new YxcClient(transports.create(endpoint, source == null ? 0 : source.getPort()));
                }
                client = device.client;
            }
            Read result = read(client, what);
            Notification notification = null;
            boolean again;
            synchronized (this) {
                if (!active || readGeneration != generation) {
                    return;
                }
                YxcDeviceState updated = result.applyTo(device.state);
                if (!updated.equals(device.state)) {
                    notification = updated.sameForRow(device.state) ? null : new Notification(device.udn, updated);
                    device.state = updated;
                }
                again = !device.pending.isEmpty(); // asked for more while reading
                if (!again) {
                    device.reading = false;
                }
            }
            fire(notification);
            if (!again) {
                return;
            }
        }
    }

    /** What one round of calls brought. */
    private static final class Read {
        YxcZoneStatus zone;
        YxcDistributionInfo distribution;
        YxcPlayInfo playInfo;
        boolean playInfoAsked;
        boolean playInfoMissing;
        boolean unreachable;

        YxcDeviceState applyTo(YxcDeviceState state) {
            if (unreachable) {
                return state.unreachable();
            }
            YxcDeviceState result = state;
            if (zone != null) {
                result = result.withZone(zone);
            }
            if (distribution != null) {
                result = result.withDistribution(distribution);
            }
            if (playInfoAsked) {
                result = result.withPlayInfo(playInfoMissing ? null : playInfo);
            }
            return result;
        }
    }

    private Read read(YxcClient client, Set<YxcEventPlan.Refresh> what) {
        Read result = new Read();
        try {
            if (what.contains(YxcEventPlan.Refresh.ZONE_STATUS)) {
                result.zone = client.getZoneStatus(YxcDeviceState.MAIN_ZONE);
            }
            if (what.contains(YxcEventPlan.Refresh.DIST_INFO)) {
                try {
                    result.distribution = client.getDistributionInfo();
                } catch (YxcException e) {
                    if (isTransportFailure(e)) {
                        throw e;
                    }
                    // a device without the group calls: no group, nothing else is wrong
                }
            }
            if (what.contains(YxcEventPlan.Refresh.PLAY_INFO)) {
                result.playInfoAsked = true;
                try {
                    result.playInfo = client.getPlayInfo();
                } catch (YxcException e) {
                    if (isTransportFailure(e)) {
                        throw e;
                    }
                    result.playInfoMissing = true; // the input has no play info
                }
            }
        } catch (YxcException e) {
            if (isTransportFailure(e)) {
                result.unreachable = true;
            } else {
                YaaccLogger.d(getClass().getName(), "Device answered with an error: " + e.getMessage());
            }
        }
        return result;
    }

    private static boolean isTransportFailure(YxcException e) {
        return e.getResponseCode() == YxcException.NONE && e.getHttpStatus() == YxcException.NONE;
    }

    private void fire(Notification notification) {
        if (notification == null) {
            return;
        }
        List<Listener> targets;
        synchronized (this) {
            targets = new ArrayList<>(listeners);
        }
        for (Listener listener : targets) {
            try {
                listener.onStateChanged(notification.udn, notification.state);
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "Listener failed", e);
            }
        }
    }
}
