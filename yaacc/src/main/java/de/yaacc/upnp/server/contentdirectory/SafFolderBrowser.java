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
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

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

import de.yaacc.R;

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

            return new StorageFolder(myId, parentId, title, "yaacc", getSize(contentDirectory, myId), null);
        }
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            return getSelectedSafPathes().size();
        } else {
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            DocumentFile file = null;
            try {
                file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            } catch (Exception e) {
                Log.e("SafFolderBrowser", "Error accessing DocumentFile: " + path, e);
                return 0;
            }
            if (file != null && file.isDirectory()) {
                try {
                    DocumentFile[] files = file.listFiles();
                    return files != null ? files.length : 0;
                } catch (Exception e) {
                    Log.e("SafFolderBrowser", "Error listing files: " + path, e);
                }
            }
        }
        return 0;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        Log.d("SafFolderBrowser", "browseContainer called with myId: " + myId);
        List<Container> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            Log.d("SafFolderBrowser", "Browsing root SAF folder");
            List<String> sortedPathes = new ArrayList<>(getSelectedSafPathes());
            Collections.sort(sortedPathes);

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);

            for (int i = start; i < end; i++) {
                String path = sortedPathes.get(i);
                try {
                    DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                    if (file != null && file.isDirectory()) {
                        String title = file.getName() != null ? file.getName() : path;
                        String base64Str = Base64.encodeToString(file.getUri().toString().getBytes(), Base64.NO_WRAP);
                        String folderId = ContentDirectoryIDs.SAF_PREFIX.getId() + base64Str;
                        Log.d("SafFolderBrowser", "Creating root folder: " + title + " with ID: " + folderId);
                        StorageFolder folder = new StorageFolder(folderId, ContentDirectoryIDs.SAF_FOLDER.getId(), title, "yaacc", 0, null);
                        result.add(folder);
                    }
                } catch (Exception e) {
                    Log.e("SafFolderBrowser", "Error processing SAF path: " + path, e);
                }
            }
        } else {
            // Browse subfolder
            Log.d("SafFolderBrowser", "Browsing subfolder with ID: " + myId);
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            Log.d("SafFolderBrowser", "Encoded path: " + pathEnc);
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            Log.d("SafFolderBrowser", "Decoded path: " + path);

            Uri uri = Uri.parse(path);
            DocumentFile root = null;

            // Check if this is a tree URI or document URI
            if (path.contains("/tree/")) {
                // This is a tree URI, use it directly
                root = DocumentFile.fromTreeUri(getContext(), uri);
                Log.d("SafFolderBrowser", "Using tree URI: " + path);
            } else {
                // This is a document URI, we need to find it within its parent tree
                Log.d("SafFolderBrowser", "Document URI detected, finding parent tree: " + path);
                // For now, skip these problematic folders to avoid showing parent content
                Log.w("SafFolderBrowser", "Skipping document URI folder to avoid parent content");
                return result;
            }

            if (root != null && root.isDirectory()) {
                DocumentFile[] files = root.listFiles();
                Log.d("SafFolderBrowser", "Found " + files.length + " files in subfolder");
                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(files.length, start + maxResults);
                Log.d("SafFolderBrowser", "Parent: " + myId);
                for (int i = start; i < end; i++) {
                    DocumentFile file = files[i];
                    if (file.isDirectory()) {
                        Log.d("SafFolderBrowser", "Child: " + file.getUri().toString());
                        String title = file.getName() != null ? file.getName() : file.getUri().toString();

                        // Create tree URI for the child folder so it can be browsed properly
                        try {
                            String authority = file.getUri().getAuthority();
                            String documentId = DocumentsContract.getDocumentId(file.getUri());
                            Uri childTreeUri = DocumentsContract.buildTreeDocumentUri(authority, documentId);
                            Log.d("SafFolderBrowser", "Child tree URI: " + childTreeUri);

                            // Test if we can access this tree URI
                            DocumentFile testAccess = DocumentFile.fromTreeUri(getContext(), childTreeUri);
                            if (testAccess != null && testAccess.canRead()) {
                                String base64Str = Base64.encodeToString(childTreeUri.toString().getBytes(), Base64.NO_WRAP);
                                String childId = ContentDirectoryIDs.SAF_PREFIX.getId() + base64Str;
                                Log.d("SafFolderBrowser", "Creating child folder: " + title + " with ID: " + childId);
                                StorageFolder folder = new StorageFolder(childId, myId, title, "yaacc", 0, null);
                                result.add(folder);
                            } else {

                                Log.w("SafFolderBrowser", "Cannot access child tree URI, skipping folder: " + title);
                            }
                        } catch (Exception e) {
                            Log.e("SafFolderBrowser", "Error creating tree URI for child, skipping folder: " + title, e);
                        }
                    }
                }
            } else {
                Log.e("SafFolderBrowser", "Root DocumentFile is null or not a directory for path: " + path);
            }
        }
        Log.d("SafFolderBrowser", "Returning " + result.size() + " containers");
        return result;
    }

    @Override
    public List<Item> browseItem(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
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
                    addItem(contentDirectory, result, path, file, myId);
                }
            }
        } else {
            // Browse subfolder items
            String pathEnc = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            String path = new String(Base64.decode(pathEnc.getBytes(), Base64.NO_WRAP));
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            if (root != null && root.isDirectory()) {
                DocumentFile[] files = root.listFiles();
                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(files.length, start + maxResults);
                for (int i = start; i < end; i++) {
                    DocumentFile file = files[i];
                    if (!file.isDirectory()) {
                        addItem(contentDirectory, result, file.getUri().toString(), file, myId);
                    }
                }
            }
        }
        return result;
    }

    private void addItem(YaaccContentDirectory contentDirectory, List<Item> result, String path, DocumentFile file, String parentId) {
        String mimeTypeStr = file.getType();
        if (mimeTypeStr != null) {
            MimeType mimeType = MimeType.valueOf(mimeTypeStr);
            String id = ContentDirectoryIDs.SAF_PREFIX.getId() + path.hashCode();
            String title = file.getName() != null ? file.getName() : path;

            // The actual URI for streaming from this server
            String uri = getUriString(contentDirectory, id, mimeType, path);

            // Create correct ProtocolInfo with DLNA attributes
            ProtocolInfo protocolInfo = new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), getDLNAAttributes(mimeType));

            Res res = new Res(protocolInfo, file.length(), uri);

            Item item = null;
            if (mimeType.getType().equals("audio")) {
                item = new AudioItem(id, parentId, title, "yaacc", res);
            } else if (mimeType.getType().equals("video")) {
                item = new VideoItem(id, parentId, title, "yaacc", res);
            } else if (mimeType.getType().equals("image")) {
                item = new ImageItem(id, parentId, title, "yaacc", res);
            }

            if (item != null) {
                result.add(item);
            }
        }
    }
}