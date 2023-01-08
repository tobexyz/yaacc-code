 /*
 * Copyright (C) 2012 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.seamless.xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.logging.Logger;

/**
 * @author Michael Pujos
 */
public class XmlPullParserUtils {

    final private static Logger log = Logger.getLogger(XmlPullParserUtils.class.getName());

    static XmlPullParserFactory xmlPullParserFactory;

    static {
        try {
            xmlPullParserFactory = XmlPullParserFactory.newInstance();
            xmlPullParserFactory.setNamespaceAware(true);
        } catch (XmlPullParserException e) {
            log.severe("cannot create XmlPullParserFactory instance: " + e);
        }
    }

    static public XmlPullParser createParser(String xml) throws XmlPullParserException {
        XmlPullParser xpp = createParser();
        InputStream is;
        try {
            is = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new XmlPullParserException("UTF-8: unsupported encoding");
        }
        xpp.setInput(is, "UTF-8");
        return xpp;
    }

    static public XmlPullParser createParser() throws XmlPullParserException {
        if (xmlPullParserFactory == null) throw new XmlPullParserException("no XML Pull parser factory");
        return xmlPullParserFactory.newPullParser();
    }

    static public XmlSerializer createSerializer() throws XmlPullParserException {
        if (xmlPullParserFactory == null) throw new XmlPullParserException("no XML Pull parser factory");
        return xmlPullParserFactory.newSerializer();
    }

    static public void setSerializerIndentation(XmlSerializer serializer, int indent) {

        if (indent > 0) {
            try {
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                //serializer.setProperty("http://xmlpull.org/v1/doc/properties.html#serializer-indentation", StringUtils.repeat(' ', indent));
            } catch (Exception e) {
                log.warning("error setting feature of XmlSerializer: " + e);
            }

        }
    }

    public static void skipTag(XmlPullParser xpp, String tag) throws IOException, XmlPullParserException {
        int event;
        do {
            event = xpp.next();
        } while (event != XmlPullParser.END_DOCUMENT && (event != XmlPullParser.END_TAG || !xpp.getName().equals(tag)));
    }

    static public void searchTag(XmlPullParser xpp, String tag) throws IOException, XmlPullParserException {

        int event;
        while ((event = xpp.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && xpp.getName().equals(tag)) return;
        }

        throw new IOException(String.format("tag '%s' not found", tag));
    }


    public static void serializeIfNotNullOrEmpty(XmlSerializer serializer, String ns, String tag, String value) throws Exception {

        if (isNullOrEmpty(value)) return;

        serializer.startTag(ns, tag);
        serializer.text(value);
        serializer.endTag(ns, tag);
    }

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.length() == 0;
    }

    public static void serializeIfNotEqual(XmlSerializer serializer, String ns, String tag, Object value, Object forbiddenValue) throws Exception {
        if (value == null || value.equals(forbiddenValue)) return;

        serializer.startTag(ns, tag);
        serializer.text(value.toString());
        serializer.endTag(ns, tag);
    }

    public static String fixXMLEntities(String xml) {

        StringBuilder fixedXml = new StringBuilder(xml.length());

        boolean isFixed = false;

        for (int i = 0; i < xml.length(); i++) {

            char c = xml.charAt(i);
            if (c == '&') {
                // will not detect all possibly valid entities but should be sufficient for the purpose
                String sub = xml.substring(i, Math.min(i + 10, xml.length()));
                if (!sub.startsWith("&#") && !sub.startsWith("&lt;") && !sub.startsWith("&gt;") && !sub.startsWith("&amp;") &&
                    !sub.startsWith("&apos;") && !sub.startsWith("&quot;")) {
                    isFixed = true;
                    fixedXml.append("&amp;");
                } else {
                    fixedXml.append(c);
                }
            } else {
                fixedXml.append(c);
            }
        }

        if (isFixed) {
            log.warning("fixed badly encoded entities in XML");
        }

        return fixedXml.toString();
    }

}
