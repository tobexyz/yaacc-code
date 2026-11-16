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
import java.util.regex.Pattern;

/**
 * Task 12.19 as sources (plain JVM): how the old per-server entries of this install are wired. The merge of the lists gets the ids from
 * the receivers; the cleanup action reads the FULL status of the server (the lists hide exactly these entries), only for the own row,
 * and deletes through the existing removal rules; nothing logs an id; the secret is never created by reading; the texts exist in every
 * locale. The parts that need Android have no JVM test, so this pins how they are wired to the pure classes.
 */
public class SnapcastLegacyOwnGuardTest {

    private static final String[] LOCALES = {"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"};
    private static final String[] KEYS = {"snapcast_member_this_phone_old", "snapcast_legacy_delete_menu", "snapcast_legacy_delete_title",
            "snapcast_legacy_delete_message", "snapcast_legacy_delete_confirm"};

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

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    // ---- the merge gets the ids ------------------------------------------------------------------------------------------

    @Test
    public void theMergeOfTheListsIsGivenTheOwnIdsOfTheKnownServers() throws Exception {
        String receivers = source("multiroom/SnapcastReceivers.java");

        assertTrue(methodBody(receivers, "private SnapcastMergedClients mergedLocked(").contains("SnapcastMergedClients.of(servers, ownIdsOf(entries))"));
        assertEquals("one place builds the merge", 1, count(receivers, "SnapcastMergedClients.of("));
        assertTrue("the ids come from the endpoints of the listed servers", methodBody(receivers, "private SnapcastOwnIds ownIdsOf(")
                .contains("entry.getKey().getEndpoint()"));
        assertTrue("a damaged store never takes the lists down", methodBody(receivers, "private SnapcastOwnIds ownIdsOf(").contains("catch (RuntimeException"));
        assertTrue(source("multiroom/SnapcastHub.java").contains("receivers.setOwnIdsSource(servers -> SnapcastClientIdentities.ownIds(this.context, servers))"));
    }

    @Test
    public void readingTheIdsNeverCreatesTheSecretAndNothingIsLogged() throws Exception {
        String identities = source("multiroom/client/SnapcastClientIdentities.java");
        String ownIds = methodBody(identities, "public static synchronized SnapcastOwnIds ownIds(");

        assertFalse("reading does not make a secret", identities.contains("putString") || identities.contains("edit()"));
        assertTrue("without a secret nothing is own", ownIds.contains("!SnapcastClientIdentity.isSecret(secret)") && ownIds.contains("SnapcastOwnIds.none()"));
        assertTrue(ownIds.contains("SnapcastLegacyClientIds.of(secret, servers, lastHost)"));
        for (String file : new String[]{"multiroom/client/SnapcastClientIdentities.java", "multiroom/client/SnapcastLegacyClientIds.java",
                "multiroom/client/SnapcastOwnIds.java", "multiroom/ui/SnapcastLegacyCleanup.java", "multiroom/SnapcastMergedClients.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("YaaccLogger") || text.contains("android.util.Log") || text.contains("System.out")
                    || text.contains("getMessage()") || text.contains("printStackTrace"));
        }
    }

