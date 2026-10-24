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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.browser;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.browser.MiniPlayerAdapter.Command;
import de.yaacc.browser.MiniPlayerPresenter.TransportAction;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;

/**
 * Controller of the mini-player of the new layout ({@code mini_player_bar.xml}): a compact list
 * with one row per running player ({@link MiniPlayerAdapter}), ordered like the device sheet's
 * sessions ({@link MiniPlayerRows#order}, oldest first). Each row shows the title, receiver name
 * and album art and drives its player with previous / play-pause / next / stop; a tap on the row
 * opens that player's screen. The list is hidden while no player exists, wraps its rows up to
 * about three and scrolls inside beyond that ({@link MaxHeightRecyclerView}).
 * <p>
 * Lives between {@link #bind()} and {@link #unbind()}, which {@link BrowserActivity} calls from
 * {@code onStart} / {@code onStop} (and {@code onDestroy} as a backstop). While bound it listens
 * to player add/remove events and polls every {@value #POLL_INTERVAL_MS} ms for titles and play
 * states; both stop on {@code unbind()}, and callbacks that still arrive afterwards are ignored.
 * Player commands run on one single-thread executor that this object owns (created in
 * {@code bind()}, shut down in {@code unbind()}), never on the main thread.
 * <p>
 * Titles and names come from untrusted LAN devices: they are cleaned by
 * {@link MiniPlayerPresenter} and set with {@code setText} only; album art is loaded only
 * through {@code IconDownloadTask}, once per player-and-art key (see {@link MiniPlayerAdapter}).
 * Main thread only.
 */
final class MiniPlayerBar implements PlayerListChangeNotifier.Listener, MiniPlayerAdapter.Actions {

    private static final String TAG = MiniPlayerBar.class.getName();
    static final long POLL_INTERVAL_MS = 2000;
    /** Commands change the state asynchronously; look again shortly after sending one. */
    private static final long REFRESH_AFTER_COMMAND_MS = 500;

