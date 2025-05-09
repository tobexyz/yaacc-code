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

import static org.junit.Assert.fail;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.binding.xml.DescriptorBindingException;
import org.fourthline.cling.binding.xml.DeviceDescriptorBinder;
import org.fourthline.cling.binding.xml.RecoveringUDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderSAXImpl;
import org.fourthline.cling.mock.MockUpnpService;
import org.fourthline.cling.mock.MockUpnpServiceConfiguration;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.test.data.SampleData;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * @author Christian Bauer
 */

@RunWith(DataProviderRunner.class)
public class InvalidUDA10DeviceDescriptorParsingTest {

    @DataProvider
    public static Object[][] strict() throws Exception {
        return new Object[][]{
                {"/invalidxml/device/atb_miviewtv.xml"},
                {"/invalidxml/device/doubletwist.xml"},
                {"/invalidxml/device/eyetv_netstream_sat.xml"},
                {"/invalidxml/device/makemkv.xml"},
                {"/invalidxml/device/tpg.xml"},
                {"/invalidxml/device/ceton_infinitv.xml"},
                {"/invalidxml/device/zyxel_miviewtv.xml"},
                {"/invalidxml/device/perfectwave.xml"},
                {"/invalidxml/device/escient.xml"},
                {"/invalidxml/device/eyecon.xml"},
                {"/invalidxml/device/kodak.xml"},
                {"/invalidxml/device/plutinosoft.xml"},
                {"/invalidxml/device/samsung.xml"},
                {"/invalidxml/device/philips_hue.xml"},
        };
    }

    @DataProvider
    public static Object[][] recoverable() throws Exception {
        return new Object[][]{
                {"/invalidxml/device/missing_namespaces.xml"},
                {"/invalidxml/device/ushare.xml"},
                {"/invalidxml/device/lg.xml"},
                {"/invalidxml/device/readydlna.xml"},
        };
    }

    @DataProvider
    public static Object[][] unrecoverable() throws Exception {
        return new Object[][]{
                {"/invalidxml/device/unrecoverable/pms.xml"},
                {"/invalidxml/device/unrecoverable/awox.xml"},
                {"/invalidxml/device/philips.xml"},
                {"/invalidxml/device/simplecenter.xml"},
                {"/invalidxml/device/ums.xml"},
        };
    }

    /* ############################## TEST FAILURE ############################ */

    @Test
    @UseDataProvider("recoverable")
    public void readFailure(String recoverable) throws Exception {
        try {
            readDevice(recoverable, new MockUpnpService());
            fail();
        } catch (DescriptorBindingException ex) {
            //ignore expected
        }
    }

    @Test
    @UseDataProvider("unrecoverable")
    public void readRecoveringFailure(String unrecoverable) {
        try {
            readDevice(
                    unrecoverable,
                    new MockUpnpService(new MockUpnpServiceConfiguration() {
                        @Override
                        public DeviceDescriptorBinder getDeviceDescriptorBinderUDA10() {
                            return new RecoveringUDA10DeviceDescriptorBinderImpl();
                        }
                    })
            );
            fail();
        } catch (Exception ex) {
            //ignore expected
        }
    }

    /* ############################## TEST SUCCESS ############################ */

    @Test
    @UseDataProvider("strict")
    public void readDefault(String strict) throws Exception {
        readDevice(strict, new MockUpnpService());
    }

    @Test
    @UseDataProvider("strict")
    public void readSAX(String strict) throws Exception {
        readDevice(
                strict,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public DeviceDescriptorBinder getDeviceDescriptorBinderUDA10() {
                        return new UDA10DeviceDescriptorBinderSAXImpl();
                    }
                })
        );
    }

    @Test
    @UseDataProvider("strict")
    public void readRecoveringStrict(String strict) throws Exception {
        readDevice(
                strict,
                new MockUpnpService(new MockUpnpServiceConfiguration() {
                    @Override
                    public DeviceDescriptorBinder getDeviceDescriptorBinderUDA10() {
                        return new RecoveringUDA10DeviceDescriptorBinderImpl();
                    }
                })
        );
    }


    protected void readDevice(String invalidXMLFile, UpnpService upnpService) throws Exception {
        RemoteDevice device = new RemoteDevice(SampleData.createRemoteDeviceIdentity());
        upnpService.getConfiguration().getDeviceDescriptorBinderUDA10()
                .describe(device, readLines(getClass().getResourceAsStream(invalidXMLFile)));
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

