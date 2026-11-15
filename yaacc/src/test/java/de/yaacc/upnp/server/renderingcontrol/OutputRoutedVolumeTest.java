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
package de.yaacc.upnp.server.renderingcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastRendererOutput;
import de.yaacc.multiroom.SnapcastRendererOutput.GroupFacts;
import de.yaacc.multiroom.SnapcastRendererOutput.Problem;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The volume and mute of the renderer follow the output (task 9.1): this device = the system volume as before; Snapcast
 * group = the volume and mute of the group, read from the model and set through the controls. Plain JVM; the sender
 * thread, the clock and the group are fakes.
 */
public class OutputRoutedVolumeTest {

    private static final String ID = SnapcastGroupDevice.idOf(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), "g-1");

    private final Map<String, String> prefs = new HashMap<>();
    private final FakeLocal local = new FakeLocal();
    private final FakeGroup group = new FakeGroup();
    private final List<Runnable> queue = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();
    private long now;
    private final OutputRoutedVolume volume = new OutputRoutedVolume(
            () -> SnapcastRendererOutput.read(prefs::get), local, group, queue::add, new SnapcastRendererOutput.Waiter() {
        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            now += millis;
        }
    }, () -> now, problems::add);

    private static final class FakeLocal implements RendererVolume {
        int percent = 42;
        boolean muted;
        int calls;

        @Override
        public int getPercent() {
            calls++;
            return percent;
        }

        @Override
        public boolean isMuted() {
            calls++;
            return muted;
        }

        @Override
        public void setPercent(int value) {
            calls++;
            percent = value;
        }

        @Override
        public void setMuted(boolean value) {
            calls++;
            muted = value;
        }
    }

    private static final class FakeGroup implements OutputRoutedVolume.GroupSide {
        GroupFacts facts = GroupFacts.READY;
        OutputRoutedVolume.Level level = new OutputRoutedVolume.Level(30, false);
        int wakes;
        int reads;
        final List<String> sent = new ArrayList<>();

        @Override
        public GroupFacts facts(String receiverId) {
            return facts;
        }

        @Override
        public void wake(String receiverId) {
            wakes++;
        }

        @Override
        public OutputRoutedVolume.Level level(String receiverId) {
            reads++;
            return level;
        }

        @Override
        public void setVolume(String receiverId, int percent) {
            sent.add("volume " + percent);
        }

        @Override
        public void setMuted(String receiverId, boolean muted) {
            sent.add("mute " + muted);
        }
    }

    private void groupMode() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        prefs.put(SnapcastRendererOutput.KEY_GROUP, ID);
    }

    private void runQueue() {
        List<Runnable> tasks = new ArrayList<>(queue);
        queue.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }

    // ---- this device

    @Test
    public void thisDeviceIsTheSystemVolumeAsBefore() {
        assertEquals(42, volume.getPercent());
        assertFalse(volume.isMuted());
        volume.setPercent(55);
        volume.setMuted(true);

        assertEquals(55, local.percent);
        assertTrue(local.muted);
        assertEquals("nothing was queued for a group", 0, queue.size());
        assertEquals(0, group.reads);
        assertEquals(0, group.wakes);
        assertTrue(group.sent.isEmpty());
    }

    @Test
    public void aDamagedSettingStaysOnTheSystemVolume() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");
        prefs.put(SnapcastRendererOutput.KEY_GROUP, "garbage");

        volume.setPercent(10);

        assertEquals(10, local.percent);
        assertEquals(0, queue.size());
    }

    // ---- the group

    @Test
    public void theReportedStateComesFromTheGroupNotFromThePhone() {
        groupMode();
        group.level = new OutputRoutedVolume.Level(64, true);

        assertEquals(64, volume.getPercent());
        assertTrue(volume.isMuted());
        assertEquals("the phone's volume is not even read", 0, local.calls);
    }

    @Test
    public void whileTheModelHasNoValueTheLastKnownOneIsReportedThenZero() {
        groupMode();
        group.level = null;
        assertEquals(0, volume.getPercent());
        assertFalse(volume.isMuted());

        group.level = new OutputRoutedVolume.Level(70, true);
        assertEquals(70, volume.getPercent());
        group.level = null;

        assertEquals("last known", 70, volume.getPercent());
        assertTrue(volume.isMuted());
        assertEquals(0, local.calls);
    }

    @Test
    public void aSetGoesToTheGroupOnTheSenderThreadAndNeverToThePhone() {
        groupMode();

        volume.setPercent(80);
        volume.setMuted(true);

        assertTrue("returns at once, nothing sent yet", group.sent.isEmpty());
        runQueue();
        assertEquals(List.of("volume 80", "mute true"), group.sent);
        assertEquals(42, local.percent);
        assertFalse(local.muted);
        assertEquals(0, local.calls);
        assertTrue(problems.isEmpty());
    }

    @Test
    public void aQuickSeriesSendsOnlyTheLatestValue() {
        groupMode();

        for (int percent = 10; percent <= 90; percent += 10) {
            volume.setPercent(percent);
        }
        volume.setMuted(true);
        volume.setMuted(false);

        assertEquals("one task, not one per value", 1, queue.size());
        runQueue();
        assertEquals(List.of("volume 90", "mute false"), group.sent);
    }

    @Test
    public void aValueIsClampedToZeroToOneHundred() {
        groupMode();

        volume.setPercent(250);
        runQueue();
        volume.setPercent(-5);
        runQueue();

        assertEquals(List.of("volume 100", "volume 0"), group.sent);
    }

    @Test
    public void aServerThatIsNotThereGetsTheProblemReportedAndNothingSent() {
        groupMode();
        group.facts = GroupFacts.GONE;

        volume.setPercent(50);
        runQueue();

        assertTrue(group.sent.isEmpty());
        assertEquals(List.of(Problem.GROUP_GONE), problems);
        assertEquals(0, local.calls);
    }

    @Test
    public void anOfflineServerIsWokenAndWaitedForBeforeTheSet() {
        groupMode();
        group.facts = GroupFacts.OFFLINE;

        volume.setPercent(50);
        runQueue();

        assertEquals(1, group.wakes);
        assertEquals(List.of(Problem.SERVER_UNREACHABLE), problems);
        assertTrue("it waited up to the limit and no longer: " + now, now >= 4_000 && now <= 4_200);
    }

    @Test
    public void noGroupChosenIsReportedAndThePhoneIsNotTouched() {
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "group");

        assertEquals(0, volume.getPercent());
        volume.setPercent(40);
        volume.setMuted(true);
        runQueue();

        assertEquals(List.of(Problem.NO_GROUP_CHOSEN), problems.subList(0, 1));
        assertTrue(group.sent.isEmpty());
        assertEquals(0, local.calls);
    }

    // ---- no feedback

    @Test
    public void readingNeverSetsAnything() {
        groupMode();
        for (int i = 0; i < 20; i++) {
            group.level = new OutputRoutedVolume.Level(i * 5, i % 2 == 0);
            volume.getPercent();
            volume.isMuted();
        }

        assertEquals(0, queue.size());
        assertTrue(group.sent.isEmpty());
        assertEquals(0, local.calls);
    }

    // ---- waking the connection

    @Test
    public void readingWakesTheServerAtMostOnceInTheInterval() {
        groupMode();

        volume.getPercent();
        volume.isMuted();
        now += OutputRoutedVolume.WAKE_INTERVAL_MILLIS - 1;
        volume.getPercent();
        assertEquals(1, group.wakes);

        now += 2;
        volume.getPercent();

        assertEquals(2, group.wakes);
    }

    @Test
    public void thisDeviceNeverWakesAnything() {
        for (int i = 0; i < 10; i++) {
            volume.getPercent();
            now += 60_000;
        }

        assertEquals(0, group.wakes);
    }

    // ---- switching

    @Test
    public void everyCallFollowsTheSettingAsItIsNow() {
        assertEquals(42, volume.getPercent());
        groupMode();
        assertEquals(30, volume.getPercent());
        prefs.put(SnapcastRendererOutput.KEY_OUTPUT, "device");
        assertEquals(42, volume.getPercent());
    }
}
