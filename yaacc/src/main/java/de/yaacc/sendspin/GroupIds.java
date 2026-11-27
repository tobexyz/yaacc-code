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
package de.yaacc.sendspin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The identifiers of the groups, as the protocol wants them (issue #325, task 6.1): 32 random bytes chosen by the server,
 * base64url-encoded without padding (43 characters), the same for as long as the group exists and allowed to change after a restart.
 * A group is named here by a key (the receiver id of its leader, or of the player that is alone); the players of the group and the
 * controllers of the server get the same id for the same key.
 */
public final class GroupIds {

    private final SecureRandom random;
    private final Map<String, String> ids = new HashMap<>();

    public GroupIds(SecureRandom random) {
        this.random = random;
    }

    public synchronized String idOf(String key) {
        String id = ids.get(key);
        if (id == null) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            ids.put(key, id);
        }
        return id;
    }
}
