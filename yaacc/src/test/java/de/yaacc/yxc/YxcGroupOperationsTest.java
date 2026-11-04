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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The orchestration behind the sheet, against simulated devices that behave like the real ones measured on 2026-11-02:
 * they accept almost any call, a client that left keeps its role while it sits on the link input.
 */
public class YxcGroupOperationsTest {
    private static final String SERVER = "udn-server";
    private static final String C1 = "udn-c1";
    private static final String C2 = "udn-c2";
    private static final String S2 = "udn-other-server";

    private final SimulatedYxcNetwork network = new SimulatedYxcNetwork();
    private final SimulatedYxcNetwork.Device server = network.add("192.0.2.10");
    private final SimulatedYxcNetwork.Device c1 = network.add("192.0.2.11");
    private final SimulatedYxcNetwork.Device c2 = network.add("192.0.2.12");
    private final SimulatedYxcNetwork.Device otherServer = network.add("192.0.2.20");
    private final Map<String, String> lastPlainInputs = new HashMap<>();
    private final List<Set<String>> busyHistory = new ArrayList<>();
    private final List<String> refreshed = new ArrayList<>();

    private final YxcGroupOperations.Environment environment = new YxcGroupOperations.Environment() {
        @Override
        public List<YxcGroupOperations.Member> members() {
            return Arrays.asList(
                    new YxcGroupOperations.Member(SERVER, network.member("192.0.2.10")),
                    new YxcGroupOperations.Member(C1, network.member("192.0.2.11")),
                    new YxcGroupOperations.Member(C2, network.member("192.0.2.12")),
                    new YxcGroupOperations.Member(S2, network.member("192.0.2.20")));
        }

        @Override
        public String lastPlainInput(String udn) {
            return lastPlainInputs.get(udn);
        }

        @Override
        public void refresh(String udn) {
            refreshed.add(udn);
        }

        @Override
        public void busyChanged(Set<String> busyUdns) {
            busyHistory.add(new LinkedHashSet<>(busyUdns));
        }
    };

    private final YxcGroupOperations operations = new YxcGroupOperations(environment,
            new YxcGroupService(network.sleeper, new Random(7)));

    {
        for (SimulatedYxcNetwork.Device device : Arrays.asList(server, c1, c2, otherServer)) {
            device.linkInputKeepsClientRole = true;
        }
        c1.input = "tuner";
        c2.input = "net_radio";
        server.input = "optical";
    }

    private static List<String> list(String... udns) {
        return Arrays.asList(udns);
    }

    // ---- link, extend, reduce, end

    @Test
    public void aSelectionLinksTheDevicesAndMarksThemBusyWhileItRuns() {
        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("server", server.role());
        assertEquals(Collections.singletonList("192.0.2.11"), server.clients);
        assertEquals("client", c1.role());
        assertEquals(YxcZoneFeatures.GROUP_INPUT_ID, c1.input);
        assertEquals(new LinkedHashSet<>(list(SERVER, C1)), busyHistory.get(0));
        assertTrue("busy ends", busyHistory.get(busyHistory.size() - 1).isEmpty());
        assertTrue(refreshed.containsAll(list(SERVER, C1)));
    }

    @Test
    public void aLargerSelectionAddsOnlyTheNewDevices() {
        operations.applySelection(SERVER, list(C1));
        int joinCallsOfC1 = network.count("192.0.2.11", "POST dist/setClientInfo");

        YxcGroupResult result = operations.applySelection(SERVER, list(C1, C2));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(Arrays.asList("192.0.2.11", "192.0.2.12"), server.clients);
        assertEquals("the first client is not touched again", joinCallsOfC1, network.count("192.0.2.11", "POST dist/setClientInfo"));
    }

    @Test
    public void aSmallerSelectionRemovesTheOthersAndPutsThemBackOnTheirInput() {
        operations.applySelection(SERVER, list(C1, C2));

        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("the group goes on", "server", server.role());
        assertEquals(Collections.singletonList("192.0.2.11"), server.clients);
        assertEquals("net_radio", c2.input);
        assertEquals("none", c2.role());
        assertEquals("the member that stays is left alone", YxcZoneFeatures.GROUP_INPUT_ID, c1.input);
    }

