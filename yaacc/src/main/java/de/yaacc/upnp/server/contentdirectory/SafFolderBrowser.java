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
import java.util.List;
import java.util.Set;

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

    public SafFolderBrowser(Context context) {
        super(context);
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

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);
            YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + sortedPathes.size());

            for (int i = start; i < end; i++) {
                long itemStart = System.currentTimeMillis();
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                YaaccLogger.d(getClass().getName(), "Path[" + i + "] DocumentFile: " + (file != null ? "exists" : "null") + ", isDirectory: " + (file != null && file.isDirectory()) + " (took " + (System.currentTimeMillis() - itemStart) + "ms)");
                if (file != null && file.isDirectory()) {
                    String title = file.getName() != null ? file.getName() : path;
                    String shortId = SAFCacheManager.getInstance(getContext()).getOrCreateShortId(file.getUri().toString());
                    String folderId = ContentDirectoryIDs.SAF_PREFIX.getId() + shortId;
                    StorageFolder folder = new StorageFolder(folderId, ContentDirectoryIDs.SAF_FOLDER.getId(), title, "yaacc", 0, null);
                    result.add(folder);
                }
            }
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

                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(files.length, start + maxResults);
                YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + files.length);

                for (int i = start; i < end; i++) {
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
                                result.add(folder);
                                YaaccLogger.d(getClass().getName(), "Child[" + i + "] " + title + " (took " + (System.currentTimeMillis() - itemStart) + "ms)");
                            } else {
                                YaaccLogger.w(getClass().getName(), "Cannot access child: " + title);
                            }
                        } catch (Exception e) {
                            YaaccLogger.e(getClass().getName(), "Error processing child: " + title, e);
                        }
                    }
                }
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

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);
            YaaccLogger.d(getClass().getName(), "Root items: pagination start=" + start + ", end=" + end + ", total=" + sortedPathes.size());

            for (int i = start; i < end; i++) {
                long itemStart = System.currentTimeMillis();
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromSingleUri(getContext(), Uri.parse(path));
                if (file != null && !file.isDirectory()) {
                    Item item = createItem(contentDirectory, path, file, myId, !file.canRead());
                    if (item != null) {
                        result.add(item);
                        YaaccLogger.d(getClass().getName(), "✓ Added to result: Item[" + (result.size() - 1) + "] " + (file.getName() != null ? file.getName() : "unknown"));
                    } else {
                        YaaccLogger.d(getClass().getName(), "✗ Skipped (null item): " + (file.getName() != null ? file.getName() : "unknown"));
                    }
                    YaaccLogger.d(getClass().getName(), "Item[" + i + "] " + (file.getName() != null ? file.getName() : "unknown") + " (took " + (System.currentTimeMillis() - itemStart) + "ms)");
                }
            }
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

                    int start = (int) Math.max(0, firstResult);
                    int end = (int) Math.min(files.length, start + maxResults);
                    YaaccLogger.d(getClass().getName(), "Pagination: start=" + start + ", end=" + end + ", total=" + files.length);

                    for (int i = start; i < end; i++) {
                        long itemStart = System.currentTimeMillis();
                        DocumentFile file = files[i];
                        if (!file.isDirectory()) {
                            long createStart = System.currentTimeMillis();
                            Item item = createItem(contentDirectory, file.getUri().toString(), file, myId, !file.canRead());
                            long createTime = System.currentTimeMillis() - createStart;
                            if (item != null) result.add(item);
                            long totalTime = System.currentTimeMillis() - itemStart;
                            YaaccLogger.d(getClass().getName(), "Item[" + i + "] " + (file.getName() != null ? file.getName() : "unknown") + " - createItem=" + createTime + "ms, total=" + totalTime + "ms");
                        }
                    }
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
        String uri = getUriString(contentDirectory, id, mimeType, metadata.shortId);
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
