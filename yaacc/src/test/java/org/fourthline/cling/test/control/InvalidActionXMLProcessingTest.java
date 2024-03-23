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

package org.fourthline.cling.test.control;

import static org.junit.Assert.assertEquals;
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
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.control.IncomingActionRequestMessage;
import org.fourthline.cling.model.message.control.IncomingActionResponseMessage;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.SoapActionHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.types.SoapActionType;
import org.fourthline.cling.test.data.SampleData;
import org.fourthline.cling.transport.impl.PullSOAPActionProcessorImpl;
import org.fourthline.cling.transport.impl.RecoveringSOAPActionProcessorImpl;
import org.fourthline.cling.transport.spi.SOAPActionProcessor;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;

/**
 * @author Christian Bauer
 */

@RunWith(DataProviderRunner.class)
public class InvalidActionXMLProcessingTest {

    @DataProvider
    public static Object[][] invalidXMLFile() throws Exception {
        return new Object[][]{
                {"/invalidxml/control/request_missing_envelope.xml"},
                {"/invalidxml/control/request_missing_action_namespace.xml"},
                {"/invalidxml/control/request_invalid_action_namespace.xml"},
        };
    }

    @DataProvider
    public static Object[][] invalidRecoverableXMLFile() throws Exception {
        return new Object[][]{
                {"/invalidxml/control/request_no_entityencoding.xml"},
                {"/invalidxml/control/request_wrong_termination.xml"},
        };
    }

    @DataProvider
    public static Object[][] invalidUnrecoverableXMLFile() throws Exception {
        return new Object[][]{
                {"/invalidxml/control/unrecoverable/naim_unity.xml"},
        };
    }

    /* ############################## TEST FAILURE ############################ */

    @Test
    @UseDataProvider("invalidXMLFile")
    public void readRequestDefaultFailure(String invalidXMLFile) throws Exception {
        try {
            // This should always fail!
            readRequest(invalidXMLFile, new MockUpnpService());
            fail();
        } catch (UnsupportedDataException ex) {
            //ignore expected
        }
    }

    @Test
    @UseDataProvider("invalidRecoverableXMLFile")
    public void readRequestRecoverableFailure(String invalidXMLFile) throws Exception {
        try {
            // This should always fail!
            readRequest(invalidXMLFile, new MockUpnpService());
            fail();
        } catch (UnsupportedDataException e) {
            //ignore expected
        }
    }

    @Test
    @UseDataProvider("invalidUnrecoverableXMLFile")
    public void readRequestRecoveringFailure(String invalidXMLFile) throws Exception {
        try {
            // This should always fail!
            readRequest(
                    invalidXMLFile,
                    new MockUpnpService(new MockUpnpServiceConfiguration() {
                        @Override
                        public SOAPActionProcessor getSoapActionProcessor() {
                            return new RecoveringSOAPActionProcessorImpl();
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
    public void readRequestPull(String invalidXMLFile) throws Exception {
        readRequest(
                invalidXMLFile,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public SOAPActionProcessor getSoapActionProcessor() {
                        return new PullSOAPActionProcessorImpl();
                    }
                })
        );
    }

    @Test
    @UseDataProvider("invalidRecoverableXMLFile")
    public void readRequestRecovering(String invalidXMLFile) throws Exception {
        readRequest(
                invalidXMLFile,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public SOAPActionProcessor getSoapActionProcessor() {
                        return new RecoveringSOAPActionProcessorImpl();
                    }
                })
        );
    }

    @Test
    public void uppercaseOutputArguments() throws Exception {
        SOAPActionProcessor processor = new RecoveringSOAPActionProcessorImpl();
        ServiceDescriptorBinder binder = new UDA10ServiceDescriptorBinderImpl();

        RemoteService service = SampleData.createUndescribedRemoteService();
        service = binder.describe(
                service,
                readLines(getClass().getResourceAsStream("/descriptors/service/uda10_connectionmanager.xml"))
        );

        Action action = service.getAction("GetProtocolInfo");

        ActionInvocation actionInvocation = new ActionInvocation(action);
        StreamResponseMessage response = new StreamResponseMessage(
                readLines(getClass().getResourceAsStream("/invalidxml/control/response_uppercase_args.xml"))
        );

        processor.readBody(new IncomingActionResponseMessage(response), actionInvocation);
    }

    protected void readRequest(String invalidXMLFile, UpnpService upnpService) throws Exception {
        LocalDevice ld = ActionSampleData.createTestDevice(ActionSampleData.LocalTestServiceExtended.class);
        LocalService svc = ld.getServices()[0];

        Action action = svc.getAction("SetSomeValue");
        ActionInvocation actionInvocation = new ActionInvocation(action);

        StreamRequestMessage message = createRequestMessage(action, invalidXMLFile);
        IncomingActionRequestMessage request = new IncomingActionRequestMessage(message, svc);

        upnpService.getConfiguration().getSoapActionProcessor().readBody(request, actionInvocation);

        assertEquals(actionInvocation.getInput()[0].toString(), "foo&bar");
    }

    public StreamRequestMessage createRequestMessage(Action action, String xmlFile) throws Exception {
        StreamRequestMessage message =
                new StreamRequestMessage(UpnpRequest.Method.POST, URI.create("http://some.uri"));

        message.getHeaders().add(
                UpnpHeader.Type.CONTENT_TYPE,
                new ContentTypeHeader(ContentTypeHeader.DEFAULT_CONTENT_TYPE_UTF8)
        );
        message.getHeaders().add(
                UpnpHeader.Type.SOAPACTION,
                new SoapActionHeader(
                        new SoapActionType(
                                action.getService().getServiceType(),
                                action.getName()
                        )
                )
        );
        message.setBody(readLines(getClass().getResourceAsStream(xmlFile)));
        return message;
    }

    private String readLines(InputStream is) throws IOException {
        if (is == null) throw new IllegalArgumentException("Inputstream was null");

        BufferedReader inputReader;
        inputReader = new BufferedReader(
                new InputStreamReader(is)
        );

        StringBuilder input = new StringBuilder();
        String inputLine;
        while ((inputLine = inputReader.readLine()) != null) {
            input.append(inputLine).append(System.getProperty("line.separator"));
        }

        return input.length() > 0 ? input.toString() : "";
    }
}
