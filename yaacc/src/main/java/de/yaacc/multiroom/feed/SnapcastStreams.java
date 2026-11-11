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
package de.yaacc.multiroom.feed;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastStream;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The stream that YAACC creates on a snapserver to play into: one tcp source in server mode per phone, the group that
 * plays on it, and the cleanup. All calls block (they make control calls): use them from a worker, never from the main
 * thread or from inside a delivery of {@code SnapcastRpc} listeners.
 *
 * <p><b>The stream.</b> Its name is {@code YAACC <phone name> <marker>}. The phone name is cut down to letters, digits,
 * space, {@code -} and {@code _} (32 characters at most). The marker is 8 random hex characters made once per install
 * and kept by the {@link MarkerStore}; it is what makes a stream ours. The URI is
 * {@code tcp://0.0.0.0:<port>?name=<name>&mode=server&sampleformat=48000:16:2&codec=flac}, the name percent-encoded in
 * full, so that nothing in it can become a query parameter of its own. The port is chosen by YAACC from
 * {@value #FIRST_PORT} to {@code FIRST_PORT + PORT_COUNT - 1}, leaving out every port that a stream URI of the status
 * names; a port that the server can not bind (answer -32603) makes it try the next, at most {@value #PORT_COUNT}
 * times.
 *
 * <p><b>What is trusted.</b> Everything in a status is untrusted. A stream is adopted only if its id is our exact
 * name (with the marker) and it is {@code tcp}, {@code mode=server}, has the expected sample format, is bound to
 * {@code 0.0.0.0} and uses a port of our range. A stream that only looks like ours is never touched. YAACC removes a
 * stream only if it carries our marker (and is {@code tcp}) and no group uses it, or if it is the one it recorded
 * itself and still looks like ours; it restores a group's previous stream only if the group is still on our stream and
 * the previous stream still exists. The port that {@link #ensure()} reports is the one YAACC asked for (or, for an
 * adopted stream, one of our own range); the caller connects the {@link PcmFeeder} to it at the <b>control
 * connection's peer address</b>, never to a host from the status.
 *
 * <p><b>Idle cleanup.</b> {@link #removeIfIdle()} removes the stream after {@code idleTimeoutMillis} without a call of
 * {@link #noteAudio()}. The server's own stream status {@code idle} plays no part: digital silence between tracks
 * turns it idle while data flows.
 *
 * <p>Instances are thread safe; the control calls run one at a time.
 */
public final class SnapcastStreams {

    /** The first port YAACC asks for. */
    public static final int FIRST_PORT = 4953;
    /** How many ports from {@link #FIRST_PORT} on YAACC may use (and the most tries of one {@link #ensure()}). */
    public static final int PORT_COUNT = 10;
    /** The default time without audio after which {@link #removeIfIdle()} removes the stream: 10 minutes. */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L;
    /** The longest phone name in the stream name, in characters. */
    public static final int MAX_PHONE_NAME_LENGTH = 32;
    /** The start of every stream name of ours. */
    public static final String NAME_PREFIX = "YAACC ";
    /** Used when a phone name has no letter or digit. */
    public static final String DEFAULT_PHONE_NAME = "Phone";
    /** The sample format of the stream. */
    public static final PcmFormat FORMAT = PcmFormat.SNAPCAST_DEFAULT;

    /** Resource key of the hint text for a server that refuses the stream: use a static source (see the README). */
    public static final String HINT_STATIC_SOURCE = "snapcast_feed_static_source_hint";
    /** Resource key of the hint text for a server that wants a login (authorization is not supported). */
    public static final String HINT_AUTHORIZATION = "snapcast_feed_authorization_hint";

    private static final int JSON_RPC_INTERNAL_ERROR = -32603;
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_LEFTOVERS_PER_RUN = 8;
    private static final String BIND_ALL = "0.0.0.0";

    /** Keeps the per-install marker. The app implements it with its preferences; it must survive restarts. */
    public interface MarkerStore {
        /** @return the stored marker, or null if there is none */
        String load();

        /** Stores the marker. */
        void save(String marker);
    }

    /** The outcome of {@link #ensure()}. */
    public enum Status {
        /** The stream exists and can be fed. */
        READY,
        /** The server refused to add the stream (see the reason and the hint). */
        REFUSED,
        /** The server wants credentials (401) or refuses them (403). */
        UNAUTHORIZED,
        /** Every port of YAACC's range is named by a stream of the status. */
        NO_FREE_PORT,
        /** A stream of ours is in use by a group but does not have the right shape; nothing was changed. */
        CONFLICT,
        /** The connection failed (no network, timeout, unreadable answer). */
        FAILED
    }

    /** The result of {@link #ensure()}; never carries an exception. */
    public static final class EnsureResult {
        private final Status status;
        private final String streamId;
        private final int port;
        private final boolean adopted;
        private final String reason;
        private final String hintKey;

        EnsureResult(Status status, String streamId, int port, boolean adopted, String reason, String hintKey) {
            this.status = status;
            this.streamId = streamId;
            this.port = port;
            this.adopted = adopted;
            this.reason = reason;
            this.hintKey = hintKey;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isReady() {
            return status == Status.READY;
        }

        /** The id of the stream; null unless READY. */
        public String getStreamId() {
            return streamId;
        }

        /** The port to feed (at the peer address of the control connection); 0 unless READY. */
        public int getPort() {
            return port;
        }

        /** True if an existing stream of ours was taken over, false if it was added now. */
        public boolean isAdopted() {
            return adopted;
        }

        /** Display text for an error row: the server's reason, cleaned and cut; null when READY. */
        public String getReason() {
            return reason;
        }

        /** The key of a hint text the UI shows with the reason, or null. */
        public String getHintKey() {
            return hintKey;
        }
    }

    /** The outcome of {@link #useFor}. */
    public enum UseResult {
        /** The group plays our stream. */
        OK,
        /** There is no stream (call {@link #ensure()}), or it vanished from the server. */
        NOT_READY,
        /** The server has no such group (any more). */
        GROUP_NOT_FOUND,
        /** The call failed. */
        FAILED
    }

    /** The outcome of {@link #release}. */
    public enum ReleaseResult {
        /** The group is back on its previous stream. */
        RESTORED,
        /** Nothing was done: not recorded, the group moved elsewhere, it is gone, or the previous stream is gone. */
        LEFT_ALONE,
        /** The call failed; the record is kept so that a retry works. */
        FAILED
    }

    private final SnapcastOperations operations;
    private final MonotonicClock clock;
    private final long idleTimeoutMillis;
    private final String marker;
    private final String streamName;
    private final AtomicLong lastAudioMillis = new AtomicLong();

    // guarded by this
    private String currentId;
    private final Map<String, String> previousByGroup = new LinkedHashMap<>();

    /** The real clock, the default idle timeout and a {@link SecureRandom} for the marker. */
    public SnapcastStreams(SnapcastOperations operations, String phoneName, MarkerStore markerStore) {
        this(operations, phoneName, markerStore, MonotonicClock.SYSTEM, DEFAULT_IDLE_TIMEOUT_MILLIS, new SecureRandom());
    }

    /**
     * @param operations       the control calls of the server (a stream is made on one server)
     * @param phoneName        the name of this phone; it is cut down for the stream name
     * @param markerStore      keeps the marker across restarts
     * @param clock            the time source for the idle timeout
     * @param idleTimeoutMillis the time without audio after which {@link #removeIfIdle()} removes the stream
     * @param random           the source of the marker; a {@link SecureRandom} in the app
     */
    public SnapcastStreams(SnapcastOperations operations, String phoneName, MarkerStore markerStore,
                           MonotonicClock clock, long idleTimeoutMillis, Random random) {
        if (operations == null || markerStore == null || clock == null || random == null || idleTimeoutMillis < 0) {
            throw new IllegalArgumentException("bad arguments");
        }
        this.operations = operations;
        this.clock = clock;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.marker = loadOrCreateMarker(markerStore, random);
        this.streamName = NAME_PREFIX + sanitizePhoneName(phoneName) + " " + marker;
        this.lastAudioMillis.set(clock.nowMillis());
    }

    private static String loadOrCreateMarker(MarkerStore store, Random random) {
        String stored = null;
        try {
            stored = store.load();
        } catch (RuntimeException e) {
            // an unreadable store gives a new marker below
        }
        if (isMarker(stored)) {
            return stored;
        }
        String fresh = String.format(Locale.ROOT, "%08x", random.nextInt());
        try {
            store.save(fresh);
        } catch (RuntimeException e) {
            // the marker lives in memory only: streams of an earlier run are not recognised as ours
        }
        return fresh;
    }

    private static boolean isMarker(String text) {
        if (text == null || text.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /** The per-install marker (8 lower-case hex characters). */
    public String getMarker() {
        return marker;
    }

    /** The name, and with it the id, of our stream: {@code YAACC <phone name> <marker>}. */
    public String getStreamName() {
        return streamName;
    }

    // ---- names and URIs

    /**
     * The phone name as it goes into the stream name: letters and digits (of any script), {@code -} and {@code _} stay,
     * every other character becomes {@code _}, runs of spaces become one, leading and trailing spaces go, at most
     * {@value #MAX_PHONE_NAME_LENGTH} characters (counted as code points, so a pair is never cut). A name without a
     * letter or digit becomes {@value #DEFAULT_PHONE_NAME}.
     */
    static String sanitizePhoneName(String name) {
        if (name == null) {
            return DEFAULT_PHONE_NAME;
        }
        StringBuilder out = new StringBuilder();
        boolean afterSpace = true; // drops leading spaces and collapses runs
        boolean hasLetterOrDigit = false;
        int count = 0;
        int i = 0;
        while (i < name.length() && count < MAX_PHONE_NAME_LENGTH) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == ' ') {
                if (!afterSpace) {
                    out.append(' ');
                    count++;
                }
                afterSpace = true;
                continue;
            }
            boolean letterOrDigit = Character.isLetterOrDigit(codePoint);
            hasLetterOrDigit |= letterOrDigit;
            out.appendCodePoint(letterOrDigit || codePoint == '-' || codePoint == '_' ? codePoint : '_');
            afterSpace = false;
            count++;
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return hasLetterOrDigit && end > 0 ? out.substring(0, end) : DEFAULT_PHONE_NAME;
    }

    /** The URI of the tcp source for a stream name and a port. */
    static String buildUri(int port, String streamName) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        return "tcp://" + BIND_ALL + ":" + port + "?name=" + percentEncode(streamName) + "&mode=server&sampleformat="
                + FORMAT.toSampleFormat() + "&codec=flac";
    }

    /**
     * Percent-encodes everything except the unreserved characters {@code A-Z a-z 0-9 - . _ ~}, as UTF-8. A space is
     * {@code %20} (the server does not decode {@code +}), and {@code &}, {@code =}, {@code %} and {@code +} can not
     * leave the value.
     */
    static String percentEncode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    // ---- recognising our streams

    /** True if the id has the shape of a name we make: {@code YAACC <something> <our marker>}. */
    private boolean carriesMarker(String id) {
        return id.length() >= NAME_PREFIX.length() + 2 + marker.length() && id.length() <= MAX_NAME_LENGTH
                && id.startsWith(NAME_PREFIX) && id.endsWith(" " + marker);
    }

    private boolean isAdoptable(SnapcastStream stream) {
        int port = stream.getPort();
        return streamName.equals(stream.getId())
                && "tcp".equals(stream.getScheme())
                && streamName.equals(stream.getQueryParameter("name"))
                && "server".equals(stream.getQueryParameter("mode"))
                && FORMAT.toSampleFormat().equals(stream.getQueryParameter("sampleformat"))
                && BIND_ALL.equals(stream.getHost())
                && port >= FIRST_PORT && port < FIRST_PORT + PORT_COUNT;
    }

    private static boolean usedByGroup(SnapcastServerStatus status, String streamId) {
        for (SnapcastGroup group : status.getGroups()) {
            if (streamId.equals(group.getStreamId())) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> usedPorts(SnapcastServerStatus status, String exceptStreamId) {
        Set<Integer> ports = new HashSet<>();
        for (SnapcastStream stream : status.getStreams()) {
            if (stream.getPort() > 0 && !stream.getId().equals(exceptStreamId)) {
                ports.add(stream.getPort());
            }
        }
        return ports;
    }

    private static String reasonOf(SnapcastException failure) {
        return SnapcastDisplayText.of(failure.describe(), MAX_REASON_LENGTH);
    }

    // ---- ensure

    /**
     * Makes sure the stream exists: adopts our own, or adds one (see the class comment). Also removes leftovers of
     * ours that no group uses (crash recovery). Never throws for what a server does.
     */
    public synchronized EnsureResult ensure() {
        final SnapcastServerStatus status;
        try {
            status = operations.getStatus();
        } catch (SnapcastException failure) {
            return failedResult(failure, false);
        }
        pruneRecords(status);

        SnapcastStream existing = status.findStream(streamName);
        String removedId = null;
        if (existing != null) {
            if (isAdoptable(existing)) {
                currentId = existing.getId();
                lastAudioMillis.set(clock.nowMillis());
                removeLeftoversIn(status);
                return new EnsureResult(Status.READY, existing.getId(), existing.getPort(), true, null, null);
            }
            // ours by name but not usable: replace it, unless a group plays it
            if (usedByGroup(status, existing.getId())) {
                return new EnsureResult(Status.CONFLICT, null, 0, false,
                        "a stream of YAACC with another configuration is in use", HINT_STATIC_SOURCE);
            }
            try {
                operations.removeStream(existing.getId());
            } catch (SnapcastException failure) {
                return failedResult(failure, false);
            }
            removedId = existing.getId();
            if (removedId.equals(currentId)) {
                currentId = null;
            }
        }

        Set<Integer> used = usedPorts(status, removedId);
        List<Integer> candidates = new ArrayList<>(PORT_COUNT);
        for (int port = FIRST_PORT; port < FIRST_PORT + PORT_COUNT; port++) {
            if (!used.contains(port)) {
                candidates.add(port);
            }
        }
        if (candidates.isEmpty()) {
            return new EnsureResult(Status.NO_FREE_PORT, null, 0, false,
                    "every port from " + FIRST_PORT + " to " + (FIRST_PORT + PORT_COUNT - 1) + " is in use", HINT_STATIC_SOURCE);
        }

        SnapcastException lastRefusal = null;
        for (int port : candidates) {
            final String answeredId;
            try {
                answeredId = operations.addStream(buildUri(port, streamName));
            } catch (SnapcastException failure) {
                if (failure.getKind() == SnapcastException.Kind.SERVER_ERROR
                        && failure.getCode() == JSON_RPC_INTERNAL_ERROR) {
                    lastRefusal = failure; // most likely a port that is taken: the next one
                    continue;
                }
                return failedResult(failure, true);
            }
            if (!streamName.equals(answeredId)) {
                // never take over an id that a server names for us: it could be a stream of the user
                return new EnsureResult(Status.REFUSED, null, 0, false, "the server answered with another stream id",
                        HINT_STATIC_SOURCE);
            }
            currentId = answeredId;
            lastAudioMillis.set(clock.nowMillis());
            removeLeftoversIn(status);
            return new EnsureResult(Status.READY, answeredId, port, false, null, null);
        }
        return failedResult(lastRefusal, true);
    }

    private EnsureResult failedResult(SnapcastException failure, boolean fromAddStream) {
        if (failure.isAuthorizationError()) {
            return new EnsureResult(Status.UNAUTHORIZED, null, 0, false, reasonOf(failure), HINT_AUTHORIZATION);
        }
        if (fromAddStream && failure.getKind() == SnapcastException.Kind.SERVER_ERROR) {
            return new EnsureResult(Status.REFUSED, null, 0, false, reasonOf(failure), HINT_STATIC_SOURCE);
        }
        return new EnsureResult(Status.FAILED, null, 0, false, "no answer from the server (" + failure.getKind() + ")", null);
    }

    // ---- groups

    /**
     * Lets a group play our stream and remembers the stream it played before. A group that is on our stream already
     * is left as it is (and its remembered previous stream is kept).
     */
    public synchronized UseResult useFor(String groupId) {
        if (currentId == null) {
            return UseResult.NOT_READY;
        }
        if (groupId == null || groupId.isEmpty()) {
            return UseResult.GROUP_NOT_FOUND;
        }
        final SnapcastServerStatus status;
        try {
            status = operations.getStatus();
        } catch (SnapcastException failure) {
            return UseResult.FAILED;
        }
        pruneRecords(status);
        if (status.findStream(currentId) == null) {
            currentId = null; // the server lost it (a restart); ensure() makes it again
            return UseResult.NOT_READY;
        }
        SnapcastGroup group = status.findGroup(groupId);
        if (group == null) {
            return UseResult.GROUP_NOT_FOUND;
        }
        if (!currentId.equals(group.getStreamId())) {
            previousByGroup.put(groupId, group.getStreamId());
            try {
                operations.setGroupStream(groupId, currentId);
            } catch (SnapcastException failure) {
                previousByGroup.remove(groupId);
                return UseResult.FAILED;
            }
        }
        lastAudioMillis.set(clock.nowMillis());
        return UseResult.OK;
    }

    /**
     * Puts a group back on the stream it played before {@link #useFor}, but only if it is still on our stream and
     * the previous stream still exists. A group that someone moved elsewhere in the meantime is left alone.
     */
    public synchronized ReleaseResult release(String groupId) {
        if (groupId == null || !previousByGroup.containsKey(groupId)) {
            return ReleaseResult.LEFT_ALONE;
        }
        final SnapcastServerStatus status;
        try {
            status = operations.getStatus();
        } catch (SnapcastException failure) {
            return ReleaseResult.FAILED;
        }
        return restore(status, groupId);
    }

    /** Restores one recorded group using a status that was just read; drops the record unless the call failed. */
    private ReleaseResult restore(SnapcastServerStatus status, String groupId) {
        String previous = previousByGroup.get(groupId);
        SnapcastGroup group = status.findGroup(groupId);
        boolean restorable = currentId != null && group != null && currentId.equals(group.getStreamId())
                && previous != null && !previous.isEmpty() && status.findStream(previous) != null;
        if (!restorable) {
            previousByGroup.remove(groupId);
            return ReleaseResult.LEFT_ALONE;
        }
        try {
            operations.setGroupStream(groupId, previous);
        } catch (SnapcastException failure) {
            return ReleaseResult.FAILED;
        }
        previousByGroup.remove(groupId);
        return ReleaseResult.RESTORED;
    }

    private void pruneRecords(SnapcastServerStatus status) {
        Set<String> groupIds = new HashSet<>();
        for (SnapcastGroup group : status.getGroups()) {
            groupIds.add(group.getId());
        }
        previousByGroup.keySet().retainAll(groupIds);
    }

    /** The number of groups with a remembered previous stream. */
    synchronized int recordedGroupCount() {
        return previousByGroup.size();
    }

    // ---- cleanup

    /**
     * Tells that audio was written: restarts the idle timer. Cheap, never blocks, callable from any thread.
     */
    public void noteAudio() {
        lastAudioMillis.set(clock.nowMillis());
    }

    /**
     * Removes the stream (after giving the groups their previous streams back) if no audio was noted for the idle
     * timeout. Call it from a timer of the owner. The caller closes its {@link PcmFeeder} first or has none.
     *
     * @return true if the stream was removed now
     */
    public synchronized boolean removeIfIdle() {
        if (currentId == null || clock.nowMillis() - lastAudioMillis.get() < idleTimeoutMillis) {
            return false;
        }
        return shutdown();
    }

    /**
     * Gives every group that YAACC moved its previous stream back and removes our stream (on user request, or when the
     * last player ended). Only a stream that still looks like ours is removed.
     *
     * @return true if nothing is left to do (also when there was nothing); false if a call failed, in which case the
     * records are kept and a second call continues
     */
    public synchronized boolean shutdown() {
        if (currentId == null && previousByGroup.isEmpty()) {
            return true;
        }
        final SnapcastServerStatus status;
        try {
            status = operations.getStatus();
        } catch (SnapcastException failure) {
            return false;
        }
        boolean done = true;
        for (String groupId : new ArrayList<>(previousByGroup.keySet())) {
            if (restore(status, groupId) == ReleaseResult.FAILED) {
                done = false;
            }
        }
        if (currentId != null) {
            SnapcastStream stream = status.findStream(currentId);
            if (stream != null && carriesMarker(stream.getId()) && "tcp".equals(stream.getScheme())) {
                try {
                    operations.removeStream(currentId);
                } catch (SnapcastException failure) {
                    done = false;
                }
            }
            if (done) {
                currentId = null;
            }
        }
        return done;
    }

    /**
     * Removes streams that carry our marker, are {@code tcp} and are used by no group, other than the one we hold: the
     * leftovers of a crash. At most 8 per call.
     *
     * @return how many were removed
     */
    public synchronized int removeLeftovers() {
        final SnapcastServerStatus status;
        try {
            status = operations.getStatus();
        } catch (SnapcastException failure) {
            return 0;
        }
        return removeLeftoversIn(status);
    }

    private int removeLeftoversIn(SnapcastServerStatus status) {
        int removed = 0;
        for (SnapcastStream stream : status.getStreams()) {
            if (removed >= MAX_LEFTOVERS_PER_RUN) {
                break;
            }
            if (stream.getId().equals(currentId) || !carriesMarker(stream.getId()) || !"tcp".equals(stream.getScheme())
                    || usedByGroup(status, stream.getId())) {
                continue;
            }
            try {
                operations.removeStream(stream.getId());
                removed++;
            } catch (SnapcastException failure) {
                // best effort: the next run tries again
            }
        }
        return removed;
    }
}
