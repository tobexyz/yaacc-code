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

import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.FileDescriptor;
import java.io.IOException;

import de.yaacc.share.DescriptorWindow;
import de.yaacc.share.PositionalSource;

/**
 * A file of another app that is kept readable by holding its descriptor open. The registry
 * (as an {@code AutoCloseable} target of a LOCAL_FILE entry) closes it; closing never blocks and
 * never happens while a read runs ({@link PositionalSource}).
 * <p>
 * Reads use {@link Os#pread}, which does not touch the file offset, so any number of responses
 * read different ranges of the one descriptor. The held descriptor is a {@code dup} of the
 * provider's one, and the provider's is closed, so the provider process is not pinned by a
 * stable reference. The start offset and length the provider declares are clamped to the real
 * file size ({@link DescriptorWindow}).
 */
public final class HeldFileSource extends PositionalSource {

    /** The descriptor does not support positional reads (pipe, socket, device, not a regular file). */
    public static final class NotSeekableException extends IOException {
        NotSeekableException(String message) {
            super(message);
        }
    }

    private final ParcelFileDescriptor descriptor;
    private final long startOffset;

    private HeldFileSource(ParcelFileDescriptor descriptor, long startOffset, long length) {
        super(length);
        this.descriptor = descriptor;
        this.startOffset = startOffset;
    }

    /**
     * Whether positional reads work on the descriptor: a regular file ({@code fstat}) on which
     * {@code lseek(SEEK_CUR)} succeeds (it fails with ESPIPE for pipes and sockets). Cheap and
     * does not change the descriptor.
     *
     * @return false for anything else, also when the check itself fails
     */
    public static boolean isSeekable(FileDescriptor fd) {
        if (fd == null || !fd.valid()) {
            return false;
        }
        try {
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                return false;
            }
            Os.lseek(fd, 0L, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /**
     * Holds the file behind the provider's descriptor. On success this method has duplicated the
     * descriptor and closed {@code afd}, so the caller must not use or close it any more. On any
     * failure {@code afd} is left open, untouched and still owned by the caller (who may then
     * fall back to copying from it, and closes it).
     * <p>
     * Do not call on the main thread: {@code fstat} on a provider descriptor can block.
     *
     * @throws NotSeekableException if the descriptor cannot be read positionally
     * @throws IOException          if the descriptor is unusable (empty window, offset beyond the end, dup failed)
     */
    public static HeldFileSource open(AssetFileDescriptor afd) throws IOException {
        if (afd == null) {
            throw new IOException("no descriptor");
        }
        ParcelFileDescriptor provided = afd.getParcelFileDescriptor();
        if (provided == null || !isSeekable(provided.getFileDescriptor())) {
            throw new NotSeekableException("descriptor is not seekable");
        }
        long realSize;
        try {
            realSize = Os.fstat(provided.getFileDescriptor()).st_size;
        } catch (ErrnoException e) {
            throw new IOException("fstat failed");
        }
        long length = DescriptorWindow.clampLength(afd.getStartOffset(), afd.getDeclaredLength(), realSize);
        if (length < 0L) {
            throw new IOException("unusable descriptor window");
        }
        ParcelFileDescriptor held = provided.dup();
        HeldFileSource source = new HeldFileSource(held, afd.getStartOffset(), length);
        try {
            afd.close();
        } catch (IOException ignored) {
            // the dup is ours; a failing close of the provider's descriptor changes nothing
        }
        return source;
    }

    @Override
    protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
        try {
            // startOffset + position cannot overflow: position < size and startOffset + size <= file size
            return Os.pread(descriptor.getFileDescriptor(), buf, off, len, startOffset + position);
        } catch (ErrnoException e) {
            throw new IOException("read failed");
        }
    }

    @Override
    protected void closeInternal() {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // nothing sensible to do
        }
    }
}
