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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import de.yaacc.multiroom.control.SnapcastDisplayText;

/**
 * The source list of the app's own OpenHome renderer with the Snapcast groups in it (task 12.5), decided without Android. An
 * OpenHome controller sees the renderer's Product service: a list of sources and the index of the current one. Here the list is
 * {@code this device} (the one source of before, with the same names) followed by one source per known Snapcast group, and the
 * current one is what the output setting of the renderer says (Group 9, {@link SnapcastRendererOutput}): the list is only another
 * way to read and to write that one setting, never a second truth.
 *
 * <ul>
 *   <li><b>Order and names.</b> This device is index 0. The groups follow ordered by their cleaned name (ignoring case), then by
 *   id, so the same groups always give the same list. Names are display text from a server: the callers hand in cleaned text and it is cleaned and
 *   cut again here ({@link SnapcastDisplayText}); an empty one gets the label; equal names are told apart by a number
 *   ({@code Kitchen}, {@code Kitchen (2)}).</li>
 *   <li><b>Ids.</b> A group's receiver id ({@code snapcast:<server key>:<group id>}) holds the address of the server, so it is
 *   never in a text a controller sees. A source carries it only in the mapping of this class; the controller gets the name and
 *   an opaque {@code SystemName} token ({@link #tokenOf}, a hash that stays the same for the same group while the list changes).</li>
 *   <li><b>Limit.</b> At most {@value #MAX_GROUPS} groups. The group that is chosen is always in the list (it takes the place of
 *   the last one); a chosen group that is not known now (its server is gone or has not answered since the app started), and a group mode with no
 *   group chosen, is shown as one placeholder source that is the current one and can not be chosen: both settings always show
 *   the same state, also when it is not a good one.</li>
 *   <li><b>Type.</b> Every source is of the type {@code Playlist} (a controller can queue on all of them).</li>
 * </ul>
 * Pure Java. Immutable results.
 */
public final class SnapcastRendererSources {

    /** The most groups listed. */
    public static final int MAX_GROUPS = 16;
    /** The longest name of a source (the group's own name is at most 64 characters, the device adds a suffix). */
    public static final int MAX_NAME = 96;

    private static final String DEVICE_NAME = "Playlist";
    private static final String TYPE = "Playlist";
    private static final String TOKEN_PREFIX = "snapcast-";

    private SnapcastRendererSources() {
    }

    /** What a source is. */
    public enum Kind {
        /** This device: today's one source. */
        THIS_DEVICE,
        /** A known Snapcast group. */
        GROUP,
        /** The chosen group is not known now, or group mode has no group: the current source, but it can not be chosen. */
        UNAVAILABLE_GROUP
    }

    /** A known group: its receiver id and its name (display text). */
    public static final class GroupRef {
        private final String receiverId;
        private final String name;

        public GroupRef(String receiverId, String name) {
            this.receiverId = receiverId;
            this.name = name;
        }

        public String receiverId() {
            return receiverId;
        }

        public String name() {
            return name;
        }

        /** Never the id. */
        @Override
        public String toString() {
            return "GroupRef{}";
        }
    }

    /** The two texts of the app that the list needs. */
    public static final class Labels {
        private final String unnamedGroup;
        private final String unavailableGroup;

        public Labels(String unnamedGroup, String unavailableGroup) {
            this.unnamedGroup = unnamedGroup == null || unnamedGroup.isEmpty() ? "Snapcast" : unnamedGroup;
            this.unavailableGroup = unavailableGroup == null || unavailableGroup.isEmpty() ? "Snapcast" : unavailableGroup;
        }
    }

    /** What choosing a source means for the output setting. Immutable. */
    public static final class Target {
        private static final Target DEVICE = new Target(SnapcastRendererOutput.Mode.THIS_DEVICE, null);

        private final SnapcastRendererOutput.Mode mode;
        private final String receiverId;

        private Target(SnapcastRendererOutput.Mode mode, String receiverId) {
            this.mode = mode;
            this.receiverId = receiverId;
        }

        /** The output is this device. */
        public static Target deviceTarget() {
            return DEVICE;
        }

