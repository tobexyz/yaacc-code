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
package de.yaacc.upnp.server.share;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import de.yaacc.share.BudgetedCopier;
import de.yaacc.share.DeleteOnCloseSource;
import de.yaacc.share.IncomingCache;
import de.yaacc.share.PositionalSource;
import de.yaacc.share.ShareLimits;
import de.yaacc.util.YaaccLogger;

/**
 * Opens a shared file after the user confirmed: the provider's descriptor is held if it can seek
 * ({@link HeldFileSource}), otherwise the file is copied to {@code cache/incoming/} and the copy
 * is held (and deleted when the source is closed). Thin glue: the rules (what to copy, how much,
 * how to stop) live in the plain-Java classes it calls.
 * <p>
 * Worker thread only: every call goes to the provider. The caller owns the returned source and
 * must close it; this class closes and deletes everything it opened itself when it fails.
 * Nothing here logs the URI, the name or the provider's text.
 */
public final class SharedFileOpener {

    private static final String TAG = SharedFileOpener.class.getName();
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    /** At most one copy runs at a time: a copy is up to 512 MiB of I/O and cache. */
    private static final Semaphore COPY_SLOT = new Semaphore(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why a file could not be opened. */
    public enum Reason {
        /** Not readable, not a usable file, or the provider failed. */
        UNUSABLE,
        /** The copy would be bigger than allowed. */
        TOO_BIG,
        /** Not enough free space, or the incoming directory is full. */
        NO_SPACE,
        /** Another copy is running. */
        BUSY
    }

    /** The file cannot be shared; {@link #getReason()} says why. The message holds no payload. */
    public static final class OpenException extends IOException {
        private final Reason reason;

        OpenException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    private SharedFileOpener() {
    }

    /**
     * The first step: what the provider handed out. Exactly one of the two is set; the owner
     * closes it ({@link #close()}) unless it passes it on.
     */
    public static final class Opened {
        private final PositionalSource source;
        private final AssetFileDescriptor needsCopy;

        private Opened(PositionalSource source, AssetFileDescriptor needsCopy) {
            this.source = source;
            this.needsCopy = needsCopy;
        }

        /** @return the held, seekable file, or null if the descriptor must be copied first */
        public PositionalSource getSource() {
            return source;
        }

        /** @return the provider's descriptor if it cannot seek (see {@link #copyAndHold}), else null */
        public AssetFileDescriptor getDescriptorToCopy() {
            return needsCopy;
        }

        /** Closes whatever is held; safe to call more than once. */
        public void close() {
            if (source != null) {
                source.close();
            }
            if (needsCopy != null) {
                closeQuietly(needsCopy);
            }
        }
    }

    /**
     * Opens the provider's descriptor and holds it if it can seek. A provider call that can
     * block: run it through {@code BoundedCall}, never on the main thread.
     *
     * @param resolver the content resolver
     * @param uri      the validated shared URI
     * @throws OpenException for every failure (nothing is left open)
     */
    public static Opened openDescriptor(ContentResolver resolver, Uri uri) throws OpenException {
        AssetFileDescriptor afd;
        try {
            afd = resolver.openAssetFileDescriptor(uri, "r");
        } catch (FileNotFoundException | RuntimeException e) {
            // a SecurityException is a RuntimeException: the grant is gone or never was
            YaaccLogger.w(TAG, "Could not open the shared file (" + e.getClass().getSimpleName() + ")");
            throw new OpenException(Reason.UNUSABLE);
        }
        if (afd == null) {
            throw new OpenException(Reason.UNUSABLE);
        }
        try {
            // on success the held source owns a dup and has closed afd
            return new Opened(HeldFileSource.open(afd), null);
        } catch (HeldFileSource.NotSeekableException e) {
            // afd is still ours: the caller copies from it
            return new Opened(null, afd);
        } catch (IOException | RuntimeException e) {
            closeQuietly(afd);
            YaaccLogger.w(TAG, "The shared file is not usable (" + e.getClass().getSimpleName() + ")");
            throw new OpenException(Reason.UNUSABLE);
        }
    }

    /**
     * The copy fallback: copies the provider's descriptor to {@code cache/incoming/} (byte cap, free
     * space reserve, directory cap, one copy at a time, time limit) and holds the copy; the
     * returned source deletes the copy when it is closed. Closes {@code afd} in every case and
     * deletes a partial copy on every failure. Takes as long as the copy takes: not through a
     * short timeout, but off the main thread.
     *
     * @throws OpenException for every failure
     */
    public static PositionalSource copyAndHold(AssetFileDescriptor afd, File incomingDir) throws OpenException {
        if (!COPY_SLOT.tryAcquire()) {
            closeQuietly(afd);
            throw new OpenException(Reason.BUSY);
        }
        File copy = null;
        try {
            long budget = IncomingCache.remainingCopyBudget(incomingDir);
            if (budget <= 0L) {
                throw new OpenException(Reason.NO_SPACE);
            }
            copy = IncomingCache.createCopyFile(incomingDir, RANDOM::nextBytes);
            copyInto(afd, copy, budget);
            PositionalSource held = holdCopy(copy);
            // from here on the source deletes the copy when it is closed
            PositionalSource source = new DeleteOnCloseSource(held, copy);
            copy = null;
            return source;
        } catch (OpenException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            YaaccLogger.w(TAG, "Could not copy the shared file (" + e.getClass().getSimpleName() + ")");
            throw new OpenException(Reason.UNUSABLE);
        } finally {
            closeQuietly(afd);
            if (copy != null) {
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
            }
            COPY_SLOT.release();
        }
    }

    private static void copyInto(AssetFileDescriptor afd, File target, long budget) throws IOException, OpenException {
        final long deadline = System.nanoTime() + ShareLimits.COPY_MAX_MILLIS * 1_000_000L;
        BooleanSupplier timedOut = () -> System.nanoTime() - deadline > 0L;
        LongSupplier usable = target.getParentFile()::getUsableSpace;
        BudgetedCopier.Result result;
        try (InputStream in = afd.createInputStream(); OutputStream out = Files.newOutputStream(target.toPath())) {
            result = BudgetedCopier.copy(in, out, afd.getDeclaredLength(), budget, usable, timedOut,
                    new byte[COPY_BUFFER_BYTES]);
        }
        switch (result.outcome()) {
            case COPIED:
                return;
            case TOO_BIG:
                throw new OpenException(Reason.TOO_BIG);
            case NO_SPACE:
                throw new OpenException(Reason.NO_SPACE);
            default:
                // cancelled by the deadline, or empty
                throw new OpenException(Reason.UNUSABLE);
        }
    }

    /** Reopens the finished copy as a descriptor and holds it like any other seekable file. */
    private static PositionalSource holdCopy(File copy) throws IOException {
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
        AssetFileDescriptor afd = new AssetFileDescriptor(descriptor, 0L, AssetFileDescriptor.UNKNOWN_LENGTH);
        try {
            return HeldFileSource.open(afd);
        } catch (IOException | RuntimeException e) {
            closeQuietly(afd);
            throw e;
        }
    }

    private static void closeQuietly(AssetFileDescriptor afd) {
        try {
            afd.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing sensible to do
        }
    }
}
