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
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.yaacc.R;
import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;
import de.yaacc.openhome.OhSources;
import de.yaacc.player.OpenHomeChoice;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.util.YaaccLogger;

/**
 * The settings of an OpenHome device in a bottom sheet, laid out like the settings of a MusicCast device: power (standby), the
 * source (Playlist, Radio, inputs ...) and how the app plays on the device (OpenHome or AVTransport). The source is only
 * switched; what the device then plays is not controlled from here. What the device offers is read when the sheet opens, a change
 * is sent on a worker thread and the device is read again. Answers that come after the sheet was closed are dropped.
 */
final class OpenHomeSettingsSheet {
    private static final int MAX_NAME_LENGTH = 60;
    /** A device needs a moment to wake or switch its source before it says so. */
    private static final long SETTLE_MILLIS = 800L;

    private final Context context;
    private final UpnpClient upnpClient;
    private final Device<?, ?, ?> device;
    private final String udn;
    private final BottomSheetDialog dialog;
    private final LinearLayout body;
    private final TextView message;
    private final ExecutorService worker = newWorker();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog picker;

    /** What the device said when the sheet last read it. */
    private static final class Snapshot {
        Boolean standby; // null: the device does not say
        List<Choice> sources = new ArrayList<>();
        int currentSource = -1;
    }

    private interface Job {
        void run() throws Exception;
    }

    /** One entry of the list of sources: the index to select it with and the (cleaned) text to show. */
    static final class Choice {
        final int index;
        final String label;

        Choice(int index, String label) {
            this.index = index;
            this.label = label;
        }
    }

    /** The visible sources as choices. The names come from the network: cleaned and shortened. */
    static List<Choice> choices(List<OhSources.Source> sources) {
        List<Choice> choices = new ArrayList<>();
        for (OhSources.Source source : sources) {
            if (!source.visible) {
                continue;
            }
            String label = MiniPlayerPresenter.cleanText(source.name);
            if (label.isEmpty()) {
                label = MiniPlayerPresenter.cleanText(source.type);
            }
            if (label.isEmpty()) {
                label = "#" + (source.index + 1);
            }
            if (label.length() > MAX_NAME_LENGTH) {
                label = label.substring(0, MAX_NAME_LENGTH);
            }
            choices.add(new Choice(source.index, label));
        }
        return choices;
    }

