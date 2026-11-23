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
package de.yaacc.fcast.discovery;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * What the activity that opens {@code fcast://r/} links accepts and does (task 5.6). The link can come from any app or web page, not
 * only from a scan, so: nothing is connected, played or stored before the user confirms; a link that is not an FCast connection link
 * (or one that does not parse) shows "not an FCast link" and nothing else; an identical link repeated within
 * {@value #REPEAT_MILLIS} ms is ignored (a page that fires the same intent in a loop must not stack dialogs); the link text is never logged.
 * Pure; the clock is injected.
 */
public final class FCastLinkIntake {

    public static final long REPEAT_MILLIS = 2_000L;

    public enum Kind {
        /** The same link again, too soon: do nothing. */
        IGNORE,
        /** Not an FCast connection link, or one that is damaged: say so, nothing else. */
        NOT_A_LINK,
        /** Ask the user whether to add the receiver. */
        CONFIRM
    }

    /** What to do with an incoming link. */
    public static final class Decision {
        private final Kind kind;
        private final FCastConnectionUrl.Parsed parsed;
        private final String link;

        private Decision(Kind kind, FCastConnectionUrl.Parsed parsed, String link) {
            this.kind = kind;
            this.parsed = parsed;
            this.link = link;
        }

        public Kind getKind() {
            return kind;
        }

        /** The content of the link for the confirmation, null unless {@link Kind#CONFIRM}. */
        public FCastConnectionUrl.Parsed getParsed() {
            return parsed;
        }

        /** The link text to hand to {@link FCastAddReceiver} when the user confirms, null unless {@link Kind#CONFIRM}. */
        public String getLink() {
            return link;
        }
    }

    private final MonotonicClock clock;
    private String lastLink;
    private long lastAt;

    public FCastLinkIntake(MonotonicClock clock) {
        this.clock = clock;
    }

    /** @param dataString the data of the intent ({@code getDataString()} only), may be null */
    public synchronized Decision accept(String dataString) {
        FCastConnectionUrl.Result result = FCastConnectionUrl.parse(dataString);
        if (result.getParsed() == null) {
            return new Decision(Kind.NOT_A_LINK, null, null);
        }
        String link = dataString.trim();
        long now = clock.nowMillis();
        if (link.equals(lastLink) && now - lastAt < REPEAT_MILLIS) {
            return new Decision(Kind.IGNORE, null, null);
        }
        lastLink = link;
        lastAt = now;
        return new Decision(Kind.CONFIRM, result.getParsed(), link);
    }
}
