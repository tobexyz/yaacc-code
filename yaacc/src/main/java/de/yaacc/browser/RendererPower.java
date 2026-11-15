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
package de.yaacc.browser;

import java.util.Objects;

/**
 * The power of a renderer card as a value (task 12.4): whether the device can be switched on and to standby at all (then the card
 * has a power button), what it is now (on, standby, or not known), and what a tap would do first. The kinds of renderers make these
 * values from their own sources (a MusicCast zone, an OpenHome Product service, later the phone's own Snapcast client) and give the
 * card a way to change the state ({@link RendererPowerToggle.Source}); the button and the toggle know nothing of the kind. Immutable.
 */
final class RendererPower {

    /** What the device says it is. */
    enum State {
        ON, STANDBY, UNKNOWN
    }

    /** What a tap on the button does first. The change itself is decided from a fresh read, see {@link RendererPowerToggle}. */
    enum Next {
        /** No button: nothing to do. */
        NOTHING,
        TURN_OFF,
        TURN_ON,
        /** The state is not known: it is read first and nothing is guessed. */
        READ_FIRST
    }

    private static final RendererPower UNSUPPORTED = new RendererPower(false, State.UNKNOWN);

    private final boolean supported;
    private final State state;

    private RendererPower(boolean supported, State state) {
        this.supported = supported;
        this.state = state;
    }

    /** The device has no power to switch: the card has no button. */
    static RendererPower unsupported() {
        return UNSUPPORTED;
    }

    /** A device that supports power, in this state (null counts as unknown). */
    static RendererPower of(State state) {
        return new RendererPower(true, state == null ? State.UNKNOWN : state);
    }

    boolean isSupported() {
        return supported;
    }

    State state() {
        return state;
    }

    Next next() {
        if (!supported) {
            return Next.NOTHING;
        }
        switch (state) {
            case ON:
                return Next.TURN_OFF;
            case STANDBY:
                return Next.TURN_ON;
            case UNKNOWN:
            default:
                return Next.READ_FIRST;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RendererPower && supported == ((RendererPower) other).supported
                && state == ((RendererPower) other).state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(supported, state);
    }

    @Override
    public String toString() {
        return supported ? "RendererPower{" + state + "}" : "RendererPower{unsupported}";
    }
}
