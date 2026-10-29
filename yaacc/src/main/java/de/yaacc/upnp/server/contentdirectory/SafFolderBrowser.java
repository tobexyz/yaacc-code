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
 * <p>
 * A folder is listed with ONE provider query ({@link SafDirectoryLister}) and the listing is shared
 * by the passes of a Browse and by the following chunk requests; only the page slice that is
 * returned gets the expensive per-item work. See docs/tech.md, "Large SAF folders".
 *
 * @author tobexyz
 */
public class SafFolderBrowser extends ContentBrowser {

    /** Containers (directories) are title-only - no meaningful single date. */
    private static final Map<String, Function<DIDLObject, String>> CONTAINER_ACCESSOR_MAP =
            Map.of(SortSupport.PROPERTY_TITLE, DIDLObject::getTitle);

    /**
     * No-op comparator used as the {@code defaultComparator} for {@link SortSupport#toComparator}.
     * {@link List#sort} is a stable sort, so applying this leaves a list already built in
     * today's default order unchanged when no {@code SortCriteria} was requested.
     */
    private static final Comparator<DIDLObject> STABLE_ORDER = (a, b) -> 0;

    /**
     * A selected SAF file of the SAF root folder (the few user chosen paths): the {@link DocumentFile}
     * for the per-item work of the page slice, the entry for the cheap sort/filter fields.
     */
    private static final class RootFile {
        final String uri;
        final DocumentFile file;
        final SafDirectoryEntry entry;

        RootFile(String uri, DocumentFile file, SafDirectoryEntry entry) {
            this.uri = uri;
            this.file = file;
            this.entry = entry;
        }
    }

    /** A sub folder of a listing with the values its container needs. */
    private static final class FolderCandidate {
        final String title;
        final Uri childUri;
        final boolean canRead;

        FolderCandidate(String title, Uri childUri, boolean canRead) {
            this.title = title;
            this.childUri = childUri;
            this.canRead = canRead;
        }
    }

    // Timing of this browser's listing and page work, for the one summary line per Browse (see
    // browseChildren). A browser serves one Browse on one thread, so plain fields are enough.
    private int listedEntries = -1;
    private boolean listedFromProvider;
    private long listMillis;
    private long pageMillis;
    private int pageStart;
    private int pageEnd;

    public SafFolderBrowser(Context context) {
        super(context);
    }

    /** Matches {@link #createItem}'s own playlist-skip check on the root folder's files. */
    private static boolean isRootPlaylist(RootFile file) {
        return SafListingSupport.isPlaylist(file.entry.name);
    }

    /** The values of a {@link DocumentFile} the item building needs (one provider query each). */
    private static SafDirectoryEntry entryOf(DocumentFile file) {
        return new SafDirectoryEntry(file.getName(), null, file.getType(), file.lastModified(), file.length(), file.isDirectory());
    }

    /**
     * Logs one line per Browse of a SAF folder: how many entries the folder has, whether they came
     * from the cache or the provider and how long that took, and the page that was built.
     */
    @Override
    public List<DIDLObject> browseChildren(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<DIDLObject> result = super.browseChildren(contentDirectory, myId, firstResult, maxResults, orderby);
        if (listedEntries >= 0) {
            YaaccLogger.d(getClass().getName(), "SAF browse " + myId + " list=" + listedEntries + " entries from "
                    + (listedFromProvider ? "provider" : "cache") + " in " + listMillis + " ms, page "
                    + pageStart + "-" + pageEnd + " in " + pageMillis + " ms");
        }
        return result;
    }

    /**
     * @return the listing of the folder from the cache or the provider; never null. A listing that
     * could not be had (waiting for it timed out, the thread was interrupted) is an empty folder.
     */
    private SafDirectory listing(String path) {
        long start = System.currentTimeMillis();
        try {
            SafListingCache.Result<SafDirectory> result = SafDirectoryLister.list(getContext(), path);
            listMillis += System.currentTimeMillis() - start;
            listedEntries = result.value.entries.size();
            listedFromProvider |= !result.fromCache;
            return result.value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            YaaccLogger.d(getClass().getName(), "Listing of " + path + " interrupted");
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Listing of " + path + " failed: " + e);
        }
        return SafDirectory.listingFailed(null, false);
    }

