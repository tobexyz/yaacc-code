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
package de.yaacc.openhome;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Reads the small XML texts a device sends (source list, track list). The text comes from the network: no DTDs, size limit. */
final class OhXml {
    /** The most characters of an XML text that is read. */
    static final int MAX_LENGTH = 2 * 1024 * 1024;
    /** The texts read here are three levels deep; some parsers (Android's) use the stack for every level. */
    static final int MAX_DEPTH = 16;

    private OhXml() {
    }

    /** @return the document, null for text that is empty, too long, has a DTD or is not XML */
    static Document parse(String xml) {
        if (xml == null || xml.isEmpty() || xml.length() > MAX_LENGTH) {
            return null;
        }
        String lower = xml.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity") || tooDeep(xml, MAX_DEPTH)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // not every parser knows the feature; the text was checked above
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /**
     * Whether elements are nested deeper than {@code max}. Counted on the text before a parser sees it, because a parser that
     * builds the document recursively would run out of stack on a hostile answer. Comments, processing instructions and CDATA
     * sections are skipped; text that is not well formed is the parser's business.
     */
    static boolean tooDeep(String xml, int max) {
        int depth = 0;
        int i = 0;
        int length = xml.length();
        while (i < length) {
            int open = xml.indexOf('<', i);
            if (open < 0) {
                return false;
            }
            if (xml.startsWith("<!--", open)) {
                int end = xml.indexOf("-->", open + 4);
                i = end < 0 ? length : end + 3;
            } else if (xml.startsWith("<![CDATA[", open)) {
                int end = xml.indexOf("]]>", open + 9);
                i = end < 0 ? length : end + 3;
            } else if (xml.startsWith("<?", open)) {
                int end = xml.indexOf("?>", open + 2);
                i = end < 0 ? length : end + 2;
            } else {
                // a tag: ends at the first '>' outside quotes
                char quote = 0;
                int end = open + 1;
                for (; end < length; end++) {
                    char c = xml.charAt(end);
                    if (quote != 0) {
                        if (c == quote) {
                            quote = 0;
                        }
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '>') {
                        break;
                    }
                }
                if (end >= length) {
                    return false;
                }
                boolean closing = open + 1 < length && xml.charAt(open + 1) == '/';
                boolean selfClosing = xml.charAt(end - 1) == '/';
                if (closing) {
                    depth--;
                } else if (!selfClosing) {
                    depth++;
                    if (depth > max) {
                        return true;
                    }
                }
                i = end + 1;
            }
        }
        return false;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && ((Element) node).getTagName().equals(name)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /**
     * The text of the first child element of that name: only its own text nodes, not the text of elements inside it (the text of
     * a hostile answer may be nested thousands of levels deep, and reading it recursively would overflow the stack).
     */
    static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        if (found.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        NodeList nodes = found.get(0).getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString().trim();
    }
}
