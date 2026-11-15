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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.multiroom.SnapcastRendererOutput.Decision;
import de.yaacc.multiroom.SnapcastRendererOutput.GroupFacts;
import de.yaacc.multiroom.SnapcastRendererOutput.Mode;
import de.yaacc.multiroom.SnapcastRendererOutput.Output;
import de.yaacc.multiroom.SnapcastRendererOutput.Problem;
import de.yaacc.multiroom.SnapcastRendererOutput.Setting;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The output of the YAACC renderer (task 9.1): what the setting says, what a new play does with it, and which error it
 * reports. Plain JVM, no waiting: the clock and the sleep are fakes.
 */
public class SnapcastRendererOutputTest {

    private static final String ID = SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), "g-1");
    private static final String OTHER = SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), "g-2");

    private static SnapcastRendererOutput.StringSource prefs(String output, String group) {
        Map<String, String> stored = new HashMap<>();
        if (output != null) {
            stored.put(SnapcastRendererOutput.KEY_OUTPUT, output);
        }
        if (group != null) {
            stored.put(SnapcastRendererOutput.KEY_GROUP, group);
        }
        return stored::get;
    }

    /** Facts that change with the polls; counts wakes and sleeps. */
    private static final class FakeWorld implements SnapcastRendererOutput.Probe, SnapcastRendererOutput.Waiter {
        final List<GroupFacts> answers = new ArrayList<>();
        int asked;
        int wakes;
        long now;
        long slept;

        FakeWorld(GroupFacts... answers) {
            for (GroupFacts answer : answers) {
                this.answers.add(answer);
            }
        }

        @Override
        public GroupFacts facts(String receiverId) {
            GroupFacts answer = answers.get(Math.min(asked, answers.size() - 1));
            asked++;
            return answer;
        }

        @Override
        public void wake(String receiverId) {
            wakes++;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            now += millis;
            slept += millis;
        }
    }

    // ---- the setting

    @Test
    public void theKeysAreNeutralAndTheDefaultIsThisDevice() {
        assertEquals("settings_renderer_output", SnapcastRendererOutput.KEY_OUTPUT);
        assertEquals("settings_renderer_output_group", SnapcastRendererOutput.KEY_GROUP);
        assertFalse(SnapcastRendererOutput.KEY_OUTPUT.toLowerCase().contains("snapcast"));
        assertFalse(SnapcastRendererOutput.KEY_GROUP.toLowerCase().contains("snapcast"));
        assertEquals("device", SnapcastRendererOutput.VALUE_DEVICE);
        assertEquals("group", SnapcastRendererOutput.VALUE_GROUP);

        Setting setting = SnapcastRendererOutput.read(prefs(null, null));

        assertEquals(Mode.THIS_DEVICE, setting.mode());
        assertFalse(setting.isDamaged());
        assertNull(setting.receiverId());
    }

    @Test
    public void thisDeviceStaysThisDeviceWhateverTheGroupPreferenceHolds() {
        for (String group : new String[]{null, "", ID, "garbage"}) {
            Setting setting = SnapcastRendererOutput.read(prefs("device", group));

            assertEquals(Mode.THIS_DEVICE, setting.mode());
            assertFalse("a group that is not used is not a damage: " + group, setting.isDamaged());
        }
    }

    @Test
    public void aChosenGroupIsReadFromItsReceiverId() {
        Setting setting = SnapcastRendererOutput.read(prefs("group", ID));

        assertEquals(Mode.GROUP, setting.mode());
        assertEquals(ID, setting.receiverId());
        assertFalse(setting.isDamaged());
    }

    @Test
    public void groupModeWithoutAGroupIsNotDamagedButHasNoGroup() {
        for (String group : new String[]{null, ""}) {
            Setting setting = SnapcastRendererOutput.read(prefs("group", group));

            assertEquals(Mode.GROUP, setting.mode());
            assertNull(setting.receiverId());
            assertFalse(setting.isDamaged());
        }
    }

    @Test
    public void aCorruptValueFallsBackToThisDeviceAndIsMarkedDamaged() {
        String[] corruptGroups = {"garbage", "snapcast:", "snapcast:10.0.0.1:1705", "snapcast:10.0.0.1:1705:", "snapcast:::g",
                "snapcast:10.0.0.1:1705:g 1", "snapcast:10.0.0.1:1705:g\n1", "snapcast: 10.0.0.1:1705:g1",
                "SNAPCAST:10.0.0.1:1705:g1", ID + "x:y", "snapcast:10.0.0.1:99999:g1", "snapcast:10.0.0.1:0:g1"};
        for (String group : corruptGroups) {
            Setting setting = SnapcastRendererOutput.read(prefs("group", group));

            assertEquals(group, Mode.THIS_DEVICE, setting.mode());
            assertTrue(group, setting.isDamaged());
            assertNull(group, setting.receiverId());
        }
        StringBuilder tooLong = new StringBuilder(ID);
        for (int i = 0; i < 400; i++) {
            tooLong.append('a');
        }
        assertTrue(SnapcastRendererOutput.read(prefs("group", tooLong.toString())).isDamaged());
    }

    @Test
    public void aNonCanonicalIdIsDamagedEvenWhenItParses() {
        // the server key must be in the canonical form the directory uses (host and port as it prints them)
        Setting setting = SnapcastRendererOutput.read(prefs("group", "snapcast:192.168.001.005:1705:g-1"));

        assertTrue(setting.isDamaged());
        assertEquals(Mode.THIS_DEVICE, setting.mode());
    }

    @Test
    public void anUnknownOutputValueIsDamagedAndPlaysOnThisDevice() {
        for (String output : new String[]{"snapcast", "GROUP", "x", " group", "device "}) {
            Setting setting = SnapcastRendererOutput.read(prefs(output, ID));

            assertEquals(output, Mode.THIS_DEVICE, setting.mode());
            assertTrue(output, setting.isDamaged());
        }
        assertFalse("an empty value is as good as none", SnapcastRendererOutput.read(prefs("", null)).isDamaged());
    }

    @Test
    public void aValueThatCanNotBeReadIsDamagedNotACrash() {
        Setting setting = SnapcastRendererOutput.read(key -> {
            throw new ClassCastException("not a string");
        });

        assertEquals(Mode.THIS_DEVICE, setting.mode());
        assertTrue(setting.isDamaged());
        Setting noSource = SnapcastRendererOutput.read(null);
        assertEquals(Mode.THIS_DEVICE, noSource.mode());
        assertTrue(noSource.isDamaged());
    }

    // ---- which output a play uses

    @Test
    public void thisDeviceIsLocalAndNeedsNoFacts() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs(null, null)), false, null);

        assertEquals(Output.LOCAL, decision.output());
        assertEquals(Problem.NONE, decision.problem());
        assertNull(decision.receiverId());
    }

    @Test
    public void aDamagedSettingPlaysLocallyWithANote() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs("group", "garbage")), false, null);

        assertEquals(Output.LOCAL, decision.output());
        assertEquals(Problem.DAMAGED_SETTING, decision.problem());
    }

    @Test
    public void aGroupThatIsReadyIsTheOutput() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs("group", ID)), false, GroupFacts.READY);

        assertEquals(Output.GROUP, decision.output());
        assertEquals(Problem.NONE, decision.problem());
        assertEquals(ID, decision.receiverId());
    }

    @Test
    public void everyOtherFactIsAnErrorAndNeverTheLocalOutput() {
        Setting setting = SnapcastRendererOutput.read(prefs("group", ID));
        Map<GroupFacts, Problem> expected = new HashMap<>();
        expected.put(GroupFacts.NOT_LISTED, Problem.SERVER_NOT_LISTED);
        expected.put(GroupFacts.PENDING, Problem.SERVER_UNREACHABLE);
        expected.put(GroupFacts.OFFLINE, Problem.SERVER_UNREACHABLE);
        expected.put(GroupFacts.GONE, Problem.GROUP_GONE);

        for (Map.Entry<GroupFacts, Problem> entry : expected.entrySet()) {
            Decision decision = SnapcastRendererOutput.decide(setting, false, entry.getKey());

            assertEquals(entry.getKey().name(), Output.NONE, decision.output());
            assertEquals(entry.getKey().name(), entry.getValue(), decision.problem());
        }
    }

    @Test
    public void noGroupChosenIsAnErrorNotALocalPlay() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs("group", null)), false, GroupFacts.READY);

        assertEquals(Output.NONE, decision.output());
        assertEquals(Problem.NO_GROUP_CHOSEN, decision.problem());
    }

    @Test
    public void videoAndImagesAreRefusedInGroupModeAndNotShownLocally() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs("group", ID)), true, GroupFacts.READY);

        assertEquals(Output.NONE, decision.output());
        assertEquals(Problem.NOT_MUSIC, decision.problem());
    }

    @Test
    public void videoStillPlaysLocallyWhenTheOutputIsThisDevice() {
        Decision decision = SnapcastRendererOutput.decide(SnapcastRendererOutput.read(prefs("device", null)), true, null);

        assertEquals(Output.LOCAL, decision.output());
    }

    @Test
    public void factsAreBuiltFromWhatTheModelKnows() {
        assertEquals(GroupFacts.NOT_LISTED, GroupFacts.of(false, true, true, true));
        assertEquals(GroupFacts.PENDING, GroupFacts.of(true, false, false, false));
        assertEquals(GroupFacts.PENDING, GroupFacts.of(true, false, true, true));
        assertEquals(GroupFacts.GONE, GroupFacts.of(true, true, false, true));
        assertEquals(GroupFacts.GONE, GroupFacts.of(true, true, false, false));
        assertEquals(GroupFacts.OFFLINE, GroupFacts.of(true, true, true, false));
        assertEquals(GroupFacts.READY, GroupFacts.of(true, true, true, true));
    }

    // ---- waiting for a connection that is coming up

    @Test
    public void aReadyGroupIsNotWokenAndNotWaitedFor() {
        FakeWorld world = new FakeWorld(GroupFacts.READY);

        Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), false, world, world, 4_000);

        assertEquals(Output.GROUP, decision.output());
        assertEquals(0, world.wakes);
        assertEquals(0, world.slept);
    }

    @Test
    public void aServerThatIsPendingGetsOneWakeAndAShortWaitThenPlays() {
        FakeWorld world = new FakeWorld(GroupFacts.PENDING, GroupFacts.PENDING, GroupFacts.OFFLINE, GroupFacts.READY);

        Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), false, world, world, 4_000);

        assertEquals(Output.GROUP, decision.output());
        assertEquals("woken once", 1, world.wakes);
        assertEquals("three steps of 200 ms", 600, world.slept);
    }

    @Test
    public void aServerThatNeverAnswersEndsInTheUnreachableErrorAfterTheLimit() {
        FakeWorld world = new FakeWorld(GroupFacts.PENDING);

        Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), false, world, world, 4_000);

        assertEquals(Output.NONE, decision.output());
        assertEquals(Problem.SERVER_UNREACHABLE, decision.problem());
        assertEquals(1, world.wakes);
        assertTrue("never waits more than the limit: " + world.slept, world.slept >= 4_000 && world.slept <= 4_200);
    }

    @Test
    public void aGroupThatIsGoneOrAServerThatIsNotListedIsNotWaitedFor() {
        for (GroupFacts facts : new GroupFacts[]{GroupFacts.GONE, GroupFacts.NOT_LISTED}) {
            FakeWorld world = new FakeWorld(facts);

            Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), false, world, world, 4_000);

            assertEquals(Output.NONE, decision.output());
            assertEquals(0, world.slept);
            assertEquals(0, world.wakes);
        }
    }

    @Test
    public void thisDeviceNeverAsksTheServerAtAll() {
        FakeWorld world = new FakeWorld(GroupFacts.READY);

        Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("device", ID)), false, world, world, 4_000);

        assertEquals(Output.LOCAL, decision.output());
        assertEquals(0, world.asked);
        assertEquals(0, world.wakes);
    }

    @Test
    public void refusedContentOrNoGroupDoesNotWakeTheServerEither() {
        FakeWorld world = new FakeWorld(GroupFacts.PENDING);

        assertEquals(Problem.NOT_MUSIC,
                SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), true, world, world, 4_000).problem());
        assertEquals(Problem.NO_GROUP_CHOSEN,
                SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", null)), false, world, world, 4_000).problem());
        assertEquals(0, world.asked);
        assertEquals(0, world.wakes);
    }

    @Test
    public void anInterruptedWaitEndsWithTheLastFactAndKeepsTheInterrupt() {
        final boolean[] interrupted = {false};
        SnapcastRendererOutput.Probe probe = new SnapcastRendererOutput.Probe() {
            @Override
            public GroupFacts facts(String receiverId) {
                return GroupFacts.PENDING;
            }

            @Override
            public void wake(String receiverId) {
            }
        };
        SnapcastRendererOutput.Waiter waiter = new SnapcastRendererOutput.Waiter() {
            @Override
            public long nowMillis() {
                return 0;
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                interrupted[0] = true;
                throw new InterruptedException();
            }
        };

        Decision decision = SnapcastRendererOutput.resolve(SnapcastRendererOutput.read(prefs("group", ID)), false, probe, waiter, 4_000);

        assertEquals(Problem.SERVER_UNREACHABLE, decision.problem());
        assertTrue(interrupted[0]);
        assertTrue("the interrupt flag is set again", Thread.interrupted());
    }

    // ---- switching while playing

    @Test
    public void theOutputKeyTellsWhichOutputAPlayerWasMadeFor() {
        String device = SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs(null, null)));
        String group = SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs("group", ID)));
        String otherGroup = SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs("group", OTHER)));
        String noGroup = SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs("group", null)));
        String damaged = SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs("group", "garbage")));

        assertEquals("device", device);
        assertNotEquals(device, group);
        assertNotEquals("a group of the same server is another output", group, otherGroup);
        assertNotEquals(device, noGroup);
        assertEquals("a damaged setting plays locally, so it is the local output", device, damaged);
        assertEquals(group, SnapcastRendererOutput.outputKey(SnapcastRendererOutput.read(prefs("group", ID))));
        assertEquals("device", SnapcastRendererOutput.outputKey(null));
    }

    // ---- the server of the chosen group is remembered like the server of a selected group

    @Test
    public void theChosenGroupIsAddedToTheSelectionOnlyInGroupMode() {
        java.util.Set<String> selection = new java.util.HashSet<>(java.util.Arrays.asList(OTHER, "uuid:abc"));

        java.util.Set<String> inGroupMode = SnapcastRendererOutput.withRendererGroup(selection, SnapcastRendererOutput.read(prefs("group", ID)));
        java.util.Set<String> onDevice = SnapcastRendererOutput.withRendererGroup(selection, SnapcastRendererOutput.read(prefs("device", ID)));

        assertTrue(inGroupMode.contains(ID));
        assertTrue(inGroupMode.containsAll(selection));
        assertEquals("this device: the server is forgotten again", selection, onDevice);
        assertEquals("the selection itself is never changed", 2, selection.size());
    }

    @Test
    public void noGroupChosenOrADamagedSettingAddsNothingAndANullSelectionIsFine() {
        assertEquals(java.util.Collections.emptySet(),
                SnapcastRendererOutput.withRendererGroup(null, SnapcastRendererOutput.read(prefs("group", null))));
        assertEquals(java.util.Collections.emptySet(),
                SnapcastRendererOutput.withRendererGroup(null, SnapcastRendererOutput.read(prefs("group", "garbage"))));
        assertEquals(java.util.Collections.singleton(ID),
                SnapcastRendererOutput.withRendererGroup(null, SnapcastRendererOutput.read(prefs("group", ID))));
        assertEquals(java.util.Collections.singleton(OTHER),
                SnapcastRendererOutput.withRendererGroup(java.util.Collections.singleton(OTHER), null));
    }
}
