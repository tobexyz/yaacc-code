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
        NOT_CONNECTED, CONNECTING, NEEDS_APPROVAL, PAIRING_REQUIRED, RE_PAIR_NEEDED, UNUSABLE, READY, PLAYING, PAUSED,
        IN_USE_BY_ANOTHER_SERVER, UNREACHABLE, DISCONNECTED
    }

    /** The words (the app fills them from its resources). */
    public interface Words {
        String type();

        String state(State state);

        String stateWithTitle(String state, String title);
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
            case UNUSABLE:
                return State.UNUSABLE;
            case READY:
                return playing ? State.PLAYING : paused ? State.PAUSED : State.READY;
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