    private static ExecutorService newWorker() {
        // the thread ends by itself when idle, also when the host went away with the sheet open
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "openhome-settings");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private OpenHomeSettingsSheet(Context context, UpnpClient upnpClient, Device<?, ?, ?> device) {
        this.context = context;
        this.upnpClient = upnpClient;
        this.device = device;
        this.udn = device.getIdentity().getUdn().getIdentifierString();
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (8 * density), 0, pad);
        TextView title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        String name = device.getDetails() == null ? "" : MiniPlayerPresenter.cleanText(device.getDetails().getFriendlyName());
        title.setText(name.isEmpty() ? context.getString(R.string.openhome_action_settings) : name);
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
        dialog.setOnDismissListener(d -> {
            worker.shutdownNow();
            if (picker != null && picker.isShowing()) {
                picker.dismiss();
            }
        });
    }

    /** Opens the sheet for the device and reads what it offers. */
    static Dialog show(Context context, UpnpClient upnpClient, Device<?, ?, ?> device) {
        OpenHomeSettingsSheet sheet = new OpenHomeSettingsSheet(context, upnpClient, device);
        sheet.dialog.show();
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.load(0);
        return sheet.dialog;
    }

    private OhRemote remote() {
        return new OhRemote(new ClingOhControl(device, upnpClient.getHttpRequestSender()));
    }

    // ---- reading

    private void load(long delayMillis) {
        run(false, () -> {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            OhRemote remote = remote();
            Snapshot snapshot = new Snapshot();
            snapshot.sources = choices(remote.sources());
            snapshot.currentSource = remote.currentSourceIndex();
            try {
                snapshot.standby = remote.standby();
            } catch (OhControlException e) {
                if (e.getCode() == OhControlException.NO_ANSWER) {
                    throw e;
                }
                // a device that refuses the question has no standby to show
            }
            post(() -> render(snapshot));
        });
    }

    private void render(Snapshot snapshot) {
        body.removeAllViews();
        message.setVisibility(View.GONE);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        if (snapshot.standby != null) {
            body.addView(powerSwitch(!snapshot.standby, pad));
        }
        body.addView(sourceRow(snapshot, pad, density));
        body.addView(protocolRow(pad, density));
    }

    // ---- the rows

    private View powerSwitch(boolean on, int pad) {
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(R.string.yxc_setting_power);
        toggle.setMinHeight((int) (56 * context.getResources().getDisplayMetrics().density));
        toggle.setPadding(pad, 0, pad, 0);
        toggle.setChecked(on);
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            setBusy(true);
            run(true, () -> {
                remote().setStandby(!checked);
                load(SETTLE_MILLIS);
            });
        });
        return toggle;
    }

    private View sourceRow(Snapshot snapshot, int pad, float density) {
        String current = null;
        for (Choice choice : snapshot.sources) {
            if (choice.index == snapshot.currentSource) {
                current = choice.label;
            }
        }
        return row(R.string.openhome_source_title, current == null ? "-" : current, pad, density, () -> chooseSource(snapshot));
    }

    private void chooseSource(Snapshot snapshot) {
        if (snapshot.sources.isEmpty()) {
            Toast.makeText(context, R.string.openhome_source_none, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[snapshot.sources.size()];
        int selected = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = snapshot.sources.get(i).label;
            if (snapshot.sources.get(i).index == snapshot.currentSource) {
                selected = i;
            }
        }
        picker = new AlertDialog.Builder(context)
                .setTitle(R.string.openhome_source_title)
                .setSingleChoiceItems(labels, selected, (picker, which) -> {
                    picker.dismiss();
                    int index = snapshot.sources.get(which).index;
                    if (index == snapshot.currentSource) {
                        return;
                    }
                    setBusy(true);
                    run(true, () -> {
                        remote().selectSource(index);
                        load(SETTLE_MILLIS);
                    });
                })
                .show();
    }

    private View protocolRow(int pad, float density) {
        boolean avTransport = OpenHomeChoice.choseAvTransport(context, udn);
        return row(R.string.openhome_protocol_title, context.getString(avTransport
                ? R.string.openhome_protocol_avtransport : R.string.openhome_protocol_openhome), pad, density, this::chooseProtocol);
    }

    private void chooseProtocol() {
        int checked = OpenHomeChoice.choseAvTransport(context, udn) ? 1 : 0;
        String[] choices = {context.getString(R.string.openhome_protocol_openhome),
                context.getString(R.string.openhome_protocol_avtransport)};
        picker = new AlertDialog.Builder(context)
                .setTitle(R.string.openhome_protocol_title)
                .setSingleChoiceItems(choices, checked, (picker, which) -> {
                    picker.dismiss();
                    OpenHomeChoice.setUseAvTransport(context, udn, which == 1);
                    load(0); // shows the new choice
                })
                .show();
    }

    /** A row like the choice rows of the MusicCast settings: a small title, the current value under it, a tap changes it. */
    private View row(int titleResource, String valueText, int pad, float density, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight((int) (56 * density));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(pad, (int) (6 * density), pad, (int) (6 * density));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        TextView title = new TextView(context);
        title.setText(titleResource);
        title.setTextSize(12);
        row.addView(title);
        TextView value = new TextView(context);
        value.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        value.setText(valueText); // plain text: the names come from the network
        row.addView(value);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    // ---- running

    private void run(boolean reloadOnFailure, Job job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    YaaccLogger.d(OpenHomeSettingsSheet.class.getName(), "OpenHome settings failed: " + e.getClass().getSimpleName());
                    post(() -> {
                        Toast.makeText(context, R.string.openhome_source_failed, Toast.LENGTH_LONG).show();
                        if (reloadOnFailure) {
                            load(0);
                        } else {
                            message.setText(R.string.openhome_source_failed);
                            message.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException closed) {
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
            setEnabledDeep(body.getChildAt(i), !busy);
            body.getChildAt(i).setAlpha(busy ? 0.5f : 1f);
        }
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeep(group.getChildAt(i), enabled);
            }
        }
    }
}
