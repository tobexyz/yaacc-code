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

/**
 * Which message the sheet shows for a failed group operation. The text of {@link YxcGroupResult#getDetail()} is for
 * logs; the user gets one of a few kinds, with the name of the device to blame.
 */
public final class YxcGroupMessages {

    public enum Kind {
        /** The change is not possible as asked (a device is in another group, is not known ...). */
        NOT_POSSIBLE,
        /** A device did not answer. */
        UNREACHABLE,
        /** A client left but could not be put back on its input. */
        INPUT_NOT_RESTORED,
        /** Anything else: a step did not reach its state. */
        FAILED
    }

    private YxcGroupMessages() {
    }

    /** @return the kind for a failed result, {@code null} for a success */
    public static Kind kindOf(YxcGroupResult result) {
        if (result == null || result.isSuccess()) {
            return null;
        }
        switch (result.getStep()) {
            case VALIDATE:
                return Kind.NOT_POSSIBLE;
            case READ_STATE:
                return Kind.UNREACHABLE;
            case RESTORE_INPUT:
                return Kind.INPUT_NOT_RESTORED;
            default:
                return Kind.FAILED;
        }
    }
}
