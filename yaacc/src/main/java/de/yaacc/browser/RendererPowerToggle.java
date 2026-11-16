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

/**
 * The tap on a power button (task 12.4), the same for every kind of renderer: the state is <b>read fresh</b> first, the change that
 * follows is decided from that read (a card that showed "on" while the device went to standby meanwhile switches it on, not off),
 * the change is sent once, the device is given a moment and read again. Nothing is changed on a guess: a device that can not be read,
 * or does not say whether it is on, is left alone and the user is told. Pure Java; the device is a {@link Source}, the wait a
 * {@link Pause}. Blocks: call it on a worker thread.
 *
 * <p>The texts of a device (names, addresses, answers) are never kept: a failure is a typed {@link Error}.
 */
final class RendererPowerToggle {

    private RendererPowerToggle() {
    }

    /** A device could not be read or did not accept a change. Carries no text of the device. */
    static final class PowerException extends Exception {
        private static final long serialVersionUID = 1L;

        PowerException() {
            super(null, null, false, false);
        }

        /** The cause is dropped: it holds the device's address and words. */
        PowerException(Throwable cause) {
            this();
        }

        @Override
        public String toString() {
            return "PowerException";
        }
    }

    /** Why the power was not changed. */
    enum Error {
        NONE,
        /** The state could not be read: the device does not answer. */
        UNREACHABLE,
        /** The device answered but does not say whether it is on. */
        UNKNOWN_STATE,
        /** The device did not accept the change. */
        REFUSED
    }

    /** What a renderer's device offers to the toggle. Called on a worker thread. */
    interface Source {
        /** @return the state the device says now, {@link RendererPower.State#UNKNOWN} when it does not say */
        RendererPower.State read() throws PowerException;

        /** @param on true to switch on, false to go to standby; returns when the device took the request */
        void set(boolean on) throws PowerException;
    }

    /** A device that can not be reached (the app has no way to call it): reading it fails, so the toggle ends as unreachable. */
    static Source unreachable() {
        return new Source() {
            @Override
            public RendererPower.State read() throws PowerException {
                throw new PowerException();
            }

            @Override
            public void set(boolean on) throws PowerException {
                throw new PowerException();
            }
        };
    }

    /** Waits; replaced in tests. */
    interface Pause {
        void pause(long millis) throws InterruptedException;
    }

    /** What a tap came to: the state to show (or {@code UNKNOWN} when it is not known) and the error, if any. Immutable. */
    static final class Outcome {
        private final RendererPower.State state;
        private final Error error;

        Outcome(RendererPower.State state, Error error) {
            this.state = state == null ? RendererPower.State.UNKNOWN : state;
            this.error = error == null ? Error.NONE : error;
        }

        RendererPower.State state() {
            return state;
        }

        Error error() {
            return error;
        }

        @Override
        public String toString() {
            return "Outcome{" + state + ", " + error + "}";
        }
    }

    /**
     * @param settleMillis how long the device gets to change before it is read again (a device in standby needs a moment to wake)
     */
    static Outcome toggle(Source source, Pause pause, long settleMillis) {
        RendererPower.State fresh;
        try {
            fresh = source.read();
        } catch (PowerException | RuntimeException unreachable) {
            return new Outcome(RendererPower.State.UNKNOWN, Error.UNREACHABLE);
        }
        if (fresh == null || fresh == RendererPower.State.UNKNOWN) {
            return new Outcome(RendererPower.State.UNKNOWN, Error.UNKNOWN_STATE);
        }
        try {
            source.set(fresh == RendererPower.State.STANDBY);
        } catch (PowerException | RuntimeException refused) {
            return new Outcome(fresh, Error.REFUSED);
        }
        try {
            pause.pause(settleMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome(RendererPower.State.UNKNOWN, Error.NONE);
        }
        try {
            return new Outcome(source.read(), Error.NONE);
        } catch (PowerException | RuntimeException silent) {
            return new Outcome(RendererPower.State.UNKNOWN, Error.NONE); // the change went through; the next live update tells
        }
    }
}
