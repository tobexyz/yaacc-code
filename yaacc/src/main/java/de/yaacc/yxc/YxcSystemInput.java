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
package de.yaacc.yxc;

import org.json.JSONObject;

/**
 * One entry of the device-wide input list of the features: id, whether it can be distributed, its play info type and
 * whether the input needs an account and can be renamed. A flag the device does not report reads as false.
 */
public final class YxcSystemInput {
    private final String id;
    private final boolean distributionEnabled;
    private final String playInfoType;
    private final boolean accountEnabled;
    private final boolean renameEnabled;

    YxcSystemInput(String id, boolean distributionEnabled, String playInfoType, boolean accountEnabled, boolean renameEnabled) {
        this.id = id;
        this.distributionEnabled = distributionEnabled;
        this.playInfoType = playInfoType;
        this.accountEnabled = accountEnabled;
        this.renameEnabled = renameEnabled;
    }

    /** @return the entry, or {@code null} if it has no id */
    static YxcSystemInput fromJson(JSONObject json) {
        String id = YxcJson.string(json, "id");
        if (id == null) {
            return null;
        }
        return new YxcSystemInput(id, YxcJson.flag(json, "distribution_enable"), YxcJson.string(json, "play_info_type"),
                YxcJson.flag(json, "account_enable"), YxcJson.flag(json, "rename_enable"));
    }

    public String getId() {
        return id;
    }

    public boolean isDistributionEnabled() {
        return distributionEnabled;
    }

    /** @return one of the words {@code netusb}, {@code tuner}, {@code cd}, {@code none} (seen on devices), {@code null} if not reported */
    public String getPlayInfoType() {
        return playInfoType;
    }

    /** @return true for an input that needs an account of a service */
    public boolean isAccountEnabled() {
        return accountEnabled;
    }

    /** @return true for an input the user may give another name */
    public boolean isRenameEnabled() {
        return renameEnabled;
    }
}
