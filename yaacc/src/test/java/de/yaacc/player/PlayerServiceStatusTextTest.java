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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The notification of the service says how many players there are and what they do, in the language of the phone. */
public class PlayerServiceStatusTextTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};
    private static final String[] STRINGS = {"player_service_title", "player_service_idle", "player_service_status",
            "player_service_state_separator"};
    private static final String[] PLURALS = {"player_service_players", "player_service_playing", "player_service_paused",
            "player_service_stopped"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theTextsAreAssembledFromTheParts() {
        assertEquals("3 players (1 playing, 1 paused, 1 stopped)", PlayerService.statusText(3, "running", "3 players",
                Arrays.asList("1 playing", "1 paused", "1 stopped"), "%1$s (%2$s)", ", "));
        assertEquals("1 player (1 paused)", PlayerService.statusText(1, "running", "1 player",
                Collections.singletonList("1 paused"), "%1$s (%2$s)", ", "));
    }

    @Test
    public void noPlayersGiveTheIdleText() {
        assertEquals("Running", PlayerService.statusText(0, "Running", "0 players", Collections.emptyList(), "%1$s (%2$s)", ", "));
    }

    @Test
    public void theFormatOfALanguageDecidesTheOrder() {
        assertEquals("(1 playing) 1 player", PlayerService.statusText(1, "-", "1 player",
                Collections.singletonList("1 playing"), "(%2$s) %1$s", ", "));
    }

    @Test
    public void everyLocaleHasEveryTextAndEveryPluralHasOtherAndTheNumber() throws Exception {
        for (String dir : LOCALES) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            for (String name : STRINGS) {
                assertTrue(dir + " " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
            for (String name : PLURALS) {
                Matcher plural = Pattern.compile("<plurals name=\"" + name + "\">(.*?)</plurals>", Pattern.DOTALL).matcher(xml);
                assertTrue(dir + " " + name, plural.find());
                assertTrue(dir + " " + name + " other", plural.group(1).contains("quantity=\"other\""));
                Matcher item = Pattern.compile("<item quantity=\"[a-z]+\">([^<]+)</item>").matcher(plural.group(1));
                while (item.find()) {
                    assertTrue(dir + " " + name + " shows the number: " + item.group(1), item.group(1).contains("%d"));
                }
            }
            // the format takes both parts
            Matcher format = Pattern.compile("<string name=\"player_service_status\">([^<]+)</string>").matcher(xml);
            assertTrue(format.find());
            assertTrue(dir, format.group(1).contains("%1$s") && format.group(1).contains("%2$s"));
        }
    }

    @Test
    public void theNotificationOfTheServiceHasNoTextOfItsOwn() throws Exception {
        String source = read("src/main/java/de/yaacc/player/PlayerService.java");
        assertFalse(source.contains("\"Player Service\""));
        assertFalse(source.contains("\"running\""));
        assertFalse(source.contains("\" player\""));
        assertFalse(source.contains("\"playing\")"));
    }

    @Test
    public void theOtherNotificationsHaveNoEnglishTextOfTheirOwnEither() throws Exception {
        String[] names = {"notification_upnp_service_title", "notification_no_network_interface", "notification_indexing",
                "notification_player_title", "notification_download_title", "notification_download_to"};
        for (String dir : LOCALES) {
            String xml = read("src/main/res/" + dir + "/strings.xml");
            for (String name : names) {
                assertTrue(dir + " " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
            assertTrue(dir, xml.contains("<string name=\"notification_download_to\">") && xml.contains("%1$s"));
        }
        assertFalse(read("src/main/java/de/yaacc/upnp/server/YaaccUpnpServerService.java").contains("\"Yaacc UPnP Service\""));
        assertFalse(read("src/main/java/de/yaacc/upnp/server/YaaccUpnpServerService.java").contains("No usable network interface"));
        assertFalse(read("src/main/java/de/yaacc/player/AbstractPlayer.java").contains("\"Yaacc player\""));
        assertFalse(read("src/main/java/de/yaacc/util/FileDownloader.java").contains("\"download to: \""));
    }
}
