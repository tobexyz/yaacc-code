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
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * In-memory registry of the active shares served by the temporary media proxy. Plain Java with
 * an injectable clock and random source so it can be unit tested on the JVM.
 * <p>
 * Every entry gets a random key: 16 bytes from the random source, encoded as unpadded base64url
 * (22 characters). The key depends on nothing but the random bytes (not on the target), is never
 * handed out twice while the registry lives, and is the only way to reach the entry. Nothing is
 * persisted.
 * <p>
 * Lifetime of an entry: it expires {@code absoluteMaxMillis} after registration, or, once
 * {@link #release(String)} was called, {@code idleGraceMillis} after the first release, whichever
 * comes first ({@link #markActive(String)} cancels a pending release). An expired entry is never
 * returned; it is removed and its target closed when it is noticed (by {@link #lookup},
 * {@link #register}, {@link #size} or {@link #expire()}). A target that implements
 * {@link AutoCloseable} is closed exactly once, whichever way the entry goes (remove, expiry,
 * {@link #removeAll()}); an exception from {@code close()} is swallowed. At most
 * {@code maxActive} entries are held; {@link #register} returns null when none is free.
 * <p>
 * All methods are thread safe. No method throws for a null, empty or garbage key.
 */
public final class ShareRegistry {

    /** How many active entries the default registry holds. */
    public static final int DEFAULT_MAX_ACTIVE = 8;

    /** Number of random bytes behind a key (128 bit). */
    public static final int KEY_BYTES = 16;

    /** Length of a key in characters: {@link #KEY_BYTES} bytes as unpadded base64url. */
    public static final int KEY_TEXT_LENGTH = 22;

    /** What the target of an entry is. */
    public enum Kind {
        /** An http or https link the proxy relays; the target is the URL text. */
        HTTP_LINK,
        /** A local file held open; the target is an {@link AutoCloseable} source. */
        LOCAL_FILE
    }

    /** One share. Immutable; the registry never looks inside {@link #getTarget()} except to close it. */
    public static final class Entry {
        private final Kind kind;
        private final Object target;
        private final String mimeType;
        private final Set<String> allowedAddresses;
        private final long idleGraceMillis;
        private final long absoluteMaxMillis;

        /**
         * @param kind              not null
         * @param target            not null, opaque to the registry (closed if AutoCloseable)
         * @param mimeType          may be null (unknown)
         * @param allowedAddresses  receiver addresses as text, may be null (= none); copied,
         *                          the entry's set is unmodifiable and null elements are dropped
         * @param idleGraceMillis   how long the entry lives after release, &gt;= 0
         * @param absoluteMaxMillis how long the entry lives at most, &gt; 0
         * @throws IllegalArgumentException for a null kind or target, a negative grace or a
         *                                  non-positive absolute maximum
         */
        public Entry(Kind kind, Object target, String mimeType, Set<String> allowedAddresses,
                     long idleGraceMillis, long absoluteMaxMillis) {
            // STUB (red phase): no validation, no defensive copy
            this.kind = kind;
            this.target = target;
            this.mimeType = mimeType;
            this.allowedAddresses = allowedAddresses;
            this.idleGraceMillis = idleGraceMillis;
            this.absoluteMaxMillis = absoluteMaxMillis;
        }

        public Kind getKind() {
            return kind;
        }

        public Object getTarget() {
            return target;
        }

        public String getMimeType() {
            return mimeType;
        }

        public Set<String> getAllowedAddresses() {
            return allowedAddresses;
        }

        public long getIdleGraceMillis() {
            return idleGraceMillis;
        }

        public long getAbsoluteMaxMillis() {
            return absoluteMaxMillis;
        }
    }

    /**
     * @param nowMillis   monotonic clock in milliseconds, not null
     * @param randomBytes fills the given array with random bytes (e.g. {@code
     *                    new SecureRandom()::nextBytes}), not null; must be thread safe or is
     *                    only called under the registry's lock
     * @param maxActive   &gt; 0
     * @throws IllegalArgumentException for a null argument or a non-positive maximum
     */
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes, int maxActive) {
        // STUB (red phase): nothing
    }

    /** Same with {@link #DEFAULT_MAX_ACTIVE}. */
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes) {
        this(nowMillis, randomBytes, DEFAULT_MAX_ACTIVE);
    }

    /** A registry on the monotonic system clock and a {@code SecureRandom}. */
    public static ShareRegistry createDefault() {
        // STUB (red phase): wrong on purpose
        return null;
    }

    /**
     * Registers an entry. Expired entries are swept first, so they never block a free slot.
     *
     * @return the new key, or null if {@code maxActive} entries are active or no unused key could
     * be drawn (a broken random source repeating itself); in both cases nothing is stored and
     * the caller keeps ownership of the target (the registry does not close it)
     * @throws IllegalArgumentException if the entry is null
     */
    public String register(Entry entry) {
        // STUB (red phase): wrong on purpose
        return null;
    }

    /**
     * @return the live entry for the key, or null for an unknown, garbage, expired or
     * released-and-grace-over key (an expired entry is removed and closed on the way)
     */
    public Entry lookup(String key) {
        // STUB (red phase): wrong on purpose
        return null;
    }

    /**
     * Cancels a pending release (a player started again, a renderer is still fetching). Does not
     * extend the absolute maximum.
     *
     * @return true if the key names a live entry
     */
    public boolean markActive(String key) {
        // STUB (red phase): wrong on purpose
        return false;
    }

    /**
     * Starts the grace period (the player ended). The first release counts; repeated releases do
     * not restart the grace period. Unknown keys are ignored.
     */
    public void release(String key) {
        // STUB (red phase): nothing
    }

    /**
     * Removes the entry now and closes its target (once).
     *
     * @return true if an entry was removed
     */
    public boolean remove(String key) {
        // STUB (red phase): wrong on purpose
        return false;
    }

    /** Removes and closes every entry that has expired. @return how many were removed */
    public int expire() {
        // STUB (red phase): wrong on purpose
        return 0;
    }

    /** Removes and closes every entry (service stop). */
    public void removeAll() {
        // STUB (red phase): nothing
    }

    /** @return the number of live entries (expired ones are swept first) */
    public int size() {
        // STUB (red phase): wrong on purpose
        return -1;
    }

    /**
     * Format check for the key from a request path, used by the proxy handler BEFORE the lookup
     * (no registry state needed): exactly {@value #KEY_TEXT_LENGTH} characters, each one of
     * {@code A-Z a-z 0-9 - _} (the unpadded base64url form of {@link #KEY_BYTES} bytes). ASCII
     * only: no padding {@code =}, no {@code + / . %}, no whitespace, no unicode letters or digits.
     * Nothing is trimmed or decoded.
     *
     * @return false for null, empty, any other length and any other character
     */
    public static boolean isWellFormedKey(String key) {
        // STUB (red phase): wrong on purpose (fail closed)
        return false;
    }
}
