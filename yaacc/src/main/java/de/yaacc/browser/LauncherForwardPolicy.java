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

/**
 * Decides what the exported launcher activity hands on to the browser. Plain Java so it can be
 * unit tested on the JVM; the Android {@code Intent} handling stays in the activity.
 * <p>
 * The launcher can be started by any app with any intent, so the forward is an allowlist: a plain
 * launch for everything (MAIN, VIEW, no action, unknown actions), except an {@code ACTION_SEND}
 * of exactly {@code text/plain} with a non-empty text of at most
 * {@link ShareUriPolicy#MAX_TEXT_LENGTH} characters, which is forwarded as that text and nothing
 * else. A text over the cap is dropped, not truncated, and the launch becomes a plain one.
 * <p>
 * Action and type are compared exactly, case-sensitively: the manifest filter and the share
 * handler use the lower-case spelling, so another spelling is not forwarded.
 */
public final class LauncherForwardPolicy {

    private static final String ACTION_SEND = "android.intent.action.SEND";
    private static final String TYPE_TEXT_PLAIN = "text/plain";
    private static final Decision PLAIN_LAUNCH = new Decision(null);

    private LauncherForwardPolicy() {
    }

    /** Either a plain launch (no text) or a SEND text/plain with a capped text. */
    public static final class Decision {
        private final String text;

        private Decision(String text) {
            this.text = text;
        }

        /** @return true if the forward is an {@code ACTION_SEND} of type text/plain carrying {@link #getText()} */
        public boolean isSendText() {
            return text != null;
        }

        /** @return the text to forward, or null for a plain launch */
        public String getText() {
            return text;
        }
    }

    /**
     * @param action the incoming action, may be null
     * @param type   the incoming MIME type, may be null
     * @param text   the incoming text (EXTRA_TEXT or clip text), may be null
     */
    public static Decision decide(String action, String type, String text) {
        if (!ACTION_SEND.equals(action) || !TYPE_TEXT_PLAIN.equals(type)) {
            return PLAIN_LAUNCH;
        }
        if (text == null || text.isEmpty() || text.length() > ShareUriPolicy.MAX_TEXT_LENGTH) {
            return PLAIN_LAUNCH;
        }
        return new Decision(text);
    }
}
