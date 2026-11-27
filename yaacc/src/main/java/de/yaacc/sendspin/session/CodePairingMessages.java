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

import de.yaacc.sendspin.secure.Base64Url;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * The messages of the code-based pairing (issue #325, task 11.1; spec, "Pairing", "Messages"): {@code server/activate} for a method,
 * {@code client/pair-pending}, {@code client/pair-init} with the commitment, {@code server/pair-init}, {@code server/pair-auth},
 * {@code client/pair-auth}, {@code server/pair-confirm}, {@code client/pair-confirm}, {@code client/pair-retry} and the wrapped
 * {@code client/pair-finalize}. Both directions in one place, strict on what is read (lengths of the encoded values, ranges): the peer
 * is not trusted. Pure.
 */
public final class CodePairingMessages {

    private static final int MAX_PENDING_BYTES = 200;

    private CodePairingMessages() {
    }

    // ------------------------------------------------------------------ building

    /** {@code server/activate} that starts a code-based attempt: the {@code pairing} activity, no roles, the method and its format. */
    public static String serverActivate(String method, String format) {
        JSONObject pairing = SendspinMessages.json("method", method);
        if (format != null) {
            SendspinMessages.put(pairing, "format", format);
        }
        return SendspinMessages.message("server/activate", SendspinMessages.json("activities",
                new JSONArray(Collections.singletonList("pairing")), "active_roles", new JSONArray(), "pairing", pairing));
    }

    public static String clientPairPending(int pairingIndex, String message) {
        JSONObject payload = SendspinMessages.json("pairing_index", pairingIndex);
        if (message != null) {
            SendspinMessages.put(payload, "message", clip(message, MAX_PENDING_BYTES));
        }
        return SendspinMessages.message("client/pair-pending", payload);
    }

    /** {@code client/pair-init}; {@code commit} (32 bytes) is the commitment of the dynamic flow, null in the static flow. */
    public static String clientPairInit(int pairingIndex, byte[] commit) {
        JSONObject payload = SendspinMessages.json("pairing_index", pairingIndex);
        if (commit != null) {
            SendspinMessages.put(payload, "commit_B", Base64Url.encode(commit));
        }
        return SendspinMessages.message("client/pair-init", payload);
    }

    /** {@code server/pair-init}; {@code nonceA} (32 bytes) in the first round, null in the later ones. */
    public static String serverPairInit(byte[] nonceA) {
        JSONObject payload = new JSONObject();
        if (nonceA != null) {
            SendspinMessages.put(payload, "nonce_A", Base64Url.encode(nonceA));
        }
        return SendspinMessages.message("server/pair-init", payload);
    }

    public static String serverPairAuth(byte[] share) {
        return SendspinMessages.message("server/pair-auth", SendspinMessages.json("pake_msg_1", Base64Url.encode(share)));
    }

    public static String clientPairAuth(byte[] share) {
        return SendspinMessages.message("client/pair-auth", SendspinMessages.json("pake_msg_2", Base64Url.encode(share)));
    }

    public static String serverPairConfirm(byte[] tag) {
        return SendspinMessages.message("server/pair-confirm", SendspinMessages.json("server_kc", Base64Url.encode(tag)));
    }

    /** {@code client/pair-confirm}; {@code wrappedNonceB} (48 bytes) only in the dynamic flow. */
    public static String clientPairConfirm(byte[] tag, byte[] wrappedNonceB) {
        JSONObject payload = SendspinMessages.json("client_kc", Base64Url.encode(tag));
        if (wrappedNonceB != null) {
            SendspinMessages.put(payload, "wrapped_nonce_B", Base64Url.encode(wrappedNonceB));
        }
        return SendspinMessages.message("client/pair-confirm", payload);
    }

    public static String clientPairRetry() {
        return SendspinMessages.message("client/pair-retry", new JSONObject());
    }

    /** {@code client/pair-finalize} of the code-based flows: the long-term PSK wrapped under the key of the run (48 bytes). */
    public static String clientPairFinalizeWrapped(byte[] wrappedPsk) {
        return SendspinMessages.message("client/pair-finalize", SendspinMessages.json("wrapped_psk", Base64Url.encode(wrappedPsk)));
    }

    public static String serverPairFinalize() {
        return SendspinMessages.serverPairFinalize();
    }

    private static String clip(String text, int maxBytes) {
        String cut = text;
        while (cut.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    // ------------------------------------------------------------------ reading

    private static byte[] exact(JSONObject p, String key, int length) throws ProtocolException {
        String text = SendspinMessages.text(p, key, length * 2, true);
        byte[] bytes = Base64Url.decode(text);
        if (bytes == null || bytes.length != length) {
            throw new ProtocolException(key);
        }
        return bytes;
    }

    /** The pairing index and the commitment of a {@code client/pair-init}. */
    public static final class PairInit {
        public final int pairingIndex;
        /** 32 bytes, or null (the static flow and the Pairing PSK flow have none). */
        public final byte[] commit;

        PairInit(int pairingIndex, byte[] commit) {
            this.pairingIndex = pairingIndex;
            this.commit = commit;
        }
    }

    public static PairInit parsePairInit(JSONObject p) throws ProtocolException {
        int index = SendspinMessages.integer(p, "pairing_index", 0, Integer.MAX_VALUE, true);
        return new PairInit(index, p.has("commit_B") ? exact(p, "commit_B", 32) : null);
    }

    /** A {@code client/pair-pending}: the index and the sentence for the operator (from an unauthenticated peer: shown as the device's). */
    public static final class Pending {
        public final int pairingIndex;
        /** At most 200 bytes, or null. */
        public final String message;

        Pending(int pairingIndex, String message) {
            this.pairingIndex = pairingIndex;
            this.message = message;
        }
    }

    public static Pending parsePending(JSONObject p) throws ProtocolException {
        return new Pending(SendspinMessages.integer(p, "pairing_index", 0, Integer.MAX_VALUE, true),
                SendspinMessages.text(p, "message", MAX_PENDING_BYTES, false));
    }

    /** @return the 32 byte nonce of a {@code server/pair-init}, or null in a round that has none */
    public static byte[] parseServerPairInit(JSONObject p) throws ProtocolException {
        return p.has("nonce_A") ? exact(p, "nonce_A", 32) : null;
    }

    /** A share ({@code pake_msg_1} or {@code pake_msg_2}), 32 bytes. */
    public static byte[] parseShare(JSONObject p, String key) throws ProtocolException {
        return exact(p, key, 32);
    }

    /** A confirmation tag ({@code server_kc}), 64 bytes. */
    public static byte[] parseTag(JSONObject p, String key) throws ProtocolException {
        return exact(p, key, 64);
    }

    /** A {@code client/pair-confirm}. */
    public static final class ClientConfirm {
        public final byte[] tag;
        /** 48 bytes in the dynamic flow, else null. */
        public final byte[] wrappedNonceB;

        ClientConfirm(byte[] tag, byte[] wrappedNonceB) {
            this.tag = tag;
            this.wrappedNonceB = wrappedNonceB;
        }
    }

    public static ClientConfirm parseClientConfirm(JSONObject p) throws ProtocolException {
        return new ClientConfirm(exact(p, "client_kc", 64), p.has("wrapped_nonce_B") ? exact(p, "wrapped_nonce_B", 48) : null);
    }

    /** The 48 bytes of {@code wrapped_psk}. */
    public static byte[] parseWrappedPsk(JSONObject p) throws ProtocolException {
        return exact(p, "wrapped_psk", 48);
    }

    /**
     * The {@code supported_pair_methods} of a {@code client/hello}: the Pairing PSK, which every client offers, and the code-based methods
     * of {@code codes} (the formats of the dynamic flow, the static flow); null: the Pairing PSK only.
     */
    public static JSONObject pairMethods(de.yaacc.sendspin.pairing.CodePairingClient.Offer codes) {
        JSONObject methods = SendspinMessages.json("pairing_psk", new JSONObject());
        if (codes != null && (codes.offersDigits() || codes.offersQrCode())) {
            JSONArray codeFormats = new JSONArray();
            if (codes.offersDigits()) {
                codeFormats.put("digits");
            }
            if (codes.offersQrCode()) {
                codeFormats.put("qr_code");
            }
            SendspinMessages.put(methods, "dynamic_pairing_code", SendspinMessages.json("out_channels",
                    new JSONArray(Collections.singletonList("display")), "formats", codeFormats));
        }
        if (codes != null && codes.staticCode() != null) {
            SendspinMessages.put(methods, "static_pairing_code", SendspinMessages.json("locations",
                    new JSONArray(Collections.singletonList("device"))));
        }
        return methods;
    }
}
