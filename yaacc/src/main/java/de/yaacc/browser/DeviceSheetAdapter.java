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

import android.content.Context;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.yaacc.R;
import de.yaacc.browser.DeviceSessionGrouper.PlayerRef;
import de.yaacc.browser.DeviceSessionGrouper.Row;
import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.ThemeHelper;
import de.yaacc.util.image.IconDownloadTask;

/**
 * List of the device sheet: every receiver row (bound by
 * {@link ReceiverRowBinder}, exactly like the Receiver tab), each followed by
 * the yaacc player sessions running on it, as {@link DeviceSessionGrouper}
 * orders them. Sessions on a device that isn't a receiver follow at the end.
 * <p>
 * Main thread only. Call {@link #release()} when the hosting view goes away.
 */
final class DeviceSheetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_RECEIVER = 0;
    private static final int TYPE_SESSION = 1;

    /** One list entry: a receiver (device set) or a player session (player set). */
    private static final class Entry {
        final Row.Kind kind;
        final String deviceId;
        final Device<?, ?, ?> device;
        final Player player;
        /** Session whose device isn't in the receiver list. */
        final boolean orphan;
        final long stableId;
        /** Receivers: the player id the row shows (0 = none), so a change rebinds the row. */
        int primaryPlayerId;
        /** What the session row showed when last bound; see {@link #refreshLiveState()}. */
        String boundSignature;

        Entry(Row.Kind kind, String deviceId, Device<?, ?, ?> device, Player player, boolean orphan, long stableId) {
            this.kind = kind;
            this.deviceId = deviceId;
            this.device = device;
            this.player = player;
            this.orphan = orphan;
            this.stableId = stableId;
        }
    }

    private final Context context;
    private final UpnpClient upnpClient;
    private final ReceiverRowBinder rowBinder;
    private final List<Entry> entries = new ArrayList<>();
    // Stable ids keep D-pad focus on the same row across refreshes.
    private final Map<String, Long> stableIds = new HashMap<>();
    // The checkbox already shows the new state and UpnpClient fires
    // receiverDeviceAdded/Removed (which updates the Content tab's bar), so
    // the sheet has nothing else to do on a selection change.
    private final ReceiverRowBinder.SelectionListener selectionListener = (device, selected) -> {
    };
    private String localStatusSignature;
    private boolean focusFirstReceiver;

    DeviceSheetAdapter(Context context, UpnpClient upnpClient) {
        this.context = context;
        this.upnpClient = upnpClient;
        this.rowBinder = new ReceiverRowBinder(context, upnpClient);
        setHasStableIds(true);
    }

    /**
     * Rebuilds the rows from the current receivers and players. Receivers are
     * the devices offering AVTransport, in the same order the Receiver tab's
     * source list has (local device first, then by name), each resolved by id
     * through {@link UpnpClient#getDevice(String)}.
     */
    void refresh() {
        Map<String, Device<?, ?, ?>> receiversById = new LinkedHashMap<>();
        for (Device<?, ?, ?> listed : upnpClient.getDevicesProvidingAvTransportService()) {
            String id = listed.getIdentity().getUdn().getIdentifierString();
            if (!receiversById.containsKey(id)) {
                Device<?, ?, ?> device = upnpClient.getDevice(id);
                if (device != null) {
                    receiversById.put(id, device);
                }
            }
        }

        List<PlayerRef> refs = new ArrayList<>();
        Map<Integer, Player> playersById = new HashMap<>();
        for (Player player : new ArrayList<>(upnpClient.getCurrentPlayers())) {
            refs.add(new PlayerRef(player.getId(), player.getDeviceId(), player.isPlaying(),
                    upnpClient.getPlayerCreationSequence(player)));
            playersById.put(player.getId(), player);
        }

        List<Entry> newEntries = new ArrayList<>();
        for (Row row : DeviceSessionGrouper.group(new ArrayList<>(receiversById.keySet()), refs)) {
            if (row.kind() == Row.Kind.RECEIVER) {
                Entry receiver = new Entry(Row.Kind.RECEIVER, row.deviceId(), receiversById.get(row.deviceId()),
                        null, false, stableId("R:" + row.deviceId()));
                PlayerRef primary = DeviceSessionGrouper.primarySession(row.deviceId(), refs);
                receiver.primaryPlayerId = primary == null ? 0 : primary.playerId();
                newEntries.add(receiver);
            } else {
                Player player = playersById.get(row.playerId());
                if (player != null) {
                    newEntries.add(new Entry(Row.Kind.PLAYER_SESSION, row.deviceId(), null, player,
                            !receiversById.containsKey(row.deviceId()), stableId("P:" + row.playerId())));
                }
            }
        }
        // Rows whose device/player object is unchanged aren't rebound (which
        // would re-run the volume/mute load tasks); live text changes are
        // handled by refreshLiveState().
        List<Entry> oldEntries = new ArrayList<>(entries);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldEntries.size();
            }

            @Override
            public int getNewListSize() {
                return newEntries.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return oldEntries.get(oldPosition).stableId == newEntries.get(newPosition).stableId;
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Entry before = oldEntries.get(oldPosition);
                Entry after = newEntries.get(newPosition);
                return before.device == after.device && before.player == after.player
                        && before.orphan == after.orphan && before.primaryPlayerId == after.primaryPlayerId;
            }
        });
        for (int i = 0; i < newEntries.size(); i++) {
            // Keep what an unchanged session row currently shows.
            Entry after = newEntries.get(i);
            for (Entry before : oldEntries) {
                if (before.stableId == after.stableId && before.player == after.player) {
                    after.boundSignature = before.boundSignature;
                    break;
                }
            }
        }
        entries.clear();
        entries.addAll(newEntries);
        diff.dispatchUpdatesTo(this);
    }

    /** Asks for focus on the first receiver's checkbox once that row is bound (D-pad start). */
    void requestInitialFocus() {
        focusFirstReceiver = true;
    }

    /** A status from {@link RendererStatusMonitor}: refreshes that receiver's row. */
    void onStatusChanged(RendererStatus status) {
        rowBinder.updateStatus(status);
        String deviceId = status.getDevice().getIdentity().getUdn().getIdentifierString();
        int position = receiverPosition(deviceId);
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position);
        }
    }

    /**
     * Polled while the sheet is open: the local device has no
     * {@code RendererStatusMonitor} feed (same as the Receiver tab's 2 s
     * poll), and session titles change with every track. Only rows whose
     * shown state changed are rebound.
     */
    void refreshLiveState() {
        int localPosition = receiverPosition(UpnpClient.LOCAL_UID);
        if (localPosition != RecyclerView.NO_POSITION) {
            Device<?, ?, ?> localDevice = entries.get(localPosition).device;
            RendererStatus status = rowBinder.localDeviceStatus(localDevice);
            String signature = status == null ? null : status.getState() + "|" + status.getTrackTitle();
            if (!Objects.equals(signature, localStatusSignature)) {
                localStatusSignature = signature;
                if (status == null) {
                    rowBinder.clearStatus(localDevice);
                } else {
                    rowBinder.updateStatus(status);
                }
                notifyItemChanged(localPosition);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.kind == Row.Kind.PLAYER_SESSION
                    && !Objects.equals(entry.boundSignature, sessionSignature(entry.player))) {
                notifyItemChanged(i);
            }
        }
    }

    /** Stops the binder's transport-action thread; call when the sheet's view goes away. */
    void release() {
        rowBinder.shutdown();
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).stableId;
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).kind == Row.Kind.RECEIVER ? TYPE_RECEIVER : TYPE_SESSION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_RECEIVER) {
            View view = inflater.inflate(R.layout.browse_receiver_device_item, parent, false);
            ReceiverHolder holder = new ReceiverHolder(view);
            // Same row interaction as the Receiver tab (BrowseReceiverDeviceAdapter).
            view.setOnClickListener(v -> {
                Entry entry = entryAt(holder.getBindingAdapterPosition());
                if (entry != null && entry.device != null) {
                    rowBinder.openPlayerActivity(v.getContext(), entry.device);
                }
            });
            view.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        v.performClick();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        holder.views.checkBox.requestFocus();
                        return true;
                    default:
                        return false;
                }
            });
            return holder;
        }
        View view = inflater.inflate(R.layout.device_sheet_player_session_item, parent, false);
        SessionHolder holder = new SessionHolder(view);
        view.setOnClickListener(v -> {
            Entry entry = entryAt(holder.getBindingAdapterPosition());
            if (entry != null && entry.player != null) {
                ReceiverRowBinder.openPlayer(v.getContext(), entry.player);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof ReceiverHolder) {
            ReceiverHolder receiverHolder = (ReceiverHolder) holder;
            Set<String> selectedIds = upnpClient.getReceiverDeviceIds();
            rowBinder.bind(receiverHolder.views, entry.device, selectedIds.contains(entry.deviceId), selectionListener);
            if (focusFirstReceiver && position == firstReceiverPosition()) {
                focusFirstReceiver = false;
                receiverHolder.views.checkBox.post(receiverHolder.views.checkBox::requestFocus);
            }
        } else {
            bindSession((SessionHolder) holder, entry);
        }
    }

    private void bindSession(SessionHolder holder, Entry entry) {
        Player player = entry.player;
        entry.boundSignature = sessionSignature(player);
        // Untrusted LAN strings: plain setText only.
        holder.name.setText(player.getName());
        String title = player.getCurrentItemTitle();
        if (title != null && !title.trim().isEmpty()) {
            holder.title.setText(title);
            holder.title.setVisibility(View.VISIBLE);
        } else {
            holder.title.setVisibility(View.GONE);
        }
        if (entry.orphan && entry.deviceId != null) {
            holder.device.setText(entry.deviceId);
            holder.device.setVisibility(View.VISIBLE);
        } else {
            holder.device.setVisibility(View.GONE);
        }

        String artTag = "P:" + player.getId();
        holder.art.setTag(artTag);
        if (player.getIcon() != null) {
            holder.art.setImageBitmap(player.getIcon());
        } else if (player.getIconResourceId() == R.drawable.yaacc48_24_png) {
            holder.art.setImageResource(R.drawable.yaacc48_24_png);
        } else {
            holder.art.setImageDrawable(ThemeHelper.tintDrawable(
                    ResourcesCompat.getDrawable(context.getResources(), player.getIconResourceId(), context.getTheme()),
                    context.getTheme()));
        }
        if (player.getAlbumArt() != null) {
            // The tag makes the task drop its result if this row was recycled meanwhile.
            new IconDownloadTask(holder.art, artTag).execute(Uri.parse(player.getAlbumArt().toString()));
        }
    }

    private static String sessionSignature(Player player) {
        return player.getName() + "|" + player.getCurrentItemTitle() + "|" + player.getAlbumArt();
    }

    private long stableId(String key) {
        Long id = stableIds.get(key);
        if (id == null) {
            id = (long) stableIds.size();
            stableIds.put(key, id);
        }
        return id;
    }

    private Entry entryAt(int position) {
        return position == RecyclerView.NO_POSITION || position >= entries.size() ? null : entries.get(position);
    }

    private int receiverPosition(String deviceId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.kind == Row.Kind.RECEIVER && Objects.equals(entry.deviceId, deviceId)) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    private int firstReceiverPosition() {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind == Row.Kind.RECEIVER) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    static final class ReceiverHolder extends RecyclerView.ViewHolder {
        final ReceiverRowBinder.RowViews views;

        ReceiverHolder(@NonNull View itemView) {
            super(itemView);
            views = new ReceiverRowBinder.RowViews(itemView);
        }
    }

    static final class SessionHolder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView name;
        final TextView title;
        final TextView device;

        SessionHolder(@NonNull View itemView) {
            super(itemView);
            art = itemView.findViewById(R.id.deviceSheetSessionArt);
            name = itemView.findViewById(R.id.deviceSheetSessionName);
            title = itemView.findViewById(R.id.deviceSheetSessionTitle);
            device = itemView.findViewById(R.id.deviceSheetSessionDevice);
        }
    }
}