    @Test
    public void anEmptySelectionEndsTheGroup() {
        operations.applySelection(SERVER, list(C1, C2));

        YxcGroupResult result = operations.applySelection(SERVER, Collections.<String>emptyList());

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("none", server.role());
        assertEquals("tuner", c1.input);
        assertEquals("net_radio", c2.input);
        assertEquals("none", c1.role());
        assertEquals("none", c2.role());
    }

    @Test
    public void aSelectionThatChangesNothingSendsNoChangingCall() {
        operations.applySelection(SERVER, list(C1));
        int before = network.changingCalls();

        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertTrue(result.isSuccess());
        assertEquals(before, network.changingCalls());
    }

    @Test
    public void swappingTheClientsRemovesOneAndAddsTheOther() {
        operations.applySelection(SERVER, list(C1));

        YxcGroupResult result = operations.applySelection(SERVER, list(C2));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(Collections.singletonList("192.0.2.12"), server.clients);
        assertEquals("tuner", c1.input);
        assertEquals(YxcZoneFeatures.GROUP_INPUT_ID, c2.input);
    }

    // ---- refusals

    @Test
    public void aDeviceInAnotherGroupIsRefusedAndNothingChanges() {
        otherServer.servingGroup("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "192.0.2.12");
        c2.clientOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "192.0.2.20");
        int before = network.changingCalls();

        YxcGroupResult result = operations.applySelection(SERVER, list(C2));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.VALIDATE, result.getStep());
        assertEquals("192.0.2.12", result.getDeviceIp());
        assertEquals(before, network.changingCalls());
        assertEquals("the other group is intact", Collections.singletonList("192.0.2.12"), otherServer.clients);
    }

    @Test
    public void aDeviceThatServesAGroupIsRefused() {
        otherServer.servingGroup("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", "192.0.2.12");

        YxcGroupResult result = operations.applySelection(SERVER, list(S2));

        assertEquals(YxcGroupResult.Step.VALIDATE, result.getStep());
        assertEquals("192.0.2.20", result.getDeviceIp());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void aServerThatIsAClientOfAGroupIsRefused() {
        otherServer.servingGroup("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "192.0.2.10");
        server.clientOf("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "192.0.2.20");

        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertEquals(YxcGroupResult.Step.VALIDATE, result.getStep());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void anUnknownDeviceIsRefused() {
        assertEquals(YxcGroupResult.Step.VALIDATE, operations.applySelection("udn-unknown", list(C1)).getStep());
        assertEquals(YxcGroupResult.Step.VALIDATE, operations.applySelection(SERVER, list("udn-unknown")).getStep());
        assertEquals(YxcGroupResult.Step.VALIDATE, operations.leave("udn-unknown").getStep());
        assertEquals(YxcGroupResult.Step.VALIDATE, operations.unlinkGroup("udn-unknown").getStep());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void theServerCanNotBePickedAsItsOwnClient() {
        assertEquals(YxcGroupResult.Step.VALIDATE, operations.applySelection(SERVER, list(SERVER)).getStep());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void aDeviceThatDoesNotAnswerFailsAtReadingAndBusyEnds() {
        c1.unreachable = true;

        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
        assertEquals("192.0.2.11", result.getDeviceIp());
        assertTrue(busyHistory.get(busyHistory.size() - 1).isEmpty());
        assertEquals(0, network.changingCalls());
    }

    // ---- leave and unlink

    @Test
    public void aClientLeavesAndIsPutBackOnItsInputAndTheGroupGoesOn() {
        operations.applySelection(SERVER, list(C1, C2));

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("tuner", c1.input);
        assertEquals("none", c1.role());
        assertEquals(Collections.singletonList("192.0.2.12"), server.clients);
        assertEquals("server", server.role());
    }

    @Test
    public void theLastClientLeavingEndsTheGroup() {
        operations.applySelection(SERVER, list(C1));

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("none", server.role());
        assertEquals("tuner", c1.input);
    }

    @Test
    public void aStuckClientIsFixedByTheInputAlone() {
        c1.input = YxcZoneFeatures.GROUP_INPUT_ID; // left earlier, still on the link input
        lastPlainInputs.put(C1, "bluetooth");
        assertEquals("client", c1.role());
        int groupCalls = network.count("192.0.2.11", "POST dist/");

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("bluetooth", c1.input);
        assertEquals("none", c1.role());
        assertEquals("no group call for it", groupCalls, network.count("192.0.2.11", "POST dist/"));
    }

    @Test
    public void unlinkingTheGroupPutsEveryClientBackAndEndsTheServer() {
        operations.applySelection(SERVER, list(C1, C2));
        lastPlainInputs.put(C1, "hdmi1"); // what the monitor saw; the input read just before our own join is newer and wins

        YxcGroupResult result = operations.unlinkGroup(SERVER);

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("none", server.role());
        assertEquals("tuner", c1.input);
        assertEquals("net_radio", c2.input);
        assertEquals("none", c1.role());
        assertEquals("none", c2.role());
    }

    @Test
    public void unlinkingADeviceThatServesNothingChangesNothing() {
        assertTrue(operations.unlinkGroup(SERVER).isSuccess());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void aServerCanNotLeaveAsAClient() {
        operations.applySelection(SERVER, list(C1));

        YxcGroupResult result = operations.leave(SERVER);

        assertEquals(YxcGroupResult.Step.VALIDATE, result.getStep());
        assertEquals("server", server.role());
    }

    // ---- the input to go back to

    @Test
    public void withoutAnyMemoryTheFirstPlainInputOfTheDeviceIsUsed() {
        c1.featuresJson = "{\"response_code\":0,\"zone\":[{\"id\":\"main\",\"input_list\":[\"mc_link\",\"optical\",\"tuner\"]}],"
                + "\"distribution\":{\"version\":2.0,\"compatible_client\":[2],\"client_max\":9,\"server_zone_list\":[\"main\"]}}";
        c1.input = YxcZoneFeatures.GROUP_INPUT_ID;

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("optical", c1.input);
        assertEquals("none", c1.role());
    }

    @Test
    public void withoutAnyInputToGoBackToTheDeviceIsLeftAlone() {
        c1.input = YxcZoneFeatures.GROUP_INPUT_ID;

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.isSuccess());
        assertEquals(YxcZoneFeatures.GROUP_INPUT_ID, c1.input);
    }

    @Test
    public void aClientAlreadyOnAnotherInputIsNotTouched() {
        int before = network.changingCalls();

        assertTrue(operations.leave(C1).isSuccess());

        assertEquals(before, network.changingCalls());
    }

    @Test
    public void aFailedInputRestoreIsReportedBecauseTheClientStaysOnTheLinkInput() {
        operations.applySelection(SERVER, list(C1));
        c1.brokenCalls.add("main/setInput");

        YxcGroupResult result = operations.leave(C1);

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.RESTORE_INPUT, result.getStep());
        assertEquals("192.0.2.11", result.getDeviceIp());
        assertFalse(busyHistory.isEmpty());
        assertTrue(busyHistory.get(busyHistory.size() - 1).isEmpty());
    }

    @Test
    public void aStuckServerIsFixedBeforeItServes() {
        server.input = YxcZoneFeatures.GROUP_INPUT_ID;
        lastPlainInputs.put(SERVER, "optical");

        YxcGroupResult result = operations.applySelection(SERVER, list(C1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("server", server.role());
        assertEquals("optical", server.input);
    }

    @Test
    public void aMemberThatDoesNotAnswerStaysInTheGroupWhenAnotherIsAdded() {
        operations.applySelection(SERVER, list(C1));
        c1.unreachable = true;

        YxcGroupResult result = operations.applySelection(SERVER, list(C1, C2));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(Arrays.asList("192.0.2.11", "192.0.2.12"), server.clients);
        assertEquals("client", c2.role());
    }

    @Test
    public void aFailedInputRestoreDoesNotStopTheClientsThatAreToBeAdded() {
        operations.applySelection(SERVER, list(C1));
        c1.brokenCalls.add("main/setInput");

        YxcGroupResult result = operations.applySelection(SERVER, list(C2));

        assertEquals("the restore failure is what the user hears", YxcGroupResult.Step.RESTORE_INPUT, result.getStep());
        assertEquals("192.0.2.11", result.getDeviceIp());
        assertEquals("the new client joined anyway", Collections.singletonList("192.0.2.12"), server.clients);
        assertEquals("client", c2.role());
    }

    @Test
    public void everyLeaverIsPutBackEvenWhenOneCanNot() {
        operations.applySelection(SERVER, list(C1, C2));
        c1.brokenCalls.add("main/setInput");

        YxcGroupResult result = operations.unlinkGroup(SERVER);

        assertEquals(YxcGroupResult.Step.RESTORE_INPUT, result.getStep());
        assertEquals("the other client is put back", "net_radio", c2.input);
        assertEquals("none", server.role());
    }

    @Test
    public void theClientsAreBusyWhileAGroupIsUnlinked() {
        operations.applySelection(SERVER, list(C1, C2));
        busyHistory.clear();

        operations.unlinkGroup(SERVER);

        assertEquals(new LinkedHashSet<>(list(SERVER)), busyHistory.get(0));
        assertEquals("announced again once the clients are known", new LinkedHashSet<>(list(SERVER, C1, C2)), busyHistory.get(1));
        assertTrue(busyHistory.get(busyHistory.size() - 1).isEmpty());
    }

    @Test
    public void theLeaversAreBusyWhileTheSelectionShrinks() {
        operations.applySelection(SERVER, list(C1, C2));
        busyHistory.clear();

        operations.applySelection(SERVER, list(C1));

        assertTrue(busyHistory.get(1).contains(C2));
    }

    @Test
    public void theRowsAreAskedForAFreshReadBeforeTheyAreFreed() {
        java.util.ArrayList<String> order = new java.util.ArrayList<>();
        YxcGroupOperations.Environment recording = new YxcGroupOperations.Environment() {
            @Override
            public List<YxcGroupOperations.Member> members() {
                return environment.members();
            }

            @Override
            public String lastPlainInput(String udn) {
                return null;
            }

            @Override
            public void refresh(String udn) {
                order.add("refresh");
            }

            @Override
            public void busyChanged(Set<String> busyUdns) {
                order.add(busyUdns.isEmpty() ? "free" : "busy");
            }
        };
        YxcGroupOperations own = new YxcGroupOperations(recording, new YxcGroupService(network.sleeper, new Random(3)));

        own.applySelection(SERVER, list(C1));

        assertEquals("free", order.get(order.size() - 1));
        assertTrue(order.indexOf("refresh") < order.lastIndexOf("free"));
    }

    @Test
    public void aServerThatListsADeviceOfAnotherGroupDoesNotTakeItOutOfThatGroup() {
        server.servingGroup("1111AAAA1111AAAA1111AAAA1111AAAA", "192.0.2.12"); // lists c2 ...
        c2.clientOf("2222BBBB2222BBBB2222BBBB2222BBBB", "192.0.2.20");         // ... which is in another group
        otherServer.servingGroup("2222BBBB2222BBBB2222BBBB2222BBBB", "192.0.2.12");

        YxcGroupResult result = operations.applySelection(SERVER, Collections.<String>emptyList());

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("the other group is intact", "2222BBBB2222BBBB2222BBBB2222BBBB", c2.groupId);
        assertEquals("client", c2.role());
        assertEquals(Collections.singletonList("192.0.2.12"), otherServer.clients);
    }

    @Test
    public void anInputThatDoesNotLookLikeAnInputIdIsNeverSent() {
        c1.input = YxcZoneFeatures.GROUP_INPUT_ID;
        lastPlainInputs.put(C1, "x&y=../z");

        YxcGroupResult result = operations.leave(C1);

        assertTrue(result.isSuccess());
        assertEquals("left as it was", YxcZoneFeatures.GROUP_INPUT_ID, c1.input);
    }
}
