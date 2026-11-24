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

import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.NoiseTransport;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The encrypted channel after the handshake: every WebSocket binary message is one Noise transport message whose plaintext is a
 * message id byte followed by the body (id 0: a JSON message, 1: a fragment of a larger message, 4 to 7: player audio ...).
 * Sending is serialized: encrypting and writing happen under one lock, so the counters run in the order of the frames on the wire.
 * Reading is for one thread. A text message, a message that does not verify, an empty one and every malformed fragment sequence
 * end the connection (the caller closes).
 */
public final class SecureChannel {

    /** A message after decryption (and reassembly): its id and its body. */
    public static final class Inbound {
        private final int id;
        private final byte[] body;

        Inbound(int id, byte[] body) {
            this.id = id;
            this.body = body;
        }

        public int id() {
            return id;
        }

        public byte[] body() {
            return body;
        }

        public boolean isJson() {
            return id == SendspinMessages.JSON_ID;
        }

        /** @throws ProtocolException if this is not a valid JSON message */
        public Envelope envelope() throws ProtocolException {
            if (!isJson()) {
                throw new ProtocolException("not a JSON message");
            }
            return Envelope.parse(body);
        }
    }

    /** The largest message that arrives in fragments: the receiver's choice, large enough for every message it can receive. */
    private static final int MAX_REASSEMBLED = 1 << 20;
    /** The room of one transport message: the plaintext holds the id byte and the body. */
    public static final int MAX_BODY = NoiseTransport.MAX_PLAINTEXT_LENGTH - 1;

    private final WsConnection ws;
    private volatile NoiseTransport transport;
    private final Object sendLock = new Object();
    /** Messages that arrived under the old keys while a re-handshake waited for its second message; they are delivered first. */
    private final java.util.ArrayDeque<Inbound> stash = new java.util.ArrayDeque<>();

    private int fragmentType = -1;
    private ByteArrayOutputStream fragments;

    public SecureChannel(WsConnection ws, NoiseTransport transport) {
        this.ws = ws;
        this.transport = transport;
    }

    /**
     * The channel of the older, unencrypted wire (the "transition mode", only when the user allows it): a JSON message is a WebSocket
     * text message, anything else a binary message that starts with its id byte. There is no transport and nothing is encrypted.
     */
    public static SecureChannel plain(WsConnection ws) {
        return new SecureChannel(ws, null);
    }

    /** True for the plain channel of the older wire. */
    public boolean isPlain() {
        return transport == null;
    }

    public NoiseTransport transport() {
        return transport;
    }

    /** The lock that serializes sending; a re-handshake holds it so that no application message follows Noise message 1. */
    Object sendLock() {
        return sendLock;
    }

    /** Puts the keys of a finished re-handshake in place (the old ones are destroyed) and forgets a half received fragmented message. */
    void installTransport(NoiseTransport next) {
        NoiseTransport old = transport;
        transport = next;
        fragmentType = -1;
        fragments = null;
        if (old != null) {
            old.destroy();
        }
    }

    /** Keeps a message that arrived under the old keys; {@link #receive()} returns it first. */
    void stash(Inbound inbound) {
        stash.addLast(inbound);
    }

    /** Sends a JSON message (id 0). */
    public void sendJson(String json) throws IOException {
        if (transport == null) {
            synchronized (sendLock) {
                ws.sendText(json);
            }
            return;
        }
        sendMessage(SendspinMessages.JSON_ID, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a binary message of the given id. @param body at most {@link #MAX_BODY} bytes (larger messages are not sent by this server role yet) */
    public void sendMessage(int id, byte[] body) throws IOException {
        if (id < 0 || id > 255 || body.length > MAX_BODY) {
            throw new IllegalArgumentException("message too big for one transport message");
        }
        byte[] plain = new byte[body.length + 1];
        plain[0] = (byte) id;
        System.arraycopy(body, 0, plain, 1, body.length);
        synchronized (sendLock) {
            if (transport == null) {
                ws.sendBinary(plain);
                return;
            }
            try {
                ws.sendBinary(transport.encrypt(plain));
            } catch (NoiseException e) {
                throw new IOException("encryption failed");
            }
        }
    }

    /**
     * Blocks for the next message.
     *
     * @throws java.net.SocketTimeoutException if nothing arrived (the connection is intact)
     * @throws ProtocolException               if the peer broke a rule of the protocol (close)
     * @throws NoiseException                  if a message does not verify (close without a message)
     */
    public Inbound receive() throws IOException, ProtocolException, NoiseException {
        if (!stash.isEmpty()) {
            return stash.removeFirst();
        }
        if (transport == null) {
            WsConnection.Message message = ws.receive();
            if (message.isText()) {
                return new Inbound(SendspinMessages.JSON_ID, message.data());
            }
            byte[] data = message.data();
            if (data.length == 0) {
                throw new ProtocolException("empty message");
            }
            return new Inbound(data[0] & 0xFF, java.util.Arrays.copyOfRange(data, 1, data.length));
        }
        while (true) {
            WsConnection.Message message = ws.receive();
            if (message.isText()) {
                throw new ProtocolException("cleartext message after the handshake");
            }
            byte[] plain = transport.decrypt(message.data());
            if (plain.length == 0) {
                throw new ProtocolException("empty message");
            }
            int id = plain[0] & 0xFF;
            byte[] body = java.util.Arrays.copyOfRange(plain, 1, plain.length);
            if (id != SendspinMessages.FRAGMENT_ID) {
                if (fragmentType >= 0) {
                    throw new ProtocolException("a message inside a fragmented one");
                }
                return new Inbound(id, body);
            }
            Inbound whole = addFragment(body);
            if (whole != null) {
                return whole;
            }
        }
    }

    private Inbound addFragment(byte[] body) throws ProtocolException {
        if (body.length < 1) {
            throw new ProtocolException("fragment without flags");
        }
        int flags = body[0] & 0xFF;
        if ((flags & 0xFC) != 0) {
            throw new ProtocolException("reserved fragment flags");
        }
        boolean first = (flags & 0x02) != 0;
        boolean last = (flags & 0x01) != 0;
        int dataStart = 1;
        if (first) {
            if (fragmentType >= 0) {
                throw new ProtocolException("a first fragment inside a fragmented message");
            }
            if (body.length < 2) {
                throw new ProtocolException("first fragment without a type");
            }
            fragmentType = body[1] & 0xFF;
            if (fragmentType == SendspinMessages.FRAGMENT_ID) {
                throw new ProtocolException("fragment of a fragment");
            }
            fragments = new ByteArrayOutputStream();
            dataStart = 2;
        } else if (fragmentType < 0) {
            throw new ProtocolException("a fragment without a first one");
        }
        if ((long) fragments.size() + (body.length - dataStart) > MAX_REASSEMBLED) {
            throw new ProtocolException("message too big");
        }
        fragments.write(body, dataStart, body.length - dataStart);
        if (!last) {
            return null;
        }
        Inbound whole = new Inbound(fragmentType, fragments.toByteArray());
        fragmentType = -1;
        fragments = null;
        return whole;
    }

    /** Sends a close frame; the caller closes the socket. */
    public void close(int code) {
        try {
            ws.close(code, "");
        } catch (IOException ignored) {
            // the connection is gone
        }
        if (transport != null) {
            transport.destroy();
        }
    }
}
