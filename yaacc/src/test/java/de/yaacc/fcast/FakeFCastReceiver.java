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

import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable FCast receiver for the tests (protocol v3 as the reference receivers speak it, see {@code docs/tech.md},
 * "FCast (issue #327)"). It listens on <b>port 0</b> of the loopback address (the test asks {@link #port()}; no test binds a
 * fixed port) and records every packet it receives, from every connection, in arrival order.
 *
 * <p>The wire format (a little-endian size that counts the opcode and the body, one opcode byte, an optional UTF-8 JSON
 * body, 32000 bytes at most) is written <b>here, independently of the production codec</b>, so that the codec tests have a
 * second opinion: the codec is not checked against itself.
 *
 * <p>What it does is chosen with {@link Options}: the greeting {@code Version} it sends first (the desktop receiver sends 4,
 * the Android receiver 3), the highest version it speaks (3, or 2 and 1 for an old receiver, which sends no {@code Initial}),
 * the answer to {@code Ping}, a ping of its own, silence, a stalled reader. For anything else a test gives its own
 * {@link Script}, which gets the {@link Session} of one accepted connection.
 */
public final class FakeFCastReceiver implements AutoCloseable {

    /** The opcodes of protocol v3 (the fixture's own copy). */
    public static final class Op {
        public static final int PLAY = 1;
        public static final int PAUSE = 2;
        public static final int RESUME = 3;
        public static final int STOP = 4;
        public static final int SEEK = 5;
        public static final int PLAYBACK_UPDATE = 6;
        public static final int VOLUME_UPDATE = 7;
        public static final int SET_VOLUME = 8;
        public static final int PLAYBACK_ERROR = 9;
        public static final int SET_SPEED = 10;
        public static final int VERSION = 11;
        public static final int PING = 12;
        public static final int PONG = 13;
        public static final int INITIAL = 14;
        public static final int PLAY_UPDATE = 15;
        public static final int SET_PLAYLIST_ITEM = 16;
        public static final int SUBSCRIBE_EVENT = 17;
        public static final int UNSUBSCRIBE_EVENT = 18;
        public static final int EVENT = 19;

        private Op() {
        }
    }

    /** The largest packet (opcode plus body) of protocol v3. */
    public static final int MAX_PACKET = 32000;

    /** One packet as it was on the wire. */
    public static final class Packet {
        public final int opcode;
        /** The body text, or null if the packet had none. */
        public final String body;

        public Packet(int opcode, String body) {
            this.opcode = opcode;
            this.body = body;
        }

        public JSONObject json() throws Exception {
            return new JSONObject(body);
        }

        @Override
        public String toString() {
            return "Packet{opcode=" + opcode + ", body=" + body + "}";
        }
    }

    /** The packet size announced by a peer exceeded the limit. */
    public static final class OversizeException extends IOException {
        public final long declaredSize;

        public OversizeException(long declaredSize) {
            super("packet of " + declaredSize + " bytes is over the limit of " + MAX_PACKET);
            this.declaredSize = declaredSize;
        }
    }

    /** The framing of protocol v3, written without the production code. */
    public static final class Wire {
        private Wire() {
        }

        public static void write(OutputStream out, int opcode, byte[] body) throws IOException {
            int bodyLength = body == null ? 0 : body.length;
            long size = 1L + bodyLength;
            byte[] packet = new byte[4 + 1 + bodyLength];
            packet[0] = (byte) (size & 0xff);
            packet[1] = (byte) ((size >> 8) & 0xff);
            packet[2] = (byte) ((size >> 16) & 0xff);
            packet[3] = (byte) ((size >> 24) & 0xff);
            packet[4] = (byte) opcode;
            if (bodyLength > 0) {
                System.arraycopy(body, 0, packet, 5, bodyLength);
            }
            out.write(packet);
            out.flush();
        }

        /**
         * Reads one packet.
         *
         * @return the packet, or null if the peer closed the connection before the first byte of a packet
         * @throws OversizeException if the announced size is over {@link #MAX_PACKET} (nothing more is read)
         * @throws EOFException      if the peer closed inside a packet
         */
        public static Packet read(InputStream in) throws IOException {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            long size = first;
            for (int i = 1; i < 4; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("closed inside the size field");
                }
                size |= ((long) b) << (8 * i);
            }
            if (size > MAX_PACKET) {
                throw new OversizeException(size);
            }
            if (size < 1) {
                throw new IOException("a packet without an opcode (size " + size + ")");
            }
            int opcode = in.read();
            if (opcode < 0) {
                throw new EOFException("closed before the opcode");
            }
            int bodyLength = (int) size - 1;
            String body = null;
            if (bodyLength > 0) {
                byte[] bytes = new byte[bodyLength];
                int done = 0;
                while (done < bodyLength) {
                    int n = in.read(bytes, done, bodyLength - done);
                    if (n < 0) {
                        throw new EOFException("closed inside the body");
                    }
                    done += n;
                }
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            return new Packet(opcode, body);
        }
    }

    /** How the ready-made behaviour acts. All fields have the behaviour of a normal v3 receiver. */
    public static final class Options {
        /** The {@code Version} the receiver sends first, on its own; 0: none (the Android receiver 3, the desktop receiver 4). */
        public int greetingVersion = 3;
        /** The highest version it speaks; the session uses the smaller of this and the sender's. 1 and 2 send no {@code Initial}. */
        public int speaksVersion = 3;
        public String displayName = "Fake Receiver";
        public String appName = "FakeFCast";
        public String appVersion = "1.0";
        /** Answer a {@code Ping} with a {@code Pong}. */
        public boolean answerPings = true;
        /** Send a {@code Ping} of its own this often (0: never); a {@code Pong} from the sender is counted. */
        public long pingEveryMillis = 0;
        /** Say nothing at all: no greeting, no {@code Initial}, no {@code Pong}; it still reads and records. */
        public boolean silent = false;
        /** After the handshake do not read any more (the sender's writes pile up) until {@link FakeFCastReceiver#release()}. */
        public boolean stallAfterHandshake = false;
        /**
         * A sender {@code Version} of 4 or more ends the session: the desktop receiver switches to TLS at once and this fixture
         * has no TLS, so a client that sends 4 before it can do the upgrade is a bug (phase 2 adds a v4 fixture).
         */
        public boolean endOnVersion4 = true;
        /** A volume to report with the {@code Initial} (a {@code VolumeUpdate} follows, as the receivers do). */
        public double initialVolume = 1.0;

        public Options greeting(int version) {
            this.greetingVersion = version;
            return this;
        }

        public Options speaks(int version) {
            this.speaksVersion = version;
            return this;
        }

        public Options name(String name) {
            this.displayName = name;
            return this;
        }

        public Options ignorePings() {
            this.answerPings = false;
            return this;
        }

        public Options pingEvery(long millis) {
            this.pingEveryMillis = millis;
            return this;
        }

        public Options silent() {
            this.silent = true;
            return this;
        }

        public Options stalled() {
            this.stallAfterHandshake = true;
            return this;
        }
    }

    /** What a test does with one accepted connection. */
    public interface Script {
        void run(Session session) throws Exception;
    }

    /** One accepted connection as seen by a script. */
    public final class Session {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        /** The version this session settled on (min of both), 0 until a {@code Version} arrived. */
        private volatile int negotiated;
        private volatile boolean sentInitial;
        private final AtomicInteger pongs = new AtomicInteger();

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        /** The next packet of the sender, recorded; null when it closed the connection. */
        public Packet read() throws IOException {
            Packet packet = Wire.read(in);
            if (packet != null) {
                received.add(packet);
                if (packet.opcode == Op.PONG) {
                    pongs.incrementAndGet();
                }
            }
            return packet;
        }

        public void send(int opcode, String json) throws IOException {
            synchronized (writeLock) {
                Wire.write(out, opcode, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
            }
        }

        public void sendVersion(int version) throws IOException {
            send(Op.VERSION, "{\"version\":" + version + "}");
        }

        public void sendInitial(Options o) throws IOException {
            JSONObject json = new JSONObject();
            try {
                json.put("displayName", o.displayName);
                json.put("appName", o.appName);
                json.put("appVersion", o.appVersion);
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
            send(Op.INITIAL, json.toString());
        }

        /** {@code state}: 0 idle, 1 playing, 2 paused. A negative time or duration is left out of the message. */
        public void sendPlaybackUpdate(int state, double time, double duration, double speed) throws IOException {
            StringBuilder sb = new StringBuilder("{\"generationTime\":").append(System.currentTimeMillis())
                    .append(",\"state\":").append(state);
            if (time >= 0) {
                sb.append(",\"time\":").append(time);
            }
            if (duration >= 0) {
                sb.append(",\"duration\":").append(duration);
            }
            if (speed >= 0) {
                sb.append(",\"speed\":").append(speed);
            }
            send(Op.PLAYBACK_UPDATE, sb.append('}').toString());
        }

        public void sendVolumeUpdate(double volume) throws IOException {
            send(Op.VOLUME_UPDATE, "{\"generationTime\":" + System.currentTimeMillis() + ",\"volume\":" + volume + "}");
        }

        /** What a receiver says when another sender (or the receiver itself) started something. */
        public void sendPlayUpdate(String container, String url) throws IOException {
            send(Op.PLAY_UPDATE, "{\"generationTime\":" + System.currentTimeMillis()
                    + ",\"playData\":{\"container\":" + JSONObject.quote(container) + ",\"url\":" + JSONObject.quote(url) + "}}");
        }

        public void sendPlaybackError(String message) throws IOException {
            send(Op.PLAYBACK_ERROR, "{\"message\":" + JSONObject.quote(message) + "}");
        }

        public void sendPing() throws IOException {
            send(Op.PING, null);
        }

        public void sendPong() throws IOException {
            send(Op.PONG, null);
        }

        /** Announces a packet larger than the limit and sends a few bytes of it; the connection stays open. */
        public void sendOversize(long declaredSize) throws IOException {
            byte[] header = new byte[]{
                    (byte) (declaredSize & 0xff), (byte) ((declaredSize >> 8) & 0xff),
                    (byte) ((declaredSize >> 16) & 0xff), (byte) ((declaredSize >> 24) & 0xff),
                    (byte) Op.PLAYBACK_UPDATE, '{', '"'};
            sendRaw(header);
        }

        /** A packet without an opcode: size 0. */
        public void sendEmptyPacket() throws IOException {
            sendRaw(new byte[]{0, 0, 0, 0});
        }

        public void sendRaw(byte[] bytes) throws IOException {
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
        }

        public int negotiatedVersion() {
            return negotiated;
        }

        public int pongCount() {
            return pongs.get();
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private final ServerSocket serverSocket;
    private final Options options;
    private final Script script;
    private final List<Packet> received = new CopyOnWriteArrayList<>();
    private final List<Session> sessions = Collections.synchronizedList(new ArrayList<Session>());
    private final AtomicInteger accepted = new AtomicInteger();
    private final CountDownLatch released = new CountDownLatch(1);
    private final Thread acceptThread;
    private volatile boolean closed;

    /** A receiver that behaves as the options say. */
    public static FakeFCastReceiver start(Options options) throws IOException {
        return new FakeFCastReceiver(options, null);
    }

    /** A normal v3 receiver. */
    public static FakeFCastReceiver start() throws IOException {
        return start(new Options());
    }

    /** A receiver that runs the script on every accepted connection (the options are not used). */
    public static FakeFCastReceiver start(Script script) throws IOException {
        return new FakeFCastReceiver(new Options(), script);
    }

    private FakeFCastReceiver(Options options, Script script) throws IOException {
        this.options = options;
        this.script = script;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        this.acceptThread = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    final Session session = new Session(socket);
                    sessions.add(session);
                    accepted.incrementAndGet();
                    if (closed) {
                        session.close();
                        return;
                    }
                    Thread handler = new Thread(() -> {
                        try {
                            if (this.script != null) {
                                this.script.run(session);
                            } else {
                                behave(session);
                            }
                        } catch (Exception | Error ignored) {
                            // the sender went away or the test ended: nothing to report
                        }
                    }, "fake-fcast-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-fcast-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** The ready-made behaviour of a receiver. */
    private void behave(final Session session) throws Exception {
        final Options o = options;
        if (!o.silent && o.greetingVersion > 0) {
            session.sendVersion(o.greetingVersion);
        }
        Thread pinger = null;
        if (!o.silent && o.pingEveryMillis > 0) {
            pinger = new Thread(() -> {
                try {
                    while (!closed) {
                        Thread.sleep(o.pingEveryMillis);
                        session.sendPing();
                    }
                } catch (Exception ignored) {
                    // the connection ended
                }
            }, "fake-fcast-pinger");
            pinger.setDaemon(true);
            pinger.start();
        }
        boolean handshakeDone = false;
        while (true) {
            if (handshakeDone && o.stallAfterHandshake) {
                released.await();
                handshakeDone = false;
            }
            Packet packet = session.read();
            if (packet == null) {
                return;
            }
            if (o.silent) {
                continue;
            }
            switch (packet.opcode) {
                case Op.VERSION:
                    int theirs = new JSONObject(packet.body).getInt("version");
                    if (theirs >= 4 && o.endOnVersion4) {
                        session.close();
                        return;
                    }
                    session.negotiated = Math.min(theirs, o.speaksVersion);
                    if (session.negotiated >= 3 && !session.sentInitial) {
                        session.sentInitial = true;
                        session.sendInitial(o);
                    }
                    session.sendVolumeUpdate(o.initialVolume);
                    handshakeDone = true;
                    break;
                case Op.PING:
                    if (o.answerPings) {
                        session.sendPong();
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** The address of the receiver as text for the code under test: {@code 127.0.0.1:<port>}. */
    public String endpoint() {
        return "127.0.0.1:" + port();
    }

    /** The port of the receiver; hand it to the code under test. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public int acceptedCount() {
        return accepted.get();
    }

    /** All packets received so far, from every connection, in arrival order. */
    public List<Packet> received() {
        return new ArrayList<>(received);
    }

    /** The packets with this opcode received so far. */
    public List<Packet> received(int opcode) {
        List<Packet> result = new ArrayList<>();
        for (Packet packet : received) {
            if (packet.opcode == opcode) {
                result.add(packet);
            }
        }
        return result;
    }

    /** Waits until at least {@code count} packets with this opcode arrived; false after the timeout. */
    public boolean awaitPackets(int opcode, int count, long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < end) {
            if (received(opcode).size() >= count) {
                return true;
            }
            Thread.sleep(5);
        }
        return received(opcode).size() >= count;
    }

    /** The n-th accepted session (waits a moment for the accept thread), for a test that talks on it directly. */
    public Session session(int index) throws InterruptedException {
        long end = System.nanoTime() + 2_000_000_000L;
        while (sessions.size() <= index && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        return sessions.get(index);
    }

    /** Sends the same packet to every connection (what a receiver does when another sender changed something). */
    public void broadcast(int opcode, String json) throws IOException {
        synchronized (sessions) {
            for (Session session : sessions) {
                session.send(opcode, json);
            }
        }
    }

    /** Lets a stalled receiver read again. */
    public void release() {
        released.countDown();
    }

    /** Closes the n-th connection from the receiver's side. */
    public void closeConnection(int index) throws InterruptedException {
        session(index).close();
    }

    @Override
    public void close() {
        closed = true;
        released.countDown();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing
        }
        synchronized (sessions) {
            for (Session session : sessions) {
                session.close();
            }
        }
    }
}
