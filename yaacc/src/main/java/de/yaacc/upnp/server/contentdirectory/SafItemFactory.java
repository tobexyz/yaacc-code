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

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.item.Item;
import org.seamless.util.MimeType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import de.yaacc.upnp.model.YaaccItem;
import de.yaacc.upnp.model.YaaccRes;
import de.yaacc.util.SAFMetadata;

/**
 * Builds the ContentDirectory item of one file of a SAF folder from the values of the listing and the cached metadata.
 * The part of {@code SafFolderBrowser.createItem} that needs no Android classes, so it is testable (and measurable)
 * on the plain JVM. One instance serves one Browse: what depends on the mime type is worked out once per mime type
 * (a folder has a handful of them, not 5000), the part of the resource url that is the same for all items is a prefix.
 * <p>
 * The result is the same as before: ids ({@code SAF_PREFIX + short id}), titles ("[X] " prefix when restricted),
 * resource url ({@code <prefix><id>/<short id>_<safe file name>.<extension>}), protocol info, class, size, duration (audio,
 * not restricted) and {@code dc:date}.
 * <p>
 * Not thread safe.
 *
 * @author Tobias Schoene (tobexyz)
 */
final class SafItemFactory {

    private static final long MILLIS_PER_DAY = 24L * 3600 * 1000;

    /** The characters of a file name that are replaced in the resource url. */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    /** What is the same for all files of one mime type. */
    private static final class MimeInfo {
        final MimeType mimeType;
        final boolean audio;
        final String extension;
        final ProtocolInfo protocolInfo;
        final String clazz;

        MimeInfo(MimeType mimeType, String extension, ProtocolInfo protocolInfo) {
            this.mimeType = mimeType;
            String main = mimeType.getType();
            this.audio = main.equals("audio");
            this.extension = extension;
            this.protocolInfo = protocolInfo;
            this.clazz = audio ? "object.item.audioItem"
                    : main.equals("video") ? "object.item.videoItem"
                    : "object.item.imageItem";
        }
    }

    private final String uriPrefix;
    private final Function<String, String> extensionOfMimeType;
    private final Function<MimeType, ProtocolInfo> protocolInfoOf;
    private final Map<String, MimeInfo> byMimeString = new HashMap<>();
    /** {@code dc:date} (a UTC day) by day number: the files of a folder are from a few hundred days at most. */
    private final Map<Long, String> dateByDay = new HashMap<>();

    /**
     * @param uriPrefix           {@code http://<ip>:<port>/saf/}; the ip is looked up once per Browse by the caller
     *                            (looking it up lists the network interfaces: not a thing to do per item)
     * @param extensionOfMimeType the file extension of a mime type, null if unknown
     * @param protocolInfoOf      the protocol info (with the DLNA attributes) of a mime type
     */
    SafItemFactory(String uriPrefix, Function<String, String> extensionOfMimeType, Function<MimeType, ProtocolInfo> protocolInfoOf) {
        this.uriPrefix = uriPrefix;
        this.extensionOfMimeType = extensionOfMimeType;
        this.protocolInfoOf = protocolInfoOf;
    }

    /**
     * @param documentUri the file's document uri (the key of the metadata)
     * @param file        the file's values from the listing
     * @param metadata    its cached metadata
     * @param mimeGuesser guesses a mime type from the file name when the metadata has no usable one; may return null
     * @return the item, null if the mime type cannot be determined (the file is not served)
     * @throws IllegalArgumentException for a mime type that cannot be parsed (as before)
     */
    Item create(String documentUri, SafDirectoryEntry file, SAFMetadata metadata, String parentId, boolean restricted,
                UnaryOperator<String> mimeGuesser) {
        // If MIME type is null or invalid, try to guess from filename
        String mimeTypeStr = metadata.mimeType;
        if (mimeTypeStr == null || mimeTypeStr.equals("null") || !mimeTypeStr.contains("/")) {
            mimeTypeStr = mimeGuesser.apply(file.name);
            if (mimeTypeStr == null) {
                return null;  // Still couldn't determine MIME type
            }
        }
        MimeInfo mime = byMimeString.get(mimeTypeStr);
        if (mime == null) {
            MimeType mimeType = MimeType.valueOf(mimeTypeStr);
            String extension = extensionOfMimeType.apply(mimeType.toString());
            if (extension == null) {
                extension = mimeType.getSubtype();
            }
            mime = new MimeInfo(mimeType, extension, protocolInfoOf.apply(mimeType));
            byMimeString.put(mimeTypeStr, mime);
        }

        String id = ContentDirectoryIDs.SAF_PREFIX.getId() + metadata.shortId;
        String title = file.name != null ? file.name : fileNameOf(documentUri);
        if (restricted) {
            title = "[X] " + title;
        }

        // The short id (not an encoded path) and the file name in the url: renderers show the name as title.
        // Everything but letters, digits and ._- of the name is replaced.
        String safeFilename = UNSAFE_FILENAME_CHARS.matcher(file.name != null ? file.name : "file").replaceAll("_");
        String uri = uriPrefix + id + "/" + metadata.shortId + "_" + safeFilename + "." + mime.extension;

        String duration = mime.audio && !restricted ? metadata.duration : null;

        // the size of the listing: the cached metadata holds the size of the first sighting, which is too small for a
        // file that was still being written then (a download); 0 = the provider has none
        YaaccRes yaaccRes = new YaaccRes(mime.protocolInfo, file.size > 0 ? file.size : metadata.fileSize, duration, null, uri);
        YaaccItem yaaccItem = new YaaccItem(id, parentId, title, "yaacc", restricted, mime.clazz);
        yaaccItem.addResource(yaaccRes);
        Item item = yaaccItem.toClingItem();

        // dc:date from the file's lastModified so SortSupport can honor SortCriteria="dc:date" (0 = unknown, leave unset)
        if (file.lastModified > 0) {
            item.addProperty(new DIDLObject.Property.DC.DATE(dateOf(file.lastModified)));
        }
        return item;
    }

    private String dateOf(long lastModified) {
        return dateByDay.computeIfAbsent(lastModified / MILLIS_PER_DAY, day -> SortSupport.formatEpochMillisAsDate(lastModified));
    }

    private static String fileNameOf(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
