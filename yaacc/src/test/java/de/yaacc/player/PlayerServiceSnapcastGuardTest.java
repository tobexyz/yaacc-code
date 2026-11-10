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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.model.meta.Device;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Until the Snapcast player exists, a Snapcast group is listed as a receiver but nothing can be played to it: the
 * player service must say so instead of building a player that silently does nothing.
 */
public class PlayerServiceSnapcastGuardTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static SnapcastGroupDevice groupDevice() {
        SnapcastClient member = new SnapcastClient("m", "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g-a", "Attic", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5:1705").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void aSnapcastGroupHasNoPlayerYet() {
        assertTrue(PlayerService.isNotPlayableYet(groupDevice()));
    }

    @Test
    public void otherReceiversAreNotAffected() {
        assertFalse(PlayerService.isNotPlayableYet((Device<?, ?, ?>) null));
    }

    @Test
    public void theToastTextExistsInEveryLanguage() throws Exception {
        for (String locale : LOCALES) {
            File file = new File("src/main/res/" + locale + "/strings.xml");
            assertTrue("missing " + file.getAbsolutePath(), file.isFile());
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int at = xml.indexOf("<string name=\"snapcast_play_not_available\">");
            assertTrue(locale + " lacks snapcast_play_not_available", at >= 0);
            int end = xml.indexOf("</string>", at);
            assertTrue(locale + ": empty text", end - at > "<string name=\"snapcast_play_not_available\">".length());
        }
    }

    @Test
    public void theEnglishTextSaysNotAvailableYet() throws Exception {
        String xml = new String(Files.readAllBytes(new File("src/main/res/values/strings.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(xml.contains(">Playing to Snapcast groups is not available yet<"));
        assertEquals(1, xml.split("snapcast_play_not_available", -1).length - 1);
    }
}
