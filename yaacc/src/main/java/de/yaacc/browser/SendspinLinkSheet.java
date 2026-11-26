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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.yaacc.R;
import de.yaacc.sendspin.SendspinGroups;
import de.yaacc.sendspin.SendspinHub;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinRowText;

/**
 * The sheet of the link button on the card of a Sendspin player. On the card of a player that leads (or none yet) it lists the other
 * players with a tick each: the ticked ones play together with this one, in sync (linking approves a player, as playing to it does). On
 * the card of a member it says whose group the player is in and offers to leave it. Plain views in an {@link AlertDialog}.
 */
final class SendspinLinkSheet {

    private SendspinLinkSheet() {
    }

    private static String nameOf(SendspinReceiverDevice device) {
        String name = SendspinRowText.clean(device.getName());
        return name.isEmpty() ? device.getReceiverId() : name;
    }

    static void show(final Context context, final SendspinReceiverDevice device) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final SendspinHub hub = SendspinHub.getShared(context);
        final SendspinGroups groups = hub.groups();
        final String id = device.getReceiverId();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);

        String leaderId = groups.leaderOf(id);
        if (leaderId != null) {
            SendspinReceiverDevice leader = hub.findReceiver(leaderId);
            addText(root, context.getString(R.string.sendspin_link_member_of, leader == null ? leaderId : nameOf(leader)));
            new AlertDialog.Builder(context)
                    .setTitle(nameOf(device))
                    .setView(root)
                    .setPositiveButton(R.string.sendspin_link_leave, (d, w) -> hub.unlink(id))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        addText(root, context.getString(R.string.sendspin_link_message));
        final Map<CheckBox, SendspinReceiverDevice> boxes = new LinkedHashMap<>();
        final List<String> members = groups.membersOf(id);
        for (SendspinReceiverDevice other : hub.receivers()) {
            String otherId = other.getReceiverId();
            if (other.isServer() || otherId.equals(id) || groups.isLeader(otherId)) {
                continue; // a leader of another group has to give up its group first
            }
            if (other.isSelf()) {
                hub.connectionById(otherId, true); // the phone's own player: the connection is made now, so that it is ready when "Link" is tapped
            }
            CheckBox box = new CheckBox(context);
            String inOther = groups.leaderOf(otherId);
            String text = nameOf(other);
            if (inOther != null && !inOther.equals(id)) {
                SendspinReceiverDevice otherLeader = hub.findReceiver(inOther);
                text += "  " + context.getString(R.string.sendspin_link_in_other_group, otherLeader == null ? inOther : nameOf(otherLeader));
            }
            box.setText(text);
            box.setChecked(members.contains(otherId));
            root.addView(box);
            boxes.put(box, other);
        }
        if (boxes.isEmpty()) {
            addText(root, context.getString(R.string.sendspin_link_none));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sendspin_link_title, nameOf(device)))
                .setView(scroll)
                .setPositiveButton(R.string.sendspin_link_apply, (d, w) -> apply(context, hub, id, boxes))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void apply(Context context, SendspinHub hub, String leaderId, Map<CheckBox, SendspinReceiverDevice> boxes) {
        List<String> now = hub.groups().membersOf(leaderId);
        for (Map.Entry<CheckBox, SendspinReceiverDevice> entry : boxes.entrySet()) {
            SendspinReceiverDevice other = entry.getValue();
            String otherId = other.getReceiverId();
            boolean ticked = entry.getKey().isChecked();
            if (ticked && !now.contains(otherId)) {
                int message = messageFor(hub.link(leaderId, otherId));
                if (message != 0) {
                    Toast.makeText(context, context.getString(message, nameOf(other)), Toast.LENGTH_LONG).show();
                }
            } else if (!ticked && now.contains(otherId)) {
                hub.unlink(otherId);
            }
        }
    }

    /** @return the text for an outcome that did not link, 0 if it did */
    private static int messageFor(SendspinHub.LinkOutcome outcome) {
        switch (outcome) {
            case NOT_KNOWN:
                return R.string.sendspin_link_not_known;
            case NEEDS_PAIRING:
                return R.string.sendspin_link_needs_pairing;
            case NO_COMMON_FORMAT:
                return R.string.sendspin_link_no_format;
            case FULL:
                return R.string.sendspin_link_full;
            case REFUSED:
                return R.string.sendspin_link_refused;
            default:
                return 0;
        }
    }

    private static void addText(LinearLayout root, String text) {
        TextView view = new TextView(root.getContext());
        view.setText(text);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setPadding(0, 0, 0, (int) (8 * root.getContext().getResources().getDisplayMetrics().density));
        root.addView(view);
    }
}
