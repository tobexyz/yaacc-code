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
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** The state of a device built from the captured answers and events (see {@code captured/README.md}). */
public class YxcDeviceStateTest {
    private final YxcEventParser parser = new YxcEventParser();

    private static YxcClient client(String status, String dist, String play) {
        return new YxcClient(new RecordingTransport()
                .answer("main/getStatus", YxcFixtures.read("captured/" + status + ".json"))
                .answer("dist/getDistributionInfo", YxcFixtures.read("captured/" + dist + ".json"))
                .answer("netusb/getPlayInfo", YxcFixtures.read("captured/" + play + ".json")));
    }

    private YxcEvent event(String name) {
        return parser.parse(YxcFixtures.read("captured/" + name + ".json"));
    }

    @Test
    public void unknownIsNotReachableAndHasNoRole() {
        YxcDeviceState state = YxcDeviceState.unknown();

        assertFalse(state.isReachable());
        assertEquals(YxcDistributionInfo.Role.UNKNOWN, state.getRole());
        assertNull(state.getPower());
    }

    @Test
    public void aStandbyDeviceIsReadFromTheCapturedAnswers() throws Exception {
        YxcClient device = client("a_status_unlinked", "b_distInfo_none", "a_playInfo");

        YxcDeviceState state = YxcDeviceState.unknown()
                .withZone(device.getZoneStatus("main"))
                .withDistribution(device.getDistributionInfo())
                .withPlayInfo(device.getPlayInfo());

        assertTrue(state.isReachable());
        assertEquals("standby", state.getPower());
        assertFalse(state.isPowerOn());
        assertEquals("tuner", state.getInput());
        assertEquals(Integer.valueOf(27), state.getVolume());
        assertEquals(Integer.valueOf(60), state.getMaxVolume());
        assertEquals(Boolean.FALSE, state.getMute());
        assertEquals(YxcDistributionInfo.Role.NONE, state.getRole());
        assertNull("the all-zero group has no name to show", state.getGroupName());
        assertEquals("stop", state.getPlayback());
    }

    @Test
    public void aLinkedClientShowsTheGroupAndTheLinkInput() throws Exception {
        YxcClient device = client("c_status_linked_client", "c_distInfo_client", "c_playInfo");

        YxcDeviceState state = YxcDeviceState.unknown()
                .withZone(device.getZoneStatus("main"))
                .withDistribution(device.getDistributionInfo());

        assertTrue(state.isPowerOn());
        assertTrue(state.isLinkInput());
        assertEquals(YxcDistributionInfo.Role.CLIENT, state.getRole());
        assertEquals("scrubbed-group_name", state.getGroupName());
    }

    @Test
    public void aServerCountsItsClients() throws Exception {
        YxcClient device = client("a_status_linked_server", "a_distInfo_server_pair", "a_playInfo");

        YxcDeviceState state = YxcDeviceState.unknown().withDistribution(device.getDistributionInfo());

        assertEquals(YxcDistributionInfo.Role.SERVER, state.getRole());
        assertEquals(1, state.getClientCount());
    }

    @Test
    public void aMissingPlayInfoClearsWhatPlayed() throws Exception {
        YxcClient device = client("a_status_unlinked", "b_distInfo_none", "a_playInfo");
        YxcDeviceState state = YxcDeviceState.unknown().withPlayInfo(device.getPlayInfo());

        YxcDeviceState cleared = state.withPlayInfo(null);

        assertNull(cleared.getPlayback());
        assertNull(cleared.getTrack());
    }

    @Test
    public void unreachableDropsTheValuesButKeepsTheGroup() throws Exception {
        YxcClient device = client("c_status_linked_client", "c_distInfo_client", "c_playInfo");
        YxcDeviceState state = YxcDeviceState.unknown()
                .withZone(device.getZoneStatus("main")).withDistribution(device.getDistributionInfo());

        YxcDeviceState gone = state.unreachable();

        assertFalse(gone.isReachable());
        assertNull(gone.getPower());
        assertNull(gone.getVolume());
        assertEquals(YxcDistributionInfo.Role.CLIENT, gone.getRole());
    }

    @Test
    public void eventsThatCarryValuesAreAppliedDirectly() throws Exception {
        YxcClient device = client("a_status_unlinked", "b_distInfo_none", "a_playInfo");
        YxcDeviceState state = YxcDeviceState.unknown().withZone(device.getZoneStatus("main"));

        YxcDeviceState afterPower = state.withEvent(event("a_event_power_on"));
        YxcDeviceState afterMute = afterPower.withEvent(event("a_event_mute_on"));
        YxcDeviceState afterVolume = afterMute.withEvent(event("a_event_volume"));

        assertEquals("on", afterPower.getPower());
        assertEquals(Boolean.TRUE, afterMute.getMute());
        assertEquals("the volume of the file", (Integer) event("a_event_volume").getZones().get("main").getVolume(), afterVolume.getVolume());
        assertEquals("tuner", afterVolume.getInput());
        assertNotEquals(state, afterVolume);
    }

    @Test
    public void eventsWithoutValuesAndPlayTimeTicksChangeNothing() throws Exception {
        YxcClient device = client("a_status_unlinked", "b_distInfo_none", "a_playInfo");
        YxcDeviceState state = YxcDeviceState.unknown().withZone(device.getZoneStatus("main"));

        assertEquals(state, state.withEvent(event("a_event_netusb_play_time")));
        assertEquals(state, state.withEvent(event("a_event_track_change")));
        assertEquals(state, state.withEvent(event("a_event_dist_info_updated")));
        assertEquals(state, state.withEvent(YxcEvent.unparsable()));
    }

