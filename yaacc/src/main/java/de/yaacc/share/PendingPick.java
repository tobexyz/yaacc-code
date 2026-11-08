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

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one receiver pick that can be open at a time in a browser activity (the "Play on..." sheet),
 * and what the sheet's answer does with it. Plain Java, no {@code android.*}, so the rules are
 * unit tested on the JVM; the activity keeps one instance in a field (the pending action is lost
 * with the activity, accepted).
 * <ul>
 * <li>{@link #open} stores the callbacks of the action and returns an id the sheet reports back.
 * While a pick is open, another {@link #open} is dropped (returns null) and never replaces it, so
 * an answer can only ever start the action the user saw.</li>
 * <li>{@link #resolve} with a confirm and at least one receiver closes the pick and runs
 * {@link Callbacks#onConfirmed} once; a confirm without a receiver keeps the pick open and runs
 * nothing; a cancel closes the pick and runs {@link Callbacks#onCancelled} once and never hands
 * out the ticked ids. An id that is not the open pick's (stale, forged, repeated, after an
 * {@link #abandon}) changes nothing, so nothing is delivered twice. The decision itself is
 * {@link ReceiverPickResult#decide}.</li>
 * <li>{@link #abandon} (the activity goes away, the share timed out) cancels an open pick.</li>
 * </ul>
 * The state is cleared before a callback runs and callbacks run outside the lock, so an action may
 * open the next pick and a throwing action cannot leave a pick open. Thread-safe.
 */
public final class PendingPick {

    /** What the owner of the pick wants done with the answer. Called on the caller's thread. */
    public interface Callbacks {
        /** The user confirmed with these receivers (never empty; unmodifiable). */
        void onConfirmed(Set<String> receiverIds);

        /** The user dismissed the picker, or it was abandoned: release what the action had reserved. */
        void onCancelled();
    }

    /** What {@link #resolve} / {@link #abandon} did. */
    public enum Outcome {
        /** Not the open pick (or none open): nothing changed. */
        IGNORED,
        /** The pick was closed and its action started. */
        STARTED,
        /** Confirmed without a receiver: the pick stays open, nothing ran. */
        KEPT_OPEN,
        /** The pick was closed and its cancel callback ran. */
        CANCELLED
    }

    private final Supplier<String> idSupplier;

    // all guarded by "this"
    private String openId;
    private Callbacks callbacks;

    /** @param idSupplier makes the ids of picks; a null or empty id refuses the pick */
    public PendingPick(Supplier<String> idSupplier) {
        if (idSupplier == null) {
            throw new IllegalArgumentException("an id supplier is required");
        }
        this.idSupplier = idSupplier;
    }

    /** A holder whose ids are random UUIDs (a restored sheet of an earlier process cannot match). */
    public static PendingPick createDefault() {
        return new PendingPick(() -> UUID.randomUUID().toString());
    }

    /**
     * Opens a pick.
     *
     * @return the id the sheet must report back, or null (and nothing changes) if a pick is already
     * open, the callbacks are null or no id could be made
     */
    public synchronized String open(Callbacks newCallbacks) {
        if (newCallbacks == null || openId != null) {
            return null;
        }
        String id = idSupplier.get();
        if (id == null || id.isEmpty()) {
            return null;
        }
        openId = id;
        callbacks = newCallbacks;
        return id;
    }

    /** @return true if {@code pickId} is the id of the open pick */
    public synchronized boolean isOpen(String pickId) {
        return openId != null && openId.equals(pickId);
    }

    /**
     * The sheet's answer.
     *
     * @param pickId    the id {@link #open} returned
     * @param confirmed true for the confirm button, false for a dismissal
     * @param chosenIds the receivers ticked, may be null
     */
    public Outcome resolve(String pickId, boolean confirmed, Set<String> chosenIds) {
        ReceiverPickResult.Decision decision = ReceiverPickResult.decide(confirmed, chosenIds);
        Callbacks toRun;
        synchronized (this) {
            if (!isOpen(pickId)) {
                return Outcome.IGNORED;
            }
            if (decision.keepPickerOpen()) {
                return Outcome.KEPT_OPEN;
            }
            toRun = close();
        }
        if (decision.startPlayback()) {
            // already an unmodifiable copy (ReceiverPickResult)
            toRun.onConfirmed(decision.receiversToUse());
            return Outcome.STARTED;
        }
        toRun.onCancelled();
        return Outcome.CANCELLED;
    }

    /** Cancels the open pick, if any (the activity is going away, the share timed out). */
    public Outcome abandon() {
        Callbacks toRun;
        synchronized (this) {
            if (openId == null) {
                return Outcome.IGNORED;
            }
            toRun = close();
        }
        toRun.onCancelled();
        return Outcome.CANCELLED;
    }

    private Callbacks close() {
        Callbacks closed = callbacks;
        openId = null;
        callbacks = null;
        return closed;
    }
}
