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
package de.yaacc.fcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Task 5.3: what the settings sheet shows when, the speed choices and the troubleshooting trace. */
public class FCastSheetModelTest {

    @Test
    public void withoutAPlayerOnlyTheVolumeAndTheConnectionAreOffered() {
        FCastSheetModel.Layout layout = FCastSheetModel.layout(new FCastSheetModel.Facts());

        assertTrue(layout.showsVolume());
        assertFalse(layout.showsMute());
        assertFalse(layout.showsSpeed());
        assertFalse(layout.showsStop());
        assertTrue(layout.showsCheckConnection());
        assertFalse("nothing to reconnect before a connection was made", layout.showsReconnect());
        assertFalse(layout.showsRemove());
    }

    @Test
    public void aPlayerBringsMuteSpeedAndStopWhenSomethingPlays() {
        FCastSheetModel.Layout idle = FCastSheetModel.layout(new FCastSheetModel.Facts().player(false));
        FCastSheetModel.Layout playing = FCastSheetModel.layout(new FCastSheetModel.Facts().player(true));

        assertTrue(idle.showsMute());
        assertTrue(idle.showsSpeed());
        assertFalse(idle.showsStop());
        assertTrue(playing.showsStop());
        assertFalse("the connection of a player is the player's", FCastSheetModel.layout(new FCastSheetModel.Facts().player(true).connected(true)).showsReconnect());
    }

    @Test
    public void theReceiversOwnReportOffersStopWithoutAPlayer() {
        FCastSheetModel.Layout layout = FCastSheetModel.layout(new FCastSheetModel.Facts().session(true).connected(true));

        assertTrue(layout.showsStop());
        assertTrue(layout.showsReconnect());
        assertFalse(layout.showsMute());
    }

    @Test
    public void aPlayerThatIsIdleDoesNotOfferStopEvenIfTheSessionSaysSomethingPlays() {
        // the player is the authority for its receiver
        assertFalse(FCastSheetModel.layout(new FCastSheetModel.Facts().player(false).session(true)).showsStop());
    }

    @Test
    public void onlyAReceiverAddedByHandCanBeRemoved() {
        assertTrue(FCastSheetModel.layout(new FCastSheetModel.Facts().manual(true)).showsRemove());
        assertFalse(FCastSheetModel.layout(new FCastSheetModel.Facts().manual(false)).showsRemove());
    }

    @Test
    public void theSpeedsAreWrittenWithoutTrailingZeros() {
        assertEquals(java.util.Arrays.asList("0.5\u00d7", "0.75\u00d7", "1\u00d7", "1.25\u00d7", "1.5\u00d7", "2\u00d7"), FCastSheetModel.speedLabels());
        assertEquals("0.25\u00d7", FCastSheetModel.speedLabel(0.25));
        assertEquals("4\u00d7", FCastSheetModel.speedLabel(4.0));
    }

    @Test
    public void theNearestSpeedIsFoundForAnyReportedSpeed() {
        assertEquals(2, FCastSheetModel.nearestSpeed(1.0));
        assertEquals(0, FCastSheetModel.nearestSpeed(0.0625));
        assertEquals(5, FCastSheetModel.nearestSpeed(16.0));
        assertEquals(3, FCastSheetModel.nearestSpeed(1.2));
        assertEquals(-1, FCastSheetModel.nearestSpeed(Double.NaN));
    }

    // ---- the trace

    @Test
    public void theTraceKeepsTheLastFiftyLinesOldestFirst() {
        FCastSheetTrace trace = new FCastSheetTrace();
        for (int i = 0; i < 80; i++) {
            trace.add("event " + i);
        }

        List<String> lines = trace.lines();

        assertEquals(FCastSheetTrace.MAX_LINES, lines.size());
        assertEquals("event 30", lines.get(0));
        assertEquals("event 79", lines.get(49));
        assertTrue(trace.text().endsWith("event 79\n"));
    }

    @Test
    public void aLineIsCleanedAndCut() {
        FCastSheetTrace trace = new FCastSheetTrace();
        StringBuilder long_ = new StringBuilder("A\u0007\u202eB");
        while (long_.length() < 500) {
            long_.append('x');
        }

        trace.add(long_.toString());
        trace.add(null);
        trace.add("   ");

        assertEquals(1, trace.lines().size());
        assertTrue(trace.lines().get(0).startsWith("AB"));
        assertEquals(FCastSheetTrace.MAX_LINE, trace.lines().get(0).length());
    }

    @Test
    public void aUrlInALineIsShortenedToHostAndPath() {
        FCastSheetTrace trace = new FCastSheetTrace();

        trace.add("Play http://user:secret@192.168.0.5:8080/proxy/abc?api_key=TOKEN&x=1#frag now");
        trace.add("see https://example.org/a/b.mp3?t=1.");

        assertEquals("Play http://192.168.0.5:8080/proxy/abc now", trace.lines().get(0));
        assertEquals("see https://example.org/a/b.mp3", trace.lines().get(1)); // the text after the ? is the query, whatever it looks like
        assertFalse(trace.text().contains("secret"));
        assertFalse(trace.text().contains("TOKEN"));
    }

    @Test
    public void anUpperCaseSchemeIsShortenedToo() {
        FCastSheetTrace trace = new FCastSheetTrace();

        trace.add("Play HTTP://u:p@192.168.0.5/a?k=SECRET and Https://h/b#f");

        assertEquals("Play HTTP://192.168.0.5/a and Https://h/b", trace.lines().get(0));
    }

    @Test
    public void shortenLeavesOtherTextAndPlainUrlsAlone() {
        assertEquals("http://h/a", FCastSheetTrace.shorten("http://h/a"));
        assertEquals("http://h", FCastSheetTrace.shorten("http://u@h?x"));
        assertEquals("fcast://r/abc", FCastSheetTrace.shorten("fcast://r/abc"));
        assertEquals("", FCastSheetTrace.shorten(null));
    }
}
