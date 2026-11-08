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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Random;

/** {@link IncomingCache}: sweep, size accounting and file creation in the incoming directory. */
public class IncomingCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String OUR_NAME = "share-0123456789abcdef0123456789abcdef.bin";
    private static final String OTHER_OUR_NAME = "share-fedcba9876543210fedcba9876543210.bin";

    private File dir() throws IOException {
        return temp.newFolder("incoming");
    }

    private static void write(File file, int size) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            f.setLength(size);
        }
    }

    // ---- sweep

    @Test
    public void sweepDeletesOurFilesAndNothingElse() throws IOException {
        File dir = dir();
        File ours = new File(dir, OUR_NAME);
        File foreign = new File(dir, "notes.txt");
        File upper = new File(dir, OUR_NAME.toUpperCase());
        File tooShort = new File(dir, "share-0123.bin");
        write(ours, 10);
        write(foreign, 10);
        write(upper, 10);
        write(tooShort, 10);

        int deleted = IncomingCache.sweep(dir);

        assertEquals(1, deleted);
        assertFalse(ours.exists());
        assertTrue(foreign.exists());
        assertTrue(tooShort.exists());
    }

    @Test
    public void sweepNeverDescendsIntoOrDeletesADirectoryWithOurName() throws IOException {
        File dir = dir();
        File lookalike = new File(dir, OUR_NAME);
        assertTrue(lookalike.mkdir());
        File inside = new File(lookalike, OTHER_OUR_NAME);
        write(inside, 5);

        assertEquals(0, IncomingCache.sweep(dir));

        assertTrue(lookalike.isDirectory());
        assertTrue("the content of a directory is not touched", inside.exists());
    }

    @Test
    public void sweepNeverFollowsOrDeletesASymbolicLink() throws IOException {
        File dir = dir();
        File target = temp.newFile("precious.txt");
        File link = new File(dir, OUR_NAME);
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("no symbolic links here", e);
        }

        assertEquals(0, IncomingCache.sweep(dir));

        assertTrue(target.exists());
        assertTrue(Files.isSymbolicLink(link.toPath()));
    }

    @Test
    public void sweepOfAMissingDirectoryOrNullDoesNothing() throws IOException {
        assertEquals(0, IncomingCache.sweep(new File(temp.getRoot(), "nope")));
        assertEquals(0, IncomingCache.sweep(null));
    }

    // ---- totals and budget

    @Test
    public void totalBytesAddsRegularFilesOnly() throws IOException {
        File dir = dir();
        write(new File(dir, OUR_NAME), 100);
        write(new File(dir, "other.dat"), 50);
        File sub = new File(dir, "sub");
        assertTrue(sub.mkdir());
        write(new File(sub, "x"), 1_000);

        assertEquals(150L, IncomingCache.totalBytes(dir));
    }

    @Test
    public void totalBytesOfAMissingDirectoryIsZero() {
        assertEquals(0L, IncomingCache.totalBytes(new File(temp.getRoot(), "nope")));
        assertEquals(0L, IncomingCache.totalBytes(null));
    }

    @Test
    public void anEmptyDirectoryAllowsTheFullPerFileCap() throws IOException {
        assertEquals(SharedContentPolicy.MAX_COPY_BYTES, IncomingCache.remainingCopyBudget(dir()));
    }

    @Test
    public void theBudgetShrinksWithWhatIsThere() throws IOException {
        File dir = dir();
        // a sparse file: the size counts, no disk is used
        write(new File(dir, OUR_NAME), 1);
        long cap = ShareLimits.INCOMING_DIR_MAX_BYTES;
        try (RandomAccessFile f = new RandomAccessFile(new File(dir, OTHER_OUR_NAME), "rw")) {
            f.setLength(cap - 1_000_000L);
        }

        long budget = IncomingCache.remainingCopyBudget(dir);

        assertEquals(1_000_000L - 1L, budget);
    }

    @Test
    public void aFullDirectoryHasNoBudget() throws IOException {
        File dir = dir();
        try (RandomAccessFile f = new RandomAccessFile(new File(dir, OUR_NAME), "rw")) {
            f.setLength(ShareLimits.INCOMING_DIR_MAX_BYTES + 5);
        }

        assertEquals(0L, IncomingCache.remainingCopyBudget(dir));
    }

    @Test
    public void theDirectoryCapIsOneGiBAndHoldsTwoMaxCopies() {
        assertEquals(1024L * 1024L * 1024L, ShareLimits.INCOMING_DIR_MAX_BYTES);
        assertEquals(2 * SharedContentPolicy.MAX_COPY_BYTES, ShareLimits.INCOMING_DIR_MAX_BYTES);
    }

    // ---- createCopyFile

    @Test
    public void createCopyFileMakesTheDirectoryAndAnEmptyFileWithOurShape() throws IOException {
        File dir = new File(temp.getRoot(), "incoming");
        Random random = new Random(1);

        File file = IncomingCache.createCopyFile(dir, random::nextBytes);

        assertTrue(file.exists());
        assertEquals(0L, file.length());
        assertEquals(dir, file.getParentFile());
        assertTrue(IncomingFileNames.isOurIncomingName(file.getName()));
    }

    @Test
    public void createCopyFileDrawsAnotherNameWhenOneIsTaken() throws IOException {
        File dir = dir();
        int[] calls = new int[1];
        // first draw collides with an existing file, the second one is fresh
        write(new File(dir, IncomingFileNames.randomName(bytes -> java.util.Arrays.fill(bytes, (byte) 7))), 3);

        File file = IncomingCache.createCopyFile(dir, bytes -> java.util.Arrays.fill(bytes, (byte) (calls[0]++ == 0 ? 7 : 9)));

        assertEquals(2, calls[0]);
        assertEquals(0L, file.length());
        assertEquals(IncomingFileNames.randomName(bytes -> java.util.Arrays.fill(bytes, (byte) 9)), file.getName());
    }

    @Test(expected = IOException.class)
    public void createCopyFileGivesUpWhenEveryNameIsTaken() throws IOException {
        File dir = dir();
        write(new File(dir, IncomingFileNames.randomName(bytes -> java.util.Arrays.fill(bytes, (byte) 7))), 3);

        IncomingCache.createCopyFile(dir, bytes -> java.util.Arrays.fill(bytes, (byte) 7));
    }
}
