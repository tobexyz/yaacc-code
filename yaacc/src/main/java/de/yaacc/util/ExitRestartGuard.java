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
package de.yaacc.util;

import java.util.function.LongSupplier;

/**
 * Decides whether a start that comes right after an explicit exit (exit button, shutdown timer, idle timer) is a start the user
 * wanted or the system bringing the app back (task 12.2).
 * <p>
 * The exit marks the time it was asked for before anything is stopped, and the mark is persisted (the process ends right after).
 * What the system does afterwards can not be controlled from the process (a sticky restart of a service, a new process for some
 * other reason, and every new process starts the UPnP server service), so the starting side asks: an {@link StartReason#SYSTEM_RESTART
 * sticky restart}, a {@link StartReason#PROCESS_START process start} or a {@link StartReason#RESTORED_SCREEN restored screen} within {@link #WINDOW_MILLIS} of the mark stops itself. A
 * start by the user ({@link StartReason#USER_LAUNCH}) is never blocked and clears the mark, so a later start by anything is a
 * normal start; the window is the second safety, the mark never blocks for long even if nobody clears it. A boot is a new
 * session and always starts (the autostart is the user's choice).
 * <p>
 * Fails open: a store that can not be read or written never blocks a start and never makes the exit fail.
 */
public final class ExitRestartGuard {

    /** The restart of a service after a process end comes within seconds (the system's first delay is one second, then it backs off). */
    public static final long WINDOW_MILLIS = 120_000L;

    /** A clock that stepped back by this much or less still counts as "right after"; further back the mark can not be judged. */
    public static final long CLOCK_SKEW_MILLIS = 5_000L;

    /** Why something starts. */
    public enum StartReason {
        /** The user opened the app (launcher, share, notification, recents) or an activity of it was created fresh. */
        USER_LAUNCH,
        /** The system restarts a service after its process ended: {@code onStartCommand} with no intent. */
        SYSTEM_RESTART,
        /** A process was created and no user start is known yet (the application object is created). */
        PROCESS_START,
        /** The system recreates a screen from its saved state (the screen was left over from before the exit). */
        RESTORED_SCREEN,
        /** The device was booted. */
        BOOT
    }

    /** What the starting side does. */
    public enum Decision {
        START, STOP_SELF
    }

    /** Where the mark is kept (survives the process). */
    public interface Store {
        /** The time of the mark in epoch milliseconds, 0 if none. */
        long readExitRequestedAt();

        /** Stores the mark; 0 clears it. Must be durable when it returns (the process ends right after). */
        void writeExitRequestedAt(long millis);
    }

    private final Store store;
    private final LongSupplier clock;

    public ExitRestartGuard(Store store, LongSupplier clock) {
        if (store == null || clock == null) {
            throw new IllegalArgumentException("missing argument");
        }
        this.store = store;
        this.clock = clock;
    }

    /** The decision itself, without a store. */
    public static Decision decide(long exitRequestedAtMillis, long nowMillis, StartReason reason) {
        if (exitRequestedAtMillis <= 0L || reason == null) {
            return Decision.START;
        }
        if (reason == StartReason.USER_LAUNCH || reason == StartReason.BOOT) {
            return Decision.START;
        }
        long sinceExit = nowMillis - exitRequestedAtMillis;
        if (sinceExit < -CLOCK_SKEW_MILLIS) {
            return Decision.START; // the clock went back: the mark can not be judged, never block on it
        }
        return sinceExit < WINDOW_MILLIS ? Decision.STOP_SELF : Decision.START;
    }

    /** Called first thing in the exit, before anything is stopped. Never throws. */
    public void markExitRequested() {
        try {
            store.writeExitRequestedAt(clock.getAsLong());
        } catch (RuntimeException e) {
            // the exit goes on; without the mark the old behaviour is all that is left
        }
    }

    /** Called when the user starts the app: whatever starts later is a normal start. Never throws. */
    public void clearOnUserStart() {
        try {
            if (store.readExitRequestedAt() != 0L) { // every screen calls this: no write when there is nothing to clear
                store.writeExitRequestedAt(0L);
            }
        } catch (RuntimeException e) {
            // the window ends the block anyway
        }
    }

    /** What a start for this reason does now. Never throws; an unreadable store allows the start. */
    public Decision decide(StartReason reason) {
        try {
            return decide(store.readExitRequestedAt(), clock.getAsLong(), reason);
        } catch (RuntimeException e) {
            return Decision.START;
        }
    }
}
