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
package de.yaacc.sendspin.client;

import java.io.IOException;
import java.security.SecureRandom;

import org.json.JSONObject;

import de.yaacc.sendspin.pairing.CodePairingClient;
import de.yaacc.sendspin.pairing.PairingWindow;
import de.yaacc.sendspin.secure.NoiseSuite;
import de.yaacc.sendspin.secure.SendspinPsk;
import de.yaacc.sendspin.session.ProtocolException;
import de.yaacc.sendspin.session.SecureChannel;

/**
 * The part of a client's connection that pairs with a code (issue #325, task 11.1), shared by the player session and the controller
 * session: what the client offers, its pairing window and the display of the code, and the attempt that runs on this connection. The
 * connection hands the messages of the pairing to it and takes the Pairing PSK from it when the attempt is done.
 */
public final class CodePairingSlot {

    private volatile CodePairingClient.Offer offer;
    private volatile PairingWindow window;
    private volatile CodePairingClient.Emitter emitter;
    private volatile CodePairingClient client;

    /** What the client offers, its pairing window (the gesture gate) and where the code of a dynamic attempt is shown. Before the start. */
    public void configure(CodePairingClient.Offer offer, PairingWindow window, CodePairingClient.Emitter emitter) {
        this.offer = offer;
        this.window = window;
        this.emitter = emitter;
    }

    /** What the client offers, or null (then the hello lists the Pairing PSK only). */
    public CodePairingClient.Offer offer() {
        return offer;
    }

    /** True if the server may pick this method here: the client offers it and the connection is unpaired (code pairing needs the Sentinel). */
    public boolean offers(String method, SendspinPsk.Category matched) {
        return offer != null && matched == SendspinPsk.Category.SENTINEL
                && ("dynamic_pairing_code".equals(method) || "static_pairing_code".equals(method));
    }

    /** The server activated a code method: the attempt starts on this connection. */
    public void start(SecureChannel channel, SecureRandom random, int activationIndex, String method, String format) throws IOException {
        NoiseSuite suite = channel.transport().suite();
        CodePairingClient attempt = new CodePairingClient(suite, channel.transport().handshakeHash(), random, channel::sendJson, offer, window,
                emitter);
        client = attempt;
        attempt.onActivate(activationIndex, method, format);
    }

    /** True if an attempt belongs to this connection (it has been started and not finished or given up). */
    public boolean has() {
        return client != null;
    }

    /** True while an attempt is under way. */
    public boolean isRunning() {
        CodePairingClient c = client;
        return c != null && c.state() != CodePairingClient.State.DONE && c.state() != CodePairingClient.State.FAILED
                && c.state() != CodePairingClient.State.IDLE;
    }

    /** The operator's gesture opened the window: an attempt that waited for it starts now. */
    public void onWindowOpened() {
        CodePairingClient c = client;
        if (c != null) {
            try {
                c.windowOpened();
            } catch (IOException e) {
                // the connection is gone; its loop ends it
            }
        }
    }

    /** A message of the attempt ({@code server/pair-init}, {@code -auth}, {@code -confirm}). */
    public void onMessage(String type, JSONObject payload) throws IOException, ProtocolException {
        CodePairingClient c = client;
        if (c == null) {
            throw new ProtocolException(type + " without a code-based pairing");
        }
        c.onMessage(type, payload);
    }

    /**
     * {@code server/pair-finalize} of an attempt: the Pairing PSK it made (the caller keeps it and wipes it), or null if the attempt did
     * not end well. The attempt is over either way.
     */
    public byte[] finish(JSONObject payload) throws IOException, ProtocolException {
        CodePairingClient c = client;
        if (c == null) {
            return null;
        }
        c.onMessage("server/pair-finalize", payload);
        byte[] psk = c.takePsk();
        client = null;
        return psk;
    }

    /** {@code pair/abort}: the attempt is given up. */
    public void abort(JSONObject payload) throws IOException, ProtocolException {
        CodePairingClient c = client;
        if (c != null) {
            c.onMessage("pair/abort", payload);
            client = null;
        }
    }
}
