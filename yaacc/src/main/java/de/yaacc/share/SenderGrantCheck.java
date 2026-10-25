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
package de.yaacc.share;

import java.util.function.IntSupplier;

/**
 * The launcher's last gate before it forwards a shared file: on Android 15 and newer the real
 * sender can be asked whether it holds read access to the URI
 * ({@code Activity.getInitialCaller().checkContentUriPermission}). Plain Java so the decision
 * (and its fail-closed rule) is tested on the JVM; the glue passes the platform call.
 * <p>
 * Below Android 15 there is no such API and nothing is checked here (the residual risk is
 * documented in design.md). From 35 on, any failure of the check, including a thrown
 * {@code IllegalArgumentException} or {@code SecurityException}, means "do not forward".
 */
public final class SenderGrantCheck {

    /** {@code Build.VERSION_CODES.VANILLA_ICE_CREAM}. */
    public static final int FIRST_SDK_WITH_CALLER_CHECK = 35;

    /** {@code PackageManager.PERMISSION_GRANTED}. */
    static final int PERMISSION_GRANTED = 0;

    private SenderGrantCheck() {
    }

    /**
     * @param sdkInt         {@code Build.VERSION.SDK_INT}
     * @param permissionCheck the platform check; only called from SDK 35 on
     * @return true if the share may be forwarded
     */
    public static boolean mayForward(int sdkInt, IntSupplier permissionCheck) {
        if (sdkInt < FIRST_SDK_WITH_CALLER_CHECK) {
            return true;
        }
        if (permissionCheck == null) {
            return false;
        }
        try {
            return permissionCheck.getAsInt() == PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
