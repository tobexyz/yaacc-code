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
 * Tasks 12.4 part 2, 12.10, 12.11, 12.16 (Group 12C run 2): the parts that need Android (the cards, the group sheet, the service, the
 * dialog) have no JVM test, so this pins how they are wired to the pure classes: the power button only on the card and the row of the
 * phone's own client, one id for all servers, the server rule, no new component or permission, nothing logged with an address or an id,
 * the texts in every locale. Plain JVM: sources (comments stripped) and resources are read from the module directory.
 */
public class SnapcastOwnClientGuardTest {

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

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    // ---- the button only for the own client ------------------------------------------------------------------------------

    @Test
    public void theCardOfAGroupHasTheButtonOnlyThroughTheOwnClientRule() throws Exception {
        String binder = source("browser/SnapcastRowBinder.java");
        String bind = methodBody(binder, "void bindPower(");

        assertTrue("the rule decides", bind.contains("SnapcastOwnClient.cardOffersPower("));
        assertTrue("with the app's one client id, known without a session", bind.contains("SnapcastClientIdentities.ownId(context)"));
        assertTrue(bind.contains("SnapcastOwnPower.of(own, own &&"));
        assertTrue("the same button, look and tap as the other kinds", bind.contains("RendererPowerButton.bind("));
        assertTrue("busy while a change runs", bind.contains("RendererPowerController.getShared().isBusy(id)"));
        assertTrue("the tap goes through the one toggle", bind.contains("SnapcastUi.toggleOwnPower("));
        assertTrue("the state follows the session with every show (two seconds)", methodBody(binder, "void show(").contains("bindPower(views, device)"));

        String rows = source("browser/ReceiverRowBinder.java");
        String card = methodBody(rows, "private void bindPower(");
        assertTrue("a Snapcast card is the Snapcast binder's", card.indexOf("snapcastRows.bindPower(holder, (SnapcastGroupDevice) device)") >= 0);
        assertTrue("and never gets a MusicCast or OpenHome button", card.indexOf("snapcastRows.bindPower(") < card.indexOf("MusicCastPower.of("));
    }

