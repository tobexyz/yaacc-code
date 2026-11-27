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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The messages of one WebSocket connection (RFC 6455) after the opening handshake: complete text and binary messages in, frames
 * out. A subset that is enough for Sendspin and strict about what it reads: no extensions, a size cap for every message and frame,
 * reserved bits and unknown opcodes are errors, a client does not accept masked frames and a server only masked ones, control
 * frames are at most 125 bytes and never fragmented, a text message must be UTF-8, fragments of two messages never interleave.
 * Pings are answered here; a close is answered and ends the connection with a {@link ClosedException}; a violation sends a close
 * frame with the matching code and ends it with a {@link ViolationException}.
 * <p>
 * One thread reads ({@link #receive()}); any thread may send. A {@link SocketTimeoutException} from {@link #receive()} means that
 * nothing arrived and the connection is intact; a timeout inside a frame is a plain {@link IOException} (the connection is lost).
 */
public final class WsConnection implements Closeable {

    /** A complete message. */
    public static final class Message {
        private final boolean text;
        private final byte[] data;

        Message(boolean text, byte[] data) {
            this.text = text;
            this.data = data;
        }

        public boolean isText() {
            return text;
        }

        public byte[] data() {
            return data;
        }

        /** The text of a text message. */
        public String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** The peer closed the connection with a close frame (which has been answered). */
    public static final class ClosedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;

        ClosedException(int code, String reason) {
            super("closed by the peer: " + code + (reason.isEmpty() ? "" : " " + reason));
            this.code = code;
        }

        /** The code of the close frame; 1005 if it had none. */
        public int code() {
            return code;
        }
    }

    /** The peer broke the rules of the protocol; a close frame with {@link #code()} was sent. */
    public static final class ViolationException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;

        ViolationException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_INVALID_DATA = 1007;
    public static final int CLOSE_TOO_BIG = 1009;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;
    private static final int MAX_CONTROL_PAYLOAD = 125;
    /** A write that has not ended after this long is broken: the peer does not read and the socket is full. */
    private static final long DEFAULT_WRITE_STALL_MILLIS = 10_000L;

    private final InputStream in;
    private final OutputStream out;
    private final boolean client;
    private final int maxMessageBytes;
    private final SecureRandom random;
    private final Object writeLock = new Object();

    private int fragmentOpcode = -1;
    private ByteArrayOutputStream fragments;
    private boolean closeSent;
    private volatile long lastPongNanos;
    /** When the write in progress began ({@link System#nanoTime()}), 0 if none is. */
    private volatile long writingSinceNanos;
    private volatile long writeStallNanos = DEFAULT_WRITE_STALL_MILLIS * 1_000_000L;

    /**
     * @param client          true for the side that opened the connection (it masks what it sends)
     * @param maxMessageBytes the largest message (and frame) this side accepts
     */
    public WsConnection(InputStream in, OutputStream out, boolean client, int maxMessageBytes, SecureRandom random) {
        this.in = in;
        this.out = out;
        this.client = client;
        this.maxMessageBytes = maxMessageBytes;
        this.random = random;
    }

    /** The time of the last pong, from {@link System#nanoTime()}; 0 if none came. */
    public long lastPongNanos() {
        return lastPongNanos;
    }

    // ------------------------------------------------------------------ reading

    /** Blocks for the next complete message. */
    public Message receive() throws IOException {
        while (true) {
            int b0 = in.read(); // a timeout here leaves the connection intact
            if (b0 < 0) {
                throw new IOException("connection closed without a close frame");
            }
            try {
                Message message = readFrame(b0);
                if (message != null) {
                    return message;
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("timeout inside a frame");
            }
        }
    }

    private Message readFrame(int b0) throws IOException {
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        if ((b0 & 0x70) != 0) {
            throw violation(CLOSE_PROTOCOL_ERROR, "reserved bits set");
        }
        int b1 = readByte();
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (masked == client) {
            throw violation(CLOSE_PROTOCOL_ERROR, client ? "masked frame from the server" : "unmasked frame from the client");
        }
        boolean control = opcode >= 0x8;
        if (control && (!fin || length > MAX_CONTROL_PAYLOAD)) {
            throw violation(CLOSE_PROTOCOL_ERROR, "bad control frame");
        }
        if (length == 126) {
            length = (readByte() << 8) | readByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte();
            }
            if (length < 0) {
                throw violation(CLOSE_PROTOCOL_ERROR, "bad length");
            }
        }
        if (length > maxMessageBytes) {
            throw violation(CLOSE_TOO_BIG, "frame too big");
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        switch (opcode) {
            case OP_PING:
                sendFrame(OP_PONG, payload);
                return null;
            case OP_PONG:
                lastPongNanos = System.nanoTime();
                return null;
            case OP_CLOSE:
                throw handleClose(payload);
            case OP_TEXT:
            case OP_BINARY:
                if (fragmentOpcode >= 0) {
                    throw violation(CLOSE_PROTOCOL_ERROR, "a new message inside a fragmented one");
                }
                if (fin) {
                    return complete(opcode, payload);
                }
                fragmentOpcode = opcode;
                fragments = new ByteArrayOutputStream();
                fragments.write(payload, 0, payload.length);
                return null;
            case OP_CONTINUATION:
                if (fragmentOpcode < 0) {
                    throw violation(CLOSE_PROTOCOL_ERROR, "a continuation without a message");
                }
                if ((long) fragments.size() + payload.length > maxMessageBytes) {
                    throw violation(CLOSE_TOO_BIG, "message too big");
                }
                fragments.write(payload, 0, payload.length);
                if (!fin) {
                    return null;
                }
                int type = fragmentOpcode;
                byte[] whole = fragments.toByteArray();
                fragmentOpcode = -1;
                fragments = null;
                return complete(type, whole);
            default:
                throw violation(CLOSE_PROTOCOL_ERROR, "unknown opcode");
        }
    }

    private Message complete(int opcode, byte[] data) throws IOException {
        if (opcode == OP_TEXT) {
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            } catch (CharacterCodingException e) {
                throw violation(CLOSE_INVALID_DATA, "text is not UTF-8");
            }
            return new Message(true, data);
        }
        return new Message(false, data);
    }

    private IOException handleClose(byte[] payload) throws IOException {
        int code = 1005;
        String reason = "";
        if (payload.length == 1) {
            return violation(CLOSE_PROTOCOL_ERROR, "bad close frame");
        }
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            boolean valid = (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1011) || (code >= 3000 && code <= 4999);
            if (!valid) {
                return violation(CLOSE_PROTOCOL_ERROR, "bad close code");
            }
            try {
                reason = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload, 2, payload.length - 2)).toString();
            } catch (CharacterCodingException e) {
                return violation(CLOSE_INVALID_DATA, "close reason is not UTF-8");
            }
        }
        sendCloseOnce(code == 1005 ? CLOSE_NORMAL : code, "");
        return new ClosedException(code, reason.length() > 60 ? reason.substring(0, 60) : reason);
    }

    private ViolationException violation(int code, String message) {
        try {
            sendCloseOnce(code, "");
        } catch (IOException ignored) {
            // the connection is gone anyway
        }
        return new ViolationException(code, message);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("connection closed inside a frame");
        }
        return b;
    }

    private void readFully(byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer, off, buffer.length - off);
            if (n < 0) {
                throw new IOException("connection closed inside a frame");
            }
            off += n;
        }
    }

    // ------------------------------------------------------------------ writing

    public void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(byte[] data) throws IOException {
        sendFrame(OP_BINARY, data);
    }

    public void sendPing(byte[] payload) throws IOException {
        if (payload.length > MAX_CONTROL_PAYLOAD) {
            throw new IllegalArgumentException("a ping holds at most 125 bytes");
        }
        sendFrame(OP_PING, payload);
    }

    /** Sends a close frame (once) and flushes; the caller closes the streams. */
    public void close(int code, String reason) throws IOException {
        sendCloseOnce(code, reason);
    }

    @Override
    public void close() throws IOException {
        sendCloseOnce(CLOSE_NORMAL, "");
    }

    private void sendCloseOnce(int code, String reason) throws IOException {
        synchronized (writeLock) {
            if (closeSent) {
                return;
            }
            closeSent = true;
        }
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + Math.min(text.length, MAX_CONTROL_PAYLOAD - 2)];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, payload.length - 2);
        writeFrame(OP_CLOSE, payload);
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (closeSent) {
                throw new IOException("connection is closing");
            }
        }
        writeFrame(opcode, payload);
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(14);
        header.write(0x80 | opcode);
        int maskBit = client ? 0x80 : 0;
        if (payload.length < 126) {
            header.write(maskBit | payload.length);
        } else if (payload.length <= 0xFFFF) {
            header.write(maskBit | 126);
            header.write(payload.length >> 8);
            header.write(payload.length);
        } else {
            header.write(maskBit | 127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                header.write((int) (length >> shift));
            }
        }
        byte[] body = payload;
        if (client) {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            header.write(mask, 0, 4);
            body = Arrays.copyOf(payload, payload.length);
            for (int i = 0; i < body.length; i++) {
                body[i] ^= mask[i & 3];
            }
        }
        synchronized (writeLock) {
            writingSinceNanos = System.nanoTime();
            WriteWatchdog.watch(this);
            try {
                out.write(header.toByteArray());
                out.write(body);
                out.flush();
            } finally {
                writingSinceNanos = 0;
                WriteWatchdog.unwatch(this);
            }
        }
    }

    /** For tests: how long a write may last before it is broken. */
    void setWriteStallMillis(long millis) {
        writeStallNanos = millis * 1_000_000L;
    }

    /** Closes the output (which closes the socket) so that a write that waits for ever fails. From a thread of its own: a close may wait. */
    private void breakStalledWrite() {
        Thread closer = new Thread(() -> {
            try {
                out.close();
            } catch (IOException ignored) {
                // it is closing anyway
            }
        }, "sendspin-write-closer");
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * Looks twice a second at the connections that are writing, and breaks a write that lasts too long. The thread runs only while a
     * write is in progress and ends when none is.
     */
    private static final class WriteWatchdog implements Runnable {
        private static final java.util.Set<WsConnection> WRITING = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static Thread thread;

        static void watch(WsConnection c) {
            WRITING.add(c);
            synchronized (WriteWatchdog.class) {
                if (thread == null) {
                    thread = new Thread(new WriteWatchdog(), "sendspin-write-watchdog");
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }

        static void unwatch(WsConnection c) {
            WRITING.remove(c);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Thread.sleep(250);
                    long now = System.nanoTime();
                    for (WsConnection c : WRITING) {
                        long since = c.writingSinceNanos;
                        if (since != 0 && now - since > c.writeStallNanos) {
                            WRITING.remove(c);
                            c.breakStalledWrite();
                        }
                    }
                    synchronized (WriteWatchdog.class) {
                        if (WRITING.isEmpty()) {
                            thread = null;
                            return;
                        }
                    }
                }
            } catch (InterruptedException e) {
                synchronized (WriteWatchdog.class) {
                    thread = null;
                }
            }
        }
    }
}
