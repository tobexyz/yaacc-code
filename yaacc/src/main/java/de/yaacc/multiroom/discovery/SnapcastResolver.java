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
package de.yaacc.multiroom.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns the endpoint of a snapserver into the {@link InetAddress} a connection uses. A discovered server and an IP
 * literal have their address already and are never looked up. A host name (a manual entry) goes to the system
 * resolver, which has no time limit of its own: here the lookup runs on a helper thread and the caller waits at most
 * {@code timeoutMillis}, so the reader thread of a connection is never held by a slow or hanging resolver. At most
 * {@link #MAX_HELPERS} lookups are under way at once (a hanging one keeps its thread until the resolver returns);
 * more are refused at once, so repeated attempts cannot pile up threads. An answer that is unspecified or multicast is
 * refused. Errors carry no host name or address in their text.
 */
public final class SnapcastResolver {

    /** The most lookups that can be under way at once. */
    static final int MAX_HELPERS = 2;

    /** The lookup of a name; {@link InetAddress#getByName} in the app, a fake in a test. */
    public interface Lookup {
        InetAddress lookup(String host) throws Exception;
    }

    private final Lookup lookup;
    private final long timeoutMillis;
    private final ThreadPoolExecutor helpers;

    public SnapcastResolver(Lookup lookup, long timeoutMillis) {
        this.lookup = lookup;
        this.timeoutMillis = timeoutMillis;
        this.helpers = new ThreadPoolExecutor(0, MAX_HELPERS, 10, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "snapcast-resolver");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** The resolver of the app: the system's name lookup with a limit of 5 seconds. */
    public static SnapcastResolver system() {
        return new SnapcastResolver(new Lookup() {
            @Override
            public InetAddress lookup(String host) throws UnknownHostException {
                return InetAddress.getByName(host);
            }
        }, 5_000L);
    }

    /**
     * @return the address to connect to
     * @throws IOException when the name does not resolve in time, the resolver is busy, or the address is unusable
     */
    public InetAddress resolve(SnapcastEndpoint endpoint) throws IOException {
        InetAddress known = endpoint.getAddress();
        if (known != null) {
            return known;
        }
        final String host = endpoint.getHost();
        Future<InetAddress> future;
        try {
            future = helpers.submit(() -> lookup.lookup(host));
        } catch (RejectedExecutionException busy) {
            throw new IOException("name lookup busy");
        }
        InetAddress address;
        try {
            address = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            future.cancel(true);
            throw new IOException("name lookup timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("name lookup interrupted");
        } catch (ExecutionException failed) {
            throw new IOException("name lookup failed"); // the cause names the host: not kept
        }
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress() || isBroadcast(address)) {
            throw new IOException("name lookup gave an unusable address");
        }
        return address;
    }

    private static boolean isBroadcast(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length != 4) {
            return false;
        }
        for (byte b : raw) {
            if (b != (byte) 0xff) {
                return false;
            }
        }
        return true;
    }
}
