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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.ControllerServerMessages;

import org.json.JSONObject;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The mapping of the controller role onto the app's playback, with a fake group: spec {@code controller-server.md}, sections 3 to 5. */
public class ControllerLogicTest {

    /** A group of the hub with an app player behind it. */
    private static final class FakeGroup implements PlaybackBridge.Group {
        final String id;
        final List<String> log = new ArrayList<>();
        String name;
        boolean playing;
        long serial;
        int items = 5;
        long durationMs = 200_000L;
        long positionMs = 10_000L;
        int volume = 50;
        boolean muted;
        boolean volumeSupported = true;
        boolean muteSupported = true;
        boolean playModes = true;
        String repeat = "off";
        boolean shuffle;
        String title = "Song";
        boolean failing;

        FakeGroup(String id, String name, long serial) {
            this.id = id;
            this.name = name;
            this.serial = serial;
        }

        private void note(String what) {
            if (failing) {
                throw new IllegalStateException("the player failed");
            }
            log.add(what);
        }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public boolean isPlaying() { return playing; }
        @Override public long activitySerial() { return serial; }
        @Override public int itemCount() { return items; }
        @Override public long durationMs() { return durationMs; }
        @Override public long positionMs() { return positionMs; }
        @Override public int volume() { return volume; }
        @Override public boolean isMuted() { return muted; }
        @Override public boolean supportsVolume() { return volumeSupported; }
        @Override public boolean supportsMute() { return muteSupported; }
        @Override public boolean supportsPlayModes() { return playModes; }
        @Override public String repeat() { return repeat; }
        @Override public boolean isShuffle() { return shuffle; }
        @Override public String title() { return title; }
        @Override public String artist() { return "Artist"; }
        @Override public String album() { return "Album"; }
        @Override public String artworkUrl() { return null; }
        @Override public int year() { return 0; }
        @Override public int track() { return 0; }
        @Override public void play() { note("play"); playing = true; }
        @Override public void pause() { note("pause"); playing = false; }
        @Override public void stop() { note("stop"); playing = false; positionMs = 0; }
        @Override public void next() { note("next"); }
        @Override public void previous() { note("previous"); }
        @Override public void setVolume(int percent) { note("volume " + percent); volume = percent; }
        @Override public void setMuted(boolean m) { note("mute " + m); muted = m; }
        @Override public void setRepeat(String mode) { note("repeat " + mode); repeat = mode; }
        @Override public void setShuffle(boolean on) { note("shuffle " + on); shuffle = on; }
        @Override public void seekTo(long ms) { note("seek " + ms); positionMs = ms; }
    }

    private final List<PlaybackBridge.Group> groups = new ArrayList<>();
    private final ControllerLogic logic = new ControllerLogic(() -> new ArrayList<>(groups), "Phone", new SecureRandom());

    private static ControllerServerMessages.Command cmd(String command) throws Exception {
        return ControllerServerMessages.parseCommand(new JSONObject(ControllerMessages.commandPlain(command)).getJSONObject("payload"));
    }

    private static ControllerServerMessages.Command volume(int v) throws Exception {
        return ControllerServerMessages.parseCommand(new JSONObject(ControllerMessages.commandVolume(v)).getJSONObject("payload"));
    }

    private static ControllerServerMessages.Command mute(boolean m) throws Exception {
        return ControllerServerMessages.parseCommand(new JSONObject(ControllerMessages.commandMute(m)).getJSONObject("payload"));
    }

    private static ControllerServerMessages.Command seek(long ms) throws Exception {
        return ControllerServerMessages.parseCommand(new JSONObject(ControllerMessages.commandSeek(ms)).getJSONObject("payload"));
    }

    private static ControllerServerMessages.Command seekRelative(long ms) throws Exception {
        return ControllerServerMessages.parseCommand(new JSONObject(ControllerMessages.commandSeekRelative(ms)).getJSONObject("payload"));
    }

    // ------------------------------------------------------------------ the group a controller is on

    @Test
    public void withNoGroupThereIsNothingToControl() {
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        ControllerLogic.View view = logic.view(a, 1_000_000L);
        assertTrue(view.controller.supportedCommands().isEmpty());
        assertFalse(view.playing);
        assertEquals("Phone", view.groupName);
        assertEquals("the idle group has a protocol id of 43 characters", 43, view.groupId.length());
    }

