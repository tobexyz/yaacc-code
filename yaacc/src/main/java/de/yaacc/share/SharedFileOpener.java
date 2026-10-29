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

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

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

    /**
     * At most one copy runs at a time (a copy is up to 512 MiB of I/O and cache) and each is
     * watched: see {@link #copyAndHold}.
     */
    private static final WatchedCopy WATCHED_COPY = new WatchedCopy(ShareLimits.COPY_MAX_MILLIS,
            ShareLimits.COPY_MAX_STUCK_WORKERS);
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
     * space reserve, directory cap, one copy at a time) and holds the copy; the returned source
     * deletes the copy when it is closed. Closes {@code afd} in every case and deletes a partial
     * copy on every failure.
     * <p>
     * Takes as long as the copy takes, off the main thread, but at most
     * {@link ShareLimits#COPY_MAX_MILLIS}: the copy runs on its own worker and this call waits with
     * a deadline ({@link WatchedCopy}), because a provider whose pipe never delivers a byte would
     * block the read for ever. At the deadline the descriptor is closed from this thread (which
     * wakes the blocked read), the partial file is deleted, the copy slot is given back and the call
     * fails, even if the worker stays stuck.
     *
     * @throws OpenException for every failure
     */
    public static PositionalSource copyAndHold(AssetFileDescriptor afd, File incomingDir) throws OpenException {
        try {
            return WATCHED_COPY.copy(new DescriptorInput(afd),
                    () -> IncomingCache.createCopyFile(incomingDir, RANDOM::nextBytes),
                    () -> IncomingCache.remainingCopyBudget(incomingDir),
                    incomingDir::getUsableSpace,
                    SharedFileOpener::holdCopy);
        } catch (WatchedCopy.Failure e) {
            // the reason is an enum name, no payload
            YaaccLogger.w(TAG, "Could not copy the shared file (" + e.getReason() + ")");
            throw new OpenException(toReason(e.getReason()));
        }
    }

    private static Reason toReason(WatchedCopy.Reason reason) {
        switch (reason) {
            case TOO_BIG:
                return Reason.TOO_BIG;
            case NO_SPACE:
                return Reason.NO_SPACE;
            case BUSY:
                return Reason.BUSY;
            default:
                // unusable, or timed out
                return Reason.UNUSABLE;
        }
    }

    /** The provider's descriptor as the input of {@link WatchedCopy}; closing it wakes a blocked read. */
    private static final class DescriptorInput implements WatchedCopy.Input {
        private final AssetFileDescriptor afd;
        private volatile InputStream stream;

        DescriptorInput(AssetFileDescriptor afd) {
            this.afd = afd;
        }

        @Override
        public InputStream open() throws IOException {
            InputStream opened = afd.createInputStream();
            stream = opened;
            return opened;
        }

        @Override
        public long declaredLength() {
            return afd.getDeclaredLength();
        }

        @Override
        public void close() {
            InputStream opened = stream;
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException | RuntimeException ignored) {
                    // nothing sensible to do
                }
            }
            closeQuietly(afd);
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
