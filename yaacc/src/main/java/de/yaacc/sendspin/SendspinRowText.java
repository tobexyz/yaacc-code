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
package de.yaacc.sendspin;

import de.yaacc.sendspin.hub.HubConnection;

/**
 * The text of a Sendspin player's card: the type, the player's name (cleaned: it came from the network), the address and the
 * state in words. Pure; the words come from the app's string resources.
 */
public final class SendspinRowText {

    /** What the card says about the connection. */
    public enum State {
        NOT_CONNECTED, CONNECTING, NEEDS_APPROVAL, PAIRING_REQUIRED, RE_PAIR_NEEDED, PAIRING, PAIRING_REJECTED, PAIRING_FAILED,
        UNUSABLE, READY, READY_PAIRED, PLAYING, PAUSED,
        /** The card of a server: connected, the server lets this client control and show. */
        SERVER_CONNECTED,
        /** The card of a server: connected, but the server has not activated a role (its operator has to allow this client). */
        SERVER_WAITING,
        /** The card of a server: it wants pairing, which this client can not do yet. */
        SERVER_NEEDS_PAIRING,
        /** The card of a server: it declared something this client is not allowed to accept. */
        SERVER_REFUSED,
        IN_USE_BY_ANOTHER_SERVER, UNREACHABLE, DISCONNECTED
    }

    /** The words (the app fills them from its resources). */
    public interface Words {
        String type();

        String state(State state);

        String stateWithTitle(String state, String title);

        /** The type word of the card of a Sendspin server. */
        String serverType();
    }

    /** The longest name or title shown. */
    public static final int MAX_TEXT = 64;

    private SendspinRowText() {
    }

    /**
     * @param phase  the phase of the connection, null if there is none
     * @param reason why it ended (only for an ended connection)
     */
    public static State stateOf(HubConnection.Phase phase, HubConnection.EndReason reason, boolean playing, boolean paused) {
        return stateOf(phase, reason, HubConnection.PairingFailure.NONE, false, playing, paused);
    }

    /** True if a tap on the card should offer the entry of a pairing token. */
    public static boolean offersPairing(State state) {
        return state == State.PAIRING_REQUIRED || state == State.RE_PAIR_NEEDED || state == State.PAIRING_REJECTED
                || state == State.PAIRING_FAILED;
    }

    /**
     * @param failure why the last pairing did not complete
     * @param paired  true if the connection is under the long-term PSK of a paired player
     */
    public static State stateOf(HubConnection.Phase phase, HubConnection.EndReason reason, HubConnection.PairingFailure failure,
                                boolean paired, boolean playing, boolean paused) {
        if (phase == null) {
            return State.NOT_CONNECTED;
        }
        switch (phase) {
            case CONNECTING:
            case HANDSHAKING:
            case WAITING_FOR_HELLO:
            case ACTIVATING:
                return State.CONNECTING;
            case NEEDS_APPROVAL:
                return State.NEEDS_APPROVAL;
            case PAIRING_REQUIRED:
                return State.PAIRING_REQUIRED;
            case RE_PAIR_NEEDED:
                return State.RE_PAIR_NEEDED;
            case PAIRING:
                return State.PAIRING;
            case PAIRING_FAILED:
                return failure == HubConnection.PairingFailure.TOKEN_REJECTED ? State.PAIRING_REJECTED : State.PAIRING_FAILED;
            case UNUSABLE:
                return State.UNUSABLE;
            case AVAILABLE:
            case READY:
                return playing ? State.PLAYING : paused ? State.PAUSED : paired ? State.READY_PAIRED : State.READY;
            case ENDED:
            default:
                if (reason == HubConnection.EndReason.ANOTHER_SERVER || reason == HubConnection.EndReason.CONCURRENT_ATTEMPT) {
                    return State.IN_USE_BY_ANOTHER_SERVER;
                }
                if (reason == HubConnection.EndReason.FAILED) {
                    return State.UNREACHABLE;
                }
                return State.DISCONNECTED;
        }
    }

    /**
     * The state of the card of a server from its session.
     *
     * @param groupPlaying whether the group of this client plays (from {@code group/update})
     */
    public static State stateOfServer(de.yaacc.sendspin.client.ControllerSession.Phase phase,
                                      de.yaacc.sendspin.client.ControllerSession.EndReason reason, boolean groupPlaying) {
        if (phase == null) {
            return State.NOT_CONNECTED;
        }
        switch (phase) {
            case CONNECTING:
            case HANDSHAKING:
            case WAITING_FOR_HELLO:
            case WAITING_FOR_ACTIVATION:
                return State.CONNECTING;
            case WAITING_FOR_SERVER:
                return State.SERVER_WAITING;
            case ACTIVE:
                return groupPlaying ? State.PLAYING : State.SERVER_CONNECTED;
            case ENDED:
            default:
                if (reason == de.yaacc.sendspin.client.ControllerSession.EndReason.PAIRING_REQUIRED) {
                    return State.SERVER_NEEDS_PAIRING;
                }
                if (reason == de.yaacc.sendspin.client.ControllerSession.EndReason.UNAUTHORIZED) {
                    return State.SERVER_REFUSED;
                }
                if (reason == de.yaacc.sendspin.client.ControllerSession.EndReason.FAILED) {
                    return State.UNREACHABLE;
                }
                return State.DISCONNECTED;
        }
    }

    /** The text of the card of a server: {@code Sendspin server, name, address, state} (what plays is added to the state). */
    public static String ofServer(String name, String address, State state, String nowPlaying, Words words) {
        StringBuilder sb = new StringBuilder(words.serverType());
        String cleaned = clean(name);
        if (!cleaned.isEmpty()) {
            sb.append(" · ").append(cleaned);
        }
        sb.append(" · ").append(address);
        String stateText = words.state(state);
        String cleanText = clean(nowPlaying);
        sb.append(" · ").append(cleanText.isEmpty() ? stateText : words.stateWithTitle(stateText, cleanText));
        return sb.toString();
    }

    /** {@code artist - title}, with what is missing left out; both are cleaned (they came from a server). */
    public static String nowPlaying(String title, String artist) {
        String t = clean(title);
        String a = clean(artist);
        if (t.isEmpty()) {
            return a;
        }
        return a.isEmpty() ? t : a + " - " + t;
    }

    /** The text of a card: {@code Sendspin, name, address, state} (the title of what plays is added to the state). */
    public static String of(String name, String address, State state, String title, Words words) {
        StringBuilder sb = new StringBuilder(words.type());
        String cleaned = clean(name);
        if (!cleaned.isEmpty()) {
            sb.append(" · ").append(cleaned);
        }
        sb.append(" · ").append(address);
        String stateText = words.state(state);
        String cleanTitle = clean(title);
        sb.append(" · ").append(cleanTitle.isEmpty() ? stateText : words.stateWithTitle(stateText, cleanTitle));
        return sb.toString();
    }

    /** A text of the network or of a file for the display: no control or separator characters, trimmed, at most {@link #MAX_TEXT} characters. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            boolean bad = Character.isISOControl(c) || c == ' ' || c == ' ' || (c >= '‪' && c <= '‮')
                    || (c >= '⁦' && c <= '⁩') || c == '‎' || c == '‏';
            sb.append(bad ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
