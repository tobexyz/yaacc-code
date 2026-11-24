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

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.sendspin.session.ControllerMessages;

/**
 * The commands of a Sendspin server's card that do not fit in the card (issue #325, controller): what plays now (title, artist, album,
 * position), previous and next, shuffle, repeat, seek, and the switch to the next group of the server. Each button is enabled only if
 * the server lists the command. The texts come from the network and are cleaned. Plain views in an {@link AlertDialog}; the commands
 * are the session's.
 */
final class SendspinServerSheet {

    private static final long SEEK_STEP_MS = 30_000L;

    private SendspinServerSheet() {
    }

    static void show(Context context, SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final ControllerSession session = SendspinHub.getShared(context).controllers().session(device.getEndpoint());
        if (session == null || session.phase() != ControllerSession.Phase.ACTIVE) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);

        ControllerMessages.Metadata metadata = session.metadata();
        if (metadata != null) {
            addText(root, SendspinRowText.nowPlaying(metadata.title, metadata.artist), android.R.style.TextAppearance_Material_Medium);
            String album = SendspinRowText.clean(metadata.album);
            if (!album.isEmpty()) {
                addText(root, album, android.R.style.TextAppearance_Material_Body1);
            }
            long position = session.positionMs();
            if (position >= 0) {
                String shown = format(position) + (metadata.progress != null && metadata.progress.trackDurationMs > 0
                        ? " / " + format(metadata.progress.trackDurationMs) : "");
                addText(root, shown, android.R.style.TextAppearance_Material_Body1);
            }
        }
        ControllerMessages.ControllerState controller = session.controller();
        LinearLayout transport = row(context);
        transport.addView(button(context, R.string.sendspin_server_previous, controller != null && controller.supports("previous"),
                () -> session.previous()));
        transport.addView(button(context, R.string.sendspin_server_next, controller != null && controller.supports("next"),
                () -> session.next()));
        root.addView(transport);

        LinearLayout seek = row(context);
        seek.addView(button(context, R.string.sendspin_server_seek_back, controller != null && controller.supports("seek_relative"),
                () -> session.seekRelative(-SEEK_STEP_MS)));
        seek.addView(button(context, R.string.sendspin_server_seek_forward, controller != null && controller.supports("seek_relative"),
                () -> session.seekRelative(SEEK_STEP_MS)));
        root.addView(seek);

        LinearLayout modes = row(context);
        final boolean shuffle = controller != null && controller.shuffle;
        modes.addView(button(context, shuffle ? R.string.sendspin_server_shuffle_off : R.string.sendspin_server_shuffle_on,
                controller != null && controller.supports(shuffle ? "unshuffle" : "shuffle"), () -> session.setShuffle(!shuffle)));
        final String repeat = controller == null ? "off" : controller.repeat;
        final String nextRepeat = "off".equals(repeat) ? "all" : "all".equals(repeat) ? "one" : "off";
        int repeatText = "off".equals(repeat) ? R.string.sendspin_server_repeat_off : "all".equals(repeat)
                ? R.string.sendspin_server_repeat_all : R.string.sendspin_server_repeat_one;
        modes.addView(button(context, repeatText, controller != null && controller.supports(
                "one".equals(nextRepeat) ? "repeat_one" : "all".equals(nextRepeat) ? "repeat_all" : "repeat_off"),
                () -> session.setRepeat(nextRepeat)));
        root.addView(modes);

        LinearLayout group = row(context);
        group.addView(button(context, R.string.sendspin_server_switch, controller != null && controller.supports("switch"),
                () -> session.switchGroup()));
        root.addView(group);

        String name = SendspinRowText.clean(session.serverName());
        new AlertDialog.Builder(context)
                .setTitle(name.isEmpty() ? device.getName() : name)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String format(long millis) {
        long seconds = millis / 1000L;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static void addText(LinearLayout root, String text, int appearance) {
        TextView view = new TextView(root.getContext());
        view.setText(text);
        view.setTextAppearance(appearance);
        root.addView(view);
    }

    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private static Button button(Context context, int text, boolean enabled, final Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }
}