        /** The output is the group with this receiver id. */
        public static Target groupTarget(String receiverId) {
            return new Target(SnapcastRendererOutput.Mode.GROUP, receiverId);
        }

        public SnapcastRendererOutput.Mode mode() {
            return mode;
        }

        /** The group's receiver id for {@code GROUP}; null for this device. */
        public String receiverId() {
            return receiverId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Target && mode == ((Target) other).mode && Objects.equals(receiverId, ((Target) other).receiverId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, receiverId);
        }

        /** Never the id. */
        @Override
        public String toString() {
            return "Target{" + mode + "}";
        }
    }

    /** One source of the list. Immutable. */
    public static final class Entry {
        private final int index;
        private final Kind kind;
        private final String name;
        private final String systemName;
        private final String receiverId;

        private Entry(int index, Kind kind, String name, String systemName, String receiverId) {
            this.index = index;
            this.kind = kind;
            this.name = name;
            this.systemName = systemName;
            this.receiverId = receiverId;
        }

        /** The position in the list: the number a controller selects it with. */
        public int index() {
            return index;
        }

        public Kind kind() {
            return kind;
        }

        /** The name a controller shows (cleaned display text, unique in the list). */
        public String name() {
            return name;
        }

        /** Always {@code Playlist}. */
        public String type() {
            return TYPE;
        }

        /** {@code Playlist} for this device, else an opaque token that never holds an id or an address. */
        public String systemName() {
            return systemName;
        }

        /** The group's receiver id (the mapping to the setting); null for this device and for a placeholder with no group. */
        public String receiverId() {
            return receiverId;
        }

        /** False for the placeholder of a group that is not available. */
        public boolean selectable() {
            return kind != Kind.UNAVAILABLE_GROUP;
        }

        /** What the output becomes when this source is chosen; null when it can not be chosen. */
        public Target target() {
            switch (kind) {
                case THIS_DEVICE:
                    return Target.deviceTarget();
                case GROUP:
                    return Target.groupTarget(receiverId);
                case UNAVAILABLE_GROUP:
                default:
                    return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return index == that.index && kind == that.kind && name.equals(that.name) && systemName.equals(that.systemName)
                    && Objects.equals(receiverId, that.receiverId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, kind, name, systemName, receiverId);
        }

        /** Never the id or the name. */
        @Override
        public String toString() {
            return "Entry{" + index + ", " + kind + "}";
        }
    }

    /** The list a controller is told and the index of the current source. Immutable; equal when it would tell the same. */
    public static final class Snapshot {
        private final List<Entry> entries;
        private final int currentIndex;

        private Snapshot(List<Entry> entries, int currentIndex) {
            this.entries = Collections.unmodifiableList(entries);
            this.currentIndex = currentIndex;
        }

        public List<Entry> entries() {
            return entries;
        }

        public int currentIndex() {
            return currentIndex;
        }

        public Entry current() {
            return entries.get(currentIndex);
        }

        /** @return the source at this position, null when there is none */
        public Entry byIndex(long index) {
            return index < 0 || index >= entries.size() ? null : entries.get((int) index);
        }

        /** @return the source with exactly this name, null when there is none */
        public Entry byName(String name) {
            if (name == null) {
                return null;
            }
            for (Entry entry : entries) {
                if (entry.name.equals(name)) {
                    return entry;
                }
            }
            return null;
        }

        /** @return the source with exactly this system name, null when there is none */
        public Entry bySystemName(String systemName) {
            if (systemName == null) {
                return null;
            }
            for (Entry entry : entries) {
                if (entry.systemName.equals(systemName)) {
                    return entry;
                }
            }
            return null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Snapshot && currentIndex == ((Snapshot) other).currentIndex
                    && entries.equals(((Snapshot) other).entries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entries, currentIndex);
        }

        @Override
        public String toString() {
            return "Snapshot{" + entries.size() + " sources, current " + currentIndex + "}";
        }
    }

