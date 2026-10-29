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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.contentdirectory.ContentDirectoryErrorCode;
import org.fourthline.cling.support.contentdirectory.ContentDirectoryException;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.BrowseResult;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NumberReturned and TotalMatches of Browse: BrowseMetadata returns the one object (1 and 1, the child count of a
 * folder is not a match count), BrowseDirectChildren returns the page and the number of children.
 */
public class YaaccContentDirectoryBrowseCountTest {

    private static final SortCriterion[] NO_SORT = new SortCriterion[0];

    /** A browser of a folder with 5 children, of which a page of 2 is returned; unknown ids do not exist. */
    private static class FolderBrowser extends ContentBrowser {
        final AtomicInteger sizeCalls = new AtomicInteger();
        final AtomicInteger metaCalls = new AtomicInteger();
        final AtomicInteger childrenCalls = new AtomicInteger();

        FolderBrowser() {
            super(mock(Context.class));
        }

        @Override
        public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
            sizeCalls.incrementAndGet();
            return 5;
        }

        @Override
        public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
            metaCalls.incrementAndGet();
            if (!myId.equals("saf-folder")) {
                return null;
            }
            return new StorageFolder(myId, "0", "Folder", "yaacc", 5, null);
        }

        @Override
        public List<Container> browseContainer(YaaccContentDirectory content, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
            childrenCalls.incrementAndGet();
            List<Container> result = new ArrayList<>();
            result.add(new StorageFolder("c1", myId, "One", "yaacc", 0, null));
            result.add(new StorageFolder("c2", myId, "Two", "yaacc", 0, null));
            return result;
        }

        @Override
        public List<? extends Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
            return new ArrayList<>();
        }
    }

    private static YaaccContentDirectory directory(FolderBrowser browser) {
        SharedPreferences preferences = mock(SharedPreferences.class); // the test content switch is off
        try (MockedStatic<PreferenceManager> preferenceManager = Mockito.mockStatic(PreferenceManager.class)) {
            preferenceManager.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(preferences);
            return new YaaccContentDirectory(mock(Context.class)) {
                @Override
                ContentBrowser findBrowserFor(String objectID) {
                    return browser;
                }
            };
        }
    }

    @Test
    public void browseMetadataOfAnExistingFolderReturnsOneObjectAndTotalMatchesOne() throws Exception {
        FolderBrowser browser = new FolderBrowser();

        BrowseResult result = directory(browser).browse("saf-folder", BrowseFlag.METADATA, "*", 0, 0, NO_SORT);

        assertEquals(1, result.getCountLong());
        assertEquals("not the child count of the folder", 1, result.getTotalMatchesLong());
        assertEquals("exactly one object", 1, countOccurrences(result.getResult(), "<container "));
        assertTrue(result.getResult().contains("id=\"saf-folder\""));
    }

    @Test
    public void browseMetadataDoesNotCountTheChildren() throws Exception {
        FolderBrowser browser = new FolderBrowser();

        directory(browser).browse("saf-folder", BrowseFlag.METADATA, "*", 0, 0, NO_SORT);

        assertEquals("getSize is for BrowseDirectChildren only", 0, browser.sizeCalls.get());
        assertEquals(1, browser.metaCalls.get());
    }

    @Test
    public void browseMetadataOfAMissingObjectIsNoSuchObject701() throws Exception {
        FolderBrowser browser = new FolderBrowser();

        try {
            directory(browser).browse("gone", BrowseFlag.METADATA, "*", 0, 0, NO_SORT);
            fail("expected 701");
        } catch (ContentDirectoryException e) {
            assertEquals(ContentDirectoryErrorCode.NO_SUCH_OBJECT.getCode(), e.getErrorCode());
        }
        assertEquals(0, browser.sizeCalls.get());
    }

    @Test
    public void browseDirectChildrenIsUnchangedNumberReturnedIsThePageTotalMatchesTheChildCount() throws Exception {
        FolderBrowser browser = new FolderBrowser();

        BrowseResult result = directory(browser).browse("saf-folder", BrowseFlag.DIRECT_CHILDREN, "*", 0, 2, NO_SORT);

        assertEquals(2, result.getCountLong());
        assertEquals(5, result.getTotalMatchesLong());
        assertEquals(2, countOccurrences(result.getResult(), "<container "));
        assertEquals(1, browser.sizeCalls.get());
        assertFalse(result.getResult().contains("id=\"saf-folder\""));
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }
}