    @Test
    public void theGroupThatPlaysIsChosenAndTheLastStartedWinsAmongSeveral() {
        FakeGroup quiet = new FakeGroup("a", "Kitchen", 3);
        FakeGroup first = new FakeGroup("b", "Living room", 5);
        first.playing = true;
        FakeGroup second = new FakeGroup("c", "Bedroom", 9);
        second.playing = true;
        groups.addAll(Arrays.asList(quiet, first, second));
        ControllerLogic.View view = logic.view(new ControllerLogic.Attachment(), 1L);
        assertEquals("Bedroom", view.groupName);
        assertTrue(view.playing);
    }

    @Test
    public void whenNothingPlaysTheGroupThatPlayedLastIsChosen() {
        groups.addAll(Arrays.asList(new FakeGroup("a", "Kitchen", 3), new FakeGroup("b", "Living room", 7)));
        assertEquals("Living room", logic.view(new ControllerLogic.Attachment(), 1L).groupName);
    }

    @Test
    public void aGroupIdIsStableForTheGroupAndDiffersBetweenGroups() {
        FakeGroup a = new FakeGroup("a", "Kitchen", 3);
        FakeGroup b = new FakeGroup("b", "Living room", 7);
        groups.addAll(Arrays.asList(a, b));
        String idA = logic.groupIdOf("a");
        assertEquals(idA, logic.groupIdOf("a"));
        assertNotEquals(idA, logic.groupIdOf("b"));
        assertEquals(43, idA.length());
        assertTrue(idA.matches("[A-Za-z0-9_-]{43}"));
    }

    // ------------------------------------------------------------------ what is offered

    @Test
    public void supportedCommandsFollowWhatTheGroupCan() {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        groups.add(g);
        ControllerLogic.View v = logic.view(new ControllerLogic.Attachment(), 1L);
        List<String> all = v.controller.supportedCommands();
        for (String c : Arrays.asList("play", "pause", "stop", "next", "previous", "volume", "mute", "repeat_off", "repeat_one",
                "repeat_all", "shuffle", "unshuffle", "seek", "seek_relative")) {
            assertTrue(c, all.contains(c));
        }
        assertFalse("one group: nothing to switch to", all.contains("switch"));
        assertEquals(Long.valueOf(200_000L), v.controller.seekMaxMs());

        g.items = 1;
        g.durationMs = 0;
        g.volumeSupported = false;
        g.muteSupported = false;
        g.playModes = false;
        List<String> few = logic.view(new ControllerLogic.Attachment(), 1L).controller.supportedCommands();
        assertEquals(Arrays.asList("play", "pause", "stop"), few);
        assertNull(logic.view(new ControllerLogic.Attachment(), 1L).controller.seekMaxMs());
    }

    @Test
    public void switchIsOfferedWhenThereIsMoreThanOneGroup() {
        groups.addAll(Arrays.asList(new FakeGroup("a", "Kitchen", 1), new FakeGroup("b", "Living room", 2)));
        assertTrue(logic.view(new ControllerLogic.Attachment(), 1L).controller.supportedCommands().contains("switch"));
    }

    // ------------------------------------------------------------------ the commands

    @Test
    public void theTransportCommandsReachThePlayer() throws Exception {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        for (String c : Arrays.asList("play", "pause", "next", "previous", "stop")) {
            assertEquals(c, ControllerLogic.Outcome.DONE, logic.handle(a, cmd(c)));
        }
        assertEquals(Arrays.asList("play", "pause", "next", "previous", "stop"), g.log);
    }

