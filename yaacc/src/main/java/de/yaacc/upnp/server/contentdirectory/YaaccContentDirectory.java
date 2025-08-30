/*
 *
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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

import static java.util.Arrays.stream;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpInputArgument;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.binding.annotations.UpnpStateVariables;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.csv.CSV;
import org.fourthline.cling.model.types.csv.CSVString;
import org.fourthline.cling.support.contentdirectory.ContentDirectoryErrorCode;
import org.fourthline.cling.support.contentdirectory.ContentDirectoryException;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.BrowseResult;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.container.MusicAlbum;
import org.fourthline.cling.support.model.container.PhotoAlbum;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.Photo;
import org.seamless.util.MimeType;

import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.yaacc.R;

/**
 * a content directory which uses the content of the MediaStore in order to
 * provide it via upnp.
 *
 * @author Tobias Schoene (tobexyz)
 */
@UpnpService(serviceId = @UpnpServiceId("ContentDirectory"), serviceType = @UpnpServiceType(value = "ContentDirectory"))
@UpnpStateVariables({
        @UpnpStateVariable(name = "A_ARG_TYPE_ObjectID", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_Result", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_BrowseFlag", sendEvents = false, datatype = "string", allowedValuesEnum = BrowseFlag.class),
        @UpnpStateVariable(name = "A_ARG_TYPE_Filter", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_SortCriteria", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_Index", sendEvents = false, datatype = "ui4"),
        @UpnpStateVariable(name = "A_ARG_TYPE_Count", sendEvents = false, datatype = "ui4"),
        @UpnpStateVariable(name = "A_ARG_TYPE_UpdateID", sendEvents = false, datatype = "ui4"),
        @UpnpStateVariable(name = "A_ARG_TYPE_URI", sendEvents = false, datatype = "uri")})
public class YaaccContentDirectory {


    @UpnpStateVariable(sendEvents = false)
    final private CSV<String> searchCapabilities;
    @UpnpStateVariable(sendEvents = false)
    final private CSV<String> sortCapabilities;
    final private PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(
            this);
    // test content only
    private final Map<String, DIDLObject> content = new HashMap<>();
    private final Context context;
    private final SharedPreferences preferences;
    @UpnpStateVariable(defaultValue = "0", eventMaximumRateMilliseconds = 200)
    private final UnsignedIntegerFourBytes systemUpdateID = new UnsignedIntegerFourBytes(
            0);
    private final String ipAddress;

    public YaaccContentDirectory(Context context, String ipAddress) {
        this.context = context;
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        if (isUsingTestContent()) {
            createTestContentDirectory();
        }
        this.searchCapabilities = new CSVString();
        this.sortCapabilities = new CSVString();
        this.ipAddress = ipAddress;
    }

    private boolean isUsingTestContent() {
        return preferences.getBoolean(
                getContext().getString(
                        R.string.settings_local_server_testcontent_chkbx),
                false);
    }

    public Context getContext() {
        return context;
    }

    /**
     *
     */
    private void createTestContentDirectory() {
        StorageFolder rootContainer = new StorageFolder("0", "-1", "root",
                "yaacc", 2, 907000L);
        rootContainer.setClazz(new DIDLObject.Class("object.container"));
        rootContainer.setRestricted(true);
        addContent(rootContainer.getId(), rootContainer);
        List<MusicTrack> musicTracks = createMusicTracks("1");
        MusicAlbum musicAlbum = new MusicAlbum("1", rootContainer, "Music",
                null, musicTracks.size(), musicTracks);
        musicAlbum.setClazz(new DIDLObject.Class("object.container"));
        musicAlbum.setRestricted(true);
        rootContainer.addContainer(musicAlbum);
        addContent(musicAlbum.getId(), musicAlbum);
        List<Photo> photos = createPhotos("2");
        PhotoAlbum photoAlbum = new PhotoAlbum("2", rootContainer, "Photos",
                null, photos.size(), photos);
        photoAlbum.setClazz(new DIDLObject.Class("object.container"));
        photoAlbum.setRestricted(true);
        rootContainer.addContainer(photoAlbum);
        addContent(photoAlbum.getId(), photoAlbum);
    }

