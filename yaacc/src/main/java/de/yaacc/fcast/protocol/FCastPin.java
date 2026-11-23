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

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;

/**
 * The fingerprint of a receiver's key (FCast v4, docs/tech.md, "FCast v4"): SHA-256 of the DER {@code SubjectPublicKeyInfo} of its
 * self-signed certificate, written as standard base64 in the {@code fp} of the mDNS record and of an {@code fcast://r/} link.
 * Immutable. The connection to a receiver that has a pin is trusted only if the certificate's key has exactly this hash.
 */
public final class FCastPin {
    /** Where the fingerprint came from; the link is the stronger source (a channel the network can not change). */
    public enum Source {
        /** From an {@code fcast://r/} link (the receiver's screen or a link the user pasted). */
        LINK,
        /** From the mDNS record: protects against a listener, not against an attacker who answers discovery. */
        DISCOVERY
    }

    /** Bytes of a SHA-256 digest. */
    public static final int LENGTH = 32;

    private final byte[] hash;
    private final Source source;

    private FCastPin(byte[] hash, Source source) {
        this.hash = hash;
        this.source = source;
    }

    /**
     * @param base64 the {@code fp} text (standard base64, padding allowed but not needed)
     * @return the pin, or null if the text is not base64 of exactly 32 bytes
     */
    public static FCastPin parse(String base64, Source source) {
        if (base64 == null || source == null || base64.length() > 64) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        return bytes.length == LENGTH ? new FCastPin(bytes, source) : null;
    }

    public static FCastPin of(byte[] sha256, Source source) {
        if (sha256 == null || sha256.length != LENGTH || source == null) {
            throw new IllegalArgumentException("a pin is 32 bytes");
        }
        return new FCastPin(sha256.clone(), source);
    }

    /** The pin of a certificate (what the receiver would announce); used by tests and by the sheet to show what was seen. */
    public static FCastPin of(X509Certificate certificate, Source source) {
        return new FCastPin(spkiHash(certificate), source);
    }

    static byte[] spkiHash(X509Certificate certificate) {
        try {
            // the encoding of a public key of the platform is its SubjectPublicKeyInfo (X.509 form)
            return MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is always there", impossible);
        }
    }

    /** True if the certificate's key has this pin's hash (constant time). */
    public boolean matches(X509Certificate certificate) {
        return certificate != null && MessageDigest.isEqual(hash, spkiHash(certificate));
    }

    /** The same key with another source. */
    public FCastPin withSource(Source other) {
        return new FCastPin(hash, other);
    }

    public Source getSource() {
        return source;
    }

    /** The {@code fp} text. */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FCastPin && Arrays.equals(hash, ((FCastPin) other).hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        return "FCastPin{" + source + ", " + toBase64().substring(0, 8) + "...}";
    }
}
