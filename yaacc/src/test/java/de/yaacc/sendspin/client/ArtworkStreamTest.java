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
package de.yaacc.sendspin.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.yaacc.sendspin.session.ControllerMessages;
import de.yaacc.sendspin.session.ProtocolException;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/** The artwork role of a client (spec, "Artwork messages"): transfers in parts, the current and the pending image, every malformed case. */
public class ArtworkStreamTest {

    private static final long NOW = 1_000_000L;

    private static ArtworkStream started() {
        ArtworkStream stream = new ArtworkStream();
        stream.start(Collections.singletonList(ControllerMessages.ArtworkChannel.album("jpeg", 300, 300)));
        return stream;
    }

    /** The body of an announce (the type byte is the message id and not in the body): flags, timestamp, total size. */
    private static byte[] announce(long timestamp, long totalSize) {
        return ByteBuffer.allocate(13).put((byte) 0x02).putLong(timestamp).putInt((int) totalSize).array();
    }

    private static byte[] part(byte... data) {
        byte[] body = new byte[1 + data.length];
        System.arraycopy(data, 0, body, 1, data.length);
        return body;
    }

    private static final byte[] CANCEL = {0x01};

    private static void rejects(String what, ArtworkStream stream, int type, byte[] body) {
        try {
            stream.onMessage(type, body, NOW);
            fail(what);
        } catch (ProtocolException expected) {
            // fine
        }
    }