    @Test
    public void theLegacyAndCleanupClassesArePure() throws Exception {
        for (String file : new String[]{"multiroom/client/SnapcastLegacyClientIds.java", "multiroom/client/SnapcastOwnIds.java",
                "multiroom/ui/SnapcastLegacyCleanup.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("import android.") || text.contains("Thread") || text.contains("Executor"));
        }
    }

    // ---- the cleanup ---------------------------------------------------------------------------------------------------

    @Test
    public void theCleanupReadsTheFullStatusOfTheServerNotTheFilteredView() throws Exception {
        String sheet = source("browser/SnapcastGroupSheet.java");
        String entries = methodBody(sheet, "private List<String> legacyEntries()");

        assertTrue("the full status: the lists have merged exactly these entries away", entries.contains("state.getStatus()"));
        assertFalse(entries.contains("getVisibleStatus") || entries.contains("getVisibleGroup"));
        assertTrue(entries.contains("SnapcastLegacyCleanup.removable("));
        assertTrue(entries.contains("receivers.ownIds()"));
    }

    @Test
    public void theMenuEntryIsOnTheOwnRowOnlyAndOnlyWhenThereIsSomethingToRemove() throws Exception {
        String menu = methodBody(source("browser/SnapcastGroupSheet.java"), "private void showMemberMenu(");
        int own = menu.indexOf("if (member.isOwn())");
        int entry = menu.indexOf("R.string.snapcast_legacy_delete_menu");

        assertTrue(own >= 0 && entry > own);
        assertTrue("only with offline old entries on this server", menu.substring(own, entry).contains("!legacyEntries().isEmpty()"));
        assertEquals(1, count(menu, "R.string.snapcast_legacy_delete_menu"));
        assertTrue(menu.contains("showDeleteLegacy()"));
    }

    @Test
    public void theDeletionAsksWithTheCountAndGoesThroughTheExistingRemovalRules() throws Exception {
        String sheet = source("browser/SnapcastGroupSheet.java");
        String dialog = methodBody(sheet, "private void showDeleteLegacy()");

        assertTrue("a confirmation that names the count", dialog.contains("R.string.snapcast_legacy_delete_message, count"));
        assertTrue(dialog.contains("setNegativeButton(android.R.string.cancel"));
        assertEquals("one call per id through the one removal path (fresh status, offline only, never the own session, rate limit)", 1,
                count(dialog, "controls.deleteOfflineClient(serverKey, id)"));
        assertFalse("no other call that deletes", dialog.contains("deleteClient(") || dialog.contains("operations"));
        assertTrue("the ids are read again at the tap", dialog.indexOf("for (String id : legacyEntries())") > dialog.indexOf("setPositiveButton("));
        // the controls and the removal rules know nothing of legacy ids: they decide from the fresh status as for any entry
        for (String file : new String[]{"multiroom/ui/SnapcastControls.java", "multiroom/ui/SnapcastClientRemoval.java"}) {
            assertFalse(file, source(file).contains("SnapcastOwnIds") || source(file).contains("SnapcastLegacy"));
        }
        String removal = methodBody(source("multiroom/ui/SnapcastControls.java"), "public void deleteOfflineClient(");
        assertTrue(removal.contains("operations.getStatus()") && removal.contains("SnapcastClientRemoval.decide("));
        assertEquals("the sheet is the only caller of the cleanup", 1, count(sheet, "SnapcastLegacyCleanup.removable("));
    }

    @Test
    public void theOldEntryIsLabelledInTheRowAndShowsNoIdOrName() throws Exception {
        String row = methodBody(source("browser/SnapcastGroupSheet.java"), "private View memberRow(");

        assertTrue(row.contains("member.isLegacy()") && row.contains("R.string.snapcast_member_this_phone_old") && row.contains("R.string.snapcast_member_this_phone"));
    }

    // ---- the texts -----------------------------------------------------------------------------------------------------------

    @Test
    public void theNewTextsExistInEveryLocaleAndTheOthersDifferFromTheEnglishOnes() throws Exception {
        String english = read("src/main/res/values/strings.xml");
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            for (String key : KEYS) {
                java.util.regex.Matcher found = Pattern.compile("<string name=\"" + key + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + key, found.find());
                if (!locale.equals("values") && !key.equals("snapcast_legacy_delete_confirm")) {
                    java.util.regex.Matcher en = Pattern.compile("<string name=\"" + key + "\">([^<]+)</string>").matcher(english);
                    assertTrue(en.find());
                    assertFalse(locale + " still has the English text of " + key, found.group(1).equals(en.group(1)));
                }
                if (key.equals("snapcast_legacy_delete_message")) {
                    assertTrue(locale + ": the count", found.group(1).contains("%1$d"));
                }
            }
        }
    }
}