    @Test
    public void whatEventsAskToReadAgain() {
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("a_event_netusb_play_time")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("a_event_signal_info_updated")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("a_event_volume")));
        assertEquals(refreshes(YxcEventPlan.Refresh.PLAY_INFO), YxcEventPlan.refreshesFor(event("a_event_track_change")));
        assertEquals(refreshes(YxcEventPlan.Refresh.PLAY_INFO), YxcEventPlan.refreshesFor(event("a_event_pause_play")));
        assertEquals(refreshes(YxcEventPlan.Refresh.DIST_INFO, YxcEventPlan.Refresh.ZONE_STATUS),
                YxcEventPlan.refreshesFor(event("a_event_dist_info_updated")));
        assertEquals(refreshes(YxcEventPlan.Refresh.DIST_INFO, YxcEventPlan.Refresh.ZONE_STATUS),
                YxcEventPlan.refreshesFor(event("a_event_play_time_and_dist_info_updated")));
        assertEquals(refreshes(YxcEventPlan.Refresh.ZONE_STATUS, YxcEventPlan.Refresh.PLAY_INFO),
                YxcEventPlan.refreshesFor(event("a_event_power_on")));
        assertEquals(refreshes(YxcEventPlan.Refresh.ZONE_STATUS, YxcEventPlan.Refresh.PLAY_INFO),
                YxcEventPlan.refreshesFor(event("a_event_power_standby")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(YxcEvent.unparsable()));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(null));
    }

    @Test
    public void whatTheEventsOfAClientAskToReadAgain() {
        YxcEventPlan.Refresh zone = YxcEventPlan.Refresh.ZONE_STATUS;
        YxcEventPlan.Refresh dist = YxcEventPlan.Refresh.DIST_INFO;
        YxcEventPlan.Refresh play = YxcEventPlan.Refresh.PLAY_INFO;

        assertEquals(refreshes(zone, dist, play), YxcEventPlan.refreshesFor(event("c_event_unlink_input_tuner")));
        assertEquals(refreshes(zone, dist, play), YxcEventPlan.refreshesFor(event("c_event_link_input_mc_link")));
        assertEquals(refreshes(zone, play), YxcEventPlan.refreshesFor(event("c_event_power_on_account")));
        assertEquals(refreshes(zone, play), YxcEventPlan.refreshesFor(event("c_event_power_standby")));
        assertEquals(refreshes(play), YxcEventPlan.refreshesFor(event("c_event_play_info_updated")));
        assertEquals("the tuner floods these, they cost nothing", Collections.emptySet(),
                YxcEventPlan.refreshesFor(event("c_event_tuner_play_info_updated")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("c_event_bluetooth_info_updated")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("c_event_account_updated")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("c_event_wake_up")));
        assertEquals(Collections.emptySet(), YxcEventPlan.refreshesFor(event("c_event_volume")));
    }

    @Test
    public void theLinkInputOfAnEventShowsAtOnceBeforeTheZoneIsRead() throws Exception {
        YxcClient device = client("c_status_linked_client", "c_distInfo_client", "c_playInfo");
        YxcDeviceState linked = YxcDeviceState.unknown().withZone(device.getZoneStatus("main"));

        YxcDeviceState unlinked = linked.withEvent(event("c_event_unlink_input_tuner"));
        YxcDeviceState relinked = unlinked.withEvent(event("c_event_link_input_mc_link"));

        assertEquals("tuner", unlinked.getInput());
        assertFalse(unlinked.isLinkInput());
        assertTrue(relinked.isLinkInput());
    }

    @Test
    public void anInputChangeReadsTheZoneAndThePlayInfo() {
        YxcEventPlan.Refresh zone = YxcEventPlan.Refresh.ZONE_STATUS;
        YxcEventPlan.Refresh play = YxcEventPlan.Refresh.PLAY_INFO;

        for (String name : new String[]{"c_event_input_net_radio", "c_event_input_bluetooth", "c_event_input_cd", "c_event_input_tuner"}) {
            assertEquals(name, refreshes(zone, play), YxcEventPlan.refreshesFor(event(name)));
        }
        assertEquals(refreshes(zone, play), YxcEventPlan.refreshesFor(event("c_event_power_on")));
        assertEquals(refreshes(YxcEventPlan.Refresh.DIST_INFO, zone, play), YxcEventPlan.refreshesFor(event("c_event_group_flags")));
        assertEquals("a play_time of 0 with the play flag reads the play info once", refreshes(play),
                YxcEventPlan.refreshesFor(event("c_event_play_time_start")));
    }

    @Test
    public void anInputEventReplacesTheInputAndForgetsItsDeviceName() throws Exception {
        YxcClient device = client("c_status_linked_client", "c_distInfo_client", "c_playInfo");
        YxcDeviceState linked = YxcDeviceState.unknown().withZone(device.getZoneStatus("main"));
        assertEquals("MC Link", linked.getInputText());

        YxcDeviceState radio = linked.withEvent(event("c_event_input_net_radio"));

        assertEquals("net_radio", radio.getInput());
        assertNull(radio.getInputText());
        assertTrue(radio.isPowerOn());
    }

    private static Set<YxcEventPlan.Refresh> refreshes(YxcEventPlan.Refresh first, YxcEventPlan.Refresh... more) {
        return EnumSet.of(first, more);
    }
}
