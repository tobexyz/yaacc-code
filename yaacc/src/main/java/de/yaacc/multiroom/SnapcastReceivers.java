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
package de.yaacc.multiroom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import de.yaacc.multiroom.control.RetryScheduler;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The Snapcast groups as receivers: joins the {@link SnapcastDirectory} (which servers exist) with one
 * {@link SnapcastServerLink} per server (what each server says) and hands out {@link SnapcastGroupDevice}s. Pure Java;
 * the link factory, the scheduler and the labels (asked for each time they are needed, so a change of the language shows)
 * are injected.
 *
 * <ul>
 *   <li><b>Lazy connections.</b> A link is opened only for a server that is listed in the directory and only while a
 *   page is visible ({@link #pageStarted}), the server is {@link #retain retained} (a player uses it), or a selected
 *   receiver of it is {@link #wake woken}. Nothing connects to a server that is not listed, and the directory holds
 *   only addresses that passed the range filter or that the user typed in.</li>
 *   <li><b>Grace.</b> When the last page is hidden the links close after {@code closeGraceMillis} (so that switching
 *   between the tab and the sheet does not reconnect), except retained ones.</li>
 *   <li><b>Last known state.</b> The last status of a server is kept when its link is closed or down, and its groups
 *   stay listed marked as offline, so a stored selection survives a hidden page. A server that leaves the directory
 *   (its grace is over; a manual server never leaves) takes its groups with it; a group that is no longer in a fresh
 *   status disappears. At most {@value #MAX_GROUPS} groups are listed.</li>
 * </ul>
 * Thread safe; no lock is held while a link or a listener is called. The list of servers is read under the lock (a quiet
 * {@link SnapcastDirectory#snapshot snapshot}; the sweep of the expired ones happens before, outside the lock), so a
 * server that left is never reconciled from an out-of-date list.
 *
 * <p><b>Identity.</b> {@link #groups()} and {@link #findGroup} return the same {@link SnapcastGroupDevice} instance for
 * a group for as long as its name, offline flag, stream and number of members stay the same (the lists compare
 * devices by identity); a changed group gets a new instance, a vanished one is forgotten.
 * A stored selection of a group whose server is listed but has not answered yet is {@link #isPending pending}.
 */
public final class SnapcastReceivers implements SnapcastGroupSource {

    /** The most groups listed (the receiver rows watch at most 64 receivers). */
    public static final int MAX_GROUPS = 64;
    private static final long EXPIRY_TICK_MILLIS = 5_000L;

    /** Opens the link to a server (not started yet); {@code SnapcastSession} in the app. */
    public interface LinkFactory {
        SnapcastServerLink open(SnapcastEndpoint endpoint);
    }

    /** Told when the groups (or the offline state of one) may have changed; any thread, no lock held. */
    public interface ChangeListener {
        void onGroupsChanged();
    }

    private static final class View {
        SnapcastServerLink link;
        SnapcastServerLink.Listener linkListener;
        SnapcastServerStatus status;
        boolean online;
    }

    private final SnapcastDirectory directory;
    private final LinkFactory factory;
    private final RetryScheduler scheduler;
    private final Supplier<SnapcastGroupDevice.Labels> labels;
    private final long closeGraceMillis;
    private final Object lock = new Object();
    private final Map<SnapcastServerKey, View> views = new HashMap<>();
    private final Map<SnapcastServerKey, Integer> retained = new HashMap<>();
    private final Set<SnapcastServerKey> woken = new HashSet<>();
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    // the last device made for each group id (guarded by lock); see deviceLocked
    private final Map<String, SnapcastGroupDevice> cache = new HashMap<>();
    private int visible;
    private long closeGeneration;
    private boolean tickRunning;

    public SnapcastReceivers(SnapcastDirectory directory, LinkFactory factory, RetryScheduler scheduler,
                             Supplier<SnapcastGroupDevice.Labels> labels, long closeGraceMillis) {
        this.directory = directory;
        this.factory = factory;
        this.scheduler = scheduler;
        this.labels = labels;
        this.closeGraceMillis = closeGraceMillis;
        directory.addListener(this::onServersChanged);
    }

    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    // ---- lifetime

    /** A page that shows receivers became visible: connect to the listed servers (and retry the ones that are down). */
    public void pageStarted() {
        directory.expire(); // sweeps and tells the listeners here, outside the lock; the list is read under the lock below
        Work work = new Work();
        synchronized (lock) {
            List<SnapcastDirectory.Entry> entries = directory.snapshot();
            visible++;
            closeGeneration++; // a close that is waiting is out of date
            reconcileLocked(entries, true, work);
            if (!tickRunning) {
                tickRunning = true;
                scheduler.schedule(this::tick, EXPIRY_TICK_MILLIS);
            }
        }
        work.run();
    }

    /** A page was hidden; with the last one gone the links close after the grace. */
    public void pageStopped() {
        synchronized (lock) {
            if (visible == 0) {
                return;
            }
            visible--;
            if (visible > 0) {
                return;
            }
        }
        scheduleClose();
    }

    /** Keeps the link to a listed server open without a page (a player that feeds it); see {@link #release}. */
    public void retain(SnapcastServerKey key) {
        directory.expire();
        Work work = new Work();
        synchronized (lock) {
            List<SnapcastDirectory.Entry> entries = directory.snapshot();
            Integer count = retained.get(key);
            retained.put(key, count == null ? 1 : count + 1);
            reconcileLocked(entries, false, work);
        }
        work.run();
    }

    public void release(SnapcastServerKey key) {
        synchronized (lock) {
            Integer count = retained.get(key);
            if (count == null) {
                return;
            }
            if (count <= 1) {
                retained.remove(key);
            } else {
                retained.put(key, count - 1);
            }
        }
        scheduleClose();
    }

    @Override
    public void wake(Collection<String> receiverIds) {
        if (receiverIds == null) {
            return;
        }
        Set<SnapcastServerKey> wanted = new HashSet<>();
        for (String id : receiverIds) {
            SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
            if (parsed != null) {
                wanted.add(parsed.getServerKey());
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        directory.expire();
        Work work = new Work();
        boolean any = false;
        synchronized (lock) {
            List<SnapcastDirectory.Entry> entries = directory.snapshot();
            for (SnapcastDirectory.Entry entry : entries) {
                if (wanted.contains(entry.getKey())) {
                    woken.add(entry.getKey());
                    any = true;
                }
            }
            if (any) {
                reconcileLocked(entries, true, work);
            }
        }
        work.run();
        if (any) {
            scheduleClose();
        }
    }

    /** Closes every link and forgets everything. */
    public void shutdown() {
        Work work = new Work();
        synchronized (lock) {
            visible = 0;
            closeGeneration++;
            for (View view : views.values()) {
                work.detach(view);
            }
            views.clear();
            retained.clear();
            woken.clear();
            cache.clear();
        }
        work.run();
        fire();
    }

    /** The link of a server while it is open; null otherwise. For the code that controls the server (the player). */
    public SnapcastServerLink getLink(SnapcastServerKey key) {
        synchronized (lock) {
            View view = views.get(key);
            return view == null ? null : view.link;
        }
    }

    private void scheduleClose() {
        final long generation;
        synchronized (lock) {
            generation = ++closeGeneration;
        }
        scheduler.schedule(() -> closeIdle(generation), closeGraceMillis);
    }

    private void closeIdle(long generation) {
        Work work = new Work();
        boolean changed = false;
        synchronized (lock) {
            if (generation != closeGeneration || visible > 0) {
                return;
            }
            woken.clear();
            for (Map.Entry<SnapcastServerKey, View> entry : views.entrySet()) {
                View view = entry.getValue();
                if (view.link != null && !retained.containsKey(entry.getKey())) {
                    work.detach(view);
                    view.online = false;
                    changed = true;
                }
            }
        }
        work.run();
        if (changed) {
            fire();
        }
    }

    private void tick() {
        synchronized (lock) {
            if (visible == 0) {
                tickRunning = false;
                return;
            }
        }
        directory.expire();
        scheduler.schedule(this::tick, EXPIRY_TICK_MILLIS);
    }

    // ---- reconciling the links with the directory

    /** Links to open and links to close, done after the lock is released. */
    private static final class Work {
        final List<SnapcastServerLink> toStart = new ArrayList<>();
        final List<SnapcastServerLink> toRetry = new ArrayList<>();
        final List<SnapcastServerLink> toClose = new ArrayList<>();
        final List<Runnable> afterClose = new ArrayList<>();

        void detach(View view) {
            if (view.link != null) {
                final SnapcastServerLink link = view.link;
                final SnapcastServerLink.Listener listener = view.linkListener;
                toClose.add(link);
                afterClose.add(() -> link.removeListener(listener));
                view.link = null;
                view.linkListener = null;
            }
        }

        void run() {
            for (Runnable remove : afterClose) {
                remove.run();
            }
            for (SnapcastServerLink link : toClose) {
                try {
                    link.close();
                } catch (RuntimeException ignored) {
                    // a link that fails to close is dropped all the same
                }
            }
            for (SnapcastServerLink link : toRetry) {
                link.retry();
            }
            for (SnapcastServerLink link : toStart) {
                link.start();
            }
        }
    }

    private boolean shouldBeOpenLocked(SnapcastServerKey key) {
        return visible > 0 || retained.containsKey(key) || woken.contains(key);
    }

    private void reconcileLocked(List<SnapcastDirectory.Entry> entries, boolean retryDown, Work work) {
        Set<SnapcastServerKey> listed = new HashSet<>();
        for (SnapcastDirectory.Entry entry : entries) {
            listed.add(entry.getKey());
        }
        // a server that is not listed any more: its link and its last status go
        List<SnapcastServerKey> gone = new ArrayList<>();
        for (SnapcastServerKey key : views.keySet()) {
            if (!listed.contains(key)) {
                gone.add(key);
            }
        }
        for (SnapcastServerKey key : gone) {
            work.detach(views.remove(key));
        }
        for (SnapcastDirectory.Entry entry : entries) {
            SnapcastServerKey key = entry.getKey();
            if (!shouldBeOpenLocked(key)) {
                continue;
            }
            View view = views.get(key);
            if (view != null && view.link != null) {
                if (retryDown && !view.online) {
                    work.toRetry.add(view.link);
                }
                continue;
            }
            SnapcastServerLink link;
            try {
                link = factory.open(entry.getEndpoint());
            } catch (RuntimeException cannotOpen) {
                continue; // stays listed without a link; the next page start tries again
            }
            if (link == null) {
                continue;
            }
            if (view == null) {
                view = new View();
                views.put(key, view);
            }
            final SnapcastServerLink opened = link;
            view.link = opened;
            view.linkListener = this::onLinkChanged;
            opened.addListener(view.linkListener);
            work.toStart.add(opened);
        }
    }

    private void onServersChanged() {
        Work work = new Work();
        synchronized (lock) {
            // read under the lock: two threads that are told of two changes apply them in the order of the lock
            List<SnapcastDirectory.Entry> entries = directory.snapshot();
            reconcileLocked(entries, false, work);
        }
        work.run();
        fire();
    }

    private void onLinkChanged(SnapcastServerLink link) {
        synchronized (lock) {
            View view = views.get(link.getKey());
            if (view == null || view.link != link) {
                return; // a link that was replaced or dropped
            }
            SnapcastServerStatus status = link.getStatus();
            if (status != null) {
                view.status = status;
            }
            view.online = link.isOnline();
        }
        fire();
    }

    private void fire() {
        for (ChangeListener listener : listeners) {
            try {
                listener.onGroupsChanged();
            } catch (RuntimeException ignored) {
                // one listener's failure must not stop the others
            }
        }
    }

    // ---- the groups

    @Override
    public List<SnapcastGroupDevice> groups() {
        List<SnapcastDirectory.Entry> entries = directory.servers();
        List<SnapcastGroupDevice> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        synchronized (lock) {
            SnapcastGroupDevice.Labels texts = labels.get();
            servers:
            for (SnapcastDirectory.Entry entry : entries) {
                View view = views.get(entry.getKey());
                if (view == null || view.status == null) {
                    continue;
                }
                for (SnapcastGroup group : view.status.getGroups()) {
                    if (result.size() >= MAX_GROUPS) {
                        break servers; // the rest would be cut anyway: do not build it
                    }
                    SnapcastGroupDevice device = deviceLocked(entry.getKey(), group, !view.online, texts);
                    if (device != null) {
                        result.add(device);
                        seen.add(device.getIdentity().getUdn().getIdentifierString());
                    }
                }
            }
            cache.keySet().retainAll(seen); // a group that vanished takes its snapshot with it
        }
        Collections.sort(result, new Comparator<SnapcastGroupDevice>() {
            @Override
            public int compare(SnapcastGroupDevice a, SnapcastGroupDevice b) {
                int byName = a.getDetails().getFriendlyName().toLowerCase(Locale.ROOT)
                        .compareTo(b.getDetails().getFriendlyName().toLowerCase(Locale.ROOT));
                return byName != 0 ? byName : a.getIdentity().getUdn().getIdentifierString()
                        .compareTo(b.getIdentity().getUdn().getIdentifierString());
            }
        });
        return result;
    }

    /**
     * The device of a group: the one made before while nothing visible changed (name, offline flag, stream and number
     * of members), so that the lists, which compare devices by identity, see the same row; else a new one.
     */
    private SnapcastGroupDevice deviceLocked(SnapcastServerKey key, SnapcastGroup group, boolean offline,
                                             SnapcastGroupDevice.Labels texts) {
        if (!SnapcastGroupDevice.isSafeGroupId(group.getId())) {
            return null;
        }
        String id = SnapcastGroupDevice.idOf(key, group.getId());
        SnapcastGroupDevice known = cache.get(id);
        if (known != null && known.showsSame(SnapcastGroupDevice.nameOf(group, offline, texts), offline, group.getStreamId(),
                group.getClients().size())) {
            return known;
        }
        SnapcastGroupDevice device = SnapcastGroupDevice.of(key, group, offline, texts);
        if (device != null) {
            cache.put(id, device);
        }
        return device;
    }

    @Override
    public SnapcastGroupDevice findGroup(String receiverId) {
        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(receiverId);
        if (id == null || directory.find(id.getServerKey()) == null) {
            return null;
        }
        synchronized (lock) {
            View view = views.get(id.getServerKey());
            if (view == null || view.status == null) {
                return null;
            }
            SnapcastGroup group = view.status.findGroup(id.getGroupId());
            return group == null ? null : deviceLocked(id.getServerKey(), group, !view.online, labels.get());
        }
    }

    @Override
    public boolean isPending(String receiverId) {
        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(receiverId);
        if (id == null || directory.find(id.getServerKey()) == null) {
            return false;
        }
        synchronized (lock) {
            View view = views.get(id.getServerKey());
            return view == null || view.status == null; // answered: the group is there (found) or gone (not pending)
        }
    }

    /** The selection is kept by {@link SnapcastSelectedServers} (the hub); the receivers have nothing to remember. */
    @Override
    public void selectionChanged(Collection<String> receiverIds) {
        // nothing: see SnapcastHub
    }
}
