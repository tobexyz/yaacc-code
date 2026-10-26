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

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * In-memory registry of the active shares served by the temporary media proxy. Plain Java with
 * an injectable clock and random source so it can be unit tested on the JVM.
 * <p>
 * Every entry gets a random key: 16 bytes from the random source, encoded as unpadded base64url
 * (22 characters). The key depends on nothing but the random bytes (not on the target), is never
 * handed out twice while the registry lives (retired keys are remembered for a bounded window of
 * recent keys, which is plenty for a 128-bit random source and keeps the memory bounded), and is the only way to reach the entry. Nothing is
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

    /** How many of the most recently retired keys are remembered and never handed out again. */
    static final int RETIRED_KEY_WINDOW = 256;

    /** How often a random draw that collides with a live or retired key is repeated. */
    private static final int MAX_KEY_ATTEMPTS = 8;

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
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            if (target == null) {
                throw new IllegalArgumentException("target must not be null");
            }
            if (idleGraceMillis < 0L) {
                throw new IllegalArgumentException("idleGraceMillis must not be negative");
            }
            if (absoluteMaxMillis <= 0L) {
                throw new IllegalArgumentException("absoluteMaxMillis must be positive");
            }
            Set<String> copy = new HashSet<>();
            if (allowedAddresses != null) {
                for (String address : allowedAddresses) {
                    if (address != null) {
                        copy.add(address);
                    }
                }
            }
            this.kind = kind;
            this.target = target;
            this.mimeType = mimeType;
            this.allowedAddresses = Collections.unmodifiableSet(copy);
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

    /** Registry bookkeeping around an immutable entry; only touched under {@link #lock}. */
    private static final class Slot {
        final Entry entry;
        final long registeredAt;
        /** Time of the first release, or {@link #NOT_RELEASED}. */
        long releasedAt = NOT_RELEASED;
        boolean released;

        Slot(Entry entry, long registeredAt) {
            this.entry = entry;
            this.registeredAt = registeredAt;
        }
    }

    private static final long NOT_RELEASED = Long.MIN_VALUE;

    private final LongSupplier nowMillis;
    private final Consumer<byte[]> randomBytes;
    private final int maxActive;

    /** Guards the fields below. Targets are never closed while it is held. */
    private final Object lock = new Object();
    private final Map<String, Slot> slots = new HashMap<>();
    private final ArrayDeque<String> retiredOrder = new ArrayDeque<>();
    private final Set<String> retiredKeys = new HashSet<>();

    /**
     * @param nowMillis   monotonic clock in milliseconds, not null
     * @param randomBytes fills the given array with random bytes (e.g. {@code
     *                    new SecureRandom()::nextBytes}), not null; must be thread safe or is
     *                    only called under the registry's lock
     * @param maxActive   &gt; 0
     * @throws IllegalArgumentException for a null argument or a non-positive maximum
     */
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes, int maxActive) {
        if (nowMillis == null) {
            throw new IllegalArgumentException("nowMillis must not be null");
        }
        if (randomBytes == null) {
            throw new IllegalArgumentException("randomBytes must not be null");
        }
        if (maxActive <= 0) {
            throw new IllegalArgumentException("maxActive must be positive");
        }
        this.nowMillis = nowMillis;
        this.randomBytes = randomBytes;
        this.maxActive = maxActive;
    }

    /** Same with {@link #DEFAULT_MAX_ACTIVE}. */
    public ShareRegistry(LongSupplier nowMillis, Consumer<byte[]> randomBytes) {
        this(nowMillis, randomBytes, DEFAULT_MAX_ACTIVE);
    }

    /** A registry on the monotonic system clock and a {@code SecureRandom}. */
    public static ShareRegistry createDefault() {
        SecureRandom secureRandom = new SecureRandom();
        return new ShareRegistry(() -> System.nanoTime() / 1_000_000L, secureRandom::nextBytes,
                DEFAULT_MAX_ACTIVE);
    }

    /**
     * Registers an entry. If no slot is free, expired entries are swept first, so they never block
     * a free slot.
     * Entries that were released but are still in their grace period keep their slot; a caller
     * that wants such an entry to make room calls {@link #evictOldestReleased()} and retries.
     *
     * @return the new key, or null if {@code maxActive} entries are active or no unused key could
     * be drawn (a broken random source repeating itself); in both cases nothing is stored and
     * the caller keeps ownership of the target (the registry does not close it)
     * @throws IllegalArgumentException if the entry is null
     */
    public String register(Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        List<Object> toClose = new ArrayList<>();
        String key = null;
        synchronized (lock) {
            long now = nowMillis.getAsLong();
            // lazy: expired entries are only swept when the slot is needed; expire() and the
            // other methods still report them
            if (slots.size() >= maxActive) {
                collectExpiredLocked(now, toClose);
            }
            if (slots.size() < maxActive) {
                key = drawUnusedKeyLocked();
                if (key != null) {
                    slots.put(key, new Slot(entry, now));
                }
            }
        }
        closeAll(toClose);
        return key;
    }

    /**
     * The one way the glue puts an entry into the registry: if the registry is full, the entry
     * that was released the longest ago (a leftover of an ended playback) is evicted
     * ({@link #evictOldestReleased()}) and the registration is tried ONCE more.
     * <p>
     * The registry does not close the target of an entry it refused: when this returns null the
     * caller still owns the target and must close it.
     *
     * @return the new key, or null if there was no room even after an eviction (the caller keeps
     * ownership of the target)
     */
    public String registerOrEvict(Entry entry) {
        String key = register(entry);
        if (key == null && evictOldestReleased()) {
            key = register(entry);
        }
        return key;
    }

    /**
     * @return the live entry for the key, or null for an unknown, garbage, expired or
     * released-and-grace-over key (an expired entry is removed and closed on the way)
     */
    public Entry lookup(String key) {
        if (!isWellFormedKey(key)) {
            return null;
        }
        List<Object> toClose = new ArrayList<>(1);
        Entry result = null;
        synchronized (lock) {
            Slot slot = slots.get(key);
            if (slot != null) {
                if (isExpired(slot, nowMillis.getAsLong())) {
                    removeLocked(key, slot, toClose);
                } else {
                    result = slot.entry;
                }
            }
        }
        closeAll(toClose);
        return result;
    }

    /**
     * Cancels a pending release (a player started again, a renderer is still fetching). Does not
     * extend the absolute maximum.
     *
     * @return true if the key names a live entry
     */
    public boolean markActive(String key) {
        if (!isWellFormedKey(key)) {
            return false;
        }
        List<Object> toClose = new ArrayList<>(1);
        boolean live = false;
        synchronized (lock) {
            Slot slot = slots.get(key);
            if (slot != null) {
                if (isExpired(slot, nowMillis.getAsLong())) {
                    removeLocked(key, slot, toClose);
                } else {
                    slot.released = false;
                    slot.releasedAt = NOT_RELEASED;
                    live = true;
                }
            }
        }
        closeAll(toClose);
        return live;
    }

    /**
     * Starts the grace period (the player ended). The first release counts; repeated releases do
     * not restart the grace period. Unknown keys are ignored.
     */
    public void release(String key) {
        if (!isWellFormedKey(key)) {
            return;
        }
        synchronized (lock) {
            Slot slot = slots.get(key);
            if (slot != null && !slot.released) {
                slot.released = true;
                slot.releasedAt = nowMillis.getAsLong();
            }
        }
    }

    /**
     * Removes the entry now and closes its target (once).
     *
     * @return true if an entry was removed
     */
    public boolean remove(String key) {
        if (!isWellFormedKey(key)) {
            return false;
        }
        List<Object> toClose = new ArrayList<>(1);
        boolean removed = false;
        synchronized (lock) {
            Slot slot = slots.get(key);
            if (slot != null) {
                removeLocked(key, slot, toClose);
                removed = true;
            }
        }
        closeAll(toClose);
        return removed;
    }

    /**
     * Makes room for a new share: removes and closes the entry that was released the longest ago
     * and is still in its grace period (a leftover of an ended playback). Entries that were never
     * released are not touched, so a running playback is never cut. Expired entries are swept as
     * well.
     *
     * @return true if an entry was evicted (a slot is free, apart from a concurrent
     * {@link #register}); false if there is no released entry
     */
    public boolean evictOldestReleased() {
        List<Object> toClose = new ArrayList<>(2);
        boolean evicted = false;
        synchronized (lock) {
            collectExpiredLocked(nowMillis.getAsLong(), toClose);
            String oldestKey = null;
            Slot oldest = null;
            for (Map.Entry<String, Slot> mapEntry : slots.entrySet()) {
                Slot slot = mapEntry.getValue();
                if (slot.released && (oldest == null || slot.releasedAt < oldest.releasedAt)) {
                    oldestKey = mapEntry.getKey();
                    oldest = slot;
                }
            }
            if (oldest != null) {
                removeLocked(oldestKey, oldest, toClose);
                evicted = true;
            }
        }
        closeAll(toClose);
        return evicted;
    }

    /** Removes and closes every entry that has expired. @return how many were removed */
    public int expire() {
        List<Object> toClose = new ArrayList<>();
        int count;
        synchronized (lock) {
            count = collectExpiredLocked(nowMillis.getAsLong(), toClose);
        }
        closeAll(toClose);
        return count;
    }

    /** Removes and closes every entry (service stop). */
    public void removeAll() {
        List<Object> toClose = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, Slot> mapEntry : slots.entrySet()) {
                retireLocked(mapEntry.getKey());
                toClose.add(mapEntry.getValue().entry.getTarget());
            }
            slots.clear();
        }
        closeAll(toClose);
    }

    /** @return the number of live entries (expired ones are swept first) */
    public int size() {
        List<Object> toClose = new ArrayList<>();
        int size;
        synchronized (lock) {
            collectExpiredLocked(nowMillis.getAsLong(), toClose);
            size = slots.size();
        }
        closeAll(toClose);
        return size;
    }

    // ---- internals; the *Locked methods need the lock and never close anything

    private boolean isExpired(Slot slot, long now) {
        Entry entry = slot.entry;
        if (now - slot.registeredAt >= entry.getAbsoluteMaxMillis()) {
            return true;
        }
        return slot.released && now - slot.releasedAt >= entry.getIdleGraceMillis();
    }

    private int collectExpiredLocked(long now, List<Object> toClose) {
        int count = 0;
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Slot> mapEntry = it.next();
            if (isExpired(mapEntry.getValue(), now)) {
                it.remove();
                retireLocked(mapEntry.getKey());
                toClose.add(mapEntry.getValue().entry.getTarget());
                count++;
            }
        }
        return count;
    }

    private void removeLocked(String key, Slot slot, List<Object> toClose) {
        slots.remove(key);
        retireLocked(key);
        toClose.add(slot.entry.getTarget());
    }

    private void retireLocked(String key) {
        if (retiredKeys.add(key)) {
            retiredOrder.addLast(key);
            while (retiredOrder.size() > RETIRED_KEY_WINDOW) {
                retiredKeys.remove(retiredOrder.removeFirst());
            }
        }
    }

    /** A fresh key that is neither live nor retired, or null if the random source keeps repeating. */
    private String drawUnusedKeyLocked() {
        byte[] bytes = new byte[KEY_BYTES];
        for (int attempt = 0; attempt < MAX_KEY_ATTEMPTS; attempt++) {
            randomBytes.accept(bytes);
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!slots.containsKey(key) && !retiredKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /** Closes outside the lock, each target once; a failing close never stops the others. */
    private static void closeAll(List<Object> targets) {
        for (Object target : targets) {
            if (target instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) target).close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // the entry is gone either way; nothing sensible to do and nothing to log
                    // (the target may carry a URI)
                }
            }
        }
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
        if (key == null || key.length() != KEY_TEXT_LENGTH) {
            return false;
        }
        for (int i = 0; i < KEY_TEXT_LENGTH; i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