    /**
     * The list for the setting and the groups known now.
     *
     * @param setting the renderer's output setting; null counts as this device
     * @param groups  the known groups; null for none. Entries with an id that is not canonical, or that repeat one, are left out
     * @param labels  the texts of the app; null only when no group is listed and none is chosen
     */
    public static Snapshot build(SnapcastRendererOutput.Setting setting, List<GroupRef> groups, Labels labels) {
        Labels texts = labels == null ? new Labels(null, null) : labels;
        boolean groupMode = setting != null && setting.mode() == SnapcastRendererOutput.Mode.GROUP;
        String chosen = groupMode ? setting.receiverId() : null;

        List<Candidate> sorted = candidates(groups, texts);
        int chosenAt = -1;
        for (int i = 0; i < sorted.size() && chosen != null; i++) {
            if (sorted.get(i).receiverId.equals(chosen)) {
                chosenAt = i;
            }
        }
        boolean placeholder = groupMode && chosenAt < 0;
        int room = MAX_GROUPS - (placeholder ? 1 : 0);
        List<Candidate> listed = new ArrayList<>(sorted.subList(0, Math.min(room, sorted.size())));
        if (chosenAt >= room) {
            listed.set(listed.size() - 1, sorted.get(chosenAt)); // the chosen group is always listed
        }

        List<String> bases = new ArrayList<>();
        bases.add(DEVICE_NAME);
        for (Candidate candidate : listed) {
            bases.add(candidate.name);
        }
        if (placeholder) {
            bases.add(texts.unavailableGroup);
        }
        List<String> names = uniqueNames(bases);

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(0, Kind.THIS_DEVICE, names.get(0), DEVICE_NAME, null));
        int current = 0;
        for (int i = 0; i < listed.size(); i++) {
            Candidate candidate = listed.get(i);
            entries.add(new Entry(i + 1, Kind.GROUP, names.get(i + 1), tokenOf(candidate.receiverId), candidate.receiverId));
            if (candidate.receiverId.equals(chosen)) {
                current = i + 1;
            }
        }
        if (placeholder) {
            int index = entries.size();
            entries.add(new Entry(index, Kind.UNAVAILABLE_GROUP, names.get(index), tokenOf(chosen == null ? "" : chosen), chosen));
            current = index;
        }
        return new Snapshot(entries, current);
    }

    /**
     * The token a controller can use as the system name of a group: {@code snapcast-} and 16 hexadecimal characters of a hash of
     * the receiver id. It is the same for the same group whatever else is in the list, and tells nothing about the server.
     */
    public static String tokenOf(String receiverId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(receiverId).getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(TOKEN_PREFIX);
            for (int i = 0; i < 8; i++) {
                text.append(Character.forDigit((digest[i] >> 4) & 0xf, 16)).append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static final class Candidate {
        final String receiverId;
        final String name;

        Candidate(String receiverId, String name) {
            this.receiverId = receiverId;
            this.name = name;
        }
    }

    /** The valid, distinct groups with cleaned names, in the order of the list. */
    private static List<Candidate> candidates(List<GroupRef> groups, Labels texts) {
        List<Candidate> result = new ArrayList<>();
        if (groups == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (GroupRef group : groups) {
            if (group == null || group.receiverId == null || !SnapcastRendererOutput.isCanonicalGroupId(group.receiverId)
                    || !seen.add(group.receiverId)) {
                continue;
            }
            String name = group.name == null ? "" : SnapcastDisplayText.of(group.name, MAX_NAME).trim();
            result.add(new Candidate(group.receiverId, name.isEmpty() ? texts.unnamedGroup : name));
        }
        Collections.sort(result, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                int byName = a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
                if (byName == 0) {
                    byName = a.name.compareTo(b.name);
                }
                return byName != 0 ? byName : a.receiverId.compareTo(b.receiverId);
            }
        });
        return result;
    }

    /** The second "Kitchen" becomes "Kitchen (2)"; a number that another name uses is skipped. */
    private static List<String> uniqueNames(List<String> names) {
        Set<String> taken = new HashSet<>(names);
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>(names.size());
        for (String name : names) {
            String unique = name;
            if (!used.add(unique)) {
                int number = 2;
                unique = name + " (" + number + ")";
                while (taken.contains(unique) || used.contains(unique)) {
                    number++;
                    unique = name + " (" + number + ")";
                }
                used.add(unique);
            }
            result.add(unique);
        }
        return result;
    }
}
