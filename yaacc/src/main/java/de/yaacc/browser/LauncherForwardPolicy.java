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

import java.util.Locale;

import de.yaacc.share.SharedContentPolicy;

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

    /**
     * {@code Intent.FLAG_GRANT_READ_URI_PERMISSION}, repeated here as a number so this class
     * stays free of {@code android.*} (the value is part of the platform API and does not change).
     */
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x1;

    private static final StreamDecision NO_STREAM = new StreamDecision(null);

    /**
     * What to forward for a shared file: either nothing (no stream forward; the launch stays a
     * plain one, or the text path decides), or exactly ONE {@code content:} URI to hand on as the
     * only item of a new ClipData together with {@link #FLAG_GRANT_READ_URI_PERMISSION}. The
     * decision carries nothing else: no type, no text, no extras, no other flags.
     */
    public static final class StreamDecision {
        private final String streamUri;

        private StreamDecision(String streamUri) {
            this.streamUri = streamUri;
        }

        /** @return true if {@link #getStreamUri()} is to be forwarded with the read grant */
        public boolean isForwardStream() {
            return streamUri != null;
        }

        /** @return the one URI to forward, or null if nothing is forwarded */
        public String getStreamUri() {
            return streamUri;
        }

        /** @return true exactly when {@link #isForwardStream()} is true: read grant, never write */
        public boolean grantsRead() {
            return streamUri != null;
        }
    }

    /**
     * Decides whether the launcher forwards a shared file. Forwarded only if ALL hold:
     * <ul>
     * <li>{@code action} is exactly {@code android.intent.action.SEND};</li>
     * <li>{@code type} is an audio, video or image type, concrete ({@code image/png}) or a
     * sender's wildcard ({@code audio/*}): exact lower-case match, no parameters, no other
     * spelling. The real type is checked later via
     * {@code SharedContentPolicy#chooseMimeType}; {@code text/plain} (the text path, see
     * {@link #decide(String, String, String)}), {@code application/*}, a bare {@code *}{@code /*}
     * and null are not forwarded;</li>
     * <li>{@code streamUri} (EXTRA_STREAM as text) equals {@code clipItemUri} (ClipData item 0 as
     * text) exactly: the system checks only data and ClipData for the grant, never EXTRA_STREAM,
     * so a mismatch could name a URI the sender holds no grant for;</li>
     * <li>{@code incomingFlags} contain {@link #FLAG_GRANT_READ_URI_PERMISSION}: the sender really
     * passed a read grant on;</li>
     * <li>{@code SharedContentPolicy.isAcceptableUri(streamUri, ownAuthority)} (only
     * {@code content:} URIs of other apps).</li>
     * </ul>
     * Otherwise the answer is "no stream forward". Never throws.
     *
     * @param action       the incoming action, may be null
     * @param type         the incoming MIME type, may be null
     * @param streamUri    the incoming EXTRA_STREAM URI as text, may be null
     * @param clipItemUri  the URI of ClipData item 0 as text, may be null
     * @param incomingFlags the incoming intent flags
     * @param ownAuthority the authority of yaacc's own file provider
     */
    public static StreamDecision decideStream(String action, String type, String streamUri,
                                              String clipItemUri, int incomingFlags,
                                              String ownAuthority) {
        if (!ACTION_SEND.equals(action) || !isMediaType(type)) {
            return NO_STREAM;
        }
        if (streamUri == null || streamUri.isEmpty() || !streamUri.equals(clipItemUri)) {
            return NO_STREAM;
        }
        if ((incomingFlags & FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return NO_STREAM;
        }
        if (!SharedContentPolicy.isAcceptableUri(streamUri, ownAuthority)) {
            return NO_STREAM;
        }
        return new StreamDecision(streamUri);
    }

    /**
     * Whether the incoming intent is an attempt to share a file (a stream or a clip URI). When such an
     * attempt is not forwarded, the user is told once, with a fixed text, that only audio, video, images
     * and links are accepted; a text share never counts.
     */
    public static boolean isFileShareAttempt(String action, boolean hasExtraStream, boolean hasClipUri) {
        return "android.intent.action.SEND".equals(action) && (hasExtraStream || hasClipUri);
    }

    /**
     * Whether a declared type selects the shared-file path (instead of the text path): an audio,
     * video or image type, concrete or the sender's wildcard, exact lower case. The browser side
     * uses the same test as the launcher so the two cannot disagree about which path an intent takes.
     */
    public static boolean isStreamType(String type) {
        return isMediaType(type);
    }

    /**
     * An audio, video or image type, in lower case and without parameters: concrete
     * ({@code image/svg+xml}) or the sender's wildcard ({@code audio/*}). Lower case only, like the
     * text path: {@code Intent.setType} lower-cases, so another spelling did not come from a
     * normal sender.
     */
    private static boolean isMediaType(String type) {
        if (type == null) {
            return false;
        }
        if (type.equals("audio/*") || type.equals("video/*") || type.equals("image/*")) {
            return true;
        }
        return SharedContentPolicy.isAcceptableMimeType(type)
                && type.equals(type.toLowerCase(Locale.ROOT));
    }
}
