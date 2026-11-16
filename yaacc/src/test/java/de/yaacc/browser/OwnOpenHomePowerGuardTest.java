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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Task 12.13 as sources (plain JVM, the files are read from the module directory): the card of the app's own OpenHome renderer has
 * its power button through the one binder and the one controller; the card and the OpenHome settings switch read and write the same
 * {@code Standby} state of the renderer's Product service, so they agree; the card follows a change of the switch (and the switch what
 * the card did) through a listener that lives only while a list is shown; no new setting, thread or component.
 */
public class OwnOpenHomePowerGuardTest {

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String code(String relativePath) throws Exception {
        return read(relativePath).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String browser(String name) throws Exception {
        return code("src/main/java/de/yaacc/browser/" + name + ".java");
    }

    private static String openhome(String name) throws Exception {
        return code("src/main/java/de/yaacc/upnp/server/openhome/" + name + ".java");
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

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    public void theBinderGivesTheButtonToTheLocalOpenHomeRendererAndToNoOtherLocalCard() throws Exception {
        String bind = methodBody(browser("ReceiverRowBinder"), "private void bindPower(");

        assertTrue(bind.contains("OwnOpenHomePower.offered(device instanceof LocalDevice,"));
        assertTrue("the renderer must be running in OpenHome mode", bind.contains("OwnOpenHomeStandby.shared().isAttached()"));
        assertTrue(bind.contains("OwnOpenHomePower.of(own, OwnOpenHomeStandby.shared().standby())"));
        assertTrue("the dummy device of this phone is not a LocalDevice and stays without", bind.contains("device instanceof LocalDevice"));
        assertTrue("the remote devices keep their rule", bind.contains("!(device instanceof SnapcastGroupDevice) && !isLocalDevice(device)"));
        assertTrue("MusicCast first, then OpenHome, then the own renderer cannot take a remote card",
                bind.indexOf("MusicCastPower.of(") < bind.indexOf("OpenHomePower.offeredBy(device)"));
    }

    @Test
    public void theTapGoesThroughTheOneControllerAndTheOwnSource() throws Exception {
        String binder = browser("ReceiverRowBinder");
        String bind = methodBody(binder, "private void bindPower(");

        assertTrue(bind.contains("OwnOpenHomePower.source(OwnOpenHomePower.app())"));
        assertEquals("one toggle call for every kind", 1, count(bind, "RendererPowerController.getShared().toggle("));
        assertFalse(binder.contains("RendererPowerToggle.toggle("));
        assertTrue("busy while the change runs, like the other cards", bind.contains("RendererPowerController.getShared().isBusy(udn)"));
    }

    @Test
    public void onlyOneWriterOfTheStandbyStateAndTheActionAndThePlayWakeGoThroughIt() throws Exception {
        String core = openhome("OhRendererCore");

        assertEquals("the Standby variable is written in setStandby() only", 1, count(core, "product.set(\"Standby\""));
        assertTrue(methodBody(core, "public void setStandby(").contains("product.set(\"Standby\""));
        assertTrue("the action is the card's writer too", methodBody(core, "private Map<String, OhActionHandler> productHandlers()")
                .contains("setStandby(bool(in.get(\"Value\")))"));
        assertTrue("a play wakes through the same writer, so the card follows", methodBody(core, "private void wake()").contains("setStandby(false)"));
        assertTrue("the standby of the renderer stops the playback in one place", count(core, "playback.stop()") >= 1);
    }

    @Test
    public void theSettingsSwitchAndTheCardAreTheSameState() throws Exception {
        String sheet = browser("OpenHomeSettingsSheet");

        assertTrue("the switch shows 'not in standby'", sheet.contains("powerSwitch(!snapshot.standby, pad)"));
        assertTrue("and writes it with the Standby action", sheet.contains("remote().setStandby(!checked)"));
        String own = browser("OwnOpenHomePower");
        assertTrue("the card shows it with the model of the remote OpenHome cards", own.contains("OpenHomePower.of(offered, standby)"));
        assertTrue(own.contains("setStandby(!on)"));
        assertFalse("no setting of its own: the state is the renderer's", own.contains("SharedPreferences") || own.contains("PreferenceManager"));
        assertFalse(openhome("OwnOpenHomeStandby").contains("SharedPreferences"));
    }

    @Test
    public void theServiceHandsTheRendererToTheHolderAndTakesItBack() throws Exception {
        String service = code("src/main/java/de/yaacc/upnp/server/YaaccUpnpServerService.java");

        assertTrue(methodBody(service, "private List<LocalService<?>> createOpenHomeServices()")
                .contains("OwnOpenHomeStandby.shared().attach(openHomeRenderer.core())"));
        assertTrue("on a failed start and on the end of the service the holder lets go",
                count(service, "OwnOpenHomeStandby.shared().detach(") >= 2);
    }

    @Test
    public void theCardFollowsTheStateOnlyWhileAListIsShownAndStartsNoThread() throws Exception {
        String feed = browser("YxcRowFeed");

        assertTrue(methodBody(feed, "void start(").contains("OwnOpenHomeStandby.shared().addListener("));
        assertTrue(methodBody(feed, "void stop()").contains("OwnOpenHomeStandby.shared().removeListener("));
        assertTrue("the change reaches the same row rebind as the other power events", feed.contains("enqueue("));
        for (String text : new String[]{feed, openhome("OwnOpenHomeStandby"), browser("OwnOpenHomePower")}) {
            assertFalse(text.contains("new Thread(") || text.contains("Executor") || text.contains("Timer"));
        }
    }
}
