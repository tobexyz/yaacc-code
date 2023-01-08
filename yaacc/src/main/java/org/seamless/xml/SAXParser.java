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

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * @author Christian Bauer
 */
public class SAXParser {

    final private static Logger log = Logger.getLogger(SAXParser.class.getName());

    public static final URI XML_SCHEMA_NAMESPACE =
            URI.create("http://www.w3.org/2001/xml.xsd");
    public static final URL XML_SCHEMA_RESOURCE =
            Thread.currentThread().getContextClassLoader().getResource("org/seamless/schemas/xml.xsd");

    final private XMLReader xr;

    public SAXParser() {
        this(null);
    }

    public SAXParser(DefaultHandler handler) {
        this.xr = create();
        if (handler != null)
            xr.setContentHandler(handler);
    }

    public void setContentHandler(ContentHandler handler) {
        xr.setContentHandler(handler);
    }

    protected XMLReader create() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();

            // Configure factory to prevent XXE attacks
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            }catch(SAXNotRecognizedException ex){
                log.info("Feature disallow-doctype-decl not known");
            }
            try {
                factory.setXIncludeAware(false);
            }catch(UnsupportedOperationException usex){
                log.info("can't set factory.setXIncludeAware(false);");
            }
            factory.setNamespaceAware(true);

            if (getSchemaSources() != null) {
                factory.setSchema(createSchema(getSchemaSources()));
            }

            XMLReader xmlReader = factory.newSAXParser().getXMLReader();
            xmlReader.setErrorHandler(getErrorHandler());
            return xmlReader;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected Schema createSchema(Source[] schemaSources) {
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setResourceResolver(new CatalogResourceResolver(
                    new HashMap<URI, URL>() {{
                        put(XML_SCHEMA_NAMESPACE, XML_SCHEMA_RESOURCE);
                    }}
            ));
            return schemaFactory.newSchema(schemaSources);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected Source[] getSchemaSources() {
        return null;
    }

    protected ErrorHandler getErrorHandler() {
        return new SimpleErrorHandler();
    }

    public void parse(InputSource source) throws ParserException {
        try {
            xr.parse(source);
        } catch (Exception ex) {
            throw new ParserException(ex);
        }
    }

    /**
     * Always throws exceptions and stops parsing.
     */
    public class SimpleErrorHandler implements ErrorHandler {
        public void warning(SAXParseException e) throws SAXException {
            throw new SAXException(e);
        }

        public void error(SAXParseException e) throws SAXException {
            throw new SAXException(e);
        }

        public void fatalError(SAXParseException e) throws SAXException {
            throw new SAXException(e);
        }
    }


    public static class Handler<I> extends DefaultHandler {

        protected SAXParser parser;
        protected I instance;
        protected Handler parent;
        protected StringBuilder characters = new StringBuilder();
        protected Attributes attributes;

        public Handler(I instance) {
            this(instance, null, null);
        }

        public Handler(I instance, SAXParser parser) {
            this(instance, parser, null);
        }

        public Handler(I instance, Handler parent) {
            this(instance, parent.getParser(), parent);
        }

        public Handler(I instance, SAXParser parser, Handler parent) {
            this.instance = instance;
            this.parser = parser;
            this.parent = parent;
            if (parser != null) {
                parser.setContentHandler(this);
            }
        }

        public I getInstance() {
            return instance;
        }

        public SAXParser getParser() {
            return parser;
        }

        public Handler getParent() {
            return parent;
        }

        protected void switchToParent() {
            if (parser != null && parent != null) {
                parser.setContentHandler(parent);
                attributes = null;
            }
        }

        public String getCharacters() {
            return characters.toString();
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            this.characters = new StringBuilder();
            this.attributes = new AttributesImpl(attributes); // see http://docstore.mik.ua/orelly/xml/sax2/ch05_01.htm, section 5.1.1
            log.finer(getClass().getSimpleName() + " starting: " + localName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            characters.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName,
                               String qName) throws SAXException {

            if (isLastElement(uri, localName, qName)) {
                log.finer(getClass().getSimpleName() + ": last element, switching to parent: " + localName);
                switchToParent();
                return;
            }

            log.finer(getClass().getSimpleName() + " ending: " + localName);
        }

        protected boolean isLastElement(String uri, String localName, String qName) {
            return false;
        }

        protected Attributes getAttributes() {
            return attributes;
        }
    }

}
