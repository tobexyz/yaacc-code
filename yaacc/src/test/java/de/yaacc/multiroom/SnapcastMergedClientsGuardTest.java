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
package de.yaacc.multiroom;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Task 12.12 as sources (plain JVM, the files are read from the module directory): the merge of a client known to several servers is
 * a view for the lists only. The places that show clients read the shown members; no place that changes a server does, so a member
 * the lists hide is never dropped from a group and never removed. The filter itself is pure.
 */
public class SnapcastMergedClientsGuardTest {

    private static String code(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("missing " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String main(String path) throws Exception {
        return code("src/main/java/de/yaacc/" + path + ".java");
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
    public void everyPlaceThatShowsClientsReadsTheShownMembers() throws Exception {
        assertTrue(main("browser/SnapcastUi").contains("state.getVisibleGroup(), state.getVisibleStatus()"));
        String sheet = methodBody(main("browser/SnapcastGroupSheet"), "private void render()");
        assertTrue("the member rows, the title and the counts of the group sheet", sheet.contains("state.getVisibleGroup()")
                && sheet.contains("state.getVisibleStatus()"));
        assertTrue("the link picker lists only the clients this server shows",
                main("browser/SnapcastLinkSheet").contains("SnapcastLinkChoices.of(state.getVisibleStatus(), groupId,"));
        assertTrue("the power button belongs to the card whose first shown member is the phone",
                main("browser/SnapcastRowBinder").contains("SnapcastOwnClient.cardOffersPowerForIds(state == null ? null : state.getVisibleGroup()"));
        String receivers = main("multiroom/SnapcastReceivers");
        assertTrue("the cards and their titles", methodBody(receivers, "public List<SnapcastGroupDevice> groups()").contains("merged.visible("));
        assertTrue(methodBody(receivers, "public GroupState stateOf(").contains("merged.visible("));
        assertTrue(methodBody(receivers, "public SnapcastGroupDevice findGroup(").contains(".visible("));
    }

    @Test
    public void noPlaceThatChangesAServerKnowsTheFilter() throws Exception {
        for (String file : new String[]{"multiroom/ui/SnapcastControls", "multiroom/ui/SnapcastClientCandidates",
                "multiroom/ui/SnapcastClientRemoval", "multiroom/control/SnapcastOperations", "multiroom/ui/SnapcastLinkChoices"}) {
            String text = main(file);
            assertFalse(file, text.contains("SnapcastMergedClients"));
            assertFalse(file, text.contains("getVisibleGroup") || text.contains("getVisibleStatus"));
        }
        String controls = main("multiroom/ui/SnapcastControls");
        assertTrue("a change of the members starts from the fresh full status",
                methodBody(controls, "public void changeMembers(").contains("freshStatus(linked)"));
        assertTrue("so does the removal of an offline client", methodBody(controls, "public void deleteOfflineClient(").contains("fresh"));
    }

    @Test
    public void theGroupSheetAndTheRenameReadTheFullGroupOnlyForTheClientTheyChange() throws Exception {
        String sheet = main("browser/SnapcastGroupSheet");

        assertTrue(methodBody(sheet, "private void showRename(").contains("state.getGroup().findClient("));
        assertFalse("nothing in the sheet sends the member list of the shown group", sheet.contains("setGroupClients"));
    }

    @Test
    public void theFilterIsPureAndStartsNothing() throws Exception {
        String merged = main("multiroom/SnapcastMergedClients");

        assertFalse(merged.contains("android."));
        assertFalse(merged.contains("Thread") || merged.contains("Executor") || merged.contains("Handler"));
        assertFalse("nothing of it is logged", merged.contains("YaaccLogger") || merged.contains("System.out"));
        assertFalse("the filter only reads statuses, it has no way to call a server",
                merged.contains("SnapcastOperations") || merged.contains("SnapcastCaller") || merged.contains("SnapcastServerLink"));
    }

    @Test
    public void theStatusParserReadsLastSeenOnlyAsANumberPair() throws Exception {
        String client = main("multiroom/control/SnapcastClient");

        assertTrue(client.contains("\"lastSeen\"") && client.contains("\"sec\"") && client.contains("\"usec\""));
        assertFalse("a time of the server is never part of the text of a client", client.contains("lastSeenMicros +")
                && client.contains("toString") && client.substring(client.indexOf("public String toString")).contains("lastSeen"));
    }
}
