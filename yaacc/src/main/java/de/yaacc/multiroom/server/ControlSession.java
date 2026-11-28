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
package de.yaacc.multiroom.server;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastLineReader;
import de.yaacc.util.YaaccLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One connection to the control port of the built-in server (issue #330, task 3.2): JSON-RPC, one request (or a batch) per line. One
 * reader thread (this runnable, answers go to the queue) and one writer thread; the queue is capped, so a connection that does not read is
 * dropped and holds nobody (rule S3). A line is at most 64 KiB and has to be finished in 10 seconds.
 */
public final class ControlSession implements Runnable, Closeable {

    /** Where the notifications of a change go and what the session tells when it ends. */
    public interface Hub {
        /** Sends the notifications to every control connection except {@code origin} (null: to all). */
        void broadcast(List<JSONObject> notifications, ControlSession origin);

        void ended(ControlSession session);
    }

    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_BATCH = 16;
    public static final int MAX_QUEUE_LINES = 256;
    public static final long MAX_QUEUE_BYTES = 1024L * 1024L;
    public static final long IDLE_LIMIT_MILLIS = 10L * 60L * 1000L;
    private static final long LINE_DEADLINE_MILLIS = 10_000L;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable closer;
    private final RpcDispatcher dispatcher;
    private final Hub hub;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private long queuedBytes;
    private volatile boolean closed;

    public ControlSession(InputStream in, OutputStream out, Closeable closer, RpcDispatcher dispatcher, Hub hub) {
        this.in = in;
        this.out = out;
        this.closer = closer;
        this.dispatcher = dispatcher;
        this.hub = hub;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Queues one JSON line for this connection (any thread); a connection that is too far behind is dropped. */
    public void send(JSONObject json) {
        byte[] line = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        boolean tooMuch;
        synchronized (queue) {
            if (closed) {
                return;
            }
            tooMuch = queue.size() >= MAX_QUEUE_LINES || queuedBytes + line.length > MAX_QUEUE_BYTES;
            if (!tooMuch) {
                queuedBytes += line.length;
                queue.add(line);
            }
        }
        if (tooMuch) {
            YaaccLogger.d(getClass().getName(), "a control connection does not keep up and is dropped");
            close();
        }
    }

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, "snapcast-server-control-writer");
        writer.setDaemon(true);
        writer.start();
        try {
            SnapcastLineReader reader = new SnapcastLineReader(in, MAX_LINE_BYTES, LINE_DEADLINE_MILLIS, MonotonicClock.SYSTEM);
            long lastRx = System.nanoTime();
            while (!closed) {
                byte[] line;
                try {
                    line = reader.readLine();
                    lastRx = System.nanoTime();
                } catch (SocketTimeoutException idle) {
                    if ((System.nanoTime() - lastRx) / 1_000_000L > IDLE_LIMIT_MILLIS) {
                        break;
                    }
                    continue;
                }
                if (line == null) {
                    break;
                }
                if (line.length == 0) {
                    continue;
                }
                handle(new String(line, StandardCharsets.UTF_8));
            }
        } catch (IOException | SnapcastException e) {
            YaaccLogger.d(getClass().getName(), "a control connection ended: " + e.getClass().getSimpleName());
        } finally {
            close();
            hub.ended(this);
        }
    }

    private void handle(String text) {
        Object parsed;
        try {
            String trimmed = text.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                throw new JSONException("not an object or a list");
            }
            parsed = new org.json.JSONTokener(trimmed).nextValue();
        } catch (JSONException e) {
            send(errorResponse(-32700, "Parse error", "the line is not JSON"));
            return;
        }
        if (parsed instanceof JSONArray) {
            JSONArray batch = (JSONArray) parsed;
            if (batch.length() == 0 || batch.length() > MAX_BATCH) {
                send(errorResponse(-32600, "Invalid request", "a batch of 1 to " + MAX_BATCH + " requests"));
                return;
            }
            JSONArray answers = new JSONArray();
            for (int i = 0; i < batch.length(); i++) {
                JSONObject request = batch.optJSONObject(i);
                JSONObject answer = request == null ? errorResponse(-32600, "Invalid request", "not an object") : one(request);
                if (answer != null) {
                    answers.put(answer);
                }
            }
            if (answers.length() > 0) {
                byte[] line = (answers.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                enqueue(line);
            }
        } else if (parsed instanceof JSONObject) {
            JSONObject answer = one((JSONObject) parsed);
            if (answer != null) {
                send(answer);
            }
        } else {
            send(errorResponse(-32600, "Invalid request", "not an object"));
        }
    }

    private JSONObject one(JSONObject request) {
        RpcDispatcher.Outcome outcome = dispatcher.handle(request);
        if (!outcome.notifications.isEmpty()) {
            hub.broadcast(outcome.notifications, this);
        }
        return outcome.response;
    }

    private void enqueue(byte[] line) {
        boolean tooMuch;
        synchronized (queue) {
            if (closed) {
                return;
            }
            tooMuch = queue.size() >= MAX_QUEUE_LINES || queuedBytes + line.length > MAX_QUEUE_BYTES;
            if (!tooMuch) {
                queuedBytes += line.length;
                queue.add(line);
            }
        }
        if (tooMuch) {
            close();
        }
    }

    private static JSONObject errorResponse(int code, String message, String data) {
        JSONObject e = new JSONObject();
        JSONObject json = new JSONObject();
        try {
            e.put("code", code);
            e.put("message", message);
            e.put("data", data);
            json.put("jsonrpc", "2.0");
            json.put("error", e);
            json.put("id", JSONObject.NULL);
        } catch (JSONException impossible) {
            throw new IllegalStateException("a plain value is always valid");
        }
        return json;
    }

    private void writeLoop() {
        try {
            while (!closed) {
                byte[] next = queue.poll(500, TimeUnit.MILLISECONDS);
                if (next == null) {
                    continue;
                }
                synchronized (queue) {
                    queuedBytes -= next.length;
                }
                out.write(next);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // the connection is gone; the reader ends the session
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            closer.close();
        } catch (IOException ignored) {
            // closed
        }
        synchronized (queue) {
            queue.clear();
            queuedBytes = 0;
        }
    }
}
