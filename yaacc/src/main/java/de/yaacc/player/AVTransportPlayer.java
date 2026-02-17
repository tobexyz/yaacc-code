/*
 * Copyright (C) 2013 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.ui.PlayerNotificationManager;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.ProtocolInfos;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.model.item.Item;

import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.settings.SettingsFragment;
import de.yaacc.upnp.ActionState;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.avtransport.GetPositionInfo;
import de.yaacc.upnp.callback.avtransport.GetTransportInfo;
import de.yaacc.upnp.callback.avtransport.Pause;
import de.yaacc.upnp.callback.avtransport.Play;
import de.yaacc.upnp.callback.avtransport.Seek;
import de.yaacc.upnp.callback.avtransport.SetAVTransportURI;
import de.yaacc.upnp.callback.avtransport.Stop;
import de.yaacc.upnp.callback.connectionmanager.GetProtocolInfo;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler;
import de.yaacc.util.InterfaceResolutionHelper;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadCacheHandler;
import de.yaacc.util.image.ImageDownloader;

/**
 * A Player for playing on a remote avtransport device
 *
 * @author Tobias Schoene (openbit)
 */
@UnstableApi
public class AVTransportPlayer extends AbstractPlayer {


    private final ExecutorService executorService;
    private String deviceId = "";
    private int id;
    private String contentType;
    private PositionInfo currentPositionInfo;
    private ActionState positionActionState = null;
    private URI albumArtUri;
    private AVTransportPlayerWrapper playerWrapper;
    private MediaSession media3Session;
    private PlayerNotificationManager notificationManager;
    private int consecutivePositionFailures = 0;

    // Retry tracking for critical commands
    private static final int MAX_RETRIES = 30;
    private final Map<String, Integer> commandRetries = new HashMap<>();


    /**
     * @param upnpClient the client
     * @param name       playerName
     */
    public AVTransportPlayer(UpnpClient upnpClient, Device<?, ?, ?> receiverDevice, String name, String shortName, String contentType) {
        this(upnpClient);
        deviceId = receiverDevice.getIdentity().getUdn().getIdentifierString();
        setName(name);
        setShortName(shortName);
        this.contentType = contentType;
        // id already initialized in base constructor
        setDeviceIcon(receiverDevice);

        // Configure MediaSession for remote volume control now that device is set
        new Handler(Looper.getMainLooper()).post(() -> {
            if (getMediaSession() != null) {
                configureMediaSession(getMediaSession());
            }
        });
    }

    /**
     * @param upnpClient the client
     */
    public AVTransportPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        executorService = Executors.newFixedThreadPool(20);

        // Generate ID first (required for notification)
        id = Math.abs(UUID.randomUUID().hashCode());

        // Initialize Media3 Player wrapper
        playerWrapper = new AVTransportPlayerWrapper(this, null);
        BitmapLoader bitmapLoader = new BitmapLoader() {
            @Override
            public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
                SettableFuture<Bitmap> future = SettableFuture.create();
                try {
                    Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                    future.set(bitmap);
                } catch (Exception e) {
                    future.setException(e);
                }
                return future;
            }

