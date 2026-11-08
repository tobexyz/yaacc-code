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
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The app's {@code cache/incoming/} directory, where copies of provider files that cannot seek
 * are kept for the time of a share. Plain Java ({@code java.io}/{@code java.nio.file} only), so it
 * is tested on the JVM with a temporary folder.
 * <p>
 * The directory is private to the app, but it is still treated carefully: only entries that are
 * regular files with exactly the shape of {@link IncomingFileNames#randomName} are ever deleted,
 * symbolic links and directories are never followed, opened or deleted, and names never come
 * from the provider.
 */
public final class IncomingCache {

    /** The directory name below the cache directory. */
    public static final String DIR_NAME = "incoming";

    private static final int CREATE_ATTEMPTS = 4;

    private IncomingCache() {
    }

    /**
     * Deletes the leftovers of an earlier run (the registry is in memory, so no copy survives a
     * process). Never throws.
     *
     * @return the number of files deleted
     */
    public static int sweep(File dir) {
        if (dir == null) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir.toPath())) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name == null || !IncomingFileNames.isOurIncomingName(name.toString())) {
                    continue;
                }
                try {
                    // NOFOLLOW_LINKS: a link or a directory is not a regular file here
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && Files.deleteIfExists(entry)) {
                        deleted++;
                    }
                } catch (IOException | RuntimeException e) {
                    // leave it; the next start tries again
                }
            }
        } catch (IOException | RuntimeException e) {
            // no directory yet, or not readable: nothing to sweep
        }
        return deleted;
    }

    /**
     * @return the size of all regular files directly in the directory (links and directories are
     * not counted or followed), 0 if it does not exist; never throws
     */
    public static long totalBytes(File dir) {
        if (dir == null) {
            return 0L;
        }
        long total = 0L;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir.toPath())) {
            for (Path entry : entries) {
                try {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        total += Math.max(0L, Files.size(entry));
                    }
                } catch (IOException | RuntimeException e) {
                    // vanished meanwhile
                }
            }
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
        return total;
    }

    /**
     * How many bytes one more copy may have: the per-file cap, reduced so that all copies
     * together stay within {@link ShareLimits#INCOMING_DIR_MAX_BYTES}.
     *
     * @return 0 or more; 0 means the directory is full
     */
    public static long remainingCopyBudget(File dir) {
        long room = ShareLimits.INCOMING_DIR_MAX_BYTES - totalBytes(dir);
        return Math.max(0L, Math.min(SharedContentPolicy.MAX_COPY_BYTES, room));
    }

    /**
     * Creates a new, empty file with a random name in the directory (created if needed).
     *
     * @throws IOException if no file could be created
     */
    public static File createCopyFile(File dir, Consumer<byte[]> randomBytes) throws IOException {
        if (dir == null) {
            throw new IOException("no directory");
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create the directory");
        }
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            File file = new File(dir, IncomingFileNames.randomName(randomBytes));
            try {
                // CREATE_NEW fails on an existing file or link: a name is never reused or followed
                Files.createFile(file.toPath());
                return file;
            } catch (FileAlreadyExistsException e) {
                // draw another name
            }
        }
        throw new IOException("cannot create a copy file");
    }
}
