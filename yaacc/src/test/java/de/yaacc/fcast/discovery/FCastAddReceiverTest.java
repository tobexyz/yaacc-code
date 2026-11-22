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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.fcast.discovery.FCastAddReceiver.Outcome;

/**
 * Tasks 5.4, 5.5 and 5.7: what the "Add receiver" input does with an IP address, a host name, the name of a device and a pasted
 * connection URL, in which order, and what the failure says it tried. Pure: fake directory, lookup, reachability and clock.
 */
public class FCastAddReceiverTest {

    private final FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
    private final List<String> calls = new ArrayList<>();
    private final Set<String> reachable = new HashSet<>();
    private final java.util.Map<String, String> system = new java.util.HashMap<>();
    private final List<Long> pauses = new ArrayList<>();
    private boolean discoveryRunning = true;
    private Runnable duringPause = () -> {
    };
    private FCastStoredReceivers.AddResult storeResult = FCastStoredReceivers.AddResult.ADDED;
    private final List<String> stored = new ArrayList<>();

    private final FCastAddReceiver add = new FCastAddReceiver(new FCastAddReceiver.Environment() {
        @Override
        public List<FCastDirectory.Entry> receivers() {
            calls.add("directory");
            return directory.snapshot();
        }

        @Override
        public boolean answers(FCastEndpoint endpoint) {
            calls.add("probe " + endpoint);
            return reachable.contains(endpoint.toString());
        }

        @Override
        public InetAddress lookup(String host) throws IOException {
            calls.add("lookup " + host);
            String ip = system.get(host);
            if (ip == null) {
                throw new IOException("unknown");
            }
            return InetAddress.getByAddress(parse(ip));
        }

        @Override
        public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
            calls.add("store " + endpoint + " " + name);
            if (storeResult == FCastStoredReceivers.AddResult.ADDED) {
                stored.add(endpoint + "|" + name);
            }
            return storeResult;
        }

        @Override
        public boolean isDiscoveryRunning() {
            return discoveryRunning;
        }

        @Override
        public void pause(long millis) {
            pauses.add(millis);
            duringPause.run();
        }
    });

    private static byte[] parse(String ip) {
        String[] parts = ip.split("\\.");
        byte[] raw = new byte[4];
        for (int i = 0; i < 4; i++) {
            raw[i] = (byte) Integer.parseInt(parts[i]);
        }
        return raw;
    }

    private void announce(String name, String ip) throws Exception {
        directory.serviceFound("_fcast._tcp|" + name, name, InetAddress.getByAddress(parse(ip)), 46899, null);
    }

    private static String link(String json) {
        return FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- an address

    @Test
    public void anIpAddressIsAddedAtOnceWithoutTestingIt() {
        FCastAddReceiver.Result result = add.add("192.168.0.239");

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals(Collections.singletonList("192.168.0.239:46899|"), stored);
        assertFalse("a receiver that is switched off can be added", calls.stream().anyMatch(c -> c.startsWith("probe")));
        assertEquals("192.168.0.239:46899", result.getEndpoint().toString());
    }

    @Test
    public void anIpAddressWithAPortIsAddedWithThatPort() {
        assertEquals(Outcome.ADDED, add.add("  10.0.0.5:4711 ").getOutcome());
        assertEquals("10.0.0.5:4711|", stored.get(0));
    }

    @Test
    public void emptyTextAndNonsenseAreRefused() {
        assertEquals(Outcome.EMPTY, add.add(null).getOutcome());
        assertEquals(Outcome.EMPTY, add.add("   ").getOutcome());
        assertEquals(Outcome.INVALID, add.add("http://192.168.0.2/x").getOutcome());
        assertEquals(Outcome.INVALID, add.add("192.168.0.2:99999").getOutcome());
        assertEquals(Outcome.INVALID, add.add("a\nb").getOutcome());
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 5_000) {
            huge.append("a");
        }
        assertEquals(Outcome.INVALID, add.add(huge.toString()).getOutcome());
        assertTrue(stored.isEmpty());
    }

    @Test
    public void theStoreSaysFullOrAlreadyListed() {
        storeResult = FCastStoredReceivers.AddResult.FULL;
        assertEquals(Outcome.FULL, add.add("192.168.0.2").getOutcome());
        storeResult = FCastStoredReceivers.AddResult.DUPLICATE;
        assertEquals(Outcome.ALREADY_LISTED, add.add("192.168.0.2").getOutcome());
        storeResult = FCastStoredReceivers.AddResult.INVALID;
        assertEquals(Outcome.INVALID, add.add("192.168.0.2").getOutcome());
    }

    // ---- a connection URL

    @Test
    public void aConnectionUrlIsAddedWithTheFirstAddressThatAnswers() {
        reachable.add("10.0.0.7:46899");
        String text = link("{\"name\":\"FCast-pc\",\"addresses\":[\"192.168.0.239\",\"10.0.0.7\"],\"services\":[{\"port\":46899,\"type\":0}]}");

        FCastAddReceiver.Result result = add.add(text);

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals(Arrays.asList("probe 192.168.0.239:46899", "probe 10.0.0.7:46899", "store 10.0.0.7:46899 FCast-pc"), calls);
        assertEquals("FCast-pc", result.getName());
    }

    @Test
    public void theAddressesOfALinkAreAskedForAtOnceAndTheFirstOfTheLinkThatAnswersWins() {
        reachable.add("192.168.0.239:46899");
        reachable.add("10.0.0.7:46899");
        String text = link("{\"name\":\"FCast-pc\",\"addresses\":[\"172.17.0.1\",\"172.18.0.1\",\"10.0.0.7\",\"192.168.0.239\"],\"services\":[{\"port\":46899,\"type\":0}]}");
        List<List<FCastEndpoint>> asked = new ArrayList<>();
        FCastAddReceiver parallel = new FCastAddReceiver(new FCastAddReceiver.Environment() {
            @Override
            public List<FCastDirectory.Entry> receivers() {
                return new ArrayList<>();
            }

            @Override
            public boolean answers(FCastEndpoint endpoint) {
                throw new AssertionError("one at a time must not be used");
            }

            @Override
            public List<Boolean> answersAll(List<FCastEndpoint> endpoints) {
                asked.add(endpoints);
                List<Boolean> results = new ArrayList<>();
                for (FCastEndpoint endpoint : endpoints) {
                    results.add(reachable.contains(endpoint.toString()));
                }
                return results;
            }

            @Override
            public InetAddress lookup(String host) throws IOException {
                throw new IOException("unused");
            }

            @Override
            public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
                stored.add(endpoint + "|" + name);
                return FCastStoredReceivers.AddResult.ADDED;
            }

            @Override
            public boolean isDiscoveryRunning() {
                return false;
            }

            @Override
            public void pause(long millis) {
            }
        });

        assertEquals(Outcome.ADDED, parallel.add(text).getOutcome());

        assertEquals("one question for all four addresses", 1, asked.size());
        assertEquals(4, asked.get(0).size());
        assertEquals("the first address of the link that answers, not the fastest", "10.0.0.7:46899|FCast-pc", stored.get(0));
    }

    /** An environment that records which groups of addresses were asked for, with the phone on 192.168.0.0/24. */
    private FCastAddReceiver nearFirst(List<List<String>> asked) {
        return new FCastAddReceiver(new FCastAddReceiver.Environment() {
            @Override
            public List<FCastDirectory.Entry> receivers() {
                return new ArrayList<>();
            }

            @Override
            public boolean answers(FCastEndpoint endpoint) {
                throw new AssertionError("answersAll is used");
            }

            @Override
            public List<FCastSubnet> localSubnets() {
                try {
                    return Collections.singletonList(new FCastSubnet(InetAddress.getByAddress(parse("192.168.0.57")), 24));
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public List<Boolean> answersAll(List<FCastEndpoint> endpoints) {
                List<String> group = new ArrayList<>();
                List<Boolean> results = new ArrayList<>();
                for (FCastEndpoint endpoint : endpoints) {
                    group.add(endpoint.toString());
                    results.add(reachable.contains(endpoint.toString()));
                }
                asked.add(group);
                return results;
            }

            @Override
            public InetAddress lookup(String host) throws IOException {
                throw new IOException("unused");
            }

            @Override
            public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
                stored.add(endpoint + "|" + name);
                return FCastStoredReceivers.AddResult.ADDED;
            }

            @Override
            public boolean isDiscoveryRunning() {
                return false;
            }

            @Override
            public void pause(long millis) {
            }
        });
    }

    private static String sixAddressLink() {
        return link("{\"name\":\"FCast-pc\",\"addresses\":[\"172.19.0.1\",\"192.168.122.1\",\"172.20.0.1\",\"172.18.0.1\",\"172.17.0.1\",\"192.168.0.239\"],"
                + "\"services\":[{\"port\":46899,\"type\":0}]}");
    }

    @Test
    public void theAddressesOnTheNetworkOfThePhoneAreTriedFirstAndTheOthersOnlyIfNoneAnswers() {
        reachable.add("192.168.0.239:46899");
        reachable.add("172.19.0.1:46899"); // would answer too, but is not asked
        List<List<String>> asked = new ArrayList<>();

        FCastAddReceiver.Result result = nearFirst(asked).add(sixAddressLink());

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals("only the address of the phone's network was asked", Collections.singletonList(Collections.singletonList("192.168.0.239:46899")), asked);
        assertEquals("192.168.0.239:46899|FCast-pc", stored.get(0));
    }

    @Test
    public void whenNoNearAddressAnswersTheOthersAreTriedInTheOrderOfTheLink() {
        reachable.add("172.20.0.1:46899");
        reachable.add("172.18.0.1:46899");
        List<List<String>> asked = new ArrayList<>();

        FCastAddReceiver.Result result = nearFirst(asked).add(sixAddressLink());

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals(2, asked.size());
        assertEquals(Collections.singletonList("192.168.0.239:46899"), asked.get(0));
        assertEquals(Arrays.asList("172.19.0.1:46899", "192.168.122.1:46899", "172.20.0.1:46899", "172.18.0.1:46899", "172.17.0.1:46899"), asked.get(1));
        assertEquals("the first of the link among those that answer", "172.20.0.1:46899|FCast-pc", stored.get(0));
    }

    @Test
    public void whenNothingAnswersAllSixWereTried() {
        List<List<String>> asked = new ArrayList<>();

        FCastAddReceiver.Result result = nearFirst(asked).add(sixAddressLink());

        assertEquals(Outcome.UNREACHABLE, result.getOutcome());
        assertEquals(6, result.getTriedAddresses());
        assertEquals(2, asked.size());
    }

    @Test
    public void aConnectionUrlWhoseAddressesAllFailIsNotAddedAndSaysSo() {
        String text = link("{\"name\":\"FCast-pc\",\"addresses\":[\"192.168.0.239\",\"10.0.0.7\"],\"services\":[{\"port\":46899,\"type\":0}]}");

        FCastAddReceiver.Result result = add.add(text);

        assertEquals(Outcome.UNREACHABLE, result.getOutcome());
        assertEquals(2, result.getTriedAddresses());
        assertTrue(stored.isEmpty());
    }

    @Test
    public void aBadConnectionUrlIsRefusedWithItsProblem() {
        FCastAddReceiver.Result result = add.add(FCastConnectionUrl.PREFIX + "!!!");

        assertEquals(Outcome.BAD_LINK, result.getOutcome());
        assertEquals(FCastConnectionUrl.Problem.BAD_ENCODING, result.getLinkProblem());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void aConnectionUrlOfAReceiverThatIsListedAlready() {
        reachable.add("192.168.0.239:46899");
        storeResult = FCastStoredReceivers.AddResult.DUPLICATE;
        String text = link("{\"addresses\":[\"192.168.0.239\"],\"services\":[{\"port\":46899,\"type\":0}]}");

        assertEquals(Outcome.ALREADY_LISTED, add.add(text).getOutcome());
    }

    // ---- the name of a device

    @Test
    public void aNameThatMatchesAReceiverAlreadyFoundIsAddedWithItsAddress() throws Exception {
        announce("FCast-ntbktobe-t14", "192.168.0.239");
        announce("Living room", "192.168.0.50");

        FCastAddReceiver.Result result = add.add("fcast-NTBKTOBE-t14");

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals("192.168.0.239:46899|FCast-ntbktobe-t14", stored.get(0));
        assertFalse("no lookup when the name was found", calls.stream().anyMatch(c -> c.startsWith("lookup")));
    }

    @Test
    public void theComputersHostNameMatchesTheDefaultInstanceName() throws Exception {
        announce("FCast-ntbktobe-t14", "192.168.0.239");

        for (String typed : new String[]{"ntbktobe-t14", "NTBKTOBE-T14.local", "ntbktobe-t14.local."}) {
            stored.clear();
            assertEquals(typed, Outcome.ADDED, add.add(typed).getOutcome());
            assertEquals("192.168.0.239:46899|FCast-ntbktobe-t14", stored.get(0));
        }
    }

    @Test
    public void aNameWithSpacesFromTheReceiversSettingsMatchesToo() throws Exception {
        announce("Living room TV", "192.168.0.50");

        assertEquals(Outcome.ADDED, add.add("living ROOM tv").getOutcome());
        assertEquals("192.168.0.50:46899|Living room TV", stored.get(0));
    }

    @Test
    public void twoReceiversWithTheSameNameAskWhichOne() throws Exception {
        announce("FCast-pc", "192.168.0.10");
        directory.serviceFound("_fcast._tcp|second", "FCast-pc", InetAddress.getByAddress(parse("192.168.0.11")), 46899, null);

        FCastAddReceiver.Result result = add.add("FCast-pc");

        assertEquals(Outcome.AMBIGUOUS, result.getOutcome());
        assertEquals(Arrays.asList("192.168.0.10:46899", "192.168.0.11:46899"), result.getCandidates());
        assertTrue(stored.isEmpty());
    }

    @Test
    public void aSystemNameIsResolvedWhenNoReceiverOfThatNameWasFound() throws Exception {
        system.put("ntbktobe-t14", "192.168.0.239");

        FCastAddReceiver.Result result = add.add("ntbktobe-t14");

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals("192.168.0.239:46899|ntbktobe-t14", stored.get(0));
        assertEquals(Arrays.asList("directory", "lookup ntbktobe-t14", "store 192.168.0.239:46899 ntbktobe-t14"), calls);
    }

    @Test
    public void aHostNameWithAPortKeepsThePort() throws Exception {
        system.put("pc.lan", "192.168.0.239");

        assertEquals(Outcome.ADDED, add.add("pc.lan:4711").getOutcome());
        assertEquals("192.168.0.239:4711|pc.lan", stored.get(0));
    }

    @Test
    public void aNameThatResolvesToAnAddressOutsideTheLocalNetworkIsRefused() throws Exception {
        system.put("evil.example", "8.8.8.8");

        FCastAddReceiver.Result result = add.add("evil.example");

        assertEquals(Outcome.NAME_NOT_FOUND, result.getOutcome());
        assertTrue(stored.isEmpty());
    }

    @Test
    public void aReceiverThatAnnouncesItselfWhileWeWaitIsFound() throws Exception {
        duringPause = () -> {
            try {
                announce("FCast-late", "192.168.0.77");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        FCastAddReceiver.Result result = add.add("FCast-late");

        assertEquals(Outcome.ADDED, result.getOutcome());
        assertEquals("192.168.0.77:46899|FCast-late", stored.get(0));
        assertEquals("one wait was enough", 1, pauses.size());
    }

    @Test
    public void aNameThatIsNowhereSaysWhatWasTried() {
        FCastAddReceiver.Result result = add.add("nowhere");

        assertEquals(Outcome.NAME_NOT_FOUND, result.getOutcome());
        assertTrue(result.isTriedSystemLookup());
        assertTrue(result.isTriedSearch());
        assertEquals("the search ran in rounds", FCastAddReceiver.SEARCH_ROUNDS, pauses.size());
        assertTrue(stored.isEmpty());
    }

    @Test
    public void withDiscoveryOffTheSearchIsSkippedAndTheFailureSaysSo() {
        discoveryRunning = false;

        FCastAddReceiver.Result result = add.add("nowhere");

        assertEquals(Outcome.NAME_NOT_FOUND, result.getOutcome());
        assertFalse(result.isTriedSearch());
        assertTrue(pauses.isEmpty());
    }

    @Test
    public void aNameWithSpacesIsNeverGivenToTheSystemLookup() {
        FCastAddReceiver.Result result = add.add("Living room TV");

        assertEquals(Outcome.NAME_NOT_FOUND, result.getOutcome());
        assertFalse(result.isTriedSystemLookup());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("lookup")));
    }

    @Test
    public void aNameThatMatchesAReceiverThatIsListedAlreadyIsSaidSo() throws Exception {
        announce("FCast-pc", "192.168.0.10");
        storeResult = FCastStoredReceivers.AddResult.DUPLICATE;

        assertEquals(Outcome.ALREADY_LISTED, add.add("FCast-pc").getOutcome());
    }

    @Test
    public void aMatchIsNeverMadeByAPartOfTheName() throws Exception {
        announce("FCast-pc-upstairs", "192.168.0.10");

        FCastAddReceiver.Result result = add.add("pc");

        assertEquals(Outcome.NAME_NOT_FOUND, result.getOutcome());
        assertNull(result.getEndpoint());
    }

    @Test
    public void anIpv6LinkLocalAddressIsRefusedWhetherTypedOrFoundByName() throws Exception {
        assertEquals(Outcome.LINK_LOCAL, add.add("fe80::1").getOutcome());
        assertEquals(Outcome.LINK_LOCAL, add.add("[fe80::1]:4711").getOutcome());
        assertTrue(stored.isEmpty());

        // a name that the system resolves to a link-local address is not found: the scope would be lost
        FCastAddReceiver byName = new FCastAddReceiver(new FCastAddReceiver.Environment() {
            @Override
            public List<FCastDirectory.Entry> receivers() {
                return new ArrayList<>();
            }

            @Override
            public boolean answers(FCastEndpoint endpoint) {
                return false;
            }

            @Override
            public InetAddress lookup(String host) {
                try {
                    return InetAddress.getByName("fe80:0:0:0:0:0:0:1");
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public FCastStoredReceivers.AddResult store(FCastEndpoint endpoint, String name) {
                stored.add(endpoint.toString());
                return FCastStoredReceivers.AddResult.ADDED;
            }

            @Override
            public boolean isDiscoveryRunning() {
                return false;
            }

            @Override
            public void pause(long millis) {
            }
        });

        assertEquals(Outcome.NAME_NOT_FOUND, byName.add("mybox.local").getOutcome());
        assertTrue(stored.isEmpty());
    }
}
