/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
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

package org.fourthline.cling.transport.impl;

import android.util.Log;

import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.message.gena.IncomingEventRequestMessage;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.state.StateVariableValue;
import org.seamless.xml.XmlPullParserUtils;
import org.xmlpull.v1.XmlPullParser;

import jakarta.enterprise.inject.Alternative;

/**
 * Implementation based on the <em>Xml Pull Parser</em> XML processing API.
 * <p>
 * This processor is more lenient with parsing, looking only for the required XML tags.
 * </p>
 * <p>
 * To use this parser you need to install an implementation of the
 * <a href="http://www.xmlpull.org/impls.shtml">XMLPull API</a>.
 * </p>
 *
 * @author Michael Pujos
 */
@Alternative
public class PullGENAEventProcessorImpl extends GENAEventProcessorImpl {


    public void readBody(IncomingEventRequestMessage requestMessage) throws UnsupportedDataException {
        Log.d(getClass().getName(), "Reading body of: " + requestMessage);
        Log.v(getClass().getName(), "===================================== GENA BODY BEGIN ============================================");
        Log.v(getClass().getName(), requestMessage.getBody() != null ? requestMessage.getBody().toString() : null);
        Log.v(getClass().getName(), "-===================================== GENA BODY END ============================================");


        String body = getMessageBody(requestMessage);
        try {
            XmlPullParser xpp = XmlPullParserUtils.createParser(body);
            readProperties(xpp, requestMessage);
        } catch (Exception ex) {
            throw new UnsupportedDataException("Can't transform message payload: " + ex.getMessage(), ex, body);
        }
    }

    protected void readProperties(XmlPullParser xpp, IncomingEventRequestMessage message) throws Exception {
        // We're inside the propertyset tag
        StateVariable[] stateVariables = message.getService().getStateVariables();
        int event;
        while ((event = xpp.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            if (xpp.getName().equals("property")) {
                readProperty(xpp, message, stateVariables);
            }
        }
    }

    protected void readProperty(XmlPullParser xpp, IncomingEventRequestMessage message, StateVariable[] stateVariables) throws Exception {
        // We're inside the property tag
        int event;
        do {
            event = xpp.next();
            if (event == XmlPullParser.START_TAG) {

                String stateVariableName = xpp.getName();
                for (StateVariable stateVariable : stateVariables) {
                    if (stateVariable.getName().equals(stateVariableName)) {
                        Log.d(getClass().getName(), "Reading state variable value: " + stateVariableName);
                        String value = xpp.nextText();
                        message.getStateVariableValues().add(new StateVariableValue(stateVariable, value));
                        break;
                    }
                }
            }
        } while (event != XmlPullParser.END_DOCUMENT && (event != XmlPullParser.END_TAG || !xpp.getName().equals("property")));
    }
}
