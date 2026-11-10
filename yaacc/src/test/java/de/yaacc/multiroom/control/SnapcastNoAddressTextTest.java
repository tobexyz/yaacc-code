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
package de.yaacc.multiroom.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/** Text from the network or from a server (which may hold addresses) stays out of causes and out of {@code toString()}. */
public class SnapcastNoAddressTextTest {

    private static final String ADDRESS = "192.0.2.55";

    @Test
    public void anIoExceptionKeepsNeitherItsCauseNorItsText() {
        SnapcastException e = SnapcastException.io(new IOException("Connection refused: /" + ADDRESS + ":1705"));

        assertNull("the cause text can carry the address of the server", e.getCause());
        assertFalse(e.getMessage(), e.getMessage().contains(ADDRESS));
        assertFalse(e.describe().contains(ADDRESS));
    }

    @Test
    public void aJsonSyntaxErrorKeepsNeitherItsCauseNorTheTextOfTheServer() {
        try {
            SnapcastJson.parse("{\"host\":\"" + ADDRESS + "\" x}");
            fail();
        } catch (SnapcastException e) {
            assertNull("org.json quotes the whole input in its message", e.getCause());
            assertFalse(e.getMessage(), e.getMessage().contains(ADDRESS));
        }
    }

    @Test
    public void toStringOfAStreamPrintsNoTextOfTheServer() {
        SnapcastStream stream = new SnapcastStream("s", ADDRESS + "\nplaying", "raw", "tcp\n" + ADDRESS, ADDRESS, 4953, "/p", null);

        String text = stream.toString();

        assertFalse(text, text.contains(ADDRESS));
        assertFalse(text, text.contains("\n"));
    }

    @Test
    public void toStringOfAStreamStillSaysWhatIsKnown() {
        SnapcastStream stream = new SnapcastStream("s", "playing", "raw", "tcp", "0.0.0.0", 4953, "", null);

        assertTrue(stream.toString(), stream.toString().contains("playing"));
        assertTrue(stream.toString(), stream.toString().contains("tcp"));
    }

    @Test
    public void toStringOfTheServerInfoPrintsNoTextOfTheServer() {
        SnapcastServerInfo info = new SnapcastServerInfo(ADDRESS, "name " + ADDRESS, "v" + ADDRESS + "\n", 2, 1);

        String text = info.toString();

        assertFalse(text, text.contains(ADDRESS));
        assertFalse(text, text.contains("\n"));
    }
}
