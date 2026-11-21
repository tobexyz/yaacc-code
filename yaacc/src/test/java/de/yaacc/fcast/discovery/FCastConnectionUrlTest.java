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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Task 5.5: the connection URL that the receivers show as a QR code, {@code fcast://r/<base64url JSON>}. Everything in it is
 * untrusted (any app or web page can hand the app such a link): limits, only IP literals of the local network, only the TCP
 * service, a cleaned name.
 */
public class FCastConnectionUrlTest {

    private static final String FINGERPRINT = "rLMhA2vz0TqRRnVhzGLvY0nCkDZG9lXzaLKw7pjZ7XA=";

    private static String link(String json) {
        return FCastConnectionUrl.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String paddedLink(String json) {
        return FCastConnectionUrl.PREFIX + Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String name, String[] addresses, String services, String txt) throws Exception {
        JSONObject object = new JSONObject();
        if (name != null) {
            object.put("name", name);
        }
        JSONArray list = new JSONArray();
        for (String address : addresses) {
            list.put(address);
        }
        object.put("addresses", list);
        object.put("services", new JSONArray(services));
        if (txt != null) {
            object.put("txt", new JSONObject(txt));
        }
        return object.toString();
    }

    private static final String TCP = "[{\"port\":46899,\"type\":0}]";

    private static FCastConnectionUrl.Parsed ok(String link) {
        FCastConnectionUrl.Result result = FCastConnectionUrl.parse(link);
        assertEquals(FCastConnectionUrl.Problem.NONE, result.getProblem());
        assertNotNull(result.getParsed());
        return result.getParsed();
    }

    private static FCastConnectionUrl.Problem problem(String link) {
        FCastConnectionUrl.Result result = FCastConnectionUrl.parse(link);
        assertNull(result.getParsed());
        return result.getProblem();
    }

    // ---- the forms of the receivers

    @Test
    public void theLinkOfADesktopReceiverGivesItsNameItsAddressesInOrderAndItsHints() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("FCast-ntbktobe-t14", new String[]{"192.168.0.239", "10.0.0.7"}, TCP,
                "{\"v\":\"4\",\"fp\":\"" + FINGERPRINT + "\"}")));

        assertEquals("FCast-ntbktobe-t14", parsed.getName());
        assertEquals(2, parsed.getEndpoints().size());
        assertEquals("192.168.0.239:46899", parsed.getEndpoints().get(0).toString());
        assertEquals("10.0.0.7:46899", parsed.getEndpoints().get(1).toString());
        assertEquals(Integer.valueOf(4), parsed.getHints().getProtocolVersion());
        assertEquals(FINGERPRINT, parsed.getHints().getFingerprint());
    }

    @Test
    public void theLinkOfAnAndroidReceiverWithoutTxtWorks() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("Samsung-SM-T510", new String[]{"192.168.1.20"}, "[{\"port\":46899,\"type\":0}]", null)));

        assertEquals("Samsung-SM-T510", parsed.getName());
        assertEquals("192.168.1.20:46899", parsed.getEndpoints().get(0).toString());
        assertEquals(FCastServiceTxt.EMPTY, parsed.getHints());
    }

    @Test
    public void paddedAndUnpaddedBase64AreBothAccepted() throws Exception {
        for (String name : new String[]{"a", "ab", "abc", "abcd"}) {
            String text = json(name, new String[]{"192.168.0.2"}, TCP, null);
            assertEquals(name, ok(link(text)).getName());
            assertEquals(name, ok(paddedLink(text)).getName());
        }
    }

    @Test
    public void anotherPortInTheServiceIsUsed() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("x", new String[]{"192.168.0.2"}, "[{\"port\":4711,\"type\":0}]", null)));

        assertEquals("192.168.0.2:4711", parsed.getEndpoints().get(0).toString());
    }

    @Test
    public void onlyTheTcpServiceCountsAndTheFirstOneWins() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("x", new String[]{"192.168.0.2"},
                "[{\"port\":9999,\"type\":1},{\"port\":4711,\"type\":0},{\"port\":4712,\"type\":0}]", null)));

        assertEquals(1, parsed.getEndpoints().size());
        assertEquals("192.168.0.2:4711", parsed.getEndpoints().get(0).toString());
    }

    @Test
    public void anIpv6UniqueLocalAddressIsAcceptedAndWrittenInBrackets() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("x", new String[]{"fd12:3456:789a::5"}, TCP, null)));

        assertEquals("[fd12:3456:789a:0:0:0:0:5]:46899", parsed.getEndpoints().get(0).toString());
    }

    @Test
    public void theLinkMayHaveWhiteSpaceAroundIt() throws Exception {
        ok("  " + link(json("x", new String[]{"192.168.0.2"}, TCP, null)) + "\n");
    }

    // ---- what is left out

    @Test
    public void addressesThatAreNotOfTheLocalNetworkOrNotAddressesAreLeftOutAndTheRestKept() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("x", new String[]{"8.8.8.8", "192.168.0.2", "example.com", "127.0.0.1",
                "0.0.0.0", "224.0.0.1", "fe80::1", "192.168.0.3:99", "100.64.0.1", "192.168.0.2", "http://192.168.0.9", "", "169.254.1.1"}, TCP, null)));

        assertEquals("only the private and link-local IPv4 addresses, once each, in order", 2, parsed.getEndpoints().size());
        assertEquals("192.168.0.2:46899", parsed.getEndpoints().get(0).toString());
        assertEquals("169.254.1.1:46899", parsed.getEndpoints().get(1).toString());
    }

    @Test
    public void theNameIsCleanedAndCut() throws Exception {
        StringBuilder longName = new StringBuilder("N\u0007a\u202eme");
        while (longName.length() < 500) {
            longName.append('x');
        }
        FCastConnectionUrl.Parsed parsed = ok(link(json(longName.toString(), new String[]{"192.168.0.2"}, TCP, null)));

        assertTrue(parsed.getName().startsWith("Name"));
        assertEquals(de.yaacc.fcast.protocol.FCastText.MAX_NAME, parsed.getName().length());
        assertFalse(parsed.getName().contains("\u0007"));
        assertFalse(parsed.getName().contains("\u202e"));
    }

    @Test
    public void aMissingOrWrongNameGivesAnEmptyNameAndStillWorks() throws Exception {
        assertEquals("", ok(link(json(null, new String[]{"192.168.0.2"}, TCP, null))).getName());
        assertEquals("", ok(link("{\"name\":5,\"addresses\":[\"192.168.0.2\"],\"services\":" + TCP + "}")).getName());
    }

    @Test
    public void hintsOfTheWrongShapeAreIgnored() throws Exception {
        assertEquals(FCastServiceTxt.EMPTY, ok(link("{\"addresses\":[\"192.168.0.2\"],\"services\":" + TCP + ",\"txt\":\"nope\"}")).getHints());
        assertNull(ok(link(json("x", new String[]{"192.168.0.2"}, TCP, "{\"v\":\"99999\",\"fp\":\"short\"}"))).getHints().getFingerprint());
    }

    // ---- hostile input

    @Test
    public void textThatIsNoFCastLinkIsNotALink() {
        for (String text : new String[]{null, "", "   ", "http://192.168.0.2", "fcast://x/abc", "fcast://r", "fcast:r/abc", "FCAST://r/abc",
                "https://fcast.org/fcast://r/abc", "192.168.0.2"}) {
            assertEquals(String.valueOf(text), FCastConnectionUrl.Problem.NOT_A_LINK, problem(text));
        }
        assertFalse(FCastConnectionUrl.looksLikeLink("http://x"));
        assertTrue(FCastConnectionUrl.looksLikeLink("fcast://r/abc"));
        assertFalse(FCastConnectionUrl.looksLikeLink(null));
    }

    @Test
    public void aLinkOverTheLimitIsRefusedBeforeAnythingIsDecoded() {
        StringBuilder text = new StringBuilder(FCastConnectionUrl.PREFIX);
        while (text.length() <= FCastConnectionUrl.MAX_URL) {
            text.append('A');
        }

        assertEquals(FCastConnectionUrl.Problem.TOO_LONG, problem(text.toString()));
    }

    @Test
    public void textThatIsNotBase64OrNotJsonIsRefused() throws Exception {
        assertEquals(FCastConnectionUrl.Problem.BAD_ENCODING, problem(FCastConnectionUrl.PREFIX + "!!!not base64!!!"));
        assertEquals(FCastConnectionUrl.Problem.BAD_ENCODING, problem(FCastConnectionUrl.PREFIX + "ab+/cd=="));
        assertEquals(FCastConnectionUrl.Problem.BAD_ENCODING, problem(FCastConnectionUrl.PREFIX));
        assertEquals(FCastConnectionUrl.Problem.BAD_JSON, problem(link("not json")));
        assertEquals(FCastConnectionUrl.Problem.BAD_JSON, problem(link("[1,2,3]")));
        assertEquals(FCastConnectionUrl.Problem.BAD_JSON, problem(link("{\"addresses\":[\"192.168.0.2\"],\"services\":" + TCP + "} trailing")));
        assertEquals("single quotes and comments are no JSON here", FCastConnectionUrl.Problem.BAD_JSON,
                problem(link("{'addresses':['192.168.0.2'],'services':" + TCP.replace('"', '\'') + "}")));
        byte[] binary = new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1};
        assertEquals(FCastConnectionUrl.Problem.BAD_JSON, problem(FCastConnectionUrl.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(binary)));
    }

    @Test
    public void deepNestingIsRefusedWithoutOverflowingTheStack() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            deep.append('[');
        }

        assertEquals(FCastConnectionUrl.Problem.BAD_JSON, problem(link(deep.toString())));
    }

    @Test
    public void wrongTypesAndMissingPartsAreRefused() throws Exception {
        assertEquals(FCastConnectionUrl.Problem.NO_ADDRESS, problem(link("{\"services\":" + TCP + "}")));
        assertEquals(FCastConnectionUrl.Problem.NO_ADDRESS, problem(link("{\"addresses\":\"192.168.0.2\",\"services\":" + TCP + "}")));
        assertEquals(FCastConnectionUrl.Problem.NO_ADDRESS, problem(link("{\"addresses\":[],\"services\":" + TCP + "}")));
        assertEquals(FCastConnectionUrl.Problem.NO_ADDRESS, problem(link("{\"addresses\":[5,null,{}],\"services\":" + TCP + "}")));
        assertEquals(FCastConnectionUrl.Problem.NO_SERVICE, problem(link("{\"addresses\":[\"192.168.0.2\"]}")));
        assertEquals(FCastConnectionUrl.Problem.NO_SERVICE, problem(link("{\"addresses\":[\"192.168.0.2\"],\"services\":\"tcp\"}")));
        assertEquals(FCastConnectionUrl.Problem.NO_SERVICE, problem(link("{\"addresses\":[\"192.168.0.2\"],\"services\":[]}")));
    }

    @Test
    public void anUnknownServiceTypeOrABadPortGivesNoService() throws Exception {
        for (String services : new String[]{"[{\"port\":46899,\"type\":1}]", "[{\"port\":46899,\"type\":-1}]", "[{\"port\":46899}]",
                "[{\"type\":0}]", "[{\"port\":0,\"type\":0}]", "[{\"port\":65536,\"type\":0}]", "[{\"port\":-5,\"type\":0}]",
                "[{\"port\":\"46899\",\"type\":0}]", "[{\"port\":46899.5,\"type\":0}]", "[1,2,null]", "[{\"port\":1e99,\"type\":0}]"}) {
            assertEquals(services, FCastConnectionUrl.Problem.NO_SERVICE, problem(link(json("x", new String[]{"192.168.0.2"}, services, null))));
        }
    }

    @Test
    public void aBadServiceBeforeAGoodOneIsSkipped() throws Exception {
        FCastConnectionUrl.Parsed parsed = ok(link(json("x", new String[]{"192.168.0.2"},
                "[{\"port\":0,\"type\":0},{\"port\":4711,\"type\":0}]", null)));

        assertEquals("192.168.0.2:4711", parsed.getEndpoints().get(0).toString());
    }

    @Test
    public void aHugeListOfAddressesIsCutToTheLimit() throws Exception {
        String[] addresses = new String[200];
        for (int i = 0; i < addresses.length; i++) {
            addresses[i] = "192.168.0." + (i + 1);
        }
        // 200 addresses do not fit the 4 KB limit with their JSON; a smaller list that does is cut to 16
        String[] fitting = java.util.Arrays.copyOf(addresses, 100);

        FCastConnectionUrl.Parsed parsed = ok(link(json("x", fitting, TCP, null)));

        assertEquals(FCastConnectionUrl.MAX_ADDRESSES, parsed.getEndpoints().size());
        assertEquals("192.168.0.1:46899", parsed.getEndpoints().get(0).toString());
        assertEquals(FCastConnectionUrl.Problem.TOO_LONG, problem(link(json("x", addresses, TCP, null))));
    }
}
