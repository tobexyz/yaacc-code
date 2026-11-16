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
package de.yaacc.browser;

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
 * Pins what only the device could show about removing an offline client from a Snapcast server (task 12.8): the action is in the
 * member menu of the group sheet, behind a confirmation, the decision uses a fresh read and the own client, nothing is logged,
 * the texts exist in every locale. Plain JVM: the sources and resources are read (comments stripped) from the module directory.
 */
public class SnapcastClientRemovalWiringTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};

    private static String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String source(String relativePath) throws Exception {
        return read("src/main/java/de/yaacc/" + relativePath).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
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

    @Test
    public void theMemberMenuOffersTheRemovalOnlyThroughTheDecisionAndAsksFirst() throws Exception {
        String sheet = source("browser/SnapcastGroupSheet.java");
        String menu = methodBody(sheet, "private void showMemberMenu(");

        assertTrue(menu.contains("SnapcastClientRemoval.offered(member.isConnected(), member.getClientId(), SnapcastUi.ownClient())"));
        assertTrue(menu.contains("showDeleteOffline(member)"));
        assertEquals("the delete is called in one place: the confirmation", 1, sheet.split("deleteOfflineClient\\(", -1).length - 1);
        String confirm = methodBody(sheet, "private void showDeleteOffline(");
        assertTrue(confirm.contains("setPositiveButton(R.string.snapcast_client_delete_confirm"));
        assertTrue(confirm.contains("deleteOfflineClient(serverKey, clientId)"));
        assertTrue("the name is the cleaned display name of the row", confirm.contains("member.getName()"));
    }

    @Test
    public void theControlDecidesFromAFreshReadAndTheOwnClientAndNeverFromTheModel() throws Exception {
        String controls = source("multiroom/ui/SnapcastControls.java");
        String body = methodBody(controls, "public void deleteOfflineClient(");

        assertTrue(body.contains("operations.getStatus()"));
        assertFalse("the rate limited model is not a fresh status", body.contains("freshStatusOrModel"));
        assertFalse(body.contains("linked.status"));
        assertTrue(body.contains("SnapcastClientRemoval.decide(fresh, clientId, own.own())"));
        assertEquals("the call is made in one place, in the case of a removable client", 1,
                controls.split("operations\\.deleteClient\\(", -1).length - 1);
        assertTrue(body.indexOf("operations.getStatus()") < body.indexOf("operations.deleteClient("));
    }

    @Test
    public void theRemovalIsOnlyInTheOperationsAndNothingOfItIsLogged() throws Exception {
        for (String file : new String[]{"multiroom/ui/SnapcastControls.java", "multiroom/ui/SnapcastClientRemoval.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("YaaccLogger") || text.contains("android.util.Log") || text.contains("System.out"));
        }
        assertTrue(source("multiroom/control/SnapcastOperations.java").contains("\"Server.DeleteClient\""));
        assertEquals("the method name is in one place", 0, source("multiroom/ui/SnapcastControls.java").split("Server\\.DeleteClient", -1).length - 1);
    }

    @Test
    public void theAppTellsTheControlsWhichClientIsItsOwn() throws Exception {
        String ui = source("browser/SnapcastUi.java");
        String service = source("multiroom/client/SnapcastClientService.java");

        assertTrue(methodBody(ui, "static SnapcastClientRemoval.Own ownClient()").contains(
                "SnapcastClientRemoval.ownOf(SnapcastClientRuntime.isActive(), SnapcastClientRuntime.clientId())"));
        assertTrue(ui.contains("SnapcastUi::ownClient"));
        assertTrue("the Hello id is published as soon as it is built", service.contains("SnapcastClientRuntime.clientId(hello.getId())"));
        assertTrue(service.indexOf("SnapcastClientRuntime.clientId(hello.getId())") < service.indexOf("startServerPoll(address, hello.getId())"));
    }

    @Test
    public void theTextsExistInEveryLocaleAndTheMessageNamesTheClient() throws Exception {
        String[] needed = {"snapcast_client_delete_menu", "snapcast_client_delete_title", "snapcast_client_delete_message",
                "snapcast_client_delete_confirm", "snapcast_remove_error_connected", "snapcast_remove_error_own"};
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            for (String name : needed) {
                Matcher matcher = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + name, matcher.find());
                if (name.equals("snapcast_client_delete_message")) {
                    assertTrue(locale, matcher.group(1).contains("%1$s"));
                }
            }
        }
    }
}
