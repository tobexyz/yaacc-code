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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Source guards for task 12.21: the parts that need a running HTTP server or Android have no JVM test, so these pin how they
 * are wired to the pure decisions ({@code ProxyAvailability}, {@code ServerRequestGate}). Comment lines are stripped first (whole lines only: a string such as "image/*" must survive).
 */
public class ProxySwitchGuardTest {

    private static final File MAIN = new File("src/main");
    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String text(String relativePath) throws Exception {
        File file = new File(MAIN, relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String code(String relativePath) throws Exception {
        return text("java/de/yaacc/" + relativePath).replaceAll("(?m)^\\s*/?\\*.*$", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String body(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    private static void assertInOrder(String text, String... markers) {
        int last = -1;
        for (String marker : markers) {
            int at = text.indexOf(marker, last + 1);
            assertTrue("missing or out of order: " + marker, at > last);
            last = at;
        }
    }

    @Test
    public void theMasterSwitchIsHandedToTheShareResponderAndTheGateDecidesPerEntryKindAfterTheLookup() throws Exception {
        String handle = body(code("upnp/server/http/YaaccUpnpServerContentHttpHandler.java"), "public void handle(");
        assertInOrder(handle, "settings_local_server_chkbx", "new ProxyShareResponder(", "masterOn");
        assertFalse("the share path is not gated by the master switch alone: shared files must work without it",
                handle.contains("ServerRequestGate.mayServeShares("));
        String respond = body(code("upnp/server/http/ProxyShareResponder.java"), "void respond(");
        assertInOrder(respond, "registry.lookup(key)", "ServerRequestGate.maySendShare(masterOn, entry.getKind())",
                "SC_FORBIDDEN", "ProxyAccessPolicy.isAllowed(");
    }

    @Test
    public void theHttpServerDoesNotLookAtTheMasterSwitchAndTheClientKeepsTheServiceBound() throws Exception {
        // normal sharing (files and direct links) works without the master switch because the HTTP server of the service
        // lives as long as the service: it is created by initialize() without reading the switch, and a service that is
        // bound (BIND_AUTO_CREATE by the client, for the life of the process) does not end when LocalServerLifecycle stops it
        String service = code("upnp/server/YaaccUpnpServerService.java");
        assertFalse(body(service, "private void initialize()").contains("settings_local_server_chkbx"));
        assertFalse(body(service, "private void createHttpServer()").contains("settings_local_server_chkbx"));
        assertTrue(body(code("upnp/UpnpClient.java"), "public boolean bindServerService()").contains("BIND_AUTO_CREATE"));
        assertTrue(code("upnp/server/LocalServerLifecycle.java").contains("stopService("));
    }

    @Test
    public void theContentPathsAreOpenOnlyThroughTheGate() throws Exception {
        String handle = body(code("upnp/server/http/YaaccUpnpServerContentHttpHandler.java"), "public void handle(");
        assertTrue(handle.contains("ServerRequestGate.contentServerOpen("));
        assertTrue(handle.contains("ServerRequestGate.contentProxyOpen("));
        assertFalse("the provider switch must not be read bare", handle.matches("(?s).*boolean contentServerEnabled = preferences\\.getBoolean.*"));
        assertFalse("the proxy switch must not be read bare", handle.matches("(?s).*boolean contentProxyEnabled = preferences\\.getBoolean.*"));
    }

    @Test
    public void theSharedLinkDecisionComesFromTheOnePureClassAndBeforeAnyNetworkWork() throws Exception {
        String client = code("upnp/UpnpClient.java");
        String create = body(client, "public PlayableItem createPlayableItem(Uri uri, Set<String> receiverIds)");
        assertInOrder(create, "linkProxyDecision()", "REFUSE_NO_SERVER", "return null", "readLinkMetadata(");
        assertFalse("the proxy checkbox is no longer read bare in createPlayableItem",
                create.contains("settings_local_server_proxy_chkbx"));
        String decision = body(client, "public ProxyAvailability.Decision linkProxyDecision()");
        assertTrue(decision.contains("ProxyAvailability.decide("));
        assertTrue(decision.contains("settings_local_server_chkbx"));
        assertTrue(decision.contains("settings_local_server_proxy_chkbx"));
    }

    @Test
    public void theHandlerAsksTheDecisionBeforeItBuildsTheItemOnBothPaths() throws Exception {
        String handler = code("browser/ShareIntentHandler.java");
        assertInOrder(body(handler, "private void prepareAndPlayLink("), "linkProxyDecision()", "createPlayableItem(");
        assertInOrder(body(handler, "private void prepareAndPlay(Uri uri)"), "linkProxyDecision()", "createPlayableItem(");
    }

    @Test
    public void theRefusalMessageExistsInAllLocalesAndNamesTheSwitch() throws Exception {
        for (String locale : LOCALES) {
            String xml = text("res/" + locale + "/strings.xml");
            assertTrue(locale + " has share_link_needs_server", xml.contains("name=\"share_link_needs_server\""));
        }
    }
}
