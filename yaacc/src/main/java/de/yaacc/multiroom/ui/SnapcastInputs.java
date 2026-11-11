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
package de.yaacc.multiroom.ui;

import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastLimits;

/**
 * Reads what the user types in the dialogs of a Snapcast client (its name and its latency). Pure. A name is cleaned like
 * a name from the server (nobody should be able to paste a control character into a server's configuration), and a
 * latency is a whole number of milliseconds in the range the server accepts.
 */
public final class SnapcastInputs {

    /** The longest name accepted by the rename dialog. */
    public static final int MAX_NAME = 64;

    /** Why a latency text is not accepted. */
    public enum Problem {
        NONE,
        EMPTY,
        NOT_A_NUMBER,
        OUT_OF_RANGE
    }

    /** The outcome of {@link #parseLatency}. Immutable. */
    public static final class Latency {
        private final int value;
        private final Problem problem;

        Latency(int value, Problem problem) {
            this.value = value;
            this.problem = problem;
        }

        public boolean isValid() {
            return problem == Problem.NONE;
        }

        /** The latency in milliseconds; only meaningful when {@link #isValid()}. */
        public int getValue() {
            return value;
        }

        public Problem getProblem() {
            return problem;
        }
    }

    private SnapcastInputs() {
    }

    /** @return the latency, 0 to {@link SnapcastLimits#MAX_LATENCY_MS}, written with the digits 0 to 9 only */
    public static Latency parseLatency(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return new Latency(0, Problem.EMPTY);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return new Latency(0, Problem.NOT_A_NUMBER);
            }
        }
        if (trimmed.length() > 5) {
            return new Latency(0, Problem.OUT_OF_RANGE); // longer than "10000" (leading zeros included)
        }
        int value = Integer.parseInt(trimmed);
        if (value > SnapcastLimits.MAX_LATENCY_MS) {
            return new Latency(0, Problem.OUT_OF_RANGE);
        }
        return new Latency(value, Problem.NONE);
    }

    /** @return the name as it is sent: trimmed, cleaned, at most {@link #MAX_NAME} characters; empty for none */
    public static String normalizeName(String text) {
        return text == null ? "" : SnapcastDisplayText.of(text.trim(), MAX_NAME).trim();
    }
}
