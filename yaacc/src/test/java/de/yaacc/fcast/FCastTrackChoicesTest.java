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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.yaacc.fcast.protocol.FCastCapabilities;
import de.yaacc.fcast.protocol.FCastTrack;

/** Task 7.4: the pickers of tracks and subtitles, the formats text and the subtitle address check. */
public class FCastTrackChoicesTest {

    private static final FCastTrackChoices.Labels LABELS = new FCastTrackChoices.Labels() {
        @Override
        public String off() {
            return "Off";
        }

        @Override
        public String track(long id) {
            return "Track " + id;
        }

        @Override
        public String language(String code) {
            switch (code) {
                case "eng":
                    return "English";
                case "deu":
                    return "German";
                default:
                    return null;
            }
        }

        @Override
        public String group(FCastTrackChoices.Group group) {
            return group.name().toLowerCase();
        }

        @Override
        public String yes() {
            return "yes";
        }

        @Override
        public String no() {
            return "no";
        }

        @Override
        public String screen(int width, int height) {
            return width + "x" + height;
        }
    };

    private static final List<FCastTrack> TRACKS = Arrays.asList(
            new FCastTrack(0, FCastTrack.Type.VIDEO, "und", "Main"),
            new FCastTrack(1, FCastTrack.Type.AUDIO, "eng", "English 5.1"),
            new FCastTrack(2, FCastTrack.Type.AUDIO, "deu", ""),
            new FCastTrack(3, FCastTrack.Type.AUDIO, "xyz", ""),
            new FCastTrack(4, FCastTrack.Type.AUDIO, "", ""),
            new FCastTrack(5, FCastTrack.Type.AUDIO, "eng", "Commentary"),
            new FCastTrack(6, FCastTrack.Type.SUBTITLE, "deu", ""),
            new FCastTrack(7, FCastTrack.Type.SUBTITLE, "eng", "SDH"));

    private static List<String> labels(List<FCastTrackChoices.Choice> choices) {
        List<String> list = new ArrayList<>();
        for (FCastTrackChoices.Choice choice : choices) {
            list.add(choice.getLabel());
        }
        return list;
    }

    @Test
    public void theAudioChoicesAreTheAudioTracksOnlyInTheReceiversOrder() {
        List<FCastTrackChoices.Choice> audio = FCastTrackChoices.audio(TRACKS, LABELS);
        assertEquals(Arrays.asList("English 5.1", "German", "xyz", "Track 4", "Commentary (English)"), labels(audio));
        assertEquals(Long.valueOf(1), audio.get(0).getId());
    }

    @Test
    public void aTitleThatNamesTheLanguageAlreadyDoesNotRepeatIt() {
        assertEquals("English 5.1", FCastTrackChoices.label(TRACKS.get(1), LABELS));
        assertEquals("Commentary (English)", FCastTrackChoices.label(TRACKS.get(5), LABELS));
    }

    @Test
    public void anUnknownLanguageShowsItsCodeAndNoLanguageShowsTheTrackNumber() {
        assertEquals("xyz", FCastTrackChoices.label(TRACKS.get(3), LABELS));
        assertEquals("Track 4", FCastTrackChoices.label(TRACKS.get(4), LABELS));
        assertEquals("Main", FCastTrackChoices.label(TRACKS.get(0), LABELS));
    }

    @Test
    public void theSubtitleChoicesStartWithOff() {
        List<FCastTrackChoices.Choice> subtitles = FCastTrackChoices.subtitles(TRACKS, LABELS);
        assertEquals(Arrays.asList("Off", "German", "SDH (English)"), labels(subtitles));
        assertNull(subtitles.get(0).getId());
        assertEquals(Long.valueOf(7), subtitles.get(2).getId());
    }

    @Test
    public void withoutSubtitleTracksOnlyOffIsThere() {
        assertEquals(Collections.singletonList("Off"), labels(FCastTrackChoices.subtitles(Collections.<FCastTrack>emptyList(), LABELS)));
        assertTrue(FCastTrackChoices.audio(Collections.<FCastTrack>emptyList(), LABELS).isEmpty());
    }

    @Test
    public void theSelectionIsFoundByIdAndNoneIsOff() {
        List<FCastTrackChoices.Choice> subtitles = FCastTrackChoices.subtitles(TRACKS, LABELS);
        assertEquals(0, FCastTrackChoices.selectedIndex(subtitles, null));
        assertEquals(2, FCastTrackChoices.selectedIndex(subtitles, 7L));
        assertEquals("a track that is not listed", -1, FCastTrackChoices.selectedIndex(subtitles, 99L));
        List<FCastTrackChoices.Choice> audio = FCastTrackChoices.audio(TRACKS, LABELS);
        assertEquals("audio has no off", -1, FCastTrackChoices.selectedIndex(audio, null));
        assertEquals(1, FCastTrackChoices.selectedIndex(audio, 2L));
    }

    @Test
    public void theCapabilityLinesShowOnlyWhatIsThere() {
        FCastCapabilities caps = new FCastCapabilities(Arrays.asList("http", "https"), Arrays.asList("mp4"), Arrays.asList("h264", "h265"),
                Collections.<String>emptyList(), Arrays.asList("srt"), Collections.<String>emptyList(), Arrays.asList("png"), true, false, 0.01,
                1920, 1080);
        assertEquals(Arrays.asList("protocols: http, https", "containers: mp4", "video: h264, h265", "subtitles: srt", "images: png",
                "external_subtitles: yes", "screen: 1920x1080"), FCastTrackChoices.capabilityLines(caps, LABELS));
    }

    @Test
    public void withoutAScreenAndWithoutSubtitleFilesTheLinesSayNo() {
        FCastCapabilities caps = new FCastCapabilities(null, null, null, null, null, null, null, false, false, null, 0, 0);
        assertEquals(Collections.singletonList("external_subtitles: no"), FCastTrackChoices.capabilityLines(caps, LABELS));
    }

    @Test
    public void onlyWebAddressesAreSubtitleAddresses() {
        assertTrue(FCastTrackChoices.isAcceptableSubtitleUrl("http://192.168.0.5:8080/a.srt"));
        assertTrue(FCastTrackChoices.isAcceptableSubtitleUrl("HTTPS://example.org/path/a%20b.vtt?x=1"));
        for (String bad : new String[]{null, "", "file:///sdcard/a.srt", "content://x/y", "javascript:alert(1)", "ftp://h/a.srt", "http://", "http:///a.srt",
                "/a.srt", "a.srt", "http://h/a b.srt", "http://h/a\n.srt", "http://h/\u0085", "mailto:a@b.c", "http://h/a\u007f"}) {
            assertFalse("accepted: " + bad, FCastTrackChoices.isAcceptableSubtitleUrl(bad));
        }
        StringBuilder long1 = new StringBuilder("http://h/");
        while (long1.length() <= FCastTrackChoices.MAX_SUBTITLE_URL) {
            long1.append('x');
        }
        assertFalse(FCastTrackChoices.isAcceptableSubtitleUrl(long1.toString()));
    }
}