    @Test
    public void everyTapOnTheOwnPowerGoesThroughOneMethodAndTheOneController() throws Exception {
        String ui = source("browser/SnapcastUi.java");

        String toggle = methodBody(ui, "static void toggleOwnPower(");
        assertTrue(toggle.contains("RendererPowerController.getShared().toggle("));
        assertTrue(toggle.contains("SnapcastOwnPower.source("));
        assertTrue("a refusal has its own reason", toggle.contains("SnapcastClientPowerTexts.refusalText("));
        assertTrue(toggle.contains("RendererPowerLook.errorText("));
        for (String file : new String[]{"browser/SnapcastRowBinder.java", "browser/SnapcastGroupSheet.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("SnapcastOwnPower.source("));
            assertFalse(file + ": no direct switch of the client from a card", text.contains(".setOn("));
            assertFalse(file, text.contains("RendererPowerToggle.toggle("));
        }
        assertEquals("the sheet and the card are the only taps", 2, count(source("browser/SnapcastRowBinder.java"), "SnapcastUi.toggleOwnPower(")
                + count(source("browser/SnapcastGroupSheet.java"), "SnapcastUi.toggleOwnPower("));
    }

    @Test
    public void theSheetGivesTheToggleAndTheServerMenuToTheOwnRowOnly() throws Exception {
        String sheet = source("browser/SnapcastGroupSheet.java");

        assertTrue("the members are marked by the own client id", sheet.contains("SnapcastMemberRows.of(group, SnapcastUi.memberWords(context), SnapcastClientIdentities.ownId(context))"));
        String row = methodBody(sheet, "private View memberRow(");
        assertTrue(row.contains("if (member.isOwn())") && row.contains("ownPowerButton(member)"));
        assertTrue("labelled", row.contains("R.string.snapcast_member_this_phone"));
        assertEquals("one place makes the toggle", 1, count(sheet, "ownPowerButton(member)"));
        assertTrue(methodBody(sheet, "private ImageButton ownPowerButton(").contains("RendererPowerButton.bind("));
        assertTrue("the same symbol as the card", methodBody(sheet, "private ImageButton ownPowerButton(").contains("R.drawable.ic_baseline_power_settings_new_32"));

        String menu = methodBody(sheet, "private void showMemberMenu(");
        assertTrue("Server only for the own row", menu.contains("if (member.isOwn())")
                && menu.indexOf("if (member.isOwn())") < menu.indexOf("R.string.snapcast_member_server"));
        assertTrue(menu.contains("showServerChoice()"));
        assertTrue("the removal rule asks with the derived id", menu.contains("SnapcastUi.ownClient(context)"));
    }

    // ---- the server of the client ----------------------------------------------------------------------------------------

    @Test
    public void theServerOfTheClientIsDecidedByTheRuleAndNotBySavedListenTestAddress() throws Exception {
        String powers = source("multiroom/client/SnapcastClientPowers.java");
        String host = methodBody(powers, "public String host()");

        assertTrue(host.contains("currentServer(app)"));
        assertFalse("not the address that the listen test saved", host.contains("KEY_LAST_HOST"));
        String current = methodBody(powers, "public static SnapcastServerChoices.Choice currentServer(");
        assertTrue(current.contains("SnapcastClientServerChoice.resolve(") && current.contains("KEY_LAST_SERVER"));
        assertTrue("the known servers are the ones of the server list", methodBody(powers, "public static List<SnapcastServerChoices.Choice> knownServers(")
                .contains("SnapcastServerChoices.of(SnapcastHub.getShared(app).newServerListModel().rows()"));
    }

    @Test
    public void theLastServerIsWrittenAtThreePlacesOnly() throws Exception {
        String service = source("multiroom/client/SnapcastClientService.java");
        String remember = methodBody(service, "private void rememberServer()");
        assertTrue(remember.contains("KEY_LAST_SERVER") && remember.contains("serverRemembered.compareAndSet(false, true)"));
        // after the phase RUNNING, at the two places that set it
        Matcher running = Pattern.compile("Phase\\.RUNNING, SnapcastClientRuntime\\.Ending\\.NONE, startedAtMillis\\);\\s*rememberServer\\(\\);").matcher(service);
        int places = 0;
        while (running.find()) {
            places++;
        }
        assertEquals(2, places);
        assertEquals(1, count(service, "KEY_LAST_SERVER"));

        assertTrue(methodBody(source("settings/SnapcastClientSpikeDialog.java"), "private void onStart()").contains("KEY_LAST_SERVER"));
        assertTrue(source("multiroom/client/SnapcastClientPowers.java").contains("preferences.edit().putString(SnapcastClientSpike.KEY_LAST_SERVER, hostText)"));
    }

    @Test
    public void thereIsNoAutomaticFailoverAndTheChoiceIsMadeByTheUserOnly() throws Exception {
        for (String file : new String[]{"multiroom/client/SnapcastClientService.java", "multiroom/client/SnapcastClientRestore.java",
                "Yaacc.java", "browser/SnapcastRowBinder.java"}) {
            assertFalse(file, source(file).contains("chooseServer("));
        }
        assertEquals("only the sheet's menu calls it", 1, count(source("browser/SnapcastGroupSheet.java"), "chooseServer("));
        String power = source("multiroom/client/SnapcastClientPower.java");
        assertEquals("the pure rule is the only decision", 1, count(power, "SnapcastClientServerSwitch.decide("));
    }

    @Test
    public void everyStartChecksTheAddressAgainAndARestartWaitsBounded() throws Exception {
        String power = source("multiroom/client/SnapcastClientPower.java");
        assertTrue("the choice is validated like a typed address", methodBody(power, "public Outcome chooseServer(").contains("SnapcastClientSpike.check("));
        assertTrue("every start", methodBody(power, "private Outcome startSession()").contains("SnapcastClientSpike.check(host,"));

        String powers = source("multiroom/client/SnapcastClientPowers.java");
        String step = methodBody(powers, "private void step()");
        assertTrue(step.contains("SnapcastClientServerSwitch.waitStep("));
        assertTrue("the old session and the old service instance are gone", step.contains("!SnapcastClientRuntime.isActive() && !SnapcastClientRuntime.isServiceAlive()"));
        assertTrue("one poll on the main thread, cancelled by a stop", powers.contains("main.removeCallbacks(poll)"));
        assertTrue(methodBody(powers, "public void stop()").contains("restarter.cancel()"));

        String service = source("multiroom/client/SnapcastClientService.java");
        assertTrue(methodBody(service, "public void onCreate()").contains("SnapcastClientRuntime.serviceCreated()"));
        assertTrue(methodBody(service, "public void onDestroy()").contains("SnapcastClientRuntime.serviceDestroyed()"));
    }

    // ---- one id for all servers ------------------------------------------------------------------------------------------

    @Test
    public void theHelloIdIsDerivedFromTheSecretOnlyAndNobodyAsksForAServerId() throws Exception {
        String service = source("multiroom/client/SnapcastClientService.java");

        assertTrue(service.contains("SnapcastClientIdentity.deriveId(installSecret())"));
        assertFalse("no server text goes into the id any more", service.contains("SnapcastServerKey"));
        assertFalse(service.contains("deriveId(installSecret(),"));
        String identity = source("multiroom/client/SnapcastClientIdentity.java");
        assertFalse(identity.contains("serverKey"));
        assertTrue("the fixed MAC and the host name rule are untouched", source("multiroom/client/SnapcastClientSpike.java")
                .contains("return localityOf(endpoint) == Locality.PUBLIC ? \"YAACC\" : phoneName;"));
        assertTrue(service.contains("SnapcastClientSpike.helloHostName(address, phoneName())"));
    }

    @Test
    public void theOwnIdIsReadWithoutCreatingAnythingAndNeverLogged() throws Exception {
        String identities = source("multiroom/client/SnapcastClientIdentities.java");

        assertFalse("reading does not make a secret", identities.contains("putString") || identities.contains("edit()"));
        for (String file : new String[]{"multiroom/client/SnapcastClientIdentities.java", "multiroom/client/SnapcastClientIdentity.java",
                "multiroom/ui/SnapcastOwnClient.java", "multiroom/ui/SnapcastClientServerChoice.java",
                "multiroom/client/SnapcastClientServerSwitch.java", "browser/SnapcastOwnPower.java"}) {
            String text = source(file);
            assertFalse(file, text.contains("YaaccLogger") || text.contains("android.util.Log") || text.contains("System.out")
                    || text.contains("getMessage()"));
        }
    }

    // ---- no new component or permission; the engine and the protocol are not part of it -------------------------------------

    @Test
    public void theManifestNamesNothingOfThisRun() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");

        for (String name : new String[]{"SnapcastOwn", "SnapcastClientServer", "SnapcastClientIdentit", "SnapcastClientPowers"}) {
            assertFalse(name, manifest.contains(name));
        }
        int at = manifest.indexOf(".multiroom.client.SnapcastClientService");
        String declaration = manifest.substring(manifest.lastIndexOf("<service", at), manifest.indexOf("/>", at));
        assertTrue("the one service stays not exported", declaration.contains("android:exported=\"false\""));
        assertEquals("one client service", 1, count(manifest, ".multiroom.client.SnapcastClientService"));
    }

    // ---- the texts -----------------------------------------------------------------------------------------------------------

    @Test
    public void theNewTextsExistInEveryLocale() throws Exception {
        String[] needed = {"snapcast_member_this_phone", "snapcast_member_server", "snapcast_server_choose_title"};
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            for (String name : needed) {
                assertTrue(locale + " lacks " + name, Pattern.compile("<string name=\"" + name + "\">[^<]+</string>").matcher(xml).find());
            }
        }
    }

    @Test
    public void theDisclosureTextSaysTheIdIsTheSameOnAllServers() throws Exception {
        // a changed text must differ from the English original in the other locales, and the no-server reason no longer sends the user to the listen test only
        String english = read("src/main/res/values/strings.xml");
        assertTrue(english.contains("a generated id, which is the same on all servers"));
        for (String locale : LOCALES) {
            String xml = read("src/main/res/" + locale + "/strings.xml");
            Matcher note = Pattern.compile("<string name=\"snapcast_client_note\">([^<]+)</string>").matcher(xml);
            assertTrue(locale, note.find());
            Matcher none = Pattern.compile("<string name=\"snapcast_client_refused_no_server\">([^<]+)</string>").matcher(xml);
            assertTrue(locale, none.find());
            if (!locale.equals("values")) {
                Matcher en = Pattern.compile("<string name=\"snapcast_client_note\">([^<]+)</string>").matcher(english);
                assertTrue(en.find());
                assertFalse(locale + " still has the English note", note.group(1).equals(en.group(1)));
            }
        }
    }
}
