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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import de.yaacc.fcast.FakeFCastReceiver.Packet;
import de.yaacc.fcast.FakeFCastReceiver.Wire;
import de.yaacc.fcast.protocol.FCastPin;

/**
 * A loopback receiver for the v4 connection set-up (task 7.2): it sends {@code Version}, reads the sender's {@code Version} and,
 * if both are 4, serves TLS with a self-signed EC key (made with {@code keytool} of the running JDK, once per test run), then
 * answers {@code Ping} with {@code Pong} and records what it reads. Port 0, like the other fakes.
 */
public final class FakeFCastTlsReceiver implements AutoCloseable {
    /** The opcode of a version 4 message. */
    public static final int FLATBUF = 20;

    /** A self-signed key pair of the kind the receivers make (ECDSA P-256), with the pin a receiver would announce. */
    public static final class Keys {
        private static final String PASSWORD = "test-only";
        private static final List<Keys> CACHE = new ArrayList<>();

        private final KeyStore store;
        private final X509Certificate certificate;

        private Keys(KeyStore store, X509Certificate certificate) {
            this.store = store;
            this.certificate = certificate;
        }

        /** The n-th distinct key (0, 1, ...): two receivers with different keys. */
        public static synchronized Keys get(int n) throws Exception {
            while (CACHE.size() <= n) {
                CACHE.add(generate(CACHE.size()));
            }
            return CACHE.get(n);
        }

        private static Keys generate(int n) throws Exception {
            File dir = Files.createTempDirectory("fcast-tls-keys").toFile();
            File file = new File(dir, "keys" + n + ".p12");
            String keytool = new File(new File(System.getProperty("java.home"), "bin"), "keytool").getPath();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "receiver", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA", "-dname", "CN=fcast-test-" + n, "-validity", "3650", "-storetype", "PKCS12",
                    "-keystore", file.getPath(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                    .redirectErrorStream(true).start();
            byte[] output = readAll(process.getInputStream());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + new String(output, StandardCharsets.UTF_8));
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(file)) {
                store.load(in, PASSWORD.toCharArray());
            }
            file.delete();
            dir.delete();
            return new Keys(store, (X509Certificate) store.getCertificate("receiver"));
        }

        private static byte[] readAll(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }

        public X509Certificate certificate() {
            return certificate;
        }

        public FCastPin pin(FCastPin.Source source) {
            return FCastPin.of(certificate, source);
        }

