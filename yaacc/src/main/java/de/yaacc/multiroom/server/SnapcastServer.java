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

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.util.YaaccLogger;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The built-in Snapcast server (issue #330): the stream port with its clients, the streams with their pipelines, and the state. The
 * listener takes only the addresses the filter allows (never read before the check, rule S2) and at most {@code maxClients} at a time (S3).
 * The control port, the sources and the service are separate parts that use this one.
 */
public final class SnapcastServer implements StreamClientSession.Host, StreamPipeline.Sink, AutoCloseable {

    private final ServerState state;
    private final MicroClock clock;
    private final Predicate<InetAddress> accept;
    private final int maxClients;
    private volatile Consumer<List<JSONObject>> notifier;
    private final Map<String, StreamClientSession> sessions = new LinkedHashMap<>();
    private final Map<String, StreamPipeline> pipelines = new LinkedHashMap<>();
    private final Map<String, java.io.Closeable> sources = new LinkedHashMap<>();
    private volatile SourceFactory sourceFactory;
    private final Object lock = new Object();

    private volatile ServerSocket serverSocket;
    private Thread acceptor;
    private int connections;

    /**
     * @param notifier gets the notifications that changes make, to send to the control connections (called without a lock of this class)
     */
    public SnapcastServer(ServerState state, MicroClock clock, Predicate<InetAddress> accept, int maxClients,
                          Consumer<List<JSONObject>> notifier) {
        this.state = state;
        this.clock = clock;
        this.accept = accept;
        this.maxClients = maxClients;
        this.notifier = notifier;
    }

    /** Where the notifications go (the control port); set once before the server runs. */
    public void setNotifier(Consumer<List<JSONObject>> notifier) {
        this.notifier = notifier;
    }

    /** Opens the source of a stream (a tcp listener in the app; tests give their own). */
    public interface SourceFactory {
        java.io.Closeable open(StreamPipeline pipeline, StreamSpec spec) throws IOException;
    }

    public void setSourceFactory(SourceFactory factory) {
        this.sourceFactory = factory;
    }

    public ServerState state() {
        return state;
    }

    public MicroClock clock() {
        return clock;
    }

    /** Opens the stream port (0: any free port). */
    public synchronized void start(int port) throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), 8);
        serverSocket = socket;
        acceptor = new Thread(() -> acceptLoop(socket), "snapcast-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** The port that is bound, or -1. */
    public int streamPort() {
        ServerSocket s = serverSocket;
        return s == null || s.isClosed() ? -1 : s.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                continue;
            }
            boolean room;
            synchronized (lock) {
                room = connections < maxClients;
                if (room && accept.test(client.getInetAddress())) {
                    connections++;
                } else {
                    room = false;
                }
            }
            if (!room) {
                closeQuietly(client);
                continue;
            }
            try {
                client.setTcpNoDelay(true);
                final Socket socketOfClient = client;
                StreamClientSession session = new StreamClientSession(client.getInputStream(), client.getOutputStream(), client,
                        client.getInetAddress().getHostAddress(), this, clock, socketOfClient::setSoTimeout);
                Thread thread = new Thread(session, "snapcast-server-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                synchronized (lock) {
                    connections--;
                }
                closeQuietly(client);
            }
        }
    }

    // ------------------------------------------------------------------ streams

    /** Adds a stream with its pipeline; returns the pipeline for the producer. */
    public StreamPipeline addStream(String id, String uri, int sampleRate, int channels, int bitsPerSample, ChunkEncoder encoder) {
        List<JSONObject> notifications = state.addStream(id, uri);
        StreamPipeline pipeline = new StreamPipeline(id, sampleRate, channels * bitsPerSample / 8, encoder, this);
        synchronized (lock) {
            pipelines.put(id, pipeline);
        }
        applyChange();
        notifier.accept(notifications);
        return pipeline;
    }

    /**
     * A stream from the control port ({@code Stream.AddStream}): its source is opened (a taken port fails at once), the stream is added.
     *
     * @return the notifications of the change, for the caller to send
     */
    public List<JSONObject> addTcpStream(StreamSpec spec) {
        if (spec.codec().equals("flac")) {
            throw new ServerState.StateException(-32602, "codec flac is not available yet");
        }
        List<JSONObject> notifications = state.addStream(spec.name(), spec.raw());
        StreamPipeline pipeline = new StreamPipeline(spec.name(), spec.sampleRate(), spec.channels() * spec.bitsPerSample() / 8,
                new ChunkEncoder.Pcm(spec.sampleRate(), spec.channels(), spec.bitsPerSample()), this);
        try {
            SourceFactory factory = sourceFactory;
            java.io.Closeable source = factory != null ? factory.open(pipeline, spec) : defaultSource(pipeline, spec);
            synchronized (lock) {
                pipelines.put(spec.name(), pipeline);
                sources.put(spec.name(), source);
            }
        } catch (IOException e) {
            state.removeStream(spec.name());
            throw new ServerState.StateException(-32603, "the stream source could not be opened");
        }
        applyChange();
        return notifications;
    }

    private java.io.Closeable defaultSource(StreamPipeline pipeline, StreamSpec spec) throws IOException {
        TcpStreamSource source = new TcpStreamSource(pipeline, spec.port(), accept, clock);
        source.start();
        return source;
    }

    /** {@code Stream.RemoveStream}: the source is closed and the stream goes; returns the notifications for the caller to send. */
    public List<JSONObject> removeStreamNotifying(String id) {
        List<JSONObject> notifications = state.removeStream(id);
        java.io.Closeable source;
        synchronized (lock) {
            pipelines.remove(id);
            source = sources.remove(id);
        }
        closeQuietly(source);
        applyChange();
        return notifications;
    }

    public void removeStream(String id) {
        List<JSONObject> notifications = state.removeStream(id);
        synchronized (lock) {
            pipelines.remove(id);
        }
        applyChange();
        notifier.accept(notifications);
    }

    public StreamPipeline pipeline(String id) {
        synchronized (lock) {
            return pipelines.get(id);
        }
    }

    @Override
    public void chunk(String streamId, long timestampMicros, byte[] payload) {
        List<StreamClientSession> targets = new ArrayList<>();
        synchronized (lock) {
            for (String clientId : state.connectedClientsOfStream(streamId)) {
                StreamClientSession s = sessions.get(clientId);
                if (s != null) {
                    targets.add(s);
                }
            }
        }
        for (StreamClientSession s : targets) {
            s.offerChunk(streamId, timestampMicros, payload);
        }
    }

    @Override
    public void status(String streamId, StreamActivity.Status status) {
        notifier.accept(state.setStreamStatus(streamId, status.json()));
    }

    /**
     * The state changed through the control port or a stream came or went: every client is brought to what the state says (its stream with
     * the header of it, its settings).
     */
    public void applyChange() {
        List<StreamClientSession> all;
        synchronized (lock) {
            all = new ArrayList<>(sessions.values());
        }
        for (StreamClientSession s : all) {
            String id = s.clientId();
            if (id == null || !state.knowsClient(id)) {
                continue;
            }
            String wanted = state.streamIdOf(id);
            if (!wanted.equals(s.streamId())) {
                StreamPipeline p = pipeline(wanted);
                s.setStream(wanted, p == null ? null : p.encoder().header());
            }
            s.sendSettings(state.settingsOf(id));
        }
    }

    // ------------------------------------------------------------------ StreamClientSession.Host

    @Override
    public StreamClientSession.Join joined(StreamClientSession session, HelloMessage hello, String ip)
            throws StreamClientSession.Refused {
        List<JSONObject> notifications;
        String id;
        StreamClientSession old;
        try {
            id = state.clientIdOf(hello);
            notifications = state.clientConnected(hello, ip);
        } catch (ServerState.StateException e) {
            throw new StreamClientSession.Refused(e.data());
        }
        String streamId = state.streamIdOf(id);
        StreamPipeline pipeline = pipeline(streamId);
        CodecHeaderMessage header = pipeline == null ? null : pipeline.encoder().header();
        synchronized (lock) {
            old = sessions.put(id, session);
        }
        if (old != null && old != session) {
            old.close();
        }
        notifier.accept(notifications);
        return new StreamClientSession.Join(id, state.settingsOf(id), streamId, header);
    }

    @Override
    public void clientInfo(String clientId, int volume, boolean muted) {
        notifier.accept(state.clientInfo(clientId, volume, muted));
    }

    @Override
    public void left(StreamClientSession session) {
        String id = session.clientId();
        synchronized (lock) {
            connections = Math.max(0, connections - 1);
            if (id != null && sessions.get(id) == session) {
                sessions.remove(id);
            } else {
                id = null; // replaced by a newer session of the same client: it is still connected
            }
        }
        if (id != null) {
            notifier.accept(state.clientDisconnected(id));
        }
    }

    /** The session of a connected client (for tests and the control side), or null. */
    public StreamClientSession sessionOf(String clientId) {
        synchronized (lock) {
            return sessions.get(clientId);
        }
    }

    @Override
    public void close() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            closeQuietly(s);
        }
        List<StreamClientSession> all;
        synchronized (lock) {
            all = new ArrayList<>(sessions.values());
        }
        for (StreamClientSession session : all) {
            session.close();
        }
        List<java.io.Closeable> open;
        synchronized (lock) {
            open = new ArrayList<>(sources.values());
            sources.clear();
        }
        for (java.io.Closeable source : open) {
            closeQuietly(source);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
