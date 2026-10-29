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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.MXHeader;
import org.fourthline.cling.model.message.header.STAllHeader;
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
import org.fourthline.cling.support.contentdirectory.DIDLParser;
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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.yaacc.share.ShareLogRedactor;
import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.browser.Position;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.player.PlaylistTargets;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.player.PlayerService;
import de.yaacc.player.ShareLifetimeListener;
import de.yaacc.player.YaaccMediaRouteProvider;
import de.yaacc.share.HttpLinkTarget;
import de.yaacc.share.LinkContentType;
import de.yaacc.share.LinkTargetPolicy;
import de.yaacc.share.ReadinessWait;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareKeyTracker;
import de.yaacc.share.ShareService;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.UnclaimedShares;
import de.yaacc.upnp.callback.contentdirectory.Browse.Status;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseActionCallback;
import de.yaacc.upnp.callback.contentdirectory.ContentDirectoryBrowseResult;
import de.yaacc.upnp.callback.renderingcontrol.GetMute;
import de.yaacc.upnp.callback.renderingcontrol.GetVolume;
import de.yaacc.upnp.callback.renderingcontrol.SetMute;
import de.yaacc.upnp.callback.renderingcontrol.SetVolume;
import de.yaacc.upnp.protocol.async.SendingSearch;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.registry.RegistryListener;
import de.yaacc.upnp.server.NetworkDeviceListener;
import de.yaacc.upnp.server.NetworkRecoveryListener;
import de.yaacc.upnp.server.NetworkRecoveryNotifier;
import de.yaacc.upnp.server.ResumeRefreshPolicy;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.upnp.server.avtransport.AvTransport;
import de.yaacc.upnp.server.http.HttpRequestSender;
import de.yaacc.upnp.server.udp.UdpTransiver;
import de.yaacc.share.HttpServerProbe;
import de.yaacc.share.ShareAddressResolver;
import de.yaacc.share.SharedItemFactory;
import de.yaacc.util.FileDownloader;
import de.yaacc.util.InterfaceResolutionHelper;
import de.yaacc.util.Watchdog;
import de.yaacc.util.YaaccLogger;

/**
 * A client facade to the upnp lookup and access framework. This class provides
 * all services to manage devices.
 *
 * @author Tobias Schoene (TheOpenBit)
 */
public class UpnpClient implements RegistryListener, ServiceConnection {
    public static String LOCAL_UID = "LOCAL_UID";
    private final List<UpnpClientListener> listeners = new CopyOnWriteArrayList<>();
    // Kept here as well as in PlayerService, so a listener registered before the
    // player service is bound (or after it was rebound) still gets attached.
    private final List<PlayerListChangeNotifier.Listener> playerListChangeListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executorService;
    // Lives as long as the client, so a screen can register before the server service is bound
    // (which is when the service's NetworkDeviceListener does not exist yet). The service chains
    // this notifier behind its NetworkDeviceListener.
    private final NetworkRecoveryNotifier networkRecoveryNotifier = new NetworkRecoveryNotifier();
    SharedPreferences preferences;
    // Time (SystemClock.elapsedRealtime) of the last M-SEARCH; the resume check does not repeat a recent one.
    volatile long lastSearchAtMillis = ResumeRefreshPolicy.NEVER;
    // How long a play action waits for a selected receiver that is not in the registry right now.
    ReceiverAvailabilityPolicy receiverAvailabilityPolicy = new ReceiverAvailabilityPolicy();

    private Context context;

    private PlayerService playerService;
    private Device<?, ?, ?> localDummyDevice;
    private YaaccUpnpServerService yaaccUpnpServerService;
    private YaaccMediaRouteProvider mediaRouteProvider;


    public UpnpClient() {
        executorService = Executors.newFixedThreadPool(20);
    }

