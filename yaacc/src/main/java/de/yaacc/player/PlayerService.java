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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.browser.BrowserLauncher;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.share.ContentDuration;
import de.yaacc.share.PickedQueue;
import de.yaacc.share.QueueItems;
import de.yaacc.share.QueueShareBinder;
import de.yaacc.share.QueueShareWindow;
import de.yaacc.share.ShareAddressResolver;
import de.yaacc.share.ShareService;
import de.yaacc.upnp.OpenHomeReceivers;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;
import de.yaacc.util.YaaccLogger;

/**
 * @author Tobias Schoene (tobexyz)
 */
public class PlayerService extends MediaSessionService {

    private final IBinder binder = new PlayerServiceBinder();
    private PlayerServiceBroadcastReceiver playerServiceBroadcastReceiver;
    // Concurrent: players are added from background threads (ContentItemPlayTask,
    // AvTransportMediaRendererPlaying) while the UI iterates the live values() view on
    // the main thread. Also keeps put/remove return values atomic for the change notifier.
    private final Map<Integer, Player> currentActivePlayer = new ConcurrentHashMap<>();
    private HandlerThread playerHandlerThread;
    // Map writes can happen off the main thread (createPlayer... runs on the caller's
    // thread); only the resulting notification is posted to the main thread.
    private final PlayerListChangeNotifier playerListChangeNotifier =
            new PlayerListChangeNotifier(new Handler(Looper.getMainLooper())::post);

    private PowerManager.WakeLock wakeLock;

    // Track active MediaSession from LocalMediaSessionPlayer. Two local music players can exist at once (the local one
    // and a Snapcast player), each with its session: the one registered last is the active one, and when it goes the one
    // before it takes over instead of leaving the media controllers with none.
    private volatile MediaSession activeMediaSession;
    private final List<MediaSession> registeredMediaSessions = new java.util.concurrent.CopyOnWriteArrayList<>();


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
    public synchronized void registerMediaSession(MediaSession mediaSession) {
        registeredMediaSessions.remove(mediaSession);
        registeredMediaSessions.add(mediaSession);
        this.activeMediaSession = mediaSession;
        YaaccLogger.d(getClass().getName(), "MediaSession registered: " + mediaSession.getId());
    }

    /**
     * Unregister MediaSession when player is destroyed. Only the player that registered a session removes it; if it was
     * the active one, the session registered before it (if any) is active again.
     */
    public synchronized void unregisterMediaSession(MediaSession mediaSession) {
        registeredMediaSessions.remove(mediaSession);
        if (this.activeMediaSession == mediaSession) {
            this.activeMediaSession = registeredMediaSessions.isEmpty() ? null
                    : registeredMediaSessions.get(registeredMediaSessions.size() - 1);
            YaaccLogger.d(getClass().getName(), "MediaSession unregistered");
        }
    }

    private PendingIntent createSessionActivity() {
        Intent intent = new Intent(this, BrowserLauncher.targetClass(this));
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
        playerListChangeNotifier.putAndNotify(currentActivePlayer, player);

        // Listen for the state changes: playing, paused, stopped
        player.addPropertyChangeListener(evt -> {
            String name = evt.getPropertyName();
            if ("playing".equals(name) || AbstractPlayer.PROPERTY_PAUSED.equals(name) || Player.PROPERTY_STOPPED.equals(name)) {
                updateForegroundState();
            }
        });

        updateForegroundState();
    }

    public void removePlayer(Player player) {
        playerListChangeNotifier.removeAndNotify(currentActivePlayer, player);
        updateForegroundState();
    }

    /**
     * Registers a listener that is told when a player is added to or removed from
     * the active players. Callbacks are delivered on the main thread, at most once
     * per player for each direction. Registering the same listener twice has no
     * effect.
     * <p>
     * The service keeps a strong reference to the listener until
     * {@link #removePlayerListChangeListener} is called, so callers tied to a view
     * or fragment must unregister when it goes away. A notification queued before
     * unregistering is not delivered to that listener.
     */
    public void addPlayerListChangeListener(PlayerListChangeNotifier.Listener listener) {
        playerListChangeNotifier.addListener(listener);
    }

