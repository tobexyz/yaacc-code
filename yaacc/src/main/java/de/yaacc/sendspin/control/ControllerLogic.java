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
package de.yaacc.sendspin.control;

import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.ControllerServerMessages;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The controller role of the server, apart from the wire (issue #325, K15, spec {@code controller-server.md}): which group a
 * controller is on, what it may do, what its commands do to the app's playback and what state it is told. Pure: the app is behind a
 * {@link PlaybackBridge}. Each controller connection has an {@link Attachment}; the logic itself is shared and thread safe as far as
 * its bridge is.
 */
public final class ControllerLogic {

    /** What a command came to. */
    public enum Outcome {
        DONE,
        /** Not offered now, out of range, or no group: nothing was done (the protocol says the server ignores such a command). */
        IGNORED,
        /** The player failed: nothing was changed as far as the logic knows. */
        FAILED
    }

    /** The group a controller is on. It follows the automatic choice until the controller switches; then it stays until that group is gone. */
    public static final class Attachment {
        private String groupId;
        private boolean manual;
    }

    /** A moment of what a controller is told. */
    public static final class View {
        public final boolean hasGroup;
        public final String groupId;
        public final String groupName;
        public final boolean playing;
        public final ControllerServerMessages.ControllerView controller;
        public final ControllerServerMessages.MetadataView metadata;
        public final long metadataTimestampMicros;
        private final long positionMs;
        private final int speed;
        private final long atMicros;
        private final long durationMs;

        View(boolean hasGroup, String groupId, String groupName, boolean playing, ControllerServerMessages.ControllerView controller,
             ControllerServerMessages.MetadataView metadata, long atMicros, long positionMs, int speed, long durationMs) {
            this.hasGroup = hasGroup;
            this.groupId = groupId;
            this.groupName = groupName;
            this.playing = playing;
            this.controller = controller;
            this.metadata = metadata;
            this.metadataTimestampMicros = atMicros;
            this.atMicros = atMicros;
            this.positionMs = positionMs;
            this.speed = speed;
            this.durationMs = durationMs;
        }

        /** The group, its name or whether it plays changed (a new {@code group/update}). */
        public boolean groupChangedSince(View before) {
            return before == null || !groupId.equals(before.groupId) || !Objects.equals(groupName, before.groupName) || playing != before.playing;
        }

        /** The commands offered, the volume, the mute, the repeat or the shuffle changed (a new controller state). */
        public boolean controllerChangedSince(View before) {
            if (before == null) {
                return true;
            }
            ControllerServerMessages.ControllerView a = controller;
            ControllerServerMessages.ControllerView b = before.controller;
            return !a.supportedCommands().equals(b.supportedCommands()) || a.volume() != b.volume() || a.muted() != b.muted()
                    || !a.repeat().equals(b.repeat()) || a.shuffle() != b.shuffle() || !Objects.equals(a.seekMaxMs(), b.seekMaxMs());
        }

        /**
         * The item, its length or the play state changed, or the position jumped (a seek): the progress of the last state, carried
         * forward at its speed, is more than a second and a half away. The time passing alone is no change: the client extrapolates.
         */
        public boolean metadataChangedSince(View before) {
            if (before == null) {
                return true;
            }
            ControllerServerMessages.MetadataView a = metadata;
            ControllerServerMessages.MetadataView b = before.metadata;
            if (!Objects.equals(a.title(), b.title()) || !Objects.equals(a.artist(), b.artist()) || !Objects.equals(a.album(), b.album())
                    || !Objects.equals(a.artworkUrl(), b.artworkUrl()) || durationMs != before.durationMs || speed != before.speed
                    || !groupId.equals(before.groupId)) {
                return true;
            }
            long expected = before.positionMs + (atMicros - before.atMicros) / 1000L * before.speed / 1000L;
            return Math.abs(positionMs - expected) > JUMP_TOLERANCE_MS;
        }
    }

    private static final String IDLE = "\u0000idle";
    private static final int JUMP_TOLERANCE_MS = 1500;

    private final PlaybackBridge bridge;
    private final String serverName;
    private final SecureRandom random;
    private final Map<String, String> ids = new HashMap<>();

    public ControllerLogic(PlaybackBridge bridge, String serverName, SecureRandom random) {
        this.bridge = bridge;
        this.serverName = serverName;
        this.random = random;
    }

    /** The protocol id of a group: 32 random bytes, base64url without padding (43 characters), kept while the app runs. */
    public synchronized String groupIdOf(String key) {
        String id = ids.get(key);
        if (id == null) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            ids.put(key, id);
        }
        return id;
    }

    // ------------------------------------------------------------------ which group

    /** The groups in the order of the switch cycle: the ones that play first, the last started first; then the others the same way. */
    private static List<PlaybackBridge.Group> cycle(List<PlaybackBridge.Group> groups) {
        List<PlaybackBridge.Group> ordered = new ArrayList<>(groups);
        Collections.sort(ordered, new Comparator<PlaybackBridge.Group>() {
            @Override
            public int compare(PlaybackBridge.Group a, PlaybackBridge.Group b) {
                if (a.isPlaying() != b.isPlaying()) {
                    return a.isPlaying() ? -1 : 1;
                }
                return Long.compare(b.activitySerial(), a.activitySerial());
            }
        });
        return ordered;
    }

    private PlaybackBridge.Group resolve(Attachment attachment, List<PlaybackBridge.Group> groups) {
        if (attachment.manual && attachment.groupId != null) {
            for (PlaybackBridge.Group g : groups) {
                if (g.id().equals(attachment.groupId)) {
                    return g;
                }
            }
            attachment.manual = false; // the group it switched to is gone
        }
        if (groups.isEmpty()) {
            attachment.groupId = null;
            return null;
        }
        PlaybackBridge.Group chosen = cycle(groups).get(0);
        attachment.groupId = chosen.id();
        return chosen;
    }

    // ------------------------------------------------------------------ what is offered and told

    private static List<String> supportedCommands(PlaybackBridge.Group g, int groupCount) {
        List<String> offered = new ArrayList<>();
        if (g == null) {
            return offered;
        }
        for (String command : ControllerMessages.COMMANDS) {
            boolean ok;
            switch (command) {
                case "play": case "pause": case "stop":
                    ok = true;
                    break;
                case "next": case "previous":
                    ok = g.itemCount() > 1;
                    break;
                case "volume":
                    ok = g.supportsVolume();
                    break;
                case "mute":
                    ok = g.supportsMute();
                    break;
                case "repeat_off": case "repeat_one": case "repeat_all": case "shuffle": case "unshuffle":
                    ok = g.supportsPlayModes();
                    break;
                case "seek": case "seek_relative":
                    ok = g.durationMs() > 0;
                    break;
                case "switch":
                    ok = groupCount > 1;
                    break;
                default:
                    ok = false;
            }
            if (ok) {
                offered.add(command);
            }
        }
        return offered;
    }

    /** What the controller on this attachment is told now. {@code nowMicros} is the server clock. */
    public View view(Attachment attachment, long nowMicros) {
        List<PlaybackBridge.Group> groups = bridge.groups();
        PlaybackBridge.Group g = resolve(attachment, groups);
        if (g == null) {
            return new View(false, groupIdOf(IDLE), serverName, false,
                    new ControllerServerMessages.ControllerView(Collections.<String>emptyList(), 100, false, "off", false, null),
                    new ControllerServerMessages.MetadataView(nowMicros, null, null, null, null, null, 0, 0, null), nowMicros, 0L, 0, 0L);
        }
        long duration = Math.max(0L, g.durationMs());
        long position = Math.max(0L, g.positionMs());
        int speed = g.isPlaying() ? 1000 : 0;
        String repeat = g.supportsPlayModes() ? g.repeat() : "off";
        ControllerServerMessages.ControllerView controller = new ControllerServerMessages.ControllerView(supportedCommands(g, groups.size()),
                g.supportsVolume() ? g.volume() : 100, g.supportsMute() && g.isMuted(), repeat, g.supportsPlayModes() && g.isShuffle(),
                duration > 0 ? Long.valueOf(duration) : null);
        ControllerServerMessages.MetadataView metadata = new ControllerServerMessages.MetadataView(nowMicros, g.title(), g.artist(), null,
                g.album(), g.artworkUrl(), g.year(), g.track(), new ControllerServerMessages.Progress(position, duration, speed));
        return new View(true, groupIdOf(g.id()), g.name(), g.isPlaying(), controller, metadata, nowMicros, position, speed, duration);
    }

    // ------------------------------------------------------------------ commands

    /** Does what a controller's command asks, on the group it is on; the caller runs this off the connection's thread. */
    public Outcome handle(Attachment attachment, ControllerServerMessages.Command command) {
        List<PlaybackBridge.Group> groups = bridge.groups();
        PlaybackBridge.Group g = resolve(attachment, groups);
        if (g == null || !supportedCommands(g, groups.size()).contains(command.command)) {
            return Outcome.IGNORED;
        }
        try {
            switch (command.command) {
                case "play":
                    g.play();
                    break;
                case "pause":
                    g.pause();
                    break;
                case "stop":
                    g.stop();
                    break;
                case "next":
                    g.next();
                    break;
                case "previous":
                    g.previous();
                    break;
                case "volume":
                    g.setVolume(command.volume);
                    break;
                case "mute":
                    g.setMuted(command.mute);
                    break;
                case "repeat_off":
                    g.setRepeat("off");
                    break;
                case "repeat_one":
                    g.setRepeat("one");
                    break;
                case "repeat_all":
                    g.setRepeat("all");
                    break;
                case "shuffle":
                    g.setShuffle(true);
                    break;
                case "unshuffle":
                    g.setShuffle(false);
                    break;
                case "seek":
                    if (command.positionMs < 0 || command.positionMs > g.durationMs()) {
                        return Outcome.IGNORED;
                    }
                    g.seekTo(command.positionMs);
                    break;
                case "seek_relative":
                    g.seekTo(Math.max(0L, Math.min(g.durationMs(), g.positionMs() + command.offsetMs)));
                    break;
                case "switch": {
                    List<PlaybackBridge.Group> order = cycle(groups);
                    int at = 0;
                    for (int i = 0; i < order.size(); i++) {
                        if (order.get(i).id().equals(g.id())) {
                            at = i;
                        }
                    }
                    attachment.groupId = order.get((at + 1) % order.size()).id();
                    attachment.manual = true;
                    break;
                }
                default:
                    return Outcome.IGNORED;
            }
            return Outcome.DONE;
        } catch (RuntimeException e) {
            return Outcome.FAILED;
        }
    }
}
