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
package de.yaacc.multiroom.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import de.yaacc.multiroom.SnapcastServerLink;
import de.yaacc.multiroom.control.GroupVolume;
import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastException;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastOperations;
import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The changes that the Snapcast rows and the group sheet make on a server: volume and mute of a group and of its clients,
 * the latency and the name of a client, the stream of a group, and the members of a group. Pure Java (the Android code
 * only forwards gestures and shows the failures).
 *
 * <ul>
 *   <li><b>Off the main thread.</b> Every method returns at once; the calls run on one of two executors. Volume
 *   changes have an executor of their own (a gesture on a group is up to 256 calls), so they can not hold up a mute
 *   or a stream change.</li>
 *   <li><b>Coalesced.</b> While a slider is dragged only the latest value of a group or a client is sent; values that
 *   arrive while a call is in flight replace each other.</li>
 *   <li><b>Start levels per gesture.</b> The group volume moves every connected member by the same number of points
 *   from the levels it had when the gesture began ({@link GroupVolume}), so dragging back restores every member's own
 *   level. {@link #beginGroupGesture} starts a gesture (the finger goes down); without it a pause of
 *   {@value #GESTURE_GAP_MILLIS} ms starts a new one (a keyboard or D-pad change). A gesture has an overall budget of
 *   {@value #VOLUME_BUDGET_MILLIS} ms, so a server that answers slowly can not keep the executor.</li>
 *   <li><b>Failures</b> are reported once to the {@link Reporter} as a {@link Failure}: a kind, and for a refusal of the
 *   server the reason as {@link SnapcastDisplayText#toastReason cleaned and shortened} text. Nothing else of what the
 *   server says reaches the user, nothing is logged. A change always works on the model as it is now (the group and the
 *   clients are looked up by id when the call runs), never on a copy taken earlier.</li>
 * </ul>
 */
public final class SnapcastControls {

    /** A pause between two volume values that starts a new gesture. */
    public static final long GESTURE_GAP_MILLIS = 2_000L;
    /** The most time a gesture of the group volume may take in total. */
    public static final long VOLUME_BUDGET_MILLIS = 5_000L;
    /** The most characters of the server's reason shown. */
    static final int MAX_REASON = SnapcastDisplayText.MAX_TOAST_REASON_CODE_POINTS;
    private static final int MAX_GESTURES = 64;

    /** Where the link of a server comes from (the receivers); null when there is none. */
    public interface Links {
        SnapcastServerLink linkOf(SnapcastServerKey key);
    }

    /** What the user did. */
    public enum Action {
        GROUP_VOLUME,
        GROUP_MUTE,
        CLIENT_VOLUME,
        CLIENT_MUTE,
        CLIENT_LATENCY,
        CLIENT_NAME,
        GROUP_STREAM,
        GROUP_CLIENTS
    }

    /** Why a change did not happen. Immutable. */
    public static final class Failure {

        /** The kinds the user is told apart. */
        public enum Kind {
            /** The control connection is down or was lost. */
            UNREACHABLE,
            /** The server did not answer in time. */
            TIMEOUT,
            /** The server wants a login, which YAACC does not support. */
            UNAUTHORIZED,
            /** The server refused; {@link #getReason()} is its cleaned and shortened reason. */
            REJECTED,
            /** The group or the client is no longer in the model of the server. */
            GONE,
            /** The change makes no sense (the last member of a group can not leave). */
            NOT_POSSIBLE,
            /** Anything else. */
            FAILED
        }

        private final Kind kind;
        private final String reason;

        /** @param reason the server's reason for a refusal (see {@link #getReason()}); null or empty for none */
        public Failure(Kind kind, String reason) {
            this.kind = kind;
            this.reason = reason == null ? "" : reason;
        }

        public Kind getKind() {
            return kind;
        }

        /** The server's reason as display text for a toast; empty unless the kind is {@link Kind#REJECTED}. */
        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "SnapcastControls.Failure{" + kind + "}";
        }
    }

    /** Told, on the worker thread, when a change failed. Must be quick; the Android code posts to the main thread. */
    public interface Reporter {
        void onFailure(Action action, Failure failure);
    }

    private static final class Gesture {
        final Map<String, Integer> levels;
        long lastMillis;

        Gesture(Map<String, Integer> levels, long now) {
            this.levels = levels;
            this.lastMillis = now;
        }
    }

    private final Links links;
    private final Executor actions;
    private final Executor volumes;
    private final MonotonicClock clock;
    private final Reporter reporter;
    private final Object lock = new Object();
    // the latest wanted value of each group and client slider, and which of them have a task queued (guarded by lock)
    private final Map<String, Integer> wantedGroupVolume = new HashMap<>();
    private final Set<String> queuedGroupVolume = new HashSet<>();
    private final Map<String, Integer> wantedClientVolume = new HashMap<>();
    private final Set<String> queuedClientVolume = new HashSet<>();
    private final Map<String, Gesture> gestures = new HashMap<>();

    public SnapcastControls(Links links, Executor actions, Executor volumes, MonotonicClock clock, Reporter reporter) {
        this.links = links;
        this.actions = actions;
        this.volumes = volumes;
        this.clock = clock;
        this.reporter = reporter;
    }

    private static String idOf(SnapcastServerKey key, String id) {
        return key.asString() + "\n" + id;
    }

    // ---- group volume

    /** The finger goes down on the group slider: the next value starts from the levels the members have now. */
    public void beginGroupGesture(SnapcastServerKey key, String groupId) {
        synchronized (lock) {
            gestures.remove(idOf(key, groupId));
        }
    }

    /** Sets the volume of a group (0 to 100). Returns at once; only the latest value of a quick series is sent. */
    public void setGroupVolume(final SnapcastServerKey key, final String groupId, int percent) {
        final String id = idOf(key, groupId);
        boolean schedule;
        synchronized (lock) {
            wantedGroupVolume.put(id, Math.max(0, Math.min(100, percent)));
            schedule = queuedGroupVolume.add(id);
        }
        if (schedule) {
            submit(volumes, Action.GROUP_VOLUME, () -> applyGroupVolume(key, groupId, id));
        }
    }

    private void applyGroupVolume(SnapcastServerKey key, String groupId, String id) throws Exception {
        Integer percent;
        synchronized (lock) {
            percent = wantedGroupVolume.remove(id);
            queuedGroupVolume.remove(id);
        }
        if (percent == null) {
            return;
        }
        Linked linked = linked(key);
        SnapcastServerStatus status = linked.status;
        SnapcastGroup group = status == null ? null : status.findGroup(groupId);
        if (group == null) {
            throw new Gone();
        }
        Map<String, Integer> start;
        long now = clock.nowMillis();
        synchronized (lock) {
            Gesture gesture = gestures.get(id);
            if (gesture == null || now - gesture.lastMillis > GESTURE_GAP_MILLIS) {
                pruneGestures(now);
                gesture = new Gesture(GroupVolume.levels(group), now);
                gestures.put(id, gesture);
            }
            gesture.lastMillis = now;
            start = gesture.levels;
        }
        linked.operations.setGroupVolume(group, start, percent, VOLUME_BUDGET_MILLIS);
    }

    private void pruneGestures(long now) {
        if (gestures.size() < MAX_GESTURES) {
            return;
        }
        gestures.values().removeIf(gesture -> now - gesture.lastMillis > GESTURE_GAP_MILLIS);
        if (gestures.size() >= MAX_GESTURES) {
            gestures.clear();
        }
    }

    /** For tests: the value waiting to be sent for a group, or null. */
    Integer pendingVolumeFor(SnapcastServerKey key, String groupId) {
        synchronized (lock) {
            return wantedGroupVolume.get(idOf(key, groupId));
        }
    }

    /** Mutes or unmutes a group. */
    public void setGroupMuted(final SnapcastServerKey key, final String groupId, final boolean muted) {
        submit(actions, Action.GROUP_MUTE, () -> linked(key).operations.setGroupMute(groupId, muted));
    }

    /** The group plays this stream. {@code streamId} is the server's own id (from the stream picker), used for the call only. */
    public void setGroupStream(final SnapcastServerKey key, final String groupId, final String streamId) {
        submit(actions, Action.GROUP_STREAM, () -> linked(key).operations.setGroupStream(groupId, streamId));
    }

    // ---- clients

    /** Sets the volume of a client (0 to 100); its mute flag is kept. Only the latest value of a quick series is sent. */
    public void setClientVolume(final SnapcastServerKey key, final String clientId, int percent) {
        final String id = idOf(key, clientId);
        boolean schedule;
        synchronized (lock) {
            wantedClientVolume.put(id, Math.max(0, Math.min(100, percent)));
            schedule = queuedClientVolume.add(id);
        }
        if (schedule) {
            submit(volumes, Action.CLIENT_VOLUME, () -> {
                Integer wanted;
                synchronized (lock) {
                    wanted = wantedClientVolume.remove(id);
                    queuedClientVolume.remove(id);
                }
                if (wanted != null) {
                    Linked linked = linked(key);
                    SnapcastClient client = clientOf(linked, clientId);
                    linked.operations.setClientVolume(clientId, client.getVolume().withPercent(wanted));
                }
            });
        }
    }

    /** Mutes or unmutes a client; its volume is kept. */
    public void setClientMuted(final SnapcastServerKey key, final String clientId, final boolean muted) {
        submit(actions, Action.CLIENT_MUTE, () -> {
            Linked linked = linked(key);
            SnapcastClient client = clientOf(linked, clientId);
            linked.operations.setClientVolume(clientId, new SnapcastVolume(client.getVolume().getPercent(), muted));
        });
    }

    /** Sets the latency of a client in milliseconds (the server's range is 0 to 10,000). */
    public void setClientLatency(final SnapcastServerKey key, final String clientId, final int latencyMs) {
        submit(actions, Action.CLIENT_LATENCY, () -> linked(key).operations.setClientLatency(clientId, latencyMs));
    }

    /** Renames a client; the name is cleaned first, an empty one resets the name. */
    public void renameClient(final SnapcastServerKey key, final String clientId, String name) {
        final String clean = SnapcastInputs.normalizeName(name);
        submit(actions, Action.CLIENT_NAME, () -> linked(key).operations.setClientName(clientId, clean));
    }

    // ---- members of a group

    /**
     * The change the link sheet made: some clients join the group, some members leave it, in one {@code Group.SetClients}
     * call that carries the <b>exact</b> member list. The list is computed from a fresh status (see below), so a client
     * that another controller added since the sheet was opened stays; nothing is sent when the change leaves the members as
     * they are; a change that would leave the group without a client is refused ({@code NOT_POSSIBLE}), because an empty list
     * dissolves the group. Clients that leave get a group of their own on the server, so none is left without one.
     */
    public void changeMembers(final SnapcastServerKey key, final String groupId, SnapcastLinkChoices.Change change) {
        if (change == null || change.isEmpty()) {
            return;
        }
        final List<String> added = new ArrayList<>(change.getAdded());
        final List<String> removed = new ArrayList<>(change.getRemoved());
        submit(actions, Action.GROUP_CLIENTS, () -> {
            Linked linked = linked(key);
            SnapcastGroup group = freshGroup(linked, groupId);
            List<String> wanted = SnapcastClientCandidates.wantedAfter(group, added, removed);
            if (wanted == null) {
                throw new NotPossible();
            }
            List<String> current = new ArrayList<>();
            for (SnapcastClient member : group.getClients()) {
                current.add(member.getId());
            }
            if (wanted.equals(current)) {
                return; // the group is as wanted already (somebody else did it)
            }
            linked.operations.setGroupClients(groupId, wanted);
        });
    }

    /**
     * The group as the server has it now, for a change of its members: {@code Group.SetClients} takes the full list and
     * removes every client that is not in it, so the list is computed from a fresh status (one round trip, within the rate
     * limit of the model; the cached model when the limit refuses or the read fails). A candidate list that a picker
     * showed is only a snapshot of the moment it was opened; this is what keeps a client that another controller added
     * meanwhile from being dropped.
     */
    private static SnapcastGroup freshGroup(Linked linked, String groupId) throws Gone {
        SnapcastServerStatus fresh = linked.operations.freshStatusOrModel();
        SnapcastGroup group = fresh == null ? null : fresh.findGroup(groupId);
        if (group == null) {
            throw new Gone();
        }
        return group;
    }

    // ---- plumbing

    private static final class Linked {
        final SnapcastOperations operations;
        final SnapcastServerStatus status;

        Linked(SnapcastOperations operations, SnapcastServerStatus status) {
            this.operations = operations;
            this.status = status;
        }
    }

    private static final class Gone extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class NotPossible extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class Unreachable extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private Linked linked(SnapcastServerKey key) throws Unreachable {
        SnapcastServerLink link = links.linkOf(key);
        SnapcastOperations operations = link == null ? null : link.getOperations();
        if (operations == null) {
            throw new Unreachable();
        }
        return new Linked(operations, link.getStatus());
    }

    private static SnapcastClient clientOf(Linked linked, String clientId) throws Gone {
        SnapcastClient client = linked.status == null ? null : linked.status.findClient(clientId);
        if (client == null) {
            throw new Gone();
        }
        return client;
    }

    /** One change that can throw what the library throws. */
    private interface Change {
        void run() throws Exception;
    }

    private void submit(Executor executor, final Action action, final Change change) {
        try {
            executor.execute(() -> {
                try {
                    change.run();
                } catch (Gone gone) {
                    report(action, new Failure(Failure.Kind.GONE, null));
                } catch (NotPossible impossible) {
                    report(action, new Failure(Failure.Kind.NOT_POSSIBLE, null));
                } catch (Unreachable unreachable) {
                    report(action, new Failure(Failure.Kind.UNREACHABLE, null));
                } catch (SnapcastException failure) {
                    report(action, failureOf(failure));
                } catch (RuntimeException failure) {
                    report(action, new Failure(Failure.Kind.FAILED, null)); // a wrong argument, or the library's own error
                } catch (Exception failure) {
                    report(action, new Failure(Failure.Kind.FAILED, null));
                }
            });
        } catch (RuntimeException rejected) {
            report(action, new Failure(Failure.Kind.FAILED, null)); // the executor is shut down
        }
    }

    private void report(Action action, Failure failure) {
        try {
            reporter.onFailure(action, failure);
        } catch (RuntimeException ignored) {
            // the reporter must not break the worker
        }
    }

    /** Maps a failure of the library; only a refusal of the server carries (cleaned, short) text of the server. */
    static Failure failureOf(SnapcastException failure) {
        switch (failure.getKind()) {
            case NOT_CONNECTED:
            case CLOSED:
            case IO:
                return new Failure(Failure.Kind.UNREACHABLE, null);
            case TIMEOUT:
                return new Failure(Failure.Kind.TIMEOUT, null);
            case SERVER_ERROR:
                if (failure.isAuthorizationError()) {
                    return new Failure(Failure.Kind.UNAUTHORIZED, null);
                }
                String reason = SnapcastDisplayText.toastReason(failure.describe(), MAX_REASON);
                return new Failure(Failure.Kind.REJECTED, SnapcastDisplayText.of(reason, MAX_REASON));
            default:
                return new Failure(Failure.Kind.FAILED, null);
        }
    }
}