            @Override
            public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
                return loadBitmap(uri, null);
            }

            @Override
            public ListenableFuture<Bitmap> loadBitmap(Uri uri, @Nullable BitmapFactory.Options options) {
                YaaccLogger.e(getClass().getName(), "BitmapLoader.loadBitmap called with uri: " + uri);

                // Check cache first
                IconDownloadCacheHandler cache = IconDownloadCacheHandler.getInstance();
                Bitmap cachedBitmap = cache.getBitmap(uri, 512, 512);
                if (cachedBitmap != null) {
                    YaaccLogger.e(getClass().getName(), "Returning cached bitmap: " + cachedBitmap.getWidth() + "x" + cachedBitmap.getHeight());
                    return Futures.immediateFuture(cachedBitmap);
                }

                SettableFuture<Bitmap> future = SettableFuture.create();
                // Load bitmap in background using ImageDownloader
                ((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor().execute(() -> {
                    try {
                        YaaccLogger.e(getClass().getName(), "Loading bitmap from: " + uri);
                        Bitmap bitmap = new ImageDownloader().retrieveImageWithCertainSize(uri, 512, 512);
                        if (bitmap != null) {
                            cache.addBitmap(uri, 512, 512, bitmap);
                        }
                        YaaccLogger.e(getClass().getName(), "Bitmap loaded: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
                        future.set(bitmap);
                        YaaccLogger.e(getClass().getName(), "Future.set() called");
                    } catch (Exception e) {
                        YaaccLogger.e(getClass().getName(), "Failed to load bitmap", e);
                        future.setException(e);
                    }
                });
                return future;
            }


        };
        // Create Media3 MediaSession for the wrapper
        media3Session = new MediaSession.Builder(getContext(), playerWrapper)
                .setId("avtransport_" + id)
                // Don't set BitmapLoader - let notification manager handle it via getCurrentLargeIcon()
                .setCallback(new MediaSession.Callback() {
                    @Override
                    public MediaSession.ConnectionResult onConnect(MediaSession session,
                                                                   MediaSession.ControllerInfo controller) {
                        MediaSession.ConnectionResult result = MediaSession.Callback.super.onConnect(session, controller);

                        // Enable device volume commands
                        SessionCommands.Builder commandsBuilder = result.availableSessionCommands.buildUpon();
                        commandsBuilder.add(new SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING));

                        return MediaSession.ConnectionResult.accept(
                                commandsBuilder.build(),
                                result.availablePlayerCommands
                        );
                    }
                })
                .build();

        // Add listener to wrapper so Media3 session gets updates
        playerWrapper.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                YaaccLogger.d(getClass().getName(), "Media3: onMediaItemTransition - " +
                        (mediaItem != null ? mediaItem.mediaMetadata.title : "null"));
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                YaaccLogger.d(getClass().getName(), "Media3: onIsPlayingChanged - " + isPlaying);
            }
        });

        // Create notification manager
        notificationManager = new PlayerNotificationManager.Builder(
                getContext(),
                getNotificationId(),
                Yaacc.NOTIFICATION_CHANNEL_ID)
                .setMediaDescriptionAdapter(new PlayerNotificationManager.MediaDescriptionAdapter() {
                    @Override
                    public CharSequence getCurrentContentTitle(Player player) {
                        return getCurrentItemTitle();
                    }

                    @Override
                    public PendingIntent createCurrentContentIntent(Player player) {
                        return getNotificationIntent();
                    }

                    @Override
                    public CharSequence getCurrentContentText(Player player) {
                        return getName();
                    }

                    @Override
                    public Bitmap getCurrentLargeIcon(Player player,
                                                      PlayerNotificationManager.BitmapCallback callback) {
                        // Get album art URI from AVTransportPlayer (includes cover.jpg fallback)
                        URI albumArtJavaUri = getAlbumArt();
                        YaaccLogger.e(getClass().getName(), "getCurrentLargeIcon called, albumArtUri: " + albumArtJavaUri);

                        if (albumArtJavaUri != null) {
                            android.net.Uri artworkUri = android.net.Uri.parse(albumArtJavaUri.toString());

                            // Check cache first - return immediately if available
                            IconDownloadCacheHandler cache = IconDownloadCacheHandler.getInstance();
                            Bitmap cachedBitmap = cache.getBitmap(artworkUri, 512, 512);
                            if (cachedBitmap != null) {
                                YaaccLogger.e(getClass().getName(), "Returning cached bitmap synchronously: " + cachedBitmap.getWidth() + "x" + cachedBitmap.getHeight());
                                return cachedBitmap;
                            }

                            // Load bitmap in background thread and use callback
                            ((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor().execute(() -> {
                                try {
                                    YaaccLogger.e(getClass().getName(), "Loading bitmap from: " + artworkUri);
                                    Bitmap bitmap = new ImageDownloader().retrieveImageWithCertainSize(artworkUri, 512, 512);
                                    if (bitmap != null) {
                                        cache.addBitmap(artworkUri, 512, 512, bitmap);
                                        YaaccLogger.e(getClass().getName(), "Bitmap loaded, calling callback: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                        callback.onBitmap(bitmap);
                                    } else {
                                        YaaccLogger.e(getClass().getName(), "Bitmap is null");
                                    }
                                } catch (Exception e) {
                                    YaaccLogger.e(getClass().getName(), "Failed to load album art", e);
                                }
                            });
                        } else {
                            YaaccLogger.e(getClass().getName(), "albumArtUri is null");
                        }
                        return null;
                    }
                })
                .build();

        notificationManager.setUseNextAction(true);
        notificationManager.setUsePreviousAction(true);
        notificationManager.setUseNextActionInCompactView(true);
        notificationManager.setUsePreviousActionInCompactView(true);
        notificationManager.setSmallIcon(R.drawable.ic_notification_default);
        notificationManager.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
        notificationManager.setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT);

        YaaccLogger.d(getClass().getName(), "Setting up PlayerNotificationManager");

        // setPlayer must be called on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            YaaccLogger.d(getClass().getName(), "Calling notificationManager.setPlayer()");
            notificationManager.setPlayer(playerWrapper);
            notificationManager.setMediaSessionToken(media3Session.getSessionCompatToken());
            YaaccLogger.d(getClass().getName(), "PlayerNotificationManager setup complete");
        });
    }

    @Override
    protected void configureMediaSession(MediaSessionCompat mediaSession) {
        // Don't configure legacy MediaSession - we're using Media3 MediaSession instead
        // Deactivate it so only Media3 session is active
        mediaSession.setActive(false);
        YaaccLogger.d(getClass().getName(), "Deactivated legacy MediaSession - using Media3");
    }

    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
        super.onServiceConnected(className, binder);

        // Register Media3 MediaSession with PlayerService (if initialized)
        if (media3Session != null && binder instanceof PlayerService.PlayerServiceBinder) {
            PlayerService playerService = ((PlayerService.PlayerServiceBinder) binder).getService();
            playerService.registerMediaSession(media3Session);
            YaaccLogger.d(getClass().getName(), "Media3 MediaSession registered with PlayerService");

            // Trigger initial device info query to activate volume control
            new Handler(Looper.getMainLooper()).post(() -> {
                if (playerWrapper != null) {
                    playerWrapper.getDeviceInfo();
                    playerWrapper.getDeviceVolume();
                }
            });
        }
    }

    protected Device<?, ?, ?> getDevice() {
        return getUpnpClient().getDevice(deviceId);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getContentType() {
        return contentType;
    }

    /**
     * Helper to track and check if command should be retried
     *
     * @param commandKey Unique key for the command (e.g., "play_123")
     * @return true if should retry, false if max retries reached
     */
    private boolean shouldRetry(String commandKey) {
        int retries = commandRetries.getOrDefault(commandKey, 0);
        if (retries < MAX_RETRIES) {
            commandRetries.put(commandKey, retries + 1);
            YaaccLogger.w(getClass().getName(), "Retrying " + commandKey + " (attempt " + (retries + 1) + "/" + MAX_RETRIES + ")");
            return true;
        }
        commandRetries.remove(commandKey);
        YaaccLogger.e(getClass().getName(), "Max retries reached for " + commandKey);
        return false;
    }

    /**
     * Reset retry counter for successful command
     */
    private void resetRetry(String commandKey) {
        commandRetries.remove(commandKey);
    }

    protected de.yaacc.upnp.server.http.HttpRequestSender getHttpRequestSender() {
        return getUpnpClient().getYaaccUpnpServerService().getNetworkDeviceListener().getHttpRequestSender();
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#stopItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected void stopItem(PlayableItem playableItem) {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        final ActionState actionState = new ActionState();
// Now start Stopping
        YaaccLogger.d(getClass().getName(), "Action Stop");
        doStopWithRetry(service, "stop_" + System.currentTimeMillis());
    }

    private void doStopWithRetry(Service<?, ?> service, final String retryKey) {
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Stop actionCallback = new Stop(service, getHttpRequestSender()) {
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

                // Retry on failure
                if (shouldRetry(retryKey)) {
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            doStopWithRetry(service, retryKey);
                        }
                    }, new Date(System.currentTimeMillis() + 1000));
                }
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                resetRetry(retryKey);
                actionState.actionFinished = true;
            }
        };
        executorService.execute(actionCallback);
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#loadItem(de.yaacc.player.PlayableItem)
     */
    @Override
    protected Object loadItem(PlayableItem playableItem) {
        return playableItem;
    }

    /* (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#startItem(de.yaacc.player.PlayableItem, java.lang.Object)
     */
    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
        if (playableItem == null || getDevice() == null)
            return;

        // Request audio focus for lock screen volume control
        if (media3Session != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                // Trigger a state update to make this session active
                playerWrapper.notifyPlaybackStateChanged();
            });
        }

        // Try to select best resource for this device
        PlayableItem deviceOptimizedItem = selectBestResourceForDevice(playableItem);

        YaaccLogger.d(getClass().getName(), "Uri: " + deviceOptimizedItem.getUri());
        YaaccLogger.d(getClass().getName(), "Duration: " + deviceOptimizedItem.getDuration());
        YaaccLogger.d(getClass().getName(),
                "MimeType: " + deviceOptimizedItem.getMimeType());
        YaaccLogger.d(getClass().getName(), "Title: " + deviceOptimizedItem.getTitle());
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }

        // Check transport state first and handle accordingly
        checkTransportStateForStart(deviceOptimizedItem, service);
    }

    /**
     * Select the best resource for this specific device based on supported protocols
     */
    private PlayableItem selectBestResourceForDevice(PlayableItem playableItem) {
        Item item = playableItem.getItem();
        if (item == null || item.getResources().isEmpty()) {
            return playableItem;
        }

        // Get device's supported protocols
        Service<?, ?> cmService = getDevice().findService(new UDAServiceType("ConnectionManager"));
        if (cmService == null) {
            YaaccLogger.d(getClass().getName(), "No ConnectionManager service, using default resource");
            return playableItem;
        }

        // Query supported protocols synchronously
        final ProtocolInfos[] supportedProtocols = new ProtocolInfos[1];
        final CountDownLatch latch = new CountDownLatch(1);

        executorService.execute(
                new GetProtocolInfo(cmService, getHttpRequestSender()) {
                    @Override
                    public void received(ActionInvocation actionInvocation, ProtocolInfos sinkProtocolInfos, ProtocolInfos sourceProtocolInfos) {
                        supportedProtocols[0] = sinkProtocolInfos;
                        latch.countDown();
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        YaaccLogger.d(AVTransportPlayer.class.getName(), "GetProtocolInfo failed: " + defaultMsg);
                        latch.countDown();
                    }
                }
        );

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            YaaccLogger.d(getClass().getName(), "GetProtocolInfo timeout");
            return playableItem;
        }

        if (supportedProtocols[0] == null || supportedProtocols[0].isEmpty()) {
            YaaccLogger.d(getClass().getName(), "No supported protocols found, using default resource");
            return playableItem;
        }

        // Find best matching resource
        Res bestMatch = null;
        long bestBitrate = 0;

        for (Res resource : item.getResources()) {
            if (resource.getProtocolInfo() == null) continue;

            String contentFormat = resource.getProtocolInfo().getContentFormat();
            if (contentFormat == null || contentFormat.isEmpty()) continue;

            // Check if device supports this format
            boolean supported = false;
            for (ProtocolInfo deviceProtocol : supportedProtocols[0]) {
                if (deviceProtocol.getContentFormat().equals(contentFormat) ||
                        deviceProtocol.getContentFormat().equals("*") ||
                        deviceProtocol.getContentFormat().startsWith(contentFormat.split("/")[0] + "/*")) {
                    supported = true;
                    break;
                }
            }

            if (!supported) {
                YaaccLogger.d(getClass().getName(), "Device doesn't support: " + contentFormat);
                continue;
            }

            // Among supported formats, prefer higher bitrate
            Long bitrate = resource.getBitrate();
            if (bitrate != null && bitrate > bestBitrate) {
                bestBitrate = bitrate;
                bestMatch = resource;
            } else if (bestMatch == null) {
                bestMatch = resource;
            }
        }

        if (bestMatch != null && !bestMatch.equals(item.getFirstResource())) {
            YaaccLogger.d(getClass().getName(), "Selected device-optimized resource: " +
                    bestMatch.getProtocolInfo().getContentFormat() + " bitrate: " + bestMatch.getBitrate());
            // Create new PlayableItem with selected resource
            Item optimizedItem = new Item(item);
            optimizedItem.setResources(java.util.Collections.singletonList(bestMatch));
            return new PlayableItem(optimizedItem, (int) playableItem.getDuration());
        }

        return playableItem;
    }

    private void checkTransportStateForStart(PlayableItem playableItem, Service<?, ?> service) {
        // Check current transport state first
        GetTransportInfo stateCheck = new GetTransportInfo(service, getHttpRequestSender()) {
            @Override
            public void received(ActionInvocation actioninvocation, TransportInfo transportInfo) {
                TransportState state = transportInfo.getCurrentTransportState();
                YaaccLogger.d(getClass().getName(), "Current state before Play: " + state);

                // Only resume if paused AND not changing tracks (paused flag is true)
                if (state == TransportState.PAUSED_PLAYBACK) {
                    YaaccLogger.d(getClass().getName(), "Resuming from pause, sending Play only");
                    // For paused content, just send Play command without SetAVTransportURI
                    Play playCallback = new Play(service, getHttpRequestSender()) {
                        @Override
                        public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                            YaaccLogger.d(getClass().getName(), "Resume Play failed: " + s);
                            setProcessingCommand(false);
                        }

                        @Override
                        public void success(ActionInvocation invocation) {
                            YaaccLogger.d(getClass().getName(), "Resume Play succeeded");
                            setProcessingCommand(false);
                            setPlaying(true);
                            playerWrapper.notifyPlaybackStateChanged();
                        }
                    };
                    executorService.execute(playCallback);
                } else {
                    // For stopped or playing state, do full restart
                    YaaccLogger.d(getClass().getName(), "Sending Stop command to ensure clean state");
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            stop();
                            // Wait a bit then proceed with SetURI
                            executeCommand(new TimerTask() {
                                @Override
                                public void run() {
                                    proceedWithSetURI(playableItem, service);
                                }
                            }, new Date(System.currentTimeMillis() + 200));
                        }
                    }, new Date());
                }
            }

            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "GetTransportInfo failed, proceeding with full restart");
                // If we can't get state, do full restart
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        stop();
                        executeCommand(new TimerTask() {
                            @Override
                            public void run() {
                                proceedWithSetURI(playableItem, service);
                            }
                        }, new Date(System.currentTimeMillis() + 200));
                    }
                }, new Date());
            }
        };
        executorService.execute(stateCheck);
    }

    private void proceedWithSetURI(PlayableItem playableItem, Service<?, ?> service) {
        YaaccLogger.d(getClass().getName(), "Action SetAVTransportURI ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Item item = playableItem.getItem();

        // Check if this device needs server-side range management
        String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        String prefKey = SettingsFragment.MANAGE_EXTERNAL_SEEKING + deviceId;
        boolean useServerSideManagement = preferences.getBoolean(prefKey, false);

        YaaccLogger.d(getClass().getName(), "Device ID: " + deviceId);
        YaaccLogger.d(getClass().getName(), "Preference key: " + prefKey);
        YaaccLogger.d(getClass().getName(), "Server-side management enabled: " + useServerSideManagement);
        YaaccLogger.d(getClass().getName(), "Item is null: " + (item == null));

        if (useServerSideManagement && item != null) {
            // Make a copy of the item to avoid modifying the shared instance
            try {
                // Create a new item with the same properties but modifiable resources
                Item itemCopy = new Item(item.getId(), item.getParentID(), item.getTitle(),
                        item.getCreator(), item.getClazz());

                // Copy all properties
                for (DIDLObject.Property property : item.getProperties()) {
                    itemCopy.addProperty(property);
                }

                // Copy and modify resources
                for (Res resource : item.getResources()) {
                    String originalUri = resource.getValue();
                    String modifiedUri = modifyProxyUrlWithDeviceId(originalUri);

                    Res newResource = new Res(resource.getProtocolInfo(), resource.getSize(),
                            resource.getDuration(), resource.getBitrate(), modifiedUri);
                    itemCopy.addResource(newResource);

                    if (!originalUri.equals(modifiedUri)) {
                        YaaccLogger.d(getClass().getName(), "Modified copied item resource URI: " + modifiedUri);
                    }
                }

                item = itemCopy;
            } catch (Exception e) {
                YaaccLogger.e(getClass().getName(), "Failed to copy/modify item: " + e.getMessage());
                item = playableItem.getItem(); // Fall back to original
            }
        }

        String metadata;
        try {
            metadata = new DIDLParser().generate((item == null) ? new DIDLContent() : new DIDLContent().addItem(item), false);

        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "Error while generating Didl-Item xml: " + e);
            metadata = "";
        }
        DIDLObject.Property<URI> albumArtUriProperty = playableItem.getItem() == null ? null : playableItem.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();
        
        // Trigger notification update with new album art
        if (albumArtUri != null) {
            updateMetadataInternal();
        }

        InternalSetAVTransportURI setAVTransportURI = new InternalSetAVTransportURI(
                service, modifyProxyUrlWithDeviceId(playableItem.getUri().toString()), actionState, metadata,
                getHttpRequestSender());
        YaaccLogger.d(getClass().getName(), "Original URI: " + playableItem.getUri().toString());
        YaaccLogger.d(getClass().getName(), "Modified URI: " + modifyProxyUrlWithDeviceId(playableItem.getUri().toString()));
        executorService.execute(setAVTransportURI);
        waitForActionComplete(actionState);
        int tries = 1;
        if (setAVTransportURI.hasFailures) {
            //another try
            YaaccLogger.d(getClass().getName(), "setAVTransportURI.hasFailures");
            while (setAVTransportURI.hasFailures && tries < 4) {
                tries++;
                YaaccLogger.d(getClass().getName(), "setAVTransportURI.hasFailures retry:" + tries);
                setAVTransportURI.hasFailures = false;
                executorService.execute(setAVTransportURI);
                waitForActionComplete(actionState);
            }
        }
        if (setAVTransportURI.hasFailures) {
            //another try
            YaaccLogger.d(getClass().getName(), "Can't set AVTransportURI. Giving up");
            return;
        }
// Now start Playing
        YaaccLogger.d(getClass().getName(), "Action Play");
        lastRemainingTime = -1; // Reset to ensure timer gets set for new track
        playRetryCount = 0; // Reset retry counter for new track

        // Add small delay before Play command to let renderer process URI
        executeCommand(new TimerTask() {
            @Override
            public void run() {
                // Check current state before sending Play
                GetTransportInfo stateCheck = new GetTransportInfo(service, getHttpRequestSender()) {
                    @Override
                    public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                        YaaccLogger.d(getClass().getName(), "Failed to get transport state, sending Play anyway");
                        startPlayAction(service, actionState);
                    }

                    @Override
                    public void received(ActionInvocation actioninvocation, TransportInfo transportInfo) {
                        TransportState state = transportInfo.getCurrentTransportState();
                        YaaccLogger.d(getClass().getName(), "Current state before Play: " + state);

                        if (state == TransportState.STOPPED) {
                            YaaccLogger.d(getClass().getName(), "Valid state for Play command, proceeding");
                            startPlayAction(service, actionState);
                        } else if (state == TransportState.PAUSED_PLAYBACK) {
                            YaaccLogger.d(getClass().getName(), "Resuming from pause, sending Play only");
                            // For paused content, just send Play command without SetAVTransportURI
                            Play playCallback = new Play(service, getHttpRequestSender()) {
                                @Override
                                public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                                    YaaccLogger.d(getClass().getName(), "Resume Play failed: " + s);
                                    setProcessingCommand(false);
                                }

                                @Override
                                public void success(ActionInvocation invocation) {
                                    YaaccLogger.d(getClass().getName(), "Resume Play succeeded");
                                    setProcessingCommand(false);
                                    setPlaying(true);
                                    playerWrapper.notifyPlaybackStateChanged();
                                }
                            };
                            executorService.execute(playCallback);
                        } else if (state == TransportState.PLAYING) {
                            YaaccLogger.d(getClass().getName(), "Already playing, sending Stop first then Play");
                            Stop stopCallback = new Stop(service, getHttpRequestSender()) {
                                @Override
                                public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                                    YaaccLogger.d(getClass().getName(), "Stop before Play failed: " + s);
                                    startPlayAction(service, actionState);
                                }

                                @Override
                                public void success(ActionInvocation invocation) {
                                    YaaccLogger.d(getClass().getName(), "Stop succeeded, now sending Play");
                                    executeCommand(new TimerTask() {
                                        @Override
                                        public void run() {
                                            startPlayAction(service, actionState);
                                        }
                                    }, new Date(System.currentTimeMillis() + 200));
                                }
                            };
                            executorService.execute(stopCallback);
                        } else {
                            YaaccLogger.d(getClass().getName(), "Unknown state: " + state + ", sending Play anyway");
                            startPlayAction(service, actionState);
                        }
                    }
                };
                executorService.execute(stateCheck);
            }
        }, new Date(System.currentTimeMillis() + 200));
    }

    private void startPlayAction(Service<?, ?> service, final ActionState actionState) {
        startPlayAction(service, actionState, "play_" + System.currentTimeMillis());
    }

    private void startPlayAction(Service<?, ?> service, final ActionState actionState, final String retryKey) {
        actionState.actionFinished = false;
        Play actionCallback = new Play(service, getHttpRequestSender()) {
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

                // Retry on failure
                if (shouldRetry(retryKey)) {
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            startPlayAction(service, actionState, retryKey);
                        }
                    }, new Date(System.currentTimeMillis() + 1000));
                } else {
                    setProcessingCommand(false);
                }
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                resetRetry(retryKey);
                actionState.actionFinished = true;
                setPlaying(true);
                playerWrapper.notifyPlaybackStateChanged();
                setProcessingCommand(false);

                // Check transport state after Play command
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        getTransportInfo();
                    }
                }, new Date(System.currentTimeMillis() + 500));
            }
        };
        executorService.execute(actionCallback);
    }

    /**
     * Watchdog for async calls to complete
     */
    private void waitForActionComplete(final ActionState actionState) {
        waitForActionComplete(actionState, null);
    }

    /**
     * Watchdog for async calls to complete
     */
    private void waitForActionComplete(final ActionState actionState, Runnable fn) {
        actionState.watchdogFlag = false;
        Timer watchdogTimer = new Timer();
        watchdogTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                actionState.watchdogFlag = true;
            }
        }, 30000L); // 30sec. Watchdog
        int i = 0;
        while (!(actionState.actionFinished || actionState.watchdogFlag)) {
            if (fn != null) {
                fn.run();
            } else {
                //work around byte code optimization
                i++;
                if (i == 100000) {
                    // YaaccLogger.d(getClass().getName(), "wait for action finished ");
                    i = 0;
                }
            }
        }
        if (actionState.watchdogFlag) {
            YaaccLogger.d(getClass().getName(), "Watchdog timeout!");
        }
        if (actionState.actionFinished) {
            YaaccLogger.d(getClass().getName(), "Action completed!");
        }
    }

    /*
     * (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
    @Override
    public PendingIntent getNotificationIntent() {
        Intent notificationIntent = new Intent(getContext(),
                AVTransportPlayerActivity.class);
        YaaccLogger.d(getClass().getName(), "Put id into intent: " + getId());
        notificationIntent.setData(Uri.parse("http://0.0.0.0/" + getId() + "")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
        notificationIntent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);

    }

    /*
     * (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#getNotificationId()
     */
    @Override
    protected int getNotificationId() {
        return id;
    }

    @Override
    protected void doPause() {
        YaaccLogger.d(getClass().getName(), "doPause() called");
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        YaaccLogger.d(getClass().getName(), "Action Pause ");
        doPauseWithRetry(service, "pause_" + System.currentTimeMillis());
    }

    private void doPauseWithRetry(Service<?, ?> service, final String retryKey) {
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Pause actionCallback = new Pause(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Pause FAILED: " + s);
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
                actionState.actionFinished = true;

                // Retry on failure
                if (shouldRetry(retryKey)) {
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            doPauseWithRetry(service, retryKey);
                        }
                    }, new Date(System.currentTimeMillis() + 1000));
                }
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                resetRetry(retryKey);
                YaaccLogger.d(getClass().getName(), "Pause SUCCESS - setting isPlaying=false");
                actionState.actionFinished = true;
                setPlaying(false);
                playerWrapper.notifyPlaybackStateChanged();
                YaaccLogger.d(getClass().getName(), "After pause: isPlaying=" + isPlaying());
            }
        };
        executorService.execute(actionCallback);
    }

    @Override
    public Bitmap getIcon() {
        // Try to get album art from cache only (don't block on download)
        if (albumArtUri != null) {
            IconDownloadCacheHandler cache = IconDownloadCacheHandler.getInstance();
            Bitmap albumArt = cache.getBitmap(android.net.Uri.parse(albumArtUri.toString()), 512, 512);
            if (albumArt != null) {
                return albumArt;
            }

            // Trigger async download for next notification update
            android.net.Uri artworkUri = android.net.Uri.parse(albumArtUri.toString());
            ((Yaacc) getContext().getApplicationContext()).getContentLoadExecutor().execute(() -> {
                try {
                    Bitmap bitmap = new ImageDownloader().retrieveImageWithCertainSize(artworkUri, 512, 512);
                    if (bitmap != null) {
                        cache.addBitmap(artworkUri, 512, 512, bitmap);
                        // Trigger notification update by updating metadata
                        updateMetadataInternal();
                    }
                } catch (Exception e) {
                    YaaccLogger.w(getClass().getName(), "Failed to load album art", e);
                }
            });
        }
        // Fall back to device icon
        return super.getIcon();
    }

    @Override
    protected void doResume() {
        // For UPnP, just send Play command to resume from current position
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(), "No receiver device found: " + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(), "No AVTransport-Service found on Device: " + getDevice().getDisplayString());
            return;
        }

        YaaccLogger.d(getClass().getName(), "Resuming playback with Play command");
        Play playCallback = new Play(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Resume failed: " + s);
            }

            @Override
            public void success(ActionInvocation invocation) {
                YaaccLogger.d(getClass().getName(), "Resume succeeded");
                setPlaying(true);
                playerWrapper.notifyPlaybackStateChanged();
            }
        };
        executorService.execute(playCallback);
    }

    @Override
    public URI getAlbumArt() {
        return albumArtUri;
    }

    public boolean getMute() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().getMute(getDevice());
    }

    public void setMute(boolean mute) {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        getUpnpClient().setMute(getDevice(), mute);
    }

    public int getVolume() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return 0;
        }
        return getUpnpClient().getVolume(getDevice());
    }

    public void setVolume(int volume) {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        getUpnpClient().setVolume(getDevice(), volume);
    }

    private int playRetryCount = 0;
    private static final int MAX_PLAY_RETRIES = 3;

    private void getTransportInfo() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(), "No receiver device found for transport info: " + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(), "No AVTransport-Service found for transport info");
            return;
        }

        YaaccLogger.d(getClass().getName(), "GetTransportInfo");
        GetTransportInfo actionCallback = new GetTransportInfo(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "GetTransportInfo failure: " + s);
            }

            @Override
            public void received(ActionInvocation actioninvocation, TransportInfo info) {
                YaaccLogger.d(getClass().getName(), "Transport State: " + info.getCurrentTransportState());

                // If device stopped, track ended - advance to next
                if (info.getCurrentTransportState() == TransportState.STOPPED && isPlaying()) {
                    YaaccLogger.d(getClass().getName(), "Device stopped, advancing to next track");
                    consecutivePositionFailures = 0;
                    next();
                    return;
                }

                // Only retry Play if we think we should be playing (not paused by user)
                if (info.getCurrentTransportState() != TransportState.PLAYING &&
                        isPlaying() &&
                        playRetryCount < MAX_RETRIES) {
                    playRetryCount++;
                    YaaccLogger.d(getClass().getName(), "Renderer not playing, sending Play command again (attempt " + playRetryCount + ")");
                    executeCommand(new TimerTask() {
                        @Override
                        public void run() {
                            sendPlayCommand();
                        }
                    }, new Date(System.currentTimeMillis() + 500));
                } else {
                    YaaccLogger.d(getClass().getName(), "Checking position (retries: " + playRetryCount + ")");
                    getPositionInfo();
                }
            }
        };
        executorService.execute(actionCallback);
    }

    private void sendPlayCommand() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(), "No receiver device found for Play command: " + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(), "No AVTransport-Service found for Play command");
            return;
        }

        YaaccLogger.d(getClass().getName(), "Sending additional Play command");
        Play actionCallback = new Play(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation, UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Additional Play command failed: " + s);
                getPositionInfo(); // Check position anyway
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                YaaccLogger.d(getClass().getName(), "Additional Play command succeeded");
                setPlaying(true);
                playerWrapper.notifyPlaybackStateChanged();

                // Check transport state again after second Play command
                executeCommand(new TimerTask() {
                    @Override
                    public void run() {
                        getTransportInfo();
                    }
                }, new Date(System.currentTimeMillis() + 1000)); // Wait 1 second then check state
            }
        };
        executorService.execute(actionCallback);
    }

    protected void getPositionInfo() {
        if (positionActionState != null && !positionActionState.actionFinished) {
            return;
        }
        YaaccLogger.d(getClass().getName(),
                "GetPositioninfo");
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);

            // Track device-not-found as position failure
            consecutivePositionFailures++;
            if (consecutivePositionFailures >= MAX_RETRIES && isPlaying()) {
                YaaccLogger.w(getClass().getName(), "Device lost, stopping playback");
                consecutivePositionFailures = 0;
                stop();
            }
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        YaaccLogger.d(getClass().getName(), "Action get position info ");
        positionActionState = new ActionState();
        positionActionState.actionFinished = false;
        GetPositionInfo actionCallback = new GetPositionInfo(service, getHttpRequestSender()) {
            @Override
            public void failure(ActionInvocation actioninvocation,
                                UpnpResponse upnpresponse, String s) {
                YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: "
                        + upnpresponse);
                YaaccLogger.d(getClass().getName(),
                        upnpresponse != null ? "UpnpResponse: "
                                + upnpresponse.getResponseDetails() : "");
                YaaccLogger.d(getClass().getName(), "s: " + s);
                positionActionState.actionFinished = true;

                // Track consecutive failures
                consecutivePositionFailures++;
                YaaccLogger.w(getClass().getName(), "Position query failed " + consecutivePositionFailures + " times");

                // After 3 consecutive failures, check device state to see if track ended
                if (consecutivePositionFailures >= MAX_RETRIES && isPlaying()) {
                    YaaccLogger.w(getClass().getName(), "Position query failed 3 times, checking transport state");
                    consecutivePositionFailures = 0;
                    getTransportInfo();
                }
            }

            @Override
            public void success(ActionInvocation actioninvocation) {
                super.success(actioninvocation);
                positionActionState.actionFinished = true;
            }

            @Override
            public void received(ActionInvocation actionInvocation, PositionInfo positionInfo) {
                positionActionState.result = positionInfo;
                PositionInfo previousPositionInfo = currentPositionInfo;
                currentPositionInfo = positionInfo;
                consecutivePositionFailures = 0; // Reset failure counter on success
                YaaccLogger.d(getClass().getName(), "received Positioninfo= RelTime: " + positionInfo.getRelTime() + " remaining time: " + positionInfo.getTrackRemainingSeconds());

                // Detect track end: position reset to 0:00:00 after being > 0
                // This means device auto-advanced to next track
                if ("0:00:00".equals(positionInfo.getRelTime()) &&
                        previousPositionInfo != null &&
                        !"0:00:00".equals(previousPositionInfo.getRelTime()) &&
                        isPlaying()) {
                    YaaccLogger.d(getClass().getName(), "Position reset to 0:00:00 after playing, checking transport state");
                    getTransportInfo(); // Check if still playing or stopped
                    return;
                }

                // Update MediaSession with current position for lock screen controls
                if (isPlaying()) {
                    updatePlaybackStateInternal(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING);
                }

                long currentRemainingTime = positionInfo.getTrackRemainingSeconds();

                // Update server-side position management only for external URLs on basic renderers
                if (positionInfo.getRelTime() != null && !positionInfo.getRelTime().isEmpty()) {
                    int currentIndex = getCurrentItemIndex();
                    if (currentIndex >= 0 && currentIndex < getItems().size()) {
                        PlayableItem currentPlayableItem = getItems().get(currentIndex);
                        String itemUri = currentPlayableItem.getUri().toString();
                        boolean isExternalUrl = itemUri != null && itemUri.contains("/proxy/");

                        if (isExternalUrl) {
                            try {
                                String[] timeParts = positionInfo.getRelTime().split(":");
                                if (timeParts.length >= 3) {
                                    long hours = Long.parseLong(timeParts[0]);
                                    long minutes = Long.parseLong(timeParts[1]);
                                    long seconds = Long.parseLong(timeParts[2]);
                                    long timeMs = (hours * 3600 + minutes * 60 + seconds) * 1000;

                                    // Extract content key from proxy URL for renderer state key
                                    String contentKey = itemUri.substring(itemUri.lastIndexOf("/") + 1);

                                    // Check if position is stuck (paused)
                                    boolean isPaused = (lastRemainingTime != -1 && currentRemainingTime == lastRemainingTime);

                                    YaaccUpnpServerContentHttpHandler.updateRendererPosition(
                                            "test_renderer_" + contentKey, timeMs, isPaused);
                                }
                            } catch (Exception e) {
                                YaaccLogger.w(getClass().getName(), "Failed to parse position time: " + positionInfo.getRelTime(), e);
                            }
                        }
                    }
                }

                // Set timer on first position info OR when remaining time changes significantly
                if (lastRemainingTime == -1 && currentRemainingTime > 1) {
                    // First position check - set timer only if we have valid remaining time
                    lastRemainingTime = currentRemainingTime;
                    updateTimer();
                } else if (currentRemainingTime > 1 && Math.abs(currentRemainingTime - lastRemainingTime) > 5) {
                    // Subsequent updates - only if remaining time changed significantly
                    lastRemainingTime = currentRemainingTime;
                    updateTimer();
                }
            }
        };

        executorService.execute(actionCallback);


    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_devices_32;
    }


    private long lastPositionUpdate = 0;
    private long lastRemainingTime = -1;
    private static final long POSITION_UPDATE_INTERVAL = 1000; // 1 second for better track completion detection


    public long getCurrentPosition() {
        long currentTime = System.currentTimeMillis();
        if (currentPositionInfo == null || (currentTime - lastPositionUpdate) > POSITION_UPDATE_INTERVAL) {
            getPositionInfo();
            lastPositionUpdate = currentTime;
        }
        if (currentPositionInfo != null) {
            YaaccLogger.v(getClass().getName(), "Elapsed time: " + currentPositionInfo.getTrackElapsedSeconds() + " in millis: " + currentPositionInfo.getTrackRemainingSeconds() * 1000);
            return currentPositionInfo.getTrackElapsedSeconds() * 1000;
        }
        return -1;

    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return;
        }
        Service<?, ?> service = getUpnpClient().getAVTransportService(getDevice());
        if (service == null) {
            YaaccLogger.d(getClass().getName(),
                    "No AVTransport-Service found on Device: "
                            + getDevice().getDisplayString());
            return;
        }
        // Check if the service supports seek action
        if (service.getAction("Seek") == null) {
            YaaccLogger.w(getClass().getName(), "Player does not support Seek action");
            Context context = getUpnpClient().getContext();
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(() -> {
                    Toast.makeText(context, "Seek not supported by this player", Toast.LENGTH_SHORT).show();
                });
            }
            return;
        }

        YaaccLogger.d(getClass().getName(), "Action seek ");
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        String relativeTimeTarget = dateFormat.format(millisecondsFromStart);
        Seek seekAction = new Seek(service, relativeTimeTarget, getHttpRequestSender()) {
            @Override
            public void success(ActionInvocation invocation) {
                //super.success(invocation);
                YaaccLogger.d(getClass().getName(), "success seek" + invocation);

                // Update server-side position for external URLs
                int currentIndex = getCurrentItemIndex();
                if (currentIndex >= 0 && currentIndex < getItems().size()) {
                    PlayableItem currentPlayableItem = getItems().get(currentIndex);
                    String itemUri = currentPlayableItem.getUri().toString();
                    boolean isExternalUrl = itemUri != null && itemUri.contains("/proxy/");

                    if (isExternalUrl) {
                        String contentKey = itemUri.substring(itemUri.lastIndexOf("/") + 1);
                        String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
                        YaaccUpnpServerContentHttpHandler.updateRendererPosition(
                                deviceId + "_" + contentKey, millisecondsFromStart, false);

                        // Also save to preferences for HTTP handler
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        preferences.edit().putLong("server_position_" + deviceId, millisecondsFromStart).apply();

                        YaaccLogger.d(getClass().getName(), "Updated server position after seek: " + millisecondsFromStart + "ms");
                    }
                }

                // Don't schedule position check - let normal position polling handle it
            }

            @Override
            public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {
                YaaccLogger.w(getClass().getName(), "Seek failed - Player may not support seeking");
                YaaccLogger.w(getClass().getName(), "UpnpResponse: " + (arg1 != null ? arg1.getResponseDetails() : "null"));
                YaaccLogger.w(getClass().getName(), "Error: " + arg2);

                // Some players don't support seeking, just log and continue
                Context context = getUpnpClient().getContext();
                if (context instanceof Activity) {
                    ((Activity) context).runOnUiThread(() -> {
                        Toast.makeText(context, "Seek not supported by this player", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        };
        executorService.execute(seekAction);

    }


    @Override
    public long getRemainingTime() {
        if (currentPositionInfo == null) {
            getPositionInfo();
        }
        if (currentPositionInfo != null) {
            YaaccLogger.v(getClass().getName(), "Remaining time: " + currentPositionInfo.getTrackRemainingSeconds() + " in millis: " + currentPositionInfo.getTrackRemainingSeconds() * 1000);
            return currentPositionInfo.getTrackRemainingSeconds() * 1000;
        }
        return -1;
    }

    private String modifyProxyUrlWithDeviceId(String originalUrl) {
        // Check if it's a proxy URL from the same YAACC server
        if (originalUrl != null && originalUrl.contains("/proxy/")) {
            try {
                // Check if URL is from this YAACC server by comparing IP
                java.net.URL url = new java.net.URL(originalUrl);
                String urlHost = url.getHost();

                // Get local server IP using YAACC method
                String[] ifAndIp = InterfaceResolutionHelper.getIfAndIpAddress(getUpnpClient().getContext());
                String localIP = ifAndIp != null && ifAndIp.length > 0 ? ifAndIp[0] : null;

                YaaccLogger.d(getClass().getName(), "URL host: '" + urlHost + "', Local IP: '" + localIP + "'");
                YaaccLogger.d(getClass().getName(), "IP comparison: urlHost.equals(localIP) = " + urlHost.equals(localIP));

                if (urlHost.equals(localIP) || urlHost.equals("localhost") || urlHost.equals("127.0.0.1")) {
                    // Get device UUID and URL-encode it for safe URL usage
                    String deviceId = getDevice().getIdentity().getUdn().getIdentifierString();
                    String encodedDeviceId = java.net.URLEncoder.encode(deviceId, "UTF-8");

                    // Replace /proxy/contentKey with /proxy/encodedDeviceId/contentKey
                    String[] parts = originalUrl.split("/proxy/");
                    if (parts.length == 2) {
                        String modifiedUrl = parts[0] + "/proxy/" + encodedDeviceId + "/" + parts[1];
                        YaaccLogger.d(getClass().getName(), "Modified proxy URL: " + originalUrl + " -> " + modifiedUrl);
                        return modifiedUrl;
                    }
                }
            } catch (Exception e) {
                YaaccLogger.w(getClass().getName(), "Failed to modify proxy URL", e);
            }
        }
        return originalUrl;
    }

    @Override
    public String getDuration() {
        if (currentPositionInfo == null) {
            getPositionInfo();
        }
        if (currentPositionInfo != null) {
            return currentPositionInfo.getTrackDuration();
        }
        return "00:00:00";
    }

    @Override
    public String getElapsedTime() {
        getPositionInfo();

        if (currentPositionInfo != null) {
            return currentPositionInfo.getRelTime();
        }
        return "00:00:00";
    }

    @Override
    public void startTimer(final long duration) {
        super.startTimer(duration);
    }

    @Override
    public void onDestroy() {
        // Release notification manager and Media3 session
        if (notificationManager != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (notificationManager != null) {
                    notificationManager.setPlayer(null);
                }
                if (media3Session != null) {
                    media3Session.release();
                }
            });
        }

        doExit();
        super.onDestroy();
    }

    private void doExit() {
        stop();
        final ActionState actionState = new ActionState();
        actionState.actionFinished = false;
        Runnable fn = () -> {
            actionState.actionFinished = AVTransportPlayer.this.isProcessingCommand();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                YaaccLogger.w(getClass().getName(), e);
            }
        };
        waitForActionComplete(actionState, fn);

    }

    @Override
    public void exit() {
        doExit();
        super.exit();
    }

    private void setDeviceIcon(Device<?, ?, ?> device) {
        if (device instanceof RemoteDevice && device.hasIcons()) {
            if (device.hasIcons()) {
                Icon[] icons = device.getIcons();
                for (Icon icon : icons) {
                    if (120 == icon.getHeight() && 120 == icon.getWidth() && "image/png".equals(icon.getMimeType().toString())) {
                        URL iconUri = ((RemoteDevice) device).normalizeURI(icon.getUri());
                        if (iconUri != null) {
                            YaaccLogger.d(getClass().getName(), "Device icon uri:" + iconUri);
                            setIcon(new ImageDownloader().retrieveImageWithCertainSize(Uri.parse(iconUri.toString()), icon.getWidth(), icon.getHeight()));
                            break;
                        }
                    }
                }
            }
        }


    }

    private static class InternalSetAVTransportURI extends SetAVTransportURI {
        public boolean hasFailures = false;
        ActionState actionState;

        private InternalSetAVTransportURI(Service<?, ?> service, String uri,
                                          ActionState actionState, String metadata, de.yaacc.upnp.server.http.HttpRequestSender httpRequestSender) {
            super(service, uri, metadata, httpRequestSender);
            this.actionState = actionState;
            YaaccLogger.d(getClass().getName(), "InternalSetAVTransportURI created with URI: " + uri);
        }

        @Override
        public void failure(ActionInvocation actioninvocation,
                            UpnpResponse upnpresponse, String s) {
            YaaccLogger.d(getClass().getName(), "Failure UpnpResponse: " + upnpresponse);
            if (upnpresponse != null) {
                YaaccLogger.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getResponseDetails());
                YaaccLogger.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getStatusMessage());
                YaaccLogger.d(getClass().getName(),
                        "UpnpResponse: " + upnpresponse.getStatusCode());
            }
            hasFailures = true;
            YaaccLogger.d(getClass().getName(), "s: " + s);
            actionState.actionFinished = true;
        }

        @Override
        public void success(ActionInvocation actioninvocation) {
            super.success(actioninvocation);
            actionState.actionFinished = true;
        }
    }


    public boolean hasActionGetVolume() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().hasActionGetVolume(getDevice());
    }

    public boolean hasActionGetMute() {
        if (getDevice() == null) {
            YaaccLogger.d(getClass().getName(),
                    "No receiver device found: "
                            + deviceId);
            return false;
        }
        return getUpnpClient().hasActionGetMute(getDevice());
    }
}
