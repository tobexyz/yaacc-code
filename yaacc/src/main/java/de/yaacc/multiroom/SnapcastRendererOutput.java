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
package de.yaacc.multiroom;

/**
 * The output of the YAACC renderer (task 9.1), decided without Android. The renderer plays either on this device (the
 * default, the behaviour of before) or only into a Snapcast group: it feeds the group and the local audio output is off.
 * This class reads the two preferences, says which output a new play uses from the live facts, and which error the
 * controller and the user get when it can not be the group. Nothing here starts a connection; the {@link Probe} and the
 * {@link Waiter} are the app's.
 *
 * <p><b>The setting.</b> {@link #KEY_OUTPUT} is {@code device} (default) or {@code group}; {@link #KEY_GROUP} holds the
 * receiver id of the chosen group ({@code snapcast:<server key>:<group id>}, see {@link SnapcastGroupDevice#idOf}), never a
 * name. The id is validated at every read: it must parse from the right and be in the canonical form. A value that is
 * corrupt, unknown or unreadable gives {@code THIS_DEVICE} with {@link Setting#isDamaged()} set, so the renderer plays
 * locally and says so; it never crashes and never plays into a group it can not name exactly. Group mode with no group
 * chosen is not damaged but an error of its own ({@link Problem#NO_GROUP_CHOSEN}): the user asked for the group, so the
 * audio is not played on the phone instead.
 *
 * <p><b>Facts.</b> {@link GroupFacts} is what the model of the servers says about the chosen group right now. A server
 * that is listed but has no status yet, or only a status of an earlier connection, gets one wake and a short bounded wait
 * ({@link #awaitFacts}) so that a play right after the app started does not fail for a connection that is only coming up.
 * What only the feed can know (no free stream port, a name conflict, a server that refuses the stream, a busy slot) is not
 * guessed here: it comes back from the feed as its typed error.
 */
public final class SnapcastRendererOutput {

    /** The preference that holds the output ({@link #VALUE_DEVICE} or {@link #VALUE_GROUP}). */
    public static final String KEY_OUTPUT = "settings_renderer_output";
    /** The preference that holds the receiver id of the chosen group. */
    public static final String KEY_GROUP = "settings_renderer_output_group";
    /** This device: the default. */
    public static final String VALUE_DEVICE = "device";
    /** A Snapcast group. */
    public static final String VALUE_GROUP = "group";

    /** How long a play waits for a server that is listed but not connected, and the length of one step. */
    public static final long DEFAULT_MAX_WAIT_MILLIS = 4_000L;
    static final long STEP_MILLIS = 200L;

    private SnapcastRendererOutput() {
    }

    /** What the renderer plays on. */
    public enum Mode {
        THIS_DEVICE, GROUP
    }

    /** The two preferences, validated. Immutable. */
    public static final class Setting {
        private final Mode mode;
        private final String receiverId;
        private final boolean damaged;

        private Setting(Mode mode, String receiverId, boolean damaged) {
            this.mode = mode;
            this.receiverId = receiverId;
            this.damaged = damaged;
        }

        public Mode mode() {
            return mode;
        }

        /** The receiver id of the chosen group in group mode, else null (also in group mode with no group chosen). */
        public String receiverId() {
            return receiverId;
        }

        /** True when the stored value was not usable and this device is used instead; the user is told. */
        public boolean isDamaged() {
            return damaged;
        }

        @Override
        public String toString() {
            return "Setting{" + mode + (damaged ? ", damaged" : "") + "}"; // never the id
        }
    }

    /** Reads a preference as text; null for none. {@code SharedPreferences.getString(key, null)} in the app. */
    public interface StringSource {
        String get(String key);
    }

    /**
     * Reads the setting. Never throws.
     *
     * @param source the preferences; null or a source that fails counts as damaged
     */
    public static Setting read(StringSource source) {
        if (source == null) {
            return damaged();
        }
        try {
            String output = source.get(KEY_OUTPUT);
            if (output == null || output.isEmpty() || VALUE_DEVICE.equals(output)) {
                return new Setting(Mode.THIS_DEVICE, null, false);
            }
            if (!VALUE_GROUP.equals(output)) {
                return damaged();
            }
            String group = source.get(KEY_GROUP);
            if (group == null || group.isEmpty()) {
                return new Setting(Mode.GROUP, null, false);
            }
            return isCanonicalGroupId(group) ? new Setting(Mode.GROUP, group, false) : damaged();
        } catch (RuntimeException unreadable) {
            return damaged();
        }
    }

    private static Setting damaged() {
        return new Setting(Mode.THIS_DEVICE, null, true);
    }

    /** True for a receiver id that parses from the right and prints back to the same text. */
    public static boolean isCanonicalGroupId(String id) {
        SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
        return parsed != null && SnapcastGroupDevice.idOf(parsed.getServerKey(), parsed.getGroupId()).equals(id);
    }

    /** What the model of the servers knows about the chosen group. */
    public enum GroupFacts {
        /** The server is not in the server list (any more). */
        NOT_LISTED,
        /** The server is listed but has not answered yet. */
        PENDING,
        /** The group is in the last status of the server but the control connection is down. */
        OFFLINE,
        /** The server answered and has no such group. */
        GONE,
        /** The server is connected and has the group. */
        READY;

        /**
         * @param listed    the server is in the directory
         * @param hasStatus the server has delivered a status
         * @param hasGroup  that status has the group
         * @param online    the control connection is up
         */
        public static GroupFacts of(boolean listed, boolean hasStatus, boolean hasGroup, boolean online) {
            if (!listed) {
                return NOT_LISTED;
            }
            if (!hasStatus) {
                return PENDING;
            }
            if (!hasGroup) {
                return GONE;
            }
            return online ? READY : OFFLINE;
        }
    }

    /** Where a play goes. */
    public enum Output {
        /** Today's behaviour: the local player of this device. */
        LOCAL,
        /** The Snapcast group of the setting. */
        GROUP,
        /** Nowhere: the play is refused with the problem. */
        NONE
    }

    /** What is wrong, or to be said. */
    public enum Problem {
        NONE,
        /** The stored value was not usable: this device is used, the user is told. (Output {@code LOCAL}.) */
        DAMAGED_SETTING,
        /** Group mode but no group is chosen. */
        NO_GROUP_CHOSEN,
        /** Video or images in group mode: the group plays music only, nothing is shown locally. */
        NOT_MUSIC,
        /** The server of the group is not in the server list (removed, or never found since the app started). */
        SERVER_NOT_LISTED,
        /** The server did not answer (still pending or offline after the wait). */
        SERVER_UNREACHABLE,
        /** The server answered and the group is not there (any more). */
        GROUP_GONE,
        /** Group mode and the type of the content is unknown (no type given, no known file extension). */
        UNKNOWN_TYPE
    }

    /** What the content is, judged by its types. */
    public enum Content {
        /** Music: a group plays it. */
        PLAYABLE,
        /** Video, images or something that is not audio: a group plays music only. */
        NOT_MUSIC,
        /** No type is known for at least one item. */
        UNKNOWN_TYPE
    }

    /**
     * What the content is, from the types of its items (null or empty types are "unknown"): video or image, or a type that is
     * not audio, is {@link Content#NOT_MUSIC} (a group plays music only; checked first); else an item without a type makes
     * the content {@link Content#UNKNOWN_TYPE}; else {@link Content#PLAYABLE}. No items at all is unknown.
     */
    public static Content contentOf(java.util.List<String> mimeTypes) {
        if (mimeTypes == null || mimeTypes.isEmpty()) {
            return Content.UNKNOWN_TYPE;
        }
        boolean unknown = false;
        for (String type : mimeTypes) {
            if (type == null || type.trim().isEmpty()) {
                unknown = true;
            } else if (!type.trim().toLowerCase(java.util.Locale.ROOT).startsWith("audio/")) {
                return Content.NOT_MUSIC;
            }
        }
        return unknown ? Content.UNKNOWN_TYPE : Content.PLAYABLE;
    }

    /** True when a player made for {@code createdKey} is not for the output the setting says now (it was switched meanwhile). */
    public static boolean isStale(String createdKey, Setting now) {
        return createdKey == null || !createdKey.equals(outputKey(now));
    }

    /** The answer for one play. Immutable. */
    public static final class Decision {
        private final Output output;
        private final Problem problem;
        private final String receiverId;

        private Decision(Output output, Problem problem, String receiverId) {
            this.output = output;
            this.problem = problem;
            this.receiverId = receiverId;
        }

        public Output output() {
            return output;
        }

        public Problem problem() {
            return problem;
        }

        /** The group to play into when the output is {@link Output#GROUP}; else null. */
        public String receiverId() {
            return receiverId;
        }

        @Override
        public String toString() {
            return "Decision{" + output + ", " + problem + "}"; // never the id
        }
    }

    /** As {@link #decide(Setting, Content, GroupFacts)} for a content that is music or not. */
    public static Decision decide(Setting setting, boolean contentRefused, GroupFacts facts) {
        return decide(setting, contentRefused ? Content.NOT_MUSIC : Content.PLAYABLE, facts);
    }

