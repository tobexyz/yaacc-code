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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import de.yaacc.sendspin.player.DisplayLook;
import de.yaacc.sendspin.session.ControllerMessages;

/** How the display screen is coloured (task 11.5): the colour of the server as it is when only colours arrive, readable colours with text. */
public class DisplayLookTest {

    private static ControllerMessages.Color color(String json) throws Exception {
        return ControllerMessages.parseServerState(new JSONObject("{\"color\":" + json + "}")).color;
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Test
    public void withoutContentTheScreenIsTheDominantColourAsSent() throws Exception {
        ControllerMessages.Color c = color("{\"timestamp\":1,\"primary\":[250,10,20],\"background_dark\":[10,10,10],\"accent\":[1,2,3]}");
        DisplayLook look = DisplayLook.of(c, false);
        assertTrue(look.lightMode);
        assertEquals(argb(250, 10, 20), look.background);
    }

    @Test
    public void withoutContentAndWithoutPrimaryTheNextColourIsTaken() throws Exception {
        assertEquals(argb(10, 20, 30), DisplayLook.of(color("{\"timestamp\":1,\"background_dark\":[10,20,30],\"accent\":[1,2,3]}"), false).background);
        assertEquals(argb(1, 2, 3), DisplayLook.of(color("{\"timestamp\":1,\"accent\":[1,2,3]}"), false).background);
        assertEquals(argb(200, 200, 200), DisplayLook.of(color("{\"timestamp\":1,\"on_dark\":[200,200,200]}"), false).background);
    }

    @Test
    public void aServerThatSendsNoColourGivesBlack() {
        DisplayLook look = DisplayLook.of(null, false);
        assertEquals(argb(0, 0, 0), look.background);
        assertTrue(look.lightMode);
    }

    @Test
    public void withTextOrACoverTheReadableColoursAreUsed() throws Exception {
        ControllerMessages.Color c = color("{\"timestamp\":1,\"primary\":[250,10,20],\"background_dark\":[10,20,30],\"on_dark\":[240,240,230]}");
        DisplayLook look = DisplayLook.of(c, true);
        assertFalse(look.lightMode);
        assertEquals(argb(10, 20, 30), look.background);
        assertEquals(argb(240, 240, 230), look.foreground);
    }

    @Test
    public void withTextAndNoColoursItIsWhiteOnBlack() {
        DisplayLook look = DisplayLook.of(null, true);
        assertEquals(argb(0, 0, 0), look.background);
        assertEquals(argb(255, 255, 255), look.foreground);
    }
}
