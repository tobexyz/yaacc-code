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
package de.yaacc.upnp.server;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;

import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.ProtocolInfos;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.xmicrosoft.AbstractMediaReceiverRegistrarService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.protocol.UpnpProtocolHandler;
import de.yaacc.upnp.registry.Registry;
import de.yaacc.upnp.registry.RegistryImpl;
import de.yaacc.upnp.server.avtransport.AvTransport;
import de.yaacc.upnp.server.avtransport.YaaccAVTransportService;
import de.yaacc.upnp.server.configuration.YaaccUpnpServerControlActivity;
import de.yaacc.upnp.server.connectionmanager.ConnectionManagerService;
import de.yaacc.upnp.server.contentdirectory.YaaccContentDirectory;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler;
import de.yaacc.upnp.server.http.YaaccUpnpServerProtocolRequestHandler;
import de.yaacc.upnp.server.media.CombinedCaptureService;
import de.yaacc.upnp.server.media.MediaProjectionHelper;
import de.yaacc.upnp.server.media.ScreenCastCaptureService;
import de.yaacc.upnp.server.media.SystemAudioCaptureService;
import de.yaacc.upnp.server.renderingcontrol.YaaccAudioRenderingControlService;
import de.yaacc.util.InterfaceResolutionHelper;
import de.yaacc.util.NotificationId;
import de.yaacc.util.SAFCacheManager;
import de.yaacc.util.YaaccLogger;

/**
 * A simple local upnp server implementation. This class encapsulate the creation
 * and registration of local upnp services. it is implemented as a android
 * service in order to run in background
 *
 * @author Tobias Schoene (openbit)
 */
