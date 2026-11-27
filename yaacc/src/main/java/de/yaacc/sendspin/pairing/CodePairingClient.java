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

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The client's side (role B) of the code-based pairing (issue #325, task 11.1): the player is asked to pair with a code, holds back
 * until its operator opened the pairing window, and then either shows the code of the attempt (dynamic) or uses its fixed code
 * (static); a CPace run authenticates both sides, and the client delivers a new long-term PSK sealed under the key of the run. A pure
 * state machine like {@link CodePairingServer}. The code is shown only during an attempt that the window gates (spec, "Forged
 * emission").
 */
public final class CodePairingClient {

    /** What the client offers: the formats of the dynamic flow and the fixed code of the static flow (null: none). */
    public static final class Offer {
        final boolean digits;
        final boolean qrCode;
        final String staticCode;

        public Offer(boolean digits, boolean qrCode, String staticCode) {
            this.digits = digits;
            this.qrCode = qrCode;
            this.staticCode = staticCode;
        }

        public boolean offersDigits() {
            return digits;
        }

        public boolean offersQrCode() {
            return qrCode;
        }

        /** The fixed 8 digit code of the static flow, or null if the client has none. */
        public String staticCode() {
            return staticCode;
        }

        /** Whether the client offers any code-based method. */
        public boolean offersAny() {
            return digits || qrCode || staticCode != null;
        }
    }

    /** Where the code goes to the operator: the display (digits or a QR code). */
    public interface Emitter {
        /** Shows the code of the attempt: the digits (the format {@code digits}) or the 24 bytes of the {@code qr_code} format. */
        void show(String digitsCode, byte[] qrCode);

        /** The attempt is over: nothing is shown. */
        void hide();
    }

    public enum State {
        IDLE, PENDING_WINDOW, INITIATED, AWAIT_AUTH, AWAIT_CONFIRM, AWAIT_FINALIZE, DONE, FAILED
    }

    private final NoiseSuite suite;
    private final byte[] h;
    private final SecureRandom random;
    private final PairingPort port;
    private final Offer offer;
    private final PairingWindow window;
    private final Emitter emitter;

    private State state = State.IDLE;
    private int pairingIndex;
    private boolean dynamic;
    private boolean qrFormat;
    private int round;
    private byte[] nonceB;
    private byte[] code; // the code as the PAKE takes it
    private Cpace.Party party;
    private byte[] psk;

    public CodePairingClient(NoiseSuite suite, byte[] h, SecureRandom random, PairingPort port, Offer offer, PairingWindow window, Emitter emitter) {
        this.suite = suite;
        this.h = h.clone();
        this.random = random;
        this.port = port;
        this.offer = offer;
        this.window = window;
        this.emitter = emitter;
    }

    public synchronized State state() {
        return state;
    }

    /** The new long-term PSK once, after {@link State#DONE}; then null. The caller zeroes it. */
    public synchronized byte[] takePsk() {
        byte[] out = psk;
        psk = null;
        return out;
    }

    /** The server's pairing activation names a method: the client holds back until its window is open, or declines the method. */
    public synchronized void onActivate(int index, String method, String format) throws IOException {
        pairingIndex = index;
        round = 0;
        if ("dynamic_pairing_code".equals(method) && (("digits".equals(format) && offer.digits) || ("qr_code".equals(format) && offer.qrCode))) {
            dynamic = true;
            qrFormat = "qr_code".equals(format);
        } else if ("static_pairing_code".equals(method) && offer.staticCode != null) {
            dynamic = false;
            code = offer.staticCode.getBytes(StandardCharsets.US_ASCII);
        } else {
            port.send(ControllerMessages.pairAbort("method_not_supported"));
            state = State.FAILED;
            return;
        }
        if (window.admitsAttempt()) {
            sendInit();
        } else {
            state = State.PENDING_WINDOW;
            port.send(CodePairingMessages.clientPairPending(pairingIndex, null));
        }
    }

    /** The operator's gesture opened the window while an attempt waited for it. */
    public synchronized void windowOpened() throws IOException {
        if (state == State.PENDING_WINDOW && window.admitsAttempt()) {
            sendInit();
        }
    }

    private void sendInit() throws IOException {
        state = State.INITIATED;
        round = 1;
        if (dynamic) {
            nonceB = new byte[PairingCodes.NONCE_BYTES];
            random.nextBytes(nonceB);
            port.send(CodePairingMessages.clientPairInit(pairingIndex, PairingCodes.commit(nonceB)));
        } else {
            port.send(CodePairingMessages.clientPairInit(pairingIndex, null));
        }
    }

    /** One message of the server. */
    public synchronized void onMessage(String type, JSONObject payload) throws IOException, ProtocolException {
        switch (type) {
            case "server/pair-init": {
                if (!dynamic || (state != State.INITIATED && state != State.AWAIT_CONFIRM && state != State.AWAIT_AUTH)) {
                    throw new ProtocolException("server/pair-init out of sequence");
                }
                byte[] nonceA = CodePairingMessages.parseServerPairInit(payload);
                if (code == null) { // the first round: it carries the server's nonce and the code is derived
                    if (nonceA == null) {
                        throw new ProtocolException("nonce_A");
                    }
                    byte[] digest = PairingCodes.codeDigest(h, nonceA, nonceB);
                    code = qrFormat ? PairingCodes.qrCode(digest) : PairingCodes.digitsCode(digest).getBytes(StandardCharsets.US_ASCII);
                } else if (nonceA != null) {
                    throw new ProtocolException("nonce_A only in the first round");
                } else {
                    round++;
                }
                if (!window.admitsAttempt()) {
                    return; // a window that closed meanwhile: nothing is emitted
                }
                emitter.show(qrFormat ? null : new String(code, StandardCharsets.US_ASCII), qrFormat ? code.clone() : null);
                state = State.AWAIT_AUTH;
                return;
            }
            case "server/pair-auth": {
                boolean ready = dynamic ? state == State.AWAIT_AUTH : state == State.INITIATED;
                if (!ready) {
                    throw new ProtocolException("server/pair-auth out of sequence");
                }
                byte[] share = CodePairingMessages.parseShare(payload, "pake_msg_1");
                if (party != null) {
                    party.destroy();
                }
                party = Cpace.responder(code, PairingCodes.pakeSid(h, pairingIndex, round), random);
                port.send(CodePairingMessages.clientPairAuth(party.share()));
                try {
                    party.finish(share);
                } catch (CpaceException e) {
                    throw new ProtocolException("pake_msg_1");
                }
                state = State.AWAIT_CONFIRM;
                return;
            }
            case "server/pair-confirm": {
                if (state != State.AWAIT_CONFIRM) {
                    throw new ProtocolException("server/pair-confirm out of sequence");
                }
                byte[] tag = CodePairingMessages.parseTag(payload, "server_kc");
                if (party.verifyPeerTag(tag)) {
                    confirmAndFinalize();
                } else {
                    failedVerification();
                }
                return;
            }
            case "server/pair-finalize": {
                if (state != State.AWAIT_FINALIZE) {
                    throw new ProtocolException("server/pair-finalize out of sequence");
                }
                state = State.DONE;
                window.completed();
                emitter.hide();
                return;
            }
            case "pair/abort": {
                state = State.FAILED;
                emitter.hide();
                return;
            }
            default:
                return;
        }
    }

    private void confirmAndFinalize() throws IOException, ProtocolException {
        byte[] sid = party.sessionId();
        byte[] isk = party.sessionKey();
        byte[] newPsk = new byte[32];
        do {
            random.nextBytes(newPsk);
        } while (SendspinPsk.isSentinel(newPsk));
        try {
            byte[] wrappedNonce = dynamic ? PairingCodes.wrapNonce(suite, sid, isk, nonceB) : null;
            port.send(CodePairingMessages.clientPairConfirm(party.tag(), wrappedNonce));
            port.send(CodePairingMessages.clientPairFinalizeWrapped(PairingCodes.wrapPsk(suite, sid, isk, newPsk)));
        } catch (NoiseException e) {
            throw new ProtocolException("wrapping");
        }
        psk = newPsk;
        state = State.AWAIT_FINALIZE;
        emitter.hide();
        party.destroy();
    }

    private void failedVerification() throws IOException {
        if (dynamic) {
            if (round >= CodePairingServerLimit.ROUND_LIMIT) {
                window.roundLimitReached();
                port.send(ControllerMessages.pairAbort("pairing_code_mismatch"));
                state = State.FAILED;
                emitter.hide();
            } else {
                port.send(CodePairingMessages.clientPairRetry());
                state = State.AWAIT_CONFIRM; // the next server/pair-init begins the next round
            }
        } else {
            window.staticAttemptFailed();
            port.send(ControllerMessages.pairAbort("pairing_code_mismatch"));
            state = State.FAILED;
        }
    }

    /** The limit of rounds, shared with the server's side. */
    private static final class CodePairingServerLimit {
        static final int ROUND_LIMIT = CodePairingServer.ROUND_LIMIT;
    }
}
