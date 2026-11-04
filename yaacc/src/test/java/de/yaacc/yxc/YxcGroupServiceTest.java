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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The group sequences against a few simulated devices (see {@link SimulatedYxcNetwork}); nothing really sleeps. */
public class YxcGroupServiceTest {
    private static final String SERVER = "192.0.2.10";
    private static final String CLIENT_1 = "192.0.2.11";
    private static final String CLIENT_2 = "192.0.2.12";
    private static final String OTHER = "192.0.2.20";
    private static final String OTHER_CLIENT = "192.0.2.21";
    private static final String OLD_GROUP = "FEDCBA9876543210FEDCBA9876543210";
    private static final long SEED = 42L;

    private SimulatedYxcNetwork network;
    private SimulatedYxcNetwork.Device server;
    private SimulatedYxcNetwork.Device client1;
    private SimulatedYxcNetwork.Device client2;
    private YxcGroupService service;

    @Before
    public void setUp() {
        network = new SimulatedYxcNetwork();
        server = network.add(SERVER);
        client1 = network.add(CLIENT_1);
        client2 = network.add(CLIENT_2);
        service = new YxcGroupService(network.sleeper, new Random(SEED));
    }

    private YxcGroupMember m(String ip) {
        return network.member(ip);
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    private static String expectedFirstGroupId() {
        byte[] bytes = new byte[16];
        new Random(SEED).nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) {
            id.append(String.format("%02X", b));
        }
        return id.toString();
    }

    private void assertNotLinked(SimulatedYxcNetwork.Device device) {
        assertEquals(YxcDistributionInfo.NULL_GROUP_ID, device.groupId);
        assertFalse(device.server);
        assertFalse(device.client);
        assertTrue(device.clients.isEmpty());
    }

    private void assertInGroup(String groupId, SimulatedYxcNetwork.Device server, SimulatedYxcNetwork.Device... clients) {
        assertTrue(server.server);
        assertEquals(groupId, server.groupId);
        assertEquals("main", server.serverZone);
        for (SimulatedYxcNetwork.Device client : clients) {
            assertTrue(client.ip, server.clients.contains(client.ip));
            assertTrue(client.ip, client.client);
            assertEquals(groupId, client.groupId);
            assertEquals(YxcZoneFeatures.GROUP_INPUT_ID, client.input);
            assertEquals(SERVER, client.serverIp);
        }
        assertEquals(clients.length, server.clients.size());
    }

    // ---- link

    @Test
    public void linkTwoDevices() {
        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertNull(result.getStep());
        assertInGroup(result.getGroupId(), server, client1);
        assertEquals(1, server.distributionStarts.size());
        assertEquals("the very first group starts with 0", Integer.valueOf(0), server.distributionStarts.get(0));
    }

    private List<YxcGroupMember> list2(String... ips) {
        YxcGroupMember[] members = new YxcGroupMember[ips.length];
        for (int i = 0; i < ips.length; i++) {
            members[i] = m(ips[i]);
        }
        return Arrays.asList(members);
    }

    @Test
    public void theGroupIdIs32UppercaseHexDigitsFromTheInjectedRandomSource() {
        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(expectedFirstGroupId(), result.getGroupId());
        assertTrue(result.getGroupId().matches("[0-9A-F]{32}"));
        assertFalse(YxcDistributionInfo.isNullGroupId(result.getGroupId()));
    }

    @Test
    public void clientsAreSetUpBeforeTheServerListsThem() {
        service.link(m(SERVER), list2(CLIENT_1, CLIENT_2));

        int clientCall = network.calls.indexOf(CLIENT_2 + " POST dist/setClientInfo");
        int inputCall = network.calls.indexOf(CLIENT_2 + " GET main/setInput?input=mc_link&mode=");
        int serverCall = network.calls.indexOf(SERVER + " POST dist/setServerInfo");
        int startCall = network.calls.indexOf(SERVER + " GET dist/startDistribution?num=0");
        assertTrue(clientCall >= 0 && inputCall > clientCall);
        assertTrue("the server call comes after the last client", serverCall > inputCall);
        assertTrue("start comes after the server call", startCall > serverCall);
    }

