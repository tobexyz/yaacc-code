/*
 *
 * Copyright (C) 2013  Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.upnp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.meta.UDAVersion;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.contentdirectory.callback.Browse.Status;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

import java.net.URI;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.browser.Position;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerService;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseActionCallback;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;
import de.yaacc.upnp.model.types.SyncOffset;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.upnp.server.avtransport.AvTransport;
import de.yaacc.util.FileDownloader;
import de.yaacc.util.Watchdog;

/**
 * A client facade to the upnp lookup and access framework. This class provides
 * all services to manage devices.
 *
 * @author Tobias Schoene (TheOpenBit)
 */
public class UpnpClient implements RegistryListener, ServiceConnection {
    public static String LOCAL_UID = "LOCAL_UID";
    private final List<UpnpClientListener> listeners = new ArrayList<>();
    SharedPreferences preferences;
    private AndroidUpnpService androidUpnpService;
    private Context context;
    private boolean mute = false;
    private PlayerService playerService;

    public UpnpClient() {
    }

    public UpnpClient(Context context) {
        initialize(context);

    }


    /**
     * Initialize the Object.
     *
     * @param context the context
     * @return true if initialization completes correctly
     */
    public boolean initialize(Context context) {
        if (context != null) {
            this.context = context;
            this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
            // FIXME check if this is right: Context.BIND_AUTO_CREATE kills the
            // service after closing the activity
            boolean result = context.bindService(new Intent(context, UpnpRegistryService.class), this, Context.BIND_AUTO_CREATE);
            return result && startService();
        }
        return false;
    }

    public boolean startService() {
        if (playerService == null) {

            getContext().startForegroundService(new Intent(getContext(), PlayerService.class));
            return getContext().bindService(new Intent(getContext(), PlayerService.class),
                    this, Context.BIND_AUTO_CREATE);
        }
        return true;
    }

    private SyncOffset getDeviceSyncOffset() {
        int offsetValue = Integer.parseInt(preferences.getString(getContext().getString(R.string.settings_device_playback_offset_key), "0"));
        if (offsetValue > 999) {
            Editor editor = preferences.edit();
            editor.putString(getContext().getString(R.string.settings_device_playback_offset_key), String.valueOf(999));
            editor.apply();
            offsetValue = 999;
        }
        return new SyncOffset(true, 0, 0, 0, offsetValue, 0, 0);
    }

