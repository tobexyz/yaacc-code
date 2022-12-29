/*
 *
 * Copyright (C) 2013 www.yaacc.de
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ExceptionListener;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.SocketConfig;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ValidationError;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.DLNACaps;
import org.fourthline.cling.model.types.DLNADoc;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.protocol.async.SendingNotificationAlive;
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.ProtocolInfos;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.xmicrosoft.AbstractMediaReceiverRegistrarService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.avtransport.YaaccAVTransportService;
import de.yaacc.upnp.server.contentdirectory.YaaccContentDirectory;
import de.yaacc.util.NotificationId;

/**
 * A simple local upnp server implementation. This class encapsulate the creation
 * and registration of local upnp services. it is implemented as a android
 * service in order to run in background
 *
 * @author Tobias Schoene (openbit)
 */
public class YaaccUpnpServerService extends Service {

    private static final String UDN_ID = "35"
            + // we make this look like a valid IMEI
            Build.BOARD.length() % 10 + Build.BRAND.length() % 10 + Build.CPU_ABI.length() % 10 + Build.DEVICE.length() % 10 + Build.DISPLAY.length()
            % 10 + Build.HOST.length() % 10 + Build.ID.length() % 10 + Build.MANUFACTURER.length() % 10 + Build.MODEL.length() % 10
            + Build.PRODUCT.length() % 10 + Build.TAGS.length() % 10 + Build.TYPE.length() % 10 + Build.USER.length() % 10;
    public static final String MEDIA_SERVER_UDN_ID = UDN_ID;
    public static final String MEDIA_RENDERER_UDN_ID = UDN_ID + "-1";
    public static int PORT = 4711;
    protected IBinder binder = new YaaccUpnpServerServiceBinder();
    // make preferences available for the whole service, since there might be
    // more things to configure in the future
    SharedPreferences preferences;
    private LocalDevice localServer;
    private LocalDevice localRenderer;
    private UpnpClient upnpClient;
    private boolean watchdog;


