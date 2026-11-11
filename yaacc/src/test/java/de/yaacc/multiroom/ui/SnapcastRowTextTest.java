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
import static de.yaacc.multiroom.ui.UiFixtures.status;
import static de.yaacc.multiroom.ui.UiFixtures.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * What a Snapcast group row says: the stream, how many clients are connected, the group volume, the endpoint of the
 * server (so that a hostile server cannot pass for a legitimate group), and the two markers "server unreachable" and
 * "stream unavailable". Plain JVM; the words are fakes.
 */
public class SnapcastRowTextTest {

    private static final SnapcastRowText.Words WORDS = new SnapcastRowText.Words() {
        @Override
        public String stream(String name) {
            return "Stream " + name;
        }

        @Override
        public String noStream() {
            return "No stream";
        }

        @Override
        public String clientsConnected(int connected, int total) {
            return connected + " of " + total + " clients connected";
        }

        @Override
        public String noClients() {
            return "No clients";
        }

        @Override
        public String volume(int percent) {
            return "Volume " + percent + "%";
        }

        @Override
        public String muted() {
            return "muted";
        }

        @Override
        public String serverUnreachable() {
            return "server unreachable";
        }

        @Override
        public String streamUnavailable() {
            return "stream unavailable";
        }

        @Override
        public String server(String endpoint) {
            return "Server " + endpoint;
        }

        @Override
        public String groupGone() {
            return "group gone";
        }
    };

    private static final String ENDPOINT = "192.168.1.5:1705";

    private static SnapcastGroup threeClients(String stream) {
        return group("g1", "Ground floor", stream,
                client("a", "Kitchen", true, 40, false),
                client("b", "Hall", true, 60, false),
                client("c", "Attic", false, 100, false));
    }

    private static SnapcastRowText.Row row(SnapcastGroup group, SnapcastServerStatus status, boolean online) {
        return SnapcastRowText.of(group, status, online, ENDPOINT, WORDS);
    }

