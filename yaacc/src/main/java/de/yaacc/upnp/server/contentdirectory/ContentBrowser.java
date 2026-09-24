/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.util.Base64;
import de.yaacc.util.YaaccLogger;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.upnp.model.YaaccItem;
import de.yaacc.upnp.model.YaaccMusicTrack;
import de.yaacc.upnp.model.YaaccPhoto;
import de.yaacc.upnp.model.YaaccRes;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.Photo;


/**
 * Super class for all contentent directory browsers.
 *
 * @author openbit (Tobias Schoene)
 */
public abstract class ContentBrowser {

    // Static cache for DLNA attributes - computed once, reused forever
    private static final Map<String, String> DLNA_CACHE = new HashMap<>();
    
    // Static cache for ProtocolInfo - computed once, reused forever
    private static final Map<String, ProtocolInfo> PROTOCOL_INFO_CACHE = new HashMap<>();
    
    static {
        // Audio types
        DLNA_CACHE.put("audio/mpeg", "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/mp4", "DLNA.ORG_PN=AAC_ISO;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/aac", "DLNA.ORG_PN=AAC_ISO;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/L16", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/x-ms-wma", "DLNA.ORG_PN=WMABASE;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/vnd.dlna.adts", "DLNA.ORG_PN=ADTS;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/vnd.dolby.dd-raw", "DLNA.ORG_PN=AC3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/3gpp", "DLNA.ORG_PN=AMR_3GPP;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/x-sony-oma", "DLNA.ORG_PN=ATRAC3plus;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/ogg", "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/flac", "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/wav", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("audio/x-wav", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        
        // Image types
        DLNA_CACHE.put("image/jpeg", "DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("image/png", "DLNA.ORG_PN=PNG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        
        // Video types
        DLNA_CACHE.put("video/mp4", "DLNA.ORG_PN=MPEG4_P2_MP4_SP_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/mpeg", "DLNA.ORG_PN=MPEG1;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/vnd.dlna.mpeg-tts", "DLNA.ORG_PN=MPEG_TS_MP_LL_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/3gpp", "DLNA.ORG_PN=MPEG4_H263_MP4_P0_L10_AAC;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/x-matroska", "DLNA.ORG_PN=MATROSKA;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/webm", "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/x-msvideo", "DLNA.ORG_PN=AVI;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        DLNA_CACHE.put("video/quicktime", "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        
        // Pre-create ProtocolInfo objects for common MIME types
        for (Map.Entry<String, String> entry : DLNA_CACHE.entrySet()) {
            String mime = entry.getKey();
            String dlna = entry.getValue();
            PROTOCOL_INFO_CACHE.put(mime, new ProtocolInfo(
                Protocol.HTTP_GET.toString() + ":" + ProtocolInfo.WILDCARD + ":" + mime + ":" + dlna
            ));
        }
    }
    
    Context context;


    protected ContentBrowser(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }


    public abstract Integer getSize(YaaccContentDirectory contentDirectory, String myId);

    public abstract DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public abstract List<Container> browseContainer(
            YaaccContentDirectory content, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public abstract List<? extends Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby);

    public List<DIDLObject> browseChildren(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<DIDLObject> result = new ArrayList<>();
        result.addAll(browseContainer(contentDirectory, myId, firstResult, maxResults, orderby));
        result.addAll(browseItem(contentDirectory, myId, firstResult, maxResults, orderby));
        return result;
    }

    public String getUriString(YaaccContentDirectory contentDirectory, String id, MimeType mimeType) {
        return getUriString(contentDirectory, id, mimeType, null);
    }

    public String getUriString(YaaccContentDirectory contentDirectory, String id, MimeType mimeType, String contentUri) {
        String fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.toString());
        if (fileExtension == null) {
            YaaccLogger.d(getClass().getName(), "Can't lookup file extension from mimetype: " + mimeType);
            //try subtype
            fileExtension = mimeType.getSubtype();

        }

        if (contentUri != null) {
            // contentUri is now a short ID, not a full URI - don't Base64 encode it
            return "http://" + contentDirectory.getIpAddress() + ":"
                    + YaaccUpnpServerService.PORT + "/saf/" + id + "/" + contentUri + "." + fileExtension;
        }
        return "http://" + contentDirectory.getIpAddress() + ":"
                + YaaccUpnpServerService.PORT + "/res/" + id + "/file." + fileExtension;
    }
    
    public String getUriString(YaaccContentDirectory contentDirectory, String id, MimeType mimeType, String filename, String contentUri) {
        String fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.toString());
        if (fileExtension == null) {
            fileExtension = mimeType.getSubtype();
        }

        if (contentUri != null) {
            // SAF format with filename
            return "http://" + contentDirectory.getIpAddress() + ":"
                    + YaaccUpnpServerService.PORT + "/saf/" + id + "/" + contentUri + "." + fileExtension;
        }
        
        // Non-SAF format with filename included for renderer title
        String safeFilename = filename != null && !filename.isEmpty() 
            ? filename.replaceAll("[^a-zA-Z0-9._-]", "_")
            : "file";
        return "http://" + contentDirectory.getIpAddress() + ":"
                + YaaccUpnpServerService.PORT + "/res/" + id + "/" + safeFilename + "." + fileExtension;
    }

    public String getDLNAAttributes(MimeType mimetype) {
        String mime = mimetype.toString();
        String result = DLNA_CACHE.get(mime);
        if (result != null) {
            return result;
        }
        // Unknown type - return wildcard
        return "DLNA.ORG_PN=*;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
    }

    /**
     * Returns a cached ProtocolInfo for the given MIME type.
     * This avoids repeated parsing and object creation for each item.
     */
    protected ProtocolInfo getProtocolInfo(MimeType mimeType) {
        String mime = mimeType.toString();
        ProtocolInfo cached = PROTOCOL_INFO_CACHE.get(mime);
        if (cached != null) {
            return cached;
        }
        // Unknown MIME type - create new
        String dlna = getDLNAAttributes(mimeType);
        return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mime, dlna);
    }


