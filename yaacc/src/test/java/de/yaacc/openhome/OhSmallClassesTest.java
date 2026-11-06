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
package de.yaacc.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The small helpers of the OpenHome model: states, volume, escaping, source and track list texts, address policy. Plain JVM. */
public class OhSmallClassesTest {

    @Test
    public void theFourTransportStatesHaveTheNamesOfTheDescription() {
        assertEquals("Playing", OhTransportState.PLAYING.value());
        assertEquals(OhTransportState.BUFFERING, OhTransportState.fromValue("Buffering"));
        assertNull(OhTransportState.fromValue("playing"));
        assertNull(OhTransportState.fromValue(null));
        assertNull(OhTransportState.fromValue("Waiting"));
    }

    @Test
    public void volumeStepsAndPercentConvertBothWays() {
        assertEquals(50, OhVolume.toPercent(50, 100));
        assertEquals(50, OhVolume.toPercent(25, 50));
        assertEquals(100, OhVolume.toPercent(500, 100)); // above the maximum
        assertEquals(0, OhVolume.toPercent(-3, 100));
        assertEquals(70, OhVolume.toPercent(70, 0)); // no maximum known: 100 steps are assumed
        assertEquals(25, OhVolume.fromPercent(50, 50));
        assertEquals(100, OhVolume.fromPercent(1000, 100));
        assertEquals(0, OhVolume.fromPercent(-5, 100));
        for (int p = 0; p <= 100; p++) {
            assertEquals(p, OhVolume.toPercent(OhVolume.fromPercent(p, 100), 100));
        }
    }

    @Test
    public void textForAnXmlElementIsEscapedAndTheCharactersXmlForbidsAreDropped() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", OhEscape.xml("a & b <c> \"d\" 'e'"));
        assertEquals("ab", OhEscape.xml("a\u0000b"));
        assertEquals("a\tb\nc", OhEscape.xml("a\tb\nc"));
        assertEquals("", OhEscape.xml(null));
        assertEquals("🎵", OhEscape.xml("🎵"));
    }

    @Test
    public void theSourceListHasTheElementsOfTheReferenceImplementationInItsOrder() {
        assertEquals("<SourceList><Source><Name>Playlist</Name><Type>Playlist</Type><Visible>true</Visible>"
                + "<SystemName>Playlist</SystemName></Source></SourceList>", OhSourceXml.playlistOnly());
        assertEquals("<SourceList><Source><Name>A&amp;B</Name><Type>T</Type><Visible>false</Visible><SystemName>S</SystemName>"
                + "</Source></SourceList>", OhSourceXml.source("A&B", "T", false, "S"));
    }

    @Test
    public void theTrackListIsTheXmlOfTheReadListAnswer() {
        OhQueue queue = new OhQueue(10);
        long id = queue.insert(0, "http://192.0.2.1/a.mp3?x=1&y=2", "<DIDL-Lite/>");

        assertEquals("<TrackList><Entry><Id>" + id + "</Id><Uri>http://192.0.2.1/a.mp3?x=1&amp;y=2</Uri>"
                + "<Metadata>&lt;DIDL-Lite/&gt;</Metadata></Entry></TrackList>", OhTrackList.toXml(queue.snapshot()));
        assertEquals("<TrackList></TrackList>", OhTrackList.toXml(Collections.<OhQueue.Entry>emptyList()));
    }

    @Test
    public void onlyHttpAndHttpsAddressesWithAHostAreAcceptedForTracks() {
        for (String ok : Arrays.asList("http://192.0.2.1/a.mp3", "https://example.org/a%20b.flac", "HTTP://192.0.2.1:8080/x?y=1",
                "http://[2001:db8::1]/a")) {
            assertTrue(ok, OhUriPolicy.isAcceptable(ok));
        }
        for (String bad : Arrays.asList(null, "", "file:///sdcard/a.mp3", "content://media/external/1", "ftp://192.0.2.1/a",
                "javascript:alert(1)", "http://", "http:///a", "//192.0.2.1/a", "/a.mp3", "http://user:pw@192.0.2.1/a",
                "http://192.0.2.1/a b", "http://192.0.2.1/a\nb", "http://192.0.2.1/\u0000", "a.mp3")) {
            assertFalse(String.valueOf(bad), OhUriPolicy.isAcceptable(bad));
        }
        assertFalse(OhUriPolicy.isAcceptable("http://192.0.2.1/" + new String(new char[5000]).replace('\0', 'a')));
    }
}
