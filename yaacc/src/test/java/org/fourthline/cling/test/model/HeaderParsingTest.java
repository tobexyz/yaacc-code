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
import static org.junit.Assert.fail;

import org.fourthline.cling.model.Constants;
import org.fourthline.cling.model.ServerClientTokens;
import org.fourthline.cling.model.message.header.CallbackHeader;
import org.fourthline.cling.model.message.header.ContentRangeHeader;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.DeviceTypeHeader;
import org.fourthline.cling.model.message.header.DeviceUSNHeader;
import org.fourthline.cling.model.message.header.EXTHeader;
import org.fourthline.cling.model.message.header.EventSequenceHeader;
import org.fourthline.cling.model.message.header.HostHeader;
import org.fourthline.cling.model.message.header.InterfaceMacHeader;
import org.fourthline.cling.model.message.header.InvalidHeaderException;
import org.fourthline.cling.model.message.header.LocationHeader;
import org.fourthline.cling.model.message.header.MANHeader;
import org.fourthline.cling.model.message.header.MXHeader;
import org.fourthline.cling.model.message.header.MaxAgeHeader;
import org.fourthline.cling.model.message.header.NTSHeader;
import org.fourthline.cling.model.message.header.PragmaHeader;
import org.fourthline.cling.model.message.header.RangeHeader;
import org.fourthline.cling.model.message.header.RootDeviceHeader;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.message.header.ServerHeader;
import org.fourthline.cling.model.message.header.ServiceTypeHeader;
import org.fourthline.cling.model.message.header.ServiceUSNHeader;
import org.fourthline.cling.model.message.header.SoapActionHeader;
import org.fourthline.cling.model.message.header.SubscriptionIdHeader;
import org.fourthline.cling.model.message.header.TimeoutHeader;
import org.fourthline.cling.model.message.header.UDADeviceTypeHeader;
import org.fourthline.cling.model.message.header.UDAServiceTypeHeader;
import org.fourthline.cling.model.message.header.UDNHeader;
import org.fourthline.cling.model.message.header.USNRootDeviceHeader;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.NamedDeviceType;
import org.fourthline.cling.model.types.NamedServiceType;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.junit.Test;
import org.seamless.util.MimeType;

import java.net.URI;
import java.util.Locale;


public class HeaderParsingTest {

    @Test
    public void parseContentTypeHeader() {
        ContentTypeHeader header = new ContentTypeHeader(MimeType.valueOf("foo/bar;charset=\"utf-8\""));
        assertEquals(header.getString(), "foo/bar;charset=\"utf-8\"");
    }

    @Test
    public void parseDeviceType() {
        DeviceType deviceType = DeviceType.valueOf("urn:foo-bar:device:MyDeviceType:123");
        assertEquals(deviceType.getNamespace(), "foo-bar");
        assertEquals(deviceType.getType(), "MyDeviceType");
        assertEquals(deviceType.getVersion(), 123);
    }

    @Test
    public void parseUDADeviceType() {
        UDADeviceType deviceType = (UDADeviceType) DeviceType.valueOf("urn:schemas-upnp-org:device:MyDeviceType:123");
        assertEquals(deviceType.getType(), "MyDeviceType");
        assertEquals(deviceType.getVersion(), 123);
    }

    @Test
    public void parseInvalidDeviceTypeHeader() {
        DeviceTypeHeader header = new DeviceTypeHeader();
        header.setString("urn:foo-bar:device:!@#:123");
        assertEquals(header.getValue().getNamespace(), "foo-bar");
        assertEquals(header.getValue().getType(), "---");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:foo-bar:device:---:123");
    }

    @Test
    public void parseDeviceTypeHeaderURI() {
        DeviceTypeHeader header = new DeviceTypeHeader(URI.create("urn:schemas-upnp-org:device:MyDeviceType:123"));
        assertEquals(header.getValue().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getType(), "MyDeviceType");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:schemas-upnp-org:device:MyDeviceType:123");

    }

    @Test
    public void parseDeviceUSNHeader() {
        DeviceUSNHeader header = new DeviceUSNHeader();
        header.setString("uuid:MY-DEVICE-123::urn:schemas-upnp-org:device:MY-DEVICE-TYPE:1");
        assertEquals(header.getValue().getUdn().getIdentifierString(), "MY-DEVICE-123");
        assert header.getValue().getDeviceType() instanceof UDADeviceType;
    }

