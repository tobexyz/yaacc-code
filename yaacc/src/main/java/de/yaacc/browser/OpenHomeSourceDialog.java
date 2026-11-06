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
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;
import de.yaacc.openhome.OhControlException;
import de.yaacc.openhome.OhRemote;
import de.yaacc.openhome.OhSources;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;
import de.yaacc.util.YaaccLogger;

/**
 * Lists the sources of an OpenHome device (Playlist, Radio, inputs, ...) and switches the device to the one the user picks.
 * Nothing more is done with a source other than the playlist: the device plays what it likes, the app only switches it on.
 */
final class OpenHomeSourceDialog {
    private static final int MAX_NAME_LENGTH = 60;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhome-sources");
        thread.setDaemon(true);
        return thread;
    });

    private OpenHomeSourceDialog() {
    }

    /** One entry of the list: the index to select it with and the (cleaned) text to show. */
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

    static void show(Context context, UpnpClient upnpClient, Device<?, ?, ?> device) {
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            try {
                OhRemote remote = new OhRemote(new ClingOhControl(device, upnpClient.getHttpRequestSender()));
                List<Choice> choices = choices(remote.sources());
                int current = remote.currentSourceIndex();
                main.post(() -> showChoices(context, upnpClient, device, choices, current, main));
            } catch (OhControlException | RuntimeException e) {
                YaaccLogger.w(OpenHomeSourceDialog.class.getName(), "OpenHome sources not readable: " + e.getMessage());
                main.post(() -> Toast.makeText(context, R.string.openhome_source_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void showChoices(Context context, UpnpClient upnpClient, Device<?, ?, ?> device, List<Choice> choices, int current,
                                    Handler main) {
        if (choices.isEmpty()) {
            Toast.makeText(context, R.string.openhome_source_none, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[choices.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choices.get(i).label;
            if (choices.get(i).index == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.openhome_source_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    int index = choices.get(which).index;
                    WORKER.execute(() -> {
                        try {
                            new OhRemote(new ClingOhControl(device, upnpClient.getHttpRequestSender())).selectSource(index);
                        } catch (OhControlException | RuntimeException e) {
                            YaaccLogger.w(OpenHomeSourceDialog.class.getName(), "OpenHome source not selected: " + e.getMessage());
                            main.post(() -> Toast.makeText(context, R.string.openhome_source_failed, Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
    }
}
