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
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The in-place TLS 1.3 upgrade of a v4 connection (docs/tech.md, "FCast v4"; decision D25): the sender is the TLS client, only
 * TLS 1.3 is allowed, and the only certificate that is trusted is the one whose key has the pinned hash. The dates and the host
 * name of the certificate are not checked (the receiver makes a self-signed certificate; its key is its identity); the TLS stack
 * still verifies the handshake signature with that key, which proves that the peer holds the key.
 */
public final class FCastTls {
    private static final String PROTOCOL = "TLSv1.3";

    private FCastTls() {
    }

    /** Names the pin as the reason a handshake failed. */
    static final class PinMismatch extends CertificateException {
        PinMismatch(String message) {
            super(message);
        }
    }

    /** Trusts one key and nothing else. */
    static final class PinTrustManager implements X509TrustManager {
        private final FCastPin pin;

        PinTrustManager(FCastPin pin) {
            this.pin = pin;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new PinMismatch("the receiver sent no certificate");
            }
            if (!pin.matches(chain[0])) {
                throw new PinMismatch("the receiver's key is not the pinned one");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("this side does not accept clients");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Wraps the connected socket in TLS 1.3 and runs the handshake. The plain socket must not have been read past the receiver's
     * {@code Version} packet (the frame reader reads exactly the bytes of a frame). On any failure the socket is closed.
     *
     * @param timeoutMillis the longest the handshake may take
     * @return the TLS socket (the plain one is closed with it)
     * @throws FCastSecurityException {@code PIN_MISMATCH}, {@code HANDSHAKE_FAILED} or {@code TLS_UNSUPPORTED}
     */
    public static SSLSocket upgrade(Socket plain, String host, int port, FCastPin pin, int timeoutMillis) throws IOException {
        SSLSocket tls = null;
        try {
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(null, new TrustManager[]{new PinTrustManager(pin)}, null);
            tls = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            tls.setEnabledProtocols(new String[]{PROTOCOL});
            tls.setSoTimeout(timeoutMillis);
            tls.startHandshake();
            tls.setSoTimeout(0);
            return tls;
        } catch (GeneralSecurityException | IllegalArgumentException unsupported) {
            closeQuietly(tls != null ? tls : plain);
            throw new FCastSecurityException(FCastSecurityException.Kind.TLS_UNSUPPORTED,
                    "this device can not do TLS 1.3", unsupported);
        } catch (SSLException failed) {
            closeQuietly(tls != null ? tls : plain);
            throw new FCastSecurityException(causedByPin(failed) ? FCastSecurityException.Kind.PIN_MISMATCH
                    : FCastSecurityException.Kind.HANDSHAKE_FAILED,
                    causedByPin(failed) ? "the receiver's key does not match its fingerprint" : "the secure handshake failed", failed);
        } catch (IOException failed) {
            closeQuietly(tls != null ? tls : plain);
            throw failed;
        }
    }

    private static boolean causedByPin(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof PinMismatch) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
