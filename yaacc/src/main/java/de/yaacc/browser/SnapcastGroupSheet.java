/*
 *
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

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastMarkerStore;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.ui.SnapcastControls;
import de.yaacc.multiroom.ui.SnapcastInputs;
import de.yaacc.multiroom.ui.SnapcastMemberRows;
import de.yaacc.multiroom.ui.SnapcastRowText;
import de.yaacc.multiroom.ui.SnapcastStreamChoices;

/**
 * The sheet of a Snapcast group, in a bottom sheet like the other receiver sheets: the group's name, the <b>endpoint of its
 * server</b> (so that a hostile server cannot pass for a legitimate group, security review S20), the group volume and
 * mute, the stream, and the members, each with its own volume slider and mute symbol and an overflow menu (Rename, Latency).
 * The mute is the symbol of the other receiver cards ({@link SnapcastMuteToggle}), never a switch or a check box (11.1). Which
 * clients belong to the group is not set here but in the link sheet that the link icon of the card opens
 * ({@link SnapcastLinkSheet}, 11.3).
 *
 * <p>Thin Android glue: the model is {@link SnapcastReceivers#stateOf}, the texts and orders come from the presentation
 * classes of {@code de.yaacc.multiroom.ui}, and every change goes through the shared {@link SnapcastControls} (off the main
 * thread, coalesced, failures as a toast). The sheet follows the server while it is open: a change of the model draws it
 * again a moment later, and never while a finger is on a slider.
 */
final class SnapcastGroupSheet {

    private static final long RENDER_DELAY_MILLIS = 250L;

    private final Context context;
    private final String receiverId;
    private final SnapcastServerKey serverKey;
    private final String groupId;
    private final String fallbackTitle;
    private final SnapcastReceivers receivers;
    private final SnapcastControls controls;
    private final BottomSheetDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView title;
    private final TextView summary;
    private final LinearLayout body;
    private final SnapcastRowText.Words rowWords;
    private final float density;
    private final String marker;
    private boolean touching;
    private boolean renderWanted;
    private Object lastRendered;
    private AlertDialog openDialog;

    private final SnapcastReceivers.ChangeListener changeListener = () -> main.post(this::scheduleRender);
    private final Runnable renderTask = this::render;

