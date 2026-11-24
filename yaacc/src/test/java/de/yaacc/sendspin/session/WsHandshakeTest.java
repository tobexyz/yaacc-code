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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** The opening handshake of both sides, with the sample of RFC 6455 and every refusal. */
public class WsHandshakeTest {

    @Test
    public void theAcceptKeyIsTheOneOfTheRfc() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    /** What a server answers to a request whose key it reads from the request itself. */
    private static byte[] answerFor(String request, String status, String extraHeaders, boolean wrongAccept) {
        String key = null;
        for (String line : request.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String accept = wrongAccept ? "AAAAAAAAAAAAAAAAAAAAAAAAAAA=" : WsHandshake.acceptFor(key);
        return ("HTTP/1.1 " + status + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n"
                + extraHeaders + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** Runs the client handshake against a scripted server; @return the request it sent. */
    private static String clientAgainst(String status, String extraHeaders, boolean wrongAccept, String host, String path) throws IOException {
        // the answer needs the key of the request: the client writes first, so the scripted server is a stream that is read afterwards
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final byte[][] answer = new byte[1][];
        java.io.InputStream in = new java.io.InputStream() {
            private ByteArrayInputStream delegate;

            @Override
            public int read() {
                if (delegate == null) {
                    answer[0] = answerFor(sent.toString(), status, extraHeaders, wrongAccept);
                    delegate = new ByteArrayInputStream(answer[0]);
                }
                return delegate.read();
            }
        };
        try {
            WsHandshake.connect(in, sent, host, 8928, path, new SecureRandom());
        } finally {
            // the request is what it is even if the answer was refused
        }
        return sent.toString();
    }

    @Test
    public void aClientRequestHasTheRequiredHeadersAndNoOrigin() throws Exception {
        String request = clientAgainst("101 Switching Protocols", "", false, "192.168.0.7", "/sendspin");
        assertTrue(request, request.startsWith("GET /sendspin HTTP/1.1\r\n"));
        assertTrue(request.contains("Host: 192.168.0.7:8928\r\n"));
        assertTrue(request.contains("Upgrade: websocket\r\n"));
        assertTrue(request.contains("Connection: Upgrade\r\n"));
        assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"));
        assertTrue("no Origin header: the protocol forbids it", !request.toLowerCase().contains("origin"));
        assertTrue(request.endsWith("\r\n\r\n"));
    }

    @Test
    public void anIpv6HostIsBracketed() throws Exception {
        String request = clientAgainst("101 Switching Protocols", "", false, "fe80::1", "/sendspin");
        assertTrue(request, request.contains("Host: [fe80::1]:8928\r\n"));
    }

    private static void expectRefusal(String status, String extra, boolean wrongAccept) throws Exception {
        try {
            clientAgainst(status, extra, wrongAccept, "10.0.0.1", "/sendspin");
            fail("expected a refusal: " + status + " " + extra + " " + wrongAccept);
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void aClientRefusesAnythingButAProperUpgrade() throws Exception {
        expectRefusal("200 OK", "", false);
        expectRefusal("403 Forbidden", "", false);
        expectRefusal("101 Switching Protocols", "", true);
        expectRefusal("101 Switching Protocols", "Sec-WebSocket-Extensions: permessage-deflate\r\n", false);
        expectRefusal("101 Switching Protocols", "Sec-WebSocket-Protocol: chat\r\n", false);
    }

    @Test
    public void aRequestPathOrHostThatCouldInjectHeadersIsRefusedBeforeAnythingIsSent() throws Exception {
        for (String path : new String[]{"sendspin", "//evil", "/a b", "/a\r\nX: 1", "/\u00e4"}) {
            try {
                clientAgainst("101 Switching Protocols", "", false, "10.0.0.1", path);
                fail("expected a refusal of " + path.replace("\r", "\\r").replace("\n", "\\n"));
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        for (String host : new String[]{"", "a b", "a\r\nX: 1", "a/b"}) {
            try {
                clientAgainst("101 Switching Protocols", "", false, host, "/sendspin");
                fail("expected a refusal of the host");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
    }

    @Test
    public void aHandshakeThatNeverEndsIsCapped() throws Exception {
        java.io.InputStream endless = new java.io.InputStream() {
            @Override
            public int read() {
                return 'a';
            }
        };
        try {
            WsHandshake.connect(endless, new ByteArrayOutputStream(), "10.0.0.1", 1, "/x", new SecureRandom());
            fail("expected a cap");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("too long"));
        }
    }

    @Test
    public void nothingBehindTheBlankLineIsConsumed() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final String[] answer = new String[1];
        java.io.InputStream in = new java.io.InputStream() {
            private ByteArrayInputStream delegate;

            @Override
            public int read() {
                if (delegate == null) {
                    byte[] head = answerFor(sent.toString(), "101 Switching Protocols", "", false);
                    byte[] all = new byte[head.length + 2];
                    System.arraycopy(head, 0, all, 0, head.length);
                    all[head.length] = (byte) 0x81;
                    all[head.length + 1] = 0;
                    delegate = new ByteArrayInputStream(all);
                    answer[0] = "set";
                }
                return delegate.read();
            }

            int next() {
                return delegate.read();
            }
        };
        WsHandshake.connect(in, sent, "10.0.0.1", 1, "/x", new SecureRandom());
        assertEquals("the first frame byte is still there", 0x81, in.read());
    }

    private static String request(String... headerLines) {
        StringBuilder sb = new StringBuilder("GET /sendspin HTTP/1.1\r\n");
        for (String line : headerLines) {
            sb.append(line).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    private static final String GOOD_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    @Test
    public void aServerAcceptsAnUpgradeAndAnswersLikeTheRfc() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WsHandshake.Request request = WsHandshake.accept(new ByteArrayInputStream(request("Host: x", "Upgrade: websocket",
                "Connection: keep-alive, Upgrade", "Sec-WebSocket-Key: " + GOOD_KEY, "Sec-WebSocket-Version: 13").getBytes(StandardCharsets.US_ASCII)),
                out, true);
        assertEquals("/sendspin", request.path());
        String response = out.toString();
        assertTrue(response, response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertTrue(response, response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"));
    }

    @Test
    public void aServerRefusesABrowserOriginAndMalformedRequests() throws Exception {
        String[][] bad = {
                {"Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Key: " + GOOD_KEY, "Sec-WebSocket-Version: 13", "Origin: http://evil.example"},
                {"Connection: Upgrade", "Sec-WebSocket-Key: " + GOOD_KEY, "Sec-WebSocket-Version: 13"},
                {"Upgrade: websocket", "Sec-WebSocket-Key: " + GOOD_KEY, "Sec-WebSocket-Version: 13"},
                {"Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 13"},
                {"Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Key: " + GOOD_KEY, "Sec-WebSocket-Version: 8"},
                {"Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Key: AAAA", "Sec-WebSocket-Version: 13"},
        };
        for (String[] headers : bad) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                WsHandshake.accept(new ByteArrayInputStream(request(headers).getBytes(StandardCharsets.US_ASCII)), out, true);
                fail("expected a refusal of " + String.join(" | ", headers));
            } catch (IOException expected) {
                assertTrue(out.toString(), out.toString().startsWith("HTTP/1.1 400") || out.toString().startsWith("HTTP/1.1 403"));
            }
        }
    }
}