    @Test
    public void volumeMuteRepeatShuffleAndSeekCarryTheirParameter() throws Exception {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, volume(30)));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, mute(true)));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("repeat_one")));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("repeat_all")));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("repeat_off")));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("shuffle")));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("unshuffle")));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, seek(60_000L)));
        assertEquals(Arrays.asList("volume 30", "mute true", "repeat one", "repeat all", "repeat off", "shuffle true", "shuffle false",
                "seek 60000"), g.log);
    }

    @Test
    public void aSeekOutsideTheRangeIsIgnoredAndARelativeSeekIsClamped() throws Exception {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        g.positionMs = 10_000L;
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        assertEquals(ControllerLogic.Outcome.IGNORED, logic.handle(a, seek(200_001L)));
        assertTrue(g.log.isEmpty());
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, seekRelative(-60_000L)));
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, seekRelative(10_000_000L)));
        assertEquals(Arrays.asList("seek 0", "seek 200000"), g.log);
    }

    @Test
    public void aCommandThatIsNotOfferedIsIgnored() throws Exception {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        g.items = 1;
        g.volumeSupported = false;
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        assertEquals(ControllerLogic.Outcome.IGNORED, logic.handle(a, cmd("next")));
        assertEquals(ControllerLogic.Outcome.IGNORED, logic.handle(a, volume(10)));
        assertEquals(ControllerLogic.Outcome.IGNORED, logic.handle(a, cmd("switch")));
        assertTrue(g.log.isEmpty());
    }

    @Test
    public void withNoGroupACommandHasNoEffect() throws Exception {
        assertEquals(ControllerLogic.Outcome.IGNORED, logic.handle(new ControllerLogic.Attachment(), cmd("play")));
    }

    @Test
    public void aFailingPlayerChangesNothingAndIsReported() throws Exception {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        g.failing = true;
        groups.add(g);
        assertEquals(ControllerLogic.Outcome.FAILED, logic.handle(new ControllerLogic.Attachment(), cmd("play")));
    }

    // ------------------------------------------------------------------ switching

    @Test
    public void switchCyclesThePlayingGroupsFirstAndSticks() throws Exception {
        FakeGroup idle = new FakeGroup("a", "Kitchen", 2);
        FakeGroup p1 = new FakeGroup("b", "Living room", 5);
        p1.playing = true;
        FakeGroup p2 = new FakeGroup("c", "Bedroom", 9);
        p2.playing = true;
        groups.addAll(Arrays.asList(idle, p1, p2));
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        assertEquals("starts on the last started", "Bedroom", logic.view(a, 1L).groupName);
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("switch")));
        assertEquals("Living room", logic.view(a, 1L).groupName);
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("switch")));
        assertEquals("then the one that does not play", "Kitchen", logic.view(a, 1L).groupName);
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("switch")));
        assertEquals("and round again", "Bedroom", logic.view(a, 1L).groupName);
        p2.serial = 99; // a newer start does not pull a controller away from the group it switched to
        assertEquals("Bedroom", logic.view(a, 1L).groupName);
        assertEquals(ControllerLogic.Outcome.DONE, logic.handle(a, cmd("switch")));
        assertEquals("Living room", logic.view(a, 1L).groupName);
    }

    @Test
    public void aSwitchedGroupThatIsGoneReturnsToTheAutomaticChoice() throws Exception {
        FakeGroup a1 = new FakeGroup("a", "Kitchen", 2);
        FakeGroup b1 = new FakeGroup("b", "Living room", 5);
        b1.playing = true;
        groups.addAll(Arrays.asList(a1, b1));
        ControllerLogic.Attachment at = new ControllerLogic.Attachment();
        logic.handle(at, cmd("switch"));
        assertEquals("Kitchen", logic.view(at, 1L).groupName);
        groups.remove(a1);
        assertEquals("Living room", logic.view(at, 1L).groupName);
    }

    // ------------------------------------------------------------------ the state sent

    @Test
    public void metadataCarriesTheItemAndTheProgress() {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        g.playing = true;
        groups.add(g);
        ControllerLogic.View v = logic.view(new ControllerLogic.Attachment(), 7_000_000L);
        assertEquals(7_000_000L, v.metadataTimestampMicros);
        String message = ControllerServerMessages.serverState(v.controller, v.metadata);
        assertTrue(message.contains("\"title\":\"Song\""));
        assertTrue(message.contains("\"playback_speed\":1000"));
        g.playing = false;
        assertTrue(ControllerServerMessages.serverState(null, logic.view(new ControllerLogic.Attachment(), 8_000_000L).metadata).contains("\"playback_speed\":0"));
    }

    @Test
    public void aNewMetadataStateIsDueOnAChangeOrAJumpButNotForTheTimePassing() {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        g.playing = true;
        g.positionMs = 10_000L;
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        ControllerLogic.View first = logic.view(a, 1_000_000L);
        g.positionMs = 15_000L; // five seconds later at normal speed: as extrapolated
        ControllerLogic.View later = logic.view(a, 6_000_000L);
        assertFalse(later.metadataChangedSince(first));
        g.positionMs = 90_000L; // a seek
        assertTrue(logic.view(a, 7_000_000L).metadataChangedSince(later));
        g.title = "Next song";
        assertTrue(logic.view(a, 8_000_000L).metadataChangedSince(later));
        g.playing = false;
        assertTrue(logic.view(a, 9_000_000L).metadataChangedSince(later));
    }

    @Test
    public void controllerStateChangesAreDetected() {
        FakeGroup g = new FakeGroup("a", "Kitchen", 1);
        groups.add(g);
        ControllerLogic.Attachment a = new ControllerLogic.Attachment();
        ControllerLogic.View before = logic.view(a, 1L);
        assertFalse(logic.view(a, 2L).controllerChangedSince(before));
        g.volume = 20;
        assertTrue(logic.view(a, 3L).controllerChangedSince(before));
    }
}