    private HttpServer httpServer;
    private boolean initialized = false;

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this.getClass().getName(), "On Bind");
        // do nothing
        return binder;
    }

    /*
     * (non-Javadoc)
     *
     * @see android.app.Service#onStart(android.content.Intent, int)
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long start = System.currentTimeMillis();

        // when the service starts, the preferences are initialized
        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if (getUpnpClient() == null) {
            setUpnpClient(new UpnpClient());
        }
        // the footprint of the onStart() method must be small
        // otherwise android will kill the service
        // in order of this circumstance we have to initialize the service
        // asynchronous
        Thread initializationThread = new Thread(new Runnable() {

            @Override
            public void run() {
                initialize();
            }
        });
        initializationThread.start();
        showNotification();
        Log.d(this.getClass().getName(), "End On Start");
        Log.d(this.getClass().getName(), "on start took: " + (System.currentTimeMillis() - start));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "Destroying the service");
        if (getUpnpClient() != null) {
            if (localServer != null) {
                getUpnpClient().localDeviceRemoved(getUpnpClient().getRegistry(), localServer);
                localServer = null;
            }
            if (localRenderer != null) {
                getUpnpClient().localDeviceRemoved(getUpnpClient().getRegistry(), localRenderer);
                localRenderer = null;
            }

        }
        if (httpServer != null) {

            httpServer.stop();

        }
        cancleNotification();
        super.onDestroy();
    }

    /**
     * Displays the notification.
     */
    private void showNotification() {
        Intent notificationIntent = new Intent(this, YaaccUpnpServerControlActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID).setOngoing(true).setSmallIcon(R.drawable.ic_notification_default)
                .setContentTitle("Yaacc Upnp Server")
                .setContentText(preferences.getString(getApplicationContext().getString(R.string.settings_local_server_name_key), ""));
        mBuilder.setContentIntent(contentIntent);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify(NotificationId.UPNP_SERVER.getId(), mBuilder.build());
    }

    /**
     * Cancels the notification.
     */
    private void cancleNotification() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.cancel(NotificationId.UPNP_SERVER.getId());

    }

    /**
     *
     */
    private void initialize() {
        this.initialized = false;
        if (!getUpnpClient().isInitialized()) {
            getUpnpClient().initialize(getApplicationContext());
            watchdog = false;
            new Timer().schedule(new TimerTask() {

                @Override
                public void run() {
                    watchdog = true;
                }
            }, 30000L); // 30 sec. watchdog

            while (!getUpnpClient().isInitialized() && !watchdog) {
                // wait for upnpClient initialization
            }
        }
        if (getUpnpClient().isInitialized()) {
            if (preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_provider_chkbx), false)) {
                if (localServer == null) {
                    localServer = createMediaServerDevice();
                }
                getUpnpClient().getRegistry().addDevice(localServer);

                createHttpServer();
            }

            if (preferences.getBoolean(getApplicationContext().getString(R.string.settings_local_server_receiver_chkbx), false)) {
                if (localRenderer == null) {
                    localRenderer = createMediaRendererDevice();
                }
                getUpnpClient().getRegistry().addDevice(localRenderer);
            }
            this.initialized = true;
        } else {
            throw new IllegalStateException("UpnpClient is not initialized!");
        }

        startUpnpAliveNotifications();
    }

    /**
     * creates a http request thread
     */
    private void createHttpServer() {
        // Create a HttpService for providing content in the network.
        try {

            //FIXME set correct timeout
            SocketConfig socketConfig = SocketConfig.custom()
                    .setSoTimeout(15, TimeUnit.SECONDS)
                    .setTcpNoDelay(true)
                    .build();

            // Set up the HTTP service
            httpServer = ServerBootstrap.bootstrap()
                    .setListenerPort(PORT)
                    .setSocketConfig(socketConfig)
                    .setExceptionListener(new ExceptionListener() {

                        @Override
                        public void onError(final Exception ex) {
                            Log.i(getClass().getName(), "YaaccHttpServer throws exception:", ex);
                        }

                        @Override
                        public void onError(final HttpConnection conn, final Exception ex) {
                            if (ex instanceof SocketTimeoutException) {
                                Log.i(getClass().getName(), "connection timeout:", ex);
                            } else if (ex instanceof ConnectionClosedException) {
                                Log.i(getClass().getName(), "connection closed:", ex);
                            } else {
                                Log.i(getClass().getName(), "connection error:", ex);
                            }
                        }

                    })
                    .register("*", new YaaccHttpHandler(getApplicationContext()))
                    .create();


            httpServer.start();

        } catch (BindException e) {
            Log.w(this.getClass().getName(), "Server already running");
        } catch (IOException e) {
            // FIXME Ignored right error handling on rebind needed
            Log.w(this.getClass().getName(), "ContentProvider can not be initialized!", e);
            // throw new
            // IllegalStateException("ContentProvider can not be initialized!",
            // e);
        }
    }

    /**
     * start sending periodical upnp alive notifications.
     */
    private void startUpnpAliveNotifications() {
        int upnpNotificationFrequency = getUpnpNotificationFrequency();
        if (upnpNotificationFrequency != -1 && preferences.getBoolean(getString(R.string.settings_local_server_chkbx), false)) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    Log.d(YaaccUpnpServerService.this.getClass().getName(), "Sending upnp alive notivication");
                    SendingNotificationAlive sendingNotificationAlive = null;
                    if (localServer != null) {
                        sendingNotificationAlive = new SendingNotificationAlive(getUpnpClient().getRegistry().getUpnpService(), localServer);
                        sendingNotificationAlive.run();
                    }
                    if (localRenderer != null) {
                        sendingNotificationAlive = new SendingNotificationAlive(getUpnpClient().getRegistry().getUpnpService(), localRenderer);
                        sendingNotificationAlive.run();
                    }
                    startUpnpAliveNotifications();
                }
            }, upnpNotificationFrequency);

        }
    }

    /**
     * the time between two upnp alive notifications. -1 if never send a
     * notification
     *
     * @return the time
     */
    private int getUpnpNotificationFrequency() {
        return Integer.parseInt(preferences.getString(getUpnpClient().getContext().getString(R.string.settings_sending_upnp_alive_interval_key), "5000"));
    }

    /**
     * Create a local upnp renderer device
     *
     * @return the device
     */
    private LocalDevice createMediaRendererDevice() {
        LocalDevice device;
        String versionName;
        Log.d(this.getClass().getName(), "Create MediaRenderer with ID: " + MEDIA_SERVER_UDN_ID);
        try {
            versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionName;
        } catch (NameNotFoundException ex) {
            Log.e(this.getClass().getName(), "Error while creating device", ex);
            versionName = "??";
        }
        try {
            device = new LocalDevice(new DeviceIdentity(new UDN(MEDIA_RENDERER_UDN_ID)), new UDADeviceType("MediaRenderer", 3),
                    // Used for shown name: first part of ManufactDet, first
                    // part of ModelDet and version number
                    new DeviceDetails("YAACC - MediaRenderer (" + getLocalServerName() + ")",
                            new ManufacturerDetails("yaacc", "http://www.yaacc.de"),
                            new ModelDetails(getLocalServerName() + "-Renderer", "Free Android UPnP AV MediaRender, GNU GPL", versionName),
                            new DLNADoc[]{
                                    new DLNADoc("DMS", DLNADoc.Version.V1_5),
                                    new DLNADoc("M-DMS", DLNADoc.Version.V1_5)
                            },
                            new DLNACaps(new String[]{"av-upload", "image-upload", "audio-upload"})), createDeviceIcons(), createMediaRendererServices(), null);

            return device;
        } catch (ValidationException e) {
            for (ValidationError validationError : e.getErrors()) {
                Log.d(getClass().getCanonicalName(), validationError.toString());
            }
            throw new IllegalStateException("Exception during device creation", e);
        }

    }

    /**
     * Create a local upnp renderer device
     *
     * @return the device
     */
    private LocalDevice createMediaServerDevice() {
        // https://bitbucket.org/longkerdandy/chii2/src/
        LocalDevice device;
        String versionName;
        Log.d(this.getClass().getName(), "Create MediaServer whith ID: " + MEDIA_SERVER_UDN_ID);
        try {
            versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionName;
        } catch (NameNotFoundException ex) {
            Log.e(this.getClass().getName(), "Error while creating device", ex);
            versionName = "??";
        }
        try {

            // Yaacc Details
            // Used for shown name: first part of ManufactDet, first
            // part of ModelDet and version number
            DeviceDetails yaaccDetails = new DeviceDetails(
                    "YAACC - MediaServer(" + getLocalServerName() + ")", new ManufacturerDetails("yaacc.de",
                    "http://www.yaacc.de"), new ModelDetails(getLocalServerName() + "-MediaServer", "Free Android UPnP AV MediaServer, GNU GPL",
                    versionName));

            DeviceIdentity identity = new DeviceIdentity(new UDN(MEDIA_SERVER_UDN_ID));

            device = new LocalDevice(identity, new UDADeviceType("MediaServer"), yaaccDetails, createDeviceIcons(), createMediaServerServices());

            return device;
        } catch (ValidationException e) {
            Log.e(this.getClass().getName(), "Exception during device creation", e);
            Log.e(this.getClass().getName(), "Exception during device creation Errors:" + e.getErrors());
            throw new IllegalStateException("Exception during device creation", e);
        }

    }

    private Icon[] createDeviceIcons() {
        Drawable drawable = getResources().getDrawable(R.drawable.yaacc120_jpg);
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        //
        // return new Icon("image/png", 48, 48, 24,
        // URI.create("/icon.png"),stream.toByteArray());
        ArrayList<Icon> icons = new ArrayList<Icon>();

        icons.add(new Icon("image/jpeg", 120, 120, 24, "yaacc120.jpg", getIconAsByteArray(R.drawable.yaacc120_jpg)));
        icons.add(new Icon("image/jpeg", 48, 48, 24, "yaacc48.jpg", getIconAsByteArray(R.drawable.yaacc48_jpg)));
        icons.add(new Icon("image/jpeg", 32, 32, 24, "yaacc32.jpg", getIconAsByteArray(R.drawable.yaacc32_jpg)));
        icons.add(new Icon("image/bmp", 120, 120, 24, "yaacc120_24.bmp", getIconAsByteArray(R.drawable.yaacc120_24_bmp)));
        icons.add(new Icon("image/png", 120, 120, 24, "yaacc120_24.png", getIconAsByteArray(R.drawable.yaacc120_24_png)));
        icons.add(new Icon("image/bmp", 120, 120, 8, "yaacc120_8.bmp", getIconAsByteArray(R.drawable.yaacc120_8_bmp)));
        icons.add(new Icon("image/png", 120, 120, 8, "yaacc120_8.png", getIconAsByteArray(R.drawable.yaacc120_8_png)));
        icons.add(new Icon("image/png", 48, 48, 24, "yaacc48_24.bmp", getIconAsByteArray(R.drawable.yaacc48_24_bmp)));
        icons.add(new Icon("image/png", 48, 48, 24, "yaacc48_24.png", getIconAsByteArray(R.drawable.yaacc48_24_png)));
        icons.add(new Icon("image/bmp", 48, 48, 8, "yaacc48_8.bmp", getIconAsByteArray(R.drawable.yaacc48_8_bmp)));
        icons.add(new Icon("image/png", 48, 48, 8, "yaacc48_8.png", getIconAsByteArray(R.drawable.yaacc48_8_png)));
        icons.add(new Icon("image/bmp", 32, 32, 24, "yaacc32_24.bmp", getIconAsByteArray(R.drawable.yaacc32_24_bmp)));
        icons.add(new Icon("image/png", 32, 32, 24, "yaacc32_24.png", getIconAsByteArray(R.drawable.yaacc32_24_png)));
        icons.add(new Icon("image/bmp", 32, 32, 8, "yaacc32_8.bmp", getIconAsByteArray(R.drawable.yaacc32_8_bmp)));
        icons.add(new Icon("image/png", 32, 32, 8, "yaacc32_8.png", getIconAsByteArray(R.drawable.yaacc32_8_png)));
        return icons.toArray(new Icon[icons.size()]);
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
        List<LocalService<?>> services = new ArrayList<LocalService<?>>();
        services.add(createContentDirectoryService());
        services.add(createServerConnectionManagerService());
        services.add(createMediaReceiverRegistrarService());
        return services.toArray(new LocalService[]{});
    }

    /**
     * Create the renderer services provided by the device
     *
     * @return the services
     */
    private LocalService<?>[] createMediaRendererServices() {
        List<LocalService<?>> services = new ArrayList<LocalService<?>>();
        services.add(createAVTransportService());
        services.add(createRendererConnectionManagerService());
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
        LocalService<YaaccContentDirectory> contentDirectoryService = new AnnotationLocalServiceBinder().read(YaaccContentDirectory.class);
        contentDirectoryService.setManager(new DefaultServiceManager<YaaccContentDirectory>(contentDirectoryService, null) {

            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected YaaccContentDirectory createServiceInstance() throws Exception {
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
        LocalService<YaaccAVTransportService> avTransportService = new AnnotationLocalServiceBinder().read(YaaccAVTransportService.class);
        avTransportService.setManager(new DefaultServiceManager<YaaccAVTransportService>(avTransportService, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected YaaccAVTransportService createServiceInstance() throws Exception {
                return new YaaccAVTransportService(getUpnpClient());
            }
        });
        return avTransportService;
    }

    private LocalService<AbstractAudioRenderingControl> createRenderingControl() {
        LocalService<AbstractAudioRenderingControl> renderingControlService = new AnnotationLocalServiceBinder()
                .read(AbstractAudioRenderingControl.class);
        renderingControlService.setManager(new DefaultServiceManager<AbstractAudioRenderingControl>(renderingControlService, null) {
            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected AbstractAudioRenderingControl createServiceInstance() throws Exception {
                return new YaaccAudioRenderingControlService(getUpnpClient());
            }
        });
        return renderingControlService;
    }

    private LocalService<AbstractMediaReceiverRegistrarService> createMediaReceiverRegistrarService() {
        LocalService<AbstractMediaReceiverRegistrarService> service = new AnnotationLocalServiceBinder()
                .read(AbstractMediaReceiverRegistrarService.class);
        service.setManager(new DefaultServiceManager<AbstractMediaReceiverRegistrarService>(service, null) {

            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected AbstractMediaReceiverRegistrarService createServiceInstance() throws Exception {
                return new YaaccMediaReceiverRegistrarService(getUpnpClient());
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
    private LocalService<ConnectionManagerService> createServerConnectionManagerService() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        final ProtocolInfos sourceProtocols = getSourceProtocolInfos();

        service.setManager(new DefaultServiceManager<ConnectionManagerService>(service, ConnectionManagerService.class) {

            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected ConnectionManagerService createServiceInstance() throws Exception {
                return new ConnectionManagerService(sourceProtocols, null);
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
    private LocalService<ConnectionManagerService> createRendererConnectionManagerService() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        final ProtocolInfos sinkProtocols = getSinkProtocolInfos();
        service.setManager(new DefaultServiceManager<ConnectionManagerService>(service, ConnectionManagerService.class) {

            @Override
            protected int getLockTimeoutMillis() {
                return 1000;
            }

            @Override
            protected ConnectionManagerService createServiceInstance() throws Exception {
                return new ConnectionManagerService(null, sinkProtocols);
            }
        });

        return service;
    }

    /**
     * @return
     */
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

    private byte[] getIconAsByteArray(int drawableId) {

        Drawable drawable = getResources().getDrawable(drawableId);
        byte[] result = null;
        if (drawable != null) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            result = stream.toByteArray();
        }
        return result;
    }

    /**
     * @return the upnpClient
     */
    public UpnpClient getUpnpClient() {
        return upnpClient;
    }

    /**
     * @param upnpClient the upnpClient to set
     */
    private void setUpnpClient(UpnpClient upnpClient) {
        this.upnpClient = upnpClient;
    }

    // private boolean isYaaccUpnpServerServiceRunning() {
    // ActivityManager manager = (ActivityManager)
    // getSystemService(Context.ACTIVITY_SERVICE);
    // for (RunningServiceInfo service :
    // manager.getRunningServices(Integer.MAX_VALUE)) {
    // if (this.getClass().getName().equals(service.service.getClassName())) {
    // return true;
    // }
    // }
    // return false;
    // }

    /**
     * @return the initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    public class YaaccUpnpServerServiceBinder extends Binder {
        public YaaccUpnpServerService getService() {
            return YaaccUpnpServerService.this;
        }
    }
}