    private void fireReceiverDeviceAdded(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : new ArrayList<>(listeners)) {
            listener.receiverDeviceAdded(device);
        }
    }

    private void fireReceiverDeviceRemoved(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : listeners) {
            listener.receiverDeviceRemoved(device);
        }
    }

    private void deviceAdded(@SuppressWarnings("rawtypes") final Device device) {
        fireDeviceAdded(device);
    }

    private void deviceRemoved(@SuppressWarnings("rawtypes") final Device device) {
        fireDeviceRemoved(device);
    }

    private void deviceUpdated(@SuppressWarnings("rawtypes") final Device device) {
        fireDeviceUpdated(device);
    }

    private void fireDeviceAdded(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : new ArrayList<>(listeners)) {
            listener.deviceAdded(device);
        }
    }

    private void fireDeviceRemoved(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : listeners) {
            listener.deviceRemoved(device);
        }
    }

    private void fireDeviceUpdated(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : listeners) {
            listener.deviceUpdated(device);
        }
    }

    // interface implementation ServiceConnection
    // monitor android service creation and destruction
    /*
     * (non-Javadoc)
     *
     * @see
     * android.content.ServiceConnection#onServiceConnected(android.content.
     * ComponentName, android.os.IBinder)
     */
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        if (service instanceof AndroidUpnpService) {
            setAndroidUpnpService(((AndroidUpnpService) service));
            refreshUpnpDeviceCatalog();
        }
        if (service instanceof PlayerService.PlayerServiceBinder) {
            playerService = ((PlayerService.PlayerServiceBinder) service).getService();

        }

    }

    /*
     * (non-Javadoc)
     *
     * @see
     * android.content.ServiceConnection#onServiceDisconnected(android.content
     * .ComponentName)
     */
    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        Log.d(getClass().getName(), "on Service disconnect: " + componentName);
        if (AndroidUpnpService.class.getName().equals(componentName.getClassName())) {
            setAndroidUpnpService(null);
        }
        if (PlayerService.class.getName().equals(componentName.getClassName())) {
            playerService = null;
        }

    }

    // ----------Implementation Upnp RegistryListener Interface
    @Override
    public void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice remotedevice) {
        // TODO Auto-generated method stub
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#remoteDeviceDiscoveryFailed
     * (org.fourthline.cling.registry.Registry,
     * org.fourthline.cling.model.meta.RemoteDevice, java.lang.Exception)
     */
    @Override
    public void remoteDeviceDiscoveryFailed(Registry registry, RemoteDevice remotedevice, Exception exception) {
        Log.d(getClass().getName(), "remoteDeviceDiscoveryFailed: " + remotedevice.getDisplayString(), exception);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#remoteDeviceAdded(org.
     * fourthline .cling.registry.Registry,
     * org.fourthline.cling.model.meta.RemoteDevice)
     */
    @Override
    public void remoteDeviceAdded(Registry registry, RemoteDevice remotedevice) {
        Log.d(getClass().getName(), "remoteDeviceAdded: " + remotedevice.getDisplayString());
        deviceAdded(remotedevice);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#remoteDeviceUpdated(org
     * .fourthline .cling.registry.Registry,
     * org.fourthline.cling.model.meta.RemoteDevice)
     */
    @Override
    public void remoteDeviceUpdated(Registry registry, RemoteDevice remotedevice) {
        Log.d(getClass().getName(), "remoteDeviceUpdated: " + remotedevice.getDisplayString());
        deviceUpdated(remotedevice);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#remoteDeviceRemoved(org
     * .fourthline .cling.registry.Registry,
     * org.fourthline.cling.model.meta.RemoteDevice)
     */
    @Override
    public void remoteDeviceRemoved(Registry registry, RemoteDevice remotedevice) {
        Log.d(getClass().getName(), "remoteDeviceRemoved: " + remotedevice.getDisplayString());
        deviceRemoved(remotedevice);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.fourthline.cling.registry.RegistryListener#localDeviceAdded(org.
     * fourthline .cling.registry.Registry,
     * org.fourthline.cling.model.meta.LocalDevice)
     */
    @Override
    public void localDeviceAdded(Registry registry, LocalDevice localdevice) {
        Log.d(getClass().getName(), "localDeviceAdded: " + localdevice.getDisplayString());
        this.getRegistry().addDevice(localdevice);
        this.deviceAdded(localdevice);
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#localDeviceRemoved(org
     * .fourthline .cling.registry.Registry,
     * org.fourthline.cling.model.meta.LocalDevice)
     */
    @Override
    public void localDeviceRemoved(Registry registry, LocalDevice localdevice) {
        Registry currentRegistry = this.getRegistry();
        if (localdevice != null && currentRegistry != null) {
            Log.d(getClass().getName(), "localDeviceRemoved: " + localdevice.getDisplayString());
            this.deviceRemoved(localdevice);
            this.getRegistry().removeDevice(localdevice);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.fourthline.cling.registry.RegistryListener#beforeShutdown(org.fourthline
     * . cling.registry.Registry)
     */
    @Override
    public void beforeShutdown(Registry registry) {
        Log.d(getClass().getName(), "beforeShutdown: " + registry);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.fourthline.cling.registry.RegistryListener#afterShutdown()
     */
    @Override
    public void afterShutdown() {
        Log.d(getClass().getName(), "afterShutdown ");
    }

    // ****************************************************

    /**
     * Returns a Service of type AVTransport
     *
     * @param device the device which provides the service
     * @return the service of null
     */
    public Service<?, ?> getAVTransportService(Device<?, ?, ?> device) {
        if (device == null) {
            Log.d(getClass().getName(), "Device is null!");
            return null;
        }
        ServiceId serviceId = new UDAServiceId("AVTransport");
        Service<?, ?> service = device.findService(serviceId);
        if (service != null) {
            Log.d(getClass().getName(), "Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
        }
        return service;
    }

    /**
     * Returns a Service of type RenderingControl
     *
     * @param device the device which provides the service
     * @return the service of null
     */
    public Service<?, ?> getRenderingControlService(Device<?, ?, ?> device) {
        if (device == null) {
            Log.d(getClass().getName(), "Device is null!");
            return null;
        }
        ServiceId serviceId = new UDAServiceId("RenderingControl");
        Service<?, ?> service = device.findService(serviceId);
        if (service != null) {
            Log.d(getClass().getName(), "Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
        }
        return service;
    }

    /**
     * Add an listener.
     *
     * @param listener the listener to be added
     */
    public void addUpnpClientListener(UpnpClientListener listener) {
        listeners.add(listener);
    }


    /**
     * returns the AndroidUpnpService
     *
     * @return the service
     */
    private AndroidUpnpService getAndroidUpnpService() {
        return androidUpnpService;
    }

    /**
     * Setting an new upnpRegistryService. If the service is not null, refresh
     * the device list.
     *
     * @param upnpService upnpservice
     */
    private void setAndroidUpnpService(AndroidUpnpService upnpService) {
        this.androidUpnpService = upnpService;
    }

    /**
     * Returns all registered UpnpDevices.
     *
     * @return the upnpDevices
     */
    public Collection<Device<?, ?, ?>> getDevices() {
        if (isInitialized()) {
            return getRegistry().getDevices();
        }
        return new ArrayList<>();
    }

    /**
     * Returns all registered UpnpDevices with a ContentDirectory Service.
     *
     * @return the upnpDevices
     */
    public Collection<Device<?, ?, ?>> getDevicesProvidingContentDirectoryService() {
        if (isInitialized()) {
            return getRegistry().getDevices(new UDAServiceType("ContentDirectory"));
        }
        return new ArrayList<>();
    }

    /**
     * Returns all registered UpnpDevices with an AVTransport Service.
     *
     * @return the upnpDevices
     */
    public Collection<Device<?, ?, ?>> getDevicesProvidingAvTransportService() {
        ArrayList<Device<?, ?, ?>> result = new ArrayList<>();
        result.add(getLocalDummyDevice());
        if (isInitialized()) {
            result.addAll(getRegistry().getDevices(new UDAServiceType("AVTransport")));
        }
        return result;
    }

    /**
     * Returns a registered UpnpDevice.
     *
     * @return the upnpDevice null if not found
     */
    public Device<?, ?, ?> getDevice(String identifier) {
        if (LOCAL_UID.equals(identifier)) {
            return getLocalDummyDevice();
        }
        if (isInitialized()) {
            return getRegistry().getDevice(new UDN(identifier), true);
        }
        return null;
    }

    /**
     * True if the client is initialized.
     *
     * @return true or false
     */
    public boolean isInitialized() {
        return getAndroidUpnpService() != null;
    }

    /**
     * returns the upnp control point
     *
     * @return the control point
     */
    public ControlPoint getControlPoint() {
        if (!isInitialized()) {
            return null;
        }
        return androidUpnpService.getControlPoint();
    }

    /**
     * Returns the upnp registry
     *
     * @return the registry
     */
    public Registry getRegistry() {
        if (!isInitialized()) {
            return null;
        }
        return androidUpnpService.getRegistry();
    }

    /**
     * @return the context
     */
    public Context getContext() {
        return context;
    }

    /**
     * refresh the device catalog
     */
    private void refreshUpnpDeviceCatalog() {
        if (isInitialized()) {
            for (Device<?, ?, ?> device : getAndroidUpnpService().getRegistry().getDevices()) {
                // FIXME: What about removed devices?
                this.deviceAdded(device);
            }
            // Getting ready for future device advertisements
            getAndroidUpnpService().getRegistry().addListener(this);
            searchDevices();
        }
    }

    /**
     * Browse ContenDirctory synchronous
     *
     * @param device   the device to be browsed
     * @param objectID the browsing root
     * @return the browsing result
     */
    private ContentDirectoryBrowseResult browseSync(Device<?, ?, ?> device, String objectID) {
        return browseSync(device, objectID, BrowseFlag.DIRECT_CHILDREN, "*", 0L, null);
    }

    /**
     * Browse ContenDirctory synchronous
     *
     * @param pos Position
     *            the device and object to be browsed
     * @return the browsing result
     */
    public ContentDirectoryBrowseResult browseSync(Position pos) {
        return browseSync(pos, 0L, null);
    }

    public ContentDirectoryBrowseResult browseSync(Position pos, Long firstResult, Long maxResult) {
        if (getProviderDevice() == null) {
            return null;
        }
        if (pos == null || pos.getDeviceId() == null) {
            if (getProviderDevice() != null) {
                return browseSync(getProviderDevice(), "0", BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult);
            } else {
                return null;
            }
        }
        if (getProviderDevice() != null && !pos.getDeviceId().equals(getProviderDevice().getIdentity().getUdn().getIdentifierString())) {
            return browseSync(getProviderDevice(), "0", BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult);
        }
        return browseSync(getDevice(pos.getDeviceId()), pos.getObjectId(), BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult);
    }

    /**
     * Browse ContenDirctory synchronous
     *
     * @param device      the device to be browsed
     * @param objectID    the browsing root
     * @param flag        kind of browsing @see {@link BrowseFlag}
     * @param filter      a filter
     * @param firstResult first result
     * @param maxResults  max result count
     * @param orderBy     sorting criteria @see {@link SortCriterion}
     * @return the browsing result
     */
    public ContentDirectoryBrowseResult browseSync(Device<?, ?, ?> device, String objectID, BrowseFlag flag, String filter, long firstResult,
                                                   Long maxResults, SortCriterion... orderBy) {
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        if (device == null) {
            return result;
        }
        Service<?, ?> service = device.findService(new UDAServiceId("ContentDirectory"));
        ContentDirectoryBrowseActionCallback actionCallback;
        if (service != null) {
            Log.d(getClass().getName(), "#####Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
            actionCallback = new ContentDirectoryBrowseActionCallback(service, objectID, flag, filter, firstResult, maxResults, result, orderBy);
            getControlPoint().execute(actionCallback);
            while (actionCallback.getStatus() == Status.LOADING && actionCallback.getUpnpFailure() == null) {
                //FIXME implement maybe async model?
            }

        }

        if (preferences.getBoolean(getContext().getString(R.string.settings_browse_thumbnails_coverlookup_chkbx), false)) {
            enrichWithCover(result);
        }
        return result;
    }

    /**
     * Trying to add album art if there are only audiofiles and exactly one imagefile in a folder
     *
     * @param callbackResult orginal callback
     */
    private void enrichWithCover(ContentDirectoryBrowseResult callbackResult) {

        DIDLContent cont = callbackResult.getResult();
        if (cont == null) {
            return;
        }
        if (cont.getContainers().size() != 0) {
            return;
        }
        URI albumArtUri = null;
        LinkedList<Item> audioFiles = new LinkedList<>();

        if (cont.getItems().size() == 1) {
            //nothing to enrich
            return;
        }

        for (Item currentItem : cont.getItems())
            if (!(currentItem instanceof AudioItem)) {
                if (null == albumArtUri && (currentItem instanceof ImageItem)) {
                    albumArtUri = URI.create(currentItem.getFirstResource().getValue());
                } else {
                    //There seem to be multiple images or other media files
                    return;
                }

            } else {
                audioFiles.add(currentItem);
            }

        if (null == albumArtUri) {
            return;
        }
        //We should only be here if there are just musicfiles and exactly one imagefile
        for (Item currentItem : audioFiles) {
            currentItem.replaceFirstProperty((new DIDLObject.Property.UPNP.ALBUM_ART_URI(albumArtUri)));
        }

        //this hopefully overwrites all previously existing contents
        cont.setItems(audioFiles);
        callbackResult.setResult(cont);

    }


    /*
     * Browse ContenDirctory asynchronous
     *
     * @param device      the device to be browsed
     * @param objectID    the browsing root
     * @param flag        kind of browsing @see {@link BrowseFlag}
     * @param filter      a filter
     * @param firstResult first result
     * @param maxResults  max result count
     * @param orderBy     sorting criteria @see {@link SortCriterion}
     * @return the browsing result
     *
     //FIXME needed?
    private ContentDirectoryBrowseResult browseAsync(Device<?, ?, ?> device, String objectID, BrowseFlag flag, String filter, long firstResult,
                                                     Long maxResults, SortCriterion... orderBy) {
        Service service = device.findService(new UDAServiceId("ContentDirectory"));
        ContentDirectoryBrowseResult result = new ContentDirectoryBrowseResult();
        ContentDirectoryBrowseActionCallback actionCallback = null;
        if (service != null) {
            Log.d(getClass().getName(), "#####Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
            actionCallback = new ContentDirectoryBrowseActionCallback(service, objectID, flag, filter, firstResult, maxResults, result, orderBy);
            getControlPoint().execute(actionCallback);
        }
        if (preferences.getBoolean(getContext().getString(R.string.settings_browse_thumbnails_coverlookup_chkbx), false)) {
            result = enrichWithCover(result);
        }
        return result;
    }
    */

    /**
     * Search asynchronously for all devices.
     */
    private void searchDevices() {
        if (isInitialized()) {
            getAndroidUpnpService().getControlPoint().search();
        }
    }

    /**
     * Returns all player instances initialized with the given didl object
     *
     * @param didlObject the object which describes the content to be played
     * @return the player
     */
    public List<Player> initializePlayers(DIDLObject didlObject) {
        return initializePlayers(toItemList(didlObject));
    }

    /**
     * Returns all player instances initialized with the given didl object
     *
     * @param items the items to be played
     * @return the player
     */
    public List<Player> initializePlayers(List<Item> items) {
        if (playerService == null) {
            return Collections.emptyList();
        }
        LinkedList<PlayableItem> playableItems = new LinkedList<>();

        for (Item currentItem : items) {
            PlayableItem playableItem = new PlayableItem(currentItem, getDefaultDuration());
            playableItems.add(playableItem);
        }
        SynchronizationInfo synchronizationInfo = new SynchronizationInfo();
        synchronizationInfo.setOffset(getDeviceSyncOffset()); //device specific offset

        Calendar now = Calendar.getInstance(Locale.getDefault());
        now.add(Calendar.MILLISECOND, Integer.parseInt(preferences.getString(getContext().getString(R.string.settings_default_playback_delay_key), "0")));
        String referencedPresentationTime = new SyncOffset(true, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND), now.get(Calendar.MILLISECOND), 0, 0).toString();
        Log.d(getClass().getName(), "CurrentTime: " + new Date() + " representationTime: " + referencedPresentationTime);
        synchronizationInfo.setReferencedPresentationTime(referencedPresentationTime);

        return playerService.createPlayer(this, synchronizationInfo, playableItems);
    }

    /**
     * Returns all player instances initialized with the given transport object
     *
     * @param transport the object which describes the content to be played
     * @return the player
     */
    public List<Player> initializePlayers(AvTransport transport) {
        if (playerService == null) {
            return Collections.emptyList();
        }
        PlayableItem playableItem = new PlayableItem();
        List<PlayableItem> items = new ArrayList<>();
        if (transport == null) {
            return playerService.createPlayer(this, null, items);
        }
        Log.d(getClass().getName(), "TransportId: " + transport.getInstanceId());
        PositionInfo positionInfo = transport.getPositionInfo();
        Log.d(getClass().getName(), "positionInfo: " + positionInfo);
        if (positionInfo == null) {
            return playerService.createPlayer(this, transport.getSynchronizationInfo(), items);
        }
        DIDLContent metadata = null;
        try {
            if (positionInfo.getTrackMetaData() != null && !positionInfo.getTrackMetaData().contains("NOT_IMPLEMENTED")) {
                metadata = new DIDLParser().parse(positionInfo.getTrackMetaData());
            } else {
                Log.d(getClass().getName(), "Warning unparsable TackMetaData: " + positionInfo.getTrackMetaData());
            }
        } catch (Exception e) {
            Log.d(getClass().getName(), "Exception while parsing metadata: ", e);
        }
        String mimeType = "";
        if (metadata != null) {
            List<Item> metadataItems = metadata.getItems();
            for (Item item : metadataItems) {
                playableItem.setTitle(item.getTitle());
                List<Res> metadataResources = item.getResources();
                for (Res res : metadataResources) {
                    if (res.getProtocolInfo() != null) {
                        mimeType = res.getProtocolInfo().getContentFormatMimeType().toString();
                        break;
                    }
                }
                break;
            }
        } else {
            playableItem.setTitle(positionInfo.getTrackURI());
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(positionInfo.getTrackURI());
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
            Log.d(getClass().getName(), "fileextension from trackURI: " + fileExtension);
        }
        playableItem.setMimeType(mimeType);
        playableItem.setUri(Uri.parse(positionInfo.getTrackURI()));
        Log.d(getClass().getName(), "positionInfo.getTrackURI(): " + positionInfo.getTrackURI());
        // FIXME Duration not supported in receiver yet
        // playableItem.setDuration(duration)
        items.add(playableItem);
        Log.d(getClass().getName(), "TransportUri: " + positionInfo.getTrackURI());
        Log.d(getClass().getName(), "Current duration: " + positionInfo.getTrackDuration());
        Log.d(getClass().getName(), "TrackMetaData: " + positionInfo.getTrackMetaData());
        Log.d(getClass().getName(), "MimeType: " + playableItem.getMimeType());
        return playerService.createPlayer(this, transport.getSynchronizationInfo(), items);
    }

    /**
     * Returns all current player instances
     *
     * @return the player
     */
    public Collection<Player> getCurrentPlayers() {
        if (playerService == null) {
            return Collections.emptyList();
        }
        return playerService.getCurrentPlayers();
    }

    /**
     * Returns all current player instances for the given transport object
     *
     * @param transport the object which describes the content to be played
     * @return the player
     */
    public List<Player> getCurrentPlayers(AvTransport transport) {
        if (playerService == null) {
            return Collections.emptyList();
        }
        List<PlayableItem> items = new ArrayList<>();
        if (transport == null) {
            return playerService.createPlayer(this, null, items);
        }
        SynchronizationInfo synchronizationInfo = transport.getSynchronizationInfo();
        synchronizationInfo.setOffset(getDeviceSyncOffset());

        Log.d(getClass().getName(), "TransportId: " + transport.getInstanceId());
        PositionInfo positionInfo = transport.getPositionInfo();
        if (positionInfo == null) {
            return playerService.createPlayer(this, synchronizationInfo, items);
        }
        DIDLContent metadata = null;
        try {
            if (positionInfo.getTrackMetaData() != null) {
                metadata = new DIDLParser().parse(positionInfo.getTrackMetaData());
            }
        } catch (Exception e) {
            Log.d(getClass().getName(), "Exception while parsing metadata: ", e);
        }
        String mimeType = "";
        PlayableItem playableItem = new PlayableItem();
        playableItem.setDuration(getDefaultDuration());
        playableItem.setTitle(positionInfo.getTrackURI());
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(positionInfo.getTrackURI());
        if (metadata != null) {
            List<Item> metadataItems = metadata.getItems();
            for (Item item : metadataItems) {
                playableItem = new PlayableItem(item, getDefaultDuration());
                List<Res> metadataResources = item.getResources();
                for (Res res : metadataResources) {
                    if (res.getProtocolInfo() != null) {
                        mimeType = res.getProtocolInfo().getContentFormatMimeType().toString();
                        break;
                    }
                }
                break;
            }
        }
        if (mimeType.equals("")) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
        }
        playableItem.setMimeType(mimeType);
        playableItem.setUri(Uri.parse(positionInfo.getTrackURI()));
        Log.d(getClass().getName(), "MimeType: " + playableItem.getMimeType());
        return playerService.getCurrentPlayersOfType(playerService.getPlayerClassForMimeType(mimeType), synchronizationInfo);
    }

    /**
     * returns a list of all items including items in containers for the given didlContent
     *
     * @param didlContent the content
     * @return all items included in the content
     **/
    public List<Item> toItemList(DIDLContent didlContent) {
        List<Item> items = new ArrayList<>();
        if (didlContent == null) {
            return items;
        }
        items.addAll(didlContent.getItems());
        for (Container c : didlContent.getContainers()) {
            items.addAll(toItemList(c));
        }
        return items;
    }

    /**
     * Converts the content of a didlObject into a list of cling items.
     *
     * @param didlObject the content
     * @return the list of cling items
     */
    public List<Item> toItemList(DIDLObject didlObject) {
        List<Item> items = new ArrayList<>();
        if (didlObject instanceof Container) {
            DIDLContent content = loadContainer((Container) didlObject);
            if (content != null) {
                items.addAll(content.getItems());
                for (Container includedContainer : content.getContainers()) {
                    items.addAll(toItemList(includedContainer));
                }
            }
        } else if (didlObject instanceof Item) {
            items.add((Item) didlObject);
        }
        return items;
    }

    /**
     * load the content of the container.
     *
     * @param container the container to be loaded
     * @return the loaded content
     */
    private DIDLContent loadContainer(Container container) {
        ContentDirectoryBrowseResult result = browseSync(getProviderDevice(), container.getId());
        if (result.getUpnpFailure() != null) {
            Log.e(getClass().getName(), "Error while loading container:" + result.getUpnpFailure().getDefaultMsg());
            return null;
        }
        return result.getResult();
    }

    /**
     * Gets the receiver IDs, if none is defined the local device will be
     * returned
     *
     * @return the receiverDeviceIds
     */
    public Set<String> getReceiverDeviceIds() {
        HashSet<String> defaultReceiverSet = new HashSet<>();
        defaultReceiverSet.add(UpnpClient.LOCAL_UID);
        return preferences.getStringSet(getContext().getString(R.string.settings_selected_receivers_title), defaultReceiverSet);
    }

    /**
     * Set the list of receiver device ids.
     *
     * @param receiverDeviceIds the device ids.
     */
    private void setReceiverDeviceIds(Set<String> receiverDeviceIds) {
        assert (receiverDeviceIds != null);
        Editor prefEdit = preferences.edit();
        prefEdit.putStringSet(getContext().getString(R.string.settings_selected_receivers_title), receiverDeviceIds);
        prefEdit.apply();
    }

    /**
     * Returns the receiverIds stored in the preferences. If an receiver id is
     * unknown it will be removed.
     *
     * @return the receiverDevices
     */
    public Collection<Device<?, ?, ?>> getReceiverDevices() {
        ArrayList<Device<?, ?, ?>> result = new ArrayList<>();
        ArrayList<String> unknowsIds = new ArrayList<>(); // Maybe the the
        // receiverDevice
        // in the
        // preferences
        // isn't
        // available any
        // more
        Set<String> receiverDeviceIds = getReceiverDeviceIds();
        for (String id : receiverDeviceIds) {
            Device<?, ?, ?> receiver = this.getDevice(id);
            if (receiver != null) {
                result.add(this.getDevice(id));
            } else {
                unknowsIds.add(id);
            }
        }
        // remove all unknown ids
        receiverDeviceIds.removeAll(unknowsIds);
        setReceiverDeviceIds(receiverDeviceIds);
        return result;
    }

    /**
     * set the receiverDevices to the devices in the given collection.
     *
     * @param receiverDevices the devices
     */
    public void setReceiverDevices(Collection<Device<?, ?, ?>> receiverDevices) {
        assert (receiverDevices != null);
        HashSet<String> receiverIds = new HashSet<>();
        for (Device<?, ?, ?> receiver : receiverDevices) {
            Log.d(this.getClass().getName(), "Receiver: " + receiver);
            receiverIds.add(receiver.getIdentity().getUdn().getIdentifierString());
        }
        setReceiverDeviceIds(receiverIds);
    }

    /**
     * add a receiver device
     *
     * @param receiverDevice receiverDevice
     */
    public void addReceiverDevice(Device<?, ?, ?> receiverDevice) {
        assert (receiverDevice != null);
        Collection<Device<?, ?, ?>> receiverDevices = getReceiverDevices();
        receiverDevices.add(receiverDevice);
        setReceiverDevices(receiverDevices);
        fireReceiverDeviceAdded(receiverDevice);
    }

    /**
     * remove a receiver device
     *
     * @param receiverDevice receiverDevice
     */
    public void removeReceiverDevice(Device<?, ?, ?> receiverDevice) {
        assert (receiverDevice != null);
        Collection<Device<?, ?, ?>> receiverDevices = getReceiverDevices();
        receiverDevices.remove(receiverDevice);
        setReceiverDevices(receiverDevices);
        fireReceiverDeviceRemoved(receiverDevice);
    }

    /**
     * @return the providerDeviceId
     */
    public String getProviderDeviceId() {
        if (getContext() != null) {
            return preferences.getString(getContext().getString(R.string.settings_selected_provider_title), null);
        }
        return "";
    }

    /**
     * @return the provider device
     */
    public Device<?, ?, ?> getProviderDevice() {
        return this.getDevice(getProviderDeviceId());
    }


    public void setProviderDevice(Device<?, ?, ?> provider) {
        Editor prefEdit = preferences.edit();
        prefEdit.putString(getContext().getString(R.string.settings_selected_provider_title), provider.getIdentity().getUdn().getIdentifierString());
        prefEdit.apply();

    }

    /**
     * Shutdown the upnp client and all players
     */
    public void shutdown() {
        // shutdown UpnpRegistry
        boolean result = getContext().stopService(new Intent(getContext(), UpnpRegistryService.class));
        Log.d(getClass().getName(), "Stopping UpnpRegistryService succsessful= " + result);
        // shutdown yaacc server service
        result = getContext().stopService(new Intent(getContext(), YaaccUpnpServerService.class));
        Log.d(getClass().getName(), "Stopping YaaccUpnpServerService succsessful= " + result);
        // stop all players
        if (playerService != null) {
            playerService.shutdown();
        }
        result = getContext().stopService(new Intent(getContext(), PlayerService.class));
        Log.d(getClass().getName(), "Stopping PlayerService succsessful= " + result);

    }

    /**
     * Return the configured default duration
     *
     * @return the duration
     */
    public int getDefaultDuration() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return Integer.parseInt(preferences.getString(getContext().getString(R.string.settings_default_duration_key), "0"));
    }

    /**
     * Return the configured silence duration
     *
     * @return the duration
     */
    public int getSilenceDuration() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        return Integer.parseInt(preferences.getString(getContext().getString(R.string.settings_silence_duration_key), "2000"));
    }

    private Device<?, ?, ?> getLocalDummyDevice() {
        Device<?, ?, ?> result = null;
        try {
            result = new LocalDummyDevice();
        } catch (ValidationException e) {
            // Ignore
            Log.d(this.getClass().getName(), "Something wrong with the LocalDummyDevice...", e);
        }
        return result;
    }

    /**
     * returns the mute state
     *
     * @return the state
     */
    public boolean isMute() {
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            return audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        }
        return false;
    }

    /**
     * set the mute state
     *
     * @param mute the state
     */
    public void setMute(boolean mute) {
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        }
    }

    /**
     * returns the current volume level
     *
     * @return the value in the range of 0-100
     */
    public int getVolume() {

        int volume = 0;
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            volume = currentVolume * 100 / maxVolume;
        }
        return volume;

    }

    /**
     * set the volume in the range of 0-100
     *
     * @param desired volume
     */
    public void setVolume(int desired) {
        if (desired < 0) {
            desired = 0;
        }
        if (desired > 100) {
            desired = 100;
        }
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int volume = desired * maxVolume / 100;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, AudioManager.FLAG_SHOW_UI);
        }
    }

    public void downloadItem(DIDLObject selectedDIDLObject) {
        new FileDownloader(this).execute(selectedDIDLObject);
    }


    public List<Player> initializePlayersWithPlayableItems(List<PlayableItem> items) {
        if (playerService == null) {
            return Collections.emptyList();
        }
        SynchronizationInfo synchronizationInfo = new SynchronizationInfo();
        synchronizationInfo.setOffset(getDeviceSyncOffset()); //device specific offset

        Calendar now = Calendar.getInstance(Locale.getDefault());
        now.add(Calendar.MILLISECOND, Integer.valueOf(preferences.getString(getContext().getString(R.string.settings_default_playback_delay_key), "0")));
        String referencedPresentationTime = new SyncOffset(true, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND), now.get(Calendar.MILLISECOND), 0, 0).toString();
        Log.d(getClass().getName(), "CurrentTime: " + new Date().toString() + " representationTime: " + referencedPresentationTime);
        synchronizationInfo.setReferencedPresentationTime(referencedPresentationTime);

        return playerService.createPlayer(this, synchronizationInfo, items);
    }

    public boolean getMute(Device<?, ?, ?> device) {
        if (device == null) {
            return false;
        }
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            return isMute();
        }
        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + device.getDisplayString());
            return false;
        }
        Log.d(getClass().getName(), "Action get Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetMute actionCallback = new GetMute(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }

            @Override
            public void received(ActionInvocation actionInvocation, boolean currentMute) {
                actionState.result = currentMute;

            }
        };
        getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();
        //FIXME really best way to solve it?
        int i = 0;
        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
            i++;
            if (i == 100000) {
                Log.d(getClass().getName(), "wait for action finished ");
                i = 0;
            }
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(), "Timeout occurred");
        } else {
            watchdog.cancel();
        }
        return actionState.result != null && (Boolean) actionState.result;
    }

    public void setMute(Device<?, ?, ?> device, boolean mute) {
        if (device == null) {
            return;
        }
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            setMute(mute);
        }
        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            Log.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + device.getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Mute ");
        SetMute actionCallback = new SetMute(service, mute) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
            }
        };
        getControlPoint().execute(actionCallback);
    }

    public int getVolume(Device<?, ?, ?> device) {
        if (device == null) {
            return 0;
        }
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            return getVolume();
        }

        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            Log.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return 0;
        }
        Log.d(getClass().getName(), "Action get Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetVolume actionCallback = new GetVolume(service) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                actionState.actionFinished = true;
            }

            @Override
            public void received(ActionInvocation actionInvocation, int currentVolume) {
                actionState.result = currentVolume;

            }
        };

        getControlPoint().execute(actionCallback);
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();
        int i = 0;
        //FIXME analyze if this code is really the best way to solve it...
        while (!actionState.actionFinished && !watchdog.hasTimeout()) {
            //active wait
            i++;
            if (i == 100000) {
                Log.d(getClass().getName(), "wait for action finished ");
                i = 0;
            }
        }
        if (watchdog.hasTimeout()) {
            Log.d(getClass().getName(), "Timeout occurred");
        } else {
            watchdog.cancel();
        }
        return actionState.result == null ? 0 : (Integer) actionState.result;

    }

    public void setVolume(Device<?, ?, ?> device, int volume) {
        if (device == null) {
            return;
        }
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            setVolume(volume);
        }

        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            Log.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return;
        }
        Log.d(getClass().getName(), "Action set Volume ");
        SetVolume actionCallback = new SetVolume(service, volume) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                Log.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                Log.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                Log.d(getClass().getName(), "s: " + s);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
            }
        };
        getControlPoint().execute(actionCallback);
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    public static class LocalDummyDevice extends Device {
        LocalDummyDevice() throws ValidationException {
            super(new DeviceIdentity(new UDN(LOCAL_UID)));
        }

        @Override
        public Service[] getServices() {
            return null;
        }

        @Override
        public Device[] getEmbeddedDevices() {
            return null;
        }

        @Override
        public Device getRoot() {
            return null;
        }

        @Override
        public Device findDevice(UDN udn) {
            return null;
        }

        @Override
        public Resource[] discoverResources(Namespace namespace) {
            return null;
        }

        @Override
        public Device newInstance(UDN arg0, UDAVersion arg1, DeviceType arg2, DeviceDetails arg3, Icon[] arg4, Service[] arg5, List arg6)
                throws ValidationException {
            return null;
        }

        @Override
        public Service newInstance(ServiceType servicetype, ServiceId serviceid, URI uri, URI uri1, URI uri2, Action[] aaction,
                                   StateVariable[] astatevariable) throws ValidationException {
            return null;
        }

        @Override
        public Device[] toDeviceArray(Collection collection) {
            return null;
        }

        @Override
        public Service[] newServiceArray(int i) {
            return null;
        }

        @Override
        public Service[] toServiceArray(Collection collection) {
            return null;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.fourthline.cling.model.meta.Device#getDisplayString()
         */
        @Override
        public String getDisplayString() {
            return android.os.Build.MODEL;
        }

        @Override
        public DeviceDetails getDetails() {
            return new DeviceDetails(android.os.Build.MODEL);
        }


    }
}
