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

import java.util.Arrays;

/**
 * The transport mode after a Noise handshake: one cipher per direction, a 64 bit counter each. A message that does not verify,
 * a repeated or reordered one included (the counter is implicit), ends the connection: the caller closes on a
 * {@link NoiseException}. One thread may encrypt and another decrypt at the same time.
 */
public final class NoiseTransport {

    /** The most a Noise message may hold (Noise, section 3). */
    public static final int MAX_MESSAGE_LENGTH = 65535;
    /** The most plaintext one transport message holds: the length less the tag. */
    public static final int MAX_PLAINTEXT_LENGTH = MAX_MESSAGE_LENGTH - NoiseSuite.TAG_LENGTH;

    private final NoiseSuite suite;
    private final byte[] sendKey;
    private final byte[] receiveKey;
    private final byte[] handshakeHash;
    private long sendCounter;
    private long receiveCounter;

    NoiseTransport(NoiseSuite suite, byte[] sendKey, byte[] receiveKey, byte[] handshakeHash) {
        this.suite = suite;
        this.sendKey = sendKey;
        this.receiveKey = receiveKey;
        this.handshakeHash = handshakeHash;
    }

    public NoiseSuite suite() {
        return suite;
    }

    /** The hash of the handshake that made this transport: the prologue of a re-handshake. */
    public byte[] handshakeHash() {
        return handshakeHash.clone();
    }

    public synchronized byte[] encrypt(byte[] plaintext) throws NoiseException {
        if (plaintext.length > MAX_PLAINTEXT_LENGTH) {
            throw new NoiseException("message too long");
        }
        return suite.encrypt(sendKey, sendCounter++, new byte[0], plaintext);
    }

    public byte[] decrypt(byte[] ciphertext) throws NoiseException {
        synchronized (receiveKey) {
            if (ciphertext.length > MAX_MESSAGE_LENGTH) {
                throw new NoiseException("message too long");
            }
            return suite.decrypt(receiveKey, receiveCounter++, new byte[0], ciphertext);
        }
    }

    public void destroy() {
        Arrays.fill(sendKey, (byte) 0);
        Arrays.fill(receiveKey, (byte) 0);
        Arrays.fill(handshakeHash, (byte) 0);
    }
}
