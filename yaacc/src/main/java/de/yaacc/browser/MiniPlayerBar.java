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

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.browser.MiniPlayerPresenter.TransportAction;
import de.yaacc.player.Player;
import de.yaacc.player.PlayerListChangeNotifier;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.YaaccLogger;
import de.yaacc.util.image.IconDownloadTask;

/**
 * Controller of the mini-player bar of the new layout ({@code mini_player_bar.xml}): shows the
 * featured session (see {@link MiniPlayerPicker}) with its title, receiver name and album art,
 * drives it with previous / play-pause / next / stop and opens its player screen on a tap.
 * Hidden while no player exists.
 * <p>
 * Lives between {@link #bind()} and {@link #unbind()}, which {@link BrowserActivity} calls from
 * {@code onStart} / {@code onStop} (and {@code onDestroy} as a backstop). While bound it listens
 * to player add/remove events and polls every {@value #POLL_INTERVAL_MS} ms for title and play
 * state; both stop on {@code unbind()}, and callbacks that still arrive afterwards are ignored.
 * Player commands run on a single-thread executor that this object owns (created in
 * {@code bind()}, shut down in {@code unbind()}), never on the main thread.
 * <p>
 * Titles and names come from untrusted LAN devices: they are cleaned by
 * {@link MiniPlayerPresenter} and set with {@code setText} only; album art is loaded only
 * through {@link IconDownloadTask}. Main thread only.
 */
final class MiniPlayerBar implements PlayerListChangeNotifier.Listener {

    private static final String TAG = MiniPlayerBar.class.getName();
    static final long POLL_INTERVAL_MS = 2000;
    /** Commands change the state asynchronously; look again shortly after sending one. */
    private static final long REFRESH_AFTER_COMMAND_MS = 500;

    private final View root;
    private final ImageView art;
    private final TextView title;
    private final TextView receiver;
    private final ImageButton previousButton;
    private final ImageButton playPauseButton;
    private final ImageButton nextButton;
    private final ImageButton stopButton;
    private final UpnpClient upnpClient;
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

    private boolean bound;
    private ExecutorService commandExecutor;
    // what is shown, so an unchanged poll rebinds nothing and does not reload the art
    private Player featuredPlayer;
    private boolean shownPlaying;
    private String shownArtKey;
    private String shownReceiverDeviceId;
    private String shownReceiverName = "";

    MiniPlayerBar(@NonNull View root, @NonNull UpnpClient upnpClient) {
        this.root = root;
        this.upnpClient = upnpClient;
        art = root.findViewById(R.id.miniPlayerArt);
        title = root.findViewById(R.id.miniPlayerTitle);
        receiver = root.findViewById(R.id.miniPlayerReceiver);
        previousButton = root.findViewById(R.id.miniPlayerPrevious);
        playPauseButton = root.findViewById(R.id.miniPlayerPlayPause);
        nextButton = root.findViewById(R.id.miniPlayerNext);
        stopButton = root.findViewById(R.id.miniPlayerStop);
    }

