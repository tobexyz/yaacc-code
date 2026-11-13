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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;

/**
 * The picker of the devices a server can take as clients. It looks like the receiver picker ("Play on ..."): a bottom
 * sheet with a title, the name of the server under it, one check box per device and OK / Cancel below. Devices that
 * can not be picked (in another group, not reachable) are listed disabled with the reason; a member among them stays
 * in the group.
 */
final class YxcLinkSheet {

    /** One line of the sheet. */
    static final class Row {
        final String udn;
        final String text;
        final boolean checked;
        final boolean enabled;

        Row(String udn, String text, boolean checked, boolean enabled) {
            this.udn = udn;
            this.text = text;
            this.checked = checked;
            this.enabled = enabled;
        }
    }

    /** Told when OK was pressed, with the devices that are to be the clients (kept members included). */
    interface OnConfirm {
        void onConfirm(List<String> clientUdns);
    }

    /**
     * The words and the rule of a sheet that is not the MusicCast one (the Snapcast group sheet uses the same look): its title,
     * the hint under it, the hint when nothing can be changed, and how many rows must stay checked for OK to work.
     */
    static final class Options {
        final int titleRes;
        final int hintRes;
        final int noneHintRes;
        final int minimumChecked;
        final int subtitleMaxLines;

        /**
         * @param hintRes        shown under the title while something can be changed; 0 for none
         * @param minimumChecked OK is enabled only while at least this many rows are checked (disabled rows count)
         * @param subtitleMaxLines how many lines the subtitle may take (the layout allows two)
         */
        Options(int titleRes, int hintRes, int noneHintRes, int minimumChecked, int subtitleMaxLines) {
            this.titleRes = titleRes;
            this.hintRes = hintRes;
            this.noneHintRes = noneHintRes;
            this.minimumChecked = minimumChecked;
            this.subtitleMaxLines = subtitleMaxLines;
        }
    }

    private YxcLinkSheet() {
    }

    static BottomSheetDialog show(Context context, String serverName, List<Row> rows, OnConfirm onConfirm) {
        return show(context, serverName, rows, onConfirm, null);
    }

    /** @param options null for the MusicCast sheet */
    static BottomSheetDialog show(Context context, String serverName, List<Row> rows, OnConfirm onConfirm, Options options) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        View view = LayoutInflater.from(dialog.getContext()).inflate(R.layout.fragment_device_sheet_pick, null, false);
        TextView subtitle = view.findViewById(R.id.deviceSheetPickSubtitle);
        if (serverName != null) {
            subtitle.setText(serverName);
            subtitle.setVisibility(View.VISIBLE);
            if (options != null) {
                subtitle.setMaxLines(options.subtitleMaxLines);
            }
        }
        List<Boolean> checked = new ArrayList<>();
        boolean anyEnabled = false;
        for (Row row : rows) {
            checked.add(row.checked);
            anyEnabled = anyEnabled || row.enabled;
        }
        RecyclerView list = view.findViewById(R.id.deviceSheetList);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        final View confirm = view.findViewById(R.id.deviceSheetPickConfirm);
        final int minimumChecked = options == null ? 0 : options.minimumChecked;
        final Runnable refreshConfirm = () -> {
            int count = 0;
            for (Boolean isChecked : checked) {
                count += isChecked ? 1 : 0;
            }
            boolean allowed = count >= minimumChecked;
            confirm.setEnabled(allowed);
            confirm.setAlpha(allowed ? 1f : 0.5f);
        };
        list.setAdapter(new RowAdapter(rows, checked, refreshConfirm));
        refreshConfirm.run();
        boolean serves = false;
        for (Row row : rows) {
            serves = serves || row.checked;
        }
        TextView hint = view.findViewById(R.id.deviceSheetPickHint);
        if (options != null) {
            ((TextView) view.findViewById(R.id.deviceSheetTitle)).setText(options.titleRes);
            if (options.hintRes != 0) {
                hint.setText(options.hintRes);
                hint.setVisibility(View.VISIBLE);
            }
        } else {
            // a device that serves a group edits it; a free one links
            ((TextView) view.findViewById(R.id.deviceSheetTitle)).setText(serves ? R.string.yxc_action_edit_group : R.string.yxc_action_link);
            if (serves) {
                // the device serves a group: unchecking everybody ends it
                hint.setText(R.string.yxc_picker_end_hint);
                hint.setVisibility(View.VISIBLE);
            }
        }
        if (rows.isEmpty() || !anyEnabled) {
            hint.setText(options != null && options.noneHintRes != 0 ? options.noneHintRes : R.string.yxc_link_none);
            hint.setVisibility(View.VISIBLE);
            confirm.setVisibility(View.GONE);
        }
        confirm.setOnClickListener(v -> {
            List<String> selection = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (checked.get(i)) {
                    selection.add(rows.get(i).udn); // a disabled member stays checked: it stays in the group
                }
            }
            dialog.dismiss();
            onConfirm.onConfirm(selection);
        });
        view.findViewById(R.id.deviceSheetPickCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(view);
        dialog.show();
        BottomSheetBehavior<FrameLayout> behavior = dialog.getBehavior();
        behavior.setFitToContents(true); // as high as its content, like the receiver picker
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }

    private static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        private final List<Row> rows;
        private final List<Boolean> checked;
        private final Runnable onChanged;

        RowAdapter(List<Row> rows, List<Boolean> checked, Runnable onChanged) {
            this.rows = rows;
            this.checked = checked;
            this.onChanged = onChanged;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            CheckBox box = (CheckBox) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.device_sheet_pick_item, parent, false);
            Holder holder = new Holder(box);
            box.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && rows.get(position).enabled) {
                    checked.set(position, box.isChecked());
                    onChanged.run();
                }
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            holder.box.setText(row.text);
            holder.box.setChecked(checked.get(position));
            holder.box.setEnabled(row.enabled);
            holder.box.setAlpha(row.enabled ? 1f : 0.5f);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final CheckBox box;

            Holder(@NonNull CheckBox itemView) {
                super(itemView);
                box = itemView;
            }
        }
    }
}
