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
            String path = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            String title = (file != null && file.getName() != null) ? file.getName() : path;
            return new StorageFolder(myId, ContentDirectoryIDs.SAF_PREFIX.getId(), title, "yaacc", getSize(contentDirectory, myId), null);
        }
    }

    @Override
    public Integer getSize(YaaccContentDirectory contentDirectory, String myId) {
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            return getSelectedSafPathes().size();
        } else {
            String path = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            if (file != null && file.isDirectory()) {
                return file.listFiles().length;
            }
        }
        return 0;
    }

    @Override
    public List<Container> browseContainer(YaaccContentDirectory contentDirectory, String myId, long firstResult, long maxResults, SortCriterion[] orderby) {
        List<Container> result = new ArrayList<>();
        if (myId.equals(ContentDirectoryIDs.SAF_FOLDER.getId())) {
            List<String> sortedPathes = new ArrayList<>(getSelectedSafPathes());
            Collections.sort(sortedPathes);

            int start = (int) Math.max(0, firstResult);
            int end = (int) Math.min(sortedPathes.size(), start + maxResults);

            for (int i = start; i < end; i++) {
                String path = sortedPathes.get(i);
                DocumentFile file = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
                if (file != null && file.isDirectory()) {
                    String title = file.getName() != null ? file.getName() : path;
                    StorageFolder folder = new StorageFolder(ContentDirectoryIDs.SAF_PREFIX.getId() + path, ContentDirectoryIDs.SAF_FOLDER.getId(), title, "yaacc", 0, null);
                    result.add(folder);
                }
            }
        } else {
            // Browse subfolder
            String path = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), Uri.parse(path));
            if (root != null && root.isDirectory()) {
                DocumentFile[] files = root.listFiles();
                int start = (int) Math.max(0, firstResult);
                int end = (int) Math.min(files.length, start + maxResults);
                for (int i = start; i < end; i++) {
                    DocumentFile file = files[i];
                    if (file.isDirectory()) {
                        StorageFolder folder = new StorageFolder(ContentDirectoryIDs.SAF_PREFIX.getId() + file.getUri().toString(), myId, file.getName(), "yaacc", 0, null);
                        result.add(folder);
                    }
                }
            }
        }
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
            String path = myId.substring(ContentDirectoryIDs.SAF_PREFIX.getId().length());
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
            String id = ContentDirectoryIDs.SAF_PREFIX.getId() + path;
            String title = file.getName() != null ? file.getName() : path;

            // The actual URI for streaming from this server
            String uri = getUriString(contentDirectory, id, mimeType);

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