/*
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.client.SnapcastClientSpike;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * The servers the client settings offer to choose from (task 11.7): the known servers (typed in, found, remembered) and the
 * one used last, each once, with cleaned names, in a stable order. The text a choice puts into the field is what the
 * client's own check accepts, and its target is the address the user chose. Plain JVM.
 */
public class SnapcastServerChoicesTest {

    private static SnapcastServerListModel.Row row(String endpoint, String name, SnapcastServerListModel.Kind kind) {
        SnapcastEndpoint parsed = SnapcastEndpoint.parse(endpoint);
        assertNotNull(endpoint, parsed);
        return new SnapcastServerListModel.Row(parsed.toKey(), parsed.toString(), name, kind, kind == SnapcastServerListModel.Kind.DISCOVERED);
    }

    private static List<SnapcastServerListModel.Row> rows(SnapcastServerListModel.Row... rows) {
        return new ArrayList<>(Arrays.asList(rows));
    }

    @Test
    public void nothingKnownMeansNoChoices() {
        assertTrue(SnapcastServerChoices.of(Collections.<SnapcastServerListModel.Row>emptyList(), "").isEmpty());
        assertTrue(SnapcastServerChoices.of(null, null).isEmpty());
    }

    @Test
    public void aKnownServerIsOfferedByItsHostAndTheStreamPort() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertEquals(1, choices.size());
        // the client connects to the stream port; the control port of the list is not its port
        assertEquals("192.168.1.5", choices.get(0).hostText());
        assertEquals("192.168.1.5 (Living room)", choices.get(0).label());
        assertEquals(SnapcastServerChoices.Source.FOUND, choices.get(0).source());
    }

    @Test
    public void everyChoiceIsAcceptedByTheClientsOwnCheck() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "A", SnapcastServerListModel.Kind.DISCOVERED),
                row("[fe80::1]:1705", "B", SnapcastServerListModel.Kind.MANUAL),
                row("snap.example.org:1705", "", SnapcastServerListModel.Kind.MANUAL),
                row("10.0.0.7:1705", "", SnapcastServerListModel.Kind.REMEMBERED)), "10.0.0.9:1800");

        assertEquals(5, choices.size());
        for (SnapcastServerChoices.Choice choice : choices) {
            SnapcastClientSpike.Request request = SnapcastClientSpike.check(choice.hostText(), "0");
            assertEquals(choice.hostText(), SnapcastClientSpike.Outcome.OK, request.getOutcome());
        }
    }

    @Test
    public void anIpv6AddressIsBracketed() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("[fd00::5]:1705", "", SnapcastServerListModel.Kind.MANUAL)), "");
        assertEquals("[fd00:0:0:0:0:0:0:5]", choices.get(0).hostText()); // the canonical form of the key
    }

    @Test
    public void twoControlPortsOnOneHostAreOneChoice() {
        // same host, same target (host:1704): offering both would be two rows that connect to the same place
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "A", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.5:1805", "", SnapcastServerListModel.Kind.MANUAL)), "");

        assertEquals(1, choices.size());
    }

    @Test
    public void theLastUsedServerIsFirstAndMergedWithTheSameKnownOne() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(rows(
                row("10.0.0.1:1705", "Attic", SnapcastServerListModel.Kind.MANUAL),
                row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.DISCOVERED)), "192.168.1.5");

        assertEquals(2, choices.size());
        assertEquals("192.168.1.5", choices.get(0).hostText());
        assertTrue(choices.get(0).isLastUsed());
        assertEquals("192.168.1.5 (Living room)", choices.get(0).label());
        assertFalse(choices.get(1).isLastUsed());
    }

    @Test
    public void aLastUsedServerThatIsNotKnownIsOfferedToo() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("192.168.1.5:1705", "", SnapcastServerListModel.Kind.DISCOVERED)), "10.9.9.9:1800");

        assertEquals(2, choices.size());
        assertEquals("10.9.9.9:1800", choices.get(0).hostText());
        assertEquals(SnapcastServerChoices.Source.LAST_USED, choices.get(0).source());
        assertEquals("10.9.9.9:1800", choices.get(0).label());
    }

    @Test
    public void aLastUsedTextThatIsNotAnAddressIsLeftOut() {
        for (String bad : Arrays.asList("tcp://x", "user:pw@host", "a b", "http://1.2.3.4", "host:0", "host:99999", "   ")) {
            List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                    rows(row("192.168.1.5:1705", "", SnapcastServerListModel.Kind.DISCOVERED)), bad);
            assertEquals(bad, 1, choices.size());
            assertFalse(bad, choices.get(0).isLastUsed());
        }
    }

    @Test
    public void typedInComeBeforeFoundAndRememberedThenByAddress() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(rows(
                row("192.168.1.9:1705", "", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.2:1705", "", SnapcastServerListModel.Kind.REMEMBERED),
                row("192.168.1.8:1705", "", SnapcastServerListModel.Kind.MANUAL),
                row("192.168.1.1:1705", "", SnapcastServerListModel.Kind.MANUAL)), "");

        assertEquals(Arrays.asList("192.168.1.1", "192.168.1.8", "192.168.1.2", "192.168.1.9"), hosts(choices));
    }

    @Test
    public void theOrderDoesNotDependOnTheOrderOfTheInput() {
        List<SnapcastServerListModel.Row> input = rows(
                row("192.168.1.9:1705", "z", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.2:1705", "y", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.8:1705", "x", SnapcastServerListModel.Kind.MANUAL));
        List<String> expected = hosts(SnapcastServerChoices.of(input, ""));
        Collections.reverse(input);

        assertEquals(expected, hosts(SnapcastServerChoices.of(input, "")));
    }

    @Test
    public void aNameFromTheNetworkIsCleanedAndCutInTheLabel() {
        StringBuilder name = new StringBuilder("Evil\n‮\u0007");
        for (int i = 0; i < 300; i++) {
            name.append('x');
        }
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("192.168.1.5:1705", name.toString(), SnapcastServerListModel.Kind.DISCOVERED)), "");

        String label = choices.get(0).label();
        assertFalse(label.contains("\n"));
        assertFalse(label.contains("‮"));
        assertFalse(label.contains("\u0007"));
        assertTrue(label, label.length() <= SnapcastServerListModel.MAX_NAME + "192.168.1.5 ()".length());
        assertTrue(label.startsWith("192.168.1.5 ("));
        assertTrue(label.endsWith(")"));
    }

    @Test
    public void theNameNeverChangesWhereTheConnectionGoes() {
        // a name that looks like an address must not become the target: the target is the endpoint of the row
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("192.168.1.5:1705", "8.8.8.8:1704", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertEquals("192.168.1.5", choices.get(0).hostText());
    }

    @Test
    public void atMostSeventeenChoices() {
        List<SnapcastServerListModel.Row> many = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            many.add(row("10.0.0." + i + ":1705", "", SnapcastServerListModel.Kind.DISCOVERED));
        }
        assertEquals(SnapcastServerChoices.MAX_CHOICES, SnapcastServerChoices.of(many, "10.9.9.9").size());
        assertTrue(SnapcastServerChoices.of(many, "10.9.9.9").get(0).isLastUsed());
    }

    @Test
    public void aChoiceHasNoSecretOrCredential() {
        for (SnapcastServerChoices.Choice choice : SnapcastServerChoices.of(
                rows(row("192.168.1.5:1705", "n", SnapcastServerListModel.Kind.MANUAL)), "192.168.1.6")) {
            assertNull(choice.toString().indexOf('@') >= 0 ? choice : null);
            assertFalse(choice.hostText().contains("@"));
        }
    }

    private static List<String> hosts(List<SnapcastServerChoices.Choice> choices) {
        List<String> hosts = new ArrayList<>();
        for (SnapcastServerChoices.Choice choice : choices) {
            hosts.add(choice.hostText());
        }
        return hosts;
    }

    // ---- Group 11Cb S4: a link-local IPv6 address loses its scope in the text, so a found server there is not offered

    @Test
    public void aFoundOrRememberedServerAtALinkLocalIpv6AddressIsNotOffered() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(rows(
                row("[fe80::1]:1705", "Living", SnapcastServerListModel.Kind.DISCOVERED),
                row("[fe80::2]:1705", "Attic", SnapcastServerListModel.Kind.REMEMBERED),
                row("192.168.1.5:1705", "Kitchen", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertEquals(Arrays.asList("192.168.1.5"), hosts(choices));
    }

    @Test
    public void aTypedInLinkLocalAddressStaysTheUsersChoice() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("[fe80::1]:1705", "", SnapcastServerListModel.Kind.MANUAL)), "");

        assertEquals(1, choices.size());
    }

    @Test
    public void aUniqueLocalIpv6AddressOfAFoundServerIsOffered() {
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("[fd00::5]:1705", "", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertEquals(1, choices.size());
    }

    // ---- Group 11D, S67: the host comes first, a name can not imitate an address; S12: 169.254 is still offered

    @Test
    public void theHostComesFirstInTheLabelSoANameCanNotImitateAnAddress() {
        // the server at 10.0.0.9 calls itself like another address: the label must start with the real host
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("10.0.0.9:1705", "Living room (192.168.1.5)", SnapcastServerListModel.Kind.DISCOVERED)), "");

        String label = choices.get(0).label();
        assertTrue(label, label.startsWith("10.0.0.9 ("));
        assertEquals("10.0.0.9 (Living room (192.168.1.5))", label);
    }

    @Test
    public void aServerWithoutANameShowsTheHostAlone() {
        assertEquals("10.0.0.9", SnapcastServerChoices.of(
                rows(row("10.0.0.9:1705", "", SnapcastServerListModel.Kind.MANUAL)), "").get(0).label());
    }

    @Test
    public void aFoundIpv4LinkLocalServerIsStillOffered() {
        // only a link-local IPv6 address loses its scope id; 169.254.x.x has none and must stay a choice
        List<SnapcastServerChoices.Choice> choices = SnapcastServerChoices.of(
                rows(row("169.254.1.5:1705", "Garage", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertEquals(Arrays.asList("169.254.1.5"), hosts(choices));
    }

    @Test
    public void nothingTypedOrUsedBeforeMeansNoPrefillFromAFoundServer() {
        List<SnapcastServerChoices.Choice> found = SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.DISCOVERED)), "");

        assertNull(SnapcastServerChoices.prefill(found));
        assertNull(SnapcastServerChoices.prefill(new ArrayList<SnapcastServerChoices.Choice>()));
        assertNull(SnapcastServerChoices.prefill(null));
    }

    @Test
    public void theLastUsedTypedAndRememberedServersMayBePrefilled() {
        assertEquals("10.9.9.9:1800", SnapcastServerChoices.prefill(SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "", SnapcastServerListModel.Kind.DISCOVERED)), "10.9.9.9:1800")));
        assertEquals("192.168.1.8", SnapcastServerChoices.prefill(SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "", SnapcastServerListModel.Kind.DISCOVERED),
                row("192.168.1.8:1705", "", SnapcastServerListModel.Kind.MANUAL)), "")));
        assertEquals("192.168.1.2", SnapcastServerChoices.prefill(SnapcastServerChoices.of(rows(
                row("192.168.1.2:1705", "", SnapcastServerListModel.Kind.REMEMBERED),
                row("192.168.1.5:1705", "", SnapcastServerListModel.Kind.DISCOVERED)), "")));
    }

    @Test
    public void aFoundServerThatTheUserUsedBeforeMayBePrefilled() {
        // used last: the user chose it once, so it is not the network's choice
        assertEquals("192.168.1.5", SnapcastServerChoices.prefill(SnapcastServerChoices.of(rows(
                row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.DISCOVERED)), "192.168.1.5")));
    }
}
