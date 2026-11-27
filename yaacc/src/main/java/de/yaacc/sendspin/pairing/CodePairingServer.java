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
package de.yaacc.sendspin.pairing;

import de.yaacc.sendspin.secure.Cpace;
import de.yaacc.sendspin.secure.CpaceException;
import de.yaacc.sendspin.secure.NoiseException;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.PairingCodes;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.CodePairingMessages;
import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.ProtocolException;
import de.yaacc.sendspin.session.SendspinMessages;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The server's side (role A) of the code-based pairing (issue #325, task 11.1; spec, "Dynamic Pairing Code Flow" and "Static Pairing
 * Code Flow"): the operator chose a method for a player, the player shows or knows a code, the operator types it (or scans its QR
 * code), a CPace run authenticates both sides and the player delivers a new long-term PSK sealed under the key of the run. A pure
 * state machine: the connection feeds it the messages of the player and the operator's entry and gives it a {@link PairingPort}; it
 * never touches the socket. A violation of the sequence or of a value is a {@link ProtocolException} (the connection closes). Methods
 * are synchronized: the operator's entry comes from another thread.
 */
public final class CodePairingServer {

    /** What the operator chose. */
    public enum Method {
        DYNAMIC_DIGITS("dynamic_pairing_code", "digits", PairingCodes.DYNAMIC_DIGITS),
        DYNAMIC_QR("dynamic_pairing_code", "qr_code", 0),
        STATIC("static_pairing_code", null, PairingCodes.STATIC_DIGITS);

        final String wireName;
        final String format;
        final int digits;

        Method(String wireName, String format, int digits) {
            this.wireName = wireName;
            this.format = format;
            this.digits = digits;
        }

        public boolean isDynamic() {
            return this != STATIC;
        }
    }

    public enum State {
        /** The activation is sent; the player has not started the attempt. */
        ACTIVATED,
        /** The player holds back (no pairing window): the operator waits for its gesture. */
        WAITING_WINDOW,
        /** The operator is to enter the code. */
        WAITING_CODE,
        AUTH_SENT, CONFIRM_SENT, CONFIRMED, DONE, FAILED
    }

    public enum Failure {
        NONE,
        /** The key confirmation failed: the code was not the right one (or the rounds ran out). */
        CODE_MISMATCH,
        /** The player gave the attempt up (its operator cancelled, a timeout, another attempt). */
        ABORTED_BY_DEVICE,
        OTHER
    }

    /** The most rounds of one attempt. */
    public static final int ROUND_LIMIT = 20;

    private final NoiseSuite suite;
    private final byte[] h;
    private final int pairingIndex;
    private final Method method;
    private final SecureRandom random;
    private final PairingPort port;

    private State state = State.ACTIVATED;
    private Failure failure = Failure.NONE;
    private int round;
    private String deviceMessage;
    private byte[] commit;
    private byte[] nonceA;
    private byte[] entered; // the pairing code as the PAKE takes it (the digits as ASCII, or the 24 raw bytes)
    private Cpace.Party party;
    private byte[] psk;

    public CodePairingServer(NoiseSuite suite, byte[] h, int pairingIndex, Method method, SecureRandom random, PairingPort port) {
        this.suite = suite;
        this.h = h.clone();
        this.pairingIndex = pairingIndex;
        this.method = method;
        this.random = random;
        this.port = port;
    }

    // ------------------------------------------------------------------ what the app can read

    public synchronized State state() {
        return state;
    }

    public synchronized Failure failure() {
        return failure;
    }

    /** The round of a dynamic attempt (1 for the first); 1 in a static attempt. */
    public synchronized int round() {
        return Math.max(1, round);
    }

    public Method method() {
        return method;
    }

    /** The sentence the player sent while it holds back (shown as the device's, not as the app's), or null. */
    public synchronized String deviceMessage() {
        return deviceMessage;
    }

    /** The new long-term PSK once, after {@link State#DONE}; then null. The caller zeroes it. */
    public synchronized byte[] takePsk() {
        byte[] out = psk;
        psk = null;
        return out;
    }

    // ------------------------------------------------------------------ the attempt

    /** Sends the activation that names the method. */
    public synchronized void start() throws IOException {
        port.send(CodePairingMessages.serverActivate(method.wireName, method.format));
    }

    /**
     * The operator's entry: the typed code (the dynamic digits, or the static digits) or, for the QR format, the scanned or pasted
     * token. Accepted only when the attempt waits for it (a static code may also be given before the player has started).
     *
     * @return false if the entry is malformed or not wanted now (the attempt waits for another)
     */
    public synchronized boolean provideCode(String text) throws IOException {
        boolean early = method == Method.STATIC && (state == State.ACTIVATED || state == State.WAITING_WINDOW);
        if (state != State.WAITING_CODE && !early) {
            return false;
        }
        byte[] code;
        if (method == Method.DYNAMIC_QR) {
            code = PairingCodes.decodeCodeToken(text);
        } else {
            String digits = PairingCodes.normalizeDigits(text, method.digits);
            code = digits == null ? null : digits.getBytes(StandardCharsets.US_ASCII);
        }
        if (code == null) {
            return false;
        }
        entered = code;
        if (state == State.WAITING_CODE) {
            sendAuth();
        }
        return true;
    }

    private void sendAuth() throws IOException {
        if (party != null) {
            party.destroy();
        }
        party = Cpace.initiator(entered, PairingCodes.pakeSid(h, pairingIndex, round()), random);
        port.send(CodePairingMessages.serverPairAuth(party.share()));
        state = State.AUTH_SENT;
    }

