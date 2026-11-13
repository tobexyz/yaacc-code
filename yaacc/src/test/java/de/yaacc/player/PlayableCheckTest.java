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
package de.yaacc.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rule "is this item a playlist file that ExoPlayer can not play as audio" (11.11) and "is it an image or a video"
 * (11.2). Table tests; HLS must stay playable.
 */
public class PlayableCheckTest {

    private static final String[] PLAYLIST_TYPES = {
            "audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/x-ms-asx"};
    private static final String[] PLAYLIST_EXTENSIONS = {"m3u", "pls", "asx"};

    // ---- by type

    @Test
    public void everyPlaylistTypeIsAPlaylistFile() {
        for (String type : PLAYLIST_TYPES) {
            assertTrue(type, PlayableCheck.isPlaylistFile(type, "http://h/a", "Title"));
        }
    }

    @Test
    public void theTypeIsComparedWithoutCaseAndWithoutParameters() {
        assertTrue(PlayableCheck.isPlaylistFile("Audio/X-MpegURL", null, null));
        assertTrue(PlayableCheck.isPlaylistFile("audio/x-mpegurl; charset=utf-8", null, null));
        assertTrue(PlayableCheck.isPlaylistFile("  audio/mpegurl ;q=1", null, null));
        assertTrue(PlayableCheck.isPlaylistFile("AUDIO/X-SCPLS;x=y;z=1", null, null));
        assertTrue(PlayableCheck.isPlaylistFile("audio/x-ms-asx;", null, null));
    }

    @Test
    public void aMusicTypeIsNoPlaylistFile() {
        for (String type : new String[]{"audio/mpeg", "audio/flac", "audio/x-wav", "audio/ogg", "audio/mp4",
                "audio/aac", "audio/mpeg; charset=binary", "application/ogg", "video/mp4", "image/jpeg"}) {
            assertFalse(type, PlayableCheck.isPlaylistFile(type, "http://h/song", "Song"));
        }
    }

    // ---- by extension

