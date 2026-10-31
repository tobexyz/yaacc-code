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

import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>
 * <b>Events never hold up a command.</b> Actions run under this manager's lock (Cling's
 * {@code AbstractActionExecutor}), and a command that cannot get the lock within the timeout fails with a UPnP
 * error: the control point logs it and the command is lost. Cling's {@code fireLastChange()} takes that lock
 * and delivers to the subscriptions while holding it, so one slow or blocked subscriber (a subscription's
 * methods are {@code synchronized} and some of them need the manager lock themselves) stopped every command.
 * Here the events are sent from a background thread that takes <em>no</em> manager lock: it takes what changed
 * out of the {@code LastChange} in one step (the monitor of the {@code LastChange}, held for microseconds, is
 * all it shares with a command) and then hands it to each subscriber on its own, a failing subscriber being
 * logged and skipped. Several changes in a row are sent together; two events are at least
 * {@link #MIN_EVENT_INTERVAL_MILLIS} apart (from the end of one to the start of the next), so nothing is sent at a pace a control point could not follow.
 */
public class AvTransportServiceManager extends LastChangeAwareServiceManager<YaaccAVTransportService> {

    /** Minimum time between two events (the {@code LastChange} is cleared by each one, a lost event is lost for good). */
    static final long MIN_EVENT_INTERVAL_MILLIS = 250;

    private final int lockTimeoutMillis;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean firePending = new AtomicBoolean();
    private volatile long lastFireEndNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(MIN_EVENT_INTERVAL_MILLIS);
    // set when the service instance is created; the event thread works with these and needs no lock for them
    private volatile LastChange eventedLastChange;
    private volatile PropertyChangeSupport eventedSupport;
    private volatile Object eventSource;

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
        // the one instance of this manager: its LastChange and the PropertyChangeSupport the subscriptions listen at
        eventedLastChange = service.getLastChange();
        eventedSupport = service.getPropertyChangeSupport();
        eventSource = service;
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
        schedule(lastFireEndNanos + TimeUnit.MILLISECONDS.toNanos(MIN_EVENT_INTERVAL_MILLIS) - System.nanoTime());
    }

    private void schedule(long delayNanos) {
        try {
            executor.schedule(this::fireNow, Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            firePending.set(false);
            YaaccLogger.w(getClass().getName(), "AVT event not scheduled", e);
        }
    }

    private void fireNow() {
        long waitNanos = lastFireEndNanos + TimeUnit.MILLISECONDS.toNanos(MIN_EVENT_INTERVAL_MILLIS) - System.nanoTime();
        if (waitNanos > 0) {
            schedule(waitNanos); // the previous event is too recent; the request stays pending
            return;
        }
        // cleared first: a change that comes in while firing schedules the next event
        firePending.set(false);
        try {
            fireLastChange();
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "AVT event not sent", e);
        } finally {
            lastFireEndNanos = System.nanoTime();
        }
    }

    /**
     * Sends everything that changed since the last event to the subscribers, without the manager lock (see the
     * class description). Does nothing when nothing changed.
     */
    @Override
    public void fireLastChange() {
        if (eventedLastChange == null) {
            getImplementation(); // creates the service instance, once
        }
        LastChange lastChange = eventedLastChange;
        String changes;
        Map<Long, String> playModes = new LinkedHashMap<>();
        synchronized (lastChange) { // the monitor of the LastChange: take and clear in one step, no change is lost in between
            try {
                changes = lastChange.toString();
                for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {
                    AVTransportVariable.CurrentPlayMode playMode =
                            lastChange.getEventedValue(instanceId, AVTransportVariable.CurrentPlayMode.class);
                    if (playMode != null) {
                        playModes.put(instanceId.getValue(), String.valueOf(playMode.getValue()));
                    }
                }
            } catch (RuntimeException e) {
                // a change that cannot be written as XML would be tried again by every later event: drop it
                lastChange.reset();
                throw e;
            }
            if (changes == null || changes.isEmpty()) {
                return;
            }
            lastChange.reset();
        }
        StateVariable<?> variable = getService().getStateVariable("LastChange");
        int subscribers = deliver(variable, changes);
        for (Map.Entry<Long, String> playMode : playModes.entrySet()) {
            YaaccLogger.d(getClass().getName(), "AVT event: CurrentPlayMode=" + playMode.getValue()
                    + " (instance " + playMode.getKey() + ") sent to " + subscribers + " subscribers");
        }
    }

    /** @return the number of subscribers the event was handed to */
    private int deliver(StateVariable<?> variable, String changes) {
        PropertyChangeSupport support = eventedSupport;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Collection<StateVariableValue> values = Collections.singletonList(new StateVariableValue(variable, changes));
        PropertyChangeEvent event = new PropertyChangeEvent(eventSource, ServiceManager.EVENTED_STATE_VARIABLES, null, values);
        int delivered = 0;
        // one by one, outside of any lock: PropertyChangeSupport.firePropertyChange would stop at the first listener that throws
        for (PropertyChangeListener listener : new ArrayList<>(java.util.Arrays.asList(support.getPropertyChangeListeners()))) {
            if (listener.getClass().getEnclosingClass() == DefaultServiceManager.class) {
                continue; // the manager's own listener of the plain state variables
            }
            try {
                listener.propertyChange(event);
                delivered++;
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "AVT event not accepted by a subscriber", e);
            }
        }
        return delivered;
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