    public void removePlayerListChangeListener(PlayerListChangeNotifier.Listener listener) {
        playerListChangeNotifier.removeListener(listener);
    }

    /**
     * Creation sequence of an active player: higher means added later. Player ids
     * can't tell that ({@code AVTransportPlayer} ids are random, local ids are fixed
     * per type).
     *
     * @return the sequence (&gt; 0), or 0 if the player is not active
     */
    public long getPlayerCreationSequence(Player player) {
        return playerListChangeNotifier.creationSequence(player);
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

    /**
     * The text of the notification of the service: how many players there are and what they do, for example
     * "3 players (1 playing, 1 paused, 1 stopped)".
     *
     * @param idle         the text when there is no player
     * @param players      the number of players as text ("3 players")
     * @param states       the states that occur, as text ("1 playing"), in the order they are shown
     * @param statusFormat puts the two together: {@code %1$s} the players, {@code %2$s} the states
     * @param separator    between the states
     */
    static String statusText(int total, String idle, String players, java.util.List<String> states, String statusFormat,
                             String separator) {
        if (total == 0) {
            return idle;
        }
        StringBuilder joined = new StringBuilder();
        for (String state : states) {
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(state);
        }
        return String.format(java.util.Locale.getDefault(), statusFormat, players, joined);
    }

    private void addState(java.util.List<String> states, int plurals, int count) {
        if (count > 0) {
            states.add(getResources().getQuantityString(plurals, count, count));
        }
    }

    private void updateServiceNotification() {
        Intent notificationIntent = new Intent(this, BrowserLauncher.targetClass(this));
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Build status text
        int totalPlayers = currentActivePlayer.size();
        int playingCount = 0;
        int pausedCount = 0;
        for (Player player : currentActivePlayer.values()) {
            if (player.isPlaying()) {
                playingCount++;
            } else if (player.isPaused()) {
                pausedCount++;
            }
        }
        java.util.List<String> states = new java.util.ArrayList<>();
        addState(states, R.plurals.player_service_playing, playingCount);
        addState(states, R.plurals.player_service_paused, pausedCount);
        addState(states, R.plurals.player_service_stopped, totalPlayers - playingCount - pausedCount);
        String statusText = statusText(totalPlayers, getString(R.string.player_service_idle),
                getResources().getQuantityString(R.plurals.player_service_players, totalPlayers, totalPlayers), states,
                getString(R.string.player_service_status), getString(R.string.player_service_state_separator));

        Notification notification = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle(getString(R.string.player_service_title))
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
    // Deliberately hands out the app binder for every bind; calling super would change which binder clients receive.
    @SuppressLint("MissingSuperCall")
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
        if (items.isEmpty()) {
            return new ArrayList<>();
        }

        // Check if any receiver device is selected; a selected one that the registry lost during a
        // suspend gets a few seconds to come back first (this runs on the caller's worker thread)
        java.util.Collection<Device<?, ?, ?>> receivers = upnpClient.awaitReceiverDevices();
        if (receivers.isEmpty()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                Toast.makeText(upnpClient.getContext(), 
                    R.string.error_no_receiver_selected, 
                    Toast.LENGTH_LONG).show()
            );
            return new ArrayList<>();
        }

        return createPlayerFor(upnpClient, items, receivers);
    }