    private final RecyclerView list;
    private final UpnpClient upnpClient;
    private final MiniPlayerAdapter adapter = new MiniPlayerAdapter(this);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!bound) {
                return;
            }
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };
    private final Runnable refreshRunnable = this::refresh;

    private volatile boolean bound;
    private ExecutorService commandExecutor;
    // resolved receiver names per device id; cleared on unbind so a renamed device is picked up
    private final Map<String, String> receiverNames = new HashMap<>();

    MiniPlayerBar(@NonNull RecyclerView list, @NonNull UpnpClient upnpClient) {
        this.list = list;
        this.upnpClient = upnpClient;
        list.setLayoutManager(new LinearLayoutManager(list.getContext()));
        list.setItemAnimator(null); // no flashing on live updates
        list.setAdapter(adapter);
    }

    /** Starts following the players: listener, poll and command thread. Shows the current state. */
    void bind() {
        if (bound) {
            return;
        }
        bound = true;
        commandExecutor = Executors.newSingleThreadExecutor();
        upnpClient.addPlayerListChangeListener(this);
        refresh();
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /**
     * Stops the listener, the poll and the command thread (queued commands still run), and
     * empties the list, so no row can be tapped any more. Safe to call more than once.
     */
    void unbind() {
        if (!bound) {
            return;
        }
        bound = false;
        upnpClient.removePlayerListChangeListener(this);
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(refreshRunnable);
        if (commandExecutor != null) {
            commandExecutor.shutdown();
            commandExecutor = null;
        }
        // the rows drop their players and art bookkeeping; a late art result drops itself on the
        // tag compare (recycled holders clear their tag)
        adapter.clear();
        list.setVisibility(View.GONE);
        // a renamed device must not keep showing its old name after the next bind
        receiverNames.clear();
    }

    // ---- PlayerListChangeNotifier.Listener (delivered on the main thread) ---------------------

    @Override
    public void onPlayerAdded(Player player) {
        refresh();
    }

    @Override
    public void onPlayerRemoved(Player player) {
        refresh();
    }

    // ---- rows --------------------------------------------------------------------------------

    /** Rebuilds the rows from the running players and updates the list; does nothing while unbound. */
    void refresh() {
        if (!bound) {
            return;
        }
        try {
            refreshFromPlayers();
        } catch (RuntimeException e) {
            // a player changing under the poll (its item list is cleared by worker threads);
            // the next poll tries again. Never log titles or names.
            YaaccLogger.w(TAG, "Mini-player refresh skipped (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void refreshFromPlayers() {
        // The player service hands out its live collection; the ordering only gets copies.
        ArrayList<Player> players = new ArrayList<>(upnpClient.getCurrentPlayers());
        Map<Integer, Player> playersById = new HashMap<>();
        List<PlayerRef> refs = new ArrayList<>(players.size());
        for (Player player : players) {
            if (player != null) {
                refs.add(new PlayerRef(player.getId(), player.getDeviceId(), player.isPlaying(),
                        upnpClient.getPlayerCreationSequence(player)));
                if (!playersById.containsKey(player.getId())) {
                    playersById.put(player.getId(), player);
                }
            }
        }
        List<MiniPlayerAdapter.Row> rows = new ArrayList<>();
        for (PlayerRef ref : MiniPlayerRows.order(refs)) {
            Player player = playersById.get(ref.playerId());
            if (player != null) {
                rows.add(rowFor(player, ref));
            }
        }
        adapter.submit(rows);
        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private MiniPlayerAdapter.Row rowFor(Player player, PlayerRef ref) {
        // only in-memory reads here: no network and no getIcon() on the poll path
        String title = MiniPlayerPresenter.title(player.getCurrentItemTitle(), player.getName());
        String receiver = MiniPlayerPresenter.receiverLabel(
                resolveReceiverName(ref.deviceId()), player.getName());
        URI artUri = player.getAlbumArt();
        return new MiniPlayerAdapter.Row(ref.playerId(), player, title, receiver, ref.playing(),
                artUri == null ? null : artUri.toString());
    }

    /**
     * The receiver's name as the device sheet resolves it ({@link UpnpClient#getDevice}, which
     * also answers for {@link UpnpClient#LOCAL_UID} with this device); null while the device is
     * not known. Cached per device id once it resolved.
     */
    private String resolveReceiverName(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        String cached = receiverNames.get(deviceId);
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            Device<?, ?, ?> device = upnpClient.getDevice(deviceId);
            if (device != null && device.getDetails() != null) {
                name = device.getDetails().getFriendlyName();
            }
        } catch (RuntimeException e) {
            // an id that is not a valid UDN; the player's own name is shown instead
            YaaccLogger.d(TAG, "Receiver not resolved (" + e.getClass().getSimpleName() + ")");
        }
        if (name != null) {
            receiverNames.put(deviceId, name);
        }
        return name;
    }

    // ---- taps (MiniPlayerAdapter.Actions, main thread) ----------------------------------------

    @Override
    public void onOpen(@NonNull Player player) {
        if (!bound) {
            return;
        }
        // the player's own explicit, immutable PendingIntent with an empty fill-in
        ReceiverRowBinder.openPlayer(list.getContext(), player);
    }

    @Override
    public void onCommand(@NonNull Player player, @NonNull Command command) {
        sendCommand(player, command);
    }

    // ---- commands ----------------------------------------------------------------------------

    /** Runs the command on the row's player, off the main thread; ignored once unbound. */
    private void sendCommand(Player player, Command command) {
        ExecutorService executor = commandExecutor;
        if (!bound || executor == null || executor.isShutdown()) {
            return;
        }
        // the toggle follows the player's state at the moment of the tap
        boolean pause = command == Command.PLAY_PAUSE
                && MiniPlayerPresenter.toggleAction(player.isPlaying()) == TransportAction.PAUSE;
        try {
            executor.execute(() -> {
                try {
                    switch (command) {
                        case PREVIOUS:
                            player.previous();
                            break;
                        case PLAY_PAUSE:
                            if (pause) {
                                player.pause();
                            } else {
                                player.play();
                            }
                            break;
                        case NEXT:
                            player.next();
                            break;
                        case STOP:
                            player.stop();
                            break;
                        default:
                            break;
                    }
                } catch (RuntimeException e) {
                    // a player that is shutting down; nothing to tell the user
                    YaaccLogger.w(TAG, "Player command failed (" + e.getClass().getSimpleName() + ")");
                }
                // look again soon instead of waiting for the next poll; skipped once unbound
                if (bound) {
                    handler.postDelayed(refreshRunnable, REFRESH_AFTER_COMMAND_MS);
                }
            });
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(TAG, "Player command ignored: bar is unbound");
        }
    }
}