    /** One message of the player. */
    public synchronized void onMessage(String type, JSONObject payload) throws IOException, ProtocolException {
        switch (type) {
            case "client/pair-pending": {
                CodePairingMessages.Pending pending = CodePairingMessages.parsePending(payload);
                if (pending.pairingIndex < pairingIndex) {
                    return; // a leftover of a superseded pairing
                }
                if (pending.pairingIndex > pairingIndex || (state != State.ACTIVATED && state != State.WAITING_WINDOW)) {
                    throw new ProtocolException("client/pair-pending out of sequence");
                }
                deviceMessage = pending.message;
                state = State.WAITING_WINDOW;
                return;
            }
            case "client/pair-init": {
                CodePairingMessages.PairInit init = CodePairingMessages.parsePairInit(payload);
                if (init.pairingIndex < pairingIndex) {
                    return;
                }
                if (init.pairingIndex > pairingIndex || (state != State.ACTIVATED && state != State.WAITING_WINDOW)) {
                    throw new ProtocolException("client/pair-init out of sequence");
                }
                if (method.isDynamic()) {
                    if (init.commit == null) {
                        throw new ProtocolException("commit_B");
                    }
                    commit = init.commit;
                    nonceA = new byte[PairingCodes.NONCE_BYTES];
                    random.nextBytes(nonceA);
                    round = 1;
                    port.send(CodePairingMessages.serverPairInit(nonceA));
                    state = State.WAITING_CODE;
                } else if (entered != null) {
                    round = 1;
                    sendAuth();
                } else {
                    round = 1;
                    state = State.WAITING_CODE;
                }
                return;
            }
            case "client/pair-auth": {
                if (state != State.AUTH_SENT) {
                    throw new ProtocolException("client/pair-auth out of sequence");
                }
                byte[] share = CodePairingMessages.parseShare(payload, "pake_msg_2");
                try {
                    party.finish(share);
                } catch (CpaceException e) {
                    throw new ProtocolException("pake_msg_2");
                }
                port.send(CodePairingMessages.serverPairConfirm(party.tag()));
                state = State.CONFIRM_SENT;
                return;
            }
            case "client/pair-retry": {
                if (state != State.CONFIRM_SENT || !method.isDynamic()) {
                    throw new ProtocolException("client/pair-retry out of sequence");
                }
                if (round >= ROUND_LIMIT) {
                    abort("pairing_code_mismatch", Failure.CODE_MISMATCH);
                    return;
                }
                round++;
                entered = null;
                port.send(CodePairingMessages.serverPairInit(null));
                state = State.WAITING_CODE;
                return;
            }
            case "client/pair-confirm": {
                if (state != State.CONFIRM_SENT) {
                    throw new ProtocolException("client/pair-confirm out of sequence");
                }
                CodePairingMessages.ClientConfirm confirm = CodePairingMessages.parseClientConfirm(payload);
                if (!party.verifyPeerTag(confirm.tag)) {
                    abort("pairing_code_mismatch", Failure.CODE_MISMATCH);
                    return;
                }
                if (method.isDynamic()) {
                    verifyBinding(confirm);
                }
                state = State.CONFIRMED;
                return;
            }
            case "client/pair-finalize": {
                if (state != State.CONFIRMED) {
                    throw new ProtocolException("client/pair-finalize out of sequence");
                }
                byte[] wrapped = CodePairingMessages.parseWrappedPsk(payload);
                byte[] value;
                try {
                    value = PairingCodes.unwrapPsk(suite, party.sessionId(), party.sessionKey(), wrapped);
                } catch (NoiseException e) {
                    throw new ProtocolException("wrapped_psk");
                }
                if (SendspinPsk.isSentinel(value)) {
                    throw new ProtocolException("long-term PSK");
                }
                psk = value;
                state = State.DONE;
                party.destroy();
                return;
            }
            case "pair/abort": {
                String reason = SendspinMessages.parsePairAbortReason(payload);
                failure = "pairing_code_mismatch".equals(reason) ? Failure.CODE_MISMATCH : Failure.ABORTED_BY_DEVICE;
                state = State.FAILED;
                return;
            }
            default:
                return; // not a pairing message of this attempt: the connection handles it
        }
    }

    /** The dynamic flow's checks after the tag: the commitment opens to {@code nonce_B}, and the code entered is the one derived. */
    private void verifyBinding(CodePairingMessages.ClientConfirm confirm) throws ProtocolException {
        if (confirm.wrappedNonceB == null) {
            throw new ProtocolException("wrapped_nonce_B");
        }
        byte[] nonceB;
        try {
            nonceB = PairingCodes.unwrapNonce(suite, party.sessionId(), party.sessionKey(), confirm.wrappedNonceB);
        } catch (NoiseException e) {
            throw new ProtocolException("wrapped_nonce_B");
        }
        if (!MessageDigest.isEqual(PairingCodes.commit(nonceB), commit)) {
            throw new ProtocolException("commit_B");
        }
        byte[] digest = PairingCodes.codeDigest(h, nonceA, nonceB);
        byte[] derived = method == Method.DYNAMIC_QR ? PairingCodes.qrCode(digest) : PairingCodes.digitsCode(digest).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(derived, entered)) {
            throw new ProtocolException("pairing code binding");
        }
    }

    private void abort(String reason, Failure why) throws IOException {
        port.send(ControllerMessages.pairAbort(reason));
        failure = why;
        state = State.FAILED;
        if (party != null) {
            party.destroy();
        }
    }

    /** The operator gave up. */
    public synchronized void cancel() throws IOException {
        if (state != State.DONE && state != State.FAILED) {
            abort("user_cancelled", Failure.OTHER);
        }
    }

    public synchronized byte[] sessionKeyForTests() {
        return party == null ? null : Arrays.copyOf(party.sessionKey(), 64);
    }
}
