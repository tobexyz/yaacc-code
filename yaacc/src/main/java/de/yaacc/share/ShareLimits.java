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

/**
 * The numbers that bound a share, in one place: how long it lives, how often the registry is
 * swept, how much is sent per read and how many responses one share serves at the same time.
 */
public final class ShareLimits {

    /** How long a share stays available after its player ended (a renderer may still buffer or seek). */
    public static final long IDLE_GRACE_MILLIS = 10L * 60L * 1000L;

    /** How long a share lives at most, whatever happens. */
    public static final long ABSOLUTE_MAX_MILLIS = 6L * 60L * 60L * 1000L;

    /** How often the registry drops expired entries without any request. */
    public static final long SWEEP_INTERVAL_MILLIS = 60L * 1000L;

    /** Bytes per read when streaming a file (the response buffer is this big). */
    public static final int CHUNK_BYTES = 64 * 1024;

    /**
     * Concurrent responses per share. A renderer opens a few connections (probe, seek, buffer);
     * more than this is not playback.
     */
    public static final int MAX_CONCURRENT_RESPONSES_PER_SHARE = 4;

    /**
     * How long a response may make no progress before the watchdog takes its slot away: a reader
     * that stalls or trickles must not hold one of the few slots of a share. (The server-wide
     * socket timeout is 5 minutes and does not fire while a peer trickles.) The response itself
     * survives a pause: when it is called again it asks for a slot again and aborts only if the
     * share is full.
     */
    public static final long WRITE_IDLE_MILLIS = 60L * 1000L;

    /**
     * Progress is counted in steps of this many bytes within {@link #WRITE_IDLE_MILLIS}; less is a
     * trickle (about 270 bytes per second), not playback.
     */
    public static final int MIN_PROGRESS_BYTES = 16 * 1024;

    /**
     * How long a share that no player claimed may stay in the registry (and, for a file, hold its
     * descriptor): the player is created right after the registration, so two minutes is generous.
     */
    public static final long CLAIM_WINDOW_MILLIS = 2L * 60L * 1000L;

    /** How long a pending (unconfirmed) share waits for the user's receiver pick. */
    public static final long PENDING_SHARE_TIMEOUT_MILLIS = 2L * 60L * 1000L;

    /** Free space that must stay on the volume while a provider file is copied: 64 MiB. */
    public static final long COPY_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L;

    /** All copies together in {@code cache/incoming/}: 1 GiB. */
    public static final long INCOMING_DIR_MAX_BYTES = 1024L * 1024L * 1024L;

    /** The longest a provider call (type, name, opening the descriptor) may take before the share is dropped. */
    public static final long PROVIDER_CALL_TIMEOUT_MILLIS = 3_000L;

    /**
     * The longest opening a provider descriptor may take after the user confirmed (some providers
     * fetch the file first); longer than the type and name queries.
     */
    public static final long PROVIDER_OPEN_TIMEOUT_MILLIS = 20_000L;

    /** The longest the copy fallback may run: a provider that trickles must not hold the copy slot forever. */
    public static final long COPY_MAX_MILLIS = 5L * 60L * 1000L;

    /**
     * The longest one picked action may hold the single flight ({@link ActionFlight}) before it
     * counts as lost: longer than the copy fallback ({@link #COPY_MAX_MILLIS}), the slowest start.
     */
    public static final long ACTION_FLIGHT_MAX_MILLIS = COPY_MAX_MILLIS + 60_000L;

    /**
     * How long the token that the launcher puts into the intent it forwards stays valid: the
     * browser reads it in {@code onCreate}/{@code onNewIntent}, which follows the forward at once.
     */
    public static final long LAUNCH_NONCE_TTL_MILLIS = 30_000L;

    /** Tokens minted but not yet used; the oldest is dropped beyond this (one forward is one token). */
    public static final int LAUNCH_NONCE_MAX_OUTSTANDING = 8;

    /**
     * Threads of each provider pool (intake probe, confirmed open). A Binder call into another
     * process cannot be interrupted, so a hung provider pins its thread. The per-provider gate
     * ({@link #PROVIDER_MAX_TRACKED}) keeps one provider to one thread, for as long as its call is
     * outstanding, and lets at most that many threads into provider calls at once; the pool is
     * larger, so a thread is left for everything else (a call that has just returned and is still
     * finishing, a new provider).
     */
    public static final int PROVIDER_POOL_THREADS = 4;

    /**
     * Providers that may have a call outstanding at the same time, hung ones included. Below
     * {@link #PROVIDER_POOL_THREADS}: at most 3 of the 4 threads are ever inside provider calls.
     * One hung provider holds one place; three distinct hung providers use up all places until a
     * call returns.
     */
    public static final int PROVIDER_MAX_TRACKED = 3;

    /**
     * Copy workers that may stay stuck (abandoned after the deadline) at the same time; beyond
     * this a new copy is turned away as busy instead of starting one more thread.
     */
    public static final int COPY_MAX_STUCK_WORKERS = 3;

    private ShareLimits() {
    }
}
