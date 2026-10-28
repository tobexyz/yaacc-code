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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import de.yaacc.browser.LauncherForwardPolicy.StreamDecision;

/**
 * Unit tests for {@link LauncherForwardPolicy#decideStream}: when the exported launcher hands ONE
 * {@code content:} URI on with the read grant. The three-argument text decision is covered by
 * {@link LauncherForwardPolicyTest} and must not change. Plain JVM, no Android runtime.
 */
public class LauncherForwardPolicyStreamTest {

    private static final String SEND = "android.intent.action.SEND";
    private static final String VIEW = "android.intent.action.VIEW";
    private static final String OWN = "de.yaacc.fileprovider";
    private static final String URI = "content://com.example.provider/files/song.mp3";
    private static final int READ = 0x1;

    private static StreamDecision decide(String action, String type, String stream, String clip, int flags) {
        return LauncherForwardPolicy.decideStream(action, type, stream, clip, flags, OWN);
    }

    private static StreamDecision happy(String type) {
        return decide(SEND, type, URI, URI, READ);
    }

    private static void assertForwards(StreamDecision decision, String uri) {
        assertTrue(decision.isForwardStream());
        assertEquals(uri, decision.getStreamUri());
        assertTrue(decision.grantsRead());
    }

    private static void assertNoForward(StreamDecision decision) {
        assertFalse(decision.isForwardStream());
        assertNull(decision.getStreamUri());
        assertFalse(decision.grantsRead());
    }

    // ---- happy path

