/*
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
package de.yaacc.multiroom.discovery;

/**
 * The names of the failure codes of {@code android.net.nsd.NsdManager} (values from the platform's constants), for the
 * discovery report. Written as numbers on purpose: the constants {@code FAILURE_OPERATION_NOT_RUNNING} and
 * {@code FAILURE_BAD_PARAMETERS} exist from API 34 only, the codes themselves are reported by older releases too. Pure.
 */
public final class SnapcastNsdErrors {

    /** Not a code of the platform: {@code discoverServices} threw instead of reporting one (no service, a permission). */
    public static final int PLATFORM_THREW = 100;

    private SnapcastNsdErrors() {
    }

    /** @return the name and the number, e.g. {@code FAILURE_ALREADY_ACTIVE (3)}; {@code unknown code N} for any other */
    public static String describe(int code) {
        switch (code) {
            case 0:
                return "FAILURE_INTERNAL_ERROR (0)";
            case 3:
                return "FAILURE_ALREADY_ACTIVE (3)";
            case 4:
                return "FAILURE_MAX_LIMIT (4)";
            case 5:
                return "FAILURE_OPERATION_NOT_RUNNING (5)";
            case 6:
                return "FAILURE_BAD_PARAMETERS (6)";
            case PLATFORM_THREW:
                return "the platform threw an exception (no code)";
            default:
                return "unknown code " + code;
        }
    }
}
