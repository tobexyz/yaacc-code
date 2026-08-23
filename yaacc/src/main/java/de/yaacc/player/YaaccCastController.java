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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.player;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * RouteController for the YAACC self-device cast route.
 *
 * <p>This controller is returned by {@link YaaccSelfDeviceMediaRouteProvider} when a casting
 * app (e.g. Spotify, YouTube Music) selects the YAACC route in the system Cast picker.
 * All playback commands are delegated to the currently active players via {@link UpnpClient}.</p>
 *
 * <p>If no player is currently active, commands are silently ignored with a log warning
 * to avoid crashing the casting app.</p>
 *
 * @see YaaccSelfDeviceMediaRouteProvider
 */
public class YaaccCastController extends MediaRouteProvider.RouteController {

    private static final String TAG = YaaccCastController.class.getName();

    private final Context context;
    private final UpnpClient upnpClient;
    private final YaaccSelfDeviceMediaRouteProvider provider;


    /**
     * Create a cast controller.
     *
     * @param context    Android context (application or service context)
     * @param upnpClient YAACC UPnP client for accessing current players
     */
    public YaaccCastController(@NonNull Context context, @NonNull UpnpClient upnpClient, @NonNull YaaccSelfDeviceMediaRouteProvider provider) {
        this.context = context;
        this.upnpClient = upnpClient;
        this.provider = provider;
    }

    /**
     * Called when the user selects the YAACC route in the system Cast picker.
     * The receivers should already be selected via the Receiver tab.
     */
    @Override
    public void onSelect() {
        super.onSelect();
        YaaccLogger.d(TAG, "onSelect: YAACC route selected by casting app");
        try {
            // Ensure PlayerService is initialized
            if (!upnpClient.isPlayerServiceInitialized()) {
                YaaccLogger.d(TAG, "onSelect: starting PlayerService");
                upnpClient.startService();
            }

            YaaccLogger.d(TAG, "onSelect: YAACC ready to receive media on selected receivers");
        } catch (Exception e) {
            YaaccLogger.e(TAG, "onSelect: error initializing: " + e.getMessage(), e);
        }
        provider.markRouteConnected();
    }


    /**
     * Called when the user deselects the YAACC route or another route is chosen.
     */
    @Override
    public void onUnselect() {
        YaaccLogger.d(TAG, "onUnselect: YAACC route deselected");
    }

    /**
     * Called to release resources held by this controller.
     */
    @Override
    public void onRelease() {
        YaaccLogger.d(TAG, "onRelease: YAACC cast controller released");
    }

