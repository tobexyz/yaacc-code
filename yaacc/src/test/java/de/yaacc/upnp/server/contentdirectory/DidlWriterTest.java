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
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Protocol;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.WriteStatus;
import org.fourthline.cling.support.model.container.StorageFolder;
import org.fourthline.cling.support.model.item.Item;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.yaacc.upnp.model.YaaccItem;
import de.yaacc.upnp.model.YaaccRes;

/**
 * {@link DidlWriter} against {@code DIDLParser.generate}, the generator it replaces for big answers: the same xml over
 * many items and folders, titles and urls with special characters included. Plain JVM.
 */
public class DidlWriterTest {

    private static final ProtocolInfo JPEG = new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, "image/jpeg",
            "DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    private static final ProtocolInfo MP3 = new ProtocolInfo(Protocol.HTTP_GET, ProtocolInfo.WILDCARD, "audio/mpeg",
            "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");

    /** Titles with everything an xml serializer has an opinion about (the ones the writer takes; the others are tested below). */
    private static final String[] TITLES = {
            "plain.jpg", "IMG_0001 Strand & Meer.jpg", "a<b>c.jpg", "quote \" and ' apostrophe.jpg", "&amp; already.jpg",
            "Umlaute äöüß.jpg", "日本語.mp3", "café à la carte.jpg", "tab-free  double  space.jpg",
            "", " leading and trailing ", "]]> cdata end", "<![CDATA[x]]>", "€ 5 – dash „quote“", " nbsp .jpg", "x".repeat(300)};

    private static Item item(int i, String title, boolean audio, boolean restricted, long date, String creator) {
        YaaccRes res = new YaaccRes(audio ? MP3 : JPEG, i % 7 == 0 ? null : (long) (1000 + i), audio ? "00:0" + (i % 10) + ":12" : null, null,
                "http://192.168.1.20:49157/saf/1100999" + i + "/" + i + "_" + title.replace(' ', '_') + (audio ? ".mp3" : ".jpg"));
        YaaccItem yaaccItem = new YaaccItem("1100999" + i, "1100999", restricted ? "[X] " + title : title, creator, restricted,
                audio ? "object.item.audioItem" : "object.item.imageItem");
        yaaccItem.addResource(res);
        Item item = yaaccItem.toClingItem();
        if (date > 0) {
            item.addProperty(new DIDLObject.Property.DC.DATE(SortSupport.formatEpochMillisAsDate(date)));
        }
        return item;
    }

    private static DIDLContent content(Random random, int folders, int items) {
        DIDLContent content = new DIDLContent();
        for (int i = 0; i < folders; i++) {
            StorageFolder folder = new StorageFolder("1100999f" + i, "1100999", TITLES[random.nextInt(TITLES.length)] + i, "yaacc", i % 3 == 0 ? null : i, null);
            folder.setRestricted(random.nextBoolean());
            content.addContainer(folder);
        }
        for (int i = 0; i < items; i++) {
            content.addItem(item(i, TITLES[random.nextInt(TITLES.length)] + i, random.nextInt(4) == 0, random.nextInt(10) == 0,
                    random.nextBoolean() ? 0 : 1_600_000_000_000L + i * 86_400_000L, i % 5 == 0 ? null : "yaacc"));
        }
        return content;
    }

    private static final Pattern START_TAG = Pattern.compile("<([A-Za-z][^\\s>/]*)((?:\\s+[^\\s=>]+=\"[^\"]*\")+)\\s*(/?)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\s+([^\\s=>]+)=\"([^\"]*)\"");

    /** The xml with the attributes of every tag in alphabetical order: the DOM serializer of the JVM sorts them, the writer keeps the order of the generator. */
    private static String attributesSorted(String xml) {
        Matcher tag = START_TAG.matcher(xml);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (tag.find()) {
            out.append(xml, last, tag.start());
            List<String> attributes = new ArrayList<>();
            Matcher attribute = ATTRIBUTE.matcher(tag.group(2));
            while (attribute.find()) {
                attributes.add(attribute.group(1) + "=\"" + attribute.group(2) + "\"");
            }
            attributes.sort(String::compareTo);
            out.append('<').append(tag.group(1));
            for (String a : attributes) {
                out.append(' ').append(a);
            }
            out.append(tag.group(3)).append('>');
            last = tag.end();
        }
        out.append(xml.substring(last));
        return out.toString();
    }

    @Test
    public void theXmlIsTheSameAsTheDomGeneratorsOverManyItemsAndFolders() throws Exception {
        DIDLContent content = content(new Random(42), 40, 2500);

        String direct = DidlWriter.write(content);
        String dom = new DIDLParser().generate(content, false);

        assertNotNull("the writer takes this content", direct);
        assertEquals(attributesSorted(dom), attributesSorted(direct));
    }

    @Test
    public void itemsWithTheAttributesInTheSortedOrderAreByteIdentical() throws Exception {
        // id, parentID, restricted and protocolInfo, size are in the generator's order and in alphabetical order: nothing to normalize
        DIDLContent content = new DIDLContent();
        for (int i = 0; i < 400; i++) {
            content.addItem(item(i + 1, TITLES[i % TITLES.length] + i, false, i % 6 == 0, 1_600_000_000_000L + i * 1000L, "yaacc"));
        }

        assertEquals(new DIDLParser().generate(content, false), DidlWriter.write(content));
    }

    @Test
    public void escapesTextAndAttributesAsTheDomSerializerDoes() throws Exception {
        DIDLContent content = new DIDLContent();
        YaaccItem yaaccItem = new YaaccItem("1", "0", "A & B < C > D \" E ' F", "yaacc", false, "object.item.imageItem");
        yaaccItem.addResource(new YaaccRes(JPEG, 5L, null, null, "http://h/saf/1/1_a.jpg?x=1&y=\"2\"<3>"));
        content.addItem(yaaccItem.toClingItem());

        String direct = DidlWriter.write(content);

        assertEquals(new DIDLParser().generate(content, false), direct);
        assertTrue(direct, direct.contains("<dc:title>A &amp; B &lt; C &gt; D \" E ' F</dc:title>"));
        assertTrue(direct, direct.contains(">http://h/saf/1/1_a.jpg?x=1&amp;y=\"2\"&lt;3&gt;</res>"));
    }

    @Test
    public void aFolderIsWrittenLikeTheDomGeneratorDoes() throws Exception {
        DIDLContent content = new DIDLContent();
        StorageFolder folder = new StorageFolder("11", "0", "[X] Fotos & Videos", "yaacc", 17, null);
        folder.setRestricted(true);
        content.addContainer(folder);

        assertEquals(attributesSorted(new DIDLParser().generate(content, false)), attributesSorted(DidlWriter.write(content)));
    }

    @Test
    public void somethingTheWriterDoesNotKnowIsLeftToTheDomGenerator() {
        DIDLContent withOtherProperty = new DIDLContent();
        Item album = item(1, "a.jpg", false, false, 0, "yaacc");
        album.addProperty(new DIDLObject.Property.UPNP.ALBUM("Album"));
        withOtherProperty.addItem(album);
        assertNull(DidlWriter.write(withOtherProperty));

        DIDLContent withWriteStatus = new DIDLContent();
        Item writable = item(2, "b.jpg", false, false, 0, "yaacc");
        writable.setWriteStatus(WriteStatus.WRITABLE);
        withWriteStatus.addItem(writable);
        assertNull(DidlWriter.write(withWriteStatus));

        DIDLContent withRefId = new DIDLContent();
        Item reference = item(3, "c.jpg", false, false, 0, "yaacc");
        reference.setRefID("9");
        withRefId.addItem(reference);
        assertNull(DidlWriter.write(withRefId));

        // an item without a title gets "Unknown Title" from the generator: not the writer's business
        Item untitled = item(5, "e.jpg", false, false, 0, "yaacc");
        untitled.setTitle(null);
        DIDLContent noTitle = new DIDLContent();
        noTitle.addItem(untitled);
        assertNull(DidlWriter.write(noTitle));
    }

    @Test
    public void aStringWithACharacterTheSerializerDecidesAboutIsLeftToTheDomGenerator() {
        String[] unsafe = {"tab\there", "new\nline", "carriage\rreturn", "bell\u0007", "nul\u0000", "c1\u0085", "del\u007f",
                "line sep ", "emoji 😀", "lone \uD800 surrogate", "nonchar￿"};
        for (String title : unsafe) {
            DIDLContent content = new DIDLContent();
            content.addItem(item(1, title, false, false, 0, "yaacc"));
            assertNull("title " + title.replaceAll("\\p{Cntrl}", "?"), DidlWriter.write(content));
        }
    }

    @Test
    public void emptyAnswerStillHasTheRoot() throws Exception {
        DIDLContent content = new DIDLContent();
        String direct = DidlWriter.write(content);
        assertEquals(attributesSorted(new DIDLParser().generate(content, false)).replace("/>", "></DIDL-Lite>"), attributesSorted(direct));
    }

    @Test
    public void utf8LengthIsTheNumberOfBytes() {
        String[] samples = {"", "abc", "äöü", "日本語", "😀 smile", "mixed ä € 😀 x", "lone \uD800 high"};
        for (String sample : samples) {
            assertEquals(sample, sample.getBytes(StandardCharsets.UTF_8).length, DidlWriter.utf8Length(sample));
        }
    }

    @Test
    public void bigAnswerIsWrittenFast() {
        DIDLContent content = content(new Random(7), 50, 5570);

        long start = System.nanoTime();
        String direct = DidlWriter.write(content);
        long millis = (System.nanoTime() - start) / 1_000_000L;

        assertNotNull(direct);
        assertTrue("5620 objects took " + millis + " ms", millis < 1500);
    }
}
