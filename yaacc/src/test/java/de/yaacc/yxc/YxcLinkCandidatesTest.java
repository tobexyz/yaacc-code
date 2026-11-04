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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** The picker lines and the row actions, from the states of captured answers. */
public class YxcLinkCandidatesTest {

    private static YxcDeviceState state(String json) throws Exception {
        return YxcDeviceState.unknown().withDistribution(new YxcClient(
                new RecordingTransport().answer("dist/getDistributionInfo", json)).getDistributionInfo());
    }

    private static String dist(String role, String groupId, String clients) {
        return "{\"response_code\":0,\"group_id\":\"" + groupId + "\",\"group_name\":\"x\",\"role\":\"" + role
                + "\",\"client_list\":[" + clients + "]}";
    }

    private static final String ZERO = "00000000000000000000000000000000";
    private static final String G1 = "00000000000000000000000000000001";
    private static final String G2 = "00000000000000000000000000000002";

    private static YxcDeviceSnapshot snap(String udn, String host, YxcDeviceState state) {
        return new YxcDeviceSnapshot(udn, host, state);
    }

    @Test
    public void theToggleFollowsTheRole() throws Exception {
        assertEquals("free: link", EnumSet.of(YxcLinkCandidates.Action.LINK_DEVICES),
                YxcLinkCandidates.actionsFor(state(dist("none", ZERO, ""))));
        assertEquals("serving: unlink (the clients leave with it)", EnumSet.of(YxcLinkCandidates.Action.UNLINK_GROUP),
                YxcLinkCandidates.actionsFor(state(dist("server", G1, "{\"ip_address\":\"192.0.2.2\"}"))));
        assertEquals("a client of another device: link shown but disabled", EnumSet.of(YxcLinkCandidates.Action.LINK_UNAVAILABLE),
                YxcLinkCandidates.actionsFor(state(dist("client", G1, ""))));
        assertEquals("a client that left but sits on the link input is in no group: it can be linked",
                EnumSet.of(YxcLinkCandidates.Action.LINK_DEVICES), YxcLinkCandidates.actionsFor(state(dist("client", ZERO, ""))));
    }

    @Test
    public void unknownAndUnreachableDevicesGetNoAction() throws Exception {
        assertTrue(YxcLinkCandidates.actionsFor(null).isEmpty());
        assertTrue(YxcLinkCandidates.actionsFor(YxcDeviceState.unknown()).isEmpty());
        assertTrue(YxcLinkCandidates.actionsFor(state(dist("none", ZERO, "")).unreachable()).isEmpty());
    }

    @Test
    public void thePickerListsEveryOtherDeviceWithItsReason() throws Exception {
        List<YxcDeviceSnapshot> devices = new ArrayList<>();
        devices.add(snap("server", "192.0.2.10", state(dist("server", G1, "{\"ip_address\":\"192.0.2.11\"}"))));
        devices.add(snap("member", "192.0.2.11", state(dist("client", G1, ""))));
        devices.add(snap("free", "192.0.2.12", state(dist("none", ZERO, ""))));
        devices.add(snap("stuck", "192.0.2.13", state(dist("client", ZERO, ""))));
        devices.add(snap("elsewhere", "192.0.2.14", state(dist("client", G2, ""))));
        devices.add(snap("otherServer", "192.0.2.15", state(dist("server", G2, "{\"ip_address\":\"192.0.2.14\"}"))));
        devices.add(snap("gone", "192.0.2.16", state(dist("none", ZERO, "")).unreachable()));
        devices.add(snap("unread", "192.0.2.17", YxcDeviceState.unknown()));

        List<YxcLinkCandidates.Candidate> lines = YxcLinkCandidates.forServer("server", devices);

        assertEquals(7, lines.size());
        assertLine(lines.get(0), "member", true, true, null);
        assertLine(lines.get(1), "free", false, true, null);
        assertLine(lines.get(2), "stuck", false, true, null);
        assertLine(lines.get(3), "elsewhere", false, false, YxcLinkCandidates.Reason.IN_OTHER_GROUP);
        assertLine(lines.get(4), "otherServer", false, false, YxcLinkCandidates.Reason.IN_OTHER_GROUP);
        assertLine(lines.get(5), "gone", false, false, YxcLinkCandidates.Reason.NOT_REACHABLE);
        assertLine(lines.get(6), "unread", false, false, YxcLinkCandidates.Reason.NOT_REACHABLE);
    }

