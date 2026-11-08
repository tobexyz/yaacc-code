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
package de.yaacc.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SharedContentPolicy}: which shared local content may be registered.
 * Only {@code content://} URIs of other apps and audio, video or image MIME types pass; the
 * rest is rejected (file:, http:, own file provider, junk). Plain JVM.
 */
public class SharedContentPolicyTest {

    private static final String OWN = "de.yaacc.fileprovider";

    private static boolean uri(String value) {
        return SharedContentPolicy.isAcceptableUri(value, OWN);
    }

    private static boolean mime(String value) {
        return SharedContentPolicy.isAcceptableMimeType(value);
    }

    // ---- URI: accepted

    @Test
    public void contentUriOfAnotherAppIsAccepted() {
        assertTrue(uri("content://com.example.provider/files/song.mp3"));
    }

    @Test
    public void mediaStoreStyleUriIsAccepted() {
        assertTrue(uri("content://media/external/audio/media/42"));
    }

    @Test
    public void documentsProviderUriWithEncodedPathIsAccepted() {
        assertTrue(uri("content://com.android.providers.media.documents/document/audio%3A42"));
    }

    @Test
    public void uriWithQueryAndFragmentIsAccepted() {
        assertTrue(uri("content://com.example.provider/files/a.mp3?x=1#t"));
    }

    @Test
    public void schemeIsMatchedIgnoringAsciiCase() {
        assertTrue(uri("CONTENT://com.example.provider/a.mp3"));
        assertTrue(uri("Content://com.example.provider/a.mp3"));
    }

    // ---- URI: wrong scheme or shape

    @Test
    public void fileUriIsRejected() {
        assertFalse(uri("file:///sdcard/Music/a.mp3"));
        assertFalse(uri("file:///data/data/de.yaacc/shared_prefs/x.xml"));
        assertFalse(uri("FILE:///sdcard/a.mp3"));
    }

    @Test
    public void httpAndHttpsAreRejected() {
        assertFalse(uri("http://example.com/a.mp3"));
        assertFalse(uri("https://example.com/a.mp3"));
    }

    @Test
    public void javascriptAndIntentSchemesAreRejected() {
        assertFalse(uri("javascript:alert(1)"));
        assertFalse(uri("intent://com.example.provider/a#Intent;scheme=content;end"));
    }

    @Test
    public void otherSchemesAreRejected() {
        assertFalse(uri("android.resource://com.example/raw/a"));
        assertFalse(uri("ftp://example.com/a.mp3"));
        assertFalse(uri("contentx://com.example.provider/a.mp3"));
        assertFalse(uri("xcontent://com.example.provider/a.mp3"));
    }

    @Test
    public void nullEmptyAndBlankAreRejected() {
        assertFalse(uri(null));
        assertFalse(uri(""));
        assertFalse(uri(" "));
        assertFalse(uri(" \t\n"));
    }

    @Test
    public void bareSchemeIsRejected() {
        assertFalse(uri("content:"));
        assertFalse(uri("content:/"));
        assertFalse(uri("content://"));
    }

    @Test
    public void emptyAuthorityIsRejected() {
        assertFalse(uri("content:///"));
        assertFalse(uri("content:///a.mp3"));
        assertFalse(uri("content://?x=1"));
        assertFalse(uri("content://#frag"));
    }

    @Test
    public void opaqueFormWithoutDoubleSlashIsRejected() {
        assertFalse(uri("content:com.example.provider/a.mp3"));
        assertFalse(uri("content:/com.example.provider/a.mp3"));
    }

    @Test
    public void barePathsAreRejected() {
        assertFalse(uri("/sdcard/Music/a.mp3"));
        assertFalse(uri("a.mp3"));
        assertFalse(uri("//com.example.provider/a.mp3"));
        assertFalse(uri("../a.mp3"));
    }

    @Test
    public void leadingWhitespaceMeansNoSchemeAndIsRejected() {
        assertFalse(uri(" content://com.example.provider/a.mp3"));
    }

    // ---- URI: own authority (confused deputy)

    @Test
    public void ownFileProviderAuthorityIsRejected() {
        assertFalse(uri("content://de.yaacc.fileprovider/cache/secret.xml"));
    }

    @Test
    public void ownAuthorityIsRejectedIgnoringCase() {
        assertFalse(uri("content://DE.YAACC.FILEPROVIDER/cache/secret.xml"));
        assertFalse(SharedContentPolicy.isAcceptableUri(
                "content://de.yaacc.fileprovider/a", "De.Yaacc.FileProvider"));
    }

    @Test
    public void ownAuthorityIsRejectedWithEmptyPathOrQuery() {
        assertFalse(uri("content://de.yaacc.fileprovider"));
        assertFalse(uri("content://de.yaacc.fileprovider?x=1"));
        assertFalse(uri("content://de.yaacc.fileprovider#x"));
    }

    @Test
    public void ownAuthorityWithAndroidUserIdPrefixIsRejected() {
        assertFalse(uri("content://0@de.yaacc.fileprovider/cache/secret.xml"));
        assertFalse(uri("content://10@de.yaacc.fileprovider/cache/secret.xml"));
    }

    @Test
    public void authorityWithUserInfoIsRejectedEvenForAnotherApp() {
        assertFalse(uri("content://user@com.example.provider/a.mp3"));
    }

    @Test
    public void percentEncodedAuthorityIsRejected() {
        assertFalse(uri("content://de.yaacc%2Efileprovider/cache/secret.xml"));
        assertFalse(uri("content://com.example%2Eprovider/a.mp3"));
    }

    @Test
    public void authoritiesThatMerelyContainOrExtendTheOwnOneAreAccepted() {
        assertTrue(uri("content://de.yaacc.fileprovider.other/a.mp3"));
        assertTrue(uri("content://other.de.yaacc.fileprovider/a.mp3"));
    }

    @Test
    public void missingOwnAuthorityRejectsEverything() {
        assertFalse(SharedContentPolicy.isAcceptableUri("content://com.example.provider/a.mp3", null));
        assertFalse(SharedContentPolicy.isAcceptableUri("content://com.example.provider/a.mp3", ""));
    }

    // ---- URI: characters and length

    @Test
    public void whitespaceInsideTheUriIsRejected() {
        assertFalse(uri("content://com.example.provider/my song.mp3"));
        assertFalse(uri("content://com.example.provider/a.mp3 "));
        assertFalse(uri("content://com.example .provider/a.mp3"));
        assertFalse(uri("content://com.example.provider/a\u00a0b.mp3"));
    }

    @Test
    public void controlCharactersInsideTheUriAreRejected() {
        assertFalse(uri("content://com.example.provider/a\n.mp3"));
        assertFalse(uri("content://com.example.provider/a\r\n.mp3"));
        assertFalse(uri("content://com.example.provider/a\u0000.mp3"));
        assertFalse(uri("content://com.example.provider/a\u001b.mp3"));
        assertFalse(uri("content://com.example.provider/a\u007f.mp3"));
        assertFalse(uri("content://com.example.provider/a\u0085.mp3"));
    }

    @Test
    public void nonAsciiCharactersAreRejected() {
        assertFalse(uri("content://com.example.provider/s\u00f6ng.mp3"));
        assertFalse(uri("content://com.exampl\u0435.provider/a.mp3"));
    }

    @Test
    public void uriOfExactlyTheMaximumLengthIsAccepted() {
        String prefix = "content://com.example.provider/";
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < SharedContentPolicy.MAX_URI_LENGTH) {
            sb.append('a');
        }

        assertEquals(SharedContentPolicy.MAX_URI_LENGTH, sb.length());
        assertTrue(uri(sb.toString()));
    }

    @Test
    public void uriOneCharLongerThanTheMaximumIsRejected() {
        String prefix = "content://com.example.provider/";
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() <= SharedContentPolicy.MAX_URI_LENGTH) {
            sb.append('a');
        }

        assertEquals(SharedContentPolicy.MAX_URI_LENGTH + 1, sb.length());
        assertFalse(uri(sb.toString()));
    }

    @Test
    public void maximumUriLengthIs2048() {
        assertEquals(2048, SharedContentPolicy.MAX_URI_LENGTH);
    }

    // ---- MIME type: accepted

    @Test
    public void concreteAudioVideoAndImageTypesAreAccepted() {
        assertTrue(mime("audio/mpeg"));
        assertTrue(mime("audio/flac"));
        assertTrue(mime("video/mp4"));
        assertTrue(mime("video/x-matroska"));
        assertTrue(mime("image/jpeg"));
        assertTrue(mime("image/svg+xml"));
        assertTrue(mime("audio/x-ms-wma"));
        assertTrue(mime("audio/vnd.wave"));
    }

    @Test
    public void typeIsMatchedIgnoringCase() {
        assertTrue(mime("AUDIO/MPEG"));
        assertTrue(mime("Video/Mp4"));
        assertTrue(mime("IMAGE/png"));
    }

    // ---- MIME type: rejected

    @Test
    public void nullEmptyAndBlankMimeTypesAreRejected() {
        assertFalse(mime(null));
        assertFalse(mime(""));
        assertFalse(mime(" "));
    }

    @Test
    public void wildcardTypesAreRejected() {
        assertFalse(mime("*/*"));
        assertFalse(mime("audio/*"));
        assertFalse(mime("video/*"));
        assertFalse(mime("image/*"));
        assertFalse(mime("*/mpeg"));
    }

    @Test
    public void typesOutsideAudioVideoImageAreRejected() {
        assertFalse(mime("application/*"));
        assertFalse(mime("application/octet-stream"));
        assertFalse(mime("application/pdf"));
        assertFalse(mime("application/x-sh"));
        assertFalse(mime("text/*"));
        assertFalse(mime("text/plain"));
        assertFalse(mime("text/html"));
        assertFalse(mime("multipart/mixed"));
    }

    @Test
    public void missingSubtypeIsRejected() {
        assertFalse(mime("audio/"));
        assertFalse(mime("audio"));
        assertFalse(mime("video/"));
        assertFalse(mime("/mpeg"));
        assertFalse(mime("/"));
    }

    @Test
    public void parametersAreRejected() {
        assertFalse(mime("audio/mpeg; charset=utf-8"));
        assertFalse(mime("audio/mpeg;charset=utf-8"));
        assertFalse(mime("video/mp4; codecs=\"avc1\""));
    }

    @Test
    public void whitespaceAroundOrInsideTheTypeIsRejected() {
        assertFalse(mime(" audio/mpeg"));
        assertFalse(mime("audio/mpeg "));
        assertFalse(mime("audio/mpeg\n"));
        assertFalse(mime("audio /mpeg"));
        assertFalse(mime("audio/ mpeg"));
    }

    @Test
    public void extraSlashesAndTypeLookalikesAreRejected() {
        assertFalse(mime("audio/mpeg/extra"));
        assertFalse(mime("audiox/mpeg"));
        assertFalse(mime("xaudio/mpeg"));
        assertFalse(mime("audio//mpeg"));
        assertFalse(mime("audio/.mpeg"));
        assertFalse(mime("audio/-mpeg"));
    }

    @Test
    public void controlAndNonAsciiCharactersInTheSubtypeAreRejected() {
        assertFalse(mime("audio/mp\u0000eg"));
        assertFalse(mime("audio/mp\u00e9g"));
    }

    @Test
    public void overlongMimeTypeIsRejected() {
        StringBuilder sb = new StringBuilder("audio/");
        while (sb.length() <= SharedContentPolicy.MAX_MIME_LENGTH) {
            sb.append('a');
        }

        assertFalse(mime(sb.toString()));
    }

    // ---- copy fallback cap

    @Test
    public void maxCopyBytesIsPositive() {
        assertTrue(SharedContentPolicy.maxCopyBytes() > 0L);
    }

    @Test
    public void maxCopyBytesIsBetween1MiBAnd1GiB() {
        long cap = SharedContentPolicy.maxCopyBytes();

        assertTrue(cap >= 1024L * 1024L);
        assertTrue(cap <= 1024L * 1024L * 1024L);
    }

    @Test
    public void maxCopyBytesMatchesTheConstant() {
        assertEquals(SharedContentPolicy.MAX_COPY_BYTES, SharedContentPolicy.maxCopyBytes());
    }

    @Test
    public void sizeUpToTheCapIsWithinTheLimit() {
        assertTrue(SharedContentPolicy.isWithinCopyLimit(0L));
        assertTrue(SharedContentPolicy.isWithinCopyLimit(1L));
        assertTrue(SharedContentPolicy.isWithinCopyLimit(SharedContentPolicy.MAX_COPY_BYTES));
    }

    @Test
    public void sizeAboveTheCapIsOutsideTheLimit() {
        assertFalse(SharedContentPolicy.isWithinCopyLimit(SharedContentPolicy.MAX_COPY_BYTES + 1L));
        assertFalse(SharedContentPolicy.isWithinCopyLimit(Long.MAX_VALUE));
    }

    @Test
    public void unknownOrNegativeSizeIsOutsideTheLimit() {
        assertFalse(SharedContentPolicy.isWithinCopyLimit(-1L));
        assertFalse(SharedContentPolicy.isWithinCopyLimit(Long.MIN_VALUE));
    }

    // ---- chooseMimeType: provider type first, concrete intent type as the fallback

    private static String choose(String providerType, String intentType) {
        return SharedContentPolicy.chooseMimeType(providerType, intentType);
    }

    @Test
    public void anAcceptableProviderTypeWins() {
        assertEquals("audio/mpeg", choose("audio/mpeg", null));
        assertEquals("video/mp4", choose("video/mp4", null));
        assertEquals("image/png", choose("image/png", null));
    }

    @Test
    public void anAcceptableProviderTypeWinsOverAnAcceptableIntentType() {
        assertEquals("audio/mpeg", choose("audio/mpeg", "video/mp4"));
        assertEquals("image/png", choose("image/png", "image/jpeg"));
    }

    @Test
    public void anAcceptableProviderTypeWinsOverAWildcardOrForeignIntentType() {
        assertEquals("audio/mpeg", choose("audio/mpeg", "audio/*"));
        assertEquals("audio/mpeg", choose("audio/mpeg", "*/*"));
        assertEquals("audio/mpeg", choose("audio/mpeg", "text/plain"));
    }

    @Test
    public void theIntentTypeIsTheFallbackWhenTheProviderHasNoType() {
        assertEquals("video/mp4", choose(null, "video/mp4"));
        assertEquals("video/mp4", choose("", "video/mp4"));
    }

    @Test
    public void theIntentTypeIsTheFallbackWhenTheProviderTypeIsNotAcceptable() {
        assertEquals("audio/flac", choose("application/octet-stream", "audio/flac"));
        assertEquals("audio/flac", choose("text/plain", "audio/flac"));
        assertEquals("audio/flac", choose("audio/*", "audio/flac"));
        assertEquals("audio/flac", choose("*/*", "audio/flac"));
        assertEquals("audio/flac", choose("audio/flac; charset=utf-8", "audio/flac"));
    }

    @Test
    public void aGenericProviderTypeWithoutAConcreteIntentTypeIsRejected() {
        assertNull(choose("application/octet-stream", null));
        assertNull(choose("application/octet-stream", ""));
        assertNull(choose("application/octet-stream", "audio/*"));
        assertNull(choose("application/octet-stream", "*/*"));
    }

    @Test
    public void wildcardsAreNeverReturned() {
        assertNull(choose("audio/*", "video/*"));
        assertNull(choose("*/*", "*/*"));
        assertNull(choose(null, "audio/*"));
        assertNull(choose(null, "video/*"));
        assertNull(choose(null, "image/*"));
        assertNull(choose(null, "*/*"));
        assertNull(choose("*/mpeg", "audio/*"));
    }

    @Test
    public void nonMediaTypesAreNeverReturned() {
        assertNull(choose("text/plain", "application/pdf"));
        assertNull(choose(null, "application/octet-stream"));
        assertNull(choose("application/x-sh", "text/html"));
        assertNull(choose("multipart/mixed", null));
    }

    @Test
    public void nullAndEmptyInputsGiveNull() {
        assertNull(choose(null, null));
        assertNull(choose("", ""));
        assertNull(choose(null, ""));
        assertNull(choose("", null));
        assertNull(choose(" ", " "));
    }

    @Test
    public void theResultIsTheLowerCaseCanonicalForm() {
        assertEquals("audio/mpeg", choose("AUDIO/MPEG", null));
        assertEquals("video/mp4", choose("Video/Mp4", "image/png"));
        assertEquals("image/png", choose(null, "IMAGE/PNG"));
        assertEquals("image/svg+xml", choose("Image/SVG+XML", null));
    }

    @Test
    public void anUpperCaseGenericProviderTypeStillFallsBack() {
        assertEquals("audio/flac", choose("APPLICATION/OCTET-STREAM", "AUDIO/FLAC"));
    }

    @Test
    public void whitespaceAndParametersAreNotStrippedSoTheyAreNotAccepted() {
        assertNull(choose(" audio/mpeg", null));
        assertNull(choose("audio/mpeg ", null));
        assertNull(choose("audio/mpeg\n", null));
        assertNull(choose(null, "audio/mpeg; codecs=mp3"));
        assertEquals("video/mp4", choose("audio/mpeg;charset=x", "video/mp4"));
    }

    @Test
    public void overlongTypesAreNotChosen() {
        StringBuilder sb = new StringBuilder("audio/");
        while (sb.length() <= SharedContentPolicy.MAX_MIME_LENGTH) {
            sb.append('a');
        }

        assertNull(choose(sb.toString(), sb.toString()));
        assertEquals("video/mp4", choose(sb.toString(), "video/mp4"));
    }

    @Test
    public void chooseMimeTypeNeverReturnsATypeThatIsNotAcceptable() {
        String[] inputs = {null, "", " ", "audio/mpeg", "AUDIO/MPEG", "audio/*", "*/*",
                "application/octet-stream", "text/plain", "video/mp4; x=y", "image/", "/",
                "audio/mp\u0000eg", "audio/mp\u00e9g"};

        for (String provider : inputs) {
            for (String intent : inputs) {
                String chosen = choose(provider, intent);
                if (chosen != null) {
                    assertTrue(provider + " / " + intent + " -> " + chosen,
                            SharedContentPolicy.isAcceptableMimeType(chosen));
                    assertEquals(chosen.toLowerCase(java.util.Locale.ROOT), chosen);
                }
            }
        }
        // and the table above does choose something for the plain cases
        assertEquals("audio/mpeg", choose("audio/mpeg", "audio/mpeg"));
    }
}