    private SnapcastGroupSheet(Context context, SnapcastGroupDevice device, SnapcastGroupDevice.Id id) {
        this.context = context;
        this.receiverId = device.getIdentity().getUdn().getIdentifierString();
        this.serverKey = id.getServerKey();
        this.groupId = id.getGroupId();
        this.fallbackTitle = SnapcastRowBinder.cleanName(device.getDetails().getFriendlyName());
        this.receivers = SnapcastHub.getShared(context).getReceivers();
        this.controls = SnapcastUi.controls(context);
        this.rowWords = SnapcastUi.rowWords(context);
        this.density = context.getResources().getDisplayMetrics().density;
        String stored = SnapcastMarkerStore.forContext(context).load();
        this.marker = stored;
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, pad);
        title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(pad, dp(4), pad, dp(4));
        root.addView(title);
        summary = new TextView(context);
        summary.setTextSize(12);
        summary.setPadding(pad, 0, pad, dp(8));
        root.addView(summary);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(context); // nested scrolling, so the sheet does not take the drag
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            receivers.removeChangeListener(changeListener);
            main.removeCallbacks(renderTask);
            if (openDialog != null && openDialog.isShowing()) {
                openDialog.dismiss();
            }
        });
    }

    /** Opens the sheet of a group. */
    static Dialog show(Context context, SnapcastGroupDevice device) {
        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(device.getIdentity().getUdn().getIdentifierString());
        if (id == null) {
            return null;
        }
        SnapcastGroupSheet sheet = new SnapcastGroupSheet(context, device, id);
        sheet.dialog.show();
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        SnapcastHub.getShared(context).addChangeListener(sheet.changeListener);
        sheet.render();
        return sheet.dialog;
    }

    private int dp(int value) {
        return (int) (value * density);
    }

    private void scheduleRender() {
        main.removeCallbacks(renderTask);
        main.postDelayed(renderTask, RENDER_DELAY_MILLIS);
    }

    // ---- drawing

    private void render() {
        SnapcastReceivers.GroupState state = receivers.stateOf(receiverId);
        SnapcastGroup group = state == null ? null : state.getGroup();
        SnapcastRowText.Row row = state == null
                ? SnapcastRowText.of(null, null, false, serverKey.asString(), rowWords)
                : SnapcastRowText.of(group, state.getStatus(), state.isOnline(), state.getEndpointText(), rowWords);
        List<SnapcastMemberRows.Member> members = group == null ? new ArrayList<SnapcastMemberRows.Member>()
                : SnapcastMemberRows.of(group, SnapcastUi.memberWords(context));
        String groupName = group == null ? fallbackTitle : groupTitle(group);
        Object signature = Arrays.asList(row, members, groupName);
        if (signature.equals(lastRendered)) {
            return;
        }
        if (touching) {
            renderWanted = true; // drawn when the finger is up
            return;
        }
        lastRendered = signature;
        title.setText(groupName);
        summary.setText(row.getText());
        body.removeAllViews();
        if (group == null) {
            return;
        }
        boolean online = state.isOnline();
        int pad = dp(16);
        final CheckBox mute = SnapcastMuteToggle.create(context, context.getString(R.string.snapcast_sheet_group_mute));
        mute.setChecked(group.isMuted());
        SnapcastMuteToggle.syncState(context, mute);
        mute.setEnabled(online);
        mute.setOnClickListener(v -> {
            SnapcastMuteToggle.syncState(context, mute);
            SnapcastUi.volumeKeys(context).muteChanged(receiverId, mute.isChecked());
            controls.setGroupMuted(serverKey, groupId, mute.isChecked());
        });
        body.addView(sliderRow(context.getString(R.string.snapcast_sheet_group_volume), row.getVolume(), row.isControllable(),
                new SliderListener() {
                    @Override
                    public void onStart() {
                        controls.beginGroupGesture(serverKey, groupId);
                    }

                    @Override
                    public void onChange(int percent) {
                        SnapcastUi.volumeKeys(context).volumeChanged(receiverId, percent); // the next key starts here
                        controls.setGroupVolume(serverKey, groupId, percent);
                    }
                }, mute));
        body.addView(valueRow(context.getString(R.string.snapcast_sheet_stream), streamLabel(group, state), online,
                v -> showStreamPicker()));
        body.addView(sectionLabel(context.getString(R.string.snapcast_sheet_members)));
        if (members.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.snapcast_sheet_no_members);
            none.setPadding(pad, dp(8), pad, dp(8));
            body.addView(none);
        }
        for (SnapcastMemberRows.Member member : members) {
            body.addView(memberRow(member, online));
        }
    }

    /** The name of the group: its own, else its first member's, else the label. Cleaned display text. */
    private String groupTitle(SnapcastGroup group) {
        String name = SnapcastDisplayText.of(group.getName(), 64).trim();
        if (name.isEmpty() && !group.getClients().isEmpty()) {
            SnapcastClient first = group.getClients().get(0);
            name = SnapcastDisplayText.of(first.getName(), 64).trim();
            if (name.isEmpty()) {
                name = SnapcastDisplayText.of(first.getHostName(), 64).trim();
            }
        }
        return name.isEmpty() ? context.getString(R.string.snapcast_group) : name;
    }

    private String streamLabel(SnapcastGroup group, SnapcastReceivers.GroupState state) {
        if (group.getStreamId().isEmpty()) {
            return context.getString(R.string.snapcast_row_no_stream);
        }
        for (SnapcastStreamChoices.Choice choice : SnapcastStreamChoices.of(state.getStatus(), groupId, marker)) {
            if (choice.isCurrent()) {
                return choiceText(choice, false);
            }
        }
        return SnapcastDisplayText.of(group.getStreamId(), 64); // the stream is not in the server's list any more
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(12);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setPadding(dp(16), dp(16), dp(16), dp(4));
        return label;
    }

    /** A row with a title and a value that opens a picker when tapped. */
    private View valueRow(String titleText, String value, boolean enabled, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.setClickable(enabled);
        row.setFocusable(enabled);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.5f);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        TextView heading = new TextView(context);
        heading.setText(titleText);
        heading.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        row.addView(heading);
        if (value != null) {
            TextView text = new TextView(context);
            text.setText(value);
            text.setTextSize(12);
            row.addView(text);
        }
        row.setOnClickListener(enabled ? onClick : null);
        return row;
    }

    private interface SliderListener {
        void onStart();

        void onChange(int percent);
    }

    /** A label with its percentage and a slider under it; {@code percent < 0} shows the slider disabled. */
    private View sliderRow(String label, int percent, boolean enabled, final SliderListener listener, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = new TextView(context);
        value.setText(percent < 0 ? "" : percent + "%");
        header.addView(value);
        if (trailing != null) {
            header.addView(trailing);
        }
        row.addView(header);
        SeekBar slider = new SeekBar(context);
        slider.setMax(100);
        slider.setProgress(Math.max(0, percent));
        slider.setEnabled(enabled && percent >= 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    value.setText(progress + "%");
                    listener.onChange(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                touching = true;
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
                listener.onStart();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                touching = false;
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                if (renderWanted) {
                    renderWanted = false;
                    scheduleRender();
                }
            }
        });
        row.addView(slider);
        return row;
    }

    private View memberRow(final SnapcastMemberRows.Member member, boolean online) {
        final String clientId = member.getClientId();
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        final CheckBox mute = SnapcastMuteToggle.create(context, context.getString(R.string.snapcast_member_mute, member.getName()));
        mute.setChecked(member.isMuted());
        SnapcastMuteToggle.syncState(context, mute);
        mute.setEnabled(online);
        mute.setOnClickListener(v -> {
            SnapcastMuteToggle.syncState(context, mute);
            controls.setClientMuted(serverKey, clientId, mute.isChecked());
        });
        actions.addView(mute);
        ImageButton more = new ImageButton(context);
        more.setImageResource(R.drawable.ic_snapcast_more);
        more.setContentDescription(context.getString(R.string.snapcast_member_actions, member.getName()));
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            more.setBackgroundResource(ripple.resourceId);
        }
        more.setMinimumWidth(dp(48));
        more.setMinimumHeight(dp(48));
        more.setEnabled(online);
        more.setOnClickListener(v -> showMemberMenu(v, member));
        actions.addView(more);
        String label = member.isConnected() ? member.getName()
                : member.getName() + " (" + context.getString(R.string.snapcast_offline) + ")";
        return sliderRow(label, member.getVolume(), online && member.isConnected(), new SliderListener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onChange(int percent) {
                controls.setClientVolume(serverKey, clientId, percent);
            }
        }, actions);
    }

    // ---- the member menu and its dialogs

    private void showMemberMenu(View anchor, final SnapcastMemberRows.Member member) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(0, 1, 0, R.string.snapcast_member_rename);
        menu.getMenu().add(0, 2, 1, R.string.snapcast_member_latency);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showRename(member);
                    return true;
                case 2:
                    showLatency(member);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private EditText inputField(String hint, int inputType, int maxLength, String initial) {
        EditText input = new EditText(context);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setText(initial);
        input.setSelection(input.getText().length());
        return input;
    }

    private LinearLayout padded(View view) {
        LinearLayout box = new LinearLayout(context);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void showRename(SnapcastMemberRows.Member member) {
        SnapcastReceivers.GroupState state = receivers.stateOf(receiverId);
        SnapcastClient client = state == null || state.getGroup() == null ? null : state.getGroup().findClient(member.getClientId());
        String current = client == null ? "" : SnapcastInputs.normalizeName(client.getName());
        final EditText input = inputField(context.getString(R.string.snapcast_rename_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, SnapcastInputs.MAX_NAME, current);
        final String clientId = member.getClientId();
        openDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.snapcast_rename_title)
                .setView(padded(input))
                .setPositiveButton(android.R.string.ok, (d, which) ->
                        controls.renameClient(serverKey, clientId, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLatency(SnapcastMemberRows.Member member) {
        final EditText input = inputField(context.getString(R.string.snapcast_latency_hint), InputType.TYPE_CLASS_NUMBER, 5,
                String.valueOf(member.getLatencyMs()));
        final String clientId = member.getClientId();
        final AlertDialog latency = new AlertDialog.Builder(context)
                .setTitle(R.string.snapcast_latency_title)
                .setView(padded(input))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // the dialog stays open while the number is wrong
        latency.setOnShowListener(d -> latency.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            SnapcastInputs.Latency parsed = SnapcastInputs.parseLatency(input.getText().toString());
            if (!parsed.isValid()) {
                input.setError(context.getString(R.string.snapcast_latency_error));
                return;
            }
            controls.setClientLatency(serverKey, clientId, parsed.getValue());
            latency.dismiss();
        }));
        openDialog = latency;
        latency.show();
    }

    // ---- the pickers

    private String choiceText(SnapcastStreamChoices.Choice choice, boolean withCurrent) {
        String text = choice.getLabel();
        if (choice.isOwnFeed()) {
            text = context.getString(R.string.snapcast_sheet_stream_own_feed, text);
        }
        if (choice.getReason() == SnapcastStreamChoices.Reason.UNAVAILABLE) {
            text = context.getString(R.string.snapcast_sheet_stream_unavailable, text);
        }
        if (withCurrent && choice.isCurrent()) {
            text = context.getString(R.string.snapcast_sheet_stream_current, text);
        }
        return text;
    }

    private void showStreamPicker() {
        SnapcastReceivers.GroupState state = receivers.stateOf(receiverId);
        if (state == null || state.getStatus() == null) {
            return;
        }
        final List<SnapcastStreamChoices.Choice> choices = SnapcastStreamChoices.of(state.getStatus(), groupId, marker);
        if (choices.isEmpty()) {
            openDialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.snapcast_sheet_stream_title)
                    .setMessage(R.string.snapcast_sheet_no_streams)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        int current = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choiceText(choices.get(i), true);
            if (choices.get(i).isCurrent()) {
                current = i;
            }
        }
        openDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.snapcast_sheet_stream_title)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    SnapcastStreamChoices.Choice choice = choices.get(which);
                    d.dismiss();
                    if (choice.isSelectable()) {
                        controls.setGroupStream(serverKey, groupId, choice.getStreamId());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
