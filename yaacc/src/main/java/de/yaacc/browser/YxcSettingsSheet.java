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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;
import de.yaacc.yxc.YxcClient;
import de.yaacc.yxc.YxcException;
import de.yaacc.yxc.YxcMonitor;
import de.yaacc.yxc.YxcNameText;
import de.yaacc.yxc.YxcSettings;
import de.yaacc.yxc.YxcSettingsWriter;
import de.yaacc.yxc.YxcZoneFeatures;
import de.yaacc.yxc.YxcZoneStatus;

/**
 * The settings of a device that speaks the group protocol, in a bottom sheet like the pickers. What it offers comes from
 * the device ({@link YxcSettings}); a change is checked against it ({@link YxcSettingsWriter}) and sent on a worker thread,
 * then the status is read again so the sheet shows what the device really did. Answers that come after the sheet was
 * closed are dropped.
 */
final class YxcSettingsSheet {
    private static final String ZONE = "main";
    /** A device needs a moment to change its power and its input before the status shows it. */
    private static final long SETTLE_MILLIS = 800L;

    private final Context context;
    private final String udn;
    private final BottomSheetDialog dialog;
    private final LinearLayout body;
    private final TextView message;
    // the thread ends by itself when idle, also when the host went away with the sheet open
    private final ExecutorService worker = newWorker();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<YxcSettings.Item> items = new ArrayList<>();

