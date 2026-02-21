/*
 * Copyright (C) 2018 Tobias Schoene www.yaacc.de
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
package de.yaacc.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.browser.TabBrowserActivity;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;
import de.yaacc.util.YaaccLogger;

/**
 * @author Tobias Schoene (tobexyz)
 */
public class PlayerService extends MediaSessionService {

    private final IBinder binder = new PlayerServiceBinder();
    private PlayerServiceBroadcastReceiver playerServiceBroadcastReceiver;
    private final Map<Integer, Player> currentActivePlayer = new HashMap<>();
    private HandlerThread playerHandlerThread;

    private PowerManager.WakeLock wakeLock;

    // Track active MediaSession from LocalMediaSessionPlayer
    private MediaSession activeMediaSession;


    public PlayerService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        YaaccLogger.d(getClass().getName(), "Service created");
    }

    /**
     * Register MediaSession from LocalMediaSessionPlayer.
     * Called when player connects to service.
     */
    public void registerMediaSession(MediaSession mediaSession) {
        this.activeMediaSession = mediaSession;
        YaaccLogger.d(getClass().getName(), "MediaSession registered: " + mediaSession.getId());
    }

    /**
     * Unregister MediaSession when player is destroyed.
     */
    public void unregisterMediaSession(MediaSession mediaSession) {
        if (this.activeMediaSession == mediaSession) {
            this.activeMediaSession = null;
            YaaccLogger.d(getClass().getName(), "MediaSession unregistered");
        }
    }

    private PendingIntent createSessionActivity() {
        Intent intent = new Intent(this, de.yaacc.browser.TabBrowserActivity.class);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    public void addPlayer(Player player) {
        if (player.getId() == 0) {
            return;
        }
        currentActivePlayer.put(player.getId(), player);

        // Listen for playing state changes
        player.addPropertyChangeListener(evt -> {
            if ("playing".equals(evt.getPropertyName())) {
                updateForegroundState();
            }
        });

        updateForegroundState();
    }

    public void removePlayer(Player player) {
        currentActivePlayer.remove(player.getId());
        updateForegroundState();
    }

    private void updateForegroundState() {
        boolean hasPlayingPlayer = false;

        // Check if any player is actively playing
        for (Player player : currentActivePlayer.values()) {
            if (player.isPlaying()) {
                hasPlayingPlayer = true;
                break;
            }
        }

        if (hasPlayingPlayer) {
            // At least one player playing - MediaSessionService handles foreground automatically
            YaaccLogger.d(getClass().getName(), "Player active - service foreground");
            updateServiceNotification();
        } else {
            // No players playing
            if (currentActivePlayer.isEmpty()) {
                // No players at all - stop service completely
                YaaccLogger.d(getClass().getName(), "No players - stopping service");
                stopForeground(STOP_FOREGROUND_REMOVE);
                ((Yaacc) getApplicationContext()).cancelYaaccGroupNotification();
                stopSelf();
            } else {
                // Players exist but paused
                // Don't stop foreground - Media3 PlayerNotificationManager handles it
                YaaccLogger.d(getClass().getName(), "Players paused - keeping foreground for notification");
                updateServiceNotification();
            }
        }
    }

    private void updateServiceNotification() {
        Intent notificationIntent = new Intent(this, TabBrowserActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Build status text
        int totalPlayers = currentActivePlayer.size();
        int playingCount = 0;
        for (Player player : currentActivePlayer.values()) {
            if (player.isPlaying()) {
                playingCount++;
            }
        }

        String statusText = totalPlayers == 0 ? "running" :
                totalPlayers + " player" + (totalPlayers > 1 ? "s" : "") +
                        (playingCount > 0 ? " (" + playingCount + " playing)" : " (paused)");

        Notification notification = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle("Player Service")
                .setSilent(true)
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NotificationId.PLAYER_SERVICE.getId(), notification);
    }

    @Override
    public void onDestroy() {
        YaaccLogger.d(this.getClass().getName(), "On Destroy");
        // Stop all players before service is destroyed
        shutdown();
        // MediaSession is owned by players, they will release it
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        YaaccLogger.d(this.getClass().getName(), "On Bind");
        // Always return our binder for service binding
        // MediaSessionService will handle media controller connections separately
        return binder;
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        // Return active MediaSession from LocalMediaSessionPlayer
        // Returns null if no LocalMediaSessionPlayer active (other players use MediaSessionCompat)
        return activeMediaSession;
    }

    public Collection<Player> getPlayer() {
        return currentActivePlayer.values();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        YaaccLogger.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        if (playerServiceBroadcastReceiver == null) {
            playerServiceBroadcastReceiver = new PlayerServiceBroadcastReceiver(this);
            playerServiceBroadcastReceiver.registerReceiver();
        }
        ((Yaacc) getApplicationContext()).createYaaccGroupNotification();
        updateServiceNotification();
        initialize();

        return START_STICKY;
    }

    private void initialize() {
        playerHandlerThread = new HandlerThread("de.yaacc.PlayerService.HandlerThread");
        playerHandlerThread.start();
    }

    public HandlerThread getPlayerHandlerThread() {
        return playerHandlerThread;
    }

    public Player getPlayer(int playerId) {
        if (currentActivePlayer.get(playerId) == null) {
            YaaccLogger.v(this.getClass().getName(), "Get Player not found");
        }
        return currentActivePlayer.get(playerId);
    }

    /**
     * Creates a player for the given content. Based on the configuration
     * settings in the upnpClient the player may be a player to play on a remote
     * device.
     *
     * @param upnpClient the upnpClient
     * @param items      the items to be played
     * @return the player
     */
    public List<Player> createPlayer(UpnpClient upnpClient, List<PlayableItem> items) {
        YaaccLogger.d(getClass().getName(), "create player...");
        List<Player> resultList = new ArrayList<>();
        if (items.isEmpty()) {
            return resultList;
        }
        Player result;
        boolean video = false;
        boolean image = false;
        boolean music = false;
        for (PlayableItem playableItem : items) {
            if (playableItem.getMimeType() != null) {
                image = image || playableItem.getMimeType().startsWith("image");
                video = video || playableItem.getMimeType().startsWith("video");
                music = music || playableItem.getMimeType().startsWith("audio");
            } else {
                //no mime type no knowlege about it
                image = true;
                music = true;
                video = true;
            }

        }
        YaaccLogger.d(getClass().getName(), "video:" + video + " image: " + image + " audio:" + music);
        
        // Check if any receiver device is selected
        if (upnpClient.getReceiverDevices().isEmpty()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                Toast.makeText(upnpClient.getContext(), 
                    R.string.error_no_receiver_selected, 
                    Toast.LENGTH_LONG).show()
            );
            return resultList;
        }
        
        for (Device<?, ?, ?> device : upnpClient.getReceiverDevices()) {
            result = createPlayer(upnpClient, device, video, image, music);
            if (result != null) {
                addPlayer(result);
                result.setItems(items.toArray(new PlayableItem[0]));
                resultList.add(result);
            }
        }
        return resultList;
    }

    /**
     * Creates a player for the local device only (used by renderer service)
     */
    public List<Player> createPlayerForLocalDevice(UpnpClient upnpClient, List<PlayableItem> items) {
        YaaccLogger.d(getClass().getName(), "create player for local device...");
        List<Player> resultList = new ArrayList<>();
        if (items.isEmpty()) {
            return resultList;
        }
        
        boolean video = false;
        boolean image = false;
        boolean music = false;
        for (PlayableItem playableItem : items) {
            if (playableItem.getMimeType() != null) {
                image = image || playableItem.getMimeType().startsWith("image");
                video = video || playableItem.getMimeType().startsWith("video");
                music = music || playableItem.getMimeType().startsWith("audio");
            } else {
                image = true;
                music = true;
                video = true;
            }
        }
        
        Device<?, ?, ?> localDevice = upnpClient.getDevice(UpnpClient.LOCAL_UID);
        if (localDevice != null) {
            Player result = createPlayer(upnpClient, localDevice, video, image, music);
            if (result != null) {
                addPlayer(result);
                result.setItems(items.toArray(new PlayableItem[0]));
                resultList.add(result);
            }
        }
        return resultList;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (playerServiceBroadcastReceiver != null) {
            unregisterReceiver(playerServiceBroadcastReceiver);
        }
        return super.onUnbind(intent);
    }


    /**
     * creates a player for the given device
     *
     * @param upnpClient     the upnpClient
     * @param receiverDevice the receiverDevice
     * @param video          true if video items
     * @param image          true if image items
     * @param music          true if music items
     * @return the player or null if no device is present
     */
    @OptIn(markerClass = UnstableApi.class)
    private Player createPlayer(UpnpClient upnpClient, Device receiverDevice,
                                boolean video, boolean image, boolean music) {
        if (receiverDevice == null) {
            Toast toast = Toast.makeText(upnpClient.getContext(), upnpClient.getContext().getString(R.string.error_no_receiver_device_found), Toast.LENGTH_SHORT);
            toast.show();
            return null;
        }

        Player result;
        if (!receiverDevice.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID)) {
            String deviceName = receiverDevice.getDetails().getFriendlyName() + " - " + receiverDevice.getDisplayString();
            String contentType = "multi";
            if (video && !image && !music) {
                contentType = "video";
            } else if (!video && image && !music) {
                contentType = "image";
            } else if (!video && !image && music) {
                contentType = "music";
            }
            for (Player player : getCurrentPlayersOfType(AVTransportPlayer.class)) {
                if (((AVTransportPlayer) player).getDeviceId().equals(receiverDevice.getIdentity().getUdn().getIdentifierString())
                        && ((AVTransportPlayer) player).getContentType().equals(contentType)) {
                    shutdown(player);
                }
            }
            result = new AVTransportPlayer(upnpClient, receiverDevice, upnpClient.getContext()
                    .getString(R.string.playerNameAvTransport)
                    + "-" + contentType + "@"
                    + deviceName, receiverDevice.getDetails().getFriendlyName(), contentType);

        } else {
            if (video && !image && !music) {
// use videoplayer
                result = getFirstCurrentPlayerOfType(MultiContentPlayer.class);
                if (result != null) {
                    shutdown(result);
                }
                result = new MultiContentPlayer(upnpClient, upnpClient
                        .getContext().getString(
                                R.string.playerNameMultiContent), upnpClient
                        .getContext().getString(
                                R.string.playerShortNameMultiContent));
            } else if (!video && image && !music) {
// use imageplayer
                result = createImagePlayer(upnpClient);
            } else if (!video && !image && music) {
// use musicplayer
                result = createMusicPlayer(upnpClient);
            } else {
// use multiplayer
                result = new MultiContentPlayer(upnpClient, upnpClient
                        .getContext()
                        .getString(R.string.playerNameMultiContent), upnpClient
                        .getContext().getString(
                                R.string.playerShortNameMultiContent));
            }
        }
        return result;
    }

    private Player createImagePlayer(UpnpClient upnpClient) {
        Player result = getFirstCurrentPlayerOfType(LocalImagePlayer.class);
        if (result != null) {
            shutdown(result);
        }
        return new LocalImagePlayer(upnpClient, upnpClient.getContext()
                .getString(R.string.playerNameImage), upnpClient.getContext()
                .getString(R.string.playerNameImageShort));
    }

    private Player createMusicPlayer(UpnpClient upnpClient) {
        Player result = getFirstCurrentPlayerOfType(LocalMediaSessionPlayer.class);
        if (result != null) {
            shutdown(result);
        }
        return new LocalMediaSessionPlayer(upnpClient, upnpClient
                .getContext().getString(R.string.playerNameMusic), upnpClient
                .getContext().getString(R.string.playerShortNameMusic));
    }

    /**
     * returns all current players
     *
     * @return the currentPlayer
     */
    public Collection<Player> getCurrentPlayers() {

        return Collections.unmodifiableCollection(currentActivePlayer.values());
    }

    public Player getCurrentPlayerById(Integer id) {

        return currentActivePlayer.get(id);
    }


    /**
     * returns all current players of the given type.
     *
     * @param typeClazz the requested type
     * @return the currentPlayer
     */
    public List<Player> getCurrentPlayersOfType(Class<?> typeClazz) {
        List<Player> players = new ArrayList<>();
        for (Player player : getCurrentPlayers()) {
            if (typeClazz.isInstance(player)) {
                players.add(player);
            }
        }
        return Collections.unmodifiableList(players);
    }

    /**
     * returns the first current player of the given type.
     *
     * @param typeClazz the requested type
     * @return the currentPlayer
     */
    public Player getFirstCurrentPlayerOfType(Class typeClazz) {
        for (Player player : getCurrentPlayers()) {
            if (typeClazz.isInstance(player)) {
                return player;
            }
        }
        return null;
    }

    /**
     * Returns the class of a player for the given mime type.
     *
     * @param mimeType the mime type
     * @return the player class
     */
    public Class getPlayerClassForMimeType(String mimeType) {
// FIXME don't implement business logic twice
        Class result = MultiContentPlayer.class;
        if (mimeType != null) {
            boolean image = mimeType.startsWith("image");
            boolean video = mimeType.startsWith("video");
            boolean music = mimeType.startsWith("audio");
            if (video && !image && !music) {
// use videoplayer
                result = MultiContentPlayer.class;
            } else if (!video && image && !music) {
// use imageplayer
                result = LocalImagePlayer.class;
            } else if (!video && !image && music) {
// use musicplayer
                result = LocalMediaSessionPlayer.class;
            }
        }
        return result;
    }

    /**
     * Kills the given Player
     *
     * @param player the player to shutdown
     */
    public void shutdown(Player player) {
        assert (player != null);
        YaaccLogger.d(getClass().getName(), "Shutting down player: " + player.getId());
        currentActivePlayer.remove(player.getId());
        player.onDestroy();
        updateForegroundState();
    }

    /**
     * Kill all Players
     */
    public void shutdown() {
        HashSet<Player> players = new HashSet<>(getCurrentPlayers());
        for (Player player : players) {
            shutdown(player);
        }

    }


    public class PlayerServiceBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

}
