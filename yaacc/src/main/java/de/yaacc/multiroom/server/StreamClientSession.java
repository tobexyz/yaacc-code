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

import de.yaacc.multiroom.protocol.ClientInfoMessage;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.Frame;
import de.yaacc.multiroom.protocol.FrameReader;
import de.yaacc.multiroom.protocol.HelloMessage;
import de.yaacc.multiroom.protocol.MessageHeader;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.SnapcastBinaryMessage;
import de.yaacc.multiroom.protocol.SnapcastMessageCodec;
import de.yaacc.multiroom.protocol.SnapcastProtocolException;
import de.yaacc.multiroom.protocol.TimeMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;
import de.yaacc.util.YaaccLogger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One client on the stream port of the built-in server (issue #330): the Hello, then settings, the codec header and chunks out, Time and
 * ClientInfo in. One reader thread (this runnable) and one writer thread; every other thread only queues messages, so a client that stops
 * reading holds nobody: its queue has a size cap, and a client that reaches it is dropped (rule S3). Frames above 64 KiB, a Hello that
 * does not come in time, too many frames per second or too many strange frames end the session.
 */
public final class StreamClientSession implements Runnable, Closeable {

    /** What the server does for a session. */
    public interface Host {
        /**
         * The client said Hello. Returns what it gets first, or throws {@link Refused} to say no.
         */
        Join joined(StreamClientSession session, HelloMessage hello, String ip) throws Refused;

        void clientInfo(String clientId, int volume, boolean muted);

        /** The session ended (once). */
        void left(StreamClientSession session);
    }

    /** What a client gets at the start. */
    public static final class Join {
        final String clientId;
        final ServerSettingsMessage settings;
        final String streamId;
        final CodecHeaderMessage header;

        public Join(String clientId, ServerSettingsMessage settings, String streamId, CodecHeaderMessage header) {
            this.clientId = clientId;
            this.settings = settings;
            this.streamId = streamId;
            this.header = header;
        }
    }

    /** The server refuses the client. */
    public static final class Refused extends Exception {
        public Refused(String text) {
            super(text);
        }
    }

    public static final int MAX_QUEUE_MESSAGES = 1024;
    public static final long MAX_QUEUE_BYTES = 4L * 1024 * 1024;
    public static final int HELLO_TIMEOUT_MILLIS = 5_000;
    public static final int IDLE_LIMIT_MILLIS = 30_000;
    private static final int MAX_FRAMES_PER_SECOND = 300;
    private static final int MAX_CONSECUTIVE_UNEXPECTED = 50;
    private static final long WRITE_STALL_MILLIS = 10_000L;

    private static final class Outgoing {
        final SnapcastBinaryMessage message;
        final int refersTo;
        final int bytes;

        Outgoing(SnapcastBinaryMessage message, int refersTo, int bytes) {
            this.message = message;
            this.refersTo = refersTo;
            this.bytes = bytes;
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private final Closeable socket;
    private final String ip;
    private final Host host;
    private final MicroClock clock;
    private final FrameReader.ReadTimeout readTimeout;
    private final LinkedBlockingQueue<Outgoing> queue = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    private volatile boolean closed;
    private volatile String clientId;
    private long queuedBytes;
    private String streamId = "";
    private int nextId = 1;
    private volatile long writingSinceMillis;

    /** @param readTimeout sets the receive timeout of the socket (a test without a socket passes a no-op) */
    public StreamClientSession(InputStream in, OutputStream out, Closeable socket, String ip, Host host, MicroClock clock,
                               FrameReader.ReadTimeout readTimeout) {
        this.readTimeout = readTimeout;
        this.in = in;
        this.out = out;
        this.socket = socket;
        this.ip = ip;
        this.host = host;
        this.clock = clock;
    }

    /** The id the server gives this client, after its Hello; null before. */
    public String clientId() {
        return clientId;
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ what the server sends

    /** Sends the settings of the client (volume, mute, latency, buffer). */
    public void sendSettings(ServerSettingsMessage settings) {
        enqueue(settings, 0, 64);
    }

    /**
     * The client now plays this stream: its codec header goes first and chunks of that stream follow (none while {@code header} is null,
     * a client in a group with no stream).
     */
    public void setStream(String newStreamId, CodecHeaderMessage header) {
        synchronized (lock) {
            streamId = newStreamId == null ? "" : newStreamId;
            if (header != null) {
                enqueue(header, 0, header.getPayload().length + 40);
            }
        }
    }

    public String streamId() {
        synchronized (lock) {
            return streamId;
        }
    }

    /** A chunk of a stream: queued if the client plays that stream; a client that falls too far behind is dropped. */
    public void offerChunk(String forStream, long timestampMicros, byte[] payload) {
        synchronized (lock) {
            if (!forStream.equals(streamId)) {
                return;
            }
            long sec = Math.floorDiv(timestampMicros, 1_000_000L);
            WireChunkMessage chunk = new WireChunkMessage((int) sec, (int) Math.floorMod(timestampMicros, 1_000_000L), payload);
            enqueue(chunk, 0, payload.length + 40);
        }
    }

    private void enqueue(SnapcastBinaryMessage message, int refersTo, int bytes) {
        boolean tooMuch;
        synchronized (queue) {
            if (closed) {
                return;
            }
            tooMuch = queue.size() >= MAX_QUEUE_MESSAGES || queuedBytes + bytes > MAX_QUEUE_BYTES;
            if (!tooMuch) {
                queuedBytes += bytes;
                queue.add(new Outgoing(message, refersTo, bytes));
            }
        }
        if (tooMuch) {
            YaaccLogger.d(getClass().getName(), "a stream client does not keep up and is dropped");
            close();
        }
    }

    // ------------------------------------------------------------------ the session

    @Override
    public void run() {
        Thread writer = null;
        try {
            FrameReader reader = FrameReader.forServer(in, clock, 10_000L, null);
            readTimeout.set(HELLO_TIMEOUT_MILLIS);
            HelloMessage hello = awaitHello(reader);
            readTimeout.set(2_000);
            Join join;
            try {
                join = host.joined(this, hello, ip);
            } catch (Refused refused) {
                return;
            }
            clientId = join.clientId;
            synchronized (lock) {
                streamId = join.streamId == null ? "" : join.streamId;
            }
            sendSettings(join.settings);
            if (join.header != null) {
                synchronized (lock) {
                    enqueue(join.header, 0, join.header.getPayload().length + 40);
                }
            }
            writer = new Thread(this::writeLoop, "snapcast-server-writer");
            writer.setDaemon(true);
            writer.start();
            readLoop(reader);
        } catch (IOException | SnapcastProtocolException e) {
            YaaccLogger.d(getClass().getName(), "a stream client ended: " + e.getClass().getSimpleName());
        } finally {
            close();
            host.left(this);
        }
    }

    private HelloMessage awaitHello(FrameReader reader) throws IOException, SnapcastProtocolException {
        // the receive timeout of the socket is the time the client has to say Hello; the first frame has to be it
        Frame frame = reader.read();
        if (frame.getMessage() instanceof HelloMessage) {
            return (HelloMessage) frame.getMessage();
        }
        throw new IOException("something else than a hello first");
    }

    private void readLoop(FrameReader reader) throws IOException, SnapcastProtocolException {
        long windowStart = clock.nowMicros();
        int frames = 0;
        int unexpected = 0;
        long lastRx = clock.nowMicros();
        while (!closed) {
            Frame frame;
            try {
                frame = reader.read();
                lastRx = clock.nowMicros();
            } catch (SocketTimeoutException idle) {
                if ((clock.nowMicros() - lastRx) / 1000L > IDLE_LIMIT_MILLIS) {
                    throw new IOException("no traffic");
                }
                continue;
            }
            long now = clock.nowMicros();
            if (now - windowStart >= 1_000_000L) {
                windowStart = now;
                frames = 0;
            }
            if (++frames > MAX_FRAMES_PER_SECOND) {
                throw new IOException("too many frames");
            }
            SnapcastBinaryMessage message = frame.getMessage();
            if (message instanceof TimeMessage) {
                MessageHeader header = frame.getHeader();
                long latency = now - header.sentMicros();
                enqueue(TimeMessage.ofMicros(latency), header.getId(), 40);
                unexpected = 0;
            } else if (message instanceof ClientInfoMessage) {
                ClientInfoMessage info = (ClientInfoMessage) message;
                host.clientInfo(clientId, info.getVolume(), info.isMuted());
                unexpected = 0;
            } else if (++unexpected > MAX_CONSECUTIVE_UNEXPECTED) {
                throw new IOException("too many strange frames");
            }
        }
    }

    private void writeLoop() {
        try {
            while (!closed) {
                Outgoing next = queue.poll(500, TimeUnit.MILLISECONDS);
                if (next == null) {
                    continue;
                }
                synchronized (queue) {
                    queuedBytes -= next.bytes;
                }
                long sent = clock.nowMicros();
                int id;
                synchronized (lock) {
                    id = nextId++ & 0xffff;
                }
                byte[] bytes = SnapcastMessageCodec.encode(next.message, id, next.refersTo, sent);
                writingSinceMillis = System.nanoTime() / 1_000_000L;
                out.write(bytes);
                out.flush();
                writingSinceMillis = 0;
            }
        } catch (IOException | InterruptedException e) {
            // the connection is gone; the reader ends the session
        } finally {
            close();
        }
    }

    /** True if a write has lasted longer than the limit: the client does not read. Used by the server's watchdog. */
    boolean writeStalled(long nowMillis) {
        long since = writingSinceMillis;
        return since != 0 && nowMillis - since > WRITE_STALL_MILLIS;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
        synchronized (queue) {
            queue.clear();
            queuedBytes = 0;
        }
    }
}
