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
package de.yaacc.multiroom.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Encodes and decodes the binary messages of the Snapcast stream protocol (little endian, the layouts of
 * {@code docs/tech.md}, "Binary protocol"). Everything here is a pure function of bytes; the network is
 * {@link FrameReader}.
 *
 * <p><b>Rules for what comes from the server</b> (design, "Security rules for Phase B"): the size in the header is
 * checked against its cap before a body is read ({@link MessageHeader#parse}), every length inside a message is checked
 * against what is left of the message before anything is reserved for it, JSON goes through the depth and token
 * pre-scan of the control client, and the only failure that leaves this class is a
 * {@link SnapcastProtocolException}. Types this client does not handle (and the base type 0) are skipped by size, with
 * no message.
 */
public final class SnapcastMessageCodec {

    private SnapcastMessageCodec() {
    }

    // ---- encoding

    /**
     * One complete frame: the 26 byte header and the typed message.
     *
     * @param id          the id of this message (0 to 65535)
     * @param refersTo    the id of the message this answers (0 when it answers none)
     * @param sentSec     the time of sending, filled in by the sender
     * @param receivedSec the time of receiving, filled in by the receiver (0 when we send)
     */
    public static byte[] encode(SnapcastBinaryMessage message, int id, int refersTo, int sentSec, int sentUsec,
                                int receivedSec, int receivedUsec) {
        if (message == null) {
            throw new IllegalArgumentException("no message");
        }
        byte[] body = encodeBody(message);
        MessageHeader header = new MessageHeader(message.type().id(), id, refersTo, sentSec, sentUsec, receivedSec,
                receivedUsec, body.length);
        ByteBuffer out = ByteBuffer.allocate(SnapcastProtocolLimits.HEADER_SIZE + body.length).order(ByteOrder.LITTLE_ENDIAN);
        header.writeTo(out);
        out.put(body);
        return out.array();
    }

    /** As above with the send time as microseconds (clamped into the range of 32 bit seconds) and no receive time. */
    public static byte[] encode(SnapcastBinaryMessage message, int id, int refersTo, long sentMicros) {
        long seconds = Math.floorDiv(sentMicros, 1_000_000L);
        int sec = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, seconds));
        return encode(message, id, refersTo, sec, (int) Math.floorMod(sentMicros, 1_000_000L), 0, 0);
    }

    private static byte[] encodeBody(SnapcastBinaryMessage message) {
        switch (message.type()) {
            case HELLO:
                return ((HelloMessage) message).encodeBody();
            case SERVER_SETTINGS:
                return ((ServerSettingsMessage) message).encodeBody();
            case CODEC_HEADER:
                return ((CodecHeaderMessage) message).encodeBody();
            case WIRE_CHUNK:
                return ((WireChunkMessage) message).encodeBody();
            case TIME:
                return ((TimeMessage) message).encodeBody();
            case CLIENT_INFO:
                return ((ClientInfoMessage) message).encodeBody();
            case ERROR:
                return ((ErrorMessage) message).encodeBody();
            default:
                throw new IllegalArgumentException("unknown message type");
        }
    }

    // ---- decoding

    /**
     * Reads the base header and checks its size against the cap of the type. Nothing is read or reserved for the body.
     *
     * @throws SnapcastProtocolException TRUNCATED, NEGATIVE_SIZE or TOO_LARGE
     */
    static MessageHeader parseHeader(byte[] header) throws SnapcastProtocolException {
        return MessageHeader.parse(header);
    }

    /**
     * Decodes a body of exactly {@code header.getSize()} bytes.
     *
     * @return the message, or null for a type that is skipped
     * @throws SnapcastProtocolException for anything the server got wrong
     */
    static SnapcastBinaryMessage decodeBody(MessageHeader header, byte[] body) throws SnapcastProtocolException {
        MessageType type = MessageType.of(header.getType());
        if (type == null) {
            return null;
        }
        try {
            ByteReader reader = new ByteReader(body);
            switch (type) {
                case HELLO:
                    return HelloMessage.decode(reader);
                case SERVER_SETTINGS:
                    return ServerSettingsMessage.decode(reader);
                case CODEC_HEADER:
                    return CodecHeaderMessage.decode(reader);
                case WIRE_CHUNK:
                    return WireChunkMessage.decode(reader);
                case TIME:
                    return TimeMessage.decode(reader);
                case CLIENT_INFO:
                    return ClientInfoMessage.decode(reader);
                case ERROR:
                    return ErrorMessage.decode(reader);
                default:
                    return null;
            }
        } catch (SnapcastProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            // no cause: nothing of the message goes into an exception that may be logged
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.MALFORMED, "message could not be read");
        }
    }

    /**
     * Decodes one complete frame held in a byte array.
     *
     * @throws SnapcastProtocolException TRUNCATED when the array ends early, MALFORMED when there are bytes after the
     *                                   frame, and the errors of the header and the body
     */
    public static Frame decode(byte[] frame) throws SnapcastProtocolException {
        MessageHeader header = parseHeader(frame);
        int available = frame.length - SnapcastProtocolLimits.HEADER_SIZE;
        if (available < header.getSize()) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TRUNCATED, "message cut short");
        }
        if (available > header.getSize()) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.MALFORMED, "bytes after the message");
        }
        byte[] body = Arrays.copyOfRange(frame, SnapcastProtocolLimits.HEADER_SIZE,
                SnapcastProtocolLimits.HEADER_SIZE + header.getSize());
        return new Frame(header, decodeBody(header, body));
    }
}
