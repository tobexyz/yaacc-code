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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp.model;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.MusicTrack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight MusicTrack wrapper that avoids Cling Property overhead.
 * Supports: artist, album, track number, genre, date, album art URI.
 */
public class YaaccMusicTrack extends YaaccItem {
    
    private String album;
    private String artist;
    private Integer trackNumber;
    private String date;
    private String[] genres;
    private URI albumArtUri;
    
    public YaaccMusicTrack(String id, String parentId, String title, String creator, boolean restricted) {
        super(id, parentId, title, creator, restricted, "object.item.audioItem.musicTrack");
    }
    
    public void setAlbum(String album) {
        this.album = album;
    }
    
    public void setArtist(String artist) {
        this.artist = artist;
    }
    
    public void setTrackNumber(Integer trackNumber) {
        this.trackNumber = trackNumber;
    }
    
    public void setDate(String date) {
        this.date = date;
    }
    
    public void setGenres(String[] genres) {
        this.genres = genres;
    }
    
    public void setAlbumArtUri(URI albumArtUri) {
        this.albumArtUri = albumArtUri;
    }
    
    public String getAlbum() {
        return album;
    }
    
    public String getArtist() {
        return artist;
    }
    
    public Integer getTrackNumber() {
        return trackNumber;
    }
    
    public String getDate() {
        return date;
    }
    
    public String[] getGenres() {
        return genres;
    }
    
    public URI getAlbumArtUri() {
        return albumArtUri;
    }
    
    /**
     * Convert to Cling MusicTrack for UPnP serialization.
     */
    @Override
    public MusicTrack toClingItem() {
        Res[] clingResources = new Res[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            clingResources[i] = resources.get(i).toClingRes();
        }
        
        MusicTrack track = new MusicTrack(
            getId(), 
            getParentId(), 
            getTitle() + (album != null ? " - (" + album + ")" : ""), 
            artist != null ? artist : getCreator(),
            album,
            artist != null ? artist : getCreator(),
            clingResources
        );
        
        track.setRestricted(isRestricted());
        
        if (albumArtUri != null) {
            track.replaceFirstProperty(new DIDLObject.Property.UPNP.ALBUM_ART_URI(albumArtUri));
        }
        
        if (artist != null) {
            track.setArtists(new PersonWithRole[]{new PersonWithRole(artist)});
        }
        
        if (trackNumber != null && trackNumber > 0) {
            track.setOriginalTrackNumber(trackNumber);
        }
        
        if (date != null && !date.isEmpty()) {
            track.setDate(date);
        }
        
        if (genres != null && genres.length > 0) {
            track.setGenres(genres);
        }
        
        return track;
    }
    
    /**
     * Create from Cling MusicTrack.
     */
    public static YaaccMusicTrack fromClingMusicTrack(MusicTrack track) {
        YaaccMusicTrack yaaccTrack = new YaaccMusicTrack(
            track.getId(),
            track.getParentID(),
            track.getTitle(),
            track.getCreator(),
            track.isRestricted()
        );
        
        yaaccTrack.setAlbum(track.getAlbum());
        yaaccTrack.setArtist(track.getFirstArtist() != null ? track.getFirstArtist().getName() : null);
        yaaccTrack.setTrackNumber(track.getOriginalTrackNumber());
        yaaccTrack.setDate(track.getDate());
        yaaccTrack.setGenres(track.getGenres());
        
        URI albumArtUri = track.getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        if (albumArtUri != null) {
            yaaccTrack.setAlbumArtUri(albumArtUri);
        }
        
        for (org.fourthline.cling.support.model.Res res : track.getResources()) {
            yaaccTrack.addResource(YaaccRes.fromClingRes(res));
        }
        
        return yaaccTrack;
    }
}
