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
package de.yaacc.fcast;

import de.yaacc.fcast.protocol.FCastText;

/**
 * The text of the card of an FCast receiver (task 5.2): the type "FCast", the address, the state (not reachable, idle, playing,
 * paused, with the title of what is played) and the volume. Pure; the app fills {@link Words} from its string resources.
 *
 * <p>An idle card has no connection (D11): with no player and no open session nothing is known about the receiver's state, and the
 * card shows only the type and the address. The state comes from the player of the receiver if there is one, else from the session
 * that a screen has open (the sheet, a volume change), else it is unknown. Everything that came from the receiver (the title) is
 * cleaned and cut again here, and is shown with a plain {@code setText}.
 */
public final class FCastRowText {

    /** The words of the card. */
    public interface Words {
        String type();

        String notReachable();

        String idle();

        String playing();

        String paused();

        /** "Playing: Title" style joiner; the app's text for the state with the title, or just the state if the title is empty. */
        String stateWithTitle(String state, String title);
    }

    /** Where the state comes from. */
    public enum Source {
        /** No player and no open session: nothing is known. */
        NONE,
        PLAYER,
        SESSION
    }

    /** What the receiver is doing, as far as it is known. */
    public enum State {
        UNKNOWN, NOT_REACHABLE, IDLE, PLAYING, PAUSED
    }

    /** The facts of one receiver at one moment. */
    public static final class Facts {
        String endpoint = "";
        Source source = Source.NONE;
        boolean linkFailed;
        boolean playing;
        boolean paused;
        boolean sessionReportsPlayback;
        String title = "";
        int volume = -1;
        boolean muted;

        public Facts endpoint(String text) {
            this.endpoint = text == null ? "" : text;
            return this;
        }

        /** The receiver has a player: its state is the card's. */
        public Facts player(boolean playing, boolean paused, String title) {
            this.source = Source.PLAYER;
            this.playing = playing;
            this.paused = paused;
            this.title = title == null ? "" : title;
            return this;
        }

        /**
         * A session of a screen is open.
         *
         * @param linkFailed         the connection failed (not reachable)
         * @param reportsPlayback    the receiver has told its playback state (playing, paused or idle) in this connection
         */
        public Facts session(boolean linkFailed, boolean reportsPlayback, boolean playing, boolean paused, String title) {
            this.source = Source.SESSION;
            this.linkFailed = linkFailed;
            this.sessionReportsPlayback = reportsPlayback;
            this.playing = playing;
            this.paused = paused;
            this.title = title == null ? "" : title;
            return this;
        }

        /** The volume in percent, -1 if unknown. */
        public Facts volume(int percent, boolean muted) {
            this.volume = percent;
            this.muted = muted;
            return this;
        }
    }

    /** What the card shows. */
    public static final class Row {
        private final String text;
        private final State state;
        private final int volume;
        private final boolean muted;

        private Row(String text, State state, int volume, boolean muted) {
            this.text = text;
            this.state = state;
            this.volume = volume;
            this.muted = muted;
        }

        /** Two lines: type and address, then the state (empty if the state is unknown). */
        public String getText() {
            return text;
        }

        public State getState() {
            return state;
        }

        /** The volume in percent, -1 if unknown. */
        public int getVolume() {
            return volume;
        }

        public boolean isMuted() {
            return muted;
        }
    }

    private static final int MAX_TITLE = 80;

    private FCastRowText() {
    }

    public static Row of(Facts facts, Words words) {
        State state = stateOf(facts);
        String first = words.type() + (facts.endpoint.isEmpty() ? "" : " \u00b7 " + FCastText.clean(facts.endpoint, 70));
        String second = "";
        switch (state) {
            case NOT_REACHABLE:
                second = words.notReachable();
                break;
            case IDLE:
                second = words.idle();
                break;
            case PLAYING:
                second = words.stateWithTitle(words.playing(), cleanTitle(facts.title));
                break;
            case PAUSED:
                second = words.stateWithTitle(words.paused(), cleanTitle(facts.title));
                break;
            default:
                break;
        }
        int volume = facts.volume < 0 ? -1 : Math.min(100, facts.volume);
        return new Row(second.isEmpty() ? first : first + "\n" + second, state, volume, facts.muted);
    }

    static State stateOf(Facts facts) {
        switch (facts.source) {
            case PLAYER:
                return facts.playing ? State.PLAYING : facts.paused ? State.PAUSED : State.IDLE;
            case SESSION:
                if (facts.linkFailed) {
                    return State.NOT_REACHABLE;
                }
                if (!facts.sessionReportsPlayback) {
                    return State.UNKNOWN;
                }
                return facts.playing ? State.PLAYING : facts.paused ? State.PAUSED : State.IDLE;
            case NONE:
            default:
                return State.UNKNOWN;
        }
    }

    private static String cleanTitle(String title) {
        String clean = FCastText.clean(title, MAX_TITLE);
        return clean == null ? "" : clean;
    }
}
