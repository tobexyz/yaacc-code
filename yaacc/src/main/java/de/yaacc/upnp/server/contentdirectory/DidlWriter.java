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

import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;

import java.util.List;

/**
 * Writes the DIDL-Lite answer of a Browse of a big folder straight into a string, for the objects the SAF folders
 * produce (plain items with a resource and an optional {@code dc:date}, plain folders).
 * <p>
 * Why: {@code DIDLParser.generate} builds a DOM of the whole answer and runs the platform's XML transformer over it. For
 * 5570 files that is 2.5 MB of XML and, measured on a desktop JVM, 100 to 320 ms (the DOM and the transformer of a phone
 * are slower, see docs/tech.md, "Large SAF folders"). This writer does the same job in a few milliseconds.
 * <p>
 * Same content as {@code DIDLParser.generate(content, false)}: the same elements in the same order, the same attribute
 * values, the same escaping ({@code & < >} in text and attributes, {@code "} in attributes). It is only used for what it
 * knows exactly: {@link #write} returns null (and the caller uses {@code DIDLParser}) if an object has a feature the writer
 * does not reproduce (properties other than {@code dc:date}, a {@code refID}, a write status, search or create classes,
 * description metadata, containers with resources or properties) or a string with a character whose output form is
 * a matter of the platform's serializer (control characters, surrogates, characters of the C1 range, U+2028/U+2029,
 * U+FFFE/U+FFFF). The attributes are written in the order the generator sets them; a DOM serializer may order them
 * differently, which no XML parser notices.
 * <p>
 * No Android classes.
 *
 * @author Tobias Schoene (tobexyz)
 */
final class DidlWriter {

    /** Below this many objects the DOM costs a fraction of a second even on a phone: not worth a second way to write. */
    static final int MIN_OBJECTS = 300;

    private static final int BYTES_PER_OBJECT = 480;

    private DidlWriter() {
    }

