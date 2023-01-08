/*
 * Copyright (C) 2018 Matthew Piggott
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
package org.seamless.test.xml;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.seamless.xml.ParserException;
import org.seamless.xml.SAXParser;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Matthew Piggott
 */
public class SAXParserTest {

    @Test
    public void testXxe() throws IOException {
        SAXParser parser = new SAXParser();

        InputStream in = null;
        try {
            in = getClass().getResourceAsStream("/org/seamless/test/xml/xxe.xml");
            parser.parse(new InputSource(in));
            fail("Expected exception thrown");
        } catch (ParserException e) {
            assertTrue(e.getMessage().contains("DOCTYPE"));
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }
}
