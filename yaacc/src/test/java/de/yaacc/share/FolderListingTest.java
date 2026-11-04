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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FolderListingTest {

    private static FolderListing.Row row(String id, String name, String mime, long size) {
        return new FolderListing.Row(id, name, mime, size);
    }

    private static FolderListing.RowSource rows(List<FolderListing.Row> list) {
        return maxRows -> {
            List<FolderListing.Row> result = new ArrayList<>(list);
            return result.size() > maxRows ? new ArrayList<>(result.subList(0, maxRows)) : result;
        };
    }

    @Test
    public void directoriesAreSkippedAndFilesGetTheUriOfTheBuilder() {
        List<FolderListing.Row> list = new ArrayList<>();
        list.add(row("d1", "Sub", FolderListing.MIME_TYPE_DIR, 0));
        list.add(row("f1", "a.mp3", "audio/mpeg", 10));
        FolderListing.Result result = FolderListing.list(rows(list), id -> "content://tree/" + id);
        assertEquals(1, result.entries().size());
        assertEquals("content://tree/f1", result.entries().get(0).getUri());
        assertEquals("a.mp3", result.entries().get(0).getDisplayName());
        assertEquals("audio/mpeg", result.entries().get(0).getMimeType());
        assertFalse(result.isTruncated());
    }

    @Test
    public void rowsWithoutIdAreDropped() {
        List<FolderListing.Row> list = new ArrayList<>();
        list.add(row(null, "a.mp3", "audio/mpeg", 1));
        list.add(row("", "b.mp3", "audio/mpeg", 1));
        list.add(row("f", "c.mp3", "audio/mpeg", 1));
        FolderListing.Result result = FolderListing.list(rows(list), id -> "content://t/" + id);
        assertEquals(1, result.entries().size());
    }

    @Test
    public void tooLongOrUnbuildableUrisAreDropped() {
        List<FolderListing.Row> list = new ArrayList<>();
        list.add(row("f1", "a.mp3", "audio/mpeg", 1));
        list.add(row("f2", "b.mp3", "audio/mpeg", 1));
        FolderListing.Result result = FolderListing.list(rows(list), id -> {
            if (id.equals("f1")) {
                return null;
            }
            return "content://t/" + new String(new char[SharedContentPolicy.MAX_URI_LENGTH]).replace('\0', 'x');
        });
        assertEquals(0, result.entries().size());
    }

    @Test
    public void onlyAFixedNumberOfRowsIsReadAndTheResultSaysSo() {
        List<FolderListing.Row> list = new ArrayList<>();
        for (int i = 0; i < FolderListing.MAX_ROWS + 50; i++) {
            list.add(row("f" + i, "n" + i, "audio/mpeg", 1));
        }
        FolderListing.Result result = FolderListing.list(rows(list), id -> "content://t/" + id);
        assertEquals(FolderListing.MAX_ROWS, result.entries().size());
        assertTrue(result.isTruncated());
    }

    @Test
    public void aSourceThatFailsGivesAnEmptyFailedResult() {
        FolderListing.Result result = FolderListing.list(maxRows -> null, id -> "content://t/" + id);
        assertEquals(0, result.entries().size());
        assertTrue(result.isFailed());
        FolderListing.Result thrown = FolderListing.list(maxRows -> {
            throw new SecurityException("revoked");
        }, id -> "content://t/" + id);
        assertTrue(thrown.isFailed());
        assertNull(null);
    }

    @Test
    public void namesAreCleanedAndCapped() {
        List<FolderListing.Row> list = new ArrayList<>();
        list.add(row("f", "evil\u0000\n" + new String(new char[500]).replace('\0', 'n') + ".mp3", "audio/mpeg", 1));
        FolderListing.Result result = FolderListing.list(rows(list), id -> "content://t/" + id);
        String name = result.entries().get(0).getDisplayName();
        assertTrue(name.length() <= DisplayName.MAX_LENGTH);
        assertFalse(name.contains("\u0000"));
        assertFalse(name.contains("\n"));
    }

    @Test
    public void cappedRowsKeepNoHugeTexts() {
        String huge = new String(new char[2_000_000]).replace('\0', 'x');
        FolderListing.Row row = FolderListing.Row.capped(huge, huge, huge, 5);
        // the oversized id and type are dropped, the name is cut
        FolderListing.Result result = FolderListing.list(maxRows -> java.util.Collections.singletonList(row),
                id -> "content://t/" + id);
        assertEquals(0, result.entries().size());
        FolderListing.Row ok = FolderListing.Row.capped("f1", huge, "audio/mpeg", 5);
        FolderListing.Result kept = FolderListing.list(maxRows -> java.util.Collections.singletonList(ok),
                id -> "content://t/" + id);
        assertEquals(1, kept.entries().size());
        assertTrue(kept.entries().get(0).getDisplayName().length() <= DisplayName.MAX_LENGTH);
    }

    @Test
    public void cappedRowKeepsNormalValuesAndNulls() {
        FolderListing.Row row = FolderListing.Row.capped("f1", "a.mp3", "audio/mpeg", 7);
        FolderListing.Result result = FolderListing.list(maxRows -> java.util.Collections.singletonList(row),
                id -> "content://t/" + id);
        assertEquals("a.mp3", result.entries().get(0).getDisplayName());
        assertEquals("audio/mpeg", result.entries().get(0).getMimeType());
        FolderListing.Row nothing = FolderListing.Row.capped(null, null, null, 0);
        assertEquals(0, FolderListing.list(maxRows -> java.util.Collections.singletonList(nothing),
                id -> "content://t/" + id).entries().size());
    }
}
