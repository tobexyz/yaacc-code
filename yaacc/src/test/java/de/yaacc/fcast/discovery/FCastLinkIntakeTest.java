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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.fcast.discovery.FCastLinkIntake.Kind;

/**
 * Task 5.6: what the activity for {@code fcast://r/} links accepts and shows, and a guard of the manifest: the activity is the only new
 * component, it is exported for links of this one scheme and host, and the app has no camera permission.
 */
public class FCastLinkIntakeTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final FCastLinkIntake intake = new FCastLinkIntake(now::get);

    private static String link(String ip) {
        String json = "{\"name\":\"FCast-pc\",\"addresses\":[\"" + ip + "\"],\"services\":[{\"port\":46899,\"type\":0}]}";
        return FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aGoodLinkAsksForConfirmationAndNothingElse() {
        FCastLinkIntake.Decision decision = intake.accept(link("192.168.0.2"));

        assertEquals(Kind.CONFIRM, decision.getKind());
        assertEquals("FCast-pc", decision.getParsed().getName());
        assertEquals(link("192.168.0.2"), decision.getLink());
    }

    @Test
    public void aLinkThatIsNoFCastLinkOrIsDamagedSaysNotALinkAndNothingElse() {
        for (String data : new String[]{null, "", "http://example.org/", "fcast://x/abc", FCastConnectionUrl.PREFIX + "!!!", FCastConnectionUrl.PREFIX,
                link("8.8.8.8"), "content://media/x", "file:///sdcard/x"}) {
            FCastLinkIntake.Decision decision = intake.accept(data);
            assertEquals(String.valueOf(data), Kind.NOT_A_LINK, decision.getKind());
            assertNull(decision.getParsed());
            assertNull(decision.getLink());
        }
    }

    @Test
    public void theSameLinkAgainWithinTwoSecondsIsIgnoredAndAfterwardsItAsksAgain() {
        assertEquals(Kind.CONFIRM, intake.accept(link("192.168.0.2")).getKind());

        now.addAndGet(FCastLinkIntake.REPEAT_MILLIS - 1);
        assertEquals(Kind.IGNORE, intake.accept(link("192.168.0.2")).getKind());
        now.addAndGet(FCastLinkIntake.REPEAT_MILLIS - 1);
        assertEquals("the ignored repeat did not extend the time", Kind.CONFIRM, intake.accept(link("192.168.0.2")).getKind());
    }

    @Test
    public void aDifferentLinkIsNotARepeat() {
        assertEquals(Kind.CONFIRM, intake.accept(link("192.168.0.2")).getKind());
        assertEquals(Kind.CONFIRM, intake.accept(link("192.168.0.3")).getKind());
    }

    @Test
    public void aBadLinkDoesNotStartTheRepeatWindow() {
        assertEquals(Kind.NOT_A_LINK, intake.accept(FCastConnectionUrl.PREFIX + "!!!").getKind());
        assertEquals(Kind.CONFIRM, intake.accept(link("192.168.0.2")).getKind());
    }

    // ---- the text of the dialog

    private static final FCastLinkText.Words WORDS = new FCastLinkText.Words() {
        @Override
        public String name(String name) {
            return "Name: " + name;
        }

        @Override
        public String unnamed() {
            return "(no name)";
        }

        @Override
        public String addresses() {
            return "Addresses:";
        }

        @Override
        public String note(boolean withKey) {
            return withKey ? "NOTE WITH KEY" : "NOTE";
        }
    };

    @Test
    public void theDialogNamesTheReceiverItsAddressesAndTheNote() {
        String message = FCastLinkText.message(intake.accept(link("192.168.0.2")).getParsed(), WORDS);

        assertEquals("Name: FCast-pc\n\nAddresses:\n  192.168.0.2:46899\n\nNOTE", message);
    }

    @Test
    public void aLinkThatCarriesTheKeyGetsTheNoteOfTheEncryptedProtocol() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String json = "{\"name\":\"FCast-pc\",\"addresses\":[\"192.168.0.2\"],\"services\":[{\"port\":46899,\"type\":0}],\"txt\":{\"v\":\"4\",\"fp\":\"" + key + "\"}}";
        String data = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertTrue(FCastLinkText.message(intake.accept(data).getParsed(), WORDS).endsWith("NOTE WITH KEY"));
    }

    @Test
    public void aKeyThatIsNotAKeyDoesNotChangeTheNote() {
        String json = "{\"name\":\"FCast-pc\",\"addresses\":[\"192.168.0.2\"],\"services\":[{\"port\":46899,\"type\":0}],\"txt\":{\"fp\":\"short\"}}";
        String data = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertTrue(FCastLinkText.message(intake.accept(data).getParsed(), WORDS).endsWith("\nNOTE"));
    }

    @Test
    public void aReceiverWithoutANameIsShownAsUnnamed() {
        String json = "{\"addresses\":[\"192.168.0.2\",\"10.0.0.2\"],\"services\":[{\"port\":1234,\"type\":0}]}";
        String data = FCastConnectionUrl.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        String message = FCastLinkText.message(intake.accept(data).getParsed(), WORDS);

        assertTrue(message, message.startsWith("Name: (no name)\n"));
        assertTrue(message.contains("  192.168.0.2:1234\n  10.0.0.2:1234\n"));
    }

    // ---- the manifest

    private static String manifest() throws Exception {
        return new String(Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theLinkActivityIsDeclaredOnceExportedForTheLinkSchemeAndHostOnly() throws Exception {
        String manifest = manifest();
        Matcher activity = Pattern.compile("<activity[^>]*FCastLinkActivity[^>]*>.*?</activity>", Pattern.DOTALL).matcher(manifest);

        assertTrue("declared", activity.find());
        String declaration = activity.group();
        assertFalse("once", activity.find());
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains("android.intent.action.VIEW"));
        assertTrue(declaration.contains("android.intent.category.DEFAULT"));
        assertTrue(declaration.contains("android.intent.category.BROWSABLE"));
        assertTrue(declaration.contains("android:scheme=\"fcast\""));
        assertTrue(declaration.contains("android:host=\"r\""));
        assertTrue(declaration.contains("android:launchMode=\"singleTop\""));
        assertFalse("no other scheme or action", declaration.contains("android.intent.action.SEND") || declaration.contains("android:scheme=\"http"));
    }

    @Test
    public void theAppHasNoCameraPermissionAndNoCameraFeatureRequirement() throws Exception {
        String manifest = manifest();

        assertFalse(manifest.contains("android.permission.CAMERA"));
        assertFalse(manifest.contains("android.hardware.camera\" android:required=\"true\""));
    }
}
