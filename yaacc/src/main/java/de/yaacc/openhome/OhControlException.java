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

/** An action did not work: the code is the UPnP error code the device answered with, {@link #NO_ANSWER} if it did not answer. */
public final class OhControlException extends Exception {
    public static final int NO_ANSWER = -1;

    private final int code;

    public OhControlException(int code, String message) {
        super(message);
        this.code = code;
    }

    public OhControlException(String message, Throwable cause) {
        super(message, cause);
        this.code = NO_ANSWER;
    }

    public int getCode() {
        return code;
    }
}