    /**
     * The decision from the facts.
     *
     * @param content what the content is; refused in group mode unless it is music
     * @param facts   what the model says about the group; not looked at unless the mode is group with a group chosen and the
     *                content is music; a missing fact never plays (it counts as pending)
     */
    public static Decision decide(Setting setting, Content content, GroupFacts facts) {
        if (setting == null || setting.mode == Mode.THIS_DEVICE) {
            return new Decision(Output.LOCAL, setting != null && setting.damaged ? Problem.DAMAGED_SETTING : Problem.NONE, null);
        }
        if (setting.receiverId == null) {
            return refused(Problem.NO_GROUP_CHOSEN);
        }
        if (content == Content.NOT_MUSIC) {
            return refused(Problem.NOT_MUSIC);
        }
        if (content == Content.UNKNOWN_TYPE) {
            return refused(Problem.UNKNOWN_TYPE);
        }
        GroupFacts known = facts == null ? GroupFacts.PENDING : facts;
        switch (known) {
            case READY:
                return new Decision(Output.GROUP, Problem.NONE, setting.receiverId);
            case NOT_LISTED:
                return refused(Problem.SERVER_NOT_LISTED);
            case GONE:
                return refused(Problem.GROUP_GONE);
            case PENDING:
            case OFFLINE:
            default:
                return refused(Problem.SERVER_UNREACHABLE);
        }
    }

    private static Decision refused(Problem problem) {
        return new Decision(Output.NONE, problem, null);
    }

    /** The live facts of the app: the model of the servers and the wake of a connection. */
    public interface Probe {
        /** The facts about the group now. Cheap, no network. */
        GroupFacts facts(String receiverId);

        /** Connects to the server of the group if it is listed. Does not block. */
        void wake(String receiverId);
    }

    /** A clock and a sleep, injectable so that the tests do not wait. */
    public interface Waiter {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    /**
     * The facts of the group; while the server is pending or offline it is woken once and looked at every 200 ms until it
     * is ready, gone, not listed, or {@code maxWaitMillis} are over. The last facts are returned (the error follows from
     * them). An interrupt ends the wait at once and keeps the interrupt flag.
     */
    public static GroupFacts awaitFacts(String receiverId, Probe probe, Waiter waiter, long maxWaitMillis) {
        GroupFacts facts = probe.facts(receiverId);
        if (!isComingUp(facts)) {
            return facts;
        }
        probe.wake(receiverId);
        long deadline = waiter.nowMillis() + Math.max(0, maxWaitMillis);
        while (isComingUp(facts) && waiter.nowMillis() < deadline) {
            try {
                waiter.sleep(STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return facts;
            }
            facts = probe.facts(receiverId);
        }
        return facts;
    }

    private static boolean isComingUp(GroupFacts facts) {
        return facts == GroupFacts.PENDING || facts == GroupFacts.OFFLINE;
    }

    /** As {@link #resolve(Setting, Content, Probe, Waiter, long)} for a content that is music or not. */
    public static Decision resolve(Setting setting, boolean contentRefused, Probe probe, Waiter waiter, long maxWaitMillis) {
        return resolve(setting, contentRefused ? Content.NOT_MUSIC : Content.PLAYABLE, probe, waiter, maxWaitMillis);
    }

    /**
     * The decision for one play, with the wait for a connection that is coming up. The server is asked only in group mode
     * with a group chosen and music content: this device never touches the network here.
     */
    public static Decision resolve(Setting setting, Content content, Probe probe, Waiter waiter, long maxWaitMillis) {
        if (setting == null || setting.mode != Mode.GROUP || setting.receiverId == null || content != Content.PLAYABLE) {
            return decide(setting, content, null);
        }
        return decide(setting, content, awaitFacts(setting.receiverId, probe, waiter, maxWaitMillis));
    }

    /**
     * Which output a player was made for: {@code device} or {@code group:<id>}. A damaged setting plays locally, so its key
     * is {@code device}. Two players with the same key are for the same output; a change of the setting stops the others.
     */
    public static String outputKey(Setting setting) {
        if (setting == null || setting.mode == Mode.THIS_DEVICE) {
            return VALUE_DEVICE;
        }
        return VALUE_GROUP + ":" + (setting.receiverId == null ? "" : setting.receiverId);
    }

    /**
     * The receiver ids whose servers must be remembered: the selected receivers, and the chosen group of the renderer while the
     * output is the group (so the server is known after a restart without a scan, as for a selected group; with the output
     * back on this device it is forgotten again). A new set; the arguments are not changed.
     */
    public static java.util.Set<String> withRendererGroup(java.util.Collection<String> selection, Setting setting) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (selection != null) {
            ids.addAll(selection);
        }
        if (setting != null && setting.mode == Mode.GROUP && setting.receiverId != null) {
            ids.add(setting.receiverId);
        }
        return ids;
    }
}