    /** Remembers the page of a pass; the larger slice of the passes of one Browse is the page that is logged. */
    private void notePageRange(int total, long firstResult, long maxResults) {
        int start = SafListingSupport.pageStart(total, firstResult);
        int end = SafListingSupport.pageEnd(total, firstResult, maxResults);
        if (end - start >= pageEnd - pageStart) {
            pageStart = start;
            pageEnd = end;
        }
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
                result = createItem(contentDirectory, file.getUri().toString(), entryOf(file), myId, !file.canRead());
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
                SafDirectory directory = listing(path);
                if (directory.directory) {
                    return directory.entries.size();
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
        YaaccLogger.v(getClass().getName(), "browseContainer START: myId=" + myId + ", firstResult=" + firstResult + ", maxResults=" + maxResults);
        List<Container> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            Set<String> safPaths = getSelectedSafPathes();
            List<String> sortedPathes = new ArrayList<>(safPaths);
            Collections.sort(sortedPathes);

            List<Container> allFolders = new ArrayList<>();
            for (int i = 0; i < sortedPathes.size(); i++) {
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
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

            YaaccLogger.v(getClass().getName(), "Pagination: first=" + firstResult + ", max=" + maxResults + ", total=" + allFolders.size());
            result.addAll(SafListingSupport.page(allFolders, firstResult, maxResults));
        } else {
            // Browse subfolder
            String shortId = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId);

            if (path == null) {
                YaaccLogger.e(getClass().getName(), "Short ID not found: " + shortId);
                return result;
            }

            // Check if this is a tree URI or document URI
            if (!path.contains("/tree/")) {
                YaaccLogger.w(getClass().getName(), "Document URI detected, skipping: " + path);
                return result;
            }

            SafDirectory directory = listing(path);
            if (directory.directory) {
                long pageStartMillis = System.currentTimeMillis();
                result.addAll(childFolders(directory, myId, firstResult, maxResults, orderby));
                pageMillis += System.currentTimeMillis() - pageStartMillis;
            } else {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory");
            }
        }
        YaaccLogger.v(getClass().getName(), "browseContainer END: returning " + result.size() + " containers (total " + (System.currentTimeMillis() - browseStart) + "ms)");
        return result;
    }

    /**
     * The sub folders of a listing as containers: the requested page of them.
     * <p>
     * The restricted flag and the "[X] " title prefix come from {@code canRead()} of the folder's own
     * tree document, which is the read permission on its uri plus a mime type from the provider - the
     * listing has the latter, so no provider query is needed. The prefix is part of the title the
     * containers are sorted by, so for a title sort every folder needs it before the page is cut; without
     * a title sort only the page's folders are resolved.
     */
    private List<Container> childFolders(SafDirectory directory, String parentId, long firstResult, long maxResults, SortCriterion[] orderby) {
        Uri folderUri = Uri.parse(directory.rootUri);
        List<SafDirectoryEntry> folders = SafListingSupport.directories(directory.entries);
        List<FolderCandidate> pageCandidates = new ArrayList<>();
        if (SafListingSupport.sortsBy(orderby, SortSupport.PROPERTY_TITLE)) {
            List<FolderCandidate> all = new ArrayList<>();
            for (SafDirectoryEntry entry : folders) {
                FolderCandidate candidate = folderCandidate(folderUri, entry);
                if (candidate != null) {
                    all.add(candidate);
                }
            }
            pageCandidates = SafListingSupport.page(
                    SafListingSupport.sort(all, orderby, c -> c.title, null), firstResult, maxResults);
            notePageRange(all.size(), firstResult, maxResults);
        } else {
            for (SafDirectoryEntry entry : SafListingSupport.page(folders, firstResult, maxResults)) {
                FolderCandidate candidate = folderCandidate(folderUri, entry);
                if (candidate != null) {
                    pageCandidates.add(candidate);
                }
            }
            notePageRange(folders.size(), firstResult, maxResults);
        }
        List<Container> containers = new ArrayList<>();
        for (FolderCandidate candidate : pageCandidates) {
            String childShortId = SAFCacheManager.getInstance(getContext()).getOrCreateShortId(candidate.childUri.toString());
            String childId = ContentDirectoryIDs.SAF_PREFIX.getId() + childShortId;
            StorageFolder folder = new StorageFolder(childId, parentId, candidate.title, "yaacc", 0, null);
            folder.setRestricted(candidate.canRead);
            containers.add(folder);
        }
        return containers;
    }

