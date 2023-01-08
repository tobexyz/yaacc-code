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

package org.fourthline.cling.test.gena;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder;
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderImpl;
import org.fourthline.cling.mock.MockUpnpService;
import org.fourthline.cling.mock.MockUpnpServiceConfiguration;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpMessage.BodyType;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.gena.IncomingEventRequestMessage;
import org.fourthline.cling.model.message.gena.OutgoingEventRequestMessage;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.test.data.SampleData;
import org.fourthline.cling.transport.impl.PullGENAEventProcessorImpl;
import org.fourthline.cling.transport.impl.RecoveringGENAEventProcessorImpl;
import org.fourthline.cling.transport.spi.GENAEventProcessor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.seamless.util.io.IO;
import org.seamless.xml.XmlPullParserUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


@RunWith(DataProviderRunner.class)
public class InvalidEventXMLProcessingTest {

    @DataProvider
    public static Object[][] invalidXMLFile() throws Exception {
        return new String[][]{
                {"/invalidxml/event/invalid_root_element.xml"},
        };
    }

    @DataProvider
    public static Object[][] invalidRecoverableXMLFile() throws Exception {
        return new String[][]{
                {"/invalidxml/event/truncated.xml"},
                {"/invalidxml/event/orange_liveradio.xml"},
        };
    }

    // TODO: Shouldn't these be failures of the LastChangeParser?
    // The GENA parser does the right thing for most of them, no?
    @DataProvider
    public static Object[][] invalidUnrecoverableXMLFile() throws Exception {
        return new String[][]{
                {"/invalidxml/event/unrecoverable/denon_avr4306.xml"},
                {"/invalidxml/event/unrecoverable/philips_np2900.xml"},
                {"/invalidxml/event/unrecoverable/philips_sla5220.xml"},
                {"/invalidxml/event/unrecoverable/terratec_noxon2.xml"},
                {"/invalidxml/event/unrecoverable/marantz_mcr603.xml"},
                {"/invalidxml/event/unrecoverable/teac_wap4500.xml"},
                {"/invalidxml/event/unrecoverable/technisat_digi_hd8+.xml"},
        };
    }

    /* ############################## TEST FAILURE ############################ */

    @Test
    @UseDataProvider("invalidXMLFile")
    public void readDefaultFailure(String invalidXMLFile) throws Exception {
        try {
            // This should always fail!
            read(invalidXMLFile, new MockUpnpService());
            fail();
        } catch (UnsupportedDataException ex) {
            //ignore expected
        }
    }

    @Test
    @UseDataProvider("invalidRecoverableXMLFile")
    public void readRecoverableFailure(String invalidXMLFile) throws Exception {
        try {
            // This should always fail!
            read(invalidXMLFile, new MockUpnpService());
            fail();
        } catch (UnsupportedDataException e) {
            //ignore expected
        }
    }

    @Test
    @UseDataProvider("invalidUnrecoverableXMLFile")
    public void readRecoveringFailure(String invalidXMLFile) {
        try {
            // This should always fail!
            read(
                    invalidXMLFile,
                    new MockUpnpService(new MockUpnpServiceConfiguration() {
                        @Override
                        public GENAEventProcessor getGenaEventProcessor() {
                            return new RecoveringGENAEventProcessorImpl();
                        }
                    })
            );
            fail();
        } catch (Exception e) {
            //ignore expected
        }
    }

    /* ############################## TEST SUCCESS ############################ */

    @Test
    @UseDataProvider("invalidXMLFile")
    public void readPull(String invalidXMLFile) throws Exception {
        read(
                invalidXMLFile,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public GENAEventProcessor getGenaEventProcessor() {
                        return new PullGENAEventProcessorImpl();
                    }
                })
        );
    }

    @Test
    @UseDataProvider("invalidRecoverableXMLFile")
    public void readRecovering(String invalidXMLFile) throws Exception {
        read(
                invalidXMLFile,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public GENAEventProcessor getGenaEventProcessor() {
                        return new RecoveringGENAEventProcessorImpl();
                    }
                })
        );
    }

    protected void read(String invalidXMLFile, UpnpService upnpService) throws Exception {
        ServiceDescriptorBinder binder = new UDA10ServiceDescriptorBinderImpl();
        RemoteService service = SampleData.createUndescribedRemoteService();
        service = binder.describe(service, IO.readLines(
                getClass().getResourceAsStream("/descriptors/service/uda10_avtransport.xml"))
        );

        RemoteGENASubscription subscription = new RemoteGENASubscription(service, 1800) {
            public void failed(UpnpResponse responseStatus) {
            }

            public void ended(CancelReason reason, UpnpResponse responseStatus) {
            }

            public void eventsMissed(int numberOfMissedEvents) {
            }

            public void established() {
            }

            public void eventReceived() {
            }

            public void invalidMessage(UnsupportedDataException ex) {
            }
        };
        subscription.receive(new UnsignedIntegerFourBytes(0), new ArrayList<StateVariableValue>());

        OutgoingEventRequestMessage outgoingCall =
                new OutgoingEventRequestMessage(subscription, SampleData.getLocalBaseURL());

        upnpService.getConfiguration().getGenaEventProcessor().writeBody(outgoingCall);

        StreamRequestMessage incomingStream = new StreamRequestMessage(outgoingCall);

        IncomingEventRequestMessage message = new IncomingEventRequestMessage(incomingStream, service);
        message.setBody(BodyType.STRING, IO.readLines(getClass().getResourceAsStream(invalidXMLFile)));

        upnpService.getConfiguration().getGenaEventProcessor().readBody(message);

        // All of the messages must have a LastChange state variable, and we should be able to parse
        // the XML value of that state variable
        boolean found = false;
        for (StateVariableValue stateVariableValue : message.getStateVariableValues()) {
            if (stateVariableValue.getStateVariable().getName().equals("LastChange")
                    && stateVariableValue.getValue() != null) {
                found = true;
                String lastChange = (String) stateVariableValue.getValue();
                Map<String, Object> lastChangeValues = parseLastChangeXML(lastChange);
                assertFalse(lastChangeValues.isEmpty());
                break;
            }
        }

        assertTrue(found);
    }

    protected Map<String, Object> parseLastChangeXML(String lastChange) throws Exception {
        // All we do here is trying to parse some XML looking for any element with a 'val' attribute
        Map<String, Object> values = new HashMap<>();
        XmlPullParser xpp = XmlPullParserUtils.createParser(lastChange);
        xpp.nextTag();
        int event;
        while ((event = xpp.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = xpp.getName();
            String value = xpp.getAttributeValue(null, "val");
            if (value == null)
                continue;
            values.put(tag, value);
        }
        return values;
    }
}