    @Test
    public void aRowShowsStreamClientsAndVolumeAndTheServer() {
        SnapcastGroup group = threeClients("Default");
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "playing")), true);

        assertEquals("Stream Default · 2 of 3 clients connected · Volume 50%\nServer 192.168.1.5:1705", row.getText());
        assertEquals(50, row.getVolume());
        assertFalse(row.isMuted());
        assertTrue(row.isControllable());
    }

    @Test
    public void theGroupVolumeIsTheAverageOfTheConnectedMembersOnly() {
        SnapcastGroup group = threeClients("Default");

        assertEquals(50, row(group, status(group, stream("Default", "idle")), true).getVolume());
    }

    @Test
    public void aMutedGroupIsSaidAndKeepsItsVolume() {
        SnapcastGroup group = threeClients("Default").withMuted(true);
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "idle")), true);

        assertTrue(row.isMuted());
        assertTrue(row.getText(), row.getText().contains("muted"));
        assertEquals(50, row.getVolume());
    }

    @Test
    public void anIdleStreamIsNoProblem() {
        SnapcastGroup group = threeClients("Default");
        String text = row(group, status(group, stream("Default", "idle")), true).getText();

        assertFalse(text, text.contains("unavailable"));
        assertFalse(text, text.contains("unreachable"));
    }

    @Test
    public void aStreamThatTheServerDoesNotListIsUnavailable() {
        SnapcastGroup group = threeClients("Gone");
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "playing")), true);

        assertTrue(row.getText(), row.getText().contains("Stream Gone · stream unavailable"));
        assertTrue("the volume can still be changed", row.isControllable());
    }

    @Test
    public void aDisabledStreamIsUnavailable() {
        SnapcastGroup group = threeClients("Radio");

        assertTrue(row(group, status(group, stream("Radio", "disabled")), true).getText().contains("stream unavailable"));
    }

    @Test
    public void aGroupWithoutAStreamSaysSo() {
        SnapcastGroup group = threeClients("");
        String text = row(group, status(group, stream("Default", "idle")), true).getText();

        assertTrue(text, text.startsWith("No stream · "));
        assertFalse(text, text.contains("unavailable"));
    }

    @Test
    public void anUnreachableServerIsMarkedAndKeepsItsLastKnownGroup() {
        SnapcastGroup group = threeClients("Default");
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "playing")), false);

        assertEquals("server unreachable · Stream Default\nServer 192.168.1.5:1705", row.getText());
        assertFalse("nothing can be changed on a server that does not answer", row.isControllable());
        assertEquals("the last known volume stays on the slider", 50, row.getVolume());
    }

    @Test
    public void anUnreachableServerDoesNotClaimConnectedClients() {
        SnapcastGroup group = threeClients("Default");

        assertFalse(row(group, status(group, stream("Default", "playing")), false).getText().contains("connected"));
    }

    @Test
    public void aGroupThatIsNoLongerInTheStatusIsSaidToBeGone() {
        SnapcastGroup group = threeClients("Default");
        SnapcastRowText.Row row = row(null, status(group), true);

        assertEquals("group gone\nServer 192.168.1.5:1705", row.getText());
        assertFalse(row.isControllable());
        assertEquals(-1, row.getVolume());
    }

    @Test
    public void aGroupWithoutMembersHasNoVolume() {
        SnapcastGroup group = group("g2", "Empty", "Default");
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "idle")), true);

        assertEquals(-1, row.getVolume());
        assertFalse(row.isControllable());
        assertTrue(row.getText(), row.getText().contains("No clients"));
    }

    @Test
    public void aGroupWhoseMembersAreAllDisconnectedHasNoVolumeButShowsTheCount() {
        SnapcastGroup group = group("g3", "Away", "Default", client("a", "Kitchen", false, 40, false));
        SnapcastRowText.Row row = row(group, status(group, stream("Default", "idle")), true);

        assertEquals(-1, row.getVolume());
        assertFalse(row.isControllable());
        assertTrue(row.getText(), row.getText().contains("0 of 1 clients connected"));
    }

    @Test
    public void theStreamNameIsCleanedBeforeItIsShown() {
        SnapcastGroup group = threeClients("Radio\u202E\nFake\u200B");
        String text = row(group, status(group, stream("Radio\u202E\nFake\u200B", "idle")), true).getText();

        assertTrue(text, text.startsWith("Stream Radio??Fake? · "));
        assertEquals("one line of facts and one for the server", 1, text.split("\n").length - 1);
    }

    @Test
    public void theEndpointIsCleanedToo() {
        SnapcastGroup group = threeClients("Default");
        SnapcastRowText.Row row = SnapcastRowText.of(group, status(group, stream("Default", "idle")), true,
                "evil\u202E.local:1705", WORDS);

        assertTrue(row.getText(), row.getText().endsWith("Server evil?.local:1705"));
    }

    @Test
    public void aLongStreamNameIsCut() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            name.append('x');
        }
        SnapcastGroup group = threeClients(name.toString());
        String first = row(group, status(group, stream(name.toString(), "idle")), true).getText().split("\n")[0];

        assertTrue(first, first.length() < 150);
    }

    @Test
    public void rowsOfEqualContentAreEqualAndAChangeShows() {
        SnapcastGroup group = threeClients("Default");
        SnapcastServerStatus status = status(group, stream("Default", "idle"));

        assertEquals(row(group, status, true), row(group, status, true));
        assertEquals(row(group, status, true).hashCode(), row(group, status, true).hashCode());
        assertNotEquals(row(group, status, true), row(group, status, false));
        assertNotEquals(row(group, status, true), row(group.withMuted(true), status, true));
        SnapcastGroup louder = group.withClient(client("a", "Kitchen", true, 80, false));
        assertNotEquals(row(group, status, true), row(louder, status, true));
    }
}
