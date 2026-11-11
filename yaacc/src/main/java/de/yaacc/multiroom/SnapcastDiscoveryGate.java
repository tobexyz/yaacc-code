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
package de.yaacc.multiroom;

import java.util.function.BooleanSupplier;

/**
 * Decides when mDNS discovery runs: only while at least one receivers page is visible <b>and</b> the setting
 * {@link SnapcastSettings#KEY_DISCOVERY} is on. The setting is read each time (not once at start), so a change shows
 * live: switching it on while a page is open starts the scan, switching it off stops it at once and tells the directory to
 * drop the servers that only the scan knew. Pure; the scan and the directory are behind small interfaces.
 *
 * <p>Thread safe. The callbacks are called with the gate's lock held (they are quick: the scan hands its work to the
 * platform), so a start and a stop can never be reordered. Nothing calls back into the gate from them.
 */
public final class SnapcastDiscoveryGate {

    /** The scan: {@code SnapcastNsd} in the app. */
    public interface Discovery {
        void start();

        void stop();
    }

    private final BooleanSupplier enabled;
    private final Discovery discovery;
    private final Runnable onDisabled;
    private int pages;
    private boolean running;
    private boolean cleaned;

    /**
     * @param enabled    reads the setting; a failure counts as off
     * @param onDisabled called once each time the setting is found off after it was on (and at the first look): the
     *                   directory forgets what the scan found
     */
    public SnapcastDiscoveryGate(BooleanSupplier enabled, Discovery discovery, Runnable onDisabled) {
        this.enabled = enabled;
        this.discovery = discovery;
        this.onDisabled = onDisabled;
    }

    /** A receivers page became visible. */
    public synchronized void pageStarted() {
        pages++;
        evaluate();
    }

    /** A receivers page was hidden. */
    public synchronized void pageStopped() {
        if (pages == 0) {
            return;
        }
        pages--;
        evaluate();
    }

    /** The setting changed (or may have): look at it again. */
    public synchronized void settingChanged() {
        evaluate();
    }

    /** True while the scan runs. */
    public synchronized boolean isRunning() {
        return running;
    }

    private boolean isEnabled() {
        try {
            return enabled.getAsBoolean();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private void evaluate() {
        boolean on = isEnabled();
        if (on) {
            cleaned = false;
        } else if (!cleaned) {
            cleaned = true;
            if (running) {
                running = false;
                runQuietly(discovery::stop);
            }
            runQuietly(onDisabled);
            return;
        }
        boolean wanted = on && pages > 0;
        if (wanted && !running) {
            running = true;
            try {
                discovery.start();
            } catch (RuntimeException failed) {
                running = false; // the page count stays right; the next look tries again
            }
        } else if (!wanted && running) {
            running = false;
            runQuietly(discovery::stop);
        }
    }

    private static void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ignored) {
            // a failing scan or directory must not break the page lifetime
        }
    }
}
