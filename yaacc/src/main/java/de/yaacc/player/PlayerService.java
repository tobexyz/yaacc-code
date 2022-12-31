/*
 * Copyright (C) 2018 www.yaacc.de
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
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

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
import de.yaacc.upnp.SynchronizationInfo;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * @author Tobias Schoene (tobexyz)
 */
public class PlayerService extends Service {

    private IBinder binder = new PlayerServiceBinder();
    private Map<Integer, Player> currentActivePlayer = new HashMap<>();
    private HandlerThread playerHandlerThread;


    public PlayerService() {
    }

    public void addPlayer(Player player) {
        currentActivePlayer.put(player.getId(), player);
    }

    public void removePlayer(Player player) {

        currentActivePlayer.remove(player.getId());
    }

    @Override
    public void onDestroy() {
        Log.d(this.getClass().getName(), "On Destroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this.getClass().getName(), "On Bind");
        return binder;
    }

    public Collection<Player> getPlayer() {
        return currentActivePlayer.values();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(this.getClass().getName(), "Received start id " + startId + ": " + intent);
        Intent notificationIntent = new Intent(this, TabBrowserActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, Yaacc.NOTIFICATION_CHANNEL_ID)
                .setGroup(Yaacc.NOTIFICATION_GROUP_KEY)
                .setContentTitle("Player Service")
                .setContentText("running")
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(NotificationId.PLAYER_SERVICE.getId(), notification);
        initialize(intent);

        return START_STICKY;
    }

    private void initialize(Intent intent) {
        playerHandlerThread = new HandlerThread("de.yaacc.PlayerService.HandlerThread");
        playerHandlerThread.start();
    }

    public HandlerThread getPlayerHandlerThread() {
        return playerHandlerThread;
    }

    public Player getPlayer(int playerId) {
        Log.d(this.getClass().getName(), "Get Player for id " + playerId);
        if (currentActivePlayer.get(playerId) == null) {
            Log.d(this.getClass().getName(), "Get Player not found");
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
    public List<Player> createPlayer(UpnpClient upnpClient,
                                     SynchronizationInfo syncInfo, List<PlayableItem> items) {
        Log.d(getClass().getName(), "create player...");
        List<Player> resultList = new ArrayList<Player>();
        if (items.isEmpty()) {
            return resultList;
        }
        Player result = null;
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
        Log.d(getClass().getName(), "video:" + video + " image: " + image + "audio:" + music);
        for (Device device : upnpClient.getReceiverDevices()) {
            result = createPlayer(upnpClient, device, video, image, music, syncInfo);
            if (result != null) {
                addPlayer(result);
                result.setItems(items.toArray(new PlayableItem[items.size()]));
                resultList.add(result);
            }
        }
        return resultList;
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
    private Player createPlayer(UpnpClient upnpClient, Device receiverDevice,
                                boolean video, boolean image, boolean music, SynchronizationInfo syncInfo) {
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

            if (receiverDevice.getType().getVersion() == 3) {
                for (Player player : getCurrentPlayersOfType(SyncAVTransportPlayer.class)) {
                    if (((SyncAVTransportPlayer) player).getDeviceId().equals(receiverDevice.getIdentity().getUdn().getIdentifierString())
                            && ((SyncAVTransportPlayer) player).getContentType().equals(contentType)) {
                        shutdown(player);
                    }
                }
                result = new SyncAVTransportPlayer(upnpClient, receiverDevice, upnpClient.getContext()
                        .getString(R.string.playerNameAvTransport)
                        + "-" + contentType + "@"
                        + deviceName, receiverDevice.getDetails().getFriendlyName(), contentType);
            } else {
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
            }
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
        result.setSyncInfo(syncInfo);
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
        boolean background = PreferenceManager.getDefaultSharedPreferences(
                upnpClient.getContext()).getBoolean(
                upnpClient.getContext().getString(R.string.settings_audio_app),
                true);
        Player result = getFirstCurrentPlayerOfType(LocalBackgoundMusicPlayer.class);
        if (result != null) {
            shutdown(result);
        } else {
            result = getFirstCurrentPlayerOfType(LocalThirdPartieMusicPlayer.class);
            if (result != null) {
                shutdown(result);
            }
        }
        if (background) {
            return new LocalBackgoundMusicPlayer(upnpClient, upnpClient
                    .getContext().getString(R.string.playerNameMusic), upnpClient
                    .getContext().getString(R.string.playerShortNameMusic));
        }
        return new LocalThirdPartieMusicPlayer(upnpClient, upnpClient
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

    /**
     * returns all current players of the given type.
     *
     * @param typeClazz the requested type
     * @return the currentPlayer
     */
    public List<Player> getCurrentPlayersOfType(Class typeClazz, SynchronizationInfo syncInfo) {

        List<Player> players = getCurrentPlayersOfType(typeClazz);
        for (Player player : players) {
            player.setSyncInfo(syncInfo);
        }
        return players;
    }

    /**
     * returns all current players of the given type.
     *
     * @param typeClazz the requested type
     * @return the currentPlayer
     */
    public List<Player> getCurrentPlayersOfType(Class typeClazz) {
        List<Player> players = new ArrayList<Player>();
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
                result = LocalBackgoundMusicPlayer.class;
            }
        }
        return result;
    }

    /**
     * Kills the given Player
     *
     * @param player
     */
    public void shutdown(Player player) {
        assert (player != null);
        currentActivePlayer.remove(player.getId());
        player.onDestroy();
    }

    /**
     * Kill all Players
     */
    public void shutdown() {
        HashSet<Player> players = new HashSet<Player>();
        players.addAll(getCurrentPlayers());
        for (Player player : players) {
            shutdown(player);
        }

    }

    public void controlDevice(UpnpClient upnpClient, Device device) {
        if (device == null || upnpClient == null) return;
        if (!device.getIdentity().getUdn().getIdentifierString().equals(UpnpClient.LOCAL_UID)) {
            Intent notificationIntent = new Intent(getApplicationContext(),
                    AVTransportPlayerActivity.class);
            Log.d(getClass().getName(), "Put id into intent: " + device.getIdentity().getUdn().getIdentifierString());
            notificationIntent.setData(Uri.parse("http://0.0.0.0/" + device.getIdentity().getUdn().getIdentifierString() + "")); //just for making the intents different http://stackoverflow.com/questions/10561419/scheduling-more-than-one-pendingintent-to-same-activity-using-alarmmanager
            notificationIntent.putExtra(AVTransportController.DEVICE_ID, device.getIdentity().getUdn().getIdentifierString());
            PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0,
                    notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);
            try {
                contentIntent.send(getApplicationContext(), 0, new Intent());
            } catch (PendingIntent.CanceledException e) {
                Log.e(this.getClass().getName(), "Exception on start controller activity", e);
            }

        }

    }

    public class PlayerServiceBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

}
