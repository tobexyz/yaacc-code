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
package de.yaacc.upnp.server.contentdirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;
import org.seamless.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.util.SAFMetadata;

/**
 * {@link SafItemFactory}: the item of a file, and the cost of building the items of a big folder. Plain JVM.
 */
public class SafItemFactoryTest {

    private static final String PREFIX = "http://192.168.1.20:49157/saf/";
    private static final String FOLDER = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2F.thumbnails";

    private final AtomicInteger extensionLookups = new AtomicInteger();
    private final AtomicInteger protocolInfoLookups = new AtomicInteger();

    private SafItemFactory factory() {
        return new SafItemFactory(PREFIX,
                mime -> {
                    extensionLookups.incrementAndGet();
                    switch (mime) {
                        case "image/jpeg":
                            return "jpg";
                        case "audio/mpeg":
                            return "mp3";
                        case "video/mp4":
                            return "mp4";
                        default:
                            return null;
                    }
                },
                mime -> {
                    protocolInfoLookups.incrementAndGet();
                    return new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, mime.toString(), "DLNA.ORG_PN=*");
                });
    }

    private static SafDirectoryEntry entry(String name, String mime, long lastModified, long size) {
        return new SafDirectoryEntry(name, "primary:DCIM/.thumbnails/" + name, mime, lastModified, size, false);
    }

    private static SAFMetadata metadata(String duration, String mime, String shortId, long size) {
        return new SAFMetadata(duration, mime, shortId, size);
    }

    private static Res res(Item item) {
        assertEquals(1, item.getResources().size());
        return item.getResources().get(0);
    }

    @Test
    public void anImageItemHasTheIdTitleUrlAndSize() {
        Item item = factory().create(FOLDER + "%2Fa.jpg", entry("a b&c.jpg", "image/jpeg", 1_700_000_000_000L, 500), metadata("", "image/jpeg", "17", 400),
                "1100999folder", false, name -> null);

        assertEquals("110099917", item.getId());
        assertEquals("1100999folder", item.getParentID());
        assertEquals("a b&c.jpg", item.getTitle());
        assertEquals("yaacc", item.getCreator());
        assertEquals(false, item.isRestricted());
        assertEquals("object.item.imageItem", item.getClazz().getValue());
        assertEquals("the size of the listing wins", Long.valueOf(500), res(item).getSize());
        assertEquals(PREFIX + "110099917/17_a_b_c.jpg.jpg", res(item).getValue());
        assertEquals("image/jpeg", res(item).getProtocolInfo().getContentFormat());
        assertNull(res(item).getDuration());
        assertEquals("2023-11-14", item.getFirstPropertyValue(DIDLObject.Property.DC.DATE.class));
    }

    @Test
    public void theSizeOfTheMetadataIsUsedWhenTheListingHasNone_AndNoDateWithoutLastModified() {
        Item item = factory().create(FOLDER + "%2Fa.jpg", entry("a.jpg", "image/jpeg", 0, 0), metadata("", "image/jpeg", "1", 400), "p", false, name -> null);

        assertEquals(Long.valueOf(400), res(item).getSize());
        assertNull(item.getFirstProperty(DIDLObject.Property.DC.DATE.class));
    }

    @Test
    public void aRestrictedItemHasThePrefixAndNoDuration() {
        SafItemFactory factory = factory();
        SAFMetadata audio = metadata("00:03:12", "audio/mpeg", "5", 10);

        Item open = factory.create(FOLDER + "%2Fs.mp3", entry("s.mp3", "audio/mpeg", 0, 10), audio, "p", false, name -> null);
        Item restricted = factory.create(FOLDER + "%2Fs.mp3", entry("s.mp3", "audio/mpeg", 0, 10), audio, "p", true, name -> null);

        assertEquals("object.item.audioItem", open.getClazz().getValue());
        assertEquals("00:03:12", res(open).getDuration());
        assertEquals("[X] s.mp3", restricted.getTitle());
        assertTrue(restricted.isRestricted());
        assertNull(res(restricted).getDuration());
    }

    @Test
    public void aFileWithoutNameGetsTheLastSegmentOfTheUriAsTitleAndFileInTheUrl() {
        Item item = factory().create(FOLDER + "%2Fnoname", entry(null, "image/jpeg", 0, 1), metadata("", "image/jpeg", "9", 1), "p", false, name -> null);

        assertEquals("primary%3ADCIM%2F.thumbnails%2Fnoname", item.getTitle());
        assertEquals(PREFIX + "11009999/9_file.jpg", res(item).getValue());
    }

    @Test
    public void aMimeTypeThatIsNotUsableIsGuessedFromTheName() {
        SafItemFactory factory = factory();

        for (String broken : new String[]{null, "null", "nonsense"}) {
            Item item = factory.create(FOLDER + "%2Fv.mp4", entry("v.mp4", "x", 0, 1), metadata("", broken, "3", 1), "p", false, name -> "video/mp4");
            assertEquals("object.item.videoItem", item.getClazz().getValue());
            assertTrue(res(item).getValue().endsWith("/3_v.mp4.mp4"));
        }
        assertNull("no usable mime type: the file is not served",
                factory.create(FOLDER + "%2Fv.bin", entry("v.bin", "x", 0, 1), metadata("", null, "4", 1), "p", false, name -> null));
    }

    @Test
    public void aMimeTypeWithoutKnownExtensionUsesTheSubtype() {
        Item item = factory().create(FOLDER + "%2Fx", entry("x", "application/octet-stream", 0, 1), metadata("", "application/octet-stream", "8", 1), "p", false, name -> null);

        assertTrue(res(item).getValue().endsWith("/8_x.octet-stream"));
        assertEquals("object.item.imageItem", item.getClazz().getValue());
    }

    @Test
    public void anUnparsableMimeTypeFailsAsBefore() {
        try {
            factory().create(FOLDER + "%2Fx", entry("x", "a/b/c", 0, 1), metadata("", "a/b/c", "8", 1), "p", false, name -> null);
            fail("MimeType.valueOf throws for a/b/c");
        } catch (IllegalArgumentException expected) {
            // as before
        }
    }

    @Test
    public void whatDependsOnTheMimeTypeIsWorkedOutOncePerMimeType() {
        SafItemFactory factory = factory();

        for (int i = 0; i < 1000; i++) {
            boolean jpeg = i % 2 == 0;
            factory.create(FOLDER + "%2F" + i, entry("f" + i, jpeg ? "image/jpeg" : "video/mp4", 0, 1),
                    metadata("", jpeg ? "image/jpeg" : "video/mp4", String.valueOf(i), 1), "p", false, name -> null);
        }

        assertEquals(2, extensionLookups.get());
        assertEquals(2, protocolInfoLookups.get());
    }

    @Test
    public void theProtocolInfoOfAMimeTypeIsSharedByItsItems() {
        SafItemFactory factory = factory();
        Item first = factory.create(FOLDER + "%2F1", entry("1.jpg", "image/jpeg", 0, 1), metadata("", "image/jpeg", "1", 1), "p", false, name -> null);
        Item second = factory.create(FOLDER + "%2F2", entry("2.jpg", "image/jpeg", 0, 1), metadata("", "image/jpeg", "2", 1), "p", false, name -> null);

        assertSame(res(first).getProtocolInfo(), res(second).getProtocolInfo());
    }

    @Test
    public void buildingTheItemsOfAFolderOf5570FilesIsFast() {
        SafItemFactory factory = factory();
        List<Item> items = new ArrayList<>();
        String[] mimes = {"image/jpeg", "audio/mpeg", "video/mp4", "application/octet-stream"};

        long start = System.nanoTime();
        for (int i = 0; i < 5570; i++) {
            String mime = mimes[i % mimes.length];
            String name = "IMG_2024" + i + " Strand & Meer.x";
            Item item = factory.create(FOLDER + "%2F" + i, entry(name, mime, 1_700_000_000_000L + i * 3_600_000L, 1000 + i),
                    metadata("", mime, String.valueOf(1000 + i), 1000 + i), "1100999", i % 50 == 0, n -> null);
            assertNotNull(item);
            items.add(item);
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(5570, items.size());
        assertTrue("5570 items took " + millis + " ms (limit 1500 ms; a phone is slower than this machine, the limit is the guard against a per item cost like a lookup of the network interfaces)", millis < 1500);
        assertEquals("the extension of a mime type is looked up once per mime type, not once per item", 4, extensionLookups.get());
    }
}