public class YaaccUpnpServerService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String PROXY_LINK_KEY_PREFIX = "proxy_link_";
    public static final String PROXY_LINK_MIME_TYPE_KEY_PREFIX = "proxy_link_mime_type";
    public static final String PROXY_PATH = "proxy";
    public static final String SAF_PATH = "saf";
    public static final int LOCK_TIMEOUT = 5000;

    public static int PORT = 49157;
    public static ServiceType[] EXCLUSIVE_SERVER_TYPES = new ServiceType[]{
            new UDAServiceType("AVTransport"),
            new UDAServiceType("ContentDirectory"),
            new UDAServiceType("ConnectionManager"),
            new UDAServiceType("RenderingControl"),
            new UDAServiceType("X_MS_MediaReceiverRegistrar")};

    public String locaDeviceUuid;
    protected IBinder binder = new YaaccUpnpServerServiceBinder();
    SharedPreferences preferences;
    private LocalService<YaaccContentDirectory> contentDirectoryService;


    private NetworkDeviceListener networkDeviceListener;

    private Registry registry;

    private HttpAsyncServer httpServer;
    private LocalDevice localDevice;

    // Live streaming (Android 10+)
    private SystemAudioCaptureService audioCapture;
    private ScreenCastCaptureService videoCapture;
    private CombinedCaptureService combinedCapture;
    private MediaProjection projection;
    private static final int HTTP_SERVER_RETRY_DELAY_MS = 2000;
    private static final int HTTP_SERVER_MAX_RETRIES = 3;
    private final Object initLock = new Object();
    private volatile boolean isInitialized = false;

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent intent) {
        YaaccLogger.d(this.getClass().getName(), "On Bind");
        // do nothing
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        YaaccLogger.i(getClass().getName(), "YaaccUpnpServerService onCreate called");
        // when the service starts, the preferences are initialized
        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Register broadcast receiver for cache updates
        registerReceiver(cacheUpdateReceiver, new IntentFilter("de.yaacc.CACHE_PRELOAD_COMPLETE"));
        registerReceiver(cacheProgressReceiver, new IntentFilter("de.yaacc.CACHE_PRELOAD_PROGRESS"));
        registerReceiver(safPathsChangedReceiver, new IntentFilter("de.yaacc.SAF_PATHS_CHANGED"));

        // Start background preloading of SAF durations
        SAFCacheManager.getInstance(getApplicationContext()).preloadSafDurations();

        YaaccLogger.i(getClass().getName(), "YaaccUpnpServerService onCreate complete");
    }

    private int cacheFilesIndexed = 0;
    private String cacheCurrentFolder = "";

    private final BroadcastReceiver cacheUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int total = intent.getIntExtra("files_indexed", 0);
            cacheFilesIndexed = total;
            showNotification(); // Update notification with final cache status
        }
    };

    private final BroadcastReceiver cacheProgressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cacheFilesIndexed = intent.getIntExtra("files_indexed", 0);
            cacheCurrentFolder = intent.getStringExtra("current_folder");
            showNotification(); // Update notification with progress
        }
    };

    private final BroadcastReceiver safPathsChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Restart SAF preloading with new paths
            cacheFilesIndexed = 0;
            cacheCurrentFolder = "";
            SAFCacheManager.getInstance(getApplicationContext()).preloadSafDurations();
            showNotification();
        }
    };


    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onStart(android.content.Intent, int)
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        YaaccLogger.i(getClass().getName(), "YaaccUpnpServerService onStartCommand called");
        long start = System.currentTimeMillis();
        if (registry == null) {
            registry = new RegistryImpl();
        }
        if (networkDeviceListener == null) {
            networkDeviceListener = new NetworkDeviceListener(getApplicationContext(), registry, this);
            registry.setUpnpProtocolHandler(networkDeviceListener.getUpnpProtocolHandler());
        }
        // App is active when service starts
        networkDeviceListener.setAppInForeground(true);

        // Trigger UPnP discovery when service starts
        YaaccLogger.d(getClass().getName(), "Triggering UPnP discovery on service start");
        if (networkDeviceListener.isInitalized()) {
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Wait a bit for network to stabilize
                    UpnpClient client = ((Yaacc) getApplicationContext()).getUpnpClient();
                    if (client != null && client.isInitialized()) {
                        client.searchDevices();
                        YaaccLogger.d(getClass().getName(), "UPnP discovery triggered");
                    }
                } catch (Exception e) {
                    YaaccLogger.e(getClass().getName(), "Error triggering UPnP discovery", e);
                }
            }).start();
        } else {
            YaaccLogger.w(getClass().getName(), "NetworkDeviceListener not initialized, discovery will be triggered when network becomes available");
        }

        locaDeviceUuid = preferences.getString(getApplicationContext().getString(R.string.settings_local_device_uuid_key), null);
        if (locaDeviceUuid == null) {
            locaDeviceUuid = UUID.randomUUID().toString();
            preferences.edit().putString(getApplicationContext().getString(R.string.settings_local_device_uuid_key), locaDeviceUuid).commit();
        }
        // the footprint of the onStart() method must be small
        // otherwise android will kill the service
        // in order of this circumstance we have to initialize the service
        // asynchronous
        Thread initializationThread = new Thread(this::initialize);
        initializationThread.start();
        showNotification();
        YaaccLogger.d(this.getClass().getName(), "End On Start");
        YaaccLogger.d(this.getClass().getName(), "on start took: " + (System.currentTimeMillis() - start));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        YaaccLogger.d(this.getClass().getName(), "Destroying the service");

        synchronized (initLock) {
            isInitialized = false;
        }

        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(this);
        }

        // Unregister broadcast receivers
        try {
            unregisterReceiver(cacheUpdateReceiver);
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Error unregistering cache receiver", e);
        }
        try {
            unregisterReceiver(cacheProgressReceiver);
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Error unregistering cache progress receiver", e);
        }
        try {
            unregisterReceiver(safPathsChangedReceiver);
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Error unregistering SAF paths receiver", e);
        }


        if (localDevice != null) {
            registry.removeDevice(localDevice);
            localDevice = null;
        }

        networkDeviceListener.disable();
        if (httpServer != null) {
            httpServer.initiateShutdown();
            try {
                httpServer.awaitShutdown(TimeValue.ofSeconds(3));
            } catch (InterruptedException e) {
                YaaccLogger.w(getClass().getName(), "got exception on stream server stop ", e);
            }
            httpServer = null;
        }
        cancleNotification();


        super.onDestroy();
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        YaaccLogger.d(getClass().getName(), "Task removed - app backgrounded");
        if (networkDeviceListener != null) {
            networkDeviceListener.setAppInForeground(false);
        }
        updateNotification(); // WiFi lock may have changed
    }

    /**
     * Displays the notification.
     */
    private void showNotification() {
        ((Yaacc) getApplicationContext()).createYaaccGroupNotification();
        Intent notificationIntent = new Intent(this, YaaccUpnpServerControlActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Build status line with active features
        StringBuilder statusBuilder = new StringBuilder();

        // HTTP Server status
        if (httpServer != null) {
            try {
                IOReactorStatus status = httpServer.getStatus();
                statusBuilder.append(status == IOReactorStatus.ACTIVE ? "✓ HTTP" : "⚠ HTTP");
            } catch (Exception e) {
                statusBuilder.append("⚠ HTTP");
            }
        } else {
            statusBuilder.append("⚠ HTTP");
        }

        // Network interface info
        if (networkDeviceListener != null && networkDeviceListener.isInitalized()) {
            try {
                String[] iface = InterfaceResolutionHelper.getIfAndIpAddress(this);
                if (!"0.0.0.0".equals(iface[0])) {
                    statusBuilder.append(" | ").append(iface[1]).append(":").append(iface[0]);
                } else {
                    statusBuilder.append(" | No usable network interface found");
                }
            } catch (Exception e) {
                YaaccLogger.d(getClass().getName(), "Failed to get network interface info", e);
            }
        } else {
            statusBuilder.append(" | No usable network interface found");
        }

        // Server/Renderer status
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean serverEnabled = preferences.getBoolean(getString(R.string.settings_local_server_chkbx), false);
        boolean poviderEnabled = preferences.getBoolean(getString(R.string.settings_local_server_provider_chkbx), false);
        boolean rendererEnabled = preferences.getBoolean(getString(R.string.settings_local_server_receiver_chkbx), false);
        boolean proxyEnabled = preferences.getBoolean(getString(R.string.settings_local_server_proxy_chkbx), false);

        if (serverEnabled && poviderEnabled) {
            statusBuilder.append(" | ✓ Server");
        }
        if (serverEnabled && rendererEnabled) {
            statusBuilder.append(" | ✓ Renderer");
        }
        if (serverEnabled && proxyEnabled) {
            statusBuilder.append(" | ✓ Proxy");
        }
        // WiFi lock status
        if (networkDeviceListener != null && networkDeviceListener.isWifiLockHeld()) {
            statusBuilder.append(" | ✓ WiFi Lock");
        }

        // Duration cache status
        SAFCacheManager cacheManager = SAFCacheManager.getInstance(this);
        if (cacheManager.isPreloading()) {
            statusBuilder.append(" | ⏳ Indexing: ").append(cacheFilesIndexed);
            if (!cacheCurrentFolder.isEmpty()) {
                statusBuilder.append(" (").append(cacheCurrentFolder).append(")");
            }
        } else if (cacheManager.getCacheSize() > 0) {
            statusBuilder.append(" | ✓ Cache: ").append(cacheManager.getCacheSize());
        }

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setSilent(true)
                .setContentTitle("Yaacc UPnP Service")
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentText(statusBuilder.toString());
        mBuilder.setContentIntent(contentIntent);
        startForeground(NotificationId.UPNP_SERVER.getId(), mBuilder.build());

    }

    /**
     * Update notification with current server status.
     */
    public void updateNotification() {
        showNotification();
    }

    /**
     * Cancels the notification.
     */
    private void cancleNotification() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.cancel(NotificationId.UPNP_SERVER.getId());
        ((Yaacc) getApplicationContext()).cancelYaaccGroupNotification();
    }

    /**
     *
     */
    private void initialize() {
        synchronized (initLock) {
            if (isInitialized) {
                YaaccLogger.d(getClass().getName(), "Already initialized, skipping");
                return;
            }

            YaaccLogger.i(getClass().getName(), "initialize() called");

            // Wait for NetworkDeviceListener to be initialized
            if (!networkDeviceListener.isInitalized()) {
                YaaccLogger.w(getClass().getName(), "NetworkDeviceListener not initialized, waiting...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!networkDeviceListener.isInitalized()) {
                    YaaccLogger.e(getClass().getName(), "NetworkDeviceListener still not initialized, aborting");
                    return;
                }
            }

            // Try to create HTTP server with retries
            boolean serverStarted = false;
            for (int attempt = 1; attempt <= HTTP_SERVER_MAX_RETRIES && !serverStarted; attempt++) {
                try {
                    YaaccLogger.d(getClass().getName(), "Calling createHttpServer() - attempt " + attempt);
                    createHttpServer();

                    // Health check: verify server is actually listening
                    if (isHttpServerHealthy()) {
                        YaaccLogger.i(getClass().getName(), "HTTP server started successfully");
                        serverStarted = true;
                    } else {
                        YaaccLogger.w(getClass().getName(), "HTTP server created but health check failed - attempt " + attempt);
                        shutdownHttpServer();
                        if (attempt < HTTP_SERVER_MAX_RETRIES) {
                            Thread.sleep(HTTP_SERVER_RETRY_DELAY_MS);
                        }
                    }
                } catch (IOException e) {
                    YaaccLogger.e(getClass().getName(), "Error creating HTTP server - attempt " + attempt, e);
                    if (attempt < HTTP_SERVER_MAX_RETRIES) {
                        try {
                            Thread.sleep(HTTP_SERVER_RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!serverStarted) {
                YaaccLogger.e(getClass().getName(), "Failed to start HTTP server after " + HTTP_SERVER_MAX_RETRIES + " attempts");
            }

            createUpnpDevice();
            isInitialized = true;

            // Update notification with server status
            updateNotification();

            YaaccLogger.d(getClass().getName(), "initialize() complete");
        }
    }

    private boolean isHttpServerHealthy() {
        if (httpServer == null) {
            return false;
        }

        // Check if server is in ACTIVE state
        try {
            IOReactorStatus status = httpServer.getStatus();
            boolean healthy = status == IOReactorStatus.ACTIVE;
            YaaccLogger.d(getClass().getName(), "HTTP server health check: status=" + status + ", healthy=" + healthy);
            return healthy;
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "HTTP server health check failed", e);
            return false;
        }
    }

    private void shutdownHttpServer() {
        if (httpServer != null) {
            try {
                httpServer.initiateShutdown();
                httpServer.awaitShutdown(TimeValue.ofSeconds(1));
            } catch (Exception e) {
                YaaccLogger.w(getClass().getName(), "Error shutting down HTTP server", e);
            }
            httpServer = null;
        }
    }

    private void createUpnpDevice() {
        String versionName;
        YaaccLogger.d(this.getClass().getName(), "Create UPNP Device whith ID: " + locaDeviceUuid);

        // Ensure HTTP server is running when creating device
        if (httpServer == null) {
            YaaccLogger.w(this.getClass().getName(), "HTTP server not running, attempting to create");
            try {
                createHttpServer();
            } catch (IOException e) {
                YaaccLogger.e(this.getClass().getName(), "Failed to create HTTP server during device creation", e);
            }
        }

        // Remove old device if it exists (by UDN, not by reference)
        if (localDevice != null && registry.getDevices().contains(localDevice)) {
            YaaccLogger.d(this.getClass().getName(), "Removing old device before creating new one");
            registry.removeDevice(localDevice);

        }

        try {
            versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionName;
        } catch (NameNotFoundException ex) {
            YaaccLogger.e(this.getClass().getName(), "Error while creating device", ex);
            versionName = "??";
        }
        try {

            // Yaacc Details
            // Used for shown name: first part of ManufactDet, first
            // part of ModelDet and version number
            DeviceDetails yaaccDetails = new DeviceDetails(
                    getLocalServerName(), new ManufacturerDetails("yaacc.de",
                    "https://www.yaacc.de"), new ModelDetails(getLocalServerName() + "- UpnP", "Free Android UPnP/DLNA, GNU GPL",
                    versionName), URI.create("http://" + InterfaceResolutionHelper.getIpAddress(getApplicationContext()) + ":" + PORT));


            List<LocalService<?>> services = new ArrayList();
            services.addAll(Arrays.asList(createCoreServices()));

            boolean serverEnabled = preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_chkbx), false);
            boolean providerEnabled = preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_provider_chkbx), false);
            boolean rendererEnabled = preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_receiver_chkbx), false);

            DeviceIdentity identity = new DeviceIdentity(new UDN(locaDeviceUuid));

            // If both server and renderer are enabled, create nested device structure
            if (serverEnabled && providerEnabled && rendererEnabled) {
                // Create MediaServer as root with embedded MediaRenderer
                LocalDevice rendererDevice = new LocalDevice(
                        new DeviceIdentity(new UDN(locaDeviceUuid + "-renderer")),
                        new UDADeviceType("MediaRenderer"),
                        yaaccDetails,
                        createDeviceIcons(),
                        createMediaRendererServices()
                );

                List<LocalService<?>> serverServices = new ArrayList<>();
                serverServices.addAll(Arrays.asList(createCoreServices()));
                serverServices.addAll(Arrays.asList(createMediaServerServices()));

                LocalDevice serverDevice = new LocalDevice(
                        identity,
                        new UDADeviceType("MediaServer"),
                        yaaccDetails,
                        createDeviceIcons(),
                        serverServices.toArray(new LocalService<?>[0]),
                        new LocalDevice[]{rendererDevice}
                );

                registry.addDevice(serverDevice);
                localDevice = serverDevice;
            } else {
                // Single device type
                if (serverEnabled && providerEnabled) {
                    services.addAll(Arrays.asList(createMediaServerServices()));
                }
                if (serverEnabled && rendererEnabled) {
                    services.addAll(Arrays.asList(createMediaRendererServices()));
                }

                UDADeviceType deviceType;
                if (rendererEnabled && !providerEnabled) {
                    deviceType = new UDADeviceType("MediaRenderer");
                } else {
                    deviceType = new UDADeviceType("MediaServer");
                }

                localDevice = new LocalDevice(identity, deviceType, yaaccDetails, createDeviceIcons(), services.toArray(new LocalService<?>[0]));
                registry.addDevice(localDevice);
            }

            // Configure ALIVE announcement interval from settings
            int aliveInterval = getUpnpNotificationFrequency();
            registry.setAliveInterval(aliveInterval);
            YaaccLogger.d(this.getClass().getName(), "UPnP ALIVE interval set to: " + aliveInterval + "ms");

        } catch (ValidationException e) {
            YaaccLogger.e(this.getClass().getName(), "Exception during device creation", e);
            if (e.getErrors() != null) {
                for (Object error : e.getErrors()) {
                    YaaccLogger.e(this.getClass().getName(), "Validation error: " + error.toString());
                }
            }
            throw new IllegalStateException("Exception during device creation", e);
        }

    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        YaaccLogger.d(this.getClass().getName(), "Preference changed apply change");
        if (registry == null) {
            YaaccLogger.d(this.getClass().getName(), "Registry is null");
            registry = new RegistryImpl();
        }
        if (getApplicationContext().getString(R.string.settings_local_server_chkbx).equals(key)) {
            createUpnpDevice();
            updateNotification();
        }

        if (getApplicationContext().getString(R.string.settings_local_server_provider_chkbx).equals(key)) {
            createUpnpDevice();
        }
        if (getApplicationContext().getString(R.string.settings_local_server_receiver_chkbx).equals(key)) {
            createUpnpDevice();
            updateNotification();
        }
        if (getApplicationContext().getString(R.string.settings_sending_upnp_alive_interval_key).equals(key)) {
            int aliveInterval = getUpnpNotificationFrequency();
            registry.setAliveInterval(aliveInterval);
            YaaccLogger.d(this.getClass().getName(), "UPnP ALIVE interval updated to: " + aliveInterval + "ms");
        }

        // Restart server when selected interface changes
        if (getApplicationContext().getString(R.string.settings_upnp_selected_interface_key).equals(key)) {
            YaaccLogger.d(this.getClass().getName(), "Selected interface changed, restarting server");
            if (registry != null) {
                restartServer();
            } else {
                YaaccLogger.w(this.getClass().getName(), "Registry not initialized, skipping restart");
            }
        }

        // Trigger cache update when SAF paths change
        if (getApplicationContext().getString(R.string.settings_saf_tree_uris_pref_key).equals(key) ||
                getApplicationContext().getString(R.string.settings_saf_tree_uris_selected_pref_key).equals(key)) {
            YaaccLogger.d(this.getClass().getName(), "SAF paths changed, reloading cache");
            SAFCacheManager.getInstance(getApplicationContext()).preloadSafDurations();
        }

        // Handle live streaming toggles (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (getApplicationContext().getString(R.string.settings_local_server_serve_system_audio_chkbx).equals(key)) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                YaaccLogger.d(getClass().getName(), "Audio preference changed: " + enabled);
                if (enabled) {
                    startAudioCapture();
                    //FIXME experimental checkStartCombinedCapture();
                } else {
                    stopAudioCapture();
                    //FIXME experimentalstopCombinedCapture();
                }
            }
            if (getApplicationContext().getString(R.string.settings_local_server_serve_screen_cast_chkbx).equals(key)) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                YaaccLogger.d(getClass().getName(), "Video preference changed: " + enabled);
                if (enabled) {
                    startVideoCapture();
                    //checkStartCombinedCapture();
                    //FIXME experimental startCombinedCapture();
                } else {
                    stopVideoCapture();
                    //FIXME experimental stopCombinedCapture();
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void checkStartCombinedCapture() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean audioEnabled = prefs.getBoolean(getString(R.string.settings_local_server_serve_system_audio_chkbx), false);
        boolean videoEnabled = prefs.getBoolean(getString(R.string.settings_local_server_serve_screen_cast_chkbx), false);

        YaaccLogger.d(getClass().getName(), "checkStartCombinedCapture: audio=" + audioEnabled + " video=" + videoEnabled);

        // If both enabled, use combined capture instead of individual
        if (audioEnabled && videoEnabled) {
            // Stop individual captures if running
            stopAudioCapture();
            stopVideoCapture();
            startCombinedCapture();
        }
    }

    /**
     * creates a http request thread
     */
    private void createHttpServer() throws IOException {
        // Create a HttpService for providing content in the network.
        // Set up the HTTP service
        if (httpServer == null) {
            try {
                YaaccLogger.d(getClass().getName(), "Creating new HTTP server");
                IOReactorConfig config = IOReactorConfig.custom()
                        .setSoReuseAddress(true)
                        .setSoKeepAlive(true)
                        .setTcpNoDelay(true)
                        .setSoTimeout(Timeout.ofMinutes(5))
                        .setIoThreadCount(8)
                        .build();
                httpServer = H2ServerBootstrap.bootstrap()
                        .setIOReactorConfig(config)
                        .setExceptionCallback(new Callback<Exception>() {

                            @Override
                            public void execute(Exception ex) {
                                if (ex instanceof SocketTimeoutException) {
                                    YaaccLogger.e(getClass().getName(), "connection timeout:", ex);
                                } else if (ex instanceof ConnectionClosedException) {
                                    YaaccLogger.e(getClass().getName(), "connection closed:", ex);
                                } else {
                                    YaaccLogger.e(getClass().getName(), "connection error:", ex);
                                }
                            }

                        })
                        .setCanonicalHostName(InterfaceResolutionHelper.getIpAddress(getApplicationContext()))
                        .register("*", new YaaccUpnpServerContentHttpHandler(getApplicationContext()))
                        .register(UpnpProtocolHandler.NAMESPACE.getBasePath().getPath() + "/*", new YaaccUpnpServerProtocolRequestHandler(getNetworkDeviceListener().getUpnpProtocolHandler()))
                        .create();
                YaaccLogger.d(getClass().getName(), "Starting HTTP server");
                httpServer.start();
                YaaccLogger.d(getClass().getName(), "HTTP server started, status=" + httpServer.getStatus());


                // Verify server is actually listening
                try {
                    Thread.sleep(100); // Give it a moment to bind
                    YaaccLogger.d(getClass().getName(), "HTTP server should now be listening on port " + PORT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                YaaccLogger.e(getClass().getName(), "Failed to create HTTP server", e);
                httpServer = null;
                throw new IOException("Failed to create HTTP server", e);
            }
        } else {
            YaaccLogger.d(getClass().getName(), "HTTP server exists, resuming");
            try {
                httpServer.resume();
                YaaccLogger.d(getClass().getName(), "HTTP server resumed, status= " + httpServer.getStatus());
            } catch (Exception e) {
                YaaccLogger.e(getClass().getName(), "Failed to resume HTTP server", e);
                httpServer = null;
                throw new IOException("Failed to resume HTTP server", e);
            }
        }

        try {
            httpServer.listen(new InetSocketAddress(PORT), URIScheme.HTTP);
            YaaccLogger.d(getClass().getName(), "Server listening on port " + PORT);
            YaaccLogger.d(getClass().getName(), "Server status: " + httpServer.getStatus().name());
            YaaccLogger.d(getClass().getName(), "Server Endpoints: " + httpServer.getEndpoints().size());
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to bind HTTP server to port " + PORT, e);
            if (httpServer != null) {
                httpServer.close();
                httpServer = null;
            }
            throw new IOException("Failed to bind HTTP server to port " + PORT, e);
        }
        httpServer.getEndpoints().forEach(endpoint -> YaaccLogger.d(getClass().getName(), "Endpoint: " + endpoint.toString()));

    }


    /**
     * the time between two upnp alive notifications. -1 if never send a
     * notification
     *
     * @return the time
     */
    private int getUpnpNotificationFrequency() {
        return Integer.parseInt(preferences.getString(getApplicationContext().getString(R.string.settings_sending_upnp_alive_interval_key), "5000"));
    }


    private Icon[] createDeviceIcons() {

        ArrayList<Icon> icons = new ArrayList<>();
        icons.add(new Icon("image/jpeg", 192, 192, 24, "yaacc192.jpg", getIconAsByteArray(R.drawable.yaacc192jpg, Bitmap.CompressFormat.JPEG)));
        icons.add(new Icon("image/jpeg", 120, 120, 24, "yaacc120.jpg", getIconAsByteArray(R.drawable.yaacc120jpg, Bitmap.CompressFormat.JPEG)));
        icons.add(new Icon("image/jpeg", 64, 48, 24, "yaacc64.jpg", getIconAsByteArray(R.drawable.yaacc64jpg, Bitmap.CompressFormat.JPEG)));
        icons.add(new Icon("image/jpeg", 48, 48, 24, "yaacc48.jpg", getIconAsByteArray(R.drawable.yaacc48jpg, Bitmap.CompressFormat.JPEG)));
        icons.add(new Icon("image/jpeg", 32, 32, 24, "yaacc32.jpg", getIconAsByteArray(R.drawable.yaacc32jpg, Bitmap.CompressFormat.JPEG)));


        icons.add(new Icon("image/png", 192, 192, 24, "yaacc192.png", getIconAsByteArray(R.drawable.yaacc192png, Bitmap.CompressFormat.PNG)));
        icons.add(new Icon("image/png", 120, 120, 24, "yaacc120.png", getIconAsByteArray(R.drawable.yaacc120png, Bitmap.CompressFormat.PNG)));
        icons.add(new Icon("image/png", 64, 48, 24, "yaacc64.png", getIconAsByteArray(R.drawable.yaacc64png, Bitmap.CompressFormat.PNG)));
        icons.add(new Icon("image/png", 48, 48, 24, "yaacc48.png", getIconAsByteArray(R.drawable.yaacc48png, Bitmap.CompressFormat.PNG)));
        icons.add(new Icon("image/png", 32, 32, 24, "yaacc32.png", getIconAsByteArray(R.drawable.yaacc32png, Bitmap.CompressFormat.PNG)));


        return icons.toArray(new Icon[]{});
    }

    private String getLocalServerName() {
        return preferences.getString(getApplicationContext().getString(R.string.settings_local_server_name_key), "Yaacc");
    }

    /**
     * Create the services provided by the server device
     *
     * @return the services
     */
    private LocalService<?>[] createMediaServerServices() {
        List<LocalService<?>> services = new ArrayList<>();
        services.add(createContentDirectoryService());
        return services.toArray(new LocalService[]{});
    }

    private LocalService<?>[] createCoreServices() {
        List<LocalService<?>> services = new ArrayList<>();
        services.add(createConnectionManagerService());
        services.add(createMediaReceiverRegistrarService());
        return services.toArray(new LocalService[]{});
    }

    /**
     * Create the renderer services provided by the device
     *
     * @return the services
     */
    private LocalService<?>[] createMediaRendererServices() {
        List<LocalService<?>> services = new ArrayList<>();
        services.add(createAVTransportService());
        services.add(createRenderingControl());
        return services.toArray(new LocalService[]{});
    }

    /**
     * Creates an ContentDirectoryService. The content directory includes all
     * Files of the MediaStore.
     *
     * @return The ContenDiractoryService.
     */
    @SuppressWarnings("unchecked")
    private LocalService<YaaccContentDirectory> createContentDirectoryService() {
        contentDirectoryService = new AnnotationLocalServiceBinder().read(YaaccContentDirectory.class);
        contentDirectoryService.setManager(new DefaultServiceManager<>(contentDirectoryService, null) {

            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT;
            }

            @Override
            protected YaaccContentDirectory createServiceInstance() {
                return new YaaccContentDirectory(getApplicationContext());
            }
        });
        return contentDirectoryService;
    }

    /**
     * creates an AVTransportService
     *
     * @return the service
     */
    @SuppressWarnings("unchecked")
    private LocalService<YaaccAVTransportService> createAVTransportService() {
        // Set upnpClient for state classes to access (may be null during initialization)
        UpnpClient client = ((Yaacc) getApplicationContext()).getUpnpClient();
        if (client != null) {
            AvTransport.setUpnpClient(client);
        }

        LocalService<YaaccAVTransportService> avTransportService = new AnnotationLocalServiceBinder().read(YaaccAVTransportService.class);
        avTransportService.setManager(new DefaultServiceManager<>(avTransportService, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT;
            }

            @Override
            protected YaaccAVTransportService createServiceInstance() {
                return new YaaccAVTransportService();
            }
        });
        return avTransportService;
    }

    private LocalService<AbstractAudioRenderingControl> createRenderingControl() {
        LocalService<AbstractAudioRenderingControl> renderingControlService = new AnnotationLocalServiceBinder()
                .read(AbstractAudioRenderingControl.class);
        renderingControlService.setManager(new DefaultServiceManager<>(renderingControlService, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT;
            }

            @Override
            protected AbstractAudioRenderingControl createServiceInstance() {
                return new YaaccAudioRenderingControlService(getApplicationContext());
            }
        });
        return renderingControlService;
    }

    private LocalService<AbstractMediaReceiverRegistrarService> createMediaReceiverRegistrarService() {
        LocalService<AbstractMediaReceiverRegistrarService> service = new AnnotationLocalServiceBinder()
                .read(AbstractMediaReceiverRegistrarService.class);
        service.setManager(new DefaultServiceManager<>(service, null) {

            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT;
            }

            @Override
            protected AbstractMediaReceiverRegistrarService createServiceInstance() {
                return new YaaccMediaReceiverRegistrarService();
            }
        });
        return service;
    }

    /**
     * creates a ConnectionManagerService.
     *
     * @return the service
     */
    @SuppressWarnings("unchecked")
    private LocalService<ConnectionManagerService> createConnectionManagerService() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        final ProtocolInfos sourceProtocols = getSourceProtocolInfos();

        service.setManager(new DefaultServiceManager<>(service, ConnectionManagerService.class) {

            @Override
            protected int getLockTimeoutMillis() {
                return LOCK_TIMEOUT;
            }

            @Override
            protected ConnectionManagerService createServiceInstance() {
                return new ConnectionManagerService(sourceProtocols, null);
            }
        });

        return service;
    }


    private ProtocolInfos getSourceProtocolInfos() {
        return new ProtocolInfos(
                new ProtocolInfo("http-get:*:audio:*"),
                new ProtocolInfo("http-get:*:audio/mpeg:*"),
                new ProtocolInfo("http-get:*:audio/x-mpegurl:*"),
                new ProtocolInfo("http-get:*:audio/x-wav:*"),
                new ProtocolInfo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"),
                new ProtocolInfo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP2"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMABASE"),
                new ProtocolInfo("http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO"),
                new ProtocolInfo("http-get:*:audio/x-flac:*"),
                new ProtocolInfo("http-get:*:audio/x-aiff:*"),
                new ProtocolInfo("http-get:*:audio/x-ogg:*"),
                new ProtocolInfo("http-get:*:audio/wav:*"),
                new ProtocolInfo("http-get:*:audio/x-ape:*"),
                new ProtocolInfo("http-get:*:audio/x-m4a:*"),
                new ProtocolInfo("http-get:*:audio/x-m4b:*"),
                new ProtocolInfo("http-get:*:audio/x-wavpack:*"),
                new ProtocolInfo("http-get:*:audio/x-musepack:*"),
                new ProtocolInfo("http-get:*:audio/basic:*"),
                new ProtocolInfo("http-get:*:audio/L16;rate=11025;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=22050;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=48000;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=88200;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=96000;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo("http-get:*:audio/L16;rate=192000;channels=2:DLNA.ORG_PN=LPCM"),
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, "audio/mpeg", "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01"),
                new ProtocolInfo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"),
                new ProtocolInfo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3X"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:*"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMABASE"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMAFULL"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMAPRO"),
                new ProtocolInfo("http-get:*:image/gif:*"),
                new ProtocolInfo("http-get:*:image/jpeg:*"),
                new ProtocolInfo("http-get:*:image/png:*"),
                new ProtocolInfo("http-get:*:image/x-ico:*"),
                new ProtocolInfo("http-get:*:image/x-ms-bmp:*"),
                new ProtocolInfo("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG"),
                new ProtocolInfo("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED"),
                new ProtocolInfo("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM"),
                new ProtocolInfo("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN"),
                new ProtocolInfo("http-get:*:image/x-ycbcr-yuv420:*"),
                new ProtocolInfo("http-get:*:video/mp4:*"),
                new ProtocolInfo("http-get:*:video/mpeg:*"),
                new ProtocolInfo("http-get:*:video/quicktime:*"),
                new ProtocolInfo("http-get:*:video/x-flc:*"),
                new ProtocolInfo("http-get:*:video/x-msvideo:*"),
                new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, "video/mpeg", "DLNA.ORG_PN=MPEG1;DLNA.ORG_OP=01;DLNA.ORG_CI=0"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG1"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_NTSC"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_NTSC_XAC3"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_PAL"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_PAL_XAC3"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_PAL"),
                new ProtocolInfo("http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_PAL_XAC3"),
                new ProtocolInfo("http-get:*:video/wtv:*"),
                new ProtocolInfo("http-get:*:video/x-ms-asf:DLNA.ORG_PN=MPEG4_P2_ASF_ASP_L4_SO_G726"),
                new ProtocolInfo("http-get:*:video/x-ms-asf:DLNA.ORG_PN=MPEG4_P2_ASF_ASP_L5_SO_G726"),
                new ProtocolInfo("http-get:*:video/x-ms-asf:DLNA.ORG_PN=MPEG4_P2_ASF_SP_G726"),
                new ProtocolInfo("http-get:*:video/x-ms-asf:DLNA.ORG_PN=VC1_ASF_AP_L1_WMA"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:*"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVHIGH_FULL"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVHIGH_PRO"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_BASE"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_FULL"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_PRO"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPLL_BASE"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPML_BASE"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPML_MP3"));


    }

    private ProtocolInfos getSinkProtocolInfos() {
        return new ProtocolInfos(
                new ProtocolInfo("http-get:*:*:*"),
                new ProtocolInfo("http-get:*:audio/mkv:*"),
                new ProtocolInfo("http-get:*:audio/mpegurl:*"),
                new ProtocolInfo("http-get:*:audio/mpeg:*"),
                new ProtocolInfo("http-get:*:audio/mpeg3:*"),
                new ProtocolInfo("http-get:*:audio/mp3:*"),
                new ProtocolInfo("http-get:*:audio/mp4:*"),
                new ProtocolInfo("http-get:*:audio/basic:*"),
                new ProtocolInfo("http-get:*:audio/midi:*"),
                new ProtocolInfo("http-get:*:audio/ulaw:*"),
                new ProtocolInfo("http-get:*:audio/ogg:*"),
                new ProtocolInfo("http-get:*:audio/DVI4:*"),
                new ProtocolInfo("http-get:*:audio/G722:*"),
                new ProtocolInfo("http-get:*:audio/G723:*"),
                new ProtocolInfo("http-get:*:audio/G726-16:*"),
                new ProtocolInfo("http-get:*:audio/G726-24:*"),
                new ProtocolInfo("http-get:*:audio/G726-32:*"),
                new ProtocolInfo("http-get:*:audio/G726-40:*"),
                new ProtocolInfo("http-get:*:audio/G728:*"),
                new ProtocolInfo("http-get:*:audio/G729:*"),
                new ProtocolInfo("http-get:*:audio/G729D:*"),
                new ProtocolInfo("http-get:*:audio/G729E:*"),
                new ProtocolInfo("http-get:*:audio/GSM:*"),
                new ProtocolInfo("http-get:*:audio/GSM-EFR:*"),
                new ProtocolInfo("http-get:*:audio/L8:*"),
                new ProtocolInfo("http-get:*:audio/L16:*"),
                new ProtocolInfo("http-get:*:audio/LPC:*"),
                new ProtocolInfo("http-get:*:audio/MPA:*"),
                new ProtocolInfo("http-get:*:audio/PCMA:*"),
                new ProtocolInfo("http-get:*:audio/PCMU:*"),
                new ProtocolInfo("http-get:*:audio/QCELP:*"),
                new ProtocolInfo("http-get:*:audio/RED:*"),
                new ProtocolInfo("http-get:*:audio/VDVI:*"),
                new ProtocolInfo("http-get:*:audio/ac3:*"),
                new ProtocolInfo("http-get:*:audio/vorbis:*"),
                new ProtocolInfo("http-get:*:audio/speex:*"),
                new ProtocolInfo("http-get:*:audio/flac:*"),
                new ProtocolInfo("http-get:*:audio/x-flac:*"),
                new ProtocolInfo("http-get:*:audio/x-aiff:*"),
                new ProtocolInfo("http-get:*:audio/x-pn-realaudio:*"),
                new ProtocolInfo("http-get:*:audio/x-realaudio:*"),
                new ProtocolInfo("http-get:*:audio/x-wav:*"),
                new ProtocolInfo("http-get:*:audio/x-matroska:*"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:*"),
                new ProtocolInfo("http-get:*:audio/x-mpegurl:*"),
                new ProtocolInfo("http-get:*:application/x-shockwave-flash:*"),
                new ProtocolInfo("http-get:*:application/ogg:*"),
                new ProtocolInfo("http-get:*:application/sdp:*"),
                new ProtocolInfo("http-get:*:image/gif:*"),
                new ProtocolInfo("http-get:*:image/jpeg:*"),
                new ProtocolInfo("http-get:*:image/ief:*"),
                new ProtocolInfo("http-get:*:image/png:*"),
                new ProtocolInfo("http-get:*:image/tiff:*"),
                new ProtocolInfo("http-get:*:video/avi:*"),
                new ProtocolInfo("http-get:*:video/divx:*"),
                new ProtocolInfo("http-get:*:video/mpeg:*"),
                new ProtocolInfo("http-get:*:video/fli:*"),
                new ProtocolInfo("http-get:*:video/flv:*"),
                new ProtocolInfo("http-get:*:video/quicktime:*"),
                new ProtocolInfo("http-get:*:video/vnd.vivo:*"),
                new ProtocolInfo("http-get:*:video/vc1:*"),
                new ProtocolInfo("http-get:*:video/ogg:*"),
                new ProtocolInfo("http-get:*:video/mp4:*"),
                new ProtocolInfo("http-get:*:video/mkv:*"),
                new ProtocolInfo("http-get:*:video/BT656:*"),
                new ProtocolInfo("http-get:*:video/CelB:*"),
                new ProtocolInfo("http-get:*:video/JPEG:*"),
                new ProtocolInfo("http-get:*:video/H261:*"),
                new ProtocolInfo("http-get:*:video/H263:*"),
                new ProtocolInfo("http-get:*:video/H263-1998:*"),
                new ProtocolInfo("http-get:*:video/H263-2000:*"),
                new ProtocolInfo("http-get:*:video/MPV:*"),
                new ProtocolInfo("http-get:*:video/MP2T:*"),
                new ProtocolInfo("http-get:*:video/MP1S:*"),
                new ProtocolInfo("http-get:*:video/MP2P:*"),
                new ProtocolInfo("http-get:*:video/BMPEG:*"),
                new ProtocolInfo("http-get:*:video/xvid:*"),
                new ProtocolInfo("http-get:*:video/x-divx:*"),
                new ProtocolInfo("http-get:*:video/x-matroska:*"),
                new ProtocolInfo("http-get:*:video/x-ms-wmv:*"),
                new ProtocolInfo("http-get:*:video/x-ms-avi:*"),
                new ProtocolInfo("http-get:*:video/x-flv:*"),
                new ProtocolInfo("http-get:*:video/x-fli:*"),
                new ProtocolInfo("http-get:*:video/x-ms-asf:*"),
                new ProtocolInfo("http-get:*:video/x-ms-asx:*"),
                new ProtocolInfo("http-get:*:video/x-ms-wmx:*"),
                new ProtocolInfo("http-get:*:video/x-ms-wvx:*"),
                new ProtocolInfo("http-get:*:video/x-msvideo:*"),
                new ProtocolInfo("http-get:*:video/x-xvid:*"),
                new ProtocolInfo("http-get:*:audio/L16:*"),
                new ProtocolInfo("http-get:*:audio/mp3:*"),
                new ProtocolInfo("http-get:*:audio/x-mp3:*"),
                new ProtocolInfo("http-get:*:audio/mpeg:*"),
                new ProtocolInfo("http-get:*:audio/x-ms-wma:*"),
                new ProtocolInfo("http-get:*:audio/wma:*"),
                new ProtocolInfo("http-get:*:audio/mpeg3:*"),
                new ProtocolInfo("http-get:*:audio/wav:*"),
                new ProtocolInfo("http-get:*:audio/x-wav:*"),
                new ProtocolInfo("http-get:*:audio/ogg:*"),
                new ProtocolInfo("http-get:*:audio/x-ogg:*"),
                new ProtocolInfo("http-get:*:audio/musepack:*"),
                new ProtocolInfo("http-get:*:audio/x-musepack:*"),
                new ProtocolInfo("http-get:*:audio/flac:*"),
                new ProtocolInfo("http-get:*:audio/x-flac:*"),
                new ProtocolInfo("http-get:*:audio/mp4:*"),
                new ProtocolInfo("http-get:*:audio/m4a:*"),
                new ProtocolInfo("http-get:*:audio/aiff:*"),
                new ProtocolInfo("http-get:*:audio/x-aiff:*"),
                new ProtocolInfo("http-get:*:audio/basic:*"),
                new ProtocolInfo("http-get:*:audio/x-wavpack:*"),
                new ProtocolInfo("http-get:*:application/octet-stream:*"));
    }

    private byte[] getIconAsByteArray(int drawableId, Bitmap.CompressFormat format) {

        Drawable drawable = ResourcesCompat.getDrawable(getResources(), drawableId, getTheme());
        byte[] result = null;
        if (drawable != null) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(format, 100, stream);
            result = stream.toByteArray();
        }
        return result;
    }


    public class YaaccUpnpServerServiceBinder extends Binder {
        public YaaccUpnpServerService getService() {
            return YaaccUpnpServerService.this;
        }
    }

    public NetworkDeviceListener getNetworkDeviceListener() {
        return networkDeviceListener;
    }

    public Registry getRegistry() {
        return registry;
    }


    public void onNetworkStateChange() {
        YaaccLogger.d(getClass().getName(), "Network state change - restarting UPnP device");
        if (isInitialized()) {
            // Remove old device and create new one
            if (localDevice != null) {
                registry.removeDevice(localDevice);
                localDevice = null;
            }
            createUpnpDevice();
            updateNotification();
        }
    }

    public boolean isInitialized() {
        return registry != null && networkDeviceListener.isInitalized();
    }

    // Live streaming methods (Android 10+)

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void startAudioCapture() {
        if (audioCapture != null && audioCapture.isCapturing()) {
            YaaccLogger.w(getClass().getName(), "Audio capture already running");
            return;
        }

        projection = MediaProjectionHelper.getMediaProjection();

        if (projection == null) {
            // Try to create from stored permission
            if (!MediaProjectionHelper.createMediaProjectionFromStored(this)) {
                YaaccLogger.e(getClass().getName(), "No MediaProjection available for audio capture");
                return;
            }
            projection = MediaProjectionHelper.getMediaProjection();
        }

        if (audioCapture == null) {
            audioCapture = new SystemAudioCaptureService();
            //audioCapture = new SystemAudioCaptureServiceAAC();
        }

        if (audioCapture.startCapture(projection)) {
            YaaccLogger.d(getClass().getName(), "Audio capture started successfully");
        } else {
            YaaccLogger.e(getClass().getName(), "Failed to start audio capture");
        }
    }

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void stopAudioCapture() {
        if (audioCapture != null) {
            audioCapture.stopCapture();
            YaaccLogger.d(getClass().getName(), "Audio capture stopped");
        }
    }

    public SystemAudioCaptureService getAudioCapture() {
        return audioCapture;
    }

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void startVideoCapture() {
        if (videoCapture != null && videoCapture.isCapturing()) {
            YaaccLogger.w(getClass().getName(), "Video capture already running");
            return;
        }

        projection = MediaProjectionHelper.getMediaProjection();

        if (projection == null) {
            // Try to create from stored permission
            if (!MediaProjectionHelper.createMediaProjectionFromStored(this)) {
                YaaccLogger.e(getClass().getName(), "No MediaProjection available for video capture");
                return;
            }
            projection = MediaProjectionHelper.getMediaProjection();
        }

        if (videoCapture == null) {
            videoCapture = new ScreenCastCaptureService(this);
        }

        if (videoCapture.startCapture(projection)) {
            YaaccLogger.d(getClass().getName(), "Video capture started successfully");
        } else {
            YaaccLogger.e(getClass().getName(), "Failed to start video capture");
        }
    }

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void stopVideoCapture() {
        if (videoCapture != null) {
            videoCapture.stopCapture();
            YaaccLogger.d(getClass().getName(), "Video capture stopped");
        }
    }

    public ScreenCastCaptureService getVideoCapture() {
        return videoCapture;
    }

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void startCombinedCapture() {
        YaaccLogger.i(getClass().getName(), "startCombinedCapture called");

        // Combined capture needs its own MediaProjection - try to create from stored permission
        if (!MediaProjectionHelper.createMediaProjectionFromStored(this)) {
            YaaccLogger.w(getClass().getName(), "Cannot start combined capture: failed to create MediaProjection");
            return;
        }

        MediaProjection projection = MediaProjectionHelper.getMediaProjection();

        if (projection == null) {
            YaaccLogger.w(getClass().getName(), "Cannot start combined capture: no MediaProjection");
            return;
        }

        try {
            if (combinedCapture == null) {
                combinedCapture = new CombinedCaptureService(this);
            }
            combinedCapture.startCapture(projection);
            YaaccLogger.d(getClass().getName(), "Combined capture started");
        } catch (Exception e) {
            YaaccLogger.e(getClass().getName(), "Failed to start combined capture", e);
        }
    }

    @RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    private void stopCombinedCapture() {
        if (combinedCapture != null && combinedCapture.isCapturing()) {
            combinedCapture.stopCapture();
            YaaccLogger.d(getClass().getName(), "Combined capture stopped");
        }
    }

    public CombinedCaptureService getCombinedCapture() {
        return combinedCapture;
    }

    public void restartServer() {
        YaaccLogger.d(this.getClass().getName(), "Restarting UPnP server due to interface change");

        // Reset initialization flag
        synchronized (initLock) {
            isInitialized = false;
        }

        // Stop current server
        networkDeviceListener.disable();
        if (httpServer != null) {
            httpServer.initiateShutdown();
            try {
                httpServer.awaitShutdown(TimeValue.ofSeconds(3));
            } catch (InterruptedException e) {
                YaaccLogger.w(getClass().getName(), "got exception on stream server stop ", e);
            }
            httpServer = null;
        }

        if (localDevice != null && registry != null) {
            registry.removeDevice(localDevice);
            localDevice = null;
        }

        // Trigger network state change to reinitialize listener BEFORE initialize()
        networkDeviceListener.disable();
        networkDeviceListener.enable();
        registry.removeAllLocalDevices();
        registry.removeAllRemoteDevices();
        // Re-initialize
        onNetworkStateChange();


    }
}
