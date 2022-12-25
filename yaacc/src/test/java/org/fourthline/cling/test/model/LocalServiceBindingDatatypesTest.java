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

package org.fourthline.cling.test.model;

import static org.junit.Assert.assertEquals;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.fourthline.cling.binding.LocalServiceBinder;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.types.Datatype;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.test.data.SampleData;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/**
 * @author Christian Bauer
 */

@RunWith(DataProviderRunner.class)
public class LocalServiceBindingDatatypesTest {

    public static LocalDevice createTestDevice(LocalService service) throws Exception {
        return new LocalDevice(
                SampleData.createLocalDeviceIdentity(),
                new UDADeviceType("TestDevice", 1),
                new DeviceDetails("Test Device"),
                service
        );
    }

    @DataProvider
    public static Object[][] devices() throws Exception {

        // This is what we are actually testing
        LocalServiceBinder binder = new AnnotationLocalServiceBinder();

        return new LocalDevice[][]{
                {createTestDevice(binder.read(TestServiceOne.class))},
        };
    }

    @Test
    @UseDataProvider("devices")
    public void validateBinding(LocalDevice device) {

        LocalService svc = SampleData.getFirstService(device);

        //System.out.println("############################################################################");
        //ServiceDescriptorBinder binder = new DefaultRouterConfiguration().getServiceDescriptorBinderUDA10();
        //System.out.println(binder.generate(svc));
        //System.out.println("############################################################################");

        assertEquals(svc.getStateVariables().length, 1);
        assertEquals(svc.getStateVariable("Data").getTypeDetails().getDatatype().getBuiltin(), Datatype.Builtin.BIN_BASE64);
        assertEquals(svc.getStateVariable("Data").getEventDetails().isSendEvents(), false);

        assertEquals(svc.getActions().length, 1);

        assertEquals(svc.getAction("GetData").getName(), "GetData");
        assertEquals(svc.getAction("GetData").getArguments().length, 1);
        assertEquals(svc.getAction("GetData").getArguments()[0].getName(), "RandomData");
        assertEquals(svc.getAction("GetData").getArguments()[0].getDirection(), ActionArgument.Direction.OUT);
        assertEquals(svc.getAction("GetData").getArguments()[0].getRelatedStateVariableName(), "Data");
        assertEquals(svc.getAction("GetData").getArguments()[0].isReturnValue(), true);

    }

    /* ####################################################################################################### */

    @UpnpService(
            serviceId = @UpnpServiceId("SomeService"),
            serviceType = @UpnpServiceType(value = "SomeService", version = 1),
            supportsQueryStateVariables = false
    )
    public static class TestServiceOne {

        @UpnpStateVariable(sendEvents = false)
        private byte[] data;

        public TestServiceOne() {
            data = new byte[8];
            new Random().nextBytes(data);
        }

        @UpnpAction(out = @UpnpOutputArgument(name = "RandomData"))
        public byte[] getData() {
            return data;
        }
    }


}
