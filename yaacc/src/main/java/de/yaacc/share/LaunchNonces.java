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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Single-use tokens that prove an intent for the browser came from {@code LauncherActivity}
 * after its checks. Plain Java, memory only.
 * <p>
 * The browser activities are exported, so any app can start them with a file share and skip the
 * launcher (and with it the Android 15 check that the real sender holds read access). The launcher
 * therefore {@link #mint()}s a token when its checks have passed and puts it into the fresh intent
 * it forwards; the browser accepts a shared <i>file</i> only if the intent presents a token that
 * {@link #consume(String)} accepts. The store lives in this process only (another app can neither
 * read nor guess it: 128 bits from a {@link SecureRandom}), a token is valid for a short time and
 * once, the store is bounded, and the comparison is constant-time. A token that is replayed (a
 * recents re-delivery, a process restart) is simply not found.
 */
public final class LaunchNonces {

    /** The intent extra that carries the token from the launcher to the browser. */
    public static final String EXTRA_NAME = "de.yaacc.extra.LAUNCH_NONCE";

    private static final int NONCE_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final class Entry {
        final byte[] text;
        final long mintedAtMillis;

        Entry(byte[] text, long mintedAtMillis) {
            this.text = text;
            this.mintedAtMillis = mintedAtMillis;
        }
    }

    private final LongSupplier nowMillis;
    private final Consumer<byte[]> randomBytes;
    private final long ttlMillis;
    private final int maxOutstanding;

    // oldest first; guarded by "this"
    private final List<Entry> entries = new ArrayList<>();

    /**
     * @param nowMillis      a monotonic clock
     * @param randomBytes    fills the array with unpredictable bytes
     * @param ttlMillis      how long a token is valid, positive
     * @param maxOutstanding how many unused tokens are kept, at least 1
     */
    public LaunchNonces(LongSupplier nowMillis, Consumer<byte[]> randomBytes, long ttlMillis, int maxOutstanding) {
        if (nowMillis == null || randomBytes == null || ttlMillis <= 0L || maxOutstanding < 1) {
            throw new IllegalArgumentException("clock, random source, a positive ttl and a size are required");
        }
        this.nowMillis = nowMillis;
        this.randomBytes = randomBytes;
        this.ttlMillis = ttlMillis;
        this.maxOutstanding = maxOutstanding;
    }

    /** The production store: monotonic clock, {@link SecureRandom}, the limits of {@link ShareLimits}. */
    public static LaunchNonces createDefault() {
        SecureRandom secureRandom = new SecureRandom();
        return new LaunchNonces(() -> System.nanoTime() / 1_000_000L, secureRandom::nextBytes,
                ShareLimits.LAUNCH_NONCE_TTL_MILLIS, ShareLimits.LAUNCH_NONCE_MAX_OUTSTANDING);
    }

    /**
     * Makes a new token. Expired tokens go first; if the store is still full, the oldest live one
     * is dropped.
     *
     * @return 32 lower-case hex characters (128 bits)
     */
    public synchronized String mint() {
        long now = nowMillis.getAsLong();
        purgeExpired(now);
        while (entries.size() >= maxOutstanding) {
            entries.remove(0);
        }
        byte[] fresh = new byte[NONCE_BYTES];
        randomBytes.accept(fresh);
        String text = toHex(fresh);
        entries.add(new Entry(text.getBytes(StandardCharsets.UTF_8), now));
        return text;
    }

    /**
     * Spends a token.
     *
     * @param presented what the intent carried, may be null
     * @return true exactly once for each minted token, while it is valid; false for a missing,
     * forged, expired, wrong or already spent one
     */
    public synchronized boolean consume(String presented) {
        purgeExpired(nowMillis.getAsLong());
        if (presented == null || entries.isEmpty()) {
            return false;
        }
        byte[] given = presented.getBytes(StandardCharsets.UTF_8);
        int matched = -1;
        // every entry is compared, whatever matched: the time does not say which one was close
        for (int i = 0; i < entries.size(); i++) {
            if (MessageDigest.isEqual(given, entries.get(i).text)) {
                matched = i;
            }
        }
        if (matched < 0) {
            return false;
        }
        entries.remove(matched);
        return true;
    }

    /** @return how many unused, unexpired tokens are held (for tests) */
    synchronized int size() {
        purgeExpired(nowMillis.getAsLong());
        return entries.size();
    }

    private void purgeExpired(long now) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            long elapsed = now - it.next().mintedAtMillis;
            // a clock that runs backwards counts as expired
            if (elapsed < 0L || elapsed >= ttlMillis) {
                it.remove();
            }
        }
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
