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
package de.yaacc.fcast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.protocol.FCastSession;

/**
 * The sessions that the screens open on demand (design.md, "Connection lifecycle", decision D11): the volume of an idle card, the
 * settings sheet. An idle card has no connection; whoever needs one {@link #open opens a link}, uses its session, and closes the link,
 * and the session is closed {@value #CLOSE_AFTER_MILLIS} ms after the last link of the receiver was closed (a short grace so that a
 * second tap or the sheet that opens next uses the same connection). Each session has its own thread, so a receiver that does not
 * answer blocks only itself. A receiver has at most one session here, at most
 * {@value #MAX_SESSIONS} receivers are open at once (the global cap of the security rules), and a receiver that a player plays to is
 * left to the player ({@link #handOverToPlayer}: the player has its own session and there is never a second connection to the same
 * receiver). Thread safe; the sessions run on the executor that was given.
 */
public final class FCastReceiverLinks {

    public static final int MAX_SESSIONS = 8;
    public static final long CLOSE_AFTER_MILLIS = 35_000L;

    /** Makes the session of a receiver (the one of the app connects over TCP; a test makes one to a fake receiver). */
    public interface Factory {
        FCastSession create(FCastEndpoint endpoint, FCastSession.Listener listener, ScheduledExecutorService executor);
    }

    /** One use of a session; close it when done. */
    public final class Link implements AutoCloseable {
        private final String key;
        private final FCastSession session;
        private boolean closed;

        private Link(String key, FCastSession session) {
            this.key = key;
            this.session = session;
        }

        public FCastSession getSession() {
            return session;
        }

        /** Gives the session back; it closes after a grace time if nobody else uses it. Idempotent. */
        @Override
        public void close() {
            synchronized (FCastReceiverLinks.this) {
                if (closed) {
                    return;
                }
                closed = true;
                Entry entry = entries.get(key);
                if (entry == null || entry.session != session) {
                    return;
                }
                entry.users--;
                session.release();
                if (entry.users <= 0) {
                    scheduleClose(key, entry);
                }
            }
        }
    }

    private static final class Entry {
        final FCastSession session;
        final ScheduledExecutorService executor;
        int users;
        int generation;

        Entry(FCastSession session, ScheduledExecutorService executor) {
            this.session = session;
            this.executor = executor;
        }

        /** Closes the session and ends its thread. */
        void shutdown() {
            session.close();
            executor.shutdown();
        }
    }

    private final Factory factory;
    private final ScheduledExecutorService executor;
    private final long closeAfterMillis;
    private final java.util.function.Supplier<ScheduledExecutorService> sessionExecutors;
    private final Map<String, Entry> entries = new HashMap<>();
    private final FCastSession.Listener noListener = new FCastSession.Listener() {
        @Override
        public void onStateChanged(de.yaacc.fcast.protocol.FCastReceiverState state) {
        }
    };

    public FCastReceiverLinks(Factory factory, ScheduledExecutorService timers) {
        this(factory, timers, CLOSE_AFTER_MILLIS);
    }

    /** @param closeAfterMillis the grace time before the session of a receiver without links is closed (a test makes it short) */
    public FCastReceiverLinks(Factory factory, ScheduledExecutorService timers, long closeAfterMillis) {
        this(factory, timers, closeAfterMillis, FCastReceiverLinks::newSessionExecutor);
    }

    /** @param sessionExecutors makes the thread of one session; a test passes its own */
    public FCastReceiverLinks(Factory factory, ScheduledExecutorService timers, long closeAfterMillis,
                              java.util.function.Supplier<ScheduledExecutorService> sessionExecutors) {
        this.factory = factory;
        this.executor = timers;
        this.closeAfterMillis = closeAfterMillis;
        this.sessionExecutors = sessionExecutors;
    }

    private static ScheduledExecutorService newSessionExecutor() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fcast-link-session");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens a link to the receiver: its session is made if there is none (no connection yet: the first command connects).
     *
     * @return the link, null if the cap of open receivers is reached. The caller asks the player first: a receiver that a player plays to
     * is controlled through the player's own session, never through a link
     */
    public synchronized Link open(FCastEndpoint endpoint) {
        String key = endpoint.toString();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_SESSIONS) {
                return null;
            }
            // every receiver has its own thread: a connection that does not come up blocks only that receiver's session
            ScheduledExecutorService own = sessionExecutors.get();
            entry = new Entry(factory.create(endpoint, noListener, own), own);
            entries.put(key, entry);
        }
        entry.generation++; // a pending close of the last link is void
        entry.users++;
        entry.session.acquire();
        return new Link(key, entry.session);
    }

    /** True if a session of this receiver is open here (for the text of an idle card: no connection means no state). */
    public synchronized boolean isOpen(FCastEndpoint endpoint) {
        return entries.containsKey(endpoint.toString());
    }

    /** The session of the receiver if one is open here, else null. */
    public synchronized FCastSession peek(FCastEndpoint endpoint) {
        Entry entry = entries.get(endpoint.toString());
        return entry == null ? null : entry.session;
    }

    /** A player takes the receiver: the session of the screens closes at once (never two connections to one receiver). */
    public synchronized void handOverToPlayer(FCastEndpoint endpoint) {
        Entry entry = entries.remove(endpoint.toString());
        if (entry != null) {
            entry.shutdown();
        }
    }

    /** Closes everything (the app exits). */
    public synchronized void closeAll() {
        for (Entry entry : entries.values()) {
            entry.shutdown();
        }
        entries.clear();
    }

    private void scheduleClose(final String key, final Entry entry) {
        final int generation = entry.generation;
        try {
            executor.schedule(() -> {
                synchronized (FCastReceiverLinks.this) {
                    Entry current = entries.get(key);
                    if (current == entry && entry.users <= 0 && entry.generation == generation) {
                        entries.remove(key);
                        entry.shutdown();
                    }
                }
            }, closeAfterMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException gone) {
            entries.remove(key);
            entry.shutdown();
        }
    }
}
