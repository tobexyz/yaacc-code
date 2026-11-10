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

// THROW-AWAY SPIKE for task 1.5 of the Snapcast spec (#319). Not production code,
// not part of any Gradle module, never shipped. Plain Java 17, no dependencies:
//
//   java FeedSpike.java <host> [seconds] [--burst] [--no-group] [--allow-public-host]
//
// What it does (the Phase A "feed" path of design.md in miniature):
//   1. talks newline-delimited JSON-RPC 2.0 to the snapserver control port 1705,
//      calls Server.GetStatus and prints what it sees
//   2. Stream.AddStream with a tcp source in server mode named "YAACC spike" on the
//      first accepted port from 4953 upward (the next port is tried when the call is refused, except when the
//      name is already taken: then the run stops and tells you how to remove the leftover stream)
//   3. optionally moves every group that has a connected client to
//      the new stream with Group.SetStream, remembering the previous stream
//   4. connects to that tcp port and writes a 440 Hz sine as raw PCM, 48000 Hz,
//      16 bit little endian, 2 channels, no WAV header (the server reads raw bytes,
//      server/streamreader/asio_stream.hpp), in REAL TIME: 20 ms chunks, each
//      written at its exact deadline computed from System.nanoTime(), never early
//   5. prints the stream status from Server.GetStatus every 10 s
//   6. restores the groups' previous streams and calls Stream.RemoveStream
//
// --burst writes the same audio without pacing. The server reads its sources in
// real time anyway, so TCP back pressure stops a burst writer, but only after the
// socket buffers are full: the "lead" figure in the output is how many ms of audio
// the producer is ahead of the clock, i.e. stale audio queued between the producer
// and the speakers. That is why the feeder paces (design.md, "Feed").
//
// JSON: this file contains a tiny tolerant JSON reader (Json) instead of regexes. It
// returns null for anything malformed and the code only reads the few fields it needs.
// It is a spike helper, not a model for the production parser (task 2.1/2.2 caps line
// length, this one does not).
//
// Needs a rig or snapserver whose control port and a tcp source port are reachable
// from this machine (tools/snapcast-rig/README.md). No authentication support.
//
// Note: the control reader ignores JSON-RPC batch arrays (a throw-away spike; the real library accepts them).
// Exit code: 0 = run completed and checks passed, 1 = a check failed or an error
// occurred, 2 = usage error.

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

public final class FeedSpike {

    // ---- constants --------------------------------------------------------------
    static final int CONTROL_PORT = 1705;
    static final int FIRST_FEED_PORT = 4953;
    static final int PORT_TRIES = 10; // 4953..4962, the range published by the rig
    static final int RATE = 48000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_SAMPLE = 2;
    static final int FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE;
    static final int CHUNK_MS = 20;
    static final int CHUNK_FRAMES = RATE * CHUNK_MS / 1000; // 960
    static final long CHUNK_NANOS = CHUNK_MS * 1_000_000L;
    static final double TONE_HZ = 440.0;
    static final double AMPLITUDE = 0.3 * Short.MAX_VALUE; // about -10 dBFS
    static final String STREAM_NAME = "YAACC spike";
    static final String STREAM_NAME_ENCODED = "YAACC%20spike";
    static final long STATUS_INTERVAL_MS = 10_000;
    static final long FIRST_STATUS_MS = 3_000;
    /** A chunk written more than this after its deadline counts as late (an underrun risk). */
    static final long LATE_NANOS = CHUNK_NANOS;

    static volatile boolean stopRequested;

