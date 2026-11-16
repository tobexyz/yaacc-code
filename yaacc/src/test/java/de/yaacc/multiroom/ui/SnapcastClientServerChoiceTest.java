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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Task 12.10: which server the phone's Snapcast client uses. The last server it was connected to; else the first known server (the
 * order of {@link SnapcastServerChoices}: last used, typed, remembered, found); none when no server is known. Table tests, plain JVM.
 */
public class SnapcastClientServerChoiceTest {

    private static SnapcastServerListModel.Row row(String endpoint, String name, SnapcastServerListModel.Kind kind) {
        SnapcastEndpoint parsed = SnapcastEndpoint.parse(endpoint);
        return new SnapcastServerListModel.Row(parsed.toKey(), parsed.toString(), name, kind, kind == SnapcastServerListModel.Kind.DISCOVERED);
    }

    private static List<SnapcastServerChoices.Choice> known() {
        return SnapcastServerChoices.of(Arrays.asList(
                row("192.168.1.5:1705", "Living room", SnapcastServerListModel.Kind.MANUAL),
                row("192.168.1.9:1705", "Kitchen", SnapcastServerListModel.Kind.REMEMBERED),
                row("192.168.1.20:1705", "Hall", SnapcastServerListModel.Kind.DISCOVERED)), "");
    }

    @Test
    public void noKnownServerMeansNoServer() {
        assertNull(SnapcastClientServerChoice.resolve(null, null));
        assertNull(SnapcastClientServerChoice.resolve("", new ArrayList<SnapcastServerChoices.Choice>()));
        assertNull("a remembered server that is not known any more is no server", SnapcastClientServerChoice.resolve("192.168.1.5", Collections.<SnapcastServerChoices.Choice>emptyList()));
    }

    @Test
    public void withoutAServerConnectedBeforeTheFirstKnownServerIsUsed() {
        List<SnapcastServerChoices.Choice> known = known();

        for (String last : new String[]{null, "", "   "}) {
            assertSame(String.valueOf(last), known.get(0), SnapcastClientServerChoice.resolve(last, known));
        }
        assertEquals("the typed server comes first in the order of the choices", "192.168.1.5", known.get(0).hostText());
    }

    @Test
    public void theLastConnectedServerWinsOverTheFirstOne() {
        List<SnapcastServerChoices.Choice> known = known();

        assertSame(known.get(2), SnapcastClientServerChoice.resolve("192.168.1.20", known));
        assertSame(known.get(1), SnapcastClientServerChoice.resolve("192.168.1.9", known));
    }

    @Test
    public void theLastServerIsFoundByItsTargetNotByItsSpelling() {
        List<SnapcastServerChoices.Choice> known = known();

        // the default stream port is the same target as no port; the case of a host name does not matter either
        assertSame(known.get(1), SnapcastClientServerChoice.resolve("192.168.1.9:1704", known));
        assertSame(known.get(1), SnapcastClientServerChoice.resolve("  192.168.1.9  ", known));
    }

    @Test
    public void aLastServerThatIsNotKnownAnyMoreFallsBackToTheFirst() {
        List<SnapcastServerChoices.Choice> known = known();

        assertSame(known.get(0), SnapcastClientServerChoice.resolve("192.168.1.77", known));
        assertSame("another stream port is another target", known.get(0), SnapcastClientServerChoice.resolve("192.168.1.9:1800", known));
    }

    @Test
    public void aDamagedStoredKeyIsTheSameAsNone() {
        List<SnapcastServerChoices.Choice> known = known();

        for (String damaged : new String[]{"http://192.168.1.9", "user:pw@192.168.1.9", "192.168.1.9/x", "ho st", "0.0.0.0", "192.168.1.9:0",
                "\u0000", new String(new char[400]).replace('\u0000', 'a')}) {
            assertSame(damaged, known.get(0), SnapcastClientServerChoice.resolve(damaged, known));
        }
    }

    @Test
    public void theCurrentServerIsMarkedInTheListByTheSameRule() {
        List<SnapcastServerChoices.Choice> known = known();

        assertEquals(2, SnapcastClientServerChoice.currentIndex("192.168.1.20", known));
        assertEquals("none connected before: the first", 0, SnapcastClientServerChoice.currentIndex(null, known));
        assertEquals("not known any more: the first", 0, SnapcastClientServerChoice.currentIndex("10.9.9.9", known));
        assertEquals(-1, SnapcastClientServerChoice.currentIndex("192.168.1.20", Collections.<SnapcastServerChoices.Choice>emptyList()));
        assertEquals(-1, SnapcastClientServerChoice.currentIndex("192.168.1.20", null));
    }

    @Test
    public void everyKnownServerStaysAChoiceUpToTheLimit() {
        List<SnapcastServerListModel.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row("10.0.0." + i + ":1705", "S" + i, SnapcastServerListModel.Kind.MANUAL));
        }
        List<SnapcastServerChoices.Choice> known = SnapcastServerChoices.of(rows, "");

        assertEquals(SnapcastServerChoices.MAX_CHOICES, known.size());
        assertSame("the last one connected is among the listed ones", known.get(3), SnapcastClientServerChoice.resolve(known.get(3).hostText(), known));
    }

    @Test
    public void twoTextsAreTheSameServerWhenTheyHaveTheSameTarget() {
        assertTrue(SnapcastClientServerChoice.sameServer("192.168.1.9", "192.168.1.9:1704"));
        assertTrue(SnapcastClientServerChoice.sameServer(" 192.168.1.9 ", "192.168.1.9"));
        assertFalse(SnapcastClientServerChoice.sameServer("192.168.1.9", "192.168.1.9:1800"));
        assertFalse(SnapcastClientServerChoice.sameServer("192.168.1.9", "192.168.1.10"));
        assertFalse("a text that is no server is the same as nothing", SnapcastClientServerChoice.sameServer("bad host", "bad host"));
        assertFalse(SnapcastClientServerChoice.sameServer(null, null));
        assertFalse(SnapcastClientServerChoice.sameServer("192.168.1.9", null));
        assertFalse(SnapcastClientServerChoice.sameServer("", ""));
    }
}
