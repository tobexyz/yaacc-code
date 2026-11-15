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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the wiring of the renderer's output (task 9.1) that only the device could show otherwise: both renderers get their
 * player from the one place, the volume of both goes through the routed volume, a change of the setting is listened to only
 * while the player service lives, the read path of the volume never sets, and the chooser stores an id and nothing else.
 * Plain JVM: the sources are read (comments stripped) from the module directory.
 */
public class RendererOutputWiringGuardTest {

    private static String source(String relativePath) throws Exception {
        File file = new File("src/main/java/de/yaacc/" + relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String methodBody(String source, String signatureStart) {
        int at = source.indexOf(signatureStart);
        assertTrue("no method " + signatureStart, at >= 0);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                depth++;
            } else if (source.charAt(i) == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + signatureStart);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    public void bothRenderersGetTheirPlayerFromTheOneLocalPlayerFactory() throws Exception {
        String service = source("player/PlayerService.java");

        assertTrue(methodBody(service, "public List<Player> createPlayerForLocalDevice(").contains("createLocalPlayer("));
        assertTrue(methodBody(service, "public List<Player> createPlayerForLocalQueue(").contains("createLocalPlayer("));
        assertTrue(source("upnp/UpnpClient.java").contains("playerService.createPlayerForLocalQueue(this, items)"));
        assertEquals("only the renderer's factory asks for the output decision", 1, count(service, "SnapcastRendererOutput.resolve("));
        assertEquals("only the group branch of the renderer's factory makes the renderer's Snapcast player", 1,
                count(methodBody(service, "private List<Player> createRendererGroupPlayer("), "createSnapcastPlayer("));
    }

    @Test
    public void theTransportCommandsLookForThePlayerOfTheOutput() throws Exception {
        String client = source("upnp/UpnpClient.java");
        String lookup = methodBody(client, "public List<Player> getCurrentPlayers(AvTransport transport)");

        assertTrue(lookup.contains("getRendererPlayersOfType("));
        assertFalse(lookup.contains("getCurrentPlayersOfType("));
    }

    @Test
    public void theOutputSettingIsListenedToOnlyWhileThePlayerServiceLives() throws Exception {
        String service = source("player/PlayerService.java");

        assertTrue(methodBody(service, "public void onCreate()").contains("registerOnSharedPreferenceChangeListener(rendererOutputListener)"));
        assertTrue(methodBody(service, "public void onDestroy()").contains("unregisterOnSharedPreferenceChangeListener(rendererOutputListener)"));
        assertTrue("kept in a field: the preferences hold their listeners weakly",
                service.contains("OnSharedPreferenceChangeListener rendererOutputListener ="));
    }

    @Test
    public void everyShutdownAndRemovalForgetsTheRendererTag() throws Exception {
        String service = source("player/PlayerService.java");

        assertTrue(methodBody(service, "public void shutdown(Player player)").contains("rendererPlayers.forget(player)"));
        assertTrue(methodBody(service, "public void removePlayer(Player player)").contains("rendererPlayers.forget(player)"));
    }

    @Test
    public void theVolumeOfBothRenderersGoesThroughTheRoutedVolume() throws Exception {
        String rendering = source("upnp/server/renderingcontrol/YaaccAudioRenderingControlService.java");
        String openHome = source("upnp/server/openhome/AndroidOpenHomeHost.java");

        assertTrue(rendering.contains("RendererVolumes.forContext(context)"));
        assertTrue(rendering.contains("RendererVolumes.watcherFor(context)"));
        assertFalse("the service no longer makes the system volume itself", rendering.contains("new SystemVolume("));
        assertTrue(openHome.contains("RendererVolumes.forContext(context)"));
        assertFalse(openHome.contains("new SystemVolume("));
    }

    @Test
    public void theReadPathOfTheRoutedVolumeNeverSetsAnything() throws Exception {
        String routed = source("upnp/server/renderingcontrol/OutputRoutedVolume.java");

        for (String read : new String[]{"public int getPercent()", "public boolean isMuted()", "private Level read(Setting setting)",
                "private void wakeIfDue(String id)"}) {
            String body = methodBody(routed, read);
            assertFalse(read, body.contains("group.setVolume(") || body.contains("group.setMuted(") || body.contains("local.setPercent(")
                    || body.contains("local.setMuted(") || body.contains("sender.execute("));
        }
        assertEquals("the group is set in one place, the sender's task", 1, count(routed, "group.setVolume("));
        assertEquals(1, count(routed, "group.setMuted("));
        assertTrue(methodBody(routed, "private void send()").contains("group.setVolume("));
    }

    @Test
    public void theWatcherOnlyCallsTheReadingCallback() throws Exception {
        String volumes = source("upnp/server/renderingcontrol/RendererVolumes.java");
        String groupsListener = volumes.substring(volumes.indexOf("final SnapcastReceivers.ChangeListener groups"),
                volumes.indexOf("hub.addChangeListener(groups)"));

        assertTrue(groupsListener.contains("onChange.run()"));
        assertFalse(groupsListener.contains("setVolume") || groupsListener.contains("setMuted") || groupsListener.contains("controls"));
    }

    @Test
    public void theChooserStoresTheReceiverIdAndNothingElse() throws Exception {
        String dialog = source("settings/SnapcastRendererGroupDialog.java");
        Matcher puts = Pattern.compile("\\.put\\w+\\(([^,]+),").matcher(dialog);
        int found = 0;
        while (puts.find()) {
            assertEquals("only the group key is written", "SnapcastRendererOutput.KEY_GROUP", puts.group(1).trim());
            found++;
        }
        assertEquals(1, found);
        assertTrue(dialog.contains("group.receiverId()"));
        assertFalse("no name is stored", dialog.contains(".name())") && dialog.contains("putString(SnapcastRendererOutput.KEY_GROUP, group.name"));
    }

    @Test
    public void theOutputDecisionOpensNoConnectionOfItsOwn() throws Exception {
        String decision = source("multiroom/SnapcastRendererOutput.java");

        for (String forbidden : new String[]{"java.net", "Socket", "SnapcastFeedController", "PcmFeeder", "newFeedController"}) {
            assertFalse(forbidden, decision.contains(forbidden));
        }
    }
}
