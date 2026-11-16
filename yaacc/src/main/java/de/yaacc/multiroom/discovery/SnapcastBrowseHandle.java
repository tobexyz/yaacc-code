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
package de.yaacc.multiroom.discovery;

/**
 * One call of {@code NsdManager.discoverServices} and what a stop has to do. A stop that is requested before the
 * platform confirmed the start (the receivers page shown and hidden within a moment) is answered by some releases with
 * "stop failed" and leaves the browse running; so it is held back until {@code onDiscoveryStarted} arrives. Pure and
 * thread safe; every call of {@code discoverServices} has its own handle, so an old stop never touches a newer browse.
 */
public final class SnapcastBrowseHandle {

    private boolean started;
    private boolean failed;
    private boolean stopRequested;
    private boolean stopped;

    /** The platform confirmed the start. @return true if a stop was requested earlier and has to be made now */
    public synchronized boolean onStarted() {
        if (failed || stopped) {
            return false;
        }
        started = true;
        if (stopRequested) {
            stopped = true;
            return true;
        }
        return false;
    }

    /** The platform could not start the browse: there is nothing to stop. */
    public synchronized void onStartFailed() {
        failed = true;
    }

    /** @return true if the platform's stop has to be made now; false if it is held back until the start or not needed */
    public synchronized boolean requestStop() {
        if (failed || stopped || stopRequested) {
            return false;
        }
        if (started) {
            stopped = true;
            return true;
        }
        stopRequested = true;
        return false;
    }
}
