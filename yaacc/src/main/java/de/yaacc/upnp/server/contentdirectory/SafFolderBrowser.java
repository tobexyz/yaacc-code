/*
 *
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

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.documentfile.provider.DocumentFile;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import de.yaacc.R;
import de.yaacc.upnp.model.YaaccItem;
import de.yaacc.upnp.model.YaaccRes;
import de.yaacc.util.SAFCacheManager;
import de.yaacc.util.SAFMetadata;
import de.yaacc.util.YaaccLogger;

/**
 * Browser for saf folder.
 *
 * @author tobexyz
 */
public class SafFolderBrowser extends ContentBrowser {

    /** Containers (directories) are title-only - no meaningful single date. */
    private static final Map<String, Function<DIDLObject, String>> CONTAINER_ACCESSOR_MAP =
            Map.of(SortSupport.PROPERTY_TITLE, DIDLObject::getTitle);

    /** Items (files) also expose {@code dc:date}, sourced from {@link DocumentFile#lastModified()}. */
    private static final Map<String, Function<DIDLObject, String>> ITEM_ACCESSOR_MAP =
            Map.of(
                    SortSupport.PROPERTY_TITLE, DIDLObject::getTitle,
                    SortSupport.PROPERTY_DATE, item -> item.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class));

    /**
     * No-op comparator used as the {@code defaultComparator} for {@link SortSupport#toComparator}.
     * {@link List#sort} is a stable sort, so applying this leaves a list already built in
     * today's default order unchanged when no {@code SortCriteria} was requested.
     */
    private static final Comparator<DIDLObject> STABLE_ORDER = (a, b) -> 0;

    /**
     * Lightweight pairing of a candidate item file with the URI string
     * {@link #createItem} expects, so a folder's items can be sorted by cheap
     * {@link DocumentFile} fields (name / lastModified) <em>before</em> the
     * expensive {@code createItem(...)} pipeline (SAF metadata cache lookup,
     * MIME sniffing, {@code ProtocolInfo}/URI building) runs - that pipeline
     * is only run for the page slice actually returned, not the whole folder.
     */
    private static final class SafFileEntry {
        final String uri;
        final DocumentFile file;

        SafFileEntry(String uri, DocumentFile file) {
            this.uri = uri;
            this.file = file;
        }
    }

    /**
     * Cheap accessors for {@link SafFileEntry} - mirrors {@link #ITEM_ACCESSOR_MAP}'s
     * {@code dc:title}/{@code dc:date} mapping, but reads straight off
     * {@link DocumentFile} (name / lastModified) instead of a built DIDL item,
     * so sorting doesn't require {@link #createItem} to have run first.
     */
    private static final Map<String, Function<SafFileEntry, String>> ENTRY_ACCESSOR_MAP =
            Map.of(
                    SortSupport.PROPERTY_TITLE, entry -> entry.file.getName(),
                    SortSupport.PROPERTY_DATE, entry -> entry.file.lastModified() > 0
                            ? SortSupport.formatEpochMillisAsDate(entry.file.lastModified())
                            : null);

    /** Same idea as {@link #STABLE_ORDER}, but for the {@link SafFileEntry} pre-sort pass. */
    private static final Comparator<SafFileEntry> STABLE_ENTRY_ORDER = (a, b) -> 0;

    public SafFolderBrowser(Context context) {
        super(context);
    }

    /**
     * Mirrors {@link SortSupport#toComparator}'s direction/null-handling logic
     * exactly, but generic over a lightweight sort-key type instead of
     * {@link DIDLObject} - used here so {@link SafFileEntry} lists can be sorted
     * before the expensive {@link #createItem} pipeline runs. Kept local to this
     * class since {@code SortSupport.toComparator}'s signature is fixed to
     * {@code DIDLObject} for its other (MediaStore/synthetic-list) callers.
     */
    private static <T> Comparator<T> buildAccessorComparator(
            SortCriterion[] orderby,
            Map<String, Function<T, String>> propertyToAccessor,
            Comparator<T> defaultComparator) {
        if (orderby == null || orderby.length == 0) {
            return defaultComparator;
        }
        List<Comparator<T>> comparators = new ArrayList<>();
        for (SortCriterion criterion : orderby) {
            Function<T, String> accessor = propertyToAccessor.get(criterion.getPropertyName());
            if (accessor == null) {
                continue;
            }
            Comparator<T> comparator = Comparator.comparing(
                    accessor, Comparator.nullsFirst(Comparator.naturalOrder()));
            if (!criterion.isAscending()) {
                comparator = comparator.reversed();
            }
            comparators.add(comparator);
        }
        if (comparators.isEmpty()) {
            return defaultComparator;
        }
        Comparator<T> combined = comparators.get(0);
        for (int i = 1; i < comparators.size(); i++) {
            combined = combined.thenComparing(comparators.get(i));
        }
        return combined;
    }

    /** Matches {@link #createItem}'s own playlist-skip check, so m3u files never
     *  occupy a candidate slot (and therefore never shift pagination/order for
     *  real items), without having to call createItem to find that out. */
    private static boolean isPlaylist(DocumentFile file) {
        return file.getName() != null && file.getName().endsWith("m3u");
    }

    @Override
    public DIDLObject browseMeta(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            return new StorageFolder(ContentDirectoryIDs.SAF_FOLDER.getId(), ContentDirectoryIDs.ROOT.getId(), getContext().getString(R.string.saf_content), "yaacc", getSize(contentDirectory, myId),
                    null);
        } else {
            // Meta for a subfolder
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            String title = (file != null && file.getName() != null) ? file.getName() : path;

            // Determine parent ID - if this is a direct child of SAF root, parent is SAF_FOLDER
            // Otherwise, find the parent folder
            String parentId = ContentDirectoryIDs.SAF_FOLDER.getId();
            DocumentFile parent = file != null ? file.getParentFile() : null;
            if (parent != null && !getSelectedSafPathes().contains(parent.getUri().toString())) {
                String parentBase64 = Base64.encodeToString(parent.getUri().toString().getBytes(), Base64.NO_WRAP);
                parentId = ContentDirectoryIDs.SAF_PREFIX.getId() + parentBase64;
            }
            DIDLObject result = null;
            if (file.isDirectory()) {
                result = new StorageFolder(myId, parentId, title, "yaacc", getSize(contentDirectory, myId), null);
            } else {
                result = createItem(contentDirectory, file.getUri().toString(), file, myId, !file.canRead());
            }
            return result;
        }
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            return getSelectedSafPathes().size();
        } else {
            String shortId = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId);
            if (path != null) {
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                if (file != null && file.isDirectory()) {
                    return file.listFiles().length;
                }
            } else {
                YaaccLogger.w(getClass().getName(), "Short ID not found: " + shortId + " for id: " + myId);
            }
        }
        return 0;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        long browseStart = System.currentTimeMillis();
        YaaccLogger.d(getClass().getName(), "browseContainer START: myId=" + myId + ", firstResult=" + firstResult + ", maxResults=" + maxResults);
        List<Container> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            long rootStart = System.currentTimeMillis();
            YaaccLogger.d(getClass().getName(), "Browsing root SAF folder");
            Set<String> safPaths = getSelectedSafPathes();
            YaaccLogger.d(getClass().getName(), "Found " + safPaths.size() + " SAF paths in preferences (took " + (System.currentTimeMillis() - rootStart) + "ms)");
            List<String> sortedPathes = new ArrayList<>(safPaths);
            Collections.sort(sortedPathes);

            List<Container> allFolders = new ArrayList<>();
            for (int i = 0; i < sortedPathes.size(); i++) {
                long itemStart = System.currentTimeMillis();
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                YaaccLogger.d(getClass().getName(), "Path[" + i + "] DocumentFile: " + (file != null ? "exists" : "null") + ", isDirectory: " + (file != null && file.isDirectory()) + " (took " + (System.currentTimeMillis() - itemStart) + "ms)");
                if (file != null && file.isDirectory()) {
                    String title = file.getName() != null ? file.getName() : path;
                    String shortId = SAFCacheManager.getInstance(getContext()).getOrCreateShortId(file.getUri().toString());
                    String folderId = ContentDirectoryIDs.SAF_PREFIX.getId() + shortId;
                    StorageFolder folder = new StorageFolder(folderId, ContentDirectoryIDs.SAF_FOLDER.getId(), title, "yaacc", 0, null);
                    allFolders.add(folder);
                }
            }
            // allFolders is already built in today's default (path-alphabetical) order;
            // STABLE_ORDER preserves that exactly via stable sort when orderby is empty/null.
            allFolders.sort(SortSupport.toComparator(orderby, CONTAINER_ACCESSOR_MAP, STABLE_ORDER));

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(allFolders.size(), start + maxResults);
            YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + allFolders.size());
            result.addAll(allFolders.subList(start, end));
            YaaccLogger.d(getClass().getName(), "Root browse complete: " + result.size() + " folders (total " + (System.currentTimeMillis() - browseStart) + "ms)");
        } else {
            // Browse subfolder
            long subfolderStart = System.currentTimeMillis();
            YaaccLogger.d(getClass().getName(), "Browsing subfolder with ID: " + myId);
            String shortId = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId);

            if (path == null) {
                YaaccLogger.e(getClass().getName(), "Short ID not found: " + shortId);
                return result;
            }

            YaaccLogger.d(getClass().getName(), "Resolved path from shortId " + shortId + ": " + path);

            Uri uri = Uri.parse(path);
            DocumentFile root = null;

            // Check if this is a tree URI or document URI
            if (path.contains("/tree/")) {
                long treeStart = System.currentTimeMillis();
                root = DocumentFile.fromTreeUri(getContext(), uri);
                YaaccLogger.d(getClass().getName(), "Tree URI resolved in " + (System.currentTimeMillis() - treeStart) + "ms");
            } else {
                YaaccLogger.w(getClass().getName(), "Document URI detected, skipping: " + path);
                return result;
            }

            if (root != null && root.isDirectory()) {
                long listStart = System.currentTimeMillis();
                DocumentFile[] files = root.listFiles();
                YaaccLogger.d(getClass().getName(), "listFiles() took " + (System.currentTimeMillis() - listStart) + "ms, found " + files.length + " items");

                List<Container> allFolders = new ArrayList<>();
                for (int i = 0; i < files.length; i++) {
                    long itemStart = System.currentTimeMillis();
                    DocumentFile file = files[i];
                    if (file.isDirectory()) {
                        String title = file.getName() != null ? file.getName() : file.getUri().toString();
                        try {
                            String authority = file.getUri().getAuthority();
                            String documentId = DocumentsContract.getDocumentId(file.getUri());
                            Uri childTreeUri = DocumentsContract.buildTreeDocumentUri(authority, documentId);
                            DocumentFile testAccess = DocumentFile.fromTreeUri(getContext(), childTreeUri);
                            if (testAccess != null) {
                                String childShortId = SAFCacheManager.getInstance(getContext()).getOrCreateShortId(childTreeUri.toString());
                                String childId = ContentDirectoryIDs.SAF_PREFIX.getId() + childShortId;
                                if (!testAccess.canRead()) {
                                    title = "[X] " + title;
                                }
                                StorageFolder folder = new StorageFolder(childId, myId, title, "yaacc", 0, null);
                                folder.setRestricted(testAccess.canRead());
                                allFolders.add(folder);
                                YaaccLogger.d(getClass().getName(), "Child[" + i + "] " + title + " (took " + (System.currentTimeMillis() - itemStart) + "ms)");
                            } else {
                                YaaccLogger.w(getClass().getName(), "Cannot access child: " + title);
                            }
                        } catch (Exception e) {
                            YaaccLogger.e(getClass().getName(), "Error processing child: " + title, e);
                        }
                    }
                }
                // allFolders is in listFiles()'s original (unsorted) order, matching today's
                // behavior; STABLE_ORDER preserves it via stable sort when orderby is empty/null.
                allFolders.sort(SortSupport.toComparator(orderby, CONTAINER_ACCESSOR_MAP, STABLE_ORDER));

                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(allFolders.size(), start + maxResults);
                YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + allFolders.size());
                result.addAll(allFolders.subList(start, end));
            } else {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory");
            }
            YaaccLogger.d(getClass().getName(), "Subfolder browse complete: " + result.size() + " folders (total " + (System.currentTimeMillis() - subfolderStart) + "ms)");
        }
        YaaccLogger.d(getClass().getName(), "browseContainer END: returning " + result.size() + " containers (total " + (System.currentTimeMillis() - browseStart) + "ms)");
        return result;
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        long browseStart = System.currentTimeMillis();
        YaaccLogger.d(getClass().getName(), "browseItem START: myId=" + myId + ", firstResult=" + firstResult + ", maxResults=" + maxResults);
        List<Item> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            long rootStart = System.currentTimeMillis();
            List<String> sortedPathes = new ArrayList<>(getSelectedSafPathes());
            Collections.sort(sortedPathes);

            // Lightweight pass: only resolve each DocumentFile and read its cheap
            // name/lastModified fields - no SAF metadata cache lookup, MIME sniffing,
            // or URI/ProtocolInfo building (createItem's expensive pipeline) yet.
            List<SafFileEntry> candidates = new ArrayList<>();
            for (int i = 0; i < sortedPathes.size(); i++) {
                long itemStart = System.currentTimeMillis();
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromSingleUri(getContext(), Uri.parse(path));
                if (file != null && !file.isDirectory() && !isPlaylist(file)) {
                    candidates.add(new SafFileEntry(path, file));
                }
                YaaccLogger.d(getClass().getName(), "Path[" + i + "] resolved (took " + (System.currentTimeMillis() - itemStart) + "ms)");
            }
            // candidates is already built in today's default (path-alphabetical) order;
            // STABLE_ENTRY_ORDER preserves that exactly via stable sort when orderby is empty/null.
            candidates.sort(buildAccessorComparator(orderby, ENTRY_ACCESSOR_MAP, STABLE_ENTRY_ORDER));

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(candidates.size(), start + maxResults);
            YaaccLogger.d(getClass().getName(), "Root items: pagination start=" + start + ", end=" + end + ", total=" + candidates.size());

            // Expensive pass: createItem(...) only for the page slice actually returned.
            List<Item> allItems = new ArrayList<>();
            for (int i = start; i < end; i++) {
                SafFileEntry entry = candidates.get(i);
                long createStart = System.currentTimeMillis();
                Item item = createItem(contentDirectory, entry.uri, entry.file, myId, !entry.file.canRead());
                if (item != null) {
                    allItems.add(item);
                    YaaccLogger.d(getClass().getName(), "✓ Added to result: Item[" + (allItems.size() - 1) + "] " + (entry.file.getName() != null ? entry.file.getName() : "unknown") + " (createItem took " + (System.currentTimeMillis() - createStart) + "ms)");
                } else {
                    YaaccLogger.d(getClass().getName(), "✗ Skipped (null item): " + (entry.file.getName() != null ? entry.file.getName() : "unknown"));
                }
            }
            result.addAll(allItems);
            YaaccLogger.d(getClass().getName(), "Root items complete: " + result.size() + " items (total " + (System.currentTimeMillis() - rootStart) + "ms)");
        } else {
            // Browse subfolder items
            long subfolderStart = System.currentTimeMillis();
            YaaccLogger.d(getClass().getName(), "Browsing subfolder items for: " + myId);
            String shortId = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId);

            if (path == null) {
                YaaccLogger.e(getClass().getName(), "Short ID not found: " + shortId);
                return result;
            }

            long treeStart = System.currentTimeMillis();
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            YaaccLogger.d(getClass().getName(), "Tree URI resolved in " + (System.currentTimeMillis() - treeStart) + "ms");

            if (root != null && root.isDirectory()) {
                if (root.canRead()) {
                    long listStart = System.currentTimeMillis();
                    DocumentFile[] files = root.listFiles();
                    YaaccLogger.d(getClass().getName(), "listFiles() took " + (System.currentTimeMillis() - listStart) + "ms, found " + files.length + " items");

                    List<Item> allItems = new ArrayList<>();
                    for (int i = 0; i < files.length; i++) {
                        long itemStart = System.currentTimeMillis();
                        DocumentFile file = files[i];
                        if (!file.isDirectory()) {
                            long createStart = System.currentTimeMillis();
                            Item item = createItem(contentDirectory, file.getUri().toString(), file, myId, !file.canRead());
                            long createTime = System.currentTimeMillis() - createStart;
                            if (item != null) allItems.add(item);
                            long totalTime = System.currentTimeMillis() - itemStart;
                            YaaccLogger.d(getClass().getName(), "Item[" + i + "] " + (file.getName() != null ? file.getName() : "unknown") + " - createItem=" + createTime + "ms, total=" + totalTime + "ms");
                        }
                    }
                    // allItems is in listFiles()'s original (unsorted) order, matching today's
                    // behavior; STABLE_ORDER preserves it via stable sort when orderby is empty/null.
                    allItems.sort(SortSupport.toComparator(orderby, ITEM_ACCESSOR_MAP, STABLE_ORDER));

                    int start = (int) Math.max(0, firstResult);
                    int end = (int) Math.min(allItems.size(), start + maxResults);
                    YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + allItems.size());
                    result.addAll(allItems.subList(start, end));
                } else {
                    YaaccLogger.w(getClass().getName(), "Cannot read folder: " + path);
                }
            } else {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory");
            }
            YaaccLogger.d(getClass().getName(), "Subfolder items complete: " + result.size() + " items (total " + (System.currentTimeMillis() - subfolderStart) + "ms)");
        }
        YaaccLogger.d(getClass().getName(), "browseItem END: returning " + result.size() + " items (total " + (System.currentTimeMillis() - browseStart) + "ms)");
        return result;
    }

    private Item createItem(YaaccContentDirectory contentDirectory, String path, DocumentFile file, String parentId, boolean restricted) {
        long createStart = System.currentTimeMillis();
        String fileName = file.getName() != null ? file.getName() : "unknown";

        if (file.getName() != null && file.getName().endsWith("m3u")) {
            return null;
        }

        // Get all metadata from cache (duration, MIME type, short ID)
        SAFMetadata metadata = SAFCacheManager.getInstance(getContext()).getMetadata(file);
        if (metadata == null) {
            return null;
        }
        
        // If MIME type is null or invalid, try to guess from filename
        String mimeTypeStr = metadata.mimeType;
        if (mimeTypeStr == null || mimeTypeStr.equals("null") || !mimeTypeStr.contains("/")) {
            mimeTypeStr = SAFCacheManager.getInstance(getContext()).guessMimeTypeFromExtension(file.getName());
            if (mimeTypeStr == null) {
                return null;  // Still couldn't determine MIME type
            }
        }

        MimeType mimeType = MimeType.valueOf(mimeTypeStr);
        String mimeTypeMain = mimeType.getType();

        String id = ContentDirectoryIDs.SAF_PREFIX.getId() + metadata.shortId;
        String title = file.getName() != null ? file.getName() : extractFilenameFromUri(path);
        if (file.getName() == null) {
            YaaccLogger.d(getClass().getName(), "file.getName() is null for URI: " + file.getUri());
        }

        if (restricted) {
            title = "[X] " + title;
        }

        // Use shortId in URI instead of Base64-encoded path
        // Include filename to help renderers display proper title
        String filenameForUri = file.getName() != null ? file.getName() : "file";
        // Remove extension and special characters for URI path
        String safeFilename = filenameForUri.replaceAll("[^a-zA-Z0-9._-]", "_");
        String uriContentId = metadata.shortId + "_" + safeFilename;
        String uri = getUriString(contentDirectory, id, mimeType, uriContentId);
        YaaccLogger.d(getClass().getName(), "Generated URI for " + title + ": " + uri + " (shortId=" + metadata.shortId + ")");

        long protocolStart = System.currentTimeMillis();
        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);
        long protocolTime = System.currentTimeMillis() - protocolStart;

        String duration = null;
        if (mimeTypeMain.equals("audio") && !restricted) {
            duration = metadata.duration;
        }

        // Create lightweight YaaccRes (no Cling overhead)
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, metadata.fileSize, duration, null, uri);

        // Create lightweight YaaccItem (no Cling Property overhead)
        long itemStart = System.currentTimeMillis();
        String clazz = mimeTypeMain.equals("audio") ? "object.item.audioItem"
                : mimeTypeMain.equals("video") ? "object.item.videoItem"
                : "object.item.imageItem";
        YaaccItem yaaccItem = new YaaccItem(id, parentId, title, "yaacc", restricted, clazz);
        yaaccItem.addResource(yaaccRes);
        long itemTime = System.currentTimeMillis() - itemStart;

        // Convert to Cling Item only at the end (for UPnP serialization)
        long convertStart = System.currentTimeMillis();
        Item item = yaaccItem.toClingItem();
        long convertTime = System.currentTimeMillis() - convertStart;

        // Attach dc:date from the file's lastModified() so SortSupport can honor
        // SortCriteria="dc:date" against SAF items (0 means unknown - leave unset).
        long lastModified = file.lastModified();
        if (lastModified > 0) {
            item.addProperty(new DIDLObject.Property.DC.DATE(SortSupport.formatEpochMillisAsDate(lastModified)));
        }

        long totalTime = System.currentTimeMillis() - createStart;
        YaaccLogger.d(getClass().getName(), "Item[?] " + fileName + " - protocolInfo=" + protocolTime + "ms, YaaccItem=" + itemTime + "ms, convert=" + convertTime + "ms, total=" + totalTime + "ms");
        return item;
    }

    private String extractFilenameFromUri(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /*
        private void loadDurationAsync(DocumentFile file, Item item, Res res) {

            // Use AsyncTask for proper Android background processing
            new android.os.AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... voids) {
                    return extractDuration(file);
                }

                @Override
                protected void onPostExecute(String duration) {
                    if (duration != null) {
                        // Update the resource with duration
                        try {
                            // Create new resource with duration
                            Res newRes = new Res(res.getProtocolInfo(), res.getSize(), duration, res.getBitrate(), res.getValue());
                            // Replace the resource in the item
                            item.getResources().clear();
                            item.addResource(newRes);
                            YaaccLogger.d(getClass().getName(), "Updated duration for: " + item.getTitle() + " -> " + duration);
                        } catch (Exception e) {
                            YaaccLogger.w(getClass().getName(), "Failed to update duration for: " + item.getTitle(), e);
                        }
                    }
                    YaaccLogger.d(getClass().getName(), "Item ready for playback: " + item.getTitle());
                }
            }.execute();
        }
    */
}
