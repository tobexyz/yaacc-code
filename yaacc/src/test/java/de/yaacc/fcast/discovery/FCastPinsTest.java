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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import de.yaacc.fcast.protocol.FCastPin;
import de.yaacc.multiroom.discovery.SnapcastManualServers;

/** Task 7.2: the keys of the receivers: stored from links, announced by mDNS, and which one a connection is pinned to. */
public class FCastPinsTest {
    private static final String KEY_A = Base64.getEncoder().encodeToString(bytes(1));
    private static final String KEY_B = Base64.getEncoder().encodeToString(bytes(2));

    private static byte[] bytes(int fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    private static final class MemoryStorage implements SnapcastManualServers.Storage {
        String text;
        boolean failing;

        @Override
        public String read() {
            if (failing) {
                throw new IllegalStateException("unreadable");
            }
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
        }
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final FCastPinStore store = new FCastPinStore(storage);
    private final FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
    private final FCastPins pins = new FCastPins(store, directory::find);

    private static FCastEndpoint endpoint(String text) {
        return FCastEndpoint.parse(text);
    }

    private static FCastPin pin(String base64, FCastPin.Source source) {
        return FCastPin.parse(base64, source);
    }

    private void announce(String ip, String version, String fp) throws Exception {
        Map<String, byte[]> txt = new HashMap<>();
        if (version != null) {
            txt.put("v", version.getBytes(StandardCharsets.UTF_8));
        }
        if (fp != null) {
            txt.put("fp", fp.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(directory.serviceFound("_fcast._tcp|pc", "FCast-pc", InetAddress.getByName(ip), 46899, FCastServiceTxt.parse(txt)));
    }

    // ---- the store

    @Test
    public void aStoredKeyIsReadBackAsALinkKey() {
        assertEquals(FCastPinStore.Result.STORED, store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.DISCOVERY)));
        FCastPin read = store.get(endpoint("192.168.0.239"));
        assertEquals(KEY_A, read.toBase64());
        assertEquals("only a link can put a key here, whatever the caller said", FCastPin.Source.LINK, read.getSource());
        assertNull(store.get(endpoint("192.168.0.240")));
    }

    @Test
    public void theSameKeyIsUnchangedAndAnotherKeyReplacesIt() {
        store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK));
        assertEquals(FCastPinStore.Result.UNCHANGED, store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK)));
        assertEquals(FCastPinStore.Result.REPLACED, store.put(endpoint("192.168.0.239"), pin(KEY_B, FCastPin.Source.LINK)));
        assertEquals(KEY_B, store.get(endpoint("192.168.0.239")).toBase64());
    }

    @Test
    public void theStoreIsCappedAndAFullStoreRefusesNewReceiversButStillReplaces() {
        for (int i = 1; i <= FCastPinStore.MAX; i++) {
            assertEquals(FCastPinStore.Result.STORED, store.put(endpoint("10.0.0." + i), pin(KEY_A, FCastPin.Source.LINK)));
        }
        assertEquals(FCastPinStore.Result.FULL, store.put(endpoint("10.0.1.1"), pin(KEY_A, FCastPin.Source.LINK)));
        assertEquals(FCastPinStore.Result.REPLACED, store.put(endpoint("10.0.0.5"), pin(KEY_B, FCastPin.Source.LINK)));
        assertNull(store.get(endpoint("10.0.1.1")));
    }

    @Test
    public void removingForgetsTheKey() {
        store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK));
        assertTrue(store.remove(endpoint("192.168.0.239")));
        assertFalse(store.remove(endpoint("192.168.0.239")));
        assertNull(store.get(endpoint("192.168.0.239")));
    }

    @Test
    public void damagedLinesAreSkippedAndAnUnreadableStorageIsEmpty() {
        storage.text = "garbage\n192.168.0.239:46899\tnot base64!\n192.168.0.240:46899\t" + KEY_A + "\n999.1.1.1\t" + KEY_A
                + "\n192.168.0.241:46899\t" + Base64.getEncoder().encodeToString(new byte[5]);
        assertNull(store.get(endpoint("192.168.0.239")));
        assertNotNull(store.get(endpoint("192.168.0.240")));
        assertNull(store.get(endpoint("192.168.0.241")));
        storage.failing = true;
        assertNull(store.get(endpoint("192.168.0.240")));
    }

    @Test
    public void whatIsStoredHoldsNoNameOrOtherText() {
        store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK));
        assertEquals("192.168.0.239:46899\t" + KEY_A, storage.text);
    }

    // ---- which key a connection uses

    @Test
    public void aReceiverThatIsNeitherStoredNorFoundHasNoKey() {
        assertNull(pins.pinFor(endpoint("192.168.0.239")));
        assertNull(pins.pinFor(null));
    }

    @Test
    public void aFoundReceiverThatAnnouncesVersion4AndAKeyIsPinnedToTheAnnouncedKey() throws Exception {
        announce("192.168.0.239", "4", KEY_A);
        FCastPin pin = pins.pinFor(endpoint("192.168.0.239"));
        assertEquals(KEY_A, pin.toBase64());
        assertEquals(FCastPin.Source.DISCOVERY, pin.getSource());
    }

    @Test
    public void anAnnouncementWithoutVersion4OrWithoutAUsableKeyGivesNoKey() throws Exception {
        announce("192.168.0.239", "3", KEY_A);
        assertNull("version 3 is spoken in v3", pins.pinFor(endpoint("192.168.0.239")));
        announce("192.168.0.239", "4", null);
        assertNull("no key", pins.pinFor(endpoint("192.168.0.239")));
        announce("192.168.0.239", "4", "not a key");
        assertNull("a key that is not 32 bytes of base64", pins.pinFor(endpoint("192.168.0.239")));
        announce("192.168.0.239", null, KEY_A);
        assertNull("no version", pins.pinFor(endpoint("192.168.0.239")));
    }

    @Test
    public void aStoredKeyWinsOverTheAnnouncedOne() throws Exception {
        announce("192.168.0.239", "4", KEY_B);
        store.put(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK));
        FCastPin pin = pins.pinFor(endpoint("192.168.0.239"));
        assertEquals(KEY_A, pin.toBase64());
        assertEquals(FCastPin.Source.LINK, pin.getSource());
    }

    @Test
    public void aReceiverThatIsOnlyListedByHandIsNotPinnedToAnythingItDidNotSay() {
        directory.setManual(java.util.Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.239"), "pc")));
        assertNull(pins.pinFor(endpoint("192.168.0.239")));
    }

    // ---- adding with a key from a link

    @Test
    public void aNewReceiverWithAKeyStoresTheKey() {
        FCastStoredReceivers.AddResult result = pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK),
                FCastStoredReceivers.AddResult.ADDED);
        assertEquals(FCastStoredReceivers.AddResult.ADDED, result);
        assertEquals(KEY_A, store.get(endpoint("192.168.0.239")).toBase64());
    }

    @Test
    public void aReceiverThatWasListedByItsAddressGetsItsKeyAndCountsAsAdded() {
        FCastStoredReceivers.AddResult result = pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK),
                FCastStoredReceivers.AddResult.DUPLICATE);
        assertEquals("the user did something: the key is stored now", FCastStoredReceivers.AddResult.ADDED, result);
        assertEquals(FCastStoredReceivers.AddResult.DUPLICATE, pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK),
                FCastStoredReceivers.AddResult.DUPLICATE));
    }

    @Test
    public void aLinkWithoutAKeyOrAReceiverThatWasNotAddedStoresNothing() {
        assertEquals(FCastStoredReceivers.AddResult.ADDED, pins.remember(endpoint("192.168.0.239"), null, FCastStoredReceivers.AddResult.ADDED));
        assertEquals(FCastStoredReceivers.AddResult.FULL,
                pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK), FCastStoredReceivers.AddResult.FULL));
        assertEquals(FCastStoredReceivers.AddResult.INVALID,
                pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK), FCastStoredReceivers.AddResult.INVALID));
        assertNull(store.get(endpoint("192.168.0.239")));
    }

    @Test
    public void forgettingRemovesTheKeyOfARemovedReceiver() {
        pins.remember(endpoint("192.168.0.239"), pin(KEY_A, FCastPin.Source.LINK), FCastStoredReceivers.AddResult.ADDED);
        pins.forget(endpoint("192.168.0.239"));
        assertNull(pins.pinFor(endpoint("192.168.0.239")));
    }
}
