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

        for (String read : new String[]{"public int getPercent()", "public boolean isMuted()", "public int peekPercent()",
                "public boolean peekMuted()", "private int percent(boolean mayWake)", "private boolean muted(boolean mayWake)",
                "private Level read(Setting setting, boolean mayWake)", "private void wake(String id, boolean force)"}) {
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
        String groupsListener = volumes.substring(volumes.indexOf("this.groups = () -> {"), volumes.indexOf("this.setting = (changed, key)"));

        assertTrue(groupsListener.contains("onChange.run()"));
        assertFalse(groupsListener.contains("setVolume") || groupsListener.contains("setMuted") || groupsListener.contains("controls"));
        assertFalse("the change of a group never wakes a connection (the idle close is a change)", groupsListener.contains("wakeNow"));
        // the hub is taken only when the output is a group
        assertTrue(methodBody(volumes, "private void attachIfGroup()").contains("if (!isGroupMode())"));
        assertEquals("only attachIfGroup makes the hub", 1, count(volumes.substring(volumes.indexOf("private static final class GroupSubscription")),
                "SnapcastHub.getShared(app)"));
    }

    @Test
    public void theChangeCheckOfTheRenderingControlServiceReadsWithoutWaking() throws Exception {
        String rendering = source("upnp/server/renderingcontrol/YaaccAudioRenderingControlService.java");
        String check = methodBody(rendering, "void onSystemVolumeChanged()");

        assertTrue(check.contains("volume.peekMuted()") && check.contains("volume.peekPercent()"));
        assertFalse(check.contains("volume.isMuted()") || check.contains("volume.getPercent()"));
        assertTrue(methodBody(rendering, "private void startWatching()").contains("volume.wakeNow()"));
    }

    @Test
    public void theRowOfTheOwnRendererFollowsTheOutputAndTheRowOfThisPhoneDoesNot() throws Exception {
        String client = source("upnp/UpnpClient.java");

        assertTrue(methodBody(client, "RendererVolume volumeOf(Device<?, ?, ?> device)").contains("device instanceof LocalDevice ? rendererVolume()"));
        for (String method : new String[]{"public boolean getMute(Device<?, ?, ?> device)", "public void setMute(Device<?, ?, ?> device, boolean mute)",
                "public int getVolume(Device<?, ?, ?> device)", "public void setVolume(Device<?, ?, ?> device, int volume)"}) {
            assertTrue(method, methodBody(client, method).contains("volumeOf(device)"));
        }
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

    // ---- Fix Group 9c S11, S15, S16

    @Test
    public void creatingAndFindingThePlayerUseTheSameTypeRule() throws Exception {
        String client = source("upnp/UpnpClient.java");
        String create = methodBody(client, "public List<Player> initializePlayers(AvTransport transport)");
        String find = methodBody(client, "public List<Player> getCurrentPlayers(AvTransport transport)");

        assertTrue(create.contains("ContentTypes.pick("));
        assertTrue(find.contains("ContentTypes.pick("));
        assertFalse("no second rule for the extension", find.contains("getMimeTypeFromExtension"));
        assertFalse("the unused local is gone", find.contains("String fileExtension"));
    }

    @Test
    public void aSwitchToTheGroupWhileSubscribedAttachesTheHubAndWakesOnce() throws Exception {
        String volumes = source("upnp/server/renderingcontrol/RendererVolumes.java");
        String subscription = volumes.substring(volumes.indexOf("this.setting = (changed, key)"), volumes.indexOf("Runnable start()"));

        assertTrue(subscription.contains("attachIfGroup()"));
        assertTrue(subscription.contains("forContext(app).wakeNow()"));
        assertTrue(subscription.contains("onChange.run()"));
        assertTrue("the start attaches too (the output may be the group already)", methodBody(volumes, "Runnable start()").contains("attachIfGroup()"));
    }

    @Test
    public void theOpenHomePollReadsWithoutWakingAndTheRendererWakesOnceAtItsStart() throws Exception {
        String host = source("upnp/server/openhome/AndroidOpenHomeHost.java");
        String renderer = source("upnp/server/openhome/OpenHomeRenderer.java");

        assertTrue(methodBody(host, "public int percent()").contains("peekPercent()"));
        assertTrue(methodBody(host, "public boolean muted()").contains("peekMuted()"));
        assertTrue(methodBody(host, "public void wakeNow()").contains("systemVolume.wakeNow()"));
        assertEquals("the renderer wakes in start() only", 1, count(renderer, "volume.wakeNow()"));
    }
}