    @Test
    public void everyPlaylistExtensionOfTheUrlIsAPlaylistFile() {
        for (String extension : PLAYLIST_EXTENSIONS) {
            assertTrue(extension, PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/list." + extension, "Title"));
            assertTrue(extension + " upper", PlayableCheck.isPlaylistFile("audio/mpeg",
                    "http://h/p/LIST." + extension.toUpperCase(), "Title"));
        }
    }

    @Test
    public void everyPlaylistExtensionOfTheTitleIsAPlaylistFile() {
        for (String extension : PLAYLIST_EXTENSIONS) {
            assertTrue(extension, PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/42", "By The Way." + extension));
            assertTrue(extension + " upper", PlayableCheck.isPlaylistFile(null, null, "BY THE WAY." + extension.toUpperCase()));
        }
    }

    @Test
    public void theExtensionOfTheUrlIgnoresQueryAndFragment() {
        assertTrue(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/list.m3u?token=abc.mp3", "T"));
        assertTrue(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/list.pls#frag.mp3", "T"));
        assertTrue(PlayableCheck.isPlaylistFile("audio/mpeg", "content://media/doc/list.asx?x=1#y", "T"));
        assertFalse("the extension is in the query only", PlayableCheck.isPlaylistFile("audio/mpeg",
                "http://h/p/song.mp3?name=list.m3u", "Song"));
        assertFalse("the extension is in the fragment only", PlayableCheck.isPlaylistFile("audio/mpeg",
                "http://h/p/song.mp3#list.m3u", "Song"));
    }

    @Test
    public void aDotInTheDirectoryIsNoExtension() {
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/dir.m3u/song", "Song"));
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/dir.m3u/", "Song"));
    }

    @Test
    public void theExtensionMustBeTheWholeSuffixAndNotAPartOfAName() {
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/song.m3ux", "Song"));
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/song.xm3u", "Song"));
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/m3u", "m3u"));
        assertFalse("a title that only mentions it", PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/1", "My .m3u collection"));
        assertFalse("a name with dots", PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/1", "Mr. Brightside"));
    }

    // ---- HLS stays playable

    @Test
    public void hlsTypesAreNotFiltered() {
        assertFalse(PlayableCheck.isPlaylistFile("application/vnd.apple.mpegurl", "http://h/live/index", "Radio"));
        assertFalse(PlayableCheck.isPlaylistFile("application/x-mpegURL", "http://h/live/index", "Radio"));
        assertFalse(PlayableCheck.isPlaylistFile("APPLICATION/X-MPEGURL; charset=utf-8", "http://h/live/index", "Radio"));
        assertFalse(PlayableCheck.isPlaylistFile("application/vnd.apple.mpegurl", null, null));
    }

    @Test
    public void anM3u8UrlIsNotFilteredWhateverTheTypeSays() {
        assertFalse(PlayableCheck.isPlaylistFile(null, "http://h/live/index.m3u8", "Radio"));
        assertFalse(PlayableCheck.isPlaylistFile("", "http://h/live/index.M3U8?token=1", "Radio"));
        assertFalse("some servers send the generic type for HLS", PlayableCheck.isPlaylistFile("audio/mpegurl",
                "http://h/live/index.m3u8", "Radio"));
        assertFalse(PlayableCheck.isPlaylistFile("audio/x-mpegurl", "http://h/live/index.m3u8#x", "Radio"));
    }

    @Test
    public void anHlsTypeWinsOverAnExtensionOfTheTitle() {
        assertFalse(PlayableCheck.isPlaylistFile("application/x-mpegURL", "http://h/live/index", "Radio.m3u"));
    }

    // ---- unknown input

    @Test
    public void nothingKnownIsNoPlaylistFileAndNothingThrows() {
        assertFalse(PlayableCheck.isPlaylistFile(null, null, null));
        assertFalse(PlayableCheck.isPlaylistFile("", "", ""));
        assertFalse(PlayableCheck.isPlaylistFile("   ", "   ", "   "));
        assertFalse(PlayableCheck.isPlaylistFile(";", "?", "#"));
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", ".", "."));
        assertFalse(PlayableCheck.isPlaylistFile("audio/mpeg", "http://h/p/song.", "song."));
    }

    // ---- playable items

    private static PlayableItem item(String mime, String title) {
        PlayableItem item = new PlayableItem();
        item.setMimeType(mime);
        item.setTitle(title);
        return item;
    }

    @Test
    public void aPlayableItemIsCheckedByItsTypeAndItsTitle() {
        assertTrue(PlayableCheck.isPlaylistFile(item("audio/x-mpegurl", "By The Way")));
        assertTrue(PlayableCheck.isPlaylistFile(item("audio/mpeg", "By The Way.m3u")));
        assertFalse(PlayableCheck.isPlaylistFile(item("audio/mpeg", "By The Way")));
        assertFalse(PlayableCheck.isPlaylistFile(item("application/vnd.apple.mpegurl", "Radio")));
        assertFalse(PlayableCheck.isPlaylistFile((PlayableItem) null));
    }

    @Test
    public void anAlbumWithAPlaylistFileKeepsTheOtherTracksInTheirOrder() {
        PlayableItem one = item("audio/mpeg", "One");
        PlayableItem list = item("audio/x-mpegurl", "By The Way");
        PlayableItem two = item("audio/flac", "Two");
        PlayableItem three = item("audio/mpeg", "Three.pls");
        PlayableItem hls = item("application/x-mpegURL", "Radio");

        List<PlayableItem> kept = PlayableCheck.withoutPlaylistFiles(Arrays.asList(one, list, two, three, hls));

        assertEquals(Arrays.asList(one, two, hls), kept);
    }

    @Test
    public void whenNothingIsFilteredTheListIsTheSameContent() {
        List<PlayableItem> items = new ArrayList<>(Arrays.asList(item("audio/mpeg", "A"), item("audio/flac", "B")));

        assertEquals(items, PlayableCheck.withoutPlaylistFiles(items));
        assertTrue(PlayableCheck.withoutPlaylistFiles(new ArrayList<>()).isEmpty());
        assertTrue(PlayableCheck.withoutPlaylistFiles(null).isEmpty());
    }

    @Test
    public void whenEverythingIsAPlaylistFileNothingIsLeft() {
        assertTrue(PlayableCheck.withoutPlaylistFiles(Arrays.asList(item("audio/x-mpegurl", "A"), item("audio/x-scpls", "B"))).isEmpty());
    }

    // ---- image or video (the picker, 11.2)

    @Test
    public void imagesAndVideosAreNotAudio() {
        assertTrue(PlayableCheck.isImageOrVideo("image/jpeg"));
        assertTrue(PlayableCheck.isImageOrVideo("video/mp4"));
        assertTrue(PlayableCheck.isImageOrVideo("VIDEO/X-MATROSKA; codecs=x"));
        assertTrue(PlayableCheck.isImageOrVideo("  image/png "));
    }

    @Test
    public void audioAndUnknownTypesAreNotImagesOrVideos() {
        assertFalse(PlayableCheck.isImageOrVideo("audio/mpeg"));
        assertFalse(PlayableCheck.isImageOrVideo("audio/x-mpegurl"));
        assertFalse(PlayableCheck.isImageOrVideo("application/vnd.apple.mpegurl"));
        assertFalse(PlayableCheck.isImageOrVideo("application/octet-stream"));
        assertFalse(PlayableCheck.isImageOrVideo(""));
        assertFalse(PlayableCheck.isImageOrVideo(null));
        assertFalse("a prefix is not a type", PlayableCheck.isImageOrVideo("videos/x"));
        assertFalse(PlayableCheck.isImageOrVideo("image"));
    }
}
