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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.util;

/**
 * Cached metadata for SAF (Storage Access Framework) files.
 */
public class SAFMetadata {
    public final String duration;      // "HH:MM:SS" format
    public final String mimeType;      // "audio/mpeg", "video/mp4", etc.
    public final String encodedId;     // Base64 encoded URI (deprecated)
    public final String shortId;       // Short numeric ID for UPnP
    public final long fileSize;        // File size in bytes
    public final long timestamp;       // When cached (System.currentTimeMillis())
    
    public SAFMetadata(String duration, String mimeType, String encodedId, String shortId, long fileSize) {
        this.duration = duration;
        this.mimeType = mimeType;
        this.encodedId = encodedId;
        this.shortId = shortId;
        this.fileSize = fileSize;
        this.timestamp = System.currentTimeMillis();
    }
    
    // For serialization to SharedPreferences
    public String serialize() {
        return duration + "|" + mimeType + "|" + encodedId + "|" + shortId + "|" + fileSize + "|" + timestamp;
    }
    
    // For deserialization from SharedPreferences
    public static SAFMetadata deserialize(String data) {
        if (data == null) return null;
        String[] parts = data.split("\\|", 7);
        if (parts.length < 4) return null;
        String shortId = parts.length >= 5 ? parts[3] : null;
        long fileSize = parts.length >= 6 ? Long.parseLong(parts[4]) : 0;
        return new SAFMetadata(parts[0], parts[1], parts[2], shortId, fileSize);
    }
}
