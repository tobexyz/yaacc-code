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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.R;
import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastHub;
import de.yaacc.multiroom.SnapcastReceivers;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.multiroom.ui.SnapcastControls;
import de.yaacc.multiroom.ui.SnapcastLinkChoices;

/**
 * Links clients to a Snapcast group and unlinks them (11.3): the link icon of the group's card opens the sheet of the MusicCast
 * renderer ({@link YxcLinkSheet}) with the clients of the group's server, the members checked. OK sends one change
 * ({@link SnapcastControls#changeMembers}); the rows, the disabled member and the change are decided by
 * {@link SnapcastLinkChoices}. The sheet is a snapshot of the moment it was opened, so the control layer applies the change to the
 * group as the server has it then (a client that joined meanwhile stays), and failures come as a toast.
 *
 * <p>The server's endpoint is the first line under the title (a hostile server can name its group like a legitimate one, security
 * review S20), then the group's name; the names of the clients are the server's text, cleaned and cut like everywhere else.
 */
final class SnapcastLinkSheet {

    /** A group keeps at least one client: OK needs one check. */
    private static final int MINIMUM_CHECKED = 1;
    // the server's endpoint and the group's name, each up to 64 characters and each may wrap once: both are read in full
    private static final int SUBTITLE_LINES = 4;

    private SnapcastLinkSheet() {
    }

    /** Opens the sheet of a group; null when the server has not answered yet or the group is no longer there. */
    static Dialog show(Context context, SnapcastGroupDevice device) {
        final String receiverId = device.getIdentity().getUdn().getIdentifierString();
        SnapcastGroupDevice.Id id = SnapcastGroupDevice.parseId(receiverId);
        if (id == null) {
            return null;
        }
        SnapcastReceivers.GroupState state = SnapcastHub.getShared(context).getReceivers().stateOf(receiverId);
        if (state == null || state.getGroup() == null || state.getStatus() == null || !state.isOnline()) {
            Toast.makeText(context, R.string.snapcast_row_server_unreachable, Toast.LENGTH_LONG).show();
            return null;
        }
        final SnapcastServerKey serverKey = id.getServerKey();
        final String groupId = id.getGroupId();
        final List<SnapcastLinkChoices.Choice> choices = SnapcastLinkChoices.of(state.getStatus(), groupId,
                SnapcastUi.linkWords(context));
        List<YxcLinkSheet.Row> rows = new ArrayList<>();
        for (SnapcastLinkChoices.Choice choice : choices) {
            rows.add(new YxcLinkSheet.Row(choice.getClientId(), choice.getLabel(), choice.isMember(), choice.isEnabled()));
        }
        String subtitle = SnapcastUi.rowWords(context).server(SnapcastDisplayText.of(state.getEndpointText(), 64)) + "\n"
                + SnapcastRowBinder.cleanName(device.getDetails().getFriendlyName());
        final SnapcastControls controls = SnapcastUi.controls(context);
        return YxcLinkSheet.show(context, subtitle, rows, selection -> {
            // the ids come from the sheet's own rows; the change is only what differs from what the sheet showed
            SnapcastLinkChoices.Change change = SnapcastLinkChoices.changeOf(choices, selection);
            controls.changeMembers(serverKey, groupId, change);
        }, new YxcLinkSheet.Options(R.string.yxc_action_edit_group, R.string.snapcast_link_keep_one,
                R.string.snapcast_link_none, MINIMUM_CHECKED, SUBTITLE_LINES));
    }
}