    @Test
    public void anImageInPartsBecomesTheCurrentImageWhenItsTimeHasComeAndTheLastPartArrived() throws Exception {
        ArtworkStream stream = started();
        assertNull(stream.current(0, NOW));
        stream.onMessage(8, announce(NOW + 5_000_000L, 5), NOW);
        stream.onMessage(8, part((byte) 1, (byte) 2), NOW);
        assertNull("not complete", stream.current(0, NOW));
        stream.onMessage(8, part((byte) 3, (byte) 4, (byte) 5), NOW);
        assertNull("complete, but its time has not come", stream.current(0, NOW));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, stream.pending(0));
        assertArrayEquals("its time has come", new byte[]{1, 2, 3, 4, 5}, stream.current(0, NOW + 5_000_000L));
        assertNull(stream.pending(0));
    }

    @Test
    public void anImageOfThePastOrPresentIsShownAtOnce() throws Exception {
        ArtworkStream stream = started();
        long before = stream.version();
        stream.onMessage(8, announce(NOW, 3), NOW);
        stream.onMessage(8, part((byte) 9, (byte) 8, (byte) 7), NOW);
        assertArrayEquals(new byte[]{9, 8, 7}, stream.current(0, NOW));
        assertNotEquals("the display is told that the image changed", before, stream.version());
        assertNull(stream.pending(0));
    }

    @Test
    public void anEmptyImageClearsTheChannelAndNeedsNoParts() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW, 2), NOW);
        stream.onMessage(8, part((byte) 1, (byte) 2), NOW);
        assertTrue(stream.current(0, NOW).length == 2);
        stream.onMessage(8, announce(NOW, 0), NOW); // total_size 0 completes at once
        assertNull(stream.current(0, NOW));
        stream.onMessage(8, announce(NOW, 3), NOW); // so another transfer may follow at once
    }

    @Test
    public void aScheduledClearWaitsForItsTime() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW, 1), NOW);
        stream.onMessage(8, part((byte) 5), NOW);
        stream.onMessage(8, announce(NOW + 1_000L, 0), NOW);
        assertArrayEquals("still the old image", new byte[]{5}, stream.current(0, NOW));
        assertNull("then cleared", stream.current(0, NOW + 1_000L));
    }

    @Test
    public void aNewAnnounceDiscardsThePendingImageOfItsChannel() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW + 9_000_000L, 1), NOW);
        stream.onMessage(8, part((byte) 1), NOW);
        assertArrayEquals(new byte[]{1}, stream.pending(0));
        stream.onMessage(8, announce(NOW + 9_000_000L, 1), NOW);
        assertNull("the earlier pending image is gone at the announce", stream.pending(0));
        stream.onMessage(8, part((byte) 2), NOW);
        assertArrayEquals(new byte[]{2}, stream.pending(0));
    }

    @Test
    public void aCancelDiscardsThePendingImageAndAnInFlightTransferButNotTheCurrentImage() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW, 1), NOW);
        stream.onMessage(8, part((byte) 4), NOW);
        stream.onMessage(8, announce(NOW + 9_000_000L, 4), NOW);
        stream.onMessage(8, part((byte) 1), NOW);
        stream.onMessage(8, CANCEL, NOW);
        assertNull(stream.pending(0));
        assertArrayEquals("the current image is unaffected", new byte[]{4}, stream.current(0, NOW));
        stream.onMessage(8, announce(NOW, 1), NOW); // no transfer is in flight any more
    }

    @Test
    public void streamEndClearsEverythingAndLaterMessagesAreIgnored() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW, 1), NOW);
        stream.onMessage(8, part((byte) 4), NOW);
        stream.onMessage(8, announce(NOW + 9_000_000L, 1), NOW);
        stream.end();
        assertFalse(stream.isActive());
        assertNull(stream.current(0, NOW));
        assertNull(stream.pending(0));
        stream.onMessage(8, announce(NOW, 1), NOW); // outside a stream: ignored, not an error
        assertNull(stream.current(0, NOW));
    }

    @Test
    public void anImageOverTheLimitIsDiscardedButItsPartsAreStillCounted() throws Exception {
        ArtworkStream stream = started();
        long big = ArtworkStream.MAX_IMAGE_BYTES + 10L;
        stream.onMessage(8, announce(NOW, big), NOW);
        byte[] chunk = new byte[1000];
        long sent = 0;
        while (sent + chunk.length < big) {
            stream.onMessage(8, part(chunk), NOW);
            sent += chunk.length;
        }
        stream.onMessage(8, part(new byte[(int) (big - sent)]), NOW);
        assertNull("nothing was kept", stream.current(0, NOW));
        stream.onMessage(8, announce(NOW, 1), NOW); // the transfer was counted to its end: no one is in flight
    }

    @Test
    public void malformedMessagesAreProtocolErrors() {
        rejects("too short: no flags", started(), 8, new byte[0]);
        rejects("announce of the wrong length", started(), 8, new byte[]{0x02, 0, 0});
        rejects("announce too long", started(), 8, new byte[14]);
        rejects("cancel with data", started(), 8, new byte[]{0x01, 7});
        rejects("a reserved flag bit", started(), 8, new byte[]{0x04});
        rejects("a reserved flag bit with a part", started(), 8, new byte[]{(byte) 0x80, 1});
        rejects("cancel and announce at once", started(), 8, new byte[]{0x03});
        rejects("over 65519 bytes", started(), 8, new byte[65519]);
    }

    @Test
    public void malformedSequencesAreProtocolErrors() throws Exception {
        ArtworkStream a = started();
        rejects("a part with no transfer", a, 8, part((byte) 1));
        ArtworkStream b = started();
        b.onMessage(8, announce(NOW, 3), NOW);
        rejects("an announce while a transfer is in flight", b, 8, announce(NOW, 1));
        ArtworkStream c = started();
        c.onMessage(8, announce(NOW, 2), NOW);
        rejects("a part past the total", c, 8, part((byte) 1, (byte) 2, (byte) 3));
        ArtworkStream d = new ArtworkStream();
        d.start(Arrays.asList(ControllerMessages.ArtworkChannel.album("jpeg", 100, 100), ControllerMessages.ArtworkChannel.album("png", 50, 50)));
        d.onMessage(8, announce(NOW, 3), NOW);
        rejects("a part of another channel", d, 9, part((byte) 1));
    }

    @Test
    public void channelsAreIndependentAndOneTransferIsInFlightAtATime() throws Exception {
        ArtworkStream stream = new ArtworkStream();
        stream.start(Arrays.asList(ControllerMessages.ArtworkChannel.album("jpeg", 100, 100), ControllerMessages.ArtworkChannel.album("png", 50, 50)));
        stream.onMessage(9, announce(NOW, 1), NOW);
        stream.onMessage(9, part((byte) 6), NOW);
        stream.onMessage(8, announce(NOW, 1), NOW);
        stream.onMessage(8, part((byte) 7), NOW);
        assertArrayEquals(new byte[]{7}, stream.current(0, NOW));
        assertArrayEquals(new byte[]{6}, stream.current(1, NOW));
        assertNull("a channel that does not exist", stream.current(3, NOW));
    }

    @Test
    public void aChannelThatIsNotConfiguredDiscardsItsImagesButStillFollowsTheSequence() throws Exception {
        ArtworkStream stream = started(); // channel 0 only
        stream.onMessage(10, announce(NOW, 2), NOW);
        stream.onMessage(10, part((byte) 1, (byte) 2), NOW);
        assertNull(stream.current(2, NOW));
        stream.onMessage(8, announce(NOW, 1), NOW);
    }

    @Test
    public void aStreamStartThatRepeatsTheConfigurationKeepsTheImageAndAChangedOneClearsIt() throws Exception {
        ArtworkStream stream = started();
        stream.onMessage(8, announce(NOW, 1), NOW);
        stream.onMessage(8, part((byte) 4), NOW);
        stream.start(Collections.singletonList(ControllerMessages.ArtworkChannel.album("jpeg", 300, 300)));
        assertTrue(stream.isActive());
        assertArrayEquals(new byte[]{4}, stream.current(0, NOW));
        stream.start(Collections.singletonList(ControllerMessages.ArtworkChannel.album("png", 600, 600)));
        assertNull("a new size means a new image: the server sends it", stream.current(0, NOW));
    }
}
