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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcEvent;
import de.yaacc.yxc.YxcEventParser;

/** The line of a receiver row for a device with group support. */
public class YxcRowTextTest {

    private static final YxcRowText.Words WORDS = new YxcRowText.Words() {
        public String on() {
            return "On";
        }

        public String standby() {
            return "Standby";
        }

        public String notReachable() {
            return "Not reachable";
        }

        public String linkedWith(String group) {
            return "Linked with " + group;
        }

        public String linked() {
            return "Linked";
        }

        public String groupWith(String names) {
            return "Group with " + names;
        }

        public String groupLeader() {
            return "Group leader";
        }

        public String noGroupOnLinkInput() {
            return "No group, link input";
        }
    };

    private static YxcDeviceState state(String statusJson, String distJson) throws Exception {
        YxcClient client = new YxcClient(new FixtureTransport(statusJson, distJson));
        return YxcDeviceState.unknown().withZone(client.getZoneStatus("main")).withDistribution(client.getDistributionInfo());
    }

    /** Answers the two calls with given JSON texts. */
    private static final class FixtureTransport implements de.yaacc.yxc.YxcTransport {
        private final String status;
        private final String dist;

        FixtureTransport(String status, String dist) {
            this.status = status;
            this.dist = dist;
        }

        @Override
        public String get(String path) {
            return path.startsWith("main/") ? status : dist;
        }

        @Override
        public String post(String path, String body) {
            return get(path);
        }
    }

    private static final String NO_GROUP = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000000\",\"group_name\":\"x\",\"role\":\"none\",\"client_list\":[]}";
    private static final String CLIENT = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000001\",\"group_name\":\"Kitchen\",\"role\":\"client\",\"client_list\":[]}";
    private static final String SERVER = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000001\",\"group_name\":\"Living room\",\"role\":\"server\",\"client_list\":[{\"ip_address\":\"192.0.2.1\"}]}";
    private static final String SERVER_NO_NAME = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000001\",\"group_name\":\"  \",\"role\":\"server\",\"client_list\":[]}";

    private static String status(String power, String input, String inputText) {
        return "{\"response_code\":0,\"power\":\"" + power + "\",\"input\":\"" + input + "\""
                + (inputText == null ? "" : ",\"input_text\":\"" + inputText + "\"") + ",\"volume\":10,\"mute\":false}";
    }

    @Test
    public void nothingIsShownForNoState() {
        assertNull(YxcRowText.describe(null, WORDS));
        assertNull(YxcRowText.describe(YxcDeviceState.unknown(), WORDS));
        assertNull("not reachable and never known", YxcRowText.describe(YxcDeviceState.unknown().unreachable(), WORDS));
    }

    @Test
    public void aDeviceThatIsOnShowsPowerAndInput() throws Exception {
        assertEquals("On · Net radio", YxcRowText.describe(state(status("on", "net_radio", null), NO_GROUP), WORDS));
    }

    @Test
    public void theNameTheDeviceGivesTheInputWins() throws Exception {
        assertEquals("On · TV", YxcRowText.describe(state(status("on", "hdmi1", "TV"), NO_GROUP), WORDS));
    }

    @Test
    public void aDeviceInStandbyShowsNoInput() throws Exception {
        assertEquals("Standby", YxcRowText.describe(state(status("standby", "tuner", null), NO_GROUP), WORDS));
    }

    @Test
    public void aClientShowsTheGroupInsteadOfTheLinkInput() throws Exception {
        assertEquals("On · Linked with Kitchen",
                YxcRowText.describe(state(status("on", "mc_link", "MC Link"), CLIENT), WORDS, "Kitchen",
                        java.util.Collections.<String>emptyList()));
    }

    @Test
    public void aServerShowsTheNamesOfItsClients() throws Exception {
        assertEquals("On · Optical · Group with Living room, Bedroom",
                YxcRowText.describe(state(status("on", "optical", null), SERVER), WORDS, null,
                        java.util.Arrays.asList("Living room", "Bedroom", null)));
    }

    @Test
    public void aServerWithoutAGroupNameIsTheGroupLeader() throws Exception {
        assertEquals("On · Optical · Group leader",
                YxcRowText.describe(state(status("on", "optical", null), SERVER_NO_NAME), WORDS));
    }

    @Test
    public void anUnreachableDeviceKeepsItsGroup() throws Exception {
        YxcDeviceState gone = state(status("on", "mc_link", null), CLIENT).unreachable();

        assertEquals("Not reachable · Linked with Kitchen",
                YxcRowText.describe(gone, WORDS, "Kitchen", java.util.Collections.<String>emptyList()));
    }

    @Test
    public void anInputFromAnEventHasNoDeviceNameUntilTheZoneIsReadAgain() throws Exception {
        YxcDeviceState before = state(status("on", "hdmi1", "TV"), NO_GROUP);
        YxcEvent event = new YxcEventParser().parse("{\"main\":{\"input\":\"net_radio\"}}");

        assertEquals("On · Net radio", YxcRowText.describe(before.withEvent(event), WORDS));
    }

    @Test
    public void anEventForTheSameInputKeepsTheDeviceName() throws Exception {
        YxcDeviceState before = state(status("on", "hdmi1", "TV"), NO_GROUP);
        YxcEvent event = new YxcEventParser().parse("{\"main\":{\"input\":\"hdmi1\",\"volume\":12}}");

        assertEquals("On · TV", YxcRowText.describe(before.withEvent(event), WORDS));
    }

    @Test
    public void namesFromTheDeviceAreMadeFitForOneLine() {
        assertNull(YxcRowText.clean(null));
        assertNull(YxcRowText.clean(" \u202e \n "));
        assertEquals("Kitchen", YxcRowText.clean("Kit\u202echen\n"));
        assertEquals("Living room", YxcRowText.clean("  Living room "));
        String longName = new String(new char[500]).replace('\0', 'x');
        assertEquals(YxcRowText.MAX_NAME_CHARS + 1, YxcRowText.clean(longName).length());
        assertEquals('\u2026', YxcRowText.clean(longName).charAt(YxcRowText.MAX_NAME_CHARS));
    }

    @Test
    public void aClientWhoseServerIsNotKnownShowsOnlyThatItIsLinked() throws Exception {
        assertEquals("On \u00b7 Linked", YxcRowText.describe(state(status("on", "mc_link", null), CLIENT), WORDS));
    }

    @Test
    public void theOwnGroupNameOfAClientIsNeverShown() throws Exception {
        String line = YxcRowText.describe(state(status("on", "mc_link", null), CLIENT), WORDS);

        assertEquals(false, line.contains("Kitchen"));
    }

    private static final String CLIENT_LEFT = "{\"response_code\":0,\"group_id\":\"00000000000000000000000000000000\",\"group_name\":\"(Linked) x\",\"role\":\"client\",\"client_list\":[]}";

    @Test
    public void aClientThatLeftButStaysOnTheLinkInputIsSaidSo() throws Exception {
        YxcDeviceState stuck = state(status("on", "mc_link", null), CLIENT_LEFT);

        org.junit.Assert.assertTrue(stuck.isStuckClient());
        assertEquals("On · No group, link input", YxcRowText.describe(stuck, WORDS, "Kitchen", java.util.Collections.<String>emptyList()));
    }
}
