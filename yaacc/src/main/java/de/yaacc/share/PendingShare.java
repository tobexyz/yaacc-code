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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The one shared file that waits for the user's receiver pick. Plain Java, thread-safe, no
 * {@code android.*}.
 * <p>
 * Intake is untrusted (the launcher is exported), so a share that nobody confirmed holds nothing
 * but text: the validated URI string, the cleaned display name and the declared MIME type. No
 * descriptor, no copy and no registry slot exist until the user has picked receivers.
 * <ul>
 * <li>At most ONE share is pending. {@link #offer} while one is pending and not expired is
 * dropped (returns null, changes nothing, does not extend the timeout); it never replaces the
 * pending one, so the user cannot confirm a different file than the one named in the picker.</li>
 * <li>The pending share expires {@code timeout} after it was offered: at exactly that moment it is
 * gone ({@code elapsed >= timeout}; a clock that runs backwards counts as expired). After that a
 * new share may be offered.</li>
 * <li>The pending action is bound to a random 128-bit token (32 hex chars) that {@link #offer}
 * returns. {@link #take}, {@link #peek} and {@link #cancel} need it, so a stale, forged or second
 * result for another sheet changes nothing.</li>
 * <li>{@link #take} hands the data out exactly once and clears it.</li>
 * </ul>
 * The clock must be monotonic (the default uses {@code System.nanoTime()}); the random source is
 * injectable for tests. {@code toString()} of {@link Data} does not show its content.
 */
public final class PendingShare {

    /** How long a share waits for the user's pick: two minutes. */
    public static final long DEFAULT_TIMEOUT_MILLIS = ShareLimits.PENDING_SHARE_TIMEOUT_MILLIS;

    private static final int TOKEN_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** What a pending share holds. Immutable. */
    public static final class Data {
        private final String uri;
        private final String displayName;
        private final String mimeType;

        private Data(String uri, String displayName, String mimeType) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
        }

        /** @return the validated {@code content:} URI as text, never null or empty */
        public String getUri() {
            return uri;
        }

        /** @return the cleaned display name (see {@link DisplayName}), never null, may be empty */
        public String getDisplayName() {
            return displayName;
        }

        /** @return the MIME type the intent declared, never null, may be empty */
        public String getMimeType() {
            return mimeType;
        }

        @Override
        public String toString() {
            return "PendingShare.Data";
        }
    }

    private final LongSupplier nowMillis;
    private final Consumer<byte[]> randomBytes;
    private final long timeoutMillis;

    // all guarded by "this"
    private Data data;
    private byte[] tokenBytes;
    private long offeredAtMillis;

    /**
     * @param nowMillis     a monotonic clock in milliseconds
     * @param randomBytes   fills the given array with random bytes (e.g. {@code secureRandom::nextBytes})
     * @param timeoutMillis how long a share waits for the pick, greater than zero
     * @throws IllegalArgumentException if a source is null or the timeout is not positive
     */
    public PendingShare(LongSupplier nowMillis, Consumer<byte[]> randomBytes, long timeoutMillis) {
        if (nowMillis == null || randomBytes == null || timeoutMillis <= 0L) {
            throw new IllegalArgumentException("clock, random source and a positive timeout are required");
        }
        this.nowMillis = nowMillis;
        this.randomBytes = randomBytes;
        this.timeoutMillis = timeoutMillis;
    }

    /** A holder on the monotonic system clock and a {@code SecureRandom} with the default timeout. */
    public static PendingShare createDefault() {
        SecureRandom secureRandom = new SecureRandom();
        return new PendingShare(() -> System.nanoTime() / 1_000_000L, secureRandom::nextBytes,
                DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Makes a share pending, if none is.
     *
     * @param uri         the validated URI text (the caller ran {@link SharedContentPolicy}); null or
     *                    empty is refused
     * @param displayName the display name; it is cleaned again here (idempotent), null becomes empty
     * @param mimeType    the declared MIME type, null becomes empty
     * @return the token that binds the pending action, or null (and nothing changes) if a share is
     * already pending and not expired, or if the URI is null or empty
     */
    public synchronized String offer(String uri, String displayName, String mimeType) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        long now = nowMillis.getAsLong();
        if (data != null && !isExpired(now)) {
            return null;
        }
        byte[] fresh = new byte[TOKEN_BYTES];
        randomBytes.accept(fresh);
        data = new Data(uri, DisplayName.clean(displayName), mimeType == null ? "" : mimeType);
        tokenBytes = fresh;
        offeredAtMillis = now;
        return toHex(fresh);
    }

    /**
     * Hands the pending share out, once.
     *
     * @return the data, or null for a wrong, null, consumed, cancelled or expired token
     */
    public synchronized Data take(String token) {
        Data matched = match(token);
        if (matched != null) {
            clear();
        }
        return matched;
    }

    /**
     * Reads the pending share without consuming it (e.g. for the picker title).
     *
     * @return the data, or null for a wrong, null, consumed, cancelled or expired token
     */
    public synchronized Data peek(String token) {
        return match(token);
    }

    /**
     * Releases the pending share.
     *
     * @return true if the token matched and the pending share was released; false (and nothing
     * changes) for a wrong, null, consumed or already released token
     */
    public synchronized boolean cancel(String token) {
        if (match(token) == null) {
            return false;
        }
        clear();
        return true;
    }

    /** @return true if a share is pending and has not expired */
    public synchronized boolean isPending() {
        return data != null && !isExpired(nowMillis.getAsLong());
    }

    private Data match(String token) {
        if (data == null || token == null) {
            return null;
        }
        if (isExpired(nowMillis.getAsLong())) {
            clear();
            return null;
        }
        // constant-time compare of the token text against the held one
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        byte[] held = toHex(tokenBytes).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, held) ? data : null;
    }

    private boolean isExpired(long now) {
        long elapsed = now - offeredAtMillis;
        return elapsed < 0L || elapsed >= timeoutMillis;
    }

    private void clear() {
        data = null;
        tokenBytes = null;
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
            chars[2 * i + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(chars);
    }
}