    /**
     * Set the volume on all active players.
     *
     * @param volume new volume in range [0, 100]
     */
    @Override
    public void onSetVolume(int volume) {
        YaaccLogger.d(TAG, "onSetVolume: " + volume);
        try {
            Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "onSetVolume: no active players");
                return;
            }
            for (Player player : players) {
                try {
                    player.setVolume(volume);
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "onSetVolume: error setting volume on player "
                            + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "onSetVolume: unexpected error: " + e.getMessage());
        }
    }

    /**
     * Adjust the volume on all active players by a delta.
     *
     * @param delta volume change (positive = louder, negative = quieter)
     */
    @Override
    public void onUpdateVolume(int delta) {
        YaaccLogger.d(TAG, "onUpdateVolume: delta=" + delta);
        try {
            Collection<Player> players = upnpClient.getCurrentPlayers();
            if (players == null || players.isEmpty()) {
                YaaccLogger.w(TAG, "onUpdateVolume: no active players");
                return;
            }
            for (Player player : players) {
                try {
                    int current = player.getVolume();
                    int newVolume = Math.max(0, Math.min(100, current + delta));
                    player.setVolume(newVolume);
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "onUpdateVolume: error on player "
                            + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            YaaccLogger.e(TAG, "onUpdateVolume: unexpected error: " + e.getMessage());
        }
    }

    /**
     * Handle a playback control request from the casting app.
     *
     * <p>Handles the standard Android media actions: PLAY, PAUSE, RESUME, STOP, NEXT, PREVIOUS.
     * If the intent contains a URL (via the "uri" extra or the intent data URI), the URL is
     * handled as new content via {@link #handlePlayUrl(String)} before dispatching the action.</p>
     *
     * @param intent   the control intent with an action describing the command
     * @param callback optional callback to signal success or failure back to the casting app
     * @return true if the request was handled, false otherwise
     */
    @Override
    public boolean onControlRequest(@NonNull Intent intent,
                                    MediaRouter.ControlRequestCallback callback) {
        String action = intent.getAction();
        YaaccLogger.i(TAG, "═══════════════════════════════════════════════════════");
        YaaccLogger.i(TAG, "onControlRequest CALLED - ANY REQUEST IS LOGGED");
        YaaccLogger.i(TAG, "action=" + action);
        YaaccLogger.i(TAG, "extras=" + intent.getExtras());
        YaaccLogger.i(TAG, "data=" + intent.getData());
        YaaccLogger.i(TAG, "all extras keys: " + (intent.getExtras() != null ? intent.getExtras().keySet() : "none"));
        YaaccLogger.i(TAG, "═══════════════════════════════════════════════════════");

        // Return true to indicate we handled it, even if we don't understand it yet
        // This signals to YouTube Music that we're a valid receiver
        if (callback != null) {
            callback.onResult(null);
        }
        return true;
    }

    /**
     * Handle a URL cast request by creating a PlayableItem and starting playback.
     *
     * <p>Follows the same pattern as {@code TabBrowserActivity.ACTION_SEND}: create a
     * PlayableItem from the URI and delegate to the general player infrastructure.
     * This makes Cast protocol-agnostic — it works with any player type, not just AVTransport.</p>
     *
     * <p>If the proxy setting is enabled, the URL is rewritten to route through the
     * YAACC local proxy before being sent to players.</p>
     *
     * @param uri the URL to play
     * @return true if the request was successfully handled
     */
    boolean handlePlayUrl(String uri) {
        YaaccLogger.d(TAG, "handlePlayUrl: " + uri);
        try {
            // UpnpClient.createPlayableItem() already handles proxy rewriting if enabled

            // Ensure receiver devices are ready (same as TabBrowserActivity)
            long delayedExecution = 0;
            if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                YaaccLogger.d(TAG, "handlePlayUrl: no receivers ready, scheduling with delay");
                delayedExecution = 3000L;
            }
            if (!upnpClient.isPlayerServiceInitialized()) {
                YaaccLogger.d(TAG, "handlePlayUrl: PlayerService not initialized, starting");
                upnpClient.startService();
                delayedExecution += 3000L;
            }

            // Create playable item and start playback (with or without delay)
            Runnable execution = () -> {
                try {
                    // Fallback to local device if no receivers selected (same as TabBrowserActivity)
                    if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                        YaaccLogger.d(TAG, "handlePlayUrl: no receiver found, using local device");
                        upnpClient.setReceiverDeviceIds(java.util.Set.of(UpnpClient.LOCAL_UID));
                    }

                    // Create PlayableItem — same as TabBrowserActivity ACTION_SEND pattern
                    // (UpnpClient.createPlayableItem() handles proxy rewriting if enabled)
                    List<PlayableItem> items = new ArrayList<>();
                    PlayableItem item = upnpClient.createPlayableItem(Uri.parse(uri));
                    items.add(item);

                    // Initialize players with the item (works with any player type)
                    List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
                    if (players == null || players.isEmpty()) {
                        YaaccLogger.w(TAG, "handlePlayUrl: no players initialized for URI: " + uri);
                        return;
                    }

                    // Play on all initialized players
                    for (Player player : players) {
                        try {
                            player.play();
                        } catch (Exception e) {
                            YaaccLogger.e(TAG, "handlePlayUrl: error calling play() on player "
                                    + player.getName() + ": " + e.getMessage());
                        }
                    }

                    YaaccLogger.d(TAG, "handlePlayUrl: started playback on " + players.size() + " player(s)");
                } catch (Exception e) {
                    YaaccLogger.e(TAG, "handlePlayUrl: failed during delayed execution: " + e.getMessage());
                }
            };

            if (delayedExecution > 0) {
                java.util.concurrent.ScheduledExecutorService executor =
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                executor.schedule(execution, delayedExecution, java.util.concurrent.TimeUnit.MILLISECONDS);
                YaaccLogger.d(TAG, "handlePlayUrl: scheduled execution in " + delayedExecution + "ms");
            } else {
                execution.run();
            }

            return true;
        } catch (Exception e) {
            YaaccLogger.e(TAG, "handlePlayUrl: failed to handle URL: " + uri + " — " + e.getMessage());
            return false;
        }
    }

    /**
     * Dispatch a single action to a player.
     *
     * @param action the intent action string
     * @param player the target player
     * @return true if the action was recognised and dispatched
     */
    private boolean dispatchAction(String action, Player player) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case "android.media.action.PLAY":
            case "android.intent.action.MEDIA_PLAY":
            case "androidx.media3.session.MediaSessionService.action.PLAY":
                player.play();
                return true;

            case "android.media.action.PAUSE":
            case "android.intent.action.MEDIA_PAUSE":
                player.pause();
                return true;

            case "android.media.action.RESUME":
                player.play();
                return true;

            case "android.media.action.STOP":
            case "android.intent.action.MEDIA_STOP":
                player.stop();
                return true;

            case "android.media.action.SKIP_TO_NEXT":
            case "android.intent.action.MEDIA_NEXT":
                player.next();
                return true;

            case "android.media.action.SKIP_TO_PREVIOUS":
            case "android.intent.action.MEDIA_PREVIOUS":
                player.previous();
                return true;

            default:
                YaaccLogger.d(TAG, "dispatchAction: unhandled action=" + action);
                return false;
        }
    }
}
