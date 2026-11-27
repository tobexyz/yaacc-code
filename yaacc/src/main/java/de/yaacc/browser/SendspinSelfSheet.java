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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;
import de.yaacc.sendspin.SendspinSettings;
import de.yaacc.sendspin.player.SendspinPlayerHost;
import de.yaacc.sendspin.player.SendspinPlayerRuntime;
import de.yaacc.sendspin.player.SendspinPlayerService;

/** The sheet of the gear on the card of this phone as a player: the servers that are connected (each can be disconnected) and the switch-off. */
final class SendspinSelfSheet {

    private SendspinSelfSheet() {
    }

    static void show(final Context context, SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        final SendspinPlayerHost host = SendspinPlayerRuntime.host();
        final AlertDialog[] dialog = new AlertDialog[1];
        List<SendspinPlayerHost.SessionInfo> servers = host == null ? java.util.Collections.<SendspinPlayerHost.SessionInfo>emptyList()
                : host.sessionInfos();
        if (servers.isEmpty()) {
            TextView none = new TextView(context);
            none.setText(R.string.sendspin_self_sheet_none);
            root.addView(none);
        }
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            String name = SendspinRowText.clean(servers.get(i).serverName);
            Button disconnect = new Button(context);
            disconnect.setText(context.getString(R.string.sendspin_self_disconnect, name.isEmpty() ? "?" : name));
            disconnect.setOnClickListener(v -> {
                host.disconnect(index);
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
            });
            root.addView(disconnect);
        }
        Button token = new Button(context);
        token.setText(R.string.sendspin_self_pairing);
        token.setOnClickListener(v -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            SendspinClientPairDialog.showToken(context);
        });
        root.addView(token);
        if (!de.yaacc.sendspin.SendspinSettings.playerDisplayRoles(
                PreferenceManager.getDefaultSharedPreferences(context)::getBoolean).isEmpty()) {
            Button display = new Button(context);
            display.setText(R.string.sendspin_self_display_open);
            display.setOnClickListener(v -> {
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                context.startActivity(new android.content.Intent(context, SendspinDisplayActivity.class));
            });
            root.addView(display);
        }
        final Handler main = new Handler(Looper.getMainLooper());
        SendspinCodeSection.add(context, root, main, density);
        Button off = new Button(context);
        off.setText(R.string.sendspin_self_switch_off);
        off.setOnClickListener(v -> {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            preferences.edit().putBoolean(SendspinSettings.KEY_PLAYER, false).apply();
            SendspinPlayerService.sync(context);
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
        });
        root.addView(off);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(root);
        dialog[0] = new AlertDialog.Builder(context)
                .setTitle(device.getName())
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog[0].setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        dialog[0].show();
    }
}
