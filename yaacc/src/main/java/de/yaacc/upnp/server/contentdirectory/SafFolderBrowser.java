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
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.util.FormatHelper;
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
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            try {
                String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                if (file != null && file.isDirectory()) {
                    return file.listFiles().length;
                }
            } catch (IllegalArgumentException e) {
                YaaccLogger.w(getClass().getName(), "Can not decode path from id: " + myId + " returning size 0", e);
                return 0;
            }
        }
        return 0;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        YaaccLogger.d(getClass().getName(), "browseContainer called with myId: " + myId);
        List<Container> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            YaaccLogger.d(getClass().getName(), "Browsing root SAF folder");
            Set<String> safPaths = getSelectedSafPathes();
            YaaccLogger.d(getClass().getName(), "Found " + safPaths.size() + " SAF paths in preferences");
            List<String> sortedPathes = new ArrayList<>(safPaths);
            Collections.sort(sortedPathes);

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);

            for (int i = start; i < end; i++) {
                String path = sortedPathes.get(i);
                YaaccLogger.d(getClass().getName(), "Processing path " + i + ": " + path);
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                YaaccLogger.d(getClass().getName(), "DocumentFile: " + (file != null ? "exists" : "null") + ", isDirectory: " + (file != null && file.isDirectory()));
                if (file != null && file.isDirectory()) {
                    String title = file.getName() != null ? file.getName() : path;
                    String base64Str = Base64.encodeToString(file.getUri().toString().getBytes(), Base64.NO_WRAP);
                    String folderId = ContentDirectoryIDs.SAF_PREFIX.getId() + base64Str;
                    YaaccLogger.d(getClass().getName(), "Creating root folder: " + title + " with ID: " + folderId);
                    StorageFolder folder = new StorageFolder(folderId, ContentDirectoryIDs.SAF_FOLDER.getId(), title, "yaacc", 0, null);
                    result.add(folder);
                }
            }
            YaaccLogger.d(getClass().getName(), "Returning " + result.size() + " SAF root folders");
        } else {
            // Browse subfolder
            YaaccLogger.d(getClass().getName(), "Browsing subfolder with ID: " + myId);
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            YaaccLogger.d(getClass().getName(), "Encoded path: " + pathEnc);
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            YaaccLogger.d(getClass().getName(), "Decoded path: " + path);

            Uri uri = Uri.parse(path);
            DocumentFile root = null;

            // Check if this is a tree URI or document URI
            if (path.contains("/tree/")) {
                // This is a tree URI, use it directly
                root = DocumentFile.fromTreeUri(getContext(), uri);
                YaaccLogger.d(getClass().getName(), "Using tree URI: " + path);
            } else {
                // This is a document URI, we need to find it within its parent tree
                YaaccLogger.d(getClass().getName(), "Document URI detected, finding parent tree: " + path);
                // For now, skip these problematic folders to avoid showing parent content
                YaaccLogger.w(getClass().getName(), "Skipping document URI folder to avoid parent content");
                return result;
            }

            if (root != null && root.isDirectory()) {
                DocumentFile[] files = root.listFiles();
                YaaccLogger.d(getClass().getName(), "Found " + files.length + " files in subfolder");
                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(files.length, start + maxResults);
                YaaccLogger.d(getClass().getName(), "Parent: " + myId);
                for (int i = start; i < end; i++) {
                    DocumentFile file = files[i];
                    if (file.isDirectory()) {
                        YaaccLogger.d(getClass().getName(), "Child: " + file.getUri());
                        String title = file.getName() != null ? file.getName() : file.getUri().toString();

                        // Create tree URI for the child folder so it can be browsed properly
                        try {
                            String authority = file.getUri().getAuthority();
                            String documentId = DocumentsContract.getDocumentId(file.getUri());
                            Uri childTreeUri = DocumentsContract.buildTreeDocumentUri(authority, documentId);
                            YaaccLogger.d(getClass().getName(), "Child tree URI: " + childTreeUri);

                            // Test if we can access this tree URI
                            DocumentFile testAccess = DocumentFile.fromTreeUri(getContext(), childTreeUri);
                            if (testAccess != null) {
                                String base64Str = Base64.encodeToString(childTreeUri.toString().getBytes(), Base64.NO_WRAP);
                                String childId = ContentDirectoryIDs.SAF_PREFIX.getId() + base64Str;
                                YaaccLogger.d(getClass().getName(), "Creating child folder: " + title + " with ID: " + childId);
                                if (!testAccess.canRead()) {

                                    title = "[X] " + title; //🔒
                                }
                                StorageFolder folder = new StorageFolder(childId, myId, title, "yaacc", 0, null);
                                folder.setRestricted(testAccess.canRead());
                                result.add(folder);
                            } else {
                                YaaccLogger.w(getClass().getName(), "Cannot access child tree URI, skipping folder: " + title);
                            }
                        } catch (Exception e) {
                            YaaccLogger.e(getClass().getName(), "Error creating tree URI for child, skipping folder: " + title, e);
                        }
                    }
                }
            } else {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory for path: " + path);
            }
        }
        YaaccLogger.d(getClass().getName(), "Returning " + result.size() + " containers");
        return result;
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        YaaccLogger.d(getClass().getName(), "browseItem called with myId: " + myId);
        List<Item> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            List<String> sortedPathes = new ArrayList<>(getSelectedSafPathes());
            Collections.sort(sortedPathes);

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);

            for (int i = start; i < end; i++) {
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromSingleUri(getContext(), Uri.parse(path));
                if (file != null && !file.isDirectory()) {
                    result.add(createItem(contentDirectory, path, file, myId, !file.canRead()));
                }
            }
        } else {
            // Browse subfolder items
            YaaccLogger.d(getClass().getName(), "Browsing subfolder items for: " + myId);
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            YaaccLogger.d(getClass().getName(), "Decoded path: " + path);
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            if (root != null && root.isDirectory()) {
                if (root.canRead()) {
                    DocumentFile[] files = root.listFiles();
                    YaaccLogger.d(getClass().getName(), "Found " + files.length + " files in folder");
                    int start = (int) Math.max(0, firstResult);
                    int end = (int) Math.min(files.length, start + maxResults);
                    for (int i = start; i < end; i++) {
                        DocumentFile file = files[i];
                        if (!file.isDirectory()) {
                            result.add(createItem(contentDirectory, file.getUri().toString(), file, myId, !file.canRead()));
                        }
                    }
                } else {
                    YaaccLogger.w(getClass().getName(), "Cannot access folder, skipping: " + path);
                }
            } else {
                YaaccLogger.e(getClass().getName(), "Root DocumentFile is null or not a directory for path: " + path);
            }
        }
        YaaccLogger.d(getClass().getName(), "Returning " + result.size() + " items");
        return result;
    }

    private Item createItem(YaaccContentDirectory contentDirectory, String path, DocumentFile file, String parentId, boolean restricted) {
        String mimeTypeStr = file.getType();

        // If MIME type is null, try to guess from file extension
        if (mimeTypeStr == null && file.getName() != null) {
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(".mp3")) mimeTypeStr = "audio/mpeg";
            else if (fileName.endsWith(".mp4")) mimeTypeStr = "video/mp4";
            else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"))
                mimeTypeStr = "image/jpeg";
            else if (fileName.endsWith(".png")) mimeTypeStr = "image/png";
            else if (fileName.endsWith(".flac")) mimeTypeStr = "audio/flac";
            else if (fileName.endsWith(".m4a")) mimeTypeStr = "audio/mp4";
            else if (fileName.endsWith(".ogg")) mimeTypeStr = "audio/ogg";
            else if (fileName.endsWith(".mkv")) mimeTypeStr = "video/x-matroska";
            else if (fileName.endsWith(".avi")) mimeTypeStr = "video/x-msvideo";
            YaaccLogger.d(getClass().getName(), "Guessed MIME type from extension: " + mimeTypeStr);
        }

        long currentTime = System.currentTimeMillis();
        YaaccLogger.d(getClass().getName(), "Created item for: " + path + " with mime type: " + mimeTypeStr);
        if (file.getName() != null && file.getName().endsWith("m3u")) {
            YaaccLogger.d(getClass().getName(), "Ignoring m3u file");
            return null;
        }
        if (mimeTypeStr != null) {
            MimeType mimeType = MimeType.valueOf(mimeTypeStr);
            String mimeTypeMain = mimeType.getType();
            String base64enc = new String(Base64.encode(path.getBytes(), Base64.NO_WRAP));
            String id = ContentDirectoryIDs.SAF_PREFIX.getId() + base64enc;
            String title = file.getName() != null ? file.getName() : path;

            if (restricted) {
                title = "[X] " + title; //🔒
            }
            // The actual URI for streaming from this server
            String uri = getUriString(contentDirectory, id, mimeType, path);

            // Create correct ProtocolInfo with DLNA attributes
            ProtocolInfo protocolInfo = new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), getDLNAAttributes(mimeType));

            // Create resource without duration first for audio files

            String duration = null;
            if (mimeTypeMain.equals("audio") && !restricted) {
                YaaccLogger.d(getClass().getName(), "Extracting duration for: " + file.getUri() + " took: " + (System.currentTimeMillis() - currentTime) + "ms");
                duration = extractDuration(file);
                YaaccLogger.d(getClass().getName(), "Extracted duration for: " + file.getUri() + " took: " + (System.currentTimeMillis() - currentTime) + "ms");
            }
            Res res = new Res(protocolInfo, file.length(), duration, null, uri);

            Item item = null;
            if (mimeTypeMain.equals("audio")) {
                item = new AudioItem(id, parentId, title, "yaacc", res);
            } else if (mimeTypeMain.equals("video")) {
                item = new VideoItem(id, parentId, title, "yaacc", res);
            } else if (mimeTypeMain.equals("image")) {
                item = new ImageItem(id, parentId, title, "yaacc", res);
            }

            if (item != null) {
                item.setRestricted(restricted);
            }
            YaaccLogger.d(getClass().getName(), "Created item for: " + path + "took: " + (System.currentTimeMillis() - currentTime) + "ms");
            return item;

        }
        return null;
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
    private String extractDuration(DocumentFile file) {
        MediaMetadataRetriever retriever = null;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (preferences.contains(getContext().getString(R.string.settings_duration_format_key) + file.getUri())) {
            YaaccLogger.d(getClass().getName(), "Found duration in cache for: " + file.getUri());
            return preferences.getString(getContext().getString(R.string.settings_duration_format_key) + file.getUri(), null);
        }
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(getContext(), file.getUri());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                String durationString = FormatHelper.parseMillisToTimeStringTo(durationMs);
                YaaccLogger.d(getClass().getName(), "Put duration in cache for: " + file.getUri());
                preferences.edit().putString(getContext().getString(R.string.settings_duration_format_key) + file.getUri(), durationString).apply();
                return durationString;
            }
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Could not extract duration from: " + file.getUri(), e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    YaaccLogger.w(getClass().getName(), "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
        // Return null if extraction fails - let UPnP handle unknown duration
        return null;
    }
}
