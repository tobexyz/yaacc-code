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
package de.yaacc.upnp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.junit.Test;

import java.net.URI;

public class YaaccMusicTrackTest {

    private ProtocolInfo createProtocolInfo(String mimeType) {
        return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType, ProtocolInfo.WILDCARD);
    }

    @Test
    public void testBasicCreation() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "track_id",
            "parent_id",
            "Song Title",
            "Artist",
            false
        );
        
        track.setAlbum("Album Name");
        track.setArtist("Track Artist");
        track.setTrackNumber(5);
        track.setDate("2024-01-15");
        track.setGenres(new String[]{"Rock", "Alternative"});
        track.setAlbumArtUri(URI.create("http://example.com/albumart.jpg"));
        
        assertEquals("track_id", track.getId());
        assertEquals("parent_id", track.getParentId());
        assertEquals("Song Title", track.getTitle());
        assertEquals("Artist", track.getCreator());
        assertEquals("Album Name", track.getAlbum());
        assertEquals("Track Artist", track.getArtist());
        assertEquals(Integer.valueOf(5), track.getTrackNumber());
        assertEquals("2024-01-15", track.getDate());
    }

    @Test
    public void testGenres() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "id", "parent", "Title", "Artist", false
        );
        
        track.setGenres(new String[]{"Rock", "Pop"});
        
        String[] genres = track.getGenres();
        assertNotNull(genres);
        assertEquals(2, genres.length);
        assertEquals("Rock", genres[0]);
        assertEquals("Pop", genres[1]);
    }

    @Test
    public void testAlbumArtUri() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "id", "parent", "Title", "Artist", false
        );
        
        track.setAlbumArtUri(URI.create("http://example.com/art.jpg"));
        assertEquals("http://example.com/art.jpg", track.getAlbumArtUri().toString());
    }

    @Test
    public void testToClingMusicTrack() {
        YaaccMusicTrack yaaccTrack = new YaaccMusicTrack(
            "track_id",
            "parent_id",
            "Song Title",
            "Artist",
            false
        );
        yaaccTrack.setAlbum("Album Name");
        yaaccTrack.setArtist("Track Artist");
        yaaccTrack.setTrackNumber(5);
        yaaccTrack.setDate("2024-01-15");
        yaaccTrack.setGenres(new String[]{"Rock"});
        yaaccTrack.setAlbumArtUri(URI.create("http://example.com/albumart.jpg"));
        yaaccTrack.addResource(new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            543210L,
            "03:45",
            null,
            "http://example.com/song.mp3"
        ));
        
        MusicTrack clingTrack = yaaccTrack.toClingItem();
        
        assertNotNull(clingTrack);
        assertEquals("track_id", clingTrack.getId());
        assertEquals("parent_id", clingTrack.getParentID());
        assertEquals("Album Name", clingTrack.getAlbum());
        assertEquals(Integer.valueOf(5), clingTrack.getOriginalTrackNumber());
        assertEquals("2024-01-15", clingTrack.getDate());
        assertNotNull(clingTrack.getResources());
        assertEquals(1, clingTrack.getResources().size());
    }

    @Test
    public void testNullGenres() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "id", "parent", "Title", "Artist", false
        );
        
        assertEquals(null, track.getGenres());
    }

    @Test
    public void testEmptyGenres() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "id", "parent", "Title", "Artist", false
        );
        
        track.setGenres(new String[0]);
        assertEquals(0, track.getGenres().length);
    }

    @Test
    public void testAddResource() {
        YaaccMusicTrack track = new YaaccMusicTrack(
            "id", "parent", "Title", "Artist", false
        );
        
        track.addResource(new YaaccRes(
            createProtocolInfo("audio/mpeg"),
            543210L,
            "03:45",
            null,
            "http://example.com/song.mp3"
        ));
        
        assertEquals(1, track.getResources().size());
        assertEquals("http://example.com/song.mp3", track.getResources().get(0).getValue());
    }

    @Test
    public void testAllMimeTypes() {
        String[] audioTypes = {"audio/mpeg", "audio/ogg", "audio/flac", "audio/wav", "audio/aac"};
        
        for (String mimeType : audioTypes) {
            YaaccMusicTrack track = new YaaccMusicTrack(
                "id", "parent", "Title", "Artist", false
            );
            track.addResource(new YaaccRes(createProtocolInfo(mimeType), 100L, "03:00", null, "uri"));
            assertEquals(mimeType, track.getResources().get(0).getProtocolInfo().getContentFormat());
        }
    }
}
