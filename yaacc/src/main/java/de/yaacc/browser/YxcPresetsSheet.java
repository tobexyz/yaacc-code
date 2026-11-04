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

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;
import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcDeviceState;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.yxc.YxcPresetActions;
import de.yaacc.yxc.YxcPresets;

/**
 * The presets of a device, in a bottom sheet like the other sheets: the recently played radio stations and streaming
 * entries, the presets of the network source and the FM and DAB presets of the tuner, whatever the device lists. One tap
 * recalls an entry on a worker thread ({@link YxcPresetActions} reads the list again first) and closes the sheet; the row
 * follows through the monitor. Answers that come after the sheet was closed are dropped.
 */
final class YxcPresetsSheet {
    private static final long LATER_READ_MILLIS = 1500L;

    private final Context context;
    private final String udn;
    private final BottomSheetDialog dialog;
    private final LinearLayout body;
    private final TextView message;
    private final ExecutorService worker = newWorker();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static ExecutorService newWorker() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 5,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "yxc-presets");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private YxcPresetsSheet(Context context, String udn, String deviceName) {
        this.context = context;
        this.udn = udn;
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (8 * density), 0, pad);
        TextView title = new TextView(context);
        // the name of the device is the most important text of the sheet
        title.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setText(deviceName == null ? context.getString(R.string.yxc_action_presets) : deviceName);
        title.setPadding(pad, (int) (4 * density), pad, (int) (8 * density));
        root.addView(title);
        message = new TextView(context);
        message.setPadding(pad, 0, pad, (int) (8 * density));
        message.setText(R.string.yxc_settings_loading);
        root.addView(message);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(context);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> worker.shutdownNow());
    }

    /** Opens the sheet for the device (UDN as the monitor knows it) and loads its lists. */
    static Dialog show(Context context, String udn, String deviceName) {
        YxcPresetsSheet sheet = new YxcPresetsSheet(context, udn, deviceName);
        sheet.dialog.show();
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.load();
        return sheet.dialog;
    }

    private void load() {
        message.setText(R.string.yxc_settings_loading);
        message.setVisibility(View.VISIBLE);
        execute(() -> {
            YxcClient client = YxcMonitor.getShared().newClient(udn);
            YxcDeviceState state = YxcMonitor.getShared().getState(udn);
            if (client == null || state == null) {
                post(() -> showMessage(R.string.yxc_presets_failed));
                return;
            }
            YxcPresetActions.Lists lists = YxcPresetActions.load(client, state.getCapabilities());
            post(() -> render(lists));
        });
    }

    private void render(YxcPresetActions.Lists lists) {
        body.removeAllViews();
        if (lists.isFailed()) {
            showMessage(R.string.yxc_presets_failed);
            return;
        }
        if (lists.isEmpty()) {
            showMessage(R.string.yxc_presets_empty);
            return;
        }
        message.setVisibility(View.GONE);
        firstSection = true;
        section(R.string.yxc_presets_recent, lists.recent());
        section(R.string.yxc_presets_net, lists.net());
        section(R.string.yxc_presets_fm, lists.fm());
        section(R.string.yxc_presets_dab, lists.dab());
    }

    private void showMessage(int text) {
        body.removeAllViews();
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private boolean firstSection = true;

    private void section(int header, List<YxcPresets.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        if (!firstSection) {
            // a thin line between the groups
            View divider = new View(context);
            android.util.TypedValue line = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.listDivider, line, true) && line.resourceId != 0) {
                divider.setBackgroundResource(line.resourceId);
            }
            body.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, (int) density)));
        }
        firstSection = false;
        // the headline is the bigger and bold text; the entries below it are a step smaller and indented
        TextView title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setText(header);
        title.setPadding(pad, (int) (14 * density), pad, (int) (6 * density));
        body.addView(title);
        for (YxcPresets.Entry entry : entries) {
            body.addView(row(entry, pad, density));
        }
    }

    private View row(YxcPresets.Entry entry, int pad, float density) {
        TextView row = new TextView(context);
        row.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        row.setMinHeight((int) (48 * density));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(pad + (int) (12 * density), 0, pad, 0);
        row.setText(entry.getDetail() == null ? entry.getLabel() : entry.getLabel() + " · " + entry.getDetail());
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        row.setOnClickListener(v -> recall(entry));
        return row;
    }

    private void recall(YxcPresets.Entry entry) {
        setBusy(true);
        execute(() -> {
            YxcClient client = YxcMonitor.getShared().newClient(udn);
            YxcPresetActions.Result result = client == null ? YxcPresetActions.Result.FAILED
                    : YxcPresetActions.recall(client, entry);
            post(() -> {
                switch (result) {
                    case DONE:
                        YxcMonitor monitor = YxcMonitor.getShared();
                        monitor.refreshNow(udn);
                        main.postDelayed(() -> monitor.refreshNow(udn), LATER_READ_MILLIS);
                        dialog.dismiss();
                        break;
                    case NOT_AVAILABLE:
                        Toast.makeText(context, R.string.yxc_presets_changed, Toast.LENGTH_LONG).show();
                        load();
                        break;
                    default:
                        Toast.makeText(context, R.string.yxc_presets_recall_failed, Toast.LENGTH_LONG).show();
                        setBusy(false);
                        break;
                }
            });
        });
    }

    private void execute(Runnable job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    YaaccLogger.d(YxcPresetsSheet.class.getName(), "Presets failed: " + e.getClass().getSimpleName());
                    post(() -> showMessage(R.string.yxc_presets_failed));
                }
            });
        } catch (RejectedExecutionException e) {
            // the sheet was closed
        }
    }

    private void post(Runnable action) {
        main.post(() -> {
            if (dialog.isShowing()) {
                action.run();
            }
        });
    }

    private void setBusy(boolean busy) {
        for (int i = 0; i < body.getChildCount(); i++) {
            body.getChildAt(i).setEnabled(!busy);
            body.getChildAt(i).setAlpha(busy ? 0.5f : 1f);
        }
    }
}