    /**
     * Creates one player per given receiver for the items; the other receivers are not touched (add to
     * playlist uses this for the chosen receivers that have no player yet).
     */
    public List<Player> createPlayerFor(UpnpClient upnpClient, List<PlayableItem> items,
                                        java.util.Collection<Device<?, ?, ?>> devices) {
        List<Player> resultList = new ArrayList<>();
        if (items.isEmpty()) {
            return resultList;
        }
        devices = withoutNotPlayableYet(upnpClient, devices);
        // a client of a group plays what its server plays: the send goes to the server (see YxcPlaybackRedirect)
        devices = YxcPlayTargets.resolve(upnpClient, devices);
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
        YaaccLogger.d(getClass().getName(), "video:" + video + " image: " + image + " audio:" + music);
        if (video || image || !music) {
            // a Snapcast group plays music only: it is left out, and the user is told once
            devices = withoutSnapcastGroups(upnpClient, devices, true);
        }
        boolean toldNothingPlayable = false;
        for (Device<?, ?, ?> device : devices) {
            // a player that reads and decodes on this phone can not play playlist files (an .m3u listed as audio)
            List<PlayableItem> playable = readsOnThisPhone(device) ? PlayableCheck.withoutPlaylistFiles(items) : items;
            if (playable.isEmpty()) {
                if (!toldNothingPlayable) {
                    toldNothingPlayable = true;
                    notifyNothingPlayable(upnpClient);
                }
                continue;
            }
            Player result = createPlayer(upnpClient, device, video, image, music);
            if (result != null) {
                addPlayer(result);
                result.setItems(playable.toArray(new PlayableItem[0]));
                resultList.add(result);
            }
        }
        return resultList;
    }

    /**
     * Creates the players of a picked queue: for each non-empty type (audio, video, image) one player
     * per receiver, every player with its own items. This phone plays audio and images straight from
     * the picked {@code content:} uris; every other player gets placeholder items whose files are
     * shared just in time (current and next item) for exactly its receiver and released again.
     * The players are not started.
     */
    public List<Player> createQueuePlayers(UpnpClient upnpClient, PickedQueue.Result queue,
                                           java.util.Collection<Device<?, ?, ?>> devices) {
        List<Player> resultList = new ArrayList<>();
        devices = withoutNotPlayableYet(upnpClient, devices);
        // a client of a group plays what its server plays: the send goes to the server (see YxcPlaybackRedirect)
        devices = YxcPlayTargets.resolve(upnpClient, devices);
        // a Snapcast group plays the music of the queue; for video and images it is left out, and the user is told once
        java.util.Collection<Device<?, ?, ?>> withoutGroups = withoutSnapcastGroups(upnpClient, devices,
                !queue.video().isEmpty() || !queue.image().isEmpty());
        createQueuePlayers(upnpClient, queue.audio(), false, false, true, devices, resultList);
        createQueuePlayers(upnpClient, queue.video(), true, false, false, withoutGroups, resultList);
        createQueuePlayers(upnpClient, queue.image(), false, true, false, withoutGroups, resultList);
        return resultList;
    }

