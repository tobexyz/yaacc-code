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
package de.yaacc.sendspin.secure;

import java.security.SecureRandom;

/**
 * The identity of one side of the protocol: a Curve25519 key pair whose public key, base64url without padding (43 characters), is
 * the {@code client_id} or {@code server_id} and at the same time the static key of the Noise handshake. The key is drawn from a
 * CSPRNG once per installation and role and is never a shared default.
 */
public final class Identity {

    private final X25519Keys.KeyPair keys;

    private Identity(X25519Keys.KeyPair keys) {
        this.keys = keys;
    }

    public static Identity generate(SecureRandom random) {
        return new Identity(X25519Keys.generate(random));
    }

    /** @param privateKey 32 bytes, as {@link #exportPrivateKey()} gave them */
    public static Identity fromPrivateKey(byte[] privateKey) {
        return new Identity(X25519Keys.fromPrivate(privateKey));
    }

    /** The id: base64url of the public key, 43 characters. */
    public String id() {
        return Base64Url.encode(keys.publicKey());
    }

    public byte[] publicKey() {
        return keys.publicKey();
    }

    /** For storing it wrapped (never for a log). */
    public byte[] exportPrivateKey() {
        return keys.exportPrivateKey();
    }

    /** The handshake takes the key pair of the identity; the pair is the identity's own and is kept by it. */
    public X25519Keys.KeyPair keyPair() {
        return keys;
    }

    public void destroy() {
        keys.destroy();
    }
}
