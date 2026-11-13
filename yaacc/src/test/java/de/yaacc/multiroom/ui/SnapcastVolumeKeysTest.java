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

import static de.yaacc.multiroom.ui.UiFixtures.client;
import static de.yaacc.multiroom.ui.UiFixtures.group;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * The hardware volume keys on the focused Snapcast group (11.4): one step is {@value SnapcastVolumeKeys#STEP_PERCENT} points of
 * the group volume, clamped to 0..100; a muted group is unmuted by "up" and left alone by "down"; a group that can not be
 * controlled (no connected member, server unreachable, unknown) does not take the key, so the system keeps it. A held key
 * goes on from the value it asked for, not from a model that has not caught up. Plain JVM.
 */
public class SnapcastVolumeKeysTest {

    private static final SnapcastServerKey KEY = SnapcastEndpoint.parse("192.168.1.5").toKey();
    private static final String ID = SnapcastGroupDevice.idOf(KEY, "g1");

    private static final SnapcastRowText.Words WORDS = new SnapcastRowText.Words() {
        @Override
        public String stream(String name) {
            return name;
        }

        @Override
        public String noStream() {
            return "-";
        }

        @Override
        public String clientsConnected(int connected, int total) {
            return connected + "/" + total;
        }

        @Override
        public String noClients() {
            return "none";
        }

        @Override
        public String volume(int percent) {
            return "v" + percent;
        }

        @Override
        public String muted() {
            return "muted";
        }

        @Override
        public String serverUnreachable() {
            return "down";
        }

        @Override
        public String streamUnavailable() {
            return "unavailable";
        }

        @Override
        public String server(String endpoint) {
            return endpoint;
        }

        @Override
        public String groupGone() {
            return "gone";
        }
    };

    private final Map<String, SnapcastRowText.Row> rows = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(10_000);
    private SnapcastVolumeKeys keys;

    @Before
    public void setUp() {
        keys = new SnapcastVolumeKeys(rows::get, new SnapcastVolumeKeys.Sink() {
            @Override
            public void setGroupVolume(SnapcastServerKey key, String groupId, int percent) {
                assertEquals(KEY, key);
                calls.add("volume:" + groupId + ":" + percent);
            }

            @Override
            public void setGroupMuted(SnapcastServerKey key, String groupId, boolean muted) {
                assertEquals(KEY, key);
                calls.add("mute:" + groupId + ":" + muted);
            }
        }, now::get);
    }

    /** A group whose connected members all have {@code volume} (so the average is that), muted or not. */
    private void givenGroup(int volume, boolean muted, boolean online, boolean connected) {
        SnapcastClient member = client("m", "Kitchen", connected, volume, false);
        SnapcastGroup group = new SnapcastGroup("g1", "Living", muted, "Default", java.util.Collections.singletonList(member));
        rows.put(ID, SnapcastRowText.of(group, null, online, "192.168.1.5:1705", WORDS));
    }

    private void givenGroup(int volume, boolean muted) {
        givenGroup(volume, muted, true, true);
    }

    // ---- the decision (pure table)

    @Test
    public void upRaisesByOneStepAndDownLowersByOneStep() {
        assertEquals(45, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 40, false, true).getVolume());
        assertEquals(35, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.DOWN, 40, false, true).getVolume());
    }

    @Test
    public void theStepIsFivePoints() {
        assertEquals(5, SnapcastVolumeKeys.STEP_PERCENT);
    }

    @Test
    public void theVolumeIsClampedToZeroAndOneHundred() {
        assertEquals(100, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 98, false, true).getVolume());
        assertEquals(0, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.DOWN, 3, false, true).getVolume());
        assertEquals(100, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 95, false, true).getVolume());
        assertEquals(0, SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.DOWN, 5, false, true).getVolume());
    }

    @Test
    public void atTheEndOfTheRangeTheKeyIsTakenButNothingIsSent() {
        SnapcastVolumeKeys.Decision top = SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 100, false, true);
        SnapcastVolumeKeys.Decision bottom = SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.DOWN, 0, false, true);

        assertTrue(top.isHandled());
        assertEquals(-1, top.getVolume());
        assertFalse(top.isUnmute());
        assertTrue(bottom.isHandled());
        assertEquals(-1, bottom.getVolume());
    }

    @Test
    public void upOnAMutedGroupUnmutesAndRaises() {
        SnapcastVolumeKeys.Decision decision = SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 40, true, true);

        assertTrue(decision.isHandled());
        assertTrue(decision.isUnmute());
        assertEquals(45, decision.getVolume());
    }

    @Test
    public void upOnAMutedGroupAtTheTopOnlyUnmutes() {
        SnapcastVolumeKeys.Decision decision = SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.UP, 100, true, true);

        assertTrue(decision.isUnmute());
        assertEquals(-1, decision.getVolume());
    }

    @Test
    public void downOnAMutedGroupChangesNothingButIsTaken() {
        SnapcastVolumeKeys.Decision decision = SnapcastVolumeKeys.decide(SnapcastVolumeKeys.Direction.DOWN, 40, true, true);

        assertTrue("the phone's own volume must not move instead", decision.isHandled());
        assertFalse(decision.isUnmute());
        assertEquals(-1, decision.getVolume());
    }

    @Test
    public void aGroupThatCanNotBeControlledDoesNotTakeTheKey() {
        for (SnapcastVolumeKeys.Direction direction : SnapcastVolumeKeys.Direction.values()) {
            assertFalse(SnapcastVolumeKeys.decide(direction, 40, false, false).isHandled());
            assertFalse("no volume to start from", SnapcastVolumeKeys.decide(direction, -1, false, true).isHandled());
        }
    }

    // ---- the key on a group

    @Test
    public void aKeyPressSendsTheNewGroupVolumeToTheServerOfTheGroup() {
        givenGroup(40, false);

        SnapcastVolumeKeys.Result result = keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertTrue(result.isHandled());
        assertEquals(45, result.getVolume());
        assertFalse(result.isUnmuted());
        assertEquals(java.util.Arrays.asList("volume:g1:45"), calls);
    }

    @Test
    public void unmutingSendsTheMuteAndTheVolume() {
        givenGroup(40, true);

        SnapcastVolumeKeys.Result result = keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertTrue(result.isUnmuted());
        assertEquals(java.util.Arrays.asList("mute:g1:false", "volume:g1:45"), calls);
    }

    @Test
    public void downOnAMutedGroupSendsNothing() {
        givenGroup(40, true);

        SnapcastVolumeKeys.Result result = keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);

        assertTrue(result.isHandled());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void anUnreachableServerOrNoConnectedMemberLeavesTheKeyToTheSystem() {
        givenGroup(40, false, false, true);
        assertFalse(keys.press(ID, SnapcastVolumeKeys.Direction.UP).isHandled());

        givenGroup(40, false, true, false);
        assertFalse(keys.press(ID, SnapcastVolumeKeys.Direction.UP).isHandled());

        assertTrue(calls.isEmpty());
    }

    @Test
    public void aGroupThatIsNotKnownOrAnIdThatIsNoGroupLeavesTheKeyToTheSystem() {
        assertFalse(keys.press(ID, SnapcastVolumeKeys.Direction.UP).isHandled());
        assertFalse(keys.press("not-a-group", SnapcastVolumeKeys.Direction.UP).isHandled());
        assertFalse(keys.press(null, SnapcastVolumeKeys.Direction.UP).isHandled());
        assertTrue(calls.isEmpty());
    }

    // ---- a held key

    @Test
    public void aHeldKeyGoesOnFromWhatItAskedForWhileTheModelHasNotCaughtUp() {
        givenGroup(40, false);

        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(100);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(100);
        SnapcastVolumeKeys.Result third = keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals(java.util.Arrays.asList("volume:g1:45", "volume:g1:50", "volume:g1:55"), calls);
        assertEquals(55, third.getVolume());
    }

    @Test
    public void aHeldKeyGoesOnFromItsOwnValueEvenWhenTheModelShowsAnOlderOneInBetween() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP); // asks for 45
        givenGroup(42, false); // the server's answer for the first step is on its way, the model shows a value in between
        now.addAndGet(300);

        keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals("volume:g1:50", calls.get(1));
    }

    @Test
    public void afterAPauseTheModelIsTheStartAgain() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(SnapcastVolumeKeys.PENDING_MILLIS + 1);
        givenGroup(70, false); // somebody else changed the volume meanwhile

        keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals("volume:g1:75", calls.get(1));
    }

    @Test
    public void downAfterUpGoesBackOneStepFromThePendingValue() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(50);

        keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);

        assertEquals(java.util.Arrays.asList("volume:g1:45", "volume:g1:40"), calls);
    }

    @Test
    public void thePendingValueBelongsToOneGroup() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        String other = SnapcastGroupDevice.idOf(KEY, "g2");
        SnapcastGroup g2 = group("g2", "Bedroom", "Default", client("n", "Bed", true, 10, false));
        rows.put(other, SnapcastRowText.of(g2, null, true, "192.168.1.5:1705", WORDS));
        now.addAndGet(50);

        keys.press(other, SnapcastVolumeKeys.Direction.UP);

        assertEquals("volume:g2:15", calls.get(1));
    }

    @Test
    public void unmutingIsNotRepeatedByAHeldKey() {
        givenGroup(40, true);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(50);

        SnapcastVolumeKeys.Result second = keys.press(ID, SnapcastVolumeKeys.Direction.UP); // the model still says muted

        assertFalse(second.isUnmuted());
        assertEquals(java.util.Arrays.asList("mute:g1:false", "volume:g1:45", "volume:g1:50"), calls);
    }

    @Test
    public void aHeldDownKeyOnAMutedGroupKeepsItMutedAndAnUpThenUnmutes() {
        givenGroup(40, true);
        keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);
        now.addAndGet(50);

        SnapcastVolumeKeys.Result down = keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);
        SnapcastVolumeKeys.Result up = keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertTrue(down.isHandled());
        assertFalse(down.isUnmuted());
        assertTrue("the pending state still knows the group is muted", up.isUnmuted());
        assertEquals(java.util.Arrays.asList("mute:g1:false", "volume:g1:45"), calls);
    }

    // ---- review S9: a change made with the slider or the mute symbol is where the next key starts

    @Test
    public void aKeyAfterASliderChangeStartsFromTheSlidersValueNotFromTheLastKey() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP); // asks for 45
        now.addAndGet(300);
        keys.volumeChanged(ID, 80); // the user dragged the slider; the model has not caught up

        keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals("volume:g1:85", calls.get(1));
    }

    @Test
    public void theSlidersValueIsTheStartEvenWhenNoKeyWasPressedBefore() {
        givenGroup(40, false);

        keys.volumeChanged(ID, 10);
        keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);

        assertEquals(java.util.Arrays.asList("volume:g1:5"), calls);
    }

    @Test
    public void aSliderChangeKeepsTheMuteStateOfTheGroup() {
        givenGroup(40, true);

        keys.volumeChanged(ID, 10);
        SnapcastVolumeKeys.Result down = keys.press(ID, SnapcastVolumeKeys.Direction.DOWN);

        assertTrue(down.isHandled());
        assertTrue("still muted: down changes nothing", calls.isEmpty());
    }

    @Test
    public void aMuteChangeIsWhereTheNextKeyStartsAndKeepsTheVolume() {
        givenGroup(40, false);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP); // 45
        now.addAndGet(100);

        keys.muteChanged(ID, true); // the user muted with the symbol; the model has not caught up
        SnapcastVolumeKeys.Result up = keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertTrue("the key unmutes what the user just muted", up.isUnmuted());
        assertEquals(java.util.Arrays.asList("volume:g1:45", "mute:g1:false", "volume:g1:50"), calls);
    }

    @Test
    public void aMuteChangeBeforeAnyKeyStartsFromTheModelsVolume() {
        givenGroup(40, false);

        keys.muteChanged(ID, true);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals(java.util.Arrays.asList("mute:g1:false", "volume:g1:45"), calls);
    }

    @Test
    public void aChangeOfAnUnknownGroupIsIgnored() {
        keys.volumeChanged(ID, 10);
        keys.muteChanged(ID, true);
        keys.volumeChanged(null, 10);

        assertTrue(calls.isEmpty());
    }

    @Test
    public void aHeldKeyAtTheTopStaysAtTheTopWithoutFurtherCalls() {
        givenGroup(95, false);

        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        now.addAndGet(50);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);
        keys.press(ID, SnapcastVolumeKeys.Direction.UP);

        assertEquals(java.util.Arrays.asList("volume:g1:100"), calls);
    }
}