    @Test
    public void readGrantConstantIsTheAndroidValue() {
        assertEquals(1, LauncherForwardPolicy.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Test
    public void sendAudioWithMatchingStreamAndReadGrantForwardsTheUri() {
        assertForwards(happy("audio/mpeg"), URI);
    }

    @Test
    public void mediaStoreUriIsForwarded() {
        String uri = "content://media/external/audio/media/42";

        assertForwards(decide(SEND, "audio/mpeg", uri, uri, READ), uri);
    }

    @Test
    public void otherGrantBitsDoNotPreventTheForwardAsLongAsReadIsThere() {
        int flags = READ | 0x2 /* write */ | 0x40 /* persistable */ | 0x10000000;

        assertForwards(decide(SEND, "audio/mpeg", URI, URI, flags), URI);
        assertForwards(decide(SEND, "audio/mpeg", URI, URI, -1), URI);
    }

    // ---- type: audio, video, image (concrete or the sender's wildcard) only

    @Test
    public void audioVideoAndImageTypesAreForwarded() {
        assertForwards(happy("audio/mpeg"), URI);
        assertForwards(happy("video/mp4"), URI);
        assertForwards(happy("image/png"), URI);
        assertForwards(happy("image/svg+xml"), URI);
        assertForwards(happy("audio/x-ms-wma"), URI);
    }

    @Test
    public void aSendersWildcardOfAudioVideoOrImageIsForwarded() {
        // documented: the real type is checked later via SharedContentPolicy.chooseMimeType
        assertForwards(happy("audio/*"), URI);
        assertForwards(happy("video/*"), URI);
        assertForwards(happy("image/*"), URI);
    }

    @Test
    public void textPlainWithAStreamUriDoesNotForwardTheStream() {
        assertNoForward(happy("text/plain"));
    }

    @Test
    public void textPlainStaysOnTheTextPathOnly() {
        // the stream overload says no; the unchanged three-argument decision still forwards the text
        String text = "https://example.org/song.mp3";

        assertNoForward(decide(SEND, "text/plain", URI, URI, READ));
        assertTrue(LauncherForwardPolicy.decide(SEND, "text/plain", text).isSendText());
        assertEquals(text, LauncherForwardPolicy.decide(SEND, "text/plain", text).getText());
    }

    @Test
    public void otherTypesAreNotForwarded() {
        assertNoForward(happy("application/*"));
        assertNoForward(happy("application/octet-stream"));
        assertNoForward(happy("application/pdf"));
        assertNoForward(happy("text/*"));
        assertNoForward(happy("text/html"));
        assertNoForward(happy("*/*"));
        assertNoForward(happy("multipart/mixed"));
    }

    @Test
    public void missingOrMalformedTypesAreNotForwarded() {
        assertNoForward(happy(null));
        assertNoForward(happy(""));
        assertNoForward(happy(" "));
        assertNoForward(happy("image/"));
        assertNoForward(happy("image"));
        assertNoForward(happy("/png"));
        assertNoForward(happy("audio//mpeg"));
        assertNoForward(happy("audio/mpeg/extra"));
        assertNoForward(happy("audio/mpeg; charset=utf-8"));
        assertNoForward(happy(" audio/mpeg"));
        assertNoForward(happy("audio/mpeg\n"));
        assertNoForward(happy("audiox/mpeg"));
        assertNoForward(happy("xaudio/mpeg"));
    }

    @Test
    public void typeIsMatchedInLowerCaseOnlyLikeTheTextPath() {
        // documented: Intent.setType lower-cases, so another spelling is not forwarded
        assertNoForward(happy("AUDIO/MPEG"));
        assertNoForward(happy("Image/PNG"));
        assertForwards(happy("image/png"), URI);
    }

    // ---- action

    @Test
    public void onlyTheSendActionForwards() {
        assertNoForward(decide(VIEW, "audio/mpeg", URI, URI, READ));
        assertNoForward(decide("android.intent.action.MAIN", "audio/mpeg", URI, URI, READ));
        assertNoForward(decide("android.intent.action.SEND_MULTIPLE", "audio/mpeg", URI, URI, READ));
        assertNoForward(decide("android.intent.action.send", "audio/mpeg", URI, URI, READ));
        assertNoForward(decide("com.evil.ACTION", "audio/mpeg", URI, URI, READ));
        assertNoForward(decide(null, "audio/mpeg", URI, URI, READ));
        assertNoForward(decide("", "audio/mpeg", URI, URI, READ));
        assertForwards(decide(SEND, "audio/mpeg", URI, URI, READ), URI);
    }

    // ---- EXTRA_STREAM must equal ClipData item 0

    @Test
    public void aStreamUriThatDiffersFromClipItemZeroIsNotForwarded() {
        String other = "content://com.example.provider/files/other.mp3";

        assertNoForward(decide(SEND, "audio/mpeg", URI, other, READ));
        assertNoForward(decide(SEND, "audio/mpeg", other, URI, READ));
    }

    @Test
    public void nearlyEqualUrisAreNotEqual() {
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI + "/", READ));
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI + "?x=1", READ));
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI.toUpperCase(), READ));
        assertNoForward(decide(SEND, "audio/mpeg", URI, "content://com.example.provider/files/song.mp3#", READ));
    }

    @Test
    public void aMissingClipItemIsNotForwarded() {
        assertNoForward(decide(SEND, "audio/mpeg", URI, null, READ));
        assertNoForward(decide(SEND, "audio/mpeg", URI, "", READ));
    }

    @Test
    public void aMissingStreamUriIsNotForwarded() {
        assertNoForward(decide(SEND, "audio/mpeg", null, URI, READ));
        assertNoForward(decide(SEND, "audio/mpeg", "", URI, READ));
    }

    @Test
    public void nullOrEmptyOnBothSidesIsNotForwarded() {
        assertNoForward(decide(SEND, "audio/mpeg", null, null, READ));
        assertNoForward(decide(SEND, "audio/mpeg", "", "", READ));
    }

    // ---- the read grant flag

    @Test
    public void withoutTheReadGrantFlagNothingIsForwarded() {
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI, 0));
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI, 0x2));          // write grant only
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI, 0x40));         // persistable only
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI, ~READ));
        assertNoForward(decide(SEND, "audio/mpeg", URI, URI, Integer.MIN_VALUE));
    }

    // ---- the URI itself (SharedContentPolicy.isAcceptableUri)

    @Test
    public void fileUrisAreNotForwarded() {
        String file = "file:///sdcard/Music/a.mp3";

        assertNoForward(decide(SEND, "audio/mpeg", file, file, READ));
        String ownData = "file:///data/data/de.yaacc/shared_prefs/x.xml";
        assertNoForward(decide(SEND, "audio/mpeg", ownData, ownData, READ));
    }

    @Test
    public void ownAuthorityUrisAreNotForwarded() {
        String own = "content://de.yaacc.fileprovider/cache/secret.xml";

        assertNoForward(decide(SEND, "audio/mpeg", own, own, READ));
        String upper = "content://DE.YAACC.FILEPROVIDER/cache/secret.xml";
        assertNoForward(decide(SEND, "audio/mpeg", upper, upper, READ));
        String withUser = "content://0@de.yaacc.fileprovider/cache/secret.xml";
        assertNoForward(decide(SEND, "audio/mpeg", withUser, withUser, READ));
    }

    @Test
    public void httpAndOtherSchemesAreNotForwarded() {
        for (String uri : new String[]{"http://example.com/a.mp3", "https://example.com/a.mp3",
                "ftp://example.com/a.mp3", "android.resource://com.example/raw/a", "javascript:alert(1)",
                "intent://x#Intent;end", "/sdcard/a.mp3"}) {
            assertNoForward(decide(SEND, "audio/mpeg", uri, uri, READ));
        }
    }

    @Test
    public void unacceptableUriTextIsNotForwarded() {
        for (String uri : new String[]{"content://com.example.provider/my song.mp3",
                "content://com.example.provider/a\n.mp3", "content://user@com.example.provider/a.mp3",
                "content://com.example%2Eprovider/a.mp3", "content://", "content:///a.mp3",
                "content://com.example.provider/s\u00f6ng.mp3", " content://com.example.provider/a.mp3"}) {
            assertNoForward(decide(SEND, "audio/mpeg", uri, uri, READ));
        }
    }

    @Test
    public void anOverlongUriIsNotForwarded() {
        StringBuilder sb = new StringBuilder("content://com.example.provider/");
        while (sb.length() <= 2048) {
            sb.append('a');
        }
        String uri = sb.toString();

        assertNoForward(decide(SEND, "audio/mpeg", uri, uri, READ));
    }

    @Test
    public void aMissingOwnAuthorityForwardsNothing() {
        assertNoForward(LauncherForwardPolicy.decideStream(SEND, "audio/mpeg", URI, URI, READ, null));
        assertNoForward(LauncherForwardPolicy.decideStream(SEND, "audio/mpeg", URI, URI, READ, ""));
    }

    @Test
    public void authoritiesThatMerelyExtendTheOwnOneAreForwarded() {
        String uri = "content://de.yaacc.fileprovider.other/a.mp3";

        assertForwards(decide(SEND, "audio/mpeg", uri, uri, READ), uri);
    }

    // ---- the decision carries nothing but the URI and the flag

    @Test
    public void theDecisionExposesOnlyTheUriAndTheReadGrant() {
        StreamDecision forwarded = happy("audio/mpeg");
        Set<String> names = new HashSet<>();
        for (Method method : StreamDecision.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                names.add(method.getName());
            }
        }

        assertTrue(forwarded.isForwardStream());
        assertEquals(new HashSet<>(Arrays.asList("isForwardStream", "getStreamUri", "grantsRead")), names);
        assertEquals(URI, forwarded.getStreamUri());
    }

    @Test
    public void theDecisionHoldsNoStateBeyondTheUri() {
        StreamDecision forwarded = happy("audio/mpeg");
        int instanceFields = 0;
        for (Field field : StreamDecision.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                instanceFields++;
            }
        }

        assertTrue(forwarded.isForwardStream());
        assertEquals(1, instanceFields);
    }

    // ---- unchanged three-argument decide

    @Test
    public void theThreeArgumentDecisionIsNotAffected() {
        assertFalse(LauncherForwardPolicy.decide(SEND, "audio/mpeg", URI).isSendText());
        assertFalse(LauncherForwardPolicy.decide(SEND, "image/png", URI).isSendText());
        assertNull(LauncherForwardPolicy.decide(SEND, "image/png", URI).getText());
    }

    @Test
    public void everyRejectionHasAFixedReasonAndAForwardHasNone() {
        String uri = "content://media/external/images/media/42";
        String[][] cases = {
                {"android.intent.action.VIEW", "image/jpeg", uri, uri, "not-send"},
                {"android.intent.action.SEND", "text/plain", uri, uri, "type-not-audio-video-image"},
                {"android.intent.action.SEND", "image/jpeg", null, uri, "no-stream-extra"},
                {"android.intent.action.SEND", "image/jpeg", uri, null, "stream-differs-from-clip"},
                {"android.intent.action.SEND", "image/jpeg", uri, uri + "x", "stream-differs-from-clip"},
        };
        for (String[] c : cases) {
            assertEquals(c[4], LauncherForwardPolicy.rejectionReason(c[0], c[1], c[2], c[3], 1, OWN));
            assertNoForward(decide(c[0], c[1], c[2], c[3], 1));
        }
        assertEquals("no-read-grant-flag",
                LauncherForwardPolicy.rejectionReason("android.intent.action.SEND", "image/jpeg", uri, uri, 0, OWN));
        assertEquals("uri-not-acceptable", LauncherForwardPolicy.rejectionReason("android.intent.action.SEND",
                "image/jpeg", "file:///sdcard/a.jpg", "file:///sdcard/a.jpg", 1, OWN));
        assertEquals(null,
                LauncherForwardPolicy.rejectionReason("android.intent.action.SEND", "image/jpeg", uri, uri, 1, OWN));
        assertForwards(decide("android.intent.action.SEND", "image/jpeg", uri, uri, 1), uri);
    }
}
