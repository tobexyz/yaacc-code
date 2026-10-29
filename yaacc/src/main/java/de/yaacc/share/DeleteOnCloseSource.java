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

import java.io.File;
import java.io.IOException;

/**
 * The registered source of a copied file: reads go to the wrapped source, and when this source
 * is really closed (never while a read runs, see {@link PositionalSource}) the wrapped source is
 * closed first and then the copy is deleted, so a copy never outlives its share. Plain Java.
 */
public final class DeleteOnCloseSource extends PositionalSource {

    private final PositionalSource delegate;
    private final File file;

    /**
     * @param delegate the source that reads the copy
     * @param file     the copy to delete
     * @throws IllegalArgumentException if an argument is null
     */
    public DeleteOnCloseSource(PositionalSource delegate, File file) {
        super(requireNonNull(delegate).size());
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        this.delegate = delegate;
        this.file = file;
    }

    private static PositionalSource requireNonNull(PositionalSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source required");
        }
        return source;
    }

    @Override
    protected int readAtInternal(long position, byte[] buf, int off, int len) throws IOException {
        return delegate.readAt(position, buf, off, len);
    }

    @Override
    protected void closeInternal() {
        try {
            delegate.close();
        } finally {
            // POSIX: an inode that is still open stays readable after the unlink
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
