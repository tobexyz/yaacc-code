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
package de.yaacc.fcast.protocol;

import java.io.IOException;

/**
 * A v4 connection could not be made securely. These are errors for the user, never a reason to fall back to the unencrypted
 * protocol (FCast v4 document, "Threat model"; decisions D4 and D24).
 */
public final class FCastSecurityException extends IOException {
    /** What went wrong. */
    public enum Kind {
        /** The receiver's key is not the one the fingerprint names. */
        PIN_MISMATCH,
        /** The receiver is known to offer v4 but answered with an older version or with something else. */
        DOWNGRADE,
        /** TLS 1.3 could not be negotiated, or the handshake failed for another reason. */
        HANDSHAKE_FAILED,
        /** This device can not do TLS 1.3. */
        TLS_UNSUPPORTED
    }

    private final Kind kind;

    public FCastSecurityException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FCastSecurityException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