    /** Thrown inside the writer when the content is not something it writes. */
    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }

    private static final Unsupported UNSUPPORTED = new Unsupported();

    /**
     * @return the DIDL-Lite xml of the content, null if the content has something this writer does not write exactly
     * like {@code DIDLParser}
     */
    static String write(DIDLContent content) {
        if (!content.getDescMetadata().isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(64 + (int) Math.min(Integer.MAX_VALUE / 2, content.getCount() * BYTES_PER_OBJECT));
        try {
            out.append("<DIDL-Lite xmlns=\"").append(DIDLContent.NAMESPACE_URI)
                    .append("\" xmlns:dc=\"").append(DIDLObject.Property.DC.NAMESPACE.URI)
                    .append("\" xmlns:sec=\"").append(DIDLObject.Property.SEC.NAMESPACE.URI)
                    .append("\" xmlns:upnp=\"").append(DIDLObject.Property.UPNP.NAMESPACE.URI).append("\">");
            for (Container container : content.getContainers()) {
                if (container != null) {
                    writeContainer(out, container);
                }
            }
            for (Item item : content.getItems()) {
                if (item != null) {
                    writeItem(out, item);
                }
            }
            out.append("</DIDL-Lite>");
        } catch (Unsupported e) {
            return null;
        }
        return out.toString();
    }

    private static void writeContainer(StringBuilder out, Container container) {
        if (container.getClazz() == null || container.getId() == null || container.getParentID() == null
                || container.getTitle() == null || container.getWriteStatus() != null
                || !container.getSearchClasses().isEmpty() || !container.getCreateClasses().isEmpty()
                || !container.getProperties().isEmpty() || !container.getResources().isEmpty()
                || !container.getDescMetadata().isEmpty()) {
            throw UNSUPPORTED;
        }
        out.append("<container id=\"");
        attribute(out, container.getId());
        out.append("\" parentID=\"");
        attribute(out, container.getParentID());
        out.append('"');
        if (container.getChildCount() != null) {
            out.append(" childCount=\"").append(container.getChildCount().intValue()).append('"');
        }
        out.append(" restricted=\"").append(container.isRestricted() ? '1' : '0')
                .append("\" searchable=\"").append(container.isSearchable() ? '1' : '0').append("\">");
        element(out, "dc:title", container.getTitle());
        if (container.getCreator() != null) {
            element(out, "dc:creator", container.getCreator());
        }
        writeClass(out, container.getClazz());
        out.append("</container>");
    }

    private static void writeItem(StringBuilder out, Item item) {
        if (item.getClazz() == null || item.getId() == null || item.getParentID() == null || item.getTitle() == null
                || item.getRefID() != null || item.getWriteStatus() != null || !item.getDescMetadata().isEmpty()) {
            throw UNSUPPORTED;
        }
        out.append("<item id=\"");
        attribute(out, item.getId());
        out.append("\" parentID=\"");
        attribute(out, item.getParentID());
        out.append("\" restricted=\"").append(item.isRestricted() ? '1' : '0').append("\">");
        element(out, "dc:title", item.getTitle());
        if (item.getCreator() != null) {
            element(out, "dc:creator", item.getCreator());
        }
        writeClass(out, item.getClazz());
        // the generator writes the upnp properties, then the dc ones, then the sec ones; only dc:date is known
        List<DIDLObject.Property> properties = item.getProperties();
        for (int i = 0; i < properties.size(); i++) {
            DIDLObject.Property property = properties.get(i);
            if (property.getClass() != DIDLObject.Property.DC.DATE.class) {
                throw UNSUPPORTED;
            }
        }
        for (int i = 0; i < properties.size(); i++) {
            DIDLObject.Property property = properties.get(i);
            element(out, "dc:" + property.getDescriptorName(), property.toString());
        }
        for (Res resource : item.getResources()) {
            if (resource != null) {
                writeResource(out, resource);
            }
        }
        out.append("</item>");
    }

    private static void writeClass(StringBuilder out, DIDLObject.Class clazz) {
        out.append("<upnp:class");
        if (clazz.getFriendlyName() != null && clazz.getFriendlyName().length() > 0) {
            out.append(" name=\"");
            attribute(out, clazz.getFriendlyName());
            out.append('"');
        }
        out.append('>');
        text(out, clazz.getValue());
        out.append("</upnp:class>");
    }

    private static void writeResource(StringBuilder out, Res resource) {
        if (resource.getValue() == null || resource.getProtocolInfo() == null) {
            throw UNSUPPORTED;
        }
        out.append("<res protocolInfo=\"");
        attribute(out, resource.getProtocolInfo().toString());
        out.append('"');
        // the order of DIDLParser.generateResource
        if (resource.getImportUri() != null) {
            attributeValue(out, "importUri", resource.getImportUri().toString());
        }
        if (resource.getSize() != null) {
            attributeValue(out, "size", resource.getSize().toString());
        }
        if (resource.getDuration() != null) {
            attributeValue(out, "duration", resource.getDuration());
        }
        if (resource.getBitrate() != null) {
            attributeValue(out, "bitrate", resource.getBitrate().toString());
        }
        if (resource.getSampleFrequency() != null) {
            attributeValue(out, "sampleFrequency", resource.getSampleFrequency().toString());
        }
        if (resource.getBitsPerSample() != null) {
            attributeValue(out, "bitsPerSample", resource.getBitsPerSample().toString());
        }
        if (resource.getNrAudioChannels() != null) {
            attributeValue(out, "nrAudioChannels", resource.getNrAudioChannels().toString());
        }
        if (resource.getColorDepth() != null) {
            attributeValue(out, "colorDepth", resource.getColorDepth().toString());
        }
        if (resource.getProtection() != null) {
            attributeValue(out, "protection", resource.getProtection());
        }
        if (resource.getResolution() != null) {
            attributeValue(out, "resolution", resource.getResolution());
        }
        out.append('>');
        text(out, resource.getValue());
        out.append("</res>");
    }

    private static void element(StringBuilder out, String name, String value) {
        out.append('<').append(name).append('>');
        text(out, value);
        out.append("</").append(name).append('>');
    }

    private static void attributeValue(StringBuilder out, String name, String value) {
        out.append(' ').append(name).append("=\"");
        attribute(out, value);
        out.append('"');
    }

    /** Text content: {@code & < >} are escaped. */
    private static void text(StringBuilder out, String value) {
        escape(out, value, false);
    }

    /** Attribute value: {@code & < > "} are escaped. */
    private static void attribute(StringBuilder out, String value) {
        escape(out, value, true);
    }

    private static void escape(StringBuilder out, String value, boolean quotes) {
        int length = value.length();
        int plainFrom = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            String replacement = null;
            if (c == '&') {
                replacement = "&amp;";
            } else if (c == '<') {
                replacement = "&lt;";
            } else if (c == '>') {
                replacement = "&gt;";
            } else if (c == '"' && quotes) {
                replacement = "&quot;";
            } else if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || (c >= 0xD800 && c <= 0xDFFF)
                    || c == 0x2028 || c == 0x2029 || c == 0xFFFE || c == 0xFFFF) {
                throw UNSUPPORTED;
            }
            if (replacement != null) {
                out.append(value, plainFrom, i).append(replacement);
                plainFrom = i + 1;
            }
        }
        out.append(value, plainFrom, length);
    }

    /** The number of bytes of the string in UTF-8 (the size of the answer on the wire), without making the bytes. */
    static int utf8Length(String value) {
        int bytes = 0;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                    bytes += 4;
                    i++;
                } else {
                    bytes++; // a lone surrogate is encoded as '?'
                }
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