    public String makeLikeClause(String column, int len) {
        return MediaPathFilter.makeLikeClause(column, len);
    }

    public List<String> getMediaPathesForLikeClause() {
        return MediaPathFilter.getMediaPathesForLikeClause(getContext());
    }

    public Set<String> getMediaPathes() {
        return MediaPathFilter.getMediaPathes(getContext());
    }

    public Set<String> getSelectedSafPathes() {
        return MediaPathFilter.getSelectedSafPathes(getContext());
    }

    /**
     * Creates a lightweight Item using YaaccItem/YaaccRes, then converts to Cling Item.
     * This avoids Cling Property overhead during item creation.
     */
    protected Item createItem(String id, String parentId, String title, String creator, 
                              boolean restricted, MimeType mimeType, String uri,
                              Long size, String duration) {
        // Get cached ProtocolInfo
        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);
        
        // Create lightweight YaaccRes
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, size, duration, null, uri);
        
        // Determine class based on MIME type
        String mimeMain = mimeType.getType();
        String clazz = mimeMain.equals("audio") ? "object.item.audioItem" 
                            : mimeMain.equals("video") ? "object.item.videoItem" 
                            : "object.item.imageItem";
        
        // Create lightweight YaaccItem
        YaaccItem yaaccItem = new YaaccItem(id, parentId, title, creator, restricted, clazz);
        yaaccItem.addResource(yaaccRes);
        
        // Convert to Cling Item for UPnP serialization
        return yaaccItem.toClingItem();
    }

    /**
     * Creates a lightweight MusicTrack with additional metadata.
     */
    protected MusicTrack createMusicTrack(String id, String parentId, String title, String creator,
                                          boolean restricted, MimeType mimeType, String uri,
                                          Long size, String duration,
                                          String album, String artist, Integer trackNumber,
                                          String date, String[] genres, String albumArtUri) {
        // Get cached ProtocolInfo
        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);
        
        // Create lightweight YaaccRes with audio properties
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, size, duration, null, 
                                         44100L, 2L, 16L, uri);
        
        // Create lightweight YaaccMusicTrack
        YaaccMusicTrack yaaccTrack = new YaaccMusicTrack(id, parentId, title, creator, restricted);
        yaaccTrack.addResource(yaaccRes);
        
        // Set additional properties
        if (album != null) yaaccTrack.setAlbum(album);
        if (artist != null) yaaccTrack.setArtist(artist);
        if (trackNumber != null) yaaccTrack.setTrackNumber(trackNumber);
        if (date != null) yaaccTrack.setDate(date);
        if (genres != null) yaaccTrack.setGenres(genres);
        if (albumArtUri != null) {
            try {
                yaaccTrack.setAlbumArtUri(new java.net.URI(albumArtUri));
            } catch (Exception e) {
                // Ignore invalid URI
            }
        }
        
        // Convert to Cling MusicTrack for UPnP serialization
        return yaaccTrack.toClingItem();
    }

    /**
     * Creates a lightweight MusicTrack with bitrate support (for Android 11+).
     */
    protected MusicTrack createMusicTrack(String id, String parentId, String title, String creator,
                                          boolean restricted, MimeType mimeType, String uri,
                                          Long size, String duration,
                                          String album, String artist, Integer trackNumber,
                                          String date, String[] genres, String albumArtUri,
                                          Long bitrate) {
        // Get cached ProtocolInfo
        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);
        
        // Create lightweight YaaccRes with audio properties and bitrate
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, size, duration, bitrate, 
                                         44100L, 2L, 16L, uri);
        
        // Create lightweight YaaccMusicTrack
        YaaccMusicTrack yaaccTrack = new YaaccMusicTrack(id, parentId, title, creator, restricted);
        yaaccTrack.addResource(yaaccRes);
        
        // Set additional properties
        if (album != null) yaaccTrack.setAlbum(album);
        if (artist != null) yaaccTrack.setArtist(artist);
        if (trackNumber != null) yaaccTrack.setTrackNumber(trackNumber);
        if (date != null) yaaccTrack.setDate(date);
        if (genres != null) yaaccTrack.setGenres(genres);
        if (albumArtUri != null) {
            try {
                yaaccTrack.setAlbumArtUri(new java.net.URI(albumArtUri));
            } catch (Exception e) {
                // Ignore invalid URI
            }
        }
        
        // Convert to Cling MusicTrack for UPnP serialization
        return yaaccTrack.toClingItem();
    }

    /**
     * Creates a lightweight Photo with album art URI.
     */
    protected Photo createPhoto(String id, String parentId, String title, String creator,
                                boolean restricted, MimeType mimeType, String uri,
                                Long size, String albumArtUri) {
        // Get cached ProtocolInfo
        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);
        
        // Create lightweight YaaccRes
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, size, null, null, uri);
        
        // Create lightweight YaaccPhoto
        YaaccPhoto yaaccPhoto = new YaaccPhoto(id, parentId, title, creator, restricted);
        yaaccPhoto.addResource(yaaccRes);
        
        // Set album art URI
        if (albumArtUri != null) {
            try {
                yaaccPhoto.setAlbumArtUri(new java.net.URI(albumArtUri));
            } catch (Exception e) {
                // Ignore invalid URI
            }
        }
        
        // Convert to Cling Photo for UPnP serialization
        return yaaccPhoto.toClingItem();
    }

}
