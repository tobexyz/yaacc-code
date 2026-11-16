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
package de.yaacc.multiroom.control;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * Keeps an immutable {@link SnapcastServerStatus} up to date from the notifications of the server, so that the full
 * status has to be read once and not again after every change. After any doubt the full status is asked for again:
 * an unknown notification, a client or group or stream that the model does not have, parameters that do not make
 * sense, or notifications that were dropped. That request is rate limited, at most one per second and ten per minute,
 * so that a server (or a flood) can not make the phone read the status in a loop; a request held back is delivered
 * by {@link #poll()} when it is allowed.
 *
 * <p>The notifications handled are the ones of the Snapcast control API: {@code Client.OnConnect},
 * {@code Client.OnDisconnect}, {@code Client.OnVolumeChanged}, {@code Client.OnLatencyChanged},
 * {@code Client.OnNameChanged}, {@code Group.OnMute}, {@code Group.OnStreamChanged}, {@code Group.OnNameChanged},
 * {@code Stream.OnUpdate} and {@code Server.OnUpdate} (the whole status). {@code Stream.OnProperties} (metadata of a
 * stream) changes nothing in the model and is ignored. Thread safe.
 *
 * <p><b>Order.</b> The model is right only if {@link #setStatus} and {@link #apply} run in the order of the lines of the
 * connection. Use {@link SnapcastOperations#SnapcastOperations(SnapcastCaller, SnapcastModelUpdater)} (the status and
 * our own changes are applied through {@link SnapcastCaller#callOrdered}) and a listener that hands every notification
 * to {@link #apply}: both run on the listener executor, one after the other, in line order. A status that is read
 * elsewhere and set later can overwrite a newer notification.
 *
 * <p><b>Our own changes.</b> The server does not notify the connection that made a change, so the caller has to patch
 * the model itself ({@link #applyOwnChange}; {@link SnapcastOperations} does it after every successful call).
 *
 * <p><b>Re-reads.</b> The re-read is requested, not made: it is the caller's to do, and to repeat if it fails (the
 * wish is cleared when it is handed out). A new connection needs a fresh status as well: read it on a worker when
 * {@link SnapcastRpc.Listener#onConnected()} is told. The requester runs on the thread that found the doubt, which is
 * the listener thread of the connection, so it must only hand the read to a worker: a {@code getStatus()} in it
 * throws an {@link IllegalStateException} (see {@link SnapcastRpc#callOrdered}).
 */
public final class SnapcastModelUpdater {

    private static final long MIN_INTERVAL_MS = 1_000;
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_PER_WINDOW = 10;

    private final MonotonicClock clock;
    private final Runnable reReadRequester;
    private final ArrayDeque<Long> grants = new ArrayDeque<>();
    private SnapcastServerStatus status;
    private boolean reReadWanted;

    /**
     * @param reReadRequester called, on the thread that found the doubt (or that calls {@link #poll()}; for the
     *                        notifications that is the listener thread of the connection), when the full status
     *                        should be read again; it must only hand the work to a worker, which reads the status
     *                        and gives the answer to {@link #setStatus}
     */
    public SnapcastModelUpdater(MonotonicClock clock, Runnable reReadRequester) {
        this.clock = clock;
        this.reReadRequester = reReadRequester;
    }

    /** @return true if the updater handles this method (or knowingly ignores it) without a re-read */
    static boolean isKnown(String method) {
        if (method == null) {
            return false;
        }
        switch (method) {
            case "Client.OnConnect":
            case "Client.OnDisconnect":
            case "Client.OnVolumeChanged":
            case "Client.OnLatencyChanged":
            case "Client.OnNameChanged":
            case "Group.OnMute":
            case "Group.OnStreamChanged":
            case "Group.OnNameChanged":
            case "Stream.OnUpdate":
            case "Stream.OnProperties":
            case "Server.OnUpdate":
                return true;
            default:
                return false;
        }
    }

    /**
     * @return false when the model ignores the params of this (known) notification, so that the reader can drop them
     * ({@code Stream.OnProperties}: the metadata of a stream is not in the model)
     */
    static boolean needsParams(String method) {
        return !"Stream.OnProperties".equals(method);
    }

    /** The current model; null before the first status. */
    public synchronized SnapcastServerStatus getStatus() {
        return status;
    }

    /** Takes a freshly read status as the model; it also settles a re-read that was held back. */
    public synchronized void setStatus(SnapcastServerStatus fresh) {
        status = fresh;
        reReadWanted = false;
    }

    /** The server's notifications were dropped on the way (or a line was unreadable): the model may be behind. */
    public void onNotificationsLost() {
        requestReRead();
    }

    /**
     * Applies a change that this client made itself, in the shape of the notification the server sends to the others
     * (for example {@code Client.OnVolumeChanged}). Does nothing before the first status.
     *
     * @return the model after the change, null if there is none yet
     */
    public SnapcastServerStatus applyOwnChange(String method, JSONObject params) {
        synchronized (this) {
            if (status == null) {
                return null;
            }
        }
        return apply(method, params);
    }

    /**
     * Applies one notification.
     *
     * @return the model after the notification; unchanged when it needed a re-read
     */
    public SnapcastServerStatus apply(String method, JSONObject params) {
        boolean doubt = false;
        SnapcastServerStatus result;
        synchronized (this) {
            SnapcastServerStatus patched = null;
            if (method == null || params == null) {
                doubt = true;
            } else if ("Server.OnUpdate".equals(method)) {
                try {
                    patched = SnapcastServerStatus.fromResult(params);
                } catch (SnapcastException unreadable) {
                    doubt = true;
                }
            } else if ("Stream.OnProperties".equals(method)) {
                return status; // nothing of it is in the model
            } else if (!isKnown(method) || status == null) {
                doubt = true;
            } else {
                patched = patch(method, params);
                doubt = patched == null;
            }
            if (patched != null) {
                status = patched;
            }
            result = status;
        }
        if (doubt) {
            requestReRead();
        }
        return result;
    }

    /** @return the model patched, or null when the notification does not fit the model */
    private SnapcastServerStatus patch(String method, JSONObject params) {
        String id = SnapcastJson.strictString(params, "id");
        switch (method) {
            case "Client.OnConnect":
            case "Client.OnDisconnect":
                return patchClientState(method, params, id);
            case "Client.OnVolumeChanged": {
                JSONObject volume = SnapcastJson.object(params, "volume");
                SnapcastClient client = clientOf(id);
                if (client == null || volume == null) {
                    return null;
                }
                return replaceClient(client.withVolume(SnapcastVolume.fromJson(volume)));
            }
            case "Client.OnLatencyChanged": {
                SnapcastClient client = clientOf(id);
                if (client == null || !SnapcastJson.isNumber(params, "latency")) {
                    return null;
                }
                int latency = SnapcastJson.integer(params, "latency", 0, SnapcastLimits.MAX_LATENCY_MS, 0);
                return replaceClient(client.withLatencyMs(latency));
            }
            case "Client.OnNameChanged": {
                SnapcastClient client = clientOf(id);
                String name = SnapcastJson.strictString(params, "name");
                if (client == null || name == null) {
                    return null;
                }
                return replaceClient(client.withName(name));
            }
            case "Group.OnMute": {
                SnapcastGroup group = id == null ? null : status.findGroup(id);
                if (group == null || !SnapcastJson.isBoolean(params, "mute")) {
                    return null;
                }
                return status.withGroup(group.withMuted(SnapcastJson.bool(params, "mute", false)));
            }
            case "Group.OnStreamChanged": {
                SnapcastGroup group = id == null ? null : status.findGroup(id);
                String streamId = SnapcastJson.strictString(params, "stream_id");
                if (group == null || streamId == null) {
                    return null;
                }
                return status.withGroup(group.withStreamId(streamId));
            }
            case "Group.OnNameChanged": {
                SnapcastGroup group = id == null ? null : status.findGroup(id);
                String name = SnapcastJson.strictString(params, "name");
                if (group == null || name == null) {
                    return null;
                }
                return status.withGroup(group.withName(name));
            }
            case "Stream.OnUpdate": {
                SnapcastStream stream = SnapcastStream.fromJson(SnapcastJson.object(params, "stream"));
                if (stream == null || status.findStream(stream.getId()) == null) {
                    return null;
                }
                return status.withStream(stream);
            }
            default:
                return null;
        }
    }

    private SnapcastServerStatus patchClientState(String method, JSONObject params, String id) {
        JSONObject clientJson = SnapcastJson.object(params, "client");
        if (clientJson != null) {
            SnapcastClient client = SnapcastClient.fromJson(clientJson);
            if (client == null || status.findGroupOfClient(client.getId()) == null) {
                return null; // a client the model does not know may have a group of its own now: ask the server
            }
            return replaceClient(client);
        }
        SnapcastClient known = clientOf(id);
        return known == null ? null : replaceClient(known.withConnected("Client.OnConnect".equals(method)));
    }

    private SnapcastClient clientOf(String id) {
        return id == null ? null : status.findClient(id);
    }

    private SnapcastServerStatus replaceClient(SnapcastClient client) {
        SnapcastGroup group = status.findGroupOfClient(client.getId());
        return group == null ? null : status.withGroup(group.withClient(client));
    }

    // ---- the rate limit

    /**
     * Delivers a re-read that was held back by the rate limit, if it is allowed now. Call it now and then (once per
     * second is enough) while the model is in use.
     *
     * @return true if the re-read was requested now
     */
    public boolean poll() {
        boolean fire;
        synchronized (this) {
            fire = reReadWanted && grantLocked();
            if (fire) {
                reReadWanted = false;
            }
        }
        if (fire) {
            reReadRequester.run();
        }
        return fire;
    }

    /**
     * Asks for a full status now, within the rate limit (a request that is held back is delivered by {@link #poll()}).
     * For changes that the model can not patch, such as a stream that was added.
     */
    public void requestReRead() {
        boolean fire;
        synchronized (this) {
            fire = grantLocked();
            reReadWanted = !fire;
        }
        if (fire) {
            reReadRequester.run();
        }
    }

    /**
     * Asks the rate limit for permission to read the whole status now (a read that the caller makes itself, such as the one
     * before a destructive change). Uses up a grant, like {@link #requestReRead()}.
     *
     * @return true if the read is allowed
     */
    public boolean tryGrantRead() {
        synchronized (this) {
            return grantLocked();
        }
    }

    /** At most one grant per second and ten per minute. */
    private boolean grantLocked() {
        long now = clock.nowMillis();
        while (!grants.isEmpty() && grants.peekFirst() <= now - WINDOW_MS) {
            grants.pollFirst();
        }
        if (grants.size() >= MAX_PER_WINDOW) {
            return false;
        }
        if (!grants.isEmpty() && now - grants.peekLast() < MIN_INTERVAL_MS) {
            return false;
        }
        grants.addLast(now);
        return true;
    }
}
