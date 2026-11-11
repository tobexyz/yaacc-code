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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.R;
import de.yaacc.multiroom.ui.SnapcastControls;

/**
 * The words for a failed change on a Snapcast server: every kind has a text, a refusal shows only the cleaned reason in
 * brackets, and no other kind shows any text of the server. Plain JVM.
 */
public class SnapcastFailureTextsTest {

    private static String text(int id) {
        return "text" + id;
    }

    @Test
    public void everyKindHasItsOwnText() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (SnapcastControls.Failure.Kind kind : SnapcastControls.Failure.Kind.values()) {
            int id = SnapcastFailureTexts.textFor(kind);
            assertNotEquals(kind.name(), 0, id);
            seen.add(id);
        }
        assertEquals("seven kinds, seven texts", SnapcastControls.Failure.Kind.values().length, seen.size());
    }

    @Test
    public void theTextsAreTheRightOnes() {
        assertEquals(R.string.snapcast_feed_error_unreachable, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.UNREACHABLE));
        assertEquals(R.string.snapcast_action_timeout, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.TIMEOUT));
        assertEquals(R.string.snapcast_feed_error_unauthorized, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.UNAUTHORIZED));
        assertEquals(R.string.snapcast_action_rejected, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.REJECTED));
        assertEquals(R.string.snapcast_feed_error_group_gone, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.GONE));
        assertEquals(R.string.snapcast_action_not_possible, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.NOT_POSSIBLE));
        assertEquals(R.string.snapcast_action_failed, SnapcastFailureTexts.textFor(SnapcastControls.Failure.Kind.FAILED));
    }

    @Test
    public void aRefusalNamesTheReasonInBrackets() {
        String message = SnapcastFailureTexts.message(
                new SnapcastControls.Failure(SnapcastControls.Failure.Kind.REJECTED, "tcp is not allowed"),
                SnapcastFailureTextsTest::text);

        assertEquals(text(R.string.snapcast_action_rejected) + " (tcp is not allowed)", message);
    }

    @Test
    public void aRefusalWithoutAReasonHasNoBrackets() {
        String message = SnapcastFailureTexts.message(
                new SnapcastControls.Failure(SnapcastControls.Failure.Kind.REJECTED, ""), SnapcastFailureTextsTest::text);

        assertEquals(text(R.string.snapcast_action_rejected), message);
    }

    @Test
    public void aReasonIsShownOnlyForARefusal() {
        for (SnapcastControls.Failure.Kind kind : SnapcastControls.Failure.Kind.values()) {
            if (kind == SnapcastControls.Failure.Kind.REJECTED) {
                continue;
            }
            String message = SnapcastFailureTexts.message(new SnapcastControls.Failure(kind, "server text"),
                    SnapcastFailureTextsTest::text);
            assertEquals(kind.name(), text(SnapcastFailureTexts.textFor(kind)), message);
        }
    }

    @Test
    public void aReasonThatTheServerSentUncleanedIsCleanedAgain() {
        String message = SnapcastFailureTexts.message(
                new SnapcastControls.Failure(SnapcastControls.Failure.Kind.REJECTED, "do\u200B this\nnow"),
                SnapcastFailureTextsTest::text);

        assertEquals(text(R.string.snapcast_action_rejected) + " (do this?now)", message);
    }

    @Test
    public void theTextsExistInEveryLocale() throws Exception {
        String[] names = {"snapcast_action_timeout", "snapcast_action_rejected", "snapcast_action_failed",
                "snapcast_action_not_possible"};
        for (String locale : new String[]{"values", "values-de", "values-es", "values-fr", "values-nl", "values-pt", "values-zh"}) {
            java.io.File file = new java.io.File("src/main/res/" + locale + "/strings.xml");
            String xml = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String name : names) {
                Matcher matcher = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
                assertTrue(locale + " lacks " + name, matcher.find());
            }
        }
    }
}