    private static ExecutorService newWorker() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 5,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "yxc-settings");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private YxcSettingsSheet(Context context, String udn, String deviceName) {
        this.context = context;
        this.udn = udn;
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_App_DeviceSheet);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (8 * density), 0, pad);
        TextView title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        title.setText(deviceName == null ? context.getString(R.string.yxc_action_settings) : deviceName);
        title.setPadding(pad, 0, pad, (int) (4 * density));
        root.addView(title);
        message = new TextView(context);
        message.setPadding(pad, 0, pad, (int) (8 * density));
        message.setText(R.string.yxc_settings_loading);
        root.addView(message);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        NestedScrollView scroll = new NestedScrollView(context); // nested scrolling, so the sheet does not take the drag
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

    /** Opens the sheet for the device (UDN as the monitor knows it) and loads its settings. */
    static Dialog show(Context context, String udn, String deviceName) {
        YxcSettingsSheet sheet = new YxcSettingsSheet(context, udn, deviceName);
        sheet.dialog.show();
        BottomSheetBehavior<android.widget.FrameLayout> behavior = sheet.dialog.getBehavior();
        behavior.setFitToContents(true);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.load(0);
        return sheet.dialog;
    }

    // ---- reading

    private void load(long delayMillis) {
        run(false, () -> {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            YxcClient client = YxcMonitor.getShared().newClient(udn);
            if (client == null) {
                throw new java.io.IOException("device not available");
            }
            YxcZoneFeatures features = client.getFeatures().getZone(ZONE);
            YxcZoneStatus status = client.getZoneStatus(ZONE);
            YxcNameText names;
            try {
                names = client.getNameText();
            } catch (YxcException e) {
                names = YxcNameText.empty(); // the ids made readable are used
            }
            List<YxcSettings.Item> built = YxcSettings.build(features, status, names);
            post(() -> render(built));
        });
    }

    private void render(List<YxcSettings.Item> built) {
        items = built;
        body.removeAllViews();
        message.setVisibility(View.GONE);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        for (YxcSettings.Item item : built) {
            switch (item.getId()) {
                case POWER:
                    body.addView(powerSwitch(item, pad));
                    break;
                case INPUT:
                    body.addView(choiceRow(item, R.string.yxc_setting_input, pad, density));
                    break;
                case SOUND_PROGRAM:
                    body.addView(choiceRow(item, R.string.yxc_setting_sound_program, pad, density));
                    break;
                case SLEEP:
                    body.addView(choiceRow(item, R.string.yxc_setting_sleep, pad, density));
                    break;
                case EQUALIZER_MODE:
                    body.addView(choiceRow(item, R.string.yxc_setting_equalizer, pad, density));
                    break;
                case EQUALIZER_LOW:
                    body.addView(bandRow(item, R.string.yxc_setting_eq_low, pad, density));
                    break;
                case EQUALIZER_MID:
                    body.addView(bandRow(item, R.string.yxc_setting_eq_mid, pad, density));
                    break;
                case EQUALIZER_HIGH:
                    body.addView(bandRow(item, R.string.yxc_setting_eq_high, pad, density));
                    break;
                case LINK_CONTROL:
                    body.addView(choiceRow(item, R.string.yxc_setting_link_control, pad, density));
                    break;
                case LINK_AUDIO_DELAY:
                    body.addView(choiceRow(item, R.string.yxc_setting_link_audio_delay, pad, density));
                    break;
                case LINK_AUDIO_QUALITY:
                    body.addView(choiceRow(item, R.string.yxc_setting_link_audio_quality, pad, density));
                    break;
                default:
                    break;
            }
        }
    }

    private View powerSwitch(YxcSettings.Item item, int pad) {
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(R.string.yxc_setting_power);
        toggle.setMinHeight((int) (56 * context.getResources().getDisplayMetrics().density));
        toggle.setPadding(pad, 0, pad, 0);
        toggle.setChecked(item.isOn());
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            setBusy(true);
            run(true, () -> {
                writer().setPower(items, checked);
                load(SETTLE_MILLIS);
            });
        });
        return toggle;
    }

    private View choiceRow(YxcSettings.Item item, int titleResource, int pad, float density) {
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
        String label = optionLabel(item, item.getCurrent());
        value.setText(label == null ? "-" : label);
        row.addView(value);
        row.setOnClickListener(v -> chooseFrom(item, titleResource));
        return row;
    }

    private void chooseFrom(YxcSettings.Item item, int titleResource) {
        List<YxcSettings.Option> options = item.getOptions();
        CharSequence[] labels = new CharSequence[options.size()];
        int selected = -1;
        for (int i = 0; i < labels.length; i++) {
            // linear: the label is on the option (only the five sleep values are worded here)
            labels[i] = item.getId() == YxcSettings.Id.SLEEP ? optionLabel(item, options.get(i).getValue()) : options.get(i).getLabel();
            if (options.get(i).getValue().equals(item.getCurrent())) {
                selected = i;
            }
        }
        picker = new AlertDialog.Builder(context)
                .setTitle(titleResource)
                .setSingleChoiceItems(labels, selected, (picker, which) -> {
                    picker.dismiss();
                    String value = options.get(which).getValue();
                    if (value.equals(item.getCurrent())) {
                        return;
                    }
                    setBusy(true);
                    run(true, () -> {
                        YxcSettingsWriter writer = writer();
                        switch (item.getId()) {
                            case INPUT:
                                writer.setInput(items, value);
                                break;
                            case SOUND_PROGRAM:
                                writer.setSoundProgram(items, value);
                                break;
                            case SLEEP:
                                writer.setSleep(items, Integer.parseInt(value));
                                break;
                            case EQUALIZER_MODE:
                                writer.setEqualizerMode(items, value);
                                break;
                            case LINK_CONTROL:
                                writer.setLinkControl(items, value);
                                break;
                            case LINK_AUDIO_DELAY:
                                writer.setLinkAudioDelay(items, value);
                                break;
                            case LINK_AUDIO_QUALITY:
                                writer.setLinkAudioQuality(items, value);
                                break;
                            default:
                                throw new IllegalArgumentException("not a choice");
                        }
                        load(SETTLE_MILLIS);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // the open choice dialog, closed together with the sheet
    private AlertDialog picker;

    /** The label of a choice; the sleep timer values are worded here ("Off", "30 min"), the others come from the device. */
    private String optionLabel(YxcSettings.Item item, String value) {
        if (value == null) {
            return null;
        }
        if (item.getId() == YxcSettings.Id.SLEEP) {
            try {
                int minutes = Integer.parseInt(value);
                return minutes == 0 ? context.getString(R.string.yxc_sleep_off) : context.getString(R.string.yxc_sleep_minutes, minutes);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (YxcSettings.Option option : item.getOptions()) {
            if (option.getValue().equals(value)) {
                return option.getLabel();
            }
        }
        return item.getCurrentLabel();
    }

    /** One band of the equalizer: a slider over the range the device reports; the value is sent when the finger is lifted. */
    private View bandRow(YxcSettings.Item item, int titleResource, int pad, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(pad, (int) (6 * density), pad, (int) (6 * density));
        TextView title = new TextView(context);
        title.setTextSize(12);
        title.setText(context.getString(titleResource) + "  " + item.getNumber());
        row.addView(title);
        android.widget.SeekBar slider = new android.widget.SeekBar(context);
        slider.setMax(item.getSteps());
        slider.setProgress((int) (((long) item.getNumber() - item.getMin()) / item.getStep()));
        slider.setEnabled(item.isEnabled());
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                title.setText(context.getString(titleResource) + "  " + item.valueAt(progress));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                int value = item.valueAt(seekBar.getProgress());
                if (value == item.getNumber()) {
                    return; // nothing changed
                }
                setBusy(true);
                run(true, () -> {
                    writer().setEqualizerBand(items, item.getId(), value);
                    load(SETTLE_MILLIS / 2);
                });
            }
        });
        row.addView(slider);
        return row;
    }

    // ---- plumbing

    private YxcSettingsWriter writer() throws java.io.IOException {
        YxcClient client = YxcMonitor.getShared().newClient(udn);
        if (client == null) {
            throw new java.io.IOException("device not available");
        }
        return new YxcSettingsWriter(client, ZONE);
    }

    private interface Job {
        void run() throws Exception;
    }

    /**
     * Runs the job on the worker. A failure is told once; then the sheet shows what the device really says: after a
     * failed change the status is read again ({@code reloadOnFailure}), after a failed read the sheet goes back to the last
     * items (a read that fails is not repeated, so an unreachable device does not make a loop).
     */
    private void run(boolean reloadOnFailure, Job job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    YaaccLogger.d(YxcSettingsSheet.class.getName(), "Settings failed: " + e.getClass().getSimpleName());
                    post(() -> {
                        Toast.makeText(context, R.string.yxc_settings_failed, Toast.LENGTH_LONG).show();
                        if (reloadOnFailure) {
                            load(0);
                        } else if (!items.isEmpty()) {
                            render(items); // the last known state, every control as it was (also the disabled ones)
                        } else {
                            message.setText(R.string.yxc_settings_failed);
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