    private void createQueuePlayers(UpnpClient upnpClient, List<PickedQueue.Entry> entries, boolean video,
                                    boolean image, boolean music, java.util.Collection<Device<?, ?, ?>> devices,
                                    List<Player> resultList) {
        if (entries.isEmpty()) {
            return;
        }
        // images of another receiver have no end of their own: they stay as long as the "default duration" setting (milliseconds, -1 never)
        // says, as browsed images do; audio and video end by the transport state of the receiver
        long imageMillis = upnpClient.getDefaultDuration();
        boolean[] toldNothingPlayable = {false}; // once per batch, not once per receiver
        for (Device<?, ?, ?> device : devices) {
            String deviceId = device.getIdentity().getUdn().getIdentifierString();
            boolean local = UpnpClient.LOCAL_UID.equals(deviceId);
            // a Snapcast group is fed by this phone, which reads and decodes the music itself: no share either
            boolean snapcast = device instanceof SnapcastGroupDevice;
            // this phone reads its own audio and images with the grant of the app, no share needed
            boolean direct = (local || snapcast) && !video;
            QueueItems.Built built = direct ? QueueItems.direct(entries) : QueueItems.placeholders(entries, imageMillis);
            List<PlayableItem> queueItems = built.items();
            if (direct && !queueItems.isEmpty()) {
                // this phone reads and decodes itself: playlist files can not be played, the other files can
                queueItems = PlayableCheck.withoutPlaylistFiles(queueItems);
                if (queueItems.isEmpty()) {
                    if (!toldNothingPlayable[0]) {
                        toldNothingPlayable[0] = true;
                        notifyNothingPlayable(upnpClient);
                    }
                    continue;
                }
            }
            if (queueItems.isEmpty()) {
                continue;
            }
            if (!local && !snapcast && OpenHomeReceivers.isOpenHomeOnly(device) && !(music && !video && !image)) {
                // an OpenHome device plays music only, and has no AVTransport for the rest
                notifyFilesNotSupported(upnpClient);
                continue;
            }
            Player player = createPlayer(upnpClient, device, video, image, music);
            if (player == null) {
                continue;
            }
            if (!direct && player instanceof AbstractPlayer) {
                // what the share allows is decided now, at the confirm, not at every later bind
                java.util.Set<String> addresses = ShareAddressResolver.resolve(upnpClient,
                        getApplicationContext(), java.util.Collections.singleton(deviceId));
                ShareService shareService = new ShareService(getApplicationContext(), upnpClient);
                QueueShareWindow window = new QueueShareWindow(built.items(),
                        new QueueShareBinder(built.entries(), built.items(), addresses,
                                shareService::prepareFor, queueShareKeys(),
                                entry -> ContentDuration.read(getApplicationContext(), entry.getUri(),
                                        entry.getMimeType())));
                ((AbstractPlayer) player).setShareLease(window.newLease());
            }
            addPlayer(player);
            player.setItems(queueItems.toArray(new PlayableItem[0]));
            resultList.add(player);
        }
    }