    private static void assertLine(YxcLinkCandidates.Candidate line, String udn, boolean selected, boolean selectable,
                                   YxcLinkCandidates.Reason reason) {
        assertEquals(udn, line.getUdn());
        assertEquals(udn, selected, line.isSelected());
        assertEquals(udn, selectable, line.isSelectable());
        assertEquals(udn, reason, line.getDisabledBecause());
    }

    @Test
    public void aClientCanNotStartALink() throws Exception {
        List<YxcDeviceSnapshot> devices = new ArrayList<>();
        devices.add(snap("client", "192.0.2.11", state(dist("client", G1, ""))));
        devices.add(snap("free", "192.0.2.12", state(dist("none", ZERO, ""))));

        assertTrue(YxcLinkCandidates.forServer("client", devices).isEmpty());
        assertTrue(YxcLinkCandidates.forServer("unknown", devices).isEmpty());
    }

    @Test
    public void aStandbyDeviceInNoGroupCanBePicked() throws Exception {
        List<YxcDeviceSnapshot> devices = new ArrayList<>();
        devices.add(snap("a", "192.0.2.10", state(dist("none", ZERO, ""))));
        devices.add(snap("b", "192.0.2.11", state(dist("none", ZERO, ""))));

        List<YxcLinkCandidates.Candidate> lines = YxcLinkCandidates.forServer("a", devices);

        assertEquals(Collections.singletonList("b"), udns(lines));
        assertTrue(lines.get(0).isSelectable());
        assertFalse(lines.get(0).isSelected());
    }

    @Test
    public void aMemberThatDoesNotAnswerStaysSelectedButDisabled() throws Exception {
        List<YxcDeviceSnapshot> devices = new ArrayList<>();
        devices.add(snap("server", "192.0.2.10", state(dist("server", G1, "{\"ip_address\":\"192.0.2.11\"}"))));
        devices.add(snap("member", "192.0.2.11", state(dist("client", G1, "")).unreachable()));

        List<YxcLinkCandidates.Candidate> lines = YxcLinkCandidates.forServer("server", devices);

        assertEquals(1, lines.size());
        assertLine(lines.get(0), "member", true, false, YxcLinkCandidates.Reason.NOT_REACHABLE);
    }

    private static List<String> udns(List<YxcLinkCandidates.Candidate> lines) {
        List<String> result = new ArrayList<>();
        for (YxcLinkCandidates.Candidate line : lines) {
            result.add(line.getUdn());
        }
        return result;
    }

    @Test
    public void aMemberThatDoesNotAnswerCanStillBeUncheckedSoTheGroupCanBeEnded() throws Exception {
        List<YxcDeviceSnapshot> devices = new ArrayList<>();
        devices.add(snap("server", "192.0.2.10", state(dist("server", G1, "{\"ip_address\":\"192.0.2.11\"}"))));
        devices.add(snap("gone", "192.0.2.11", state(dist("client", G1, "")).unreachable()));
        devices.add(snap("elsewhere", "192.0.2.14", state(dist("client", G2, ""))));

        List<YxcLinkCandidates.Candidate> lines = YxcLinkCandidates.forServer("server", devices);

        assertTrue("the member can be unchecked", lines.get(0).isToggleable());
        assertFalse("a device of another group stays fixed", lines.get(1).isToggleable());
    }
}
