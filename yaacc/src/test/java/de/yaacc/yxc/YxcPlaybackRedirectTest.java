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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where a send goes when some of the selected receivers are linked clients. */
public class YxcPlaybackRedirectTest {
    private static final String ZERO = "00000000000000000000000000000000";
    private static final String G1 = "00000000000000000000000000000001";

    private static YxcDeviceState state(String role, String groupId, String clients) throws Exception {
        String json = "{\"response_code\":0,\"group_id\":\"" + groupId + "\",\"group_name\":\"x\",\"role\":\"" + role
                + "\",\"client_list\":[" + clients + "]}";
        return YxcDeviceState.unknown().withDistribution(new YxcClient(
                new RecordingTransport().answer("dist/getDistributionInfo", json)).getDistributionInfo());
    }

    private static YxcGroupDirectory directory(YxcDeviceSnapshot... devices) {
        return new YxcGroupDirectory(new ArrayList<>(Arrays.asList(devices)));
    }

    private static YxcDeviceSnapshot snap(String udn, String host, YxcDeviceState state) {
        return new YxcDeviceSnapshot(udn, host, state);
    }

    @Test
    public void aSendToALinkedClientGoesToItsServer() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.10", state("server", G1, "{\"ip_address\":\"192.0.2.11\"}")),
                snap("client", "192.0.2.11", state("client", G1, "")));

        YxcPlaybackRedirect.Plan plan = YxcPlaybackRedirect.plan(Collections.singletonList("client"), directory);

        assertEquals(Collections.singletonList("server"), plan.getTargets());
        assertEquals("server", plan.getRedirected().get("client"));
        assertTrue(plan.getRefused().isEmpty());
    }

    @Test
    public void theServerIsPlayedOnOnlyOnceWhenClientAndServerAreSelected() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.10", state("server", G1, "{\"ip_address\":\"192.0.2.11\"}")),
                snap("client", "192.0.2.11", state("client", G1, "")));

        YxcPlaybackRedirect.Plan plan = YxcPlaybackRedirect.plan(Arrays.asList("client", "server"), directory);

        assertEquals(Collections.singletonList("server"), plan.getTargets());
    }

    @Test
    public void aClientWhoseServerIsNotKnownIsRefusedAndNeverPlayedOn() throws Exception {
        YxcGroupDirectory directory = directory(snap("client", "192.0.2.11", state("client", G1, "")));

        YxcPlaybackRedirect.Plan plan = YxcPlaybackRedirect.plan(Collections.singletonList("client"), directory);

        assertTrue(plan.getTargets().isEmpty());
        assertEquals(Collections.singletonList("client"), plan.getRefused());
    }

    @Test
    public void everyoneElseIsLeftAsSelectedInTheirOrder() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("free", "192.0.2.12", state("none", ZERO, "")),
                snap("server", "192.0.2.10", state("server", G1, "{\"ip_address\":\"192.0.2.11\"}")),
                snap("stuck", "192.0.2.13", state("client", ZERO, "")));

        YxcPlaybackRedirect.Plan plan = YxcPlaybackRedirect.plan(Arrays.asList("plain-renderer", "free", "stuck", "server"), directory);

        assertEquals(Arrays.asList("plain-renderer", "free", "stuck", "server"), plan.getTargets());
        assertTrue(plan.getRedirected().isEmpty());
        assertTrue(plan.getRefused().isEmpty());
    }

    @Test
    public void severalClientsOfOneGroupPlayOnTheServerOnce() throws Exception {
        YxcGroupDirectory directory = directory(
                snap("server", "192.0.2.10", state("server", G1, "{\"ip_address\":\"192.0.2.11\"},{\"ip_address\":\"192.0.2.12\"}")),
                snap("c1", "192.0.2.11", state("client", G1, "")),
                snap("c2", "192.0.2.12", state("client", G1, "")));

        YxcPlaybackRedirect.Plan plan = YxcPlaybackRedirect.plan(Arrays.asList("c1", "c2"), directory);

        assertEquals(Collections.singletonList("server"), plan.getTargets());
        assertEquals(2, plan.getRedirected().size());
    }

    // ---- the failure messages

    @Test
    public void aFailedResultGetsOneOfFourKinds() {
        assertNull(YxcGroupMessages.kindOf(YxcGroupResult.success(null)));
        assertNull(YxcGroupMessages.kindOf(null));
        assertEquals(YxcGroupMessages.Kind.NOT_POSSIBLE,
                YxcGroupMessages.kindOf(YxcGroupResult.failed(YxcGroupResult.Step.VALIDATE, "192.0.2.1", "x")));
        assertEquals(YxcGroupMessages.Kind.UNREACHABLE,
                YxcGroupMessages.kindOf(YxcGroupResult.failed(YxcGroupResult.Step.READ_STATE, "192.0.2.1", "x")));
        assertEquals(YxcGroupMessages.Kind.INPUT_NOT_RESTORED,
                YxcGroupMessages.kindOf(YxcGroupResult.failed(YxcGroupResult.Step.RESTORE_INPUT, "192.0.2.1", "x")));
        assertEquals(YxcGroupMessages.Kind.FAILED,
                YxcGroupMessages.kindOf(YxcGroupResult.timeout(YxcGroupResult.Step.JOIN_CLIENT, "192.0.2.1", "x")));
        assertEquals(YxcGroupMessages.Kind.FAILED,
                YxcGroupMessages.kindOf(YxcGroupResult.failed(YxcGroupResult.Step.CLOSE_GROUP, "192.0.2.1", "x")));
    }

}
