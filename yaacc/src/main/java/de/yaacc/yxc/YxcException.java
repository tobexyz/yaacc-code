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
package de.yaacc.yxc;

import java.io.IOException;

/**
 * A YXC call failed: the device could not be reached, answered with an HTTP error status, sent something that is not
 * a JSON answer, or answered with a {@code response_code} other than 0. Names the call (path without query) and
 * carries the code or status when there is one. The open-source libraries never look at either; yaacc does.
 */
public final class YxcException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Value of {@link #getResponseCode()} and {@link #getHttpStatus()} when there is none. */
    public static final int NONE = -1;

    private final String call;
    private final int responseCode;
    private final int httpStatus;

    private YxcException(String message, String call, int responseCode, int httpStatus, Throwable cause) {
        super(message, cause);
        this.call = call;
        this.responseCode = responseCode;
        this.httpStatus = httpStatus;
    }

    static YxcException responseCode(String call, int code) {
        return new YxcException(call + ": response_code " + code + " (" + describe(code) + ")", call, code, NONE, null);
    }

    static YxcException httpStatus(String call, int status) {
        return new YxcException(call + ": HTTP status " + status, call, NONE, status, null);
    }

    static YxcException transport(String call, IOException cause) {
        return new YxcException(call + ": " + cause.getClass().getSimpleName(), call, NONE, NONE, cause);
    }

    /** The body is left out of the message: it may carry names of the household. */
    static YxcException malformed(String call, String what, Throwable cause) {
        return new YxcException(call + ": unusable answer (" + what + ")", call, NONE, NONE, cause);
    }

    /** @return the call, for example {@code dist/setServerInfo} */
    public String getCall() {
        return call;
    }

    /** @return the {@code response_code} of the answer, {@link #NONE} if the failure is not one */
    public int getResponseCode() {
        return responseCode;
    }

    /** @return the HTTP status, {@link #NONE} if the failure is not one */
    public int getHttpStatus() {
        return httpStatus;
    }

    private static String describe(int code) {
        switch (code) {
            case 1:
                return "device still initializing";
            case 2:
                return "internal error";
            case 3:
                return "invalid request";
            case 4:
                return "invalid parameter";
            case 5:
                return "guarded, cannot be set in the current state";
            case 6:
                return "timeout";
            case 99:
                return "firmware update running";
            default:
                return code >= 100 && code <= 112 ? "streaming service account" : "unknown";
        }
    }
}
