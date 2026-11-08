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
package de.yaacc.share;

import java.util.function.Predicate;

/**
 * The checks a browser activity repeats on a shared file it receives. The launcher forwards a
 * file only after its own checks, but {@code TabBrowserActivity} is exported, so any app can
 * start a browser directly with any intent: nothing is trusted because "it came from the
 * launcher". Plain Java, no {@code android.*}; the glue reads the fields from the {@code Intent}.
 * <p>
 * A share is accepted only if ALL hold:
 * <ul>
 * <li>the action is exactly {@code android.intent.action.SEND};</li>
 * <li>the ClipData has exactly ONE item (the system checks the grant for every ClipData item,
 * never for {@code EXTRA_STREAM}, so the one URI is read from item 0 only);</li>
 * <li>the intent has no {@code data};</li>
 * <li>the intent's own flags carry {@link #READ_FLAG} (a read grant that
 * really arrived with this intent; without it the system did not check that the sender holds
 * access);</li>
 * <li>{@link SharedContentPolicy#isAcceptableUri} accepts the text (a {@code content:} URI of
 * another app);</li>
 * <li>the authority is not a provider of this app (any of our providers, not only the file
 * provider), judged by {@code isOwnProviderAuthority}; if that check throws, the share is
 * refused.</li>
 * </ul>
 */
public final class StreamIntake {

    private static final String ACTION_SEND = "android.intent.action.SEND";

    private StreamIntake() {
    }

    /** {@code Intent.FLAG_GRANT_READ_URI_PERMISSION}, as a number so this class stays free of {@code android.*}. */
    public static final int READ_FLAG = 0x1;

    /**
     * @param action                 the intent's action, may be null
     * @param clipItemCount          the number of ClipData items, 0 for no ClipData
     * @param clipItemZeroUri        the URI of ClipData item 0 as text, may be null
     * @param hasData                true if the intent has a {@code data} URI
     * @param intentFlags            the intent's own flags
     * @param ownAuthority           the authority of yaacc's file provider
     * @param isOwnProviderAuthority true if an authority belongs to a provider of this app
     * @return the URI text to keep as the pending share, or null to ignore the intent
     */
    public static String validate(String action, int clipItemCount, String clipItemZeroUri,
                                  boolean hasData, int intentFlags, String ownAuthority,
                                  Predicate<String> isOwnProviderAuthority) {
        if (!ACTION_SEND.equals(action) || clipItemCount != 1 || hasData
                || (intentFlags & READ_FLAG) == 0
                || isOwnProviderAuthority == null) {
            return null;
        }
        if (!SharedContentPolicy.isAcceptableUri(clipItemZeroUri, ownAuthority)) {
            return null;
        }
        String authority = SharedContentPolicy.authorityOf(clipItemZeroUri);
        if (authority == null) {
            return null;
        }
        try {
            if (isOwnProviderAuthority.test(authority)) {
                return null;
            }
        } catch (RuntimeException e) {
            // cannot tell: fail closed
            return null;
        }
        return clipItemZeroUri;
    }
}