    private void notifyFilesNotSupported(UpnpClient upnpClient) {
        android.content.Context context = upnpClient.getContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, R.string.openhome_files_not_supported, android.widget.Toast.LENGTH_LONG).show());
    }

    /** The key bookkeeping of the app's registries for the shares of a queue. */
    private QueueShareBinder.Keys queueShareKeys() {
        Yaacc yaacc = (Yaacc) getApplicationContext();
        return new QueueShareBinder.Keys() {
            @Override
            public boolean hasRoom() {
                return yaacc.getShareRegistry().hasRoom();
            }

            @Override
            public void claimed(String key) {
                yaacc.getUnclaimedShares().claimed(key);
            }

            @Override
            public void remove(String key) {
                yaacc.getShareRegistry().remove(key);
                yaacc.getUnclaimedShares().released(key);
            }
        };
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
            List<PlayableItem> playable = PlayableCheck.withoutPlaylistFiles(items);
            if (playable.isEmpty()) {
                notifyNothingPlayable(upnpClient);
                return resultList;
            }
            Player result = createPlayer(upnpClient, localDevice, video, image, music);
            if (result != null) {
                addPlayer(result);
                result.setItems(playable.toArray(new PlayableItem[0]));
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


    private OpenHomePlayer newOpenHomePlayer(UpnpClient upnpClient, Device receiverDevice) {
        String deviceName = receiverDevice.getDetails().getFriendlyName() + " - " + receiverDevice.getDisplayString();
        return new OpenHomePlayer(upnpClient, receiverDevice, upnpClient.getContext()
                .getString(R.string.playerNameAvTransport) + "-OpenHome@" + deviceName,
                receiverDevice.getDetails().getFriendlyName());
    }

    /**
     * Gives an OpenHome device that plays already a player that shows its playlist (see {@link OpenHomePlayer#attach()}).
     *
     * @return the player of the device: the one it has, else a new one
     */
    public Player attachOpenHomePlayer(UpnpClient upnpClient, Device<?, ?, ?> device) {
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        for (Player player : getCurrentPlayersOfType(OpenHomePlayer.class)) {
            if (((OpenHomePlayer) player).getDeviceId().equals(deviceId)) {
                return player;
            }
        }
        OpenHomePlayer player = newOpenHomePlayer(upnpClient, device);
        addPlayer(player);
        player.attach();
        return player;
    }

    /**
     * Takes the receivers that have no player yet out of the list and tells the user once (not once per receiver).
     * The same list when there is none of them. A Snapcast group has a player only when the experimental option is on
     * ({@link #snapcastPlayEnabled}); without it the group is dropped here and the user hears "not available yet".
     */
    java.util.Collection<Device<?, ?, ?>> withoutNotPlayableYet(UpnpClient upnpClient,
                                                                java.util.Collection<Device<?, ?, ?>> devices) {
        boolean enabled = snapcastPlayEnabled(upnpClient);
        List<Device<?, ?, ?>> playable = new ArrayList<>();
        boolean dropped = false;
        for (Device<?, ?, ?> device : devices) {
            if (isNotPlayableYet(device, enabled)) {
                dropped = true;
            } else {
                playable.add(device);
            }
        }
        if (!dropped) {
            return devices;
        }
        notifyNotPlayableYet(upnpClient);
        return playable;
    }

    /** "Playing to Snapcast groups is not available yet"; callers may be on a worker thread, a toast needs the main looper. */
    void notifyNotPlayableYet(UpnpClient upnpClient) {
        final android.content.Context context = upnpClient.getContext();
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, R.string.snapcast_play_not_available, Toast.LENGTH_LONG).show());
    }

    /** "Nothing playable: playlist files can not be played" (once per call of a batch method). */
    void notifyNothingPlayable(UpnpClient upnpClient) {
        final android.content.Context context = upnpClient.getContext();
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, R.string.player_nothing_playable, Toast.LENGTH_LONG).show());
    }

    /**
     * True for a receiver whose player reads and decodes the files on this phone: this phone itself and a Snapcast group
     * (which the phone feeds). Playlist files are left out of the queue of these; a remote renderer decides for itself.
     */
    static boolean readsOnThisPhone(Device<?, ?, ?> device) {
        if (device instanceof SnapcastGroupDevice) {
            return true;
        }
        return device != null && device.getIdentity() != null && device.getIdentity().getUdn() != null
                && UpnpClient.LOCAL_UID.equals(device.getIdentity().getUdn().getIdentifierString());
    }

    /** "Only music can be played to Snapcast groups" (once per call of a batch method). */
    void notifySnapcastAudioOnly(UpnpClient upnpClient) {
        final android.content.Context context = upnpClient.getContext();
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, R.string.snapcast_play_audio_only, Toast.LENGTH_LONG).show());
    }

    /**
     * Whether the experimental option "Play to Snapcast groups" is on (off by default). Read at every call, so that
     * switching it takes effect at the next play. False when the preferences can not be read.
     */
    boolean snapcastPlayEnabled(UpnpClient upnpClient) {
        android.content.Context context = upnpClient == null ? null : upnpClient.getContext();
        if (context == null) {
            return false;
        }
        android.content.SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences != null && preferences.getBoolean(context.getString(R.string.settings_snapcast_play_experimental), false);
    }

    /**
     * Takes the Snapcast groups out of the list when the content is not music, and tells the user once. The same list
     * when there is no group in it (or when the content is music).
     */
    java.util.Collection<Device<?, ?, ?>> withoutSnapcastGroups(UpnpClient upnpClient,
                                                                java.util.Collection<Device<?, ?, ?>> devices,
                                                                boolean contentIsNotMusic) {
        if (!contentIsNotMusic) {
            return devices;
        }
        List<Device<?, ?, ?>> kept = new ArrayList<>();
        boolean dropped = false;
        for (Device<?, ?, ?> device : devices) {
            if (device instanceof SnapcastGroupDevice) {
                dropped = true;
            } else {
                kept.add(device);
            }
        }
        if (!dropped) {
            return devices;
        }
        notifySnapcastAudioOnly(upnpClient);
        return kept;
    }

    /**
     * True for a receiver that is listed but has no player: a Snapcast group while the experimental option is off (it
     * has no AVTransport service, so an {@code AVTransportPlayer} would silently do nothing).
     */
    static boolean isNotPlayableYet(Device<?, ?, ?> device) {
        return isNotPlayableYet(device, false);
    }

    /** As {@link #isNotPlayableYet(Device)}, with the state of the experimental option. */
    static boolean isNotPlayableYet(Device<?, ?, ?> device, boolean snapcastPlayEnabled) {
        return !snapcastPlayEnabled && device instanceof SnapcastGroupDevice;
    }

    /**
     * Makes the player of a Snapcast group (the seam of the tests): the group's feed controller comes from the hub, the
     * phone name from the same preference as the UPnP server name.
     */
    @OptIn(markerClass = UnstableApi.class)
    Player newSnapcastPlayer(UpnpClient upnpClient, SnapcastGroupDevice device) {
        android.content.Context context = upnpClient.getContext();
        String friendlyName = device.getDetails().getFriendlyName();
        final SnapcastHub hub = SnapcastHub.getShared(context);
        return new SnapcastPlayer(upnpClient, device, context.getString(R.string.playerNameMusic) + "@" + friendlyName,
                friendlyName, listener -> hub.newFeedController(device, listener));
    }

    /** One player per group: an earlier one for the same group ends first. Music only. */
    @OptIn(markerClass = UnstableApi.class)
    private Player createSnapcastPlayer(UpnpClient upnpClient, SnapcastGroupDevice device,
                                        boolean video, boolean image, boolean music) {
        if (video || image || !music) {
            notifySnapcastAudioOnly(upnpClient);
            return null;
        }
        String deviceId = device.getIdentity().getUdn().getIdentifierString();
        for (Player player : getCurrentPlayersOfType(SnapcastPlayer.class)) {
            if (deviceId.equals(player.getDeviceId())) {
                shutdown(player);
            }
        }
        return newSnapcastPlayer(upnpClient, device);
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
        if (isNotPlayableYet(receiverDevice, snapcastPlayEnabled(upnpClient))) {
            // the batch methods have told the user already (once per call); both callers skip a null player
            return null;
        }
        if (receiverDevice instanceof SnapcastGroupDevice) {
            return createSnapcastPlayer(upnpClient, (SnapcastGroupDevice) receiverDevice, video, image, music);
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
            if (OpenHomeChoice.useOpenHome(upnpClient.getContext(), receiverDevice, music && !video && !image)) {
                for (Player player : getCurrentPlayersOfType(OpenHomePlayer.class)) {
                    if (((OpenHomePlayer) player).getDeviceId().equals(receiverDevice.getIdentity().getUdn().getIdentifierString())) {
                        shutdown(player);
                    }
                }
                return newOpenHomePlayer(upnpClient, receiverDevice);
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

    @OptIn(markerClass = UnstableApi.class)
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
        // ConcurrentHashMap.get(null) throws; keep the old HashMap "not found" result.
        if (id == null) {
            return null;
        }
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
            if (isOfType(typeClazz, player)) {
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
            if (isOfType(typeClazz, player)) {
                return player;
            }
        }
        return null;
    }

    /**
     * A Snapcast player is built on the local music player, but it is not "the local music player" to the code that
     * looks for that one (a new local play, the media renderer's transport commands, the music screen): it answers
     * only to its own class.
     */
    @OptIn(markerClass = UnstableApi.class)
    static boolean isOfType(Class<?> typeClazz, Player player) {
        return typeClazz.isInstance(player) && (!(player instanceof SnapcastPlayer) || typeClazz == SnapcastPlayer.class);
    }

    /**
     * Returns the class of a player for the given mime type.
     *
     * @param mimeType the mime type
     * @return the player class
     */
    @OptIn(markerClass = UnstableApi.class)
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
        playerListChangeNotifier.removeAndNotify(currentActivePlayer, player);
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
