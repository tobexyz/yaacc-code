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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** The ids of the video folders reach their browsers; the old video ids still do. */
public class VideoRoutingTest {

    private static Class<?> browserOf(String objectId) {
        SharedPreferences preferences = mock(SharedPreferences.class);
        try (MockedStatic<PreferenceManager> manager = Mockito.mockStatic(PreferenceManager.class)) {
            manager.when(() -> PreferenceManager.getDefaultSharedPreferences(any())).thenReturn(preferences);
            return new YaaccContentDirectory(mock(Context.class), 0).findBrowserFor(objectId).getClass();
        }
    }

    @Test
    public void theVideoFoldersAndGalleriesAreRouted() {
        assertEquals(VideosFolderBrowser.class, browserOf("400999"));
        assertEquals(VideosAllFolderBrowser.class, browserOf("430999"));
        assertEquals(VideosByBucketNamesFolderBrowser.class, browserOf("440999"));
        assertEquals(VideosByBucketNameFolderBrowser.class, browserOf("4509997"));
        assertEquals(VideosByBucketNameFolderBrowser.class, browserOf("450999-7"));
    }

    @Test
    public void theVideoItemsAreStillRouted() {
        assertEquals(VideoItemBrowser.class, browserOf("41099911"));
    }

    @Test
    public void theImageFoldersAreNotAffected() {
        assertEquals(ImagesFolderBrowser.class, browserOf("100999"));
        assertEquals(ImagesAllFolderBrowser.class, browserOf("300999"));
        assertEquals(ImagesByBucketNamesFolderBrowser.class, browserOf("200999"));
        assertEquals(ImagesByBucketNameFolderBrowser.class, browserOf("2109991"));
    }
}
