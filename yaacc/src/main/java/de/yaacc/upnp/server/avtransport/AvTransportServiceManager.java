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
package de.yaacc.upnp.server.avtransport;

import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager;

import java.util.Collection;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.yaacc.util.YaaccLogger;

/**
 * Service manager of the media renderer's AVTransport service. Compared to Cling's plain
 * {@code DefaultServiceManager} it
 * <ul>
 * <li>sends the full state of the service (all variables of the instance, not only what changed since the
 * start) to a new subscriber, as UPnP requires for the initial event, and</li>
 * <li>sends the {@code LastChange} to the subscribers after every change. Without that nothing was ever
 * sent after the initial event: a control point such as Home Assistant, which relies on events for
 * {@code CurrentPlayMode} once it is subscribed, never saw a repeat or shuffle change.</li>
 * </ul>
 * The events are sent from a background thread, not from the thread that changed something: a change can
 * come from a player on the main thread, and sending takes the lock of this manager, which an action in
 * progress holds. Several changes in a row are sent together; two events are at least
 * {@link #MIN_EVENT_INTERVAL_MILLIS} apart, so nothing is sent at a pace a control point could not follow.
 */
public class AvTransportServiceManager extends LastChangeAwareServiceManager<YaaccAVTransportService> {

    /** Minimum time between two events (the {@code LastChange} is cleared by each one, a lost event is lost for good). */
    static final long MIN_EVENT_INTERVAL_MILLIS = 250;

    private final int lockTimeoutMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean firePending = new AtomicBoolean();
    private volatile long lastFireNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(MIN_EVENT_INTERVAL_MILLIS);

    public AvTransportServiceManager(LocalService<YaaccAVTransportService> service, int lockTimeoutMillis) {
        this(service, lockTimeoutMillis, createExecutor());
    }

    AvTransportServiceManager(LocalService<YaaccAVTransportService> service, int lockTimeoutMillis,
                              ScheduledExecutorService executor) {
        super(service, new AVTransportLastChangeParser());
        this.lockTimeoutMillis = lockTimeoutMillis;
        this.executor = executor;
    }

    // one idle thread at most, and not even that when nothing is evented (the thread ends after 10 s)
    private static ScheduledExecutorService createExecutor() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "avtransport-events");
            thread.setDaemon(true);
            return thread;
        });
        pool.setKeepAliveTime(10, TimeUnit.SECONDS);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    protected int getLockTimeoutMillis() {
        return lockTimeoutMillis;
    }

    @Override
    protected YaaccAVTransportService createServiceInstance() {
        YaaccAVTransportService service = new YaaccAVTransportService();
        service.setLastChangeListener(this::requestFireLastChange);
        return service;
    }

    /**
     * Asks to send the collected {@code LastChange} to the subscribers soon. Does not block and may be called
     * from any thread, also while the LastChange is locked.
     */
    void requestFireLastChange() {
        if (!firePending.compareAndSet(false, true)) {
            return; // the pending event takes this change along
        }
        long waitNanos = lastFireNanos + TimeUnit.MILLISECONDS.toNanos(MIN_EVENT_INTERVAL_MILLIS) - System.nanoTime();
        try {
            executor.schedule(this::fireNow, Math.max(0, waitNanos), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            firePending.set(false);
            YaaccLogger.w(getClass().getName(), "AVT event not scheduled", e);
        }
    }

    private void fireNow() {
        // cleared first: a change that comes in while firing schedules the next event
        firePending.set(false);
        lastFireNanos = System.nanoTime();
        try {
            fireLastChange();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "AVT event not sent", e);
        }
    }

    @Override
    public void fireLastChange() {
        // same order of locks as the super class: this manager first, then the LastChange
        lock();
        try {
            LastChange lastChange = getImplementation().getLastChange();
            logPlayModeEvent(lastChange);
            lastChange.fire(getPropertyChangeSupport());
        } finally {
            unlock();
        }
    }

    private void logPlayModeEvent(LastChange lastChange) {
        for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {
            AVTransportVariable.CurrentPlayMode playMode =
                    lastChange.getEventedValue(instanceId, AVTransportVariable.CurrentPlayMode.class);
            if (playMode != null) {
                // every listener but our own is a subscription
                int subscribers = Math.max(0, getPropertyChangeSupport().getPropertyChangeListeners().length - 1);
                YaaccLogger.d(getClass().getName(), "AVT event: CurrentPlayMode=" + playMode.getValue()
                        + " (instance " + instanceId.getValue() + ") sent to " + subscribers + " subscribers");
            }
        }
    }

    @Override
    protected Collection<StateVariableValue> readInitialEventedStateVariableValues() throws Exception {
        Collection<StateVariableValue> values;
        try {
            values = super.readInitialEventedStateVariableValues();
        } catch (Exception e) {
            // the accumulated LastChange is better than a subscription that fails
            YaaccLogger.w(getClass().getName(), "Full state for the initial AVT event not readable, sending the collected changes", e);
            return null;
        }
        try {
            YaaccLogger.d(getClass().getName(), "AVT initial event for a new subscriber: CurrentPlayMode="
                    + getImplementation().getTransportSettings(YaaccAVTransportService.getDefaultInstanceID()).getPlayMode());
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "AVT initial event for a new subscriber", e);
        }
        return values;
    }
}
