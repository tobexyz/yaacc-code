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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.function.Predicate;

/**
 * {@link StreamIntake}: what a browser activity repeats on a shared file, whatever the launcher
 * did. Plain JVM.
 */
public class StreamIntakeTest {

    private static final String SEND = "android.intent.action.SEND";
    private static final String OWN = "de.yaacc.fileprovider";
    private static final String URI = "content://media/external/audio/media/42";
    private static final int READ = 1;
    private static final Predicate<String> NOT_OURS = authority -> false;

    private static String validate(String action, int count, String uri, boolean data, int flags,
                                   Predicate<String> own) {
        return StreamIntake.validate(action, count, uri, data, flags, OWN, own);
    }

    @Test
    public void aWellFormedShareIsKept() {
        assertEquals(URI, validate(SEND, 1, URI, false, READ, NOT_OURS));
    }

    @Test
    public void otherFlagsDoNotMatterAsLongAsReadIsThere() {
        assertEquals(URI, validate(SEND, 1, URI, false, READ | 0x10000000, NOT_OURS));
    }

    @Test
    public void anotherActionIsIgnored() {
        assertNull(validate("android.intent.action.VIEW", 1, URI, false, READ, NOT_OURS));
        assertNull(validate("android.intent.action.SEND_MULTIPLE", 1, URI, false, READ, NOT_OURS));
        assertNull(validate("android.intent.action.send", 1, URI, false, READ, NOT_OURS));
        assertNull(validate(null, 1, URI, false, READ, NOT_OURS));
    }

    @Test
    public void exactlyOneClipItemIsRequired() {
        assertNull(validate(SEND, 0, URI, false, READ, NOT_OURS));
        assertNull(validate(SEND, 2, URI, false, READ, NOT_OURS));
        assertNull(validate(SEND, -1, URI, false, READ, NOT_OURS));
    }

    @Test
    public void dataMakesTheIntentSuspect() {
        assertNull(validate(SEND, 1, URI, true, READ, NOT_OURS));
    }

    @Test
    public void withoutTheReadFlagInItsOwnIntentThereIsNoShare() {
        assertNull(validate(SEND, 1, URI, false, 0, NOT_OURS));
        assertNull(validate(SEND, 1, URI, false, 2 /* write only */, NOT_OURS));
    }

    @Test
    public void thePolicyIsAppliedAgain() {
        assertNull(validate(SEND, 1, null, false, READ, NOT_OURS));
        assertNull(validate(SEND, 1, "file:///data/data/de.yaacc/x", false, READ, NOT_OURS));
        assertNull(validate(SEND, 1, "http://example.org/a.mp3", false, READ, NOT_OURS));
        assertNull(validate(SEND, 1, "content://" + OWN + "/shared/x", false, READ, NOT_OURS));
    }

    @Test
    public void anyProviderOfThisAppIsRefusedNotOnlyTheFileProvider() {
        Predicate<String> ours = authority -> authority.equals("de.yaacc.androidx-startup");

        assertNull(validate(SEND, 1, "content://de.yaacc.androidx-startup/x", false, READ, ours));
        assertEquals(URI, validate(SEND, 1, URI, false, READ, ours));
    }

    @Test
    public void thePackageCheckSeesTheAuthorityAsWritten() {
        final String[] seen = new String[1];
        validate(SEND, 1, "content://Media.Provider/x", false, READ, authority -> {
            seen[0] = authority;
            return false;
        });

        assertEquals("Media.Provider", seen[0]);
    }

    @Test
    public void aFailingPackageCheckRefusesTheShare() {
        assertNull(validate(SEND, 1, URI, false, READ, authority -> {
            throw new IllegalStateException("package manager is gone");
        }));
    }

    @Test
    public void noPackageCheckRefusesTheShare() {
        assertNull(validate(SEND, 1, URI, false, READ, null));
    }
}