    private List<MusicTrack> createMusicTracks(String parentId) {
        String album = "Music";
        String creator = "freetestdata.com";
        PersonWithRole artist = new PersonWithRole(creator, "");
        MimeType mimeType = new MimeType("audio", "mpeg");
        List<MusicTrack> result = new ArrayList<>();
        Res res = new Res(
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD),
                123456L,
                "00:01:27",
                26752L,
                "https://freetestdata.com/wp-content/uploads/2021/09/Free_Test_Data_2MB_MP3.mp3");
        res.setSampleFrequency(44100L);
        res.setNrAudioChannels(2L);
        MusicTrack musicTrack = new MusicTrack(
                "101",
                parentId,
                "Free_Test_Data_2MB_MP3",
                creator,
                album,
                artist,
                res);
        musicTrack.setRestricted(true);
        addContent(musicTrack.getId(), musicTrack);
        result.add(musicTrack);
        mimeType = new MimeType("audio", "ogg");
        musicTrack = new MusicTrack(
                "102",
                parentId,
                "Free_Test_Data_2MB_OGG",
                creator,
                album,
                artist,
                new Res(
                        new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD),
                        123456L,
                        "00:01:49",
                        8192L,
                        "https://freetestdata.com/wp-content/uploads/2021/09/Free_Test_Data_2MB_OGG.ogg"));
        musicTrack.setRestricted(true);
        addContent(musicTrack.getId(), musicTrack);
        result.add(musicTrack);

        return result;
    }

    private List<Photo> createPhotos(String parentId) {


        MimeType mimeType = new MimeType("image", "jpeg");
        List<Photo> result = new ArrayList<>();

        String url = "https://cdn.pixabay.com/photo/2015/04/23/22/00/tree-736881_960_720.jpg";

        Photo photo = new Photo("201", parentId, url, null, null, new Res(
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD), 123456L, url));
        photo.setRestricted(true);
        photo.setClazz(new DIDLObject.Class("object.item.imageItem"));
        addContent(photo.getId(), photo);
        result.add(photo);

        url = "https://cdn.pixabay.com/photo/2016/08/11/23/48/italy-1587287_960_720.jpg";

        photo = new Photo("202", parentId, url, null, null, new Res(
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD), 123456L, url));
        photo.setRestricted(true);
        photo.setClazz(new DIDLObject.Class("object.item.imageItem"));
        addContent(photo.getId(), photo);
        result.add(photo);

        url = "https://cdn.pixabay.com/photo/2014/09/10/00/59/utah-440520_960_720.jpg";

        addContent(photo.getId(), photo);
        photo = new Photo("203", parentId, url, null, null, new Res(
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD), 123456L, url));
        photo.setRestricted(true);
        photo.setClazz(new DIDLObject.Class("object.item.imageItem"));
        result.add(photo);

        url = "https://cdn.pixabay.com/photo/2017/01/04/21/00/fireworks-1953253_960_720.jpg";

        photo = new Photo("204", parentId, url, null, null, new Res(
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mimeType.toString(), ProtocolInfo.WILDCARD), 123456L, url));
        photo.setRestricted(true);
        photo.setClazz(new DIDLObject.Class("object.item.imageItem"));
        addContent(photo.getId(), photo);
        result.add(photo);

        url = "https://cdn.pixabay.com/photo/2013/07/27/05/13/lighthouse-168132_960_720.jpg";

        photo = new Photo("205", parentId, url, null, null, new Res(
                mimeType, 123456L, url));
        photo.setRestricted(true);
        photo.setClazz(new DIDLObject.Class("object.item.imageItem"));
        addContent(photo.getId(), photo);
        result.add(photo);

        return result;
    }

    // *******************************************************************

    @UpnpAction(out = @UpnpOutputArgument(name = "SearchCaps"))
    public CSV<String> getSearchCapabilities() {
        return searchCapabilities;
    }

    @UpnpAction(out = @UpnpOutputArgument(name = "SortCaps"))
    public CSV<String> getSortCapabilities() {
        return sortCapabilities;
    }

    @UpnpAction(out = @UpnpOutputArgument(name = "Id"))
    synchronized public UnsignedIntegerFourBytes getSystemUpdateID() {
        return systemUpdateID;
    }

    public PropertyChangeSupport getPropertyChangeSupport() {
        return propertyChangeSupport;
    }

    /**
     * Call this method after making changes to your content directory.
     * <p>
     * This will notify clients that their view of the content directory is
     * potentially outdated and has to be refreshed.
     * </p>
     */
    synchronized protected void changeSystemUpdateID() {
        Long oldUpdateID = getSystemUpdateID().getValue();
        systemUpdateID.increment(true);
        getPropertyChangeSupport().firePropertyChange("SystemUpdateID",
                oldUpdateID, getSystemUpdateID().getValue());
    }

    /**
     * add an object to the content of the directory
     *
     * @param id      of the object
     * @param content the object
     */
    private void addContent(String id, DIDLObject content) {
        this.content.put(id, content);
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "Result", stateVariable = "A_ARG_TYPE_Result", getterName = "getResult"),
            @UpnpOutputArgument(name = "NumberReturned", stateVariable = "A_ARG_TYPE_Count", getterName = "getCount"),
            @UpnpOutputArgument(name = "TotalMatches", stateVariable = "A_ARG_TYPE_Count", getterName = "getTotalMatches"),
            @UpnpOutputArgument(name = "UpdateID", stateVariable = "A_ARG_TYPE_UpdateID", getterName = "getContainerUpdateID")})
    public BrowseResult browse(
            @UpnpInputArgument(name = "ObjectID", aliases = "ContainerID") String objectId,
            @UpnpInputArgument(name = "BrowseFlag") String browseFlag,
            @UpnpInputArgument(name = "Filter") String filter,
            @UpnpInputArgument(name = "StartingIndex", stateVariable = "A_ARG_TYPE_Index") UnsignedIntegerFourBytes firstResult,
            @UpnpInputArgument(name = "RequestedCount", stateVariable = "A_ARG_TYPE_Count") UnsignedIntegerFourBytes maxResults,
            @UpnpInputArgument(name = "SortCriteria") String orderBy)
            throws ContentDirectoryException {

        SortCriterion[] orderByCriteria;
        try {
            orderByCriteria = SortCriterion.valueOf(orderBy);
        } catch (Exception ex) {
            throw new ContentDirectoryException(
                    ContentDirectoryErrorCode.UNSUPPORTED_SORT_CRITERIA,
                    ex.toString());
        }

        try {
            return browse(objectId, BrowseFlag.valueOrNullOf(browseFlag),
                    filter, firstResult.getValue(), maxResults.getValue(),
                    orderByCriteria);
        } catch (ContentDirectoryException ex) {
            throw ex;
        } catch (Exception ex) {
            Log.d(getClass().getName(), "exception on browse", ex);
            throw new ContentDirectoryException(ErrorCode.ACTION_FAILED,
                    ex.toString());
        }
    }

    public BrowseResult browse(String objectID, BrowseFlag browseFlag,
                               String filter, long firstResult, long maxResults,
                               SortCriterion[] orderby) throws ContentDirectoryException {

        Log.d(getClass().getName(), "Browse: objectId: " + objectID
                + " browseFlag: " + browseFlag + " filter: " + filter
                + " firstResult: " + firstResult + " maxResults: " + maxResults
                + " orderby: " + stream(orderby).map(SortCriterion::toString).collect(Collectors.joining(",")));
        long childCount;
        long totalMatches = 1L;
        DIDLObject didlObject;
        DIDLContent didl = new DIDLContent();
        if (isUsingTestContent()) {
            didlObject = content.get(objectID);
            if (didlObject == null) {
                // object not found return root
                didlObject = content.get("0");
            }
            if (browseFlag == BrowseFlag.METADATA) {
                didl.addObject(didlObject);
                childCount = 1;
            } else {
                if (didlObject instanceof Container) {
                    Container container = (Container) didlObject;
                    childCount = container.getChildCount();
                    List<DIDLObject> allChilds = new ArrayList<>();
                    allChilds.addAll(container.getItems());
                    allChilds.addAll(container.getContainers());
                    for (int i = 0; i < allChilds.size(); i++) {
                        if (i >= firstResult) {
                            if (allChilds.get(i) instanceof Item) {
                                didl.addItem((Item) allChilds.get(i));
                            } else {
                                didl.addContainer((Container) allChilds.get(i));
                            }
                        }
                    }

                } else {
                    didl.addObject(didlObject);
                    childCount = 1;
                }
            }

        } else {
            childCount = 0;
            if (findBrowserFor(objectID) != null) {
                totalMatches = findBrowserFor(objectID).getSize(this, objectID);
                if (browseFlag == BrowseFlag.METADATA) {
                    didlObject = findBrowserFor(objectID).browseMeta(this, objectID, firstResult, maxResults, orderby);
                    didl.addObject(didlObject);
                    childCount = 1;
                } else {
                    List<DIDLObject> children = findBrowserFor(objectID).browseChildren(this, objectID, firstResult, maxResults, orderby);
                    for (DIDLObject child : children) {
                        didl.addObject(child);
                        childCount++;

                    }

                }

            }
        }
        BrowseResult result;
        try {
            // Generate output with nested items
            String didlXml = new DIDLParser().generate(didl, false);
            Log.d(getClass().getName(), "CDResponse: " + didlXml);
            result = new BrowseResult(didlXml, childCount, totalMatches);
        } catch (Exception e) {
            throw new ContentDirectoryException(
                    ContentDirectoryErrorCode.CANNOT_PROCESS.getCode(),
                    "Error while generating BrowseResult", e);
        }
        return result;

    }

    private ContentBrowser findBrowserFor(String objectID) {
        ContentBrowser result = null;
        if (objectID == null || objectID.equals("") || ContentDirectoryIDs.ROOT.getId().equals(objectID)) {
            result = new RootFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.IMAGES_FOLDER.getId().equals(objectID)) {
            result = new ImagesFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.VIDEOS_FOLDER.getId().equals(objectID)) {
            result = new VideosFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.MUSIC_FOLDER.getId().equals(objectID)) {
            result = new MusicFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.MUSIC_GENRES_FOLDER.getId().equals(objectID)) {
            result = new MusicGenresFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.MUSIC_ALBUMS_FOLDER.getId().equals(objectID)) {
            result = new MusicAlbumsFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.MUSIC_ARTISTS_FOLDER.getId().equals(objectID)) {
            result = new MusicArtistsFolderBrowser(getContext());
        } else if (ContentDirectoryIDs.MUSIC_ALL_TITLES_FOLDER.getId().equals(objectID)) {
            result = new MusicAllTitlesFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_ALBUM_PREFIX.getId())) {
            result = new MusicAlbumFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_ARTIST_PREFIX.getId())) {
            result = new MusicArtistFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_GENRE_PREFIX.getId())) {
            result = new MusicGenreFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_ALL_TITLES_ITEM_PREFIX.getId())) {
            result = new MusicAllTitleItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_GENRE_ITEM_PREFIX.getId())) {
            result = new MusicGenreItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_ALBUM_ITEM_PREFIX.getId())) {
            result = new MusicAlbumItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.MUSIC_ARTIST_ITEM_PREFIX.getId())) {
            result = new MusicArtistItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.IMAGES_ALL_FOLDER.getId())) {
            result = new ImagesAllFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAMES_FOLDER.getId())) {
            result = new ImagesByBucketNamesFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.IMAGE_ALL_PREFIX.getId())) {
            result = new ImageAllItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.IMAGES_BY_BUCKET_NAME_PREFIX.getId())) {
            result = new ImagesByBucketNameFolderBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.IMAGE_BY_BUCKET_PREFIX.getId())) {
            result = new ImageByBucketNameItemBrowser(getContext());
        } else if (objectID.startsWith(ContentDirectoryIDs.VIDEO_PREFIX.getId())) {
            result = new VideoItemBrowser(getContext());
        }

        return result;
    }


    public String formatDuration(String millisStr) {

        if (millisStr == null || millisStr.equals("")) {
            return String.format(Locale.US, "%02d:%02d:%02d", 0L, 0L, 0L);
        }
        String res;
        long duration = Long.parseLong(millisStr);
        long hours = TimeUnit.MILLISECONDS.toHours(duration)
                - TimeUnit.DAYS.toHours(TimeUnit.MILLISECONDS.toDays(duration));
        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
                - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS
                .toHours(duration));
        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration)
                - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS
                .toMinutes(duration));

        res = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes,
                seconds);

        return res;
    }

    public String getIpAddress() {
        return ipAddress;
    }

}