    public UpnpClient(Context context) {
        this();
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
            addPlayerListChangeListener(shareLifetimeListener);

            // Initialize MediaRouteProvider for UPnP devices
            mediaRouteProvider = new YaaccMediaRouteProvider(context, this);
            androidx.mediarouter.media.MediaRouter.getInstance(context)
                    .addProvider(mediaRouteProvider);

            // FIXME check if this is right: Context.BIND_AUTO_CREATE kills the
            // service after closing the activity
            return context.bindService(new Intent(context, YaaccUpnpServerService.class), this, Context.BIND_AUTO_CREATE);
        }
        return false;
    }

    public boolean startService() {
        if (playerService == null) {
            ((Yaacc) getContext().getApplicationContext()).createYaaccGroupNotification();
            getContext().startForegroundService(new Intent(getContext(), PlayerService.class));
            return getContext().bindService(new Intent(getContext(), PlayerService.class),
                    this, Context.BIND_AUTO_CREATE);
        }
        return true;
    }

    private void fireReceiverDeviceAdded(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : new ArrayList<>(listeners)) {
            listener.receiverDeviceAdded(device);
        }
    }

    private void fireReceiverDeviceRemoved(Device<?, ?, ?> device) {
        for (UpnpClientListener listener : new ArrayList<>(listeners)) {
            listener.receiverDeviceRemoved(device);
        }
    }

    private void deviceAdded(@SuppressWarnings("rawtypes") final Device device) {
        fireDeviceAdded(device);
        // Refresh MediaRouter routes when devices change
        if (mediaRouteProvider != null) {
            mediaRouteProvider.refreshRoutes();
        }
    }

    private void deviceRemoved(@SuppressWarnings("rawtypes") final Device device) {
        fireDeviceRemoved(device);
        // Refresh MediaRouter routes when devices change
        if (mediaRouteProvider != null) {
            mediaRouteProvider.refreshRoutes();
        }
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
        if (service instanceof YaaccUpnpServerService.YaaccUpnpServerServiceBinder) {
            yaaccUpnpServerService = ((YaaccUpnpServerService.YaaccUpnpServerServiceBinder) service).getService();
            if (yaaccUpnpServerService.isInitialized()) {
                yaaccUpnpServerService.getRegistry().addListener(this);
            }
            refreshUpnpDeviceCatalog();
        }
        if (service instanceof PlayerService.PlayerServiceBinder) {
            playerService = ((PlayerService.PlayerServiceBinder) service).getService();
            YaaccLogger.e(getClass().getName(), "player service bounded");
            for (PlayerListChangeNotifier.Listener listener : playerListChangeListeners) {
                playerService.addPlayerListChangeListener(listener);
            }

        }

    }

    public YaaccUpnpServerService getYaaccUpnpServerService() {
        return yaaccUpnpServerService;
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
        YaaccLogger.d(getClass().getName(), "on Service disconnect: " + componentName);
        if (YaaccUpnpServerService.class.getName().equals(componentName.getClassName())) {
            yaaccUpnpServerService = null;
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
        YaaccLogger.v(getClass().getName(), "remoteDeviceDiscoveryFailed: " + remotedevice.getDisplayString(), exception);
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
        YaaccLogger.v(getClass().getName(), "remoteDeviceAdded: " + remotedevice.getDisplayString());
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
        YaaccLogger.v(getClass().getName(), "remoteDeviceUpdated: " + remotedevice.getDisplayString());
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
        YaaccLogger.v(getClass().getName(), "remoteDeviceRemoved: " + remotedevice.getDisplayString());
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
        YaaccLogger.v(getClass().getName(), "localDeviceAdded: " + localdevice.getDisplayString());
        if(this.getRegistry() != null){
            this.getRegistry().addDevice(localdevice);
            this.deviceAdded(localdevice);
        }
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
            YaaccLogger.v(getClass().getName(), "localDeviceRemoved: " + localdevice.getDisplayString());
            this.deviceRemoved(localdevice);
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
        YaaccLogger.v(getClass().getName(), "beforeShutdown: " + registry);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.fourthline.cling.registry.RegistryListener#afterShutdown()
     */
    @Override
    public void afterShutdown() {
        YaaccLogger.v(getClass().getName(), "afterShutdown ");
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
            YaaccLogger.d(getClass().getName(), "Device is null!");
            return null;
        }
        ServiceId serviceId = new UDAServiceId("AVTransport");
        Service<?, ?> service = device.findService(serviceId);
        if (service != null) {
            YaaccLogger.d(getClass().getName(), "Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
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
            YaaccLogger.d(getClass().getName(), "Device is null!");
            return null;
        }
        ServiceId serviceId = new UDAServiceId("RenderingControl");
        Service<?, ?> service = device.findService(serviceId);
        if (service != null) {
            YaaccLogger.d(getClass().getName(), "Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
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
     * Removes a listener added with {@link #addUpnpClientListener(UpnpClientListener)}, so a
     * destroyed screen is no longer called. Removes one registration; does nothing if the
     * listener is not registered.
     *
     * @param listener the listener to be removed
     */
    public void removeUpnpClientListener(UpnpClientListener listener) {
        listeners.remove(listener);
    }


    /**
     * Returns all registered UpnpDevices.
     *
     * @return the upnpDevices
     */
    public Collection<Device<?, ?, ?>> getDevices() {
        if (isInitialized()) {
            return sortDevices(getRegistry().getDevices());
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
            return sortDevices(getRegistry().getDevices(new UDAServiceType("ContentDirectory")));
        }
        return new ArrayList<>();
    }

    /**
     * Returns all registered UpnpDevices with an AVTransport Service.
     *
     * @return the upnpDevices
     */
    public Collection<Device<?, ?, ?>> getDevicesProvidingAvTransportService() {
        List<Device<?, ?, ?>> result = new ArrayList<>();
        if (isInitialized()) {
            result.addAll(getRegistry().getDevices(new UDAServiceType("AVTransport")));
        }
        result = sortDevices(result);
        Collections.reverse(result);
        result.add(getLocalDummyDevice());
        Collections.reverse(result);
        return result;
    }

    private List<Device<?, ?, ?>> sortDevices(Collection<Device<?, ?, ?>> devices) {
        return devices.stream().sorted(Comparator.comparing(it -> it.getDetails().getFriendlyName(), String.CASE_INSENSITIVE_ORDER)).collect(Collectors.toList());
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
            // Normalize UDN by stripping uuid: prefix if present
            UDN udn = UDN.valueOf(identifier);
            Device<?, ?, ?> device = getRegistry().getDevice(udn, false);
            YaaccLogger.d(getClass().getName(), "getDevice() identifier='" + identifier + "' -> UDN='" + udn.getIdentifierString() + "' found=" + (device != null));
            return device;
        }
        return null;
    }

    /**
     * True if the client is initialized.
     *
     * @return true or false
     */
    public boolean isInitialized() {
        return yaaccUpnpServerService != null && yaaccUpnpServerService.isInitialized();
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
        if (!yaaccUpnpServerService.getRegistry().getListeners().contains(this)) {
            yaaccUpnpServerService.getRegistry().addListener(this);
        }
        return yaaccUpnpServerService.getRegistry();
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
            for (Device<?, ?, ?> device : getRegistry().getDevices()) {
                // FIXME: What about removed devices?
                this.deviceAdded(device);
            }
            // Getting ready for future device advertisements
            getRegistry().addListener(this);
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

    public ContentDirectoryBrowseResult browseSync(Position pos, Long firstResult, Long maxResult, SortCriterion... orderBy) {
        if (getProviderDevice() == null) {
            return null;
        }
        if (pos == null || pos.getDeviceId() == null) {
            if (getProviderDevice() != null) {
                return browseSync(getProviderDevice(), "0", BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult, orderBy);
            } else {
                return null;
            }
        }
        if (getProviderDevice() != null && !pos.getDeviceId().equals(getProviderDevice().getIdentity().getUdn().getIdentifierString())) {
            return browseSync(getProviderDevice(), "0", BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult, orderBy);
        }
        return browseSync(getDevice(pos.getDeviceId()), pos.getObjectId(), BrowseFlag.DIRECT_CHILDREN, "*", firstResult, maxResult, orderBy);
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
            // e.g. the device dropped out of the registry while the network was away; not an empty folder
            result.setUpnpFailure(new UpnpFailure(null, null, "Device not available", true));
            return result;
        }
        Service<?, ?> service = device.findService(new UDAServiceId("ContentDirectory"));
        if (service == null) {
            result.setUpnpFailure(new UpnpFailure(null, null, "Device has no ContentDirectory service", false));
            return result;
        }
        HttpRequestSender httpRequestSender = getHttpRequestSender();
        if (httpRequestSender == null) {
            // no server service bound, no listener yet or no network came up so far
            YaaccLogger.w(getClass().getName(), "No HTTP request sender available, browse not possible right now");
            result.setUpnpFailure(new UpnpFailure(null, null, "No HTTP request sender available", true));
            return result;
        }
        YaaccLogger.d(getClass().getName(), "#####Service found: " + service.getServiceId() + " Type: " + service.getServiceType());
        final ContentDirectoryBrowseActionCallback actionCallback = new ContentDirectoryBrowseActionCallback(service, objectID, flag, filter, firstResult, maxResults, result, httpRequestSender, orderBy);
        executorService.execute(() -> {
            try {
                actionCallback.run();
            } catch (RuntimeException e) {
                // without this the pool thread dies and the waiting loop below never ends
                YaaccLogger.e(getClass().getName(), "Browse action failed unexpectedly", e);
                result.setUpnpFailure(new UpnpFailure(actionCallback.getActionInvocation(), null, String.valueOf(e), false));
            }
        });
        while (actionCallback.getStatus() == Status.LOADING && actionCallback.getUpnpFailure() == null) {
            //FIXME implement maybe async model?
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                // the load was cancelled: stop waiting and keep the interrupt for the caller
                Thread.currentThread().interrupt();
                result.setUpnpFailure(new UpnpFailure(actionCallback.getActionInvocation(), null, "Interrupted", false));
                break;
            }
        }
        if (actionCallback.getUpnpFailure() != null) {
            YaaccLogger.e(getClass().getName(), "UPnP failure: " + actionCallback.getUpnpFailure());
        }

        if (preferences.getBoolean(getContext().getString(R.string.settings_browse_thumbnails_coverlookup_chkbx), false)) {
            enrichWithCover(result);
        }
        return result;
    }

    /**
     * @return the sender of the server service's network listener, or null when there is none (yet):
     * no service bound, the listener not created or no network came up since the start
     */
    private HttpRequestSender getHttpRequestSender() {
        YaaccUpnpServerService serverService = yaaccUpnpServerService;
        if (serverService == null) {
            return null;
        }
        NetworkDeviceListener networkDeviceListener = serverService.getNetworkDeviceListener();
        return networkDeviceListener == null ? null : networkDeviceListener.getHttpRequestSender();
    }

    /**
     * Registers for "the network is back" (see {@link NetworkRecoveryListener}). Held weakly: the
     * caller keeps the listener and calls {@link #removeNetworkRecoveryListener} when it stops.
     */
    public void addNetworkRecoveryListener(NetworkRecoveryListener listener) {
        networkRecoveryNotifier.add(listener);
    }

    public void removeNetworkRecoveryListener(NetworkRecoveryListener listener) {
        networkRecoveryNotifier.remove(listener);
    }

    /**
     * @return the listener the server service registers at its network listener, it passes the
     * "network is back" on to the listeners added with {@link #addNetworkRecoveryListener}
     */
    public NetworkRecoveryListener getNetworkRecoveryRelay() {
        return networkRecoveryNotifier;
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
        if (!cont.getContainers().isEmpty()) {
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

    /**
     * Search asynchronously for all devices.
     */
    public void searchDevices() {
        if (isInitialized()) {
            // the listener may be disabled between the check and here: its transceiver is gone then
            UdpTransiver transiver = yaaccUpnpServerService.getNetworkDeviceListener().getUdpTransiver();
            if (transiver == null) {
                YaaccLogger.d(getClass().getName(), "Search skipped: no UDP transceiver (network listener is disabled)");
                return;
            }
            lastSearchAtMillis = nowMillis();
            executorService.execute(new SendingSearch(transiver, new STAllHeader(), MXHeader.DEFAULT_VALUE));
        }
    }

    /** Monotonic clock that also counts the time the device slept; overridable for tests. */
    long nowMillis() {
        return SystemClock.elapsedRealtime();
    }

    /** False in plain JVM tests (no main looper there). */
    boolean isOnMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && main == Looper.myLooper();
    }

    /**
     * A browser screen came back to the foreground, maybe after the device or the app was suspended
     * (screen off, Doze, a long time in the background): checks the network listener and searches
     * the devices again if the last search is not recent. Does its work on the client's executor and
     * returns at once.
     */
    public void refreshAfterResume() {
        executorService.execute(this::refreshAfterResumeNow);
    }

    /** The work of {@link #refreshAfterResume()}; worker thread only. */
    void refreshAfterResumeNow() {
        try {
            ResumeRefreshPolicy.ListenerCheck check = reenableNetworkListenerIfStale();
            if (check == null) {
                // the service starts its own discovery once the listener is up
                YaaccLogger.d(getClass().getName(), "Resume: no network listener yet, nothing to refresh");
                return;
            }
            boolean search = ResumeRefreshPolicy.shouldSearch(check.needsReenable(), lastSearchAtMillis, nowMillis());
            long ageSeconds = lastSearchAtMillis < 0 ? -1 : (nowMillis() - lastSearchAtMillis) / 1000;
            YaaccLogger.d(getClass().getName(), "Resume: network listener " + check + (check.needsReenable() ? " (enabled again)" : "") + ", "
                    + (search ? "searching devices" : "not searching, the last search was " + ageSeconds + " s ago"));
            if (search) {
                searchDevices();
            }
        } catch (RuntimeException e) {
            // a failed refresh must never take the screen down; the network callbacks remain
            YaaccLogger.w(getClass().getName(), "Resume: refresh failed (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * @return what the server service's network listener found about itself (and fixed if needed),
     * null if there is no listener yet
     */
    private ResumeRefreshPolicy.ListenerCheck reenableNetworkListenerIfStale() {
        YaaccUpnpServerService serverService = yaaccUpnpServerService;
        NetworkDeviceListener listener = serverService == null ? null : serverService.getNetworkDeviceListener();
        return listener == null ? null : listener.reenableIfStale();
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
        if (playerService == null && !startService()) {
            return Collections.emptyList();
        }
        if (!waitForPlayerServiceComeUp()) {
            return Collections.emptyList();
        }
        List<Player> players = playerService.createPlayer(this, toPlayableItems(items));
        rememberShares(players);
        return players;
    }

    @NonNull
    private LinkedList<PlayableItem> toPlayableItems(List<Item> items) {
        LinkedList<PlayableItem> playableItems = new LinkedList<>();
        for (Item currentItem : items) {
            PlayableItem playableItem = new PlayableItem(currentItem, getDefaultDuration());
            playableItems.add(playableItem);
        }
        return playableItems;
    }

    private boolean waitForPlayerServiceComeUp() {
        Watchdog watchdog = Watchdog.createWatchdog(10000L);
        watchdog.start();
        int i = 0;
        while (playerService == null && !watchdog.hasTimeout()) {
            //active wait
            i++;
            if (i == 100000) {
                YaaccLogger.d(getClass().getName(), "wait for player service start");
                i = 0;
            }
        }
        if (watchdog.hasTimeout()) {
            YaaccLogger.d(getClass().getName(), "Timeout occurred");
            return false;
        } else {
            watchdog.cancel();
        }
        return true;
    }

    /**
     * Returns all player instances initialized with the given transport object
     *
     * @param transport the object which describes the content to be played
     * @return the player
     */
    public List<Player> initializePlayers(AvTransport transport) {
        if (playerService == null && !startService()) {
            return Collections.emptyList();
        }
        if (!waitForPlayerServiceComeUp()) {
            return Collections.emptyList();
        }
        PlayableItem playableItem = new PlayableItem();
        List<PlayableItem> items = new ArrayList<>();
        if (transport == null) {
            return playerService.createPlayerForLocalDevice(this, items);
        }
        YaaccLogger.d(getClass().getName(), "TransportId: " + transport.getInstanceId());
        PositionInfo positionInfo = transport.getPositionInfo();
        YaaccLogger.d(getClass().getName(), "positionInfo: " + ShareLogRedactor.redact(String.valueOf(positionInfo)));
        if (positionInfo == null) {
            return playerService.createPlayerForLocalDevice(this, items);
        }
        DIDLContent metadata = null;
        try {
            if (positionInfo.getTrackMetaData() != null && !positionInfo.getTrackMetaData().contains("NOT_IMPLEMENTED")) {
                metadata = new DIDLParser().parse(positionInfo.getTrackMetaData());
            } else {
                YaaccLogger.d(getClass().getName(), "Warning unparsable TackMetaData: " + ShareLogRedactor.redact(String.valueOf(positionInfo.getTrackMetaData())));
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "Exception while parsing metadata: ", e);
        }
        String mimeType = "";
        if (metadata != null) {
            List<Item> metadataItems = metadata.getItems();
            for (Item item : metadataItems) {
                playableItem.setTitle(item.getTitle());
                playableItem.setItem(item); // Store the DIDL item for album art and other metadata
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
            YaaccLogger.d(getClass().getName(), "fileextension from trackURI: " + ShareLogRedactor.redact(String.valueOf(fileExtension)));
        }
        playableItem.setMimeType(mimeType);
        playableItem.setUri(Uri.parse(positionInfo.getTrackURI()));
        // FIXME Duration not supported in receiver yet
        // playableItem.setDuration(duration)
        items.add(playableItem);
        YaaccLogger.d(getClass().getName(), "Current duration: " + positionInfo.getTrackDuration());
        YaaccLogger.d(getClass().getName(), "MimeType: " + playableItem.getMimeType());
        return playerService.createPlayerForLocalDevice(this, items);
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
     * Registers a listener for players being added to or removed from the
     * player service; see {@link PlayerService#addPlayerListChangeListener}.
     * Works before the service is bound: the listener is attached once it is.
     * Callers must call {@link #removePlayerListChangeListener} when done.
     */
    public void addPlayerListChangeListener(PlayerListChangeNotifier.Listener listener) {
        if (listener == null || playerListChangeListeners.contains(listener)) {
            return;
        }
        playerListChangeListeners.add(listener);
        PlayerService service = playerService;
        if (service != null) {
            service.addPlayerListChangeListener(listener);
        }
    }

    public void removePlayerListChangeListener(PlayerListChangeNotifier.Listener listener) {
        playerListChangeListeners.remove(listener);
        PlayerService service = playerService;
        if (service != null) {
            service.removePlayerListChangeListener(listener);
        }
    }

    /**
     * Creation sequence of an active player (higher = added later), or 0 if the
     * player isn't active or the player service isn't bound.
     */
    public long getPlayerCreationSequence(Player player) {
        if (playerService == null) {
            return 0;
        }
        return playerService.getPlayerCreationSequence(player);
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

        if (transport == null) {
            return Collections.emptyList();
        }

        YaaccLogger.d(getClass().getName(), "TransportId: " + transport.getInstanceId());
        PositionInfo positionInfo = transport.getPositionInfo();
        if (positionInfo == null) {
            return Collections.emptyList();
        }
        DIDLContent metadata = null;
        try {
            if (positionInfo.getTrackMetaData() != null) {
                metadata = new DIDLParser().parse(positionInfo.getTrackMetaData());
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "Exception while parsing metadata: ", e);
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
        if (mimeType.isEmpty()) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
        }
        playableItem.setMimeType(mimeType);
        playableItem.setUri(Uri.parse(positionInfo.getTrackURI()));
        YaaccLogger.d(getClass().getName(), "MimeType: " + playableItem.getMimeType());
        return playerService.getCurrentPlayersOfType(playerService.getPlayerClassForMimeType(mimeType));
    }

    /**
     * returns a list of all items including items in containers for the given didlContent
     *
     * @param didlContent the content
     * @return all items included in the content
     **/
    public List<Item> toItemList(DIDLContent didlContent) {

        if (didlContent == null) {
            return new ArrayList<>();
        }
        List<Item> items = new ArrayList<>();
        items.addAll(didlContent.getItems());
        int itemCount = items.size();
        for (Container c : didlContent.getContainers()) {
            List<Item> containerItems = toItemList(c, itemCount);
            itemCount += containerItems.size();
            items.addAll(containerItems);
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
        return toItemList(didlObject, 0);
    }

    private List<Item> toItemList(DIDLObject didlObject, int currentResultSize) {
        if (currentResultSize >= Integer.parseInt(getPreferences().getString(getContext().getString(R.string.settings_browse_max_results_key), "1000"))) {
            return new ArrayList<>();
        }
        List<Item> items = new ArrayList<>();
        if (didlObject instanceof Container) {
            DIDLContent content = loadContainer((Container) didlObject);
            if (content != null) {
                items.addAll(content.getItems());
                int itemCount = currentResultSize + items.size();
                if (itemCount >= Integer.parseInt(getPreferences().getString(getContext().getString(R.string.settings_browse_max_results_key), "1000"))) {
                    return items;
                }
                for (Container includedContainer : content.getContainers()) {
                    List<Item> containerItems = toItemList(includedContainer, itemCount);
                    itemCount += containerItems.size();
                    items.addAll(containerItems);
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
            YaaccLogger.e(getClass().getName(), "Error while loading container:" + result.getUpnpFailure().getDefaultMsg());
            return null;
        }
        return result.getResult();
    }

    /**
     * Gets the receiver IDs, if none is defined the local device will be
     * returned
     *
     * @return the receiverDeviceIds, a copy the caller may modify (the set that
     * {@code SharedPreferences.getStringSet} returns must never be changed); an empty stored
     * set stays empty
     */
    public Set<String> getReceiverDeviceIds() {
        HashSet<String> defaultReceiverSet = new HashSet<>();
        defaultReceiverSet.add(UpnpClient.LOCAL_UID);
        Set<String> stored = preferences.getStringSet(getContext().getString(R.string.settings_selected_receivers_title), defaultReceiverSet);
        // a copy: the set returned by getStringSet must never be modified (it may be the very object
        // the preferences keep); an empty stored set stays empty, it is not replaced by a default
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    /**
     * Set the list of receiver device ids.
     *
     * @param receiverDeviceIds the device ids.
     */
    public void setReceiverDeviceIds(Set<String> receiverDeviceIds) {
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
        ArrayList<String> unknowsIds = new ArrayList<>();
        Set<String> receiverDeviceIds = getReceiverDeviceIds();
        for (String id : receiverDeviceIds) {
            Device<?, ?, ?> receiver = this.getDevice(id);
            if (receiver != null) {
                result.add(receiver);
            } else {
                unknowsIds.add(id);
            }
        }
        // remove all unknown ids (from the copy above: the stored set is never changed in place)
        if (!unknowsIds.isEmpty()) {
            receiverDeviceIds.removeAll(unknowsIds);
            setReceiverDeviceIds(receiverDeviceIds);
        }
        return result;
    }

    /**
     * Like {@link #getReceiverDevices()}, but first gives a selected receiver that is not in the
     * registry right now a few seconds to come back ({@link ReceiverAvailabilityPolicy}: three looks,
     * 2.5 s apart, a search before each wait). After a suspend the registry may have dropped the
     * renderer; {@link #getReceiverDevices()} would remove it from the stored selection for good.
     * What is still missing after the last look is dropped as before.
     * <p>
     * Waits, so worker threads only; on the main thread it does not wait.
     */
    public Collection<Device<?, ?, ?>> awaitReceiverDevices() {
        if (isOnMainThread()) {
            return getReceiverDevices();
        }
        int missingAtFirstLook = countMissingReceivers();
        int missing = receiverAvailabilityPolicy.await(this::countMissingReceivers, attempt -> {
            YaaccLogger.d(getClass().getName(), "Resume: " + countMissingReceivers() + " selected receiver(s) not in the registry, searching (look "
                    + attempt + " of " + ReceiverAvailabilityPolicy.MAX_ATTEMPTS + ")");
            reenableNetworkListenerIfStale();
            searchDevices();
        });
        if (missingAtFirstLook > 0) {
            YaaccLogger.d(getClass().getName(), missing == 0
                    ? "Resume: the selected receiver(s) are back, playing"
                    : "Resume: " + missing + " selected receiver(s) still not in the registry, giving up");
        }
        return getReceiverDevices();
    }

    private int countMissingReceivers() {
        int missing = 0;
        for (String id : getReceiverDeviceIds()) {
            if (!LOCAL_UID.equals(id) && getDevice(id) == null) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * Clears the receiver selection: the stored set becomes empty (and stays empty, it is not
     * replaced by the local device) and a "receiver removed" event is fired for every receiver
     * that was selected, so views that follow the selection refresh. Does nothing to running
     * players or to the allowed set of an existing share (that was fixed when the share was
     * registered).
     */
    public void clearReceiverDevices() {
        Collection<Device<?, ?, ?>> previous = getReceiverDevices();
        setReceiverDeviceIds(new HashSet<>());
        for (Device<?, ?, ?> device : previous) {
            fireReceiverDeviceRemoved(device);
        }
    }

    public int getReceiverDevicesReadyCount() {
        int result = 0;
        Set<String> receiverDeviceIds = getReceiverDeviceIds();
        for (String id : receiverDeviceIds) {
            Device<?, ?, ?> receiver = this.getDevice(id);
            if (receiver != null) {
                result++;
            }
        }
        return result;
    }

    /**
     * set the receiverDevices to the devices in the given collection.
     *
     * @param receiverDevices the devices
     */
    public void setReceiverDevices(Collection<Device<?, ?, ?>> receiverDevices) {
        if (receiverDevices == null) return;
        HashSet<String> receiverIds = new HashSet<>();
        for (Device<?, ?, ?> receiver : receiverDevices) {
            YaaccLogger.d(this.getClass().getName(), "Receiver: " + receiver);
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
        if (getContext() != null && preferences.contains(getContext().getString(R.string.settings_selected_provider_title))) {
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
        boolean result = getContext().stopService(new Intent(getContext(), YaaccUpnpServerService.class));
        YaaccLogger.d(getClass().getName(), "Stopping YaaccUpnpServerService succsessful= " + result);
        // stop all players
        if (playerService != null) {
            playerService.shutdown();
        }
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
        if (localDummyDevice == null) {
            try {
                localDummyDevice = new LocalDummyDevice(context);
            } catch (ValidationException e) {
                // Ignore
                YaaccLogger.d(this.getClass().getName(), "Something wrong with the LocalDummyDevice...", e);
            }
        }
        return localDummyDevice;
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
        if (playerService == null && !startService()) {
            return Collections.emptyList();
        }
        if (!waitForPlayerServiceComeUp()) {
            return Collections.emptyList();
        }
        List<Player> players = playerService.createPlayer(this, items);
        rememberShares(players);
        return players;
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
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + device.getDisplayString());
            return false;
        }
        if (!hasActionGetMute(service)) {
            YaaccLogger.d(getClass().getName(),
                    "No action get mute found on Device: "
                            + device.getDisplayString());
            return false;
        }
        YaaccLogger.d(getClass().getName(), "Action get Mute ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetMute actionCallback = new GetMute(service, yaaccUpnpServerService.getNetworkDeviceListener().getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
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
        try {
            executorService.submit(actionCallback).get(10000L, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            YaaccLogger.d(getClass().getName(), "Timeout occurred", ex);
        }
        return actionState.result != null && (Boolean) actionState.result;
    }

    public boolean hasActionGetMute(Device<?, ?, ?> device) {
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            return true;
        }
        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return false;
        }

        return hasActionGetMute(service);
    }

    private boolean hasActionGetMute(Service<?, ?> service) {
        return service.getAction("GetMute") != null;
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
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + device.getDisplayString());
            return;
        }
        if (!hasActionSetMute(service)) {
            YaaccLogger.d(getClass().getName(),
                    "No action set mute found on Device: "
                            + device.getDisplayString());
            return;
        }
        YaaccLogger.d(getClass().getName(), "Action set Mute ");
        SetMute actionCallback = new SetMute(service, mute, yaaccUpnpServerService.getNetworkDeviceListener().getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
            }
        };
        executorService.execute(actionCallback);
    }


    private boolean hasActionSetMute(Service<?, ?> service) {
        return service.getAction("SetMute") != null;
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
            YaaccLogger.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return 0;
        }
        if (!hasActionGetVolume(service)) {
            YaaccLogger.d(getClass().getName(),
                    "No action get volume found on Device: "
                            + device.getDisplayString());
            return 0;
        }
        YaaccLogger.d(getClass().getName(), "Action get Volume ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        GetVolume actionCallback = new GetVolume(service, yaaccUpnpServerService.getNetworkDeviceListener().getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
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
        try {
            executorService.submit(actionCallback).get(10000L, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            YaaccLogger.d(getClass().getName(), "Timeout occurred", ex);
        }
        return actionState.result == null ? 0 : (Integer) actionState.result;

    }

    public boolean hasActionGetVolume(Device<?, ?, ?> device) {
        if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
            return true;
        }
        Service<?, ?> service = getRenderingControlService(device);
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return false;
        }
        return hasActionGetVolume(service);
    }

    private boolean hasActionGetVolume(Service<?, ?> service) {
        return service.getAction("GetVolume") != null;
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
            YaaccLogger.d(getClass().getName(),
                    "No RenderingControl-Service found on Device: "
                            + device.getDisplayString());
            return;
        }
        if (!hasActionSetVolume(service)) {
            YaaccLogger.d(getClass().getName(),
                    "No action set volume found on Device: "
                            + device.getDisplayString());
            return;
        }
        YaaccLogger.d(getClass().getName(), "Action set Volume ");
        SetVolume actionCallback = new SetVolume(service, volume, yaaccUpnpServerService.getNetworkDeviceListener().getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
            }
        };
        executorService.execute(actionCallback);
    }

    public boolean hasActionSetVolume(Service<?, ?> service) {
        return service.getAction("SetVolume") != null;
    }

    public Optional<Player> getPlayer(int id) {
        return getCurrentPlayers().stream().filter(p -> p.getId() == id).findFirst();
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    public static class LocalDummyDevice extends Device {
        Context context;

        LocalDummyDevice(Context context) throws ValidationException {
            super(new DeviceIdentity(new UDN(LOCAL_UID)));
            this.context = context;
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
            return context.getString(R.string.this_device);
        }

        @Override
        public DeviceDetails getDetails() {
            return new DeviceDetails(context.getString(R.string.this_device));
        }
    }

    /**
     * Adds the item to the chosen receivers: the playlist of a receiver that already has a player gets
     * it, a receiver without a player gets a new one (see {@link PlaylistTargets}).
     */
    public void addToPlaylist(DIDLObject item) {
        List<Item> itemList = toItemList(item, 3);
        Collection<Device<?, ?, ?>> chosen = awaitReceiverDevices();
        List<String> chosenIds = new ArrayList<>();
        for (Device<?, ?, ?> device : chosen) {
            chosenIds.add(device.getIdentity().getUdn().getIdentifierString());
        }
        List<String> playerDeviceIds = new ArrayList<>();
        for (Player player : getCurrentPlayers()) {
            playerDeviceIds.add(player.getDeviceId());
        }
        PlaylistTargets targets = PlaylistTargets.split(chosenIds, playerDeviceIds);
        if (!targets.withPlayer().isEmpty()) {
            getCurrentPlayers().stream()
                    .filter(p -> targets.withPlayer().contains(p.getDeviceId()))
                    .forEach(p -> p.addItems(toPlayableItems(itemList)));
        }
        if (!targets.withoutPlayer().isEmpty()) {
            List<Device<?, ?, ?>> withoutPlayer = new ArrayList<>();
            for (Device<?, ?, ?> device : chosen) {
                if (targets.withoutPlayer().contains(device.getIdentity().getUdn().getIdentifierString())) {
                    withoutPlayer.add(device);
                }
            }
            initializePlayersFor(itemList, withoutPlayer);
        }
    }

    /** Creates a player with the items for each of the given receivers only. */
    private List<Player> initializePlayersFor(List<Item> items, Collection<Device<?, ?, ?>> devices) {
        if (playerService == null && !startService()) {
            return Collections.emptyList();
        }
        if (!waitForPlayerServiceComeUp()) {
            return Collections.emptyList();
        }
        List<Player> players = playerService.createPlayerFor(this, toPlayableItems(items), devices);
        rememberShares(players);
        return players;
    }

    /** What the metadata reader found about a link; any field may be unknown (0 or null). */
    private static final class LinkMetadata {
        String mimeType;
        long durationMillis;
        long bitrate;
    }

    /** HEAD timeouts for the content type of a link the metadata reader could not read. */
    private static final int LINK_HEAD_TIMEOUT_MILLIS = 5_000;

    /**
     * Makes the item for a shared http(s) link (shared text). Worker thread only: it reads the
     * link's metadata over the network and, with the media proxy option on, registers the link
     * with the share registry.
     * <p>
     * The media type comes from the metadata reader; if that fails it comes from a bounded HTTP
     * {@code HEAD} (concrete audio, video or image type); only if that fails too the link is
     * assumed to be an image, as before, and a payload-free line says so. With the proxy option on
     * a target the phone must not fetch (loopback, link-local, this phone's own address, see
     * {@link LinkTargetPolicy}) is refused. Nothing here logs the link or the exceptions' text.
     *
     * @return the item, or null if the link is refused (the caller plays nothing)
     */
    public PlayableItem createPlayableItem(Uri uri) throws IOException {
        return createPlayableItem(uri, getReceiverDeviceIds());
    }

    /**
     * Same as {@link #createPlayableItem(Uri)}, with the receivers the share is made for given
     * explicitly: the addresses a proxied link is served to are built from exactly these ids, never
     * from the stored selection, which another action may have replaced or cleared during the
     * network work.
     *
     * @param receiverIds the receivers chosen for this share, captured at confirm time
     */
    public PlayableItem createPlayableItem(Uri uri, Set<String> receiverIds) throws IOException {
        if (uri == null) {
            return null;
        }
        final String link = uri.toString();
        if (!HttpLinkTarget.isFetchable(link)) {
            YaaccLogger.w(getClass().getName(), "A shared link was refused: not an http(s) link");
            return null;
        }
        final String title = "shared with ♥ by yaacc";
        // only http(s) links are proxied; the link is kept in the in-memory share registry
        // (random key, receiver addresses, expiry), never in the preferences
        final boolean proxy = this.getPreferences().getBoolean(
                getContext().getString(R.string.settings_local_server_proxy_chkbx), false);
        final Set<String> ownAddresses = ShareAddressResolver.ownAddresses(getContext());
        if (proxy && !LinkTargetPolicy.isAllowedTarget(link, ownAddresses)) {
            // the phone would fetch its own or a link-local service for the receivers: play nothing
            YaaccLogger.w(getClass().getName(), "A shared link was refused: the media proxy does not serve it");
            return null;
        }
        // the reader is released inside this call, before any slow work below
        LinkMetadata meta = readLinkMetadata(link);
        String mimeType;
        long durationMillis = 0L;
        long sizeBytes = 1L;
        if (meta != null) {
            mimeType = meta.mimeType;
            durationMillis = meta.durationMillis;
            if (meta.bitrate > 0L && durationMillis > 0L) {
                sizeBytes = (meta.bitrate / 8 * durationMillis / 1000 / 1000);
            }
        } else {
            // a failed read must not silently label the link an image: ask the server
            mimeType = LinkContentType.find(link,
                    hop -> !proxy || LinkTargetPolicy.isAllowedTarget(hop, ownAddresses),
                    LINK_HEAD_TIMEOUT_MILLIS);
            if (mimeType == null) {
                YaaccLogger.d(getClass().getName(), "Media type of a shared link unknown, assuming an image");
                mimeType = "image/*";
            }
        }
        String playUrl = link;
        String shareKey = null;
        if (proxy) {
            String proxyUrl = createHttpLinkShare(link, mimeType, receiverIds);
            if (proxyUrl != null) {
                playUrl = proxyUrl;
                shareKey = ShareKeyTracker.keyOfProxyPath(Uri.parse(proxyUrl).getPath(), YaaccUpnpServerService.PROXY_PATH);
            }
        }
        PlayableItem item = SharedItemFactory.create(mimeType, title, Uri.parse(playUrl), sizeBytes, durationMillis,
                meta == null ? 0L : meta.bitrate);
        if (item == null && shareKey != null) {
            // do not leave a share behind that nobody can play
            getShareRegistry().remove(shareKey);
        }
        return item;
    }

    /**
     * Reads duration, media type and bitrate of a link with {@link MediaMetadataRetriever} (a
     * network call without a timeout of its own: worker thread only).
     *
     * @return null if the reader failed or did not find a concrete audio, video or image type
     */
    private LinkMetadata readLinkMetadata(String link) {
        LinkMetadata meta = new LinkMetadata();
        //auto closeable requires Android code level 29 current min level is 27
        try (MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever()) {
            metaRetriever.setDataSource(link);
            meta.mimeType = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            meta.durationMillis = parseLongOrZero(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            meta.bitrate = parseLongOrZero(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (RuntimeException | IOException e) {
            // no media file with duration; the exception text may hold the link
            YaaccLogger.d(getClass().getName(), "Can't read the metadata of a shared link ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (!SharedContentPolicy.isAcceptableMimeType(meta.mimeType) || meta.durationMillis <= 0L) {
            return null;
        }
        return meta;
    }

    private static long parseLongOrZero(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** How long a share waits for the local HTTP server before it hands out the direct link. */
    private static final long SERVER_READY_MAX_MILLIS = 3_000L;
    private static final long SERVER_READY_POLL_MILLIS = 200L;

    /**
     * Registers an http(s) link with the share registry and returns the proxy URL a receiver
     * fetches it from, or null if no share could be made (the caller then uses the link
     * directly). Allowed fetchers are the chosen receivers, see {@link ShareAddressResolver}.
     * <p>
     * Worker thread only: it waits (up to 3 s) until the local HTTP server accepts connections
     * (a cold start from a share intent) and reads the own addresses from the network
     * interfaces. Its only caller is {@link #createPlayableItem}, which
     * {@code ShareIntentHandler.prepareAndPlay} runs on its executor; on the main thread the
     * wait is skipped, so the UI never blocks. Nothing here logs the link or the key.
     */
    private String createHttpLinkShare(String link, String mimeType, Set<String> receiverIds) {
        HttpLinkTarget target = HttpLinkTarget.of(link);
        if (target == null) {
            return null;
        }
        // no address (offline) means no URL: never "http://null:..."; checked before anything is registered
        String ip = InterfaceResolutionHelper.getIpAddress(getContext());
        if (!ShareService.hasAddress(ip)) {
            YaaccLogger.w(getClass().getName(), "No network address, using the link directly");
            return null;
        }
        if (!awaitHttpServer()) {
            YaaccLogger.w(getClass().getName(), "Media proxy server is not ready, using the link directly");
            return null;
        }
        ShareRegistry registry = getShareRegistry();
        ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.HTTP_LINK, target, mimeType,
                ShareAddressResolver.resolve(this, getContext(), receiverIds),
                ShareLimits.IDLE_GRACE_MILLIS, ShareLimits.ABSOLUTE_MAX_MILLIS);
        String key = registry.registerOrEvict(entry);
        if (key == null) {
            YaaccLogger.w(getClass().getName(), "Media proxy is full, using the link directly");
            notifyRegistryFull();
            return null;
        }
        // a share no player takes within the claim window is removed by the sweeper
        getUnclaimedShares().registered(key, Yaacc.shareClockMillis());
        return ShareService.proxyUrl(ip, key);
    }

    /**
     * Gives up the share an item was made for (no player took it): removes its registry key,
     * which closes the source of a file. Does nothing for an item without a proxy URL. Never throws.
     */
    public void discardShareOf(PlayableItem item) {
        try {
            Uri uri = item == null ? null : item.getUri();
            String key = uri == null ? null
                    : ShareKeyTracker.keyOfProxyPath(uri.getPath(), YaaccUpnpServerService.PROXY_PATH);
            if (key != null) {
                getShareRegistry().remove(key);
                getUnclaimedShares().released(key);
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "Could not discard a share (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Waits for the loopback HTTP server, polling {@link #isHttpServerRunning()}. On the main
     * thread (a misuse) it neither waits nor probes, so the UI never blocks; the share is then
     * handed out without the readiness check.
     */
    private boolean awaitHttpServer() {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            YaaccLogger.w(getClass().getName(), "Media proxy readiness not checked on the main thread");
            return true;
        }
        return ReadinessWait.await(this::isHttpServerRunning, SERVER_READY_MAX_MILLIS, SERVER_READY_POLL_MILLIS);
    }

    /** The user switched the proxy on and gets the direct link instead: tell them. No payload. */
    private void notifyRegistryFull() {
        Context appContext = getContext().getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext, R.string.share_registry_full, Toast.LENGTH_LONG).show());
    }

    private ShareRegistry getShareRegistry() {
        return ((Yaacc) getContext().getApplicationContext()).getShareRegistry();
    }

    private UnclaimedShares getUnclaimedShares() {
        return ((Yaacc) getContext().getApplicationContext()).getUnclaimedShares();
    }

    /**
     * Whether the local HTTP server accepts connections (it runs whenever the app process lives,
     * whatever the "local server" setting says). A short loopback connect: call it only off the
     * main thread. See {@link HttpServerProbe}.
     */
    public boolean isHttpServerRunning() {
        return HttpServerProbe.isRunning();
    }

    /**
     * The keys of the shares a player plays right now: the last path segment of its proxy URLs
     * ({@code /proxy/<key>}). Only a snapshot: the list is empty once the player was destroyed,
     * which is why {@link ShareLifetimeListener} remembers the keys.
     */
    private static Set<String> shareKeysOf(Player player) {
        Set<String> keys = new HashSet<>();
        try {
            for (PlayableItem item : new ArrayList<>(player.getItems())) {
                Uri uri = item == null ? null : item.getUri();
                String key = uri == null ? null
                        : ShareKeyTracker.keyOfProxyPath(uri.getPath(), YaaccUpnpServerService.PROXY_PATH);
                if (key != null) {
                    keys.add(key);
                }
            }
        } catch (RuntimeException e) {
            // the list changed under us; the keys remembered earlier still count
        }
        return keys;
    }

    /**
     * Ends the grace-period countdown of a share when the last player that plays it is removed,
     * and cancels a pending countdown when a player for it appears. See
     * {@link ShareLifetimeListener} for why the keys are remembered rather than read at removal.
     */
    private final ShareLifetimeListener shareLifetimeListener = new ShareLifetimeListener(
            UpnpClient::shareKeysOf,
            () -> new ArrayList<>(getCurrentPlayers()),
            key -> {
                getShareRegistry().markActive(key);
                // a player took the key: the sweeper must not remove it any more
                getUnclaimedShares().claimed(key);
            },
            key -> {
                getShareRegistry().release(key);
                getUnclaimedShares().released(key);
            });

    /** The items of the new players are set now: remember and activate their shares. */
    private void rememberShares(List<Player> players) {
        for (Player player : players) {
            try {
                shareLifetimeListener.itemsSet(player);
            } catch (RuntimeException e) {
                YaaccLogger.w(getClass().getName(), "Could not record the shares of a player ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
    }


    private SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(getContext());

    }

    public boolean isPlayerServiceInitialized() {
        return playerService != null;
    }
}