        private SSLContext context(String protocol) throws Exception {
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance(protocol);
            context.init(factory.getKeyManagers(), null, null);
            return context;
        }
    }

    /** How this receiver acts. */
    public static final class Options {
        /** The number in the {@code Version} it sends first. */
        public int greetingVersion = 4;
        /** The TLS protocol it serves ({@code TLSv1.3}; {@code TLSv1.2} to see that the sender refuses it). */
        public String tlsProtocol = "TLSv1.3";
        /** If true it sends nothing after the {@code Version} (no TLS answer: the handshake times out). */
        public boolean silentAfterVersion;
        /** If true it sends garbage instead of a {@code Version} first. */
        public boolean garbageFirst;
        /** If true it closes the connection right after the sender's {@code Version}. */
        public boolean closeAfterVersion;
        /** If true, after the handshake it sends what a v4 receiver sends (its introduction and the volume). */
        public boolean welcome;
        /** If true it repeats what the sender asks for (a load, a state, a volume, a speed, a seek) like a v4 receiver relays it, and starts to play a loaded item. */
        public boolean relay;
    }

    private final ServerSocket server;
    private final Keys keys;
    private final Options options;
    private final Thread acceptor;
    private final AtomicInteger accepted = new AtomicInteger();
    private final List<Integer> senderVersions = Collections.synchronizedList(new ArrayList<Integer>());
    private final List<String> protocols = Collections.synchronizedList(new ArrayList<String>());
    private final List<Packet> packets = Collections.synchronizedList(new ArrayList<Packet>());
    private final List<byte[]> flatbufs = Collections.synchronizedList(new ArrayList<byte[]>());
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
    private volatile OutputStream secureOut;
    private volatile boolean closed;

    public FakeFCastTlsReceiver(Keys keys, Options options) throws IOException {
        this.keys = keys;
        this.options = options;
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::acceptLoop, "fake-fcast-tls-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** How many connections were made to this receiver (a fall back would make a second one). */
    public int accepted() {
        return accepted.get();
    }

    /** The versions in the sender's first packets, one per connection. */
    public List<Integer> senderVersions() {
        return new ArrayList<>(senderVersions);
    }

    /** The TLS protocol the handshake negotiated, one per secured connection. */
    public List<String> protocols() {
        return new ArrayList<>(protocols);
    }

    /** What was read after the set-up, plain or secured. */
    public List<Packet> packets() {
        return new ArrayList<>(packets);
    }

    /** The bodies of the {@code Flatbuf} packets the sender sent after the handshake. */
    public List<byte[]> flatbufs() {
        return new ArrayList<>(flatbufs);
    }

    /** Sends a {@code Flatbuf} packet (a serialized message) through the secured stream. */
    public void sendFlatbuf(byte[] message) throws IOException {
        OutputStream out = secureOut;
        if (out == null) {
            throw new IllegalStateException("no secured connection");
        }
        synchronized (out) {
            Wire.write(out, FLATBUF, message);
        }
    }

    /** Sends a packet through the secured stream. */
    public void sendSecure(int opcode, String body) throws IOException {
        OutputStream out = secureOut;
        if (out == null) {
            throw new IllegalStateException("no secured connection");
        }
        synchronized (out) {
            Wire.write(out, opcode, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                accepted.incrementAndGet();
                sockets.add(socket);
                Thread thread = new Thread(() -> serve(socket), "fake-fcast-tls-serve");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket plain) {
        try {
            InputStream in = plain.getInputStream();
            OutputStream out = plain.getOutputStream();
            if (options.garbageFirst) {
                Wire.write(out, FakeFCastReceiver.Op.PLAY, "{}".getBytes(StandardCharsets.UTF_8));
            } else {
                Wire.write(out, FakeFCastReceiver.Op.VERSION, ("{\"version\":" + options.greetingVersion + "}").getBytes(StandardCharsets.UTF_8));
            }
            Packet first = Wire.read(in);
            if (first == null) {
                return;
            }
            int version = first.opcode == FakeFCastReceiver.Op.VERSION ? first.json().optInt("version", 0) : 0;
            senderVersions.add(version);
            if (options.closeAfterVersion) {
                return;
            }
            if (version >= 4 && options.greetingVersion >= 4) {
                if (options.silentAfterVersion) {
                    Thread.sleep(10_000);
                    return;
                }
                SSLSocket tls = (SSLSocket) keys.context(options.tlsProtocol).getSocketFactory()
                        .createSocket(plain, plain.getInetAddress().getHostAddress(), plain.getPort(), true);
                tls.setUseClientMode(false);
                tls.setEnabledProtocols(new String[]{options.tlsProtocol});
                tls.startHandshake();
                protocols.add(tls.getSession().getProtocol());
                in = tls.getInputStream();
                out = tls.getOutputStream();
                secureOut = out;
                if (options.welcome) {
                    sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("receiverIntroduction"));
                    sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("volume"));
                }
            }
            while (!closed) {
                Packet packet = Wire.read(in);
                if (packet == null) {
                    return;
                }
                packets.add(packet);
                if (packet.opcode == FLATBUF && packet.raw != null) {
                    flatbufs.add(packet.raw);
                    if (options.relay) {
                        relay(packet.raw);
                    }
                }
                if (packet.opcode == FakeFCastReceiver.Op.PING) {
                    synchronized (out) {
                        Wire.write(out, FakeFCastReceiver.Op.PONG, null);
                    }
                }
            }
        } catch (Exception endOfConnection) {
            // the sender left, refused the key or the test ended
        } finally {
            try {
                plain.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private void relay(byte[] message) throws IOException {
        de.yaacc.fcast.protocol.v4.V4In in;
        try {
            in = de.yaacc.fcast.protocol.v4.V4Reader.read(message);
        } catch (de.yaacc.fcast.protocol.FCastProtocolException e) {
            return;
        }
        switch (in.kind) {
            case LOAD:
                sendFlatbuf(message);
                sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("statePlaying"));
                sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("progress"));
                break;
            case STATE:
            case VOLUME:
            case SPEED:
            case PROGRESS:
                sendFlatbuf(message);
                break;
            case OTHER:
                break;
            default:
                break;
        }
        if (isStop(message)) {
            sendFlatbuf(de.yaacc.fcast.protocol.V4Golden.get("stateIdle"));
        }
    }

    private static boolean isStop(byte[] message) {
        try {
            return de.yaacc.fcast.protocol.v4.V4Reader.read(message).kind == de.yaacc.fcast.protocol.v4.V4In.Kind.STOP;
        } catch (de.yaacc.fcast.protocol.FCastProtocolException e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        synchronized (sockets) {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }
}
