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
    public final String encodedId;     // Base64 encoded URI
    public final long fileSize;        // File size in bytes
    public final long timestamp;       // When cached (System.currentTimeMillis())
    
    public SAFMetadata(String duration, String mimeType, String encodedId, long fileSize) {
        this.duration = duration;
        this.mimeType = mimeType;
        this.encodedId = encodedId;
        this.fileSize = fileSize;
        this.timestamp = System.currentTimeMillis();
    }
    
    // For serialization to SharedPreferences
    public String serialize() {
        return duration + "|" + mimeType + "|" + encodedId + "|" + fileSize + "|" + timestamp;
    }
    
    // For deserialization from SharedPreferences
    public static SAFMetadata deserialize(String data) {
        if (data == null) return null;
        String[] parts = data.split("\\|", 5);
        if (parts.length < 4) return null;
        long fileSize = parts.length >= 5 ? Long.parseLong(parts[3]) : 0;
        return new SAFMetadata(parts[0], parts[1], parts[2], fileSize);
    }
}