    @Test
    public void parseDeviceUSNHeaderStatic() {
        DeviceUSNHeader header = new DeviceUSNHeader(
                NamedDeviceType.valueOf("uuid:MY-DEVICE-123::urn:schemas-upnp-org:device:MY-DEVICE-TYPE:1")
        );
        assertEquals(header.getValue().getUdn().getIdentifierString(), "MY-DEVICE-123");
        assert header.getValue().getDeviceType() instanceof UDADeviceType;
    }

    @Test
    public void parseInvalidDeviceUSNHeader() {
        try {

            DeviceUSNHeader header = new DeviceUSNHeader();
            header.setString("uuid:MY-DEVICE-123--urn:schemas-upnp-org:device:MY-DEVICE-TYPE:1");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidEXTHeader() {
        try {
            EXTHeader header = new EXTHeader();
            header.setString("MUST BE EMPTY STRING");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseHostHeaderConstructor() {
        HostHeader header = new HostHeader("foo.bar", 1234);
        assertEquals(header.getValue().getHost(), "foo.bar");
        assertEquals(header.getValue().getPort(), 1234);

        header = new HostHeader(1234);
        assertEquals(header.getValue().getHost(), Constants.IPV4_UPNP_MULTICAST_GROUP);
        assertEquals(header.getValue().getPort(), 1234);
    }

    @Test
    public void parseHostHeader() {
        HostHeader header = new HostHeader();
        assertEquals(header.getValue().getHost(), Constants.IPV4_UPNP_MULTICAST_GROUP);
        assertEquals(header.getValue().getPort(), Constants.UPNP_MULTICAST_PORT);

        header = new HostHeader();
        header.setString("foo.bar:1234");
        assertEquals(header.getValue().getHost(), "foo.bar");
        assertEquals(header.getValue().getPort(), 1234);

        header = new HostHeader();
        header.setString("foo.bar");
        assertEquals(header.getValue().getHost(), "foo.bar");
        assertEquals(header.getValue().getPort(), Constants.UPNP_MULTICAST_PORT);
    }

    @Test
    public void parseInvalidHostHeader() {
        try {
            HostHeader header = new HostHeader();
            header.setString("foo.bar:abc");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidLocationHeader() {
        try {
            LocationHeader header = new LocationHeader();
            header.setString("this://is.not...a valid URL");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidMANHeader() {
        try {
            MANHeader header = new MANHeader("abc");
            header.setString("\"foo.bar\"; ns = baz"); // Not valid
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseMANHeaderNoNS() {
        MANHeader header = new MANHeader("abc");
        header.setString("\"foo.bar\"");
        assert header.getValue().equals("foo.bar");
        assert header.getNamespace() == null;
        assert header.getString().equals("\"foo.bar\"");
    }

    @Test
    public void parseMANHeaderNS() {
        MANHeader header = new MANHeader("abc");
        header.setString("\"foo.bar\"; ns =12");
        assert header.getValue().equals("foo.bar");
        assert header.getNamespace().equals("12");
        assert header.getString().equals("\"foo.bar\"; ns=12");
    }

    @Test
    public void parseMaxAgeHeader() {
        MaxAgeHeader header = new MaxAgeHeader();
        header.setString("max-age=1234, foobar=baz");
        assertEquals(header.getValue(), new Integer(1234));
    }

    @Test
    public void parseInvalidMaxAgeHeader() {
        try {
            MaxAgeHeader header = new MaxAgeHeader();
            header.setString("max-foo=123");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseMXHeader() {
        MXHeader header = new MXHeader();
        header.setString("111");
        assertEquals(header.getValue(), new Integer(111));

        header = new MXHeader();
        header.setString("123");
        assertEquals(header.getValue(), MXHeader.DEFAULT_VALUE);

    }

    @Test
    public void parseInvalidMXHeader() {
        try {
            MXHeader header = new MXHeader();
            header.setString("abc");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidNTSHeader() {
        try {
            NTSHeader header = new NTSHeader();
            header.setString("foo");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidRootDeviceHeader() {
        try {
            RootDeviceHeader header = new RootDeviceHeader();
            header.setString("upnp:foodevice");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseServerHeader() {
        ServerHeader header = new ServerHeader();
        header.setString("foo/1 UPnP/1.1 bar/2");
        assertEquals(header.getValue().getOsName(), "foo");
        assertEquals(header.getValue().getOsVersion(), "1");
        assertEquals(header.getValue().getProductName(), "bar");
        assertEquals(header.getValue().getProductVersion(), "2");
        assertEquals(header.getValue().getMajorVersion(), 1);
        assertEquals(header.getValue().getMinorVersion(), 1);

        // Commas...
        header = new ServerHeader();
        header.setString("foo/1, UPnP/1.1, bar/2");
        assertEquals(header.getValue().getOsName(), "foo");
        assertEquals(header.getValue().getOsVersion(), "1");
        assertEquals(header.getValue().getProductName(), "bar");
        assertEquals(header.getValue().getProductVersion(), "2");
        assertEquals(header.getValue().getMajorVersion(), 1);
        assertEquals(header.getValue().getMinorVersion(), 1);

        // Whitespace in tokens
        header = new ServerHeader();
        header.setString("foo baz/1 UPnP/1.1 bar abc/2");
        assertEquals(header.getValue().getOsName(), "foo baz");
        assertEquals(header.getValue().getOsVersion(), "1");
        assertEquals(header.getValue().getProductName(), "bar abc");
        assertEquals(header.getValue().getProductVersion(), "2");
        assertEquals(header.getValue().getMajorVersion(), 1);
        assertEquals(header.getValue().getMinorVersion(), 1);

        // Commas and whitespace!
        header = new ServerHeader();
        header.setString("foo baz/1, UPnP/1.1, bar abc/2");
        assertEquals(header.getValue().getOsName(), "foo baz");
        assertEquals(header.getValue().getOsVersion(), "1");
        assertEquals(header.getValue().getProductName(), "bar abc");
        assertEquals(header.getValue().getProductVersion(), "2");
        assertEquals(header.getValue().getMajorVersion(), 1);
        assertEquals(header.getValue().getMinorVersion(), 1);

        // Absolutely not valid!
        header = new ServerHeader();
        header.setString("foo/1 UPnP/1.");
        assertEquals(header.getValue().getOsName(), ServerClientTokens.UNKNOWN_PLACEHOLDER);
        assertEquals(header.getValue().getOsVersion(), ServerClientTokens.UNKNOWN_PLACEHOLDER);
        assertEquals(header.getValue().getProductName(), ServerClientTokens.UNKNOWN_PLACEHOLDER);
        assertEquals(header.getValue().getProductVersion(), ServerClientTokens.UNKNOWN_PLACEHOLDER);
        assertEquals(header.getValue().getMajorVersion(), 1);
        assertEquals(header.getValue().getMinorVersion(), 0); // Assume UDA 1.0

    }

    @Test
    public void parseInvalidServerHeader() {
        try {
            ServerHeader header = new ServerHeader();
            header.setString("foo/1 baz/123 bar/2");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseServiceType() {
        ServiceType serviceType = ServiceType.valueOf("urn:foo-bar:service:MyServiceType:123");
        assertEquals(serviceType.getNamespace(), "foo-bar");
        assertEquals(serviceType.getType(), "MyServiceType");
        assertEquals(serviceType.getVersion(), 123);
    }

    @Test
    public void parseUDAServiceType() {
        UDAServiceType serviceType = (UDAServiceType) ServiceType.valueOf("urn:schemas-upnp-org:service:MyServiceType:123");
        assertEquals(serviceType.getType(), "MyServiceType");
        assertEquals(serviceType.getVersion(), 123);
    }

    @Test
    public void parseInvalidServiceTypeHeader() {
        ServiceTypeHeader header = new ServiceTypeHeader();
        header.setString("urn:foo-bar:service:!@#:123");
        assertEquals(header.getValue().getNamespace(), "foo-bar");
        assertEquals(header.getValue().getType(), "---");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:foo-bar:service:---:123");
    }

    @Test
    public void parseServiceTypeHeaderURI() {
        ServiceTypeHeader header = new ServiceTypeHeader(URI.create("urn:schemas-upnp-org:service:MyServiceType:123"));
        assertEquals(header.getValue().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getType(), "MyServiceType");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:schemas-upnp-org:service:MyServiceType:123");
    }

    @Test
    public void parseServiceUSNHeader() {
        ServiceUSNHeader header = new ServiceUSNHeader();
        header.setString("uuid:MY-SERVICE-123::urn:schemas-upnp-org:service:MY-SERVICE-TYPE:1");
        assertEquals(header.getValue().getUdn().getIdentifierString(), "MY-SERVICE-123");
        assert header.getValue().getServiceType() instanceof UDAServiceType;
    }

    @Test
    public void parseServiceUSNHeaderStatic() {
        ServiceUSNHeader header = new ServiceUSNHeader(
                NamedServiceType.valueOf("uuid:MY-SERVICE-123::urn:schemas-upnp-org:service:MY-SERVICE-TYPE:1")
        );
        assertEquals(header.getValue().getUdn().getIdentifierString(), "MY-SERVICE-123");
        assert header.getValue().getServiceType() instanceof UDAServiceType;
    }

    @Test
    public void parseInvalidServiceUSNHeader() {
        try {
            ServiceUSNHeader header = new ServiceUSNHeader();
            header.setString("uuid:MY-SERVICE-123--urn:schemas-upnp-org:service:MY-SERVICE-TYPE:1");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidSTAllHeader() {
        try {
            STAllHeader header = new STAllHeader();
            header.setString("ssdp:foo");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidUDADeviceTypeHeader() {
        try {
            UDADeviceTypeHeader header = new UDADeviceTypeHeader();
            header.setString("urn:foo-bar:device:!@#:123");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseUDADeviceTypeHeaderURI() {
        UDADeviceTypeHeader header = new UDADeviceTypeHeader(URI.create("urn:schemas-upnp-org:device:MyDeviceType:123"));
        assertEquals(header.getValue().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getType(), "MyDeviceType");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:schemas-upnp-org:device:MyDeviceType:123");
    }

    @Test
    public void parseInvalidUDAServiceTypeHeader() {
        try {
            UDAServiceTypeHeader header = new UDAServiceTypeHeader();
            header.setString("urn:foo-bar:service:!@#:123");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseUDAServiceTypeHeaderURI() {
        UDAServiceTypeHeader header = new UDAServiceTypeHeader(URI.create("urn:schemas-upnp-org:service:MyServiceType:123"));
        assertEquals(header.getValue().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getType(), "MyServiceType");
        assertEquals(header.getValue().getVersion(), 123);
        assertEquals(header.getString(), "urn:schemas-upnp-org:service:MyServiceType:123");

    }

    @Test
    public void parseUDNHeader() {
        UDNHeader header = new UDNHeader();
        header.setString("uuid:MY-UUID-1234");
        assertEquals(header.getValue().getIdentifierString(), "MY-UUID-1234");
    }

    @Test
    public void parseInvalidUDNHeaderPrefix() {
        try {
            UDNHeader header = new UDNHeader();
            header.setString("MY-UUID-1234");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidUDNHeaderURN() {
        try {
            UDNHeader header = new UDNHeader();
            header.setString("uuid:MY-UUID-1234::urn:foo-bar:baz");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInvalidUSNRootDeviceHeader() {
        try {
            USNRootDeviceHeader header = new USNRootDeviceHeader();
            header.setString("uuid:MY-UUID-1234::upnp:rootfoo");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseSoapActionHeader() {
        SoapActionHeader header = new SoapActionHeader(URI.create("urn:schemas-upnp-org:service:MyServiceType:1#MyAction"));
        assertEquals(header.getValue().getServiceType().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getServiceType().getType(), "MyServiceType");
        assertEquals(header.getValue().getServiceType().getVersion(), 1);
        assertEquals(header.getValue().getActionName(), "MyAction");
        assertEquals(header.getString(), "\"urn:schemas-upnp-org:service:MyServiceType:1#MyAction\"");

    }

    @Test
    public void parseSoapActionHeaderString() {
        SoapActionHeader header = new SoapActionHeader();
        header.setString("\"urn:schemas-upnp-org:service:MyServiceType:1#MyAction\"");
        assertEquals(header.getValue().getServiceType().getNamespace(), "schemas-upnp-org");
        assertEquals(header.getValue().getServiceType().getType(), "MyServiceType");
        assertEquals(header.getValue().getServiceType().getVersion(), 1);
        assertEquals(header.getValue().getActionName(), "MyAction");
        assertEquals(header.getString(), "\"urn:schemas-upnp-org:service:MyServiceType:1#MyAction\"");
    }

    @Test
    public void parseSoapActionHeaderQueryString() {
        SoapActionHeader header = new SoapActionHeader();
        header.setString("\"urn:schemas-upnp-org:control-1-0#QueryStateVariable\"");
        assertEquals(header.getValue().getServiceType(), null);
        assertEquals(header.getValue().getType(), "control-1-0");
        assertEquals(header.getValue().getVersion(), null);
        assertEquals(header.getValue().getActionName(), "QueryStateVariable");
        assertEquals(header.getString(), "\"urn:schemas-upnp-org:control-1-0#QueryStateVariable\"");
    }

    @Test
    public void parseEventSequenceHeaderString() {
        EventSequenceHeader header = new EventSequenceHeader();
        header.setString("0");
        assertEquals(header.getValue().getValue(), Long.valueOf(0));
        header.setString("001");
        assertEquals(header.getValue().getValue(), Long.valueOf(1));
        header.setString("123");
        assertEquals(header.getValue().getValue(), Long.valueOf(123));

    }

    @Test
    public void parseTimeoutHeaderString() {
        TimeoutHeader header = new TimeoutHeader();
        header.setString("Second-123");
        assertEquals(header.getValue(), Integer.valueOf(123));
        header.setString("Second-infinite");
        assertEquals(header.getValue(), TimeoutHeader.INFINITE_VALUE);
    }

    @Test
    public void parseCallbackHeaderString() {
        CallbackHeader header = new CallbackHeader();
        header.setString("<http://127.0.0.1/foo>");
        assertEquals(header.getValue().size(), 1);
        assertEquals(header.getValue().get(0).toString(), "http://127.0.0.1/foo");

        header.setString("<http://127.0.0.1/foo><http://127.0.0.1/bar>");
        assertEquals(header.getValue().size(), 2);
        assertEquals(header.getValue().get(0).toString(), "http://127.0.0.1/foo");
        assertEquals(header.getValue().get(1).toString(), "http://127.0.0.1/bar");

        header.setString("<http://127.0.0.1/foo> <http://127.0.0.1/bar>");
        assertEquals(header.getValue().size(), 2);
        assertEquals(header.getValue().get(0).toString(), "http://127.0.0.1/foo");
        assertEquals(header.getValue().get(1).toString(), "http://127.0.0.1/bar");
    }

    @Test
    public void parseInvalidCallbackHeaderString() {
        CallbackHeader header = new CallbackHeader();
        header.setString("<http://127.0.0.1/foo> <ftp://127.0.0.1/bar>");
        assertEquals(header.getValue().size(), 1);
        assertEquals(header.getValue().get(0).toString(), "http://127.0.0.1/foo");

        /* TODO: I'm having trouble finding a valid URL that is
           an invalid URI in the standard JDK...
        header.setString("<http://127.0.0.1/foo> <http://we_need_a_valid_URL_but_invalid_URI>");
        assertEquals(header.getValue().size(), 1);
        assertEquals(header.getValue().get(0).toString(), "http://127.0.0.1/foo");
        */
    }

    @Test
    public void parseSubscriptionIdHeaderString() {
        SubscriptionIdHeader header = new SubscriptionIdHeader();
        header.setString("uuid:123-123-123-123");
        assertEquals(header.getValue(), "uuid:123-123-123-123");
    }

    @Test
    public void parseInvalidSubscriptionIdHeaderString() {
        try {
            SubscriptionIdHeader header = new SubscriptionIdHeader();
            header.setString("abc:123-123-123-123");
            fail();
        } catch (InvalidHeaderException ex) {
            //ignore
        }
    }

    @Test
    public void parseInterfaceMacAddress() {
        InterfaceMacHeader header = new InterfaceMacHeader("00:17:ab:e9:65:a0");
        assertEquals(header.getValue().length, 6);
        assertEquals(header.getString().toUpperCase(Locale.ROOT), "00:17:AB:E9:65:A0");
    }

    @Test
    public void parseRange() {
        RangeHeader header = new RangeHeader("bytes=1539686400-1540210688");
        assertEquals(header.getValue().getFirstByte(), new Long(1539686400));
        assertEquals(header.getValue().getLastByte(), new Long(1540210688));
        assertEquals(header.getString(), "bytes=1539686400-1540210688");
    }

    @Test
    public void parseContentRange() {
        ContentRangeHeader header = new ContentRangeHeader("bytes 1539686400-1540210688/21323123");
        assertEquals(header.getValue().getFirstByte(), new Long(1539686400));
        assertEquals(header.getValue().getLastByte(), new Long(1540210688));
        assertEquals(header.getValue().getByteLength(), new Long(21323123));
        assertEquals(header.getString(), "bytes 1539686400-1540210688/21323123");
    }

    @Test
    public void parsePragma() {
        PragmaHeader header = new PragmaHeader("no-cache");
        assertEquals(header.getValue().getValue(), "no-cache");
        assertEquals(header.getString(), "no-cache");

        header.setString("token=value");
        assertEquals(header.getValue().getToken(), "token");
        assertEquals(header.getValue().getValue(), "value");
        assertEquals(header.getString(), "token=value");

        header.setString("token=\"value\"");
        assertEquals(header.getValue().getToken(), "token");
        assertEquals(header.getValue().getValue(), "value");
        assertEquals(header.getString(), "token=\"value\"");
    }
}