    @Test
    public void severalClientsAreListedWithOneServerCall() {
        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1, CLIENT_2));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(result.getGroupId(), server, client1, client2);
        assertEquals(1, network.count(SERVER, "POST dist/setServerInfo"));
    }

    @Test
    public void aServerInStandbyIsPoweredOn() {
        server.power = "standby";
        client1.power = "standby";

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("on", server.power);
        assertEquals("on", client1.power);
    }

    @Test
    public void aDeviceThatIsOnIsNotPoweredOnAgain() {
        service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(0, network.count(SERVER, "GET main/setPower"));
        assertEquals(0, network.count(CLIENT_1, "GET main/setPower"));
    }

    @Test
    public void aClientThatIsInAnotherGroupLeavesItFirst() {
        SimulatedYxcNetwork.Device other = network.add(OTHER);
        other.servingGroup(OLD_GROUP, CLIENT_1, OTHER_CLIENT);
        client1.clientOf(OLD_GROUP, OTHER);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(result.getGroupId(), server, client1);
        assertTrue("it left the old group before it joined", network.calls.indexOf(CLIENT_1 + " POST dist/setClientInfo")
                < network.calls.lastIndexOf(CLIENT_1 + " POST dist/setClientInfo"));
        assertEquals("the old server still lists it: only its own device is changed", OLD_GROUP, other.groupId);
    }

    @Test
    public void aClientThatServesAGroupOfItsOwnEndsThatGroupFirst() {
        client1.servingGroup(OLD_GROUP, OTHER_CLIENT);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(result.getGroupId(), server, client1);
        assertFalse(client1.server);
        assertTrue(client1.clients.isEmpty());
        assertEquals(1, network.count(CLIENT_1, "GET dist/stopDistribution"));
    }

    @Test
    public void aServerThatIsAClientOfAnotherGroupLeavesItFirst() {
        SimulatedYxcNetwork.Device other = network.add(OTHER);
        other.servingGroup(OLD_GROUP, SERVER);
        server.clientOf(OLD_GROUP, OTHER);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(result.getGroupId(), server, client1);
        assertFalse(OLD_GROUP.equals(result.getGroupId()));
    }

    @Test
    public void aClientAlreadyInTheGroupIsNotTouched() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1, CLIENT_2));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("a server that serves gets the clients added to its group", OLD_GROUP, result.getGroupId());
        assertEquals(0, network.count(CLIENT_1, "POST dist/setClientInfo"));
        assertInGroup(OLD_GROUP, server, client1, client2);
    }

    // ---- link: failures

    @Test
    public void anUnreachableServerFailsAtReadingAndChangesNothing() {
        server.unreachable = true;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
        assertEquals(SERVER, result.getDeviceIp());
        assertEquals(0, network.changingCalls());
        assertNotLinked(client1);
    }

    @Test
    public void anUnreachableClientFailsAtReadingAndChangesNothing() {
        client2.unreachable = true;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1, CLIENT_2));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
        assertEquals(CLIENT_2, result.getDeviceIp());
        assertEquals("nobody was changed, not even the reachable client", 0, network.changingCalls());
        assertNotLinked(client1);
        assertNotLinked(server);
    }

    @Test
    public void aClientThatBreaksWhileJoiningTakesTheOthersBack() {
        client2.brokenCalls.add("dist/setClientInfo");

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1, CLIENT_2));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.JOIN_CLIENT, result.getStep());
        assertEquals(CLIENT_2, result.getDeviceIp());
        assertNotLinked(client1);
        assertNotLinked(server);
        assertEquals("the server was never asked", 0, network.count(SERVER, "POST"));
    }

    @Test
    public void aDeviceThatSaysGuardedIsAFailureWithTheCode() {
        client1.forcedCodes.put("main/setInput", 5);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.JOIN_CLIENT, result.getStep());
        assertTrue(result.getDetail(), result.getDetail().contains("response_code 5"));
        assertNotLinked(client1);
    }

    @Test
    public void aServerThatRefusesTheListingTakesTheClientsBack() {
        server.forcedCodes.put("dist/setServerInfo", 4);

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.EXTEND_SERVER, result.getStep());
        assertEquals(SERVER, result.getDeviceIp());
        assertTrue(result.getDetail().contains("response_code 4"));
        assertNotLinked(client1);
    }

    @Test
    public void aDeviceThatAnswersLateIsWaitedFor() {
        client1.lateMillis = 450;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(result.getGroupId(), server, client1);
        assertTrue("waited in virtual time", network.now() >= 450);
        assertEquals("one attempt was enough", 1, network.count(CLIENT_1, "POST dist/setClientInfo"));
    }

    @Test
    public void aServerThatAnswersLateIsWaitedFor() {
        server.lateMillis = 800;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(1, network.count(SERVER, "POST dist/setServerInfo"));
    }

    @Test
    public void aDeviceThatIsTooLateGetsOneRetryAndThenItIsATimeout() {
        client1.lateMillis = 5000;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.TIMEOUT, result.getOutcome());
        assertEquals(YxcGroupResult.Step.JOIN_CLIENT, result.getStep());
        assertEquals(CLIENT_1, result.getDeviceIp());
        assertEquals("the step is repeated once, then the half-joined client is taken back", 3, network.count(CLIENT_1, "POST dist/setClientInfo"));
        assertTrue("about one second of waiting per attempt", network.now() >= 2000 && network.now() < 2500);
    }

    @Test
    public void aServerThatNeverShowsTheClientsIsATimeoutAtTheServerStep() {
        server.ignoreChanges = true;

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.TIMEOUT, result.getOutcome());
        assertEquals(YxcGroupResult.Step.EXTEND_SERVER, result.getStep());
        assertEquals(2, network.count(SERVER, "POST dist/setServerInfo"));
        assertNotLinked(client1);
    }

    @Test
    public void anInterruptedWaitIsAFailureAndKeepsTheInterruptFlag() {
        client1.lateMillis = 300;
        YxcGroupService interrupted = new YxcGroupService(millis -> {
            throw new InterruptedException();
        }, new Random(SEED));
        try {
            YxcGroupResult result = interrupted.link(m(SERVER), list2(CLIENT_1));

            assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
            assertEquals("interrupted", result.getDetail());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // ---- link: bad requests

    @Test
    public void badRequestsAreRejectedBeforeAnythingIsSent() {
        YxcGroupMember s = m(SERVER);
        assertValidationFailure(service.link(null, list2(CLIENT_1)));
        assertValidationFailure(service.link(s, null));
        assertValidationFailure(service.link(s, Collections.<YxcGroupMember>emptyList()));
        assertValidationFailure(service.link(s, list2(SERVER)));
        assertValidationFailure(service.link(s, list2(CLIENT_1, CLIENT_1)));
        assertValidationFailure(service.link(s, Arrays.asList(m(CLIENT_1), null)));
        assertValidationFailure(service.extend(s, Collections.<YxcGroupMember>emptyList()));
        assertValidationFailure(service.reduce(s, Collections.<YxcGroupMember>emptyList()));
        assertValidationFailure(service.unlink(null, null));
        assertValidationFailure(service.rename(null, "x"));
        assertEquals(0, network.calls.size());
    }

    // ---- the number of clients: client_max of the server's features

    private List<YxcGroupMember> clientsOnTheNetwork(int count) {
        YxcGroupMember[] members = new YxcGroupMember[count];
        for (int i = 0; i < count; i++) {
            String ip = "192.0.2." + (100 + i);
            network.add(ip);
            members[i] = m(ip);
        }
        return Arrays.asList(members);
    }

    @Test
    public void tenClientsAreRefusedByADeviceWithClientMaxNine() {
        server.featuresJson = YxcFixtures.read("captured/a_features.json");

        YxcGroupResult result = service.link(m(SERVER), clientsOnTheNetwork(10));

        assertValidationFailure(result);
        assertTrue(result.toString(), result.toString().contains("9"));
        assertEquals("only the server was read, nothing changed", 0, network.changingCalls());
    }

    @Test
    public void nineClientsAreAcceptedByADeviceWithClientMaxNine() {
        server.featuresJson = YxcFixtures.read("captured/a_features.json");

        YxcGroupResult result = service.link(m(SERVER), clientsOnTheNetwork(9));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(9, server.clients.size());
    }

    @Test
    public void nineteenClientsAreAcceptedByADeviceWithClientMaxNineteen() {
        server.featuresJson = YxcFixtures.read("captured/c_features.json");

        YxcGroupResult result = service.link(m(SERVER), clientsOnTheNetwork(19));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(19, server.clients.size());
        assertEquals("one server call lists them all", 1, network.count(SERVER, "POST dist/setServerInfo"));
    }

    @Test
    public void twentyClientsAreRefusedByADeviceWithClientMaxNineteen() {
        server.featuresJson = YxcFixtures.read("captured/c_features.json");

        assertValidationFailure(service.link(m(SERVER), clientsOnTheNetwork(20)));
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void withoutADistributionBlockTheOldLimitOfNineApplies() {
        server.clientMax = null;

        assertValidationFailure(service.link(m(SERVER), clientsOnTheNetwork(10)));
        assertEquals(0, network.changingCalls());
        assertTrue(service.link(m(SERVER), clientsOnTheNetwork(9)).isSuccess());
    }

    @Test
    public void aBlockWithoutClientMaxAlsoFallsBack() {
        server.featuresJson = "{\"response_code\":0,\"distribution\":{\"version\":2.0}}";

        assertValidationFailure(service.link(m(SERVER), clientsOnTheNetwork(10)));
    }

    @Test
    public void extendCountsTheClientsTheGroupHasAlready() {
        server.featuresJson = YxcFixtures.read("captured/a_features.json");
        List<YxcGroupMember> first = clientsOnTheNetwork(8);
        assertTrue(service.link(m(SERVER), first).isSuccess());
        network.add("192.0.2.200");
        network.add("192.0.2.201");

        YxcGroupResult result = service.extend(m(SERVER), list2("192.0.2.200", "192.0.2.201"));

        assertValidationFailure(result);
        assertEquals("the group is as it was", 8, server.clients.size());
        assertTrue(service.extend(m(SERVER), list2("192.0.2.200")).isSuccess());
        assertEquals(9, server.clients.size());
    }

    @Test
    public void aClientThatIsInTheGroupAlreadyDoesNotCountTwice() {
        server.featuresJson = YxcFixtures.read("captured/a_features.json");
        List<YxcGroupMember> nine = clientsOnTheNetwork(9);
        assertTrue(service.link(m(SERVER), nine).isSuccess());

        assertTrue("the same nine again", service.extend(m(SERVER), nine).isSuccess());
    }

    @Test
    public void aFailingFeaturesReadFailsAtReadingAndChangesNothing() {
        server.brokenCalls.add("system/getFeatures");

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void aMemberThatDoesNotPlayTheLinkInputGetsTheInputBackButIsNotLinkedAgain() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);
        client1.input = "optical";

        YxcGroupResult result = service.link(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(0, network.count(CLIENT_1, "POST dist/setClientInfo"));
        assertEquals(1, network.count(CLIENT_1, "GET main/setInput"));
        assertEquals(YxcZoneFeatures.GROUP_INPUT_ID, client1.input);
    }

    @Test
    public void aMemberThatPlaysTheLinkInputIsLeftAlone() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        service.link(m(SERVER), list2(CLIENT_1));

        assertEquals(0, network.count(CLIENT_1, "GET main/setInput"));
    }

    private static void assertValidationFailure(YxcGroupResult result) {
        assertEquals(result.toString(), YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.VALIDATE, result.getStep());
    }

    // ---- extend

    @Test
    public void extendAddsToTheExistingGroupAndRestartsTheDistribution() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.extend(m(SERVER), list2(CLIENT_2));

        assertTrue(result.toString(), result.isSuccess());
        assertEquals(OLD_GROUP, result.getGroupId());
        assertInGroup(OLD_GROUP, server, client1, client2);
        assertEquals("one client was in the group before the call", Collections.singletonList(1), server.distributionStarts);
    }

    @Test
    public void extendFailsWhenTheDeviceServesNoGroup() {
        YxcGroupResult result = service.extend(m(SERVER), list2(CLIENT_1));

        assertValidationFailure(result);
        assertEquals(SERVER, result.getDeviceIp());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void extendFailsWhenAnotherZoneServesTheGroup() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        server.serverZone = "zone2";
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.extend(m(SERVER), list2(CLIENT_2));

        assertValidationFailure(result);
        assertEquals(0, network.changingCalls());
    }

    // ---- reduce

    @Test
    public void reduceTakesOneClientOutAndKeepsTheOthers() {
        server.servingGroup(OLD_GROUP, CLIENT_1, CLIENT_2);
        client1.clientOf(OLD_GROUP, SERVER);
        client2.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(client1);
        assertInGroup(OLD_GROUP, server, client2);
        assertEquals("the distribution is restarted for the rest", 1, server.distributionStarts.size());
        int clientLeft = network.calls.indexOf(CLIENT_1 + " POST dist/setClientInfo");
        int serverRemoved = network.calls.indexOf(SERVER + " POST dist/setServerInfo");
        assertTrue("the client leaves first, then the server removes it", clientLeft >= 0 && clientLeft < serverRemoved);
    }

    @Test
    public void reduceToNobodyEndsTheGroup() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(server);
        assertNotLinked(client1);
        assertEquals(1, network.count(SERVER, "GET dist/stopDistribution"));
    }

    @Test
    public void reduceStillRemovesAClientThatCannotBeReached() {
        server.servingGroup(OLD_GROUP, CLIENT_1, CLIENT_2);
        client1.clientOf(OLD_GROUP, SERVER);
        client2.clientOf(OLD_GROUP, SERVER);
        client1.unreachable = true;

        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertInGroup(OLD_GROUP, server, client2);
    }

    @Test
    public void reduceByAClientThatIsNotInTheGroupChangesNothing() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_2));

        assertTrue(result.isSuccess());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void reduceOnADeviceThatServesNothingIsANoOp() {
        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_1));

        assertTrue(result.isSuccess());
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void reduceOnAnUnreachableServerFails() {
        server.unreachable = true;

        YxcGroupResult result = service.reduce(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
        assertEquals(SERVER, result.getDeviceIp());
    }

    // ---- unlink

    @Test
    public void unlinkingAServerEndsTheGroupForAllItsClients() {
        server.servingGroup(OLD_GROUP, CLIENT_1, CLIENT_2);
        client1.clientOf(OLD_GROUP, SERVER);
        client2.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.unlink(m(SERVER), list2(CLIENT_1, CLIENT_2));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(server);
        assertNotLinked(client1);
        assertNotLinked(client2);
        int lastClientLeft = network.calls.indexOf(CLIENT_2 + " POST dist/setClientInfo");
        int stop = network.calls.indexOf(SERVER + " GET dist/stopDistribution");
        int clear = network.calls.indexOf(SERVER + " POST dist/setServerInfo");
        assertTrue("the clients leave, then the server stops, then it clears its role", lastClientLeft < stop && stop < clear);
    }

    @Test
    public void unlinkingAServerWithoutKnowingItsClientsStillEndsTheGroup() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.unlink(m(SERVER), null);

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(server);
    }

    @Test
    public void unlinkingAClientTakesItOutOfItsServerToo() {
        server.servingGroup(OLD_GROUP, CLIENT_1, CLIENT_2);
        client1.clientOf(OLD_GROUP, SERVER);
        client2.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.unlink(m(CLIENT_1), list2(SERVER, CLIENT_2));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(client1);
        assertInGroup(OLD_GROUP, server, client2);
    }

    @Test
    public void unlinkingTheLastClientEndsTheGroup() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.unlink(m(CLIENT_1), list2(SERVER));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(client1);
        assertNotLinked(server);
    }

    @Test
    public void unlinkingAClientWhoseServerIsNotKnownOnlyLetsItLeave() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);

        YxcGroupResult result = service.unlink(m(CLIENT_1), Collections.<YxcGroupMember>emptyList());

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(client1);
        assertEquals("the server was not addressed", 0, network.calls.size() - network.count(CLIENT_1, ""));
    }

    @Test
    public void unlinkingAClientSkipsKnownDevicesThatAreUnreachable() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);
        client2.unreachable = true;

        YxcGroupResult result = service.unlink(m(CLIENT_1), list2(CLIENT_2, SERVER));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(client1);
        assertNotLinked(server);
    }

    @Test
    public void unlinkingADeviceThatIsNotLinkedIsASuccessAndChangesNothing() {
        YxcGroupResult result = service.unlink(m(CLIENT_1), list2(SERVER));

        assertTrue(result.toString(), result.isSuccess());
        assertNull(result.getStep());
        assertEquals(0, network.changingCalls());
        assertEquals("only the device itself was read", 1, network.calls.size());
    }

    @Test
    public void unlinkOfAnUnreachableDeviceFails() {
        client1.unreachable = true;

        YxcGroupResult result = service.unlink(m(CLIENT_1), list2(SERVER));

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.READ_STATE, result.getStep());
    }

    @Test
    public void aServerThatRefusesToStopHasNothingToStopAndIsStillClosed() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);
        server.forcedCodes.put("dist/stopDistribution", 5);

        YxcGroupResult result = service.unlink(m(SERVER), list2(CLIENT_1));

        assertTrue(result.toString(), result.isSuccess());
        assertNotLinked(server);
    }

    @Test
    public void aServerThatBreaksWhileStoppingIsAFailure() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        server.brokenCalls.add("dist/stopDistribution");

        YxcGroupResult result = service.unlink(m(SERVER), null);

        assertEquals(YxcGroupResult.Outcome.FAILED, result.getOutcome());
        assertEquals(YxcGroupResult.Step.CLOSE_GROUP, result.getStep());
    }

    @Test
    public void aServerThatDoesNotLetGoIsATimeout() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        client1.clientOf(OLD_GROUP, SERVER);
        server.ignoreChanges = true;

        YxcGroupResult result = service.unlink(m(SERVER), list2(CLIENT_1));

        assertEquals(YxcGroupResult.Outcome.TIMEOUT, result.getOutcome());
        assertEquals(YxcGroupResult.Step.CLOSE_GROUP, result.getStep());
        assertEquals(2, network.count(SERVER, "GET dist/stopDistribution"));
    }

    // ---- rename

    @Test
    public void renameSetsTheNameOfTheServedGroup() {
        server.servingGroup(OLD_GROUP, CLIENT_1);

        YxcGroupResult result = service.rename(m(SERVER), "Living room + kitchen");

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("Living room + kitchen", server.groupName);
        assertEquals(OLD_GROUP, result.getGroupId());
    }

    @Test
    public void renameToTheEmptyNameSelectsTheDefault() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        server.groupName = "old";

        YxcGroupResult result = service.rename(m(SERVER), "");

        assertTrue(result.toString(), result.isSuccess());
        assertEquals("", server.groupName);
    }

    @Test
    public void renameOnADeviceThatServesNothingFails() {
        YxcGroupResult result = service.rename(m(SERVER), "x");

        assertValidationFailure(result);
        assertEquals(0, network.changingCalls());
    }

    @Test
    public void aNameOfMoreThan128BytesIsRejected() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            name.append('ü');
        }

        assertValidationFailure(service.rename(m(SERVER), name.toString()));
        assertValidationFailure(service.rename(m(SERVER), null));
        assertEquals(0, network.calls.size());
    }

    @Test
    public void renameThatTheDeviceDoesNotShowIsATimeout() {
        server.servingGroup(OLD_GROUP, CLIENT_1);
        server.ignoreChanges = true;

        YxcGroupResult result = service.rename(m(SERVER), "New");

        assertEquals(YxcGroupResult.Outcome.TIMEOUT, result.getOutcome());
        assertEquals(YxcGroupResult.Step.RENAME, result.getStep());
    }

    // ---- construction

    @Test
    public void theServiceNeedsItsCollaborators() {
        try {
            new YxcGroupService(null, new Random());
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            new YxcGroupService(network.sleeper, null);
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void aMemberNeedsAnAddressAZoneAndAClient() {
        YxcClient client = new YxcClient(new RecordingTransport());
        for (Object[] bad : new Object[][]{{null, "main", client}, {"", "main", client}, {"192.0.2.1", null, client},
                {"192.0.2.1", "", client}, {"192.0.2.1", "main", null}}) {
            try {
                new YxcGroupMember((String) bad[0], (String) bad[1], (YxcClient) bad[2]);
                org.junit.Assert.fail();
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
