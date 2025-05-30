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

package org.fourthline.cling.test.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.binding.xml.DeviceDescriptorBinder;
import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderSAXImpl;
import org.fourthline.cling.mock.MockUpnpService;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.profile.RemoteClientInfo;
import org.fourthline.cling.test.data.SampleData;
import org.fourthline.cling.test.data.SampleDeviceRoot;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;


public class UDA10DeviceDescriptorParsingTest {

    @Test
    public void readUDA10DescriptorDOM() throws Exception {

        DeviceDescriptorBinder binder = new UDA10DeviceDescriptorBinderImpl();

        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        device = binder.describe(device, readLines(getClass().getResourceAsStream("/descriptors/device/uda10.xml")));

        SampleDeviceRoot.assertLocalResourcesMatch(
                new MockUpnpService().getConfiguration().getNamespace().getResources(device)
        );
        SampleDeviceRoot.assertMatch(device, SampleData.createRemoteDevice());

    }

    @Test
    public void readUDA10DescriptorSAX() throws Exception {

        DeviceDescriptorBinder binder = new UDA10DeviceDescriptorBinderSAXImpl();

        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        device = binder.describe(device, readLines(getClass().getResourceAsStream("/descriptors/device/uda10.xml")));

        SampleDeviceRoot.assertLocalResourcesMatch(
                new MockUpnpService().getConfiguration().getNamespace().getResources(device)
        );
        SampleDeviceRoot.assertMatch(device, SampleData.createRemoteDevice());

    }

    @Test
    public void writeUDA10Descriptor() throws Exception {

        MockUpnpService upnpService = new MockUpnpService();
        DeviceDescriptorBinder binder = new UDA10DeviceDescriptorBinderImpl();

        RemoteDevice device = SampleData.createRemoteDevice();
        String descriptorXml = binder.generate(
                device,
                new RemoteClientInfo(),
                upnpService.getConfiguration().getNamespace()
        );

/*
        System.out.println("#######################################################################################");
        System.out.println(descriptorXml);
        System.out.println("#######################################################################################");
*/

        RemoteDevice hydratedDevice = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        hydratedDevice = binder.describe(hydratedDevice, descriptorXml);

        SampleDeviceRoot.assertLocalResourcesMatch(
                upnpService.getConfiguration().getNamespace().getResources(hydratedDevice)

        );
        SampleDeviceRoot.assertMatch(hydratedDevice, device);

    }

    @Test
    public void writeUDA10DescriptorWithProvider() throws Exception {

        MockUpnpService upnpService = new MockUpnpService();
        DeviceDescriptorBinder binder = new UDA10DeviceDescriptorBinderImpl();

        LocalDevice device = SampleData.createLocalDevice(true);
        String descriptorXml = binder.generate(
                device,
                new RemoteClientInfo(),
                upnpService.getConfiguration().getNamespace()
        );


        //System.out.println("#######################################################################################");
        //System.out.println(descriptorXml);
        //System.out.println("#######################################################################################");


        RemoteDevice hydratedDevice = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        hydratedDevice = binder.describe(hydratedDevice, descriptorXml);

        SampleDeviceRoot.assertLocalResourcesMatch(
                upnpService.getConfiguration().getNamespace().getResources(hydratedDevice)

        );
        //SampleDeviceRoot.assertMatch(hydratedDevice, device, false);

    }

    @Test
    public void readUDA10DescriptorWithURLBase() throws Exception {
        MockUpnpService upnpService = new MockUpnpService();
        DeviceDescriptorBinder binder = upnpService.getConfiguration().getDeviceDescriptorBinderUDA10();

        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        device = binder.describe(
                device,
                readLines(getClass().getResourceAsStream("/descriptors/device/uda10_withbase.xml"))
        );

        assertEquals(
                device.normalizeURI(device.getDetails().getManufacturerDetails().getManufacturerURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "mfc.html"
        );
        assertEquals(
                device.normalizeURI(device.getDetails().getModelDetails().getModelURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "someotherbase/MY-DEVICE-123/model.html"
        );
        assertEquals(
                device.normalizeURI(device.getDetails().getPresentationURI()).toString(),
                "http://www.4thline.org/some_ui"
        );

        assertEquals(
                device.normalizeURI(device.getIcons()[0].getUri()).toString(),
                SampleData.getLocalBaseURL().toString() + "someotherbase/MY-DEVICE-123/icon.png"
        );

        assertEquals(device.normalizeURI(
                        device.getServices()[0].getDescriptorURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "someotherbase/MY-DEVICE-123/svc/upnp-org/MY-SERVICE-123/desc.xml"
        );
        assertEquals(
                device.normalizeURI(device.getServices()[0].getControlURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "someotherbase/MY-DEVICE-123/svc/upnp-org/MY-SERVICE-123/control"
        );
        assertEquals(
                device.normalizeURI(device.getServices()[0].getEventSubscriptionURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "someotherbase/MY-DEVICE-123/svc/upnp-org/MY-SERVICE-123/events"
        );

        assertTrue(device.isRoot());
    }

    @Test
    public void readUDA10DescriptorWithURLBase2() throws Exception {
        MockUpnpService upnpService = new MockUpnpService();
        DeviceDescriptorBinder binder = upnpService.getConfiguration().getDeviceDescriptorBinderUDA10();

        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        device = binder.describe(
                device,
                readLines(getClass().getResourceAsStream("/descriptors/device/uda10_withbase2.xml"))
        );

        assertEquals(
                device.normalizeURI(device.getDetails().getManufacturerDetails().getManufacturerURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "mfc.html"
        );

        assertEquals(
                device.normalizeURI(device.getDetails().getModelDetails().getModelURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "model.html"
        );
        assertEquals(
                device.normalizeURI(device.getDetails().getPresentationURI()).toString(),
                "http://www.4thline.org/some_ui"
        );

        assertEquals(
                device.normalizeURI(device.getIcons()[0].getUri()).toString(),
                SampleData.getLocalBaseURL().toString() + "icon.png"
        );

        assertEquals(device.normalizeURI(
                        device.getServices()[0].getDescriptorURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "svc.xml"
        );
        assertEquals(
                device.normalizeURI(device.getServices()[0].getControlURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "control"
        );
        assertEquals(
                device.normalizeURI(device.getServices()[0].getEventSubscriptionURI()).toString(),
                SampleData.getLocalBaseURL().toString() + "events"
        );

        assertTrue(device.isRoot());
    }

    @Test
    public void readUDA10DescriptorWithEmptyURLBase() throws Exception {
        DeviceDescriptorBinder binder = new UDA10DeviceDescriptorBinderImpl();

        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        device = binder.describe(device, readLines(getClass().getResourceAsStream("/descriptors/device/uda10_emptybase.xml")));

        SampleDeviceRoot.assertLocalResourcesMatch(
                new MockUpnpService().getConfiguration().getNamespace().getResources(device)
        );
        SampleDeviceRoot.assertMatch(device, SampleData.createRemoteDevice());
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

