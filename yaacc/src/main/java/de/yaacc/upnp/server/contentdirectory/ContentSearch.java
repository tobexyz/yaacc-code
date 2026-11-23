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

import android.database.Cursor;
import android.provider.MediaStore;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.upnp.search.SearchExpression;
import de.yaacc.upnp.search.SearchPaging;
import de.yaacc.upnp.search.SearchSql;

/**
 * The {@code Search} action of the server (issue #336): what the control point searches for is looked for in the media store
 * (the music, the videos and the images the server shares, always inside the media paths of the settings, as a browse does) and
 * every match is built by the same code that builds it for a browse, so that an id and a link are the same either way. The
 * results are the music first, then the videos, then the images; a page may end inside a source. Files of the added SAF
 * folders are not searched.
 */
final class ContentSearch {

    /** The properties the server searches (the answer of {@code GetSearchCapabilities}); genres only where the media store has them. */
    static String capabilities() {
        StringBuilder caps = new StringBuilder("dc:title,dc:creator,upnp:artist,upnp:album,upnp:class");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            caps.append(",upnp:genre");
        }
        return caps.toString();
    }

    private static final String MUSIC_CLASS = "object.item.audioItem.musicTrack";
    private static final String VIDEO_CLASS = "object.item.videoItem";
    private static final String IMAGE_CLASS = "object.item.imageItem.photo";

    private final YaaccContentDirectory contentDirectory;
    private final java.util.Set<String> supported;

    ContentSearch(YaaccContentDirectory contentDirectory) {
        this.contentDirectory = contentDirectory;
        this.supported = new java.util.HashSet<>(java.util.Arrays.asList(capabilities().split(",")));
    }

    /** The found objects of one page and the number of all matches. */
    static final class Result {
        final List<DIDLObject> page = new ArrayList<>();
        long total;
    }

    /** Where in the tree of the server a search may start: the root, and the folders of one kind of items. */
    private enum Scope {
        ALL(true, true, true), MUSIC(true, false, false), VIDEO(false, true, false), IMAGE(false, false, true), NONE(false, false, false);

        final boolean music;
        final boolean video;
        final boolean image;

        Scope(boolean music, boolean video, boolean image) {
            this.music = music;
            this.video = video;
            this.image = image;
        }
    }

    private static Scope scopeOf(String containerId) {
        if (containerId == null || containerId.isEmpty() || ContentDirectoryIDs.ROOT.getId().equals(containerId)) {
            return Scope.ALL;
        }
        if (ContentDirectoryIDs.MUSIC_FOLDER.getId().equals(containerId) || ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId().equals(containerId)) {
            return Scope.MUSIC;
        }
        if (ContentDirectoryIDs.VIDEOS_FOLDER.getId().equals(containerId) || ContentDirectoryIDs.VIDEOS_ALL_FOLDER.getId().equals(containerId)) {
            return Scope.VIDEO;
        }
        if (ContentDirectoryIDs.IMAGES_FOLDER.getId().equals(containerId) || ContentDirectoryIDs.IMAGES_ALL_FOLDER.getId().equals(containerId)) {
            return Scope.IMAGE;
        }
        return Scope.NONE; // a folder this server does not search in (an album, a gallery, SAF): nothing is found there
    }

    Result search(String containerId, SearchExpression.Node criteria, long firstResult, long maxResults, SortCriterion[] orderby)
            throws SearchExpression.InvalidSearchException {
        Scope scope = scopeOf(containerId);
        Result result = new Result();
        SearchSql.Where music = scope.music ? SearchSql.build(criteria, musicSource(), supported) : SearchSql.Where.FALSE;
        SearchSql.Where video = scope.video ? SearchSql.build(criteria, videoSource(), supported) : SearchSql.Where.FALSE;
        SearchSql.Where image = scope.image ? SearchSql.build(criteria, imageSource(), supported) : SearchSql.Where.FALSE;
        MusicQuery musicQuery = new MusicQuery(music);
        VideoQuery videoQuery = new VideoQuery(video);
        ImageQuery imageQuery = new ImageQuery(image);
        long[] counts = {musicQuery.count(), videoQuery.count(), imageQuery.count()};
        result.total = SearchPaging.total(counts);
        long[][] slices = SearchPaging.slices(counts, firstResult, maxResults);
        result.page.addAll(musicQuery.page(slices[0][0], slices[0][1], orderby));
        result.page.addAll(videoQuery.page(slices[1][0], slices[1][1], orderby));
        result.page.addAll(imageQuery.page(slices[2][0], slices[2][1], orderby));
        return result;
    }

    // ---- what each kind of items is searched by

    private static SearchSql.Source musicSource() {
        SearchSql.Source source = new SearchSql.Source(MUSIC_CLASS)
                // the title of an item is its tag title and its file name (see MusicAllTitleItemBrowser): both are searched
                .column("dc:title", MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME)
                .column("upnp:artist", MediaStore.Audio.Media.ARTIST)
                .column("dc:creator", MediaStore.Audio.Media.ARTIST)
                .column("upnp:album", MediaStore.Audio.Media.ALBUM);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            source.column("upnp:genre", MediaStore.Audio.Media.GENRE);
        }
        return source;
    }

    private static SearchSql.Source videoSource() {
        return new SearchSql.Source(VIDEO_CLASS).column("dc:title", MediaStore.Video.Media.DISPLAY_NAME);
    }

    private static SearchSql.Source imageSource() {
        return new SearchSql.Source(IMAGE_CLASS).column("dc:title", MediaStore.Images.Media.DISPLAY_NAME);
    }

    // ---- the queries

    /** One kind of items: the media store query for the condition, the count and a page of built items. */
    private abstract class Query {
        private final SearchSql.Where where;

        Query(SearchSql.Where where) {
            this.where = where;
        }

        abstract android.net.Uri uri();

        abstract String pathColumn();

        abstract String[] projection();

        abstract String defaultSortOrder();

        abstract Map<String, String> sortColumns();

        abstract Item itemFrom(Cursor cursor);

        private String selection() {
            String base = "(" + MediaPathFilter.makeLikeClause(pathColumn(), MediaPathFilter.getMediaPathes(contentDirectory.getContext()).size()) + ")";
            return where.isTrue() ? base : base + " AND " + where.sql;
        }

        private String[] args() {
            List<String> args = new ArrayList<>(MediaPathFilter.getMediaPathesForLikeClause(contentDirectory.getContext()));
            args.addAll(where.args);
            return args.toArray(new String[0]);
        }

        long count() {
            if (where.isFalse()) {
                return 0L;
            }
            try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(uri(), new String[]{MediaStore.MediaColumns._ID},
                    selection(), args(), null)) {
                return cursor == null ? 0L : cursor.getCount();
            }
        }

        List<DIDLObject> page(long offset, long limit, SortCriterion[] orderby) {
            List<DIDLObject> items = new ArrayList<>();
            if (where.isFalse() || limit <= 0L) {
                return items;
            }
            String sortOrder = SortSupport.toMediaStoreSortOrder(orderby, sortColumns(), defaultSortOrder());
            try (Cursor cursor = contentDirectory.getContext().getContentResolver().query(uri(), projection(), selection(), args(), sortOrder)) {
                if (cursor == null || !cursor.moveToPosition((int) Math.min(offset, Integer.MAX_VALUE))) {
                    return items;
                }
                do {
                    Item item = itemFrom(cursor);
                    if (item != null) {
                        items.add(item);
                    }
                } while (items.size() < limit && cursor.moveToNext());
            }
            return items;
        }
    }

    private final class MusicQuery extends Query {
        private final MusicAllTitleItemBrowser browser = new MusicAllTitleItemBrowser(contentDirectory.getContext());

        MusicQuery(SearchSql.Where where) {
            super(where);
        }

        @Override
        android.net.Uri uri() {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        @Override
        String pathColumn() {
            return MediaStore.Audio.Media.DATA;
        }

        @Override
        String[] projection() {
            return MusicAllTitleItemBrowser.projection();
        }

        @Override
        String defaultSortOrder() {
            return MediaStore.Audio.Media.TITLE + " ASC";
        }

        @Override
        Map<String, String> sortColumns() {
            Map<String, String> columns = new HashMap<>();
            columns.put(SortSupport.PROPERTY_TITLE, MediaStore.Audio.Media.TITLE);
            columns.put(SortSupport.PROPERTY_DATE, MediaStore.Audio.Media.DATE_ADDED);
            return columns;
        }

        @Override
        Item itemFrom(Cursor cursor) {
            return browser.itemFrom(contentDirectory, cursor);
        }
    }

    private final class ImageQuery extends Query {
        private final ImageAllItemBrowser browser = new ImageAllItemBrowser(contentDirectory.getContext());

        ImageQuery(SearchSql.Where where) {
            super(where);
        }

        @Override
        android.net.Uri uri() {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        @Override
        String pathColumn() {
            return MediaStore.Images.Media.DATA;
        }

        @Override
        String[] projection() {
            return ImageAllItemBrowser.projection();
        }

        @Override
        String defaultSortOrder() {
            return MediaStore.Images.Media.DISPLAY_NAME + " ASC";
        }

        @Override
        Map<String, String> sortColumns() {
            Map<String, String> columns = new HashMap<>();
            columns.put(SortSupport.PROPERTY_TITLE, MediaStore.Images.Media.DISPLAY_NAME);
            columns.put(SortSupport.PROPERTY_DATE, MediaStore.Images.Media.DATE_TAKEN);
            return columns;
        }

        @Override
        Item itemFrom(Cursor cursor) {
            return browser.itemFrom(contentDirectory, cursor);
        }
    }

    /** The videos are read by the code the browsers of the videos share: its query, its items. */
    private final class VideoQuery extends Query {
        private final VideoSearch browser = new VideoSearch(contentDirectory.getContext());
        private final SearchSql.Where where;

        VideoQuery(SearchSql.Where where) {
            super(where);
            this.where = where;
        }

        @Override
        android.net.Uri uri() {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }

        @Override
        String pathColumn() {
            return MediaStore.Video.Media.DATA;
        }

        @Override
        String[] projection() {
            return new String[0]; // not used: the page is read by the video browser
        }

        @Override
        String defaultSortOrder() {
            return MediaStore.Video.Media.DISPLAY_NAME + " ASC";
        }

        @Override
        Map<String, String> sortColumns() {
            return new HashMap<>();
        }

        @Override
        Item itemFrom(Cursor cursor) {
            return null; // not used
        }

        @Override
        List<DIDLObject> page(long offset, long limit, SortCriterion[] orderby) {
            List<DIDLObject> items = new ArrayList<>();
            if (where.isFalse() || limit <= 0L) {
                return items;
            }
            String selection = browser.mediaPathSelection() + (where.isTrue() ? "" : " AND " + where.sql);
            List<String> args = new ArrayList<>(browser.mediaPathArguments());
            args.addAll(where.args);
            items.addAll(browser.items(contentDirectory, selection, args, ContentDirectoryIDs.VIDEOS_ALL_FOLDER.getId(), offset, limit, orderby));
            return items;
        }
    }

    /** Gives the search access to what the browsers of the videos have in common. */
    private static final class VideoSearch extends VideoBrowser {
        VideoSearch(android.content.Context context) {
            super(context);
        }

        @Override
        public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
            return 0;
        }

        @Override
        public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults,
                                     SortCriterion[] orderby) {
            return null;
        }

        @Override
        public List<org.fourthline.cling.support.model.container.Container> browseContainer(YaaccContentDirectory content, String myId,
                                                                                            long firstResult, long maxResults,
                                                                                            SortCriterion[] orderby) {
            return new ArrayList<>();
        }

        @Override
        public List<? extends Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults,
                                               SortCriterion[] orderby) {
            return new ArrayList<>();
        }
    }
}