    private FolderCandidate folderCandidate(Uri folderUri, SafDirectoryEntry entry) {
        String title = entry.name;
        try {
            if (title == null) {
                title = SafDirectoryLister.childDocumentUri(folderUri, entry.documentId).toString();
            }
            // The child is addressed through the tree the user granted (.../tree/<granted>/document/<child>), not
            // as a tree of its own (.../tree/<child>): the grant of a picked folder covers everything below it, but
            // only for uris of that tree. A uri rooted at the child would never match the grant, and every sub
            // folder of a picked folder would show "[X]". DocumentFile.fromTreeUri accepts such a document uri.
            Uri childUri = SafDirectoryLister.childDocumentUri(folderUri, entry.documentId);
            boolean canRead = SafDirectoryLister.canRead(getContext(), childUri, entry.mimeType);
            return new FolderCandidate(canRead ? title : "[X] " + title, childUri, canRead);
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Error processing child: " + title, e);
            return null;
        }
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        long browseStart = System.currentTimeMillis();
        YaaccLogger.v(getClass().getName(), "browseItem START: myId=" + myId + ", firstResult=" + firstResult + ", maxResults=" + maxResults);
        List<Item> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            List<String> sortedPathes = new ArrayList<>(getSelectedSafPathes());
            Collections.sort(sortedPathes);

            // Lightweight pass: only the cheap name/lastModified fields are read for sorting -
            // the expensive createItem pipeline (SAF metadata cache lookup, MIME sniffing,
            // URI/ProtocolInfo building) runs for the page slice below only.
            List<RootFile> candidates = new ArrayList<>();
            for (String path : sortedPathes) {
                DocumentFile file = DocumentFile.fromSingleUri(getContext(), Uri.parse(path));
                if (file != null) {
                    SafDirectoryEntry entry = entryOf(file);
                    RootFile rootFile = new RootFile(path, file, entry);
                    if (!entry.isDirectory() && !isRootPlaylist(rootFile)) {
                        candidates.add(rootFile);
                    }
                }
            }
            // candidates is already built in today's default (path-alphabetical) order;
            // the stable sort keeps it when orderby is empty/null.
            List<RootFile> page = SafListingSupport.page(SafListingSupport.sort(candidates, orderby,
                    c -> c.entry.name, c -> SafListingSupport.dateKey(c.entry.lastModified)), firstResult, maxResults);
            YaaccLogger.v(getClass().getName(), "Root items: first=" + firstResult + ", max=" + maxResults + ", total=" + candidates.size());

            // Expensive pass: createItem(...) only for the page slice actually returned.
            for (RootFile rootFile : page) {
                Item item = createItem(contentDirectory, rootFile.uri, rootFile.entry, myId, !rootFile.file.canRead());
                if (item != null) {
                    result.add(item);
                }
            }
        } else {
            // Browse subfolder items
            String shortId = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = SAFCacheManager.getInstance(getContext()).getUriForShortId(shortId);

            if (path == null) {
                YaaccLogger.e(getClass().getName(), "Short ID not found: " + shortId);
                return result;
            }

            SafDirectory directory = listing(path);
            if (!directory.directory) {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory");
            } else if (!directory.readable) {
                YaaccLogger.w(getClass().getName(), "Cannot read folder: " + path);
            } else {
                long pageStartMillis = System.currentTimeMillis();
                // Cheap pass on the listing: filter and sort by name/lastModified. The expensive
                // createItem pipeline is deferred until after sorting and only runs for the page.
                // The candidates are in the provider's order, matching today's behavior; the stable
                // sort keeps it when orderby is empty/null.
                List<SafDirectoryEntry> candidates = SafListingSupport.itemCandidates(directory.entries);
                List<SafDirectoryEntry> page = SafListingSupport.page(SafListingSupport.sort(candidates, orderby,
                        e -> e.name, e -> SafListingSupport.dateKey(e.lastModified)), firstResult, maxResults);
                Uri folderUri = Uri.parse(directory.rootUri);
                for (SafDirectoryEntry entry : page) {
                    Uri childUri = SafDirectoryLister.childDocumentUri(folderUri, entry.documentId);
                    boolean restricted = !SafDirectoryLister.canRead(getContext(), childUri, entry.mimeType);
                    Item item = createItem(contentDirectory, childUri.toString(), entry, myId, restricted);
                    if (item != null) {
                        result.add(item);
                    }
                }
                notePageRange(candidates.size(), firstResult, maxResults);
                pageMillis += System.currentTimeMillis() - pageStartMillis;
            }
        }
        YaaccLogger.v(getClass().getName(), "browseItem END: returning " + result.size() + " items (total " + (System.currentTimeMillis() - browseStart) + "ms)");
        return result;
    }

    private Item createItem(YaaccContentDirectory contentDirectory, String path, SafDirectoryEntry file, String parentId, boolean restricted) {
        String fileName = file.name != null ? file.name : "unknown";

        if (SafListingSupport.isPlaylist(file.name)) {
            return null;
        }

        // Get all metadata from cache (duration, MIME type, short ID)
        SAFMetadata metadata = SAFCacheManager.getInstance(getContext()).getMetadata(Uri.parse(path), file.name, file.mimeType, file.size);
        if (metadata == null) {
            return null;
        }

        // If MIME type is null or invalid, try to guess from filename
        String mimeTypeStr = metadata.mimeType;
        if (mimeTypeStr == null || mimeTypeStr.equals("null") || !mimeTypeStr.contains("/")) {
            mimeTypeStr = SAFCacheManager.getInstance(getContext()).guessMimeTypeFromExtension(file.name);
            if (mimeTypeStr == null) {
                return null;  // Still couldn't determine MIME type
            }
        }

        MimeType mimeType = MimeType.valueOf(mimeTypeStr);
        String mimeTypeMain = mimeType.getType();

        String id = ContentDirectoryIDs.SAF_PREFIX.getId() + metadata.shortId;
        String title = file.name != null ? file.name : extractFilenameFromUri(path);
        if (file.name == null) {
            YaaccLogger.v(getClass().getName(), "file name is null for URI: " + path);
        }

        if (restricted) {
            title = "[X] " + title;
        }

        // Use shortId in URI instead of Base64-encoded path
        // Include filename to help renderers display proper title
        String filenameForUri = file.name != null ? file.name : "file";
        // Remove extension and special characters for URI path
        String safeFilename = filenameForUri.replaceAll("[^a-zA-Z0-9._-]", "_");
        String uriContentId = metadata.shortId + "_" + safeFilename;
        String uri = getUriString(contentDirectory, id, mimeType, uriContentId);
        YaaccLogger.v(getClass().getName(), "Generated URI for " + title + ": " + uri + " (shortId=" + metadata.shortId + ")");

        ProtocolInfo protocolInfo = getProtocolInfo(mimeType);

        String duration = null;
        if (mimeTypeMain.equals("audio") && !restricted) {
            duration = metadata.duration;
        }

        // Create lightweight YaaccRes (no Cling overhead)
        YaaccRes yaaccRes = new YaaccRes(protocolInfo, metadata.fileSize, duration, null, uri);

        // Create lightweight YaaccItem (no Cling Property overhead)
        String clazz = mimeTypeMain.equals("audio") ? "object.item.audioItem"
                : mimeTypeMain.equals("video") ? "object.item.videoItem"
                : "object.item.imageItem";
        YaaccItem yaaccItem = new YaaccItem(id, parentId, title, "yaacc", restricted, clazz);
        yaaccItem.addResource(yaaccRes);

        // Convert to Cling Item only at the end (for UPnP serialization)
        Item item = yaaccItem.toClingItem();

        // Attach dc:date from the file's lastModified so SortSupport can honor
        // SortCriteria="dc:date" against SAF items (0 means unknown - leave unset).
        if (file.lastModified > 0) {
            item.addProperty(new DIDLObject.Property.DC.DATE(SortSupport.formatEpochMillisAsDate(file.lastModified)));
        }

        YaaccLogger.v(getClass().getName(), "Item " + fileName + " created");
        return item;
    }

    private String extractFilenameFromUri(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
