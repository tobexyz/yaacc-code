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

/**
 * An error of an OpenHome action: the code goes to the control point as the UPnP error code. The codes 800 to 803 are those of the
 * OpenHome playlist service, 402 is the UPnP code for invalid arguments.
 */
public final class OhError extends RuntimeException {
    public static final int INVALID_ARGS = 402;
    public static final int ID_NOT_FOUND = 800;
    public static final int PLAYLIST_FULL = 801;
    public static final int INDEX_NOT_FOUND = 802;
    public static final int SEEK_FAILURE = 803;

    private final int code;

    public OhError(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OhError idNotFound() {
        return new OhError(ID_NOT_FOUND, "Id not found");
    }

    public static OhError playlistFull() {
        return new OhError(PLAYLIST_FULL, "Playlist full");
    }

    public static OhError indexNotFound() {
        return new OhError(INDEX_NOT_FOUND, "Index not found");
    }

    public static OhError seekFailure() {
        return new OhError(SEEK_FAILURE, "Seek failed");
    }

    public static OhError invalid(String message) {
        return new OhError(INVALID_ARGS, message);
    }
}