    private static boolean isLocalNetwork(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            byte[] raw = a.getAddress();
            boolean uniqueLocal6 = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || uniqueLocal6;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- entry point ------------------------------------------------------------
    public static void main(String[] args) {
        String host = null;
        int seconds = 300;
        boolean burst = false;
        boolean moveGroup = true;
        boolean allowPublic = false;
        boolean secondsGiven = false;
        for (String a : args) {
            if (a.equals("--burst")) {
                burst = true;
            } else if (a.equals("--no-group")) {
                moveGroup = false;
            } else if (a.equals("--allow-public-host")) {
                allowPublic = true;
            } else if (a.equals("-h") || a.equals("--help")) {
                usage();
                return;
            } else if (a.startsWith("--")) {
                System.err.println("unknown option " + a);
                usage();
                Runtime.getRuntime().halt(2);
            } else if (host == null) {
                host = a;
            } else if (!secondsGiven) {
                try {
                    seconds = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    seconds = -1;
                }
                secondsGiven = true;
            } else {
                System.err.println("too many arguments");
                usage();
                Runtime.getRuntime().halt(2);
            }
        }
        if (host == null || seconds < 1 || seconds > 86_400) {
            usage();
            Runtime.getRuntime().halt(2);
        }

        if (!allowPublic && !isLocalNetwork(host)) {
            System.err.println("refusing " + clean(host) + ": not a loopback, private or link-local address."
                    + " The spike takes over every group of the server with a test tone; use --allow-public-host only"
                    + " for a server you own.");
            Runtime.getRuntime().halt(2);
        }

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested = true; // Ctrl-C: let main clean up the stream it created
            try {
                mainThread.join(10_000);
            } catch (InterruptedException ignored) {
                // shutting down anyway
            }
        }));

        int rc;
        try {
            rc = new FeedSpike(host, seconds, burst, moveGroup).run();
        } catch (Exception e) {
            System.out.println("FAILED: " + e);
            rc = 1;
        }
        System.out.flush();
        if (rc != 0) {
            // no System.exit(): it would wait for the shutdown hook, which waits for us
            Runtime.getRuntime().halt(rc);
        }
    }

    static void usage() {
        System.err.println("usage: java FeedSpike.java <host> [seconds] [--burst] [--no-group] [--allow-public-host]");
        System.err.println("  host        snapserver host (control port " + CONTROL_PORT + ", feed ports "
                + FIRST_FEED_PORT + "-" + (FIRST_FEED_PORT + PORT_TRIES - 1) + ")");
        System.err.println("  seconds     feed duration, default 300");
        System.err.println("  --burst     write without real-time pacing (demonstrates why pacing is needed)");
        System.err.println("  --no-group  do not move a group to the new stream (clients then get no chunks)");
        System.err.println("  --allow-public-host  accept a host that is not loopback/private/link-local");
    }

    // ---- state ------------------------------------------------------------------
    private final String host;
    private final int seconds;
    private final boolean burst;
    private final boolean moveGroup;

    private final Stats stats = new Stats();
    private Control control;
    private String streamId;
    private int feedPort;
    private final List<String> movedGroupIds = new ArrayList<>();
    private final List<String> previousStreamIds = new ArrayList<>();
    private Socket feedSocket;
    private Instant feedStart;
    private Instant feedEnd;

    private FeedSpike(String host, int seconds, boolean burst, boolean moveGroup) {
        this.host = host;
        this.seconds = seconds;
        this.burst = burst;
        this.moveGroup = moveGroup;
    }

    // ---- the run ----------------------------------------------------------------
    private int run() throws Exception {
        boolean ok = true;
        int statusSamples = 0;
        int statusPlaying = 0;
        int minConnectedClients = Integer.MAX_VALUE;
        long t0 = 0;
        try {
            control = new Control(host, CONTROL_PORT);
            feedStart = Instant.now();
            System.out.println("== " + (burst ? "BURST" : "real-time paced") + " feed, " + seconds + " s, host " + host
                    + ", started " + feedStart + " (UTC)");

            // 1. Server.GetStatus
            Object status = control.call("Server.GetStatus", null);
            describe(status);
            int connected = connectedClients(status);
            if (connected < 2) {
                System.out.println("WARNING: only " + connected + " client(s) connected, the rig should show 2");
            }

            // 2. Stream.AddStream, next port on error
            addStream();
            System.out.println("stream '" + streamId + "' created, tcp source on port " + feedPort);

            // 3. Group.SetStream so the clients receive the stream
            if (moveGroup) {
                moveGroups(status);
            }

            // 4. feeder thread
            feedSocket = connectFeed(host, feedPort);
            final int totalChunks = (int) ((long) seconds * 1000 / CHUNK_MS);
            Thread feeder = new Thread(() -> feed(totalChunks), "feeder");
            feeder.setDaemon(true);
            t0 = System.nanoTime();
            stats.startNanos = t0;
            feeder.start();

            // 5. status every 10 s (plus one early check) while the feeder runs
            // In burst mode the feeder ends early; keep monitoring until the nominal end so the
            // status lines show the stream still playing what is queued.
            long nextStatusMs = FIRST_STATUS_MS;
            while (!stopRequested) {
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                boolean feeding = feeder.isAlive();
                if (!feeding && !(burst && elapsedMs < seconds * 1000L)) {
                    break;
                }
                if (feeding) {
                    feeder.join(250);
                } else {
                    Thread.sleep(250);
                }
                elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                if (elapsedMs >= nextStatusMs) {
                    nextStatusMs = (nextStatusMs == FIRST_STATUS_MS) ? STATUS_INTERVAL_MS : nextStatusMs + STATUS_INTERVAL_MS;
                    Object st = control.call("Server.GetStatus", null);
                    String s = streamStatus(st, streamId);
                    int conn = connectedClients(st);
                    statusSamples++;
                    if ("playing".equals(s)) {
                        statusPlaying++;
                    }
                    minConnectedClients = Math.min(minConnectedClients, conn);
                    System.out.printf("[%4d s] stream status=%s, clients connected=%d | %s%n",
                            elapsedMs / 1000, s, conn, stats.line());
                }
            }
            if (stopRequested) {
                System.out.println("interrupted, cleaning up");
                ok = false;
            }
            feeder.join(2000);

            // 6. verdict inputs
            if (stats.error != null) {
                System.out.println("feeder error: " + stats.error);
                ok = false;
            }
        } finally {
            feedEnd = Instant.now(); // before the groups go back to their idle streams
            cleanup();
        }

        long totalMs = t0 == 0 ? 0 : (System.nanoTime() - t0) / 1_000_000;
        System.out.println();
        System.out.println("==== summary ====");
        System.out.println("mode                  : " + (burst ? "burst (no pacing)" : "real time, " + CHUNK_MS + " ms chunks"));
        System.out.println("format                : " + RATE + " Hz, " + (BYTES_PER_SAMPLE * 8) + " bit LE, " + CHANNELS
                + " ch, raw PCM, " + TONE_HZ + " Hz sine");
        System.out.println("stream / port         : " + streamId + " / " + feedPort);
        System.out.println("requested / elapsed   : " + seconds + " s / " + totalMs / 1000.0 + " s");
        System.out.println("chunks written        : " + stats.chunks + " (" + stats.bytes / 1024 + " KiB)");
        System.out.printf("lead over real time   : max %.0f ms (paced: expect <= %d ms)%n", stats.maxLeadNs / 1e6, CHUNK_MS + 5);
        System.out.printf("late chunks (>%d ms)  : %d, worst lateness %.1f ms%n", CHUNK_MS, stats.lateChunks, stats.maxLateNs / 1e6);
        System.out.printf("slowest write() call  : %.1f ms (long values = TCP back pressure)%n", stats.maxWriteNs / 1e6);
        System.out.println("status samples        : " + statusPlaying + " of " + statusSamples + " were 'playing'");
        System.out.println("min clients connected : " + (minConnectedClients == Integer.MAX_VALUE ? "n/a" : minConnectedClients));
        System.out.println("control notifications : " + (control == null ? 0 : control.notifications) + " received");
        if (burst) {
            System.out.printf("BURST RESULT: the producer got ahead of the clock by up to %.1f s (data queued in socket buffers"
                    + " between us and the server); it would be heard that much later and a stop/seek would act that late."
                    + " The server itself still reads in real time. Compare with a paced run.%n", stats.maxLeadNs / 1e9);
        } else {
            if (stats.lateChunks > 0) {
                System.out.println("CHECK FAILED: " + stats.lateChunks + " chunk(s) were written more than " + CHUNK_MS
                        + " ms late");
                ok = false;
            }
            if (statusSamples > 0 && statusPlaying != statusSamples) {
                System.out.println("CHECK FAILED: the stream was not 'playing' in every status sample");
                ok = false;
            }
            if (statusSamples == 0 && seconds >= 5) {
                System.out.println("CHECK FAILED: no status sample was taken");
                ok = false;
            }
            Instant from = feedStart == null ? null : feedStart.plusSeconds(5);
            System.out.println("The 'no underruns on the clients' criterion needs the client logs (see the README). A client logs"
                    + " 'No chunks available' about once a second whenever it has nothing to play, so count only inside the run window:");
            System.out.println("  docker compose logs -t --since " + from + " --until " + feedEnd
                    + " snapclient-1 snapclient-2 | grep -c \"No chunks available\"   (expect 0; run it per client to see which one)");
        }
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAILED");
        return ok ? 0 : 1;
    }

    // ---- control actions ----------------------------------------------------------
    private void addStream() throws Exception {
        String lastError = "no attempt";
        for (int k = 0; k < PORT_TRIES; k++) {
            int port = FIRST_FEED_PORT + k;
            // The name is URL-encoded by hand: snapserver decodes %20 but not '+'.
            String uri = "tcp://0.0.0.0:" + port + "?name=" + STREAM_NAME_ENCODED
                    + "&mode=server&sampleformat=48000:16:2&codec=flac";
            try {
                Object res = control.call("Stream.AddStream", "{\"streamUri\":" + q(uri) + "}");
                // snapserver's code answers {"id":..}, its doc says {"stream_id":..}: accept both
                String id = Json.str(Json.at(res, "id"));
                if (id == null) {
                    id = Json.str(Json.at(res, "stream_id"));
                }
                if (id == null) {
                    throw new IOException("Stream.AddStream gave no stream id: " + clean(res));
                }
                if (!id.equals(STREAM_NAME)) {
                    throw new IOException("Stream.AddStream answered the stream id '" + clean(id) + "', not the requested '"
                            + STREAM_NAME + "'; not touching it");
                }
                streamId = id;
                feedPort = port;
                return;
            } catch (RpcException e) {
                lastError = e.getMessage();
                System.out.println("AddStream on port " + port + " refused: " + lastError);
                if (lastError.contains("already exists")) {
                    throw new IOException("a stream named '" + STREAM_NAME + "' exists already (leftover of an earlier run?)."
                            + " Remove it with: echo '{\"id\":1,\"jsonrpc\":\"2.0\",\"method\":\"Stream.RemoveStream\","
                            + "\"params\":{\"id\":\"" + STREAM_NAME + "\"}}' | nc " + host + " " + CONTROL_PORT);
                }
            }
        }
        throw new IOException("Stream.AddStream failed on ports " + FIRST_FEED_PORT + "-"
                + (FIRST_FEED_PORT + PORT_TRIES - 1) + ", last error: " + lastError
                + " (static source fallback, see design.md risk 3)");
    }

    /**
     * Moves every group that has a connected client to the new stream (a new snapclient gets a group of its own, so
     * moving one group would feed only one of the two rig clients) and remembers each previous stream.
     */
    private void moveGroups(Object status) throws Exception {
        List<?> groups = Json.list(Json.at(status, "server", "groups"));
        int moved = 0;
        for (Object g : groups) {
            boolean connected = false;
            for (Object c : Json.list(Json.at(g, "clients"))) {
                if (Boolean.TRUE.equals(Json.at(c, "connected"))) {
                    connected = true;
                    break;
                }
            }
            String gid = Json.str(Json.at(g, "id"));
            if (!connected || gid == null) {
                continue;
            }
            String prev = Json.str(Json.at(g, "stream_id"));
            control.call("Group.SetStream", "{\"id\":" + q(gid) + ",\"stream_id\":" + q(streamId) + "}");
            movedGroupIds.add(gid);
            previousStreamIds.add(prev);
            moved++;
            System.out.println("group " + clean(gid) + " moved from stream '" + clean(prev) + "' to '" + streamId + "'");
        }
        if (moved == 0) {
            System.out.println("no group with a connected client found, not moving any group (clients will not receive the stream)");
        }
    }

    private void cleanup() {
        if (feedSocket != null) {
            try {
                feedSocket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        if (control == null) {
            return;
        }
        for (int i = 0; i < movedGroupIds.size(); i++) {
            String gid = movedGroupIds.get(i);
            String prev = previousStreamIds.get(i);
            if (prev == null) {
                continue;
            }
            try {
                control.call("Group.SetStream", "{\"id\":" + q(gid) + ",\"stream_id\":" + q(prev) + "}");
                System.out.println("group " + clean(gid) + " restored to stream '" + clean(prev) + "'");
            } catch (Exception e) {
                System.out.println("could not restore the group " + gid + ": " + e);
            }
        }
        if (streamId != null) {
            try {
                control.call("Stream.RemoveStream", "{\"id\":" + q(streamId) + "}");
                System.out.println("stream '" + streamId + "' removed");
            } catch (Exception e) {
                System.out.println("could not remove stream '" + streamId + "': " + e);
            }
        }
        control.close();
    }

    // ---- the feeder -------------------------------------------------------------
    private static Socket connectFeed(String host, int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                    // retrying
                }
                LockSupport.parkNanos(200_000_000L);
            }
        }
        throw new IOException("cannot connect to the feed port " + host + ":" + port + ": " + last);
    }

    /** Writes totalChunks chunks of 20 ms; in paced mode chunk n is written at start + n * 20 ms, never earlier. */
    private void feed(int totalChunks) {
        final byte[] buf = new byte[CHUNK_FRAMES * FRAME_BYTES];
        final double step = 2.0 * Math.PI * TONE_HZ / RATE;
        double phase = 0;
        try {
            final OutputStream out = feedSocket.getOutputStream();
            final long start = stats.startNanos;
            for (int n = 0; n < totalChunks && !stopRequested; n++) {
                final long deadline = start + n * CHUNK_NANOS;
                if (!burst) {
                    long remaining;
                    while ((remaining = deadline - System.nanoTime()) > 0) {
                        LockSupport.parkNanos(remaining);
                    }
                }
                final long begin = System.nanoTime();
                final long lateNs = begin - deadline;
                if (!burst && lateNs > LATE_NANOS) {
                    stats.lateChunks++;
                }
                if (lateNs > stats.maxLateNs) {
                    stats.maxLateNs = lateNs;
                }

                int p = 0;
                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    short v = (short) Math.round(Math.sin(phase) * AMPLITUDE);
                    phase += step;
                    if (phase >= 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI;
                    }
                    for (int ch = 0; ch < CHANNELS; ch++) { // little endian, same sample on both channels
                        buf[p++] = (byte) (v & 0xff);
                        buf[p++] = (byte) ((v >> 8) & 0xff);
                    }
                }
                out.write(buf, 0, buf.length);
                final long end = System.nanoTime();

                long writeNs = end - begin;
                if (writeNs > stats.maxWriteNs) {
                    stats.maxWriteNs = writeNs;
                }
                // audio time handed over so far minus wall time since the start
                long leadNs = (n + 1) * CHUNK_NANOS - (end - start);
                if (leadNs > stats.maxLeadNs) {
                    stats.maxLeadNs = leadNs;
                }
                stats.lastLeadNs = leadNs;
                stats.bytes += buf.length;
                stats.chunks = n + 1;
            }
        } catch (IOException e) {
            stats.error = e.toString();
        }
    }

    /** Written by the feeder thread, read by the main thread for the status lines. */
    static final class Stats {
        volatile long startNanos;
        volatile long chunks;
        volatile long bytes;
        volatile long lateChunks;
        volatile long maxLateNs;
        volatile long maxWriteNs;
        volatile long maxLeadNs;
        volatile long lastLeadNs;
        volatile String error;

        String line() {
            return String.format("sent %.2f MiB, lead %+.0f ms (max %.0f), late chunks %d (worst %.1f ms), slowest write %.1f ms",
                    bytes / 1048576.0, lastLeadNs / 1e6, maxLeadNs / 1e6, lateChunks, maxLateNs / 1e6, maxWriteNs / 1e6);
        }
    }

    // ---- printing of Server.GetStatus --------------------------------------------------
    private static void describe(Object result) {
        System.out.println("snapserver version : " + clean(Json.str(Json.at(result, "server", "server", "snapserver", "version"))));
        List<?> groups = Json.list(Json.at(result, "server", "groups"));
        System.out.println("groups             : " + groups.size());
        for (Object g : groups) {
            System.out.println("  group " + clean(Json.str(Json.at(g, "id"))) + " name='" + clean(Json.str(Json.at(g, "name")))
                    + "' stream='" + Json.str(Json.at(g, "stream_id")) + "'");
            for (Object c : Json.list(Json.at(g, "clients"))) {
                Object vol = Json.at(c, "config", "volume", "percent");
                System.out.println("    client " + clean(Json.str(Json.at(c, "id"))) + " connected=" + Json.at(c, "connected")
                        + " volume=" + vol);
            }
        }
        List<?> streams = Json.list(Json.at(result, "server", "streams"));
        System.out.println("streams            : " + streams.size());
        for (Object s : streams) {
            System.out.println("  stream '" + clean(Json.str(Json.at(s, "id"))) + "' status=" + clean(Json.str(Json.at(s, "status")))
                    + " scheme=" + Json.str(Json.at(s, "uri", "scheme")));
        }
    }

    private static int connectedClients(Object result) {
        int n = 0;
        for (Object g : Json.list(Json.at(result, "server", "groups"))) {
            for (Object c : Json.list(Json.at(g, "clients"))) {
                if (Boolean.TRUE.equals(Json.at(c, "connected"))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String streamStatus(Object result, String id) {
        for (Object s : Json.list(Json.at(result, "server", "streams"))) {
            if (id.equals(Json.str(Json.at(s, "id")))) {
                return Json.str(Json.at(s, "status"));
            }
        }
        return "(stream missing)";
    }

    /** JSON string literal. */
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Server-supplied text for the terminal: control characters replaced, length capped. */
    static String clean(Object o) {
        String t = String.valueOf(o);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length() && i < 200; i++) {
            char c = t.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return b.toString();
    }

    // ---- JSON-RPC over the control socket ---------------------------------------------
    static final class RpcException extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;

        RpcException(int code, String message) {
            super("JSON-RPC error " + code + ": " + clean(message));
            this.code = code;
        }
    }

    /** Newline-delimited JSON-RPC 2.0 client. One request at a time; notifications are counted and dropped. */
    static final class Control {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private int nextId = 1;
        volatile int notifications;

        Control(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10_000);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        /** Reads one line of at most 1 MiB (the server is not trusted); null at the end of the stream. */
        private String readLine() throws IOException {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
                }
                if (b == '\n') {
                    return buf.toString(StandardCharsets.UTF_8);
                }
                if (buf.size() >= 1 << 20) {
                    throw new IOException("control line longer than 1 MiB");
                }
                buf.write(b);
            }
        }

        /** Sends a request and returns its "result"; skips notifications and answers to other ids. */
        synchronized Object call(String method, String paramsJson) throws IOException, RpcException {
            final int id = nextId++;
            String req = "{\"id\":" + id + ",\"jsonrpc\":\"2.0\",\"method\":" + q(method)
                    + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (true) {
                String line;
                try {
                    line = readLine();
                } catch (SocketTimeoutException e) {
                    throw new IOException("no answer to " + method + " within 10 s");
                }
                if (line == null) {
                    throw new IOException("control connection closed by the server");
                }
                Object msg = Json.parse(line);
                if (msg instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) msg;
                    Object mid = m.get("id");
                    if (mid instanceof Number && ((Number) mid).intValue() == id) {
                        Object err = m.get("error");
                        if (err != null) {
                            Object code = Json.at(err, "code");
                            // Snapcast puts a fixed title in "message" and the real reason in "data"
                            Object data = Json.at(err, "data");
                            String text = String.valueOf(Json.at(err, "message")) + (data == null ? "" : " (" + data + ")");
                            throw new RpcException(code instanceof Number ? ((Number) code).intValue() : 0, text);
                        }
                        return m.get("result");
                    }
                    if (m.containsKey("method")) {
                        notifications++;
                    }
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("no answer to " + method + " within 10 s (only notifications)");
                }
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    // ---- tiny tolerant JSON reader ------------------------------------------------------
    /**
     * Minimal recursive-descent JSON reader: objects become LinkedHashMap, arrays ArrayList, numbers Double,
     * plus String, Boolean and null. parse() returns null for malformed input (it never throws); nesting is
     * capped at 64 levels. at() walks a path of String keys and Integer indexes and returns null when
     * a step does not fit, so callers only deal with null.
     */
    static final class Json {
        private final String s;
        private int p;
        private int depth;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            try {
                Json j = new Json(text);
                j.ws();
                Object v = j.value();
                j.ws();
                return j.p == j.s.length() ? v : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        static Object at(Object node, Object... path) {
            for (Object k : path) {
                if (k instanceof String && node instanceof Map) {
                    node = ((Map<?, ?>) node).get(k);
                } else if (k instanceof Integer && node instanceof List) {
                    List<?> l = (List<?>) node;
                    int i = (Integer) k;
                    node = (i >= 0 && i < l.size()) ? l.get(i) : null;
                } else {
                    return null;
                }
            }
            return node;
        }

        static String str(Object o) {
            return o instanceof String ? (String) o : null;
        }

        static List<?> list(Object o) {
            return o instanceof List ? (List<?>) o : new ArrayList<>();
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
        }

        private Object value() {
            if (++depth > 64) {
                throw new IllegalStateException("nesting too deep");
            }
            try {
                switch (s.charAt(p)) {
                    case '{':
                        return object();
                    case '[':
                        return array();
                    case '"':
                        return string();
                    case 't':
                        literal("true");
                        return Boolean.TRUE;
                    case 'f':
                        literal("false");
                        return Boolean.FALSE;
                    case 'n':
                        literal("null");
                        return null;
                    default:
                        return number();
                }
            } finally {
                depth--;
            }
        }

        private void literal(String word) {
            if (!s.startsWith(word, p)) {
                throw new IllegalStateException("bad literal");
            }
            p += word.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            p++; // {
            ws();
            if (s.charAt(p) == '}') {
                p++;
                return m;
            }
            while (true) {
                ws();
                if (s.charAt(p) != '"') {
                    throw new IllegalStateException("key expected");
                }
                String key = string();
                ws();
                if (s.charAt(p++) != ':') {
                    throw new IllegalStateException("':' expected");
                }
                ws();
                m.put(key, value());
                ws();
                char c = s.charAt(p++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalStateException("',' or '}' expected");
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            p++; // [
            ws();
            if (s.charAt(p) == ']') {
                p++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(p++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new IllegalStateException("',' or ']' expected");
                }
            }
        }

        private String string() {
            p++; // opening quote
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(p++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        b.append(e);
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                        break;
                    default:
                        throw new IllegalStateException("bad escape");
                }
            }
        }

        private Double number() {
            int begin = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) {
                p++;
            }
            if (begin == p) {
                throw new IllegalStateException("value expected");
            }
            return Double.valueOf(s.substring(begin, p));
        }
    }
}