    /** Starts following the players: listener, poll and command thread. Shows the current state. */
    void bind() {
        if (bound) {
            return;
        }
        bound = true;
        commandExecutor = Executors.newSingleThreadExecutor();
        previousButton.setOnClickListener(v -> sendCommand(Command.PREVIOUS));
        nextButton.setOnClickListener(v -> sendCommand(Command.NEXT));
        stopButton.setOnClickListener(v -> sendCommand(Command.STOP));
        playPauseButton.setOnClickListener(v -> {
            Player player = featuredPlayer;
            if (player != null) {
                sendCommand(MiniPlayerPresenter.toggleAction(player.isPlaying()) == TransportAction.PAUSE
                        ? Command.PAUSE : Command.PLAY);
            }
        });
        root.setOnClickListener(v -> {
            Player player = featuredPlayer;
            if (player != null) {
                // the player's own explicit, immutable PendingIntent with an empty fill-in
                ReceiverRowBinder.openPlayer(v.getContext(), player);
            }
        });
        ViewCompat.setAccessibilityDelegate(root, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK, host.getContext().getString(R.string.mini_player_open)));
            }
        });
        upnpClient.addPlayerListChangeListener(this);
        refresh();
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /**
     * Stops the listener, the poll and the command thread (queued commands still run), and drops
     * what the views' listeners hold. Safe to call more than once.
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
        previousButton.setOnClickListener(null);
        playPauseButton.setOnClickListener(null);
        nextButton.setOnClickListener(null);
        stopButton.setOnClickListener(null);
        root.setOnClickListener(null);
        ViewCompat.setAccessibilityDelegate(root, null);
        // a late album-art result compares its tag with this one and drops itself
        art.setTag(null);
        featuredPlayer = null;
        shownArtKey = null;
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

    // ---- state -------------------------------------------------------------------------------

    /** Picks the featured session and updates the bar; does nothing while unbound. */
    void refresh() {
        if (!bound) {
            return;
        }
        // The player service hands out its live collection; the picker only gets a copy.
        ArrayList<Player> players = new ArrayList<>(upnpClient.getCurrentPlayers());
        ArrayList<PlayerRef> refs = new ArrayList<>(players.size());
        for (Player player : players) {
            if (player != null) {
                refs.add(new PlayerRef(player.getId(), player.getDeviceId(), player.isPlaying(),
                        upnpClient.getPlayerCreationSequence(player)));
            }
        }
        PlayerRef featured = MiniPlayerPicker.pick(refs);
        Player player = featured == null ? null : find(players, featured.playerId());
        if (!MiniPlayerPresenter.isVisible(featured) || player == null) {
            featuredPlayer = null;
            shownArtKey = null;
            root.setVisibility(View.GONE);
            return;
        }
        show(player);
    }

    private static Player find(ArrayList<Player> players, int playerId) {
        for (Player player : players) {
            if (player != null && player.getId() == playerId) {
                return player;
            }
        }
        return null;
    }

    private void show(Player player) {
        featuredPlayer = player;
        title.setText(MiniPlayerPresenter.title(player.getCurrentItemTitle(), player.getName()));
        receiver.setText(MiniPlayerPresenter.receiverLabel(resolveReceiverName(player.getDeviceId()), player.getName()));
        boolean playing = player.isPlaying();
        if (playing != shownPlaying || root.getVisibility() != View.VISIBLE) {
            shownPlaying = playing;
            playPauseButton.setImageResource(playing
                    ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24);
            playPauseButton.setContentDescription(root.getContext().getString(playing
                    ? R.string.mini_player_pause : R.string.mini_player_play));
        }
        showArt(player);
        root.setVisibility(View.VISIBLE);
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
        if (Objects.equals(deviceId, shownReceiverDeviceId) && !shownReceiverName.isEmpty()) {
            return shownReceiverName;
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
        shownReceiverDeviceId = deviceId;
        shownReceiverName = name == null ? "" : name;
        return name;
    }

    /** Placeholder first, then the album art through IconDownloadTask, only when the art changed. */
    private void showArt(Player player) {
        String artUri = player.getAlbumArt() == null ? null : player.getAlbumArt().toString();
        String artKey = "M:" + player.getId() + ":" + artUri + ":" + (player.getIcon() != null);
        if (artKey.equals(shownArtKey)) {
            return;
        }
        shownArtKey = artKey;
        // IconDownloadTask drops its result when the view's tag is no longer this one
        art.setTag(artKey);
        if (player.getIcon() != null) {
            art.setImageBitmap(player.getIcon());
        } else if (player.getIconResourceId() == R.drawable.yaacc48_24_png) {
            art.setImageResource(R.drawable.yaacc48_24_png);
        } else {
            Drawable placeholder = ResourcesCompat.getDrawable(
                    root.getResources(), player.getIconResourceId(), root.getContext().getTheme());
            if (placeholder != null) {
                art.setImageDrawable(ThemeHelper.tintDrawable(placeholder, root.getContext().getTheme()));
            }
        }
        if (artUri != null) {
            new IconDownloadTask(art, artKey).execute(Uri.parse(artUri));
        }
    }

    // ---- commands ----------------------------------------------------------------------------

    private enum Command {
        PREVIOUS, PLAY, PAUSE, NEXT, STOP
    }

    /** Runs the command on the player that is featured right now, off the main thread. */
    private void sendCommand(Command command) {
        Player player = featuredPlayer;
        ExecutorService executor = commandExecutor;
        if (player == null || executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    switch (command) {
                        case PREVIOUS:
                            player.previous();
                            break;
                        case PLAY:
                            player.play();
                            break;
                        case PAUSE:
                            player.pause();
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
                // look again soon instead of waiting for the next poll; ignored once unbound
                handler.postDelayed(refreshRunnable, REFRESH_AFTER_COMMAND_MS);
            });
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(TAG, "Player command ignored: bar is unbound");
        }
    }
}
