/*
 *
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.yxc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The UPnP device description against the real one of a device and a few invented variants. */
public class YxcDescriptionParserTest {

    private static final String YAMAHA_NS = "xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:yamaha=\"urn:schemas-yamaha-com:device-1-0\"";

    private static String description(String deviceBody, String xDeviceBody) {
        return "<?xml version=\"1.0\"?><root " + YAMAHA_NS + "><device>" + deviceBody + "</device>"
                + "<yamaha:X_device>" + xDeviceBody + "</yamaha:X_device></root>";
    }

    private static String yxcService(String controlUrl) {
        return "<yamaha:X_serviceList><yamaha:X_service><yamaha:X_yxcControlURL>" + controlUrl
                + "</yamaha:X_yxcControlURL></yamaha:X_service></yamaha:X_serviceList>";
    }

    // ---- the description of a real device

    @Test
    public void theDescriptionOfARealDevice() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("captured/c_upnpDescription.xml"));

        assertNotNull(info);
        assertEquals("/YamahaExtendedControl/v1/", info.getControlUrl());
        assertEquals("the padding of the device is dropped", "2403", info.getVersion());
        assertEquals("0000000C", info.getSerialNumber());
        assertEquals("uuid:9ab0c000-f668-11de-9976-00000000000c", info.getUdn());
        assertEquals("scrubbed", info.getFriendlyName());
    }

    @Test
    public void theIdentityMatchesTheAnswerOfGetDeviceInfo() throws Exception {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("captured/c_upnpDescription.xml"));
        YxcDeviceInfo device = new YxcClient(new RecordingTransport()
                .answer("system/getDeviceInfo", YxcFixtures.read("captured/c_deviceInfo.json"))).getDeviceInfo();

        assertEquals("00000000000c", info.getUdnSuffix());
        assertEquals("device_id is upper case, the UDN lower case", "00000000000C", device.getDeviceId());
        assertTrue(info.matchesDeviceId(device.getDeviceId()));
        assertTrue(info.matchesSystemId(device.getSystemId()));
    }

    @Test
    public void anotherDevicesIdentityDoesNotMatch() throws Exception {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("captured/c_upnpDescription.xml"));
        YxcDeviceInfo other = new YxcClient(new RecordingTransport()
                .answer("system/getDeviceInfo", YxcFixtures.read("captured/b_deviceInfo.json"))).getDeviceInfo();

        assertFalse(info.matchesDeviceId(other.getDeviceId()));
        assertFalse(info.matchesSystemId(other.getSystemId()));
        assertFalse(info.matchesDeviceId(null));
        assertFalse(info.matchesSystemId(null));
        assertFalse(info.matchesSystemId(""));
    }

    @Test
    public void theUrlBaseOfTheCapturedFileWasScrubbedToAWordAndIsTakenForAHostName() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("captured/c_upnpDescription.xml"));

        assertEquals("scrubbed", info.getHost());
        assertEquals(80, info.getPort());
        assertEquals("http://scrubbed/YamahaExtendedControl/v1/", info.getControlBaseUrl());
    }

    @Test
    public void theIdsStayOutOfLogLines() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("captured/c_upnpDescription.xml"));

        assertFalse(info.toString().contains("0000000C"));
        assertFalse(info.toString().contains("9ab0c000"));
    }

    // ---- the old and the new service side by side

    @Test
    public void theOldServiceIsIgnoredAndTheYxcOneBesideItIsFound() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("description_old_and_new_service.xml"));

        assertNotNull(info);
        assertEquals("/YamahaExtendedControl/v1/", info.getControlUrl());
        assertEquals("2.00", info.getVersion());
        assertEquals("192.0.2.50", info.getHost());
        assertEquals(49154, info.getPort());
        assertEquals("http://192.0.2.50:49154/YamahaExtendedControl/v1/", info.getControlBaseUrl());
        assertEquals("Invented Room", info.getFriendlyName());
    }

    @Test
    public void anEmbeddedDeviceDoesNotReplaceTheIdentityOfTheRootDevice() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(YxcFixtures.read("description_old_and_new_service.xml"));

        assertEquals("0000000D", info.getSerialNumber());
        assertEquals("uuid:9ab0c000-f668-11de-9976-00000000000D", info.getUdn());
        assertEquals("00000000000d", info.getUdnSuffix());
        assertTrue("the suffix matches ignoring case", info.matchesDeviceId("00000000000d"));
        assertTrue(info.matchesDeviceId("00000000000D"));
    }

    @Test
    public void aDeviceWithOnlyTheOldServiceIsNotAYxcDevice() {
        assertNull(YxcDescriptionParser.parse(YxcFixtures.read("description_old_service_only.xml")));
    }

    @Test
    public void aRendererWithoutAnyYamahaBlockIsNotAYxcDevice() {
        assertNull(YxcDescriptionParser.parse(YxcFixtures.read("description_renderer_only.xml")));
    }

    // ---- tolerance

    @Test
    public void anUndeclaredOrOtherPrefixIsNoProblem() {
        String undeclared = "<root><device><friendlyName>Room</friendlyName></device><yamaha:X_device>"
                + yxcService("/YamahaExtendedControl/v1/") + "</yamaha:X_device></root>";
        String other = "<root xmlns:y=\"urn:schemas-yamaha-com:device-1-0\"><device/><y:X_device><y:X_serviceList><y:X_service>"
                + "<y:X_yxcControlURL>/YamahaExtendedControl/v1/</y:X_yxcControlURL></y:X_service></y:X_serviceList></y:X_device></root>";

        assertEquals("/YamahaExtendedControl/v1/", YxcDescriptionParser.parse(undeclared).getControlUrl());
        assertEquals("/YamahaExtendedControl/v1/", YxcDescriptionParser.parse(other).getControlUrl());
    }

    @Test
    public void aServiceOutsideOfTheYamahaDeviceBlockIsNotLookedAt() {
        String xml = "<root><device/><other><yamaha:X_service><yamaha:X_yxcControlURL>/YamahaExtendedControl/v1/</yamaha:X_yxcControlURL>"
                + "</yamaha:X_service></other></root>";

        assertNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void missingOptionalPartsAreNull() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(description("", yxcService("/YamahaExtendedControl/v1/")));

        assertNotNull(info);
        assertNull(info.getVersion());
        assertNull(info.getHost());
        assertNull(info.getControlBaseUrl());
        assertNull(info.getUdn());
        assertNull(info.getUdnSuffix());
        assertNull(info.getSerialNumber());
        assertNull(info.getFriendlyName());
        assertFalse(info.matchesDeviceId("00000000000C"));
    }

    @Test
    public void aByteOrderMarkAndWhitespaceInFrontAreTolerated() {
        String xml = "" + (char) 0xFEFF + "  \n" + description("", yxcService("/YamahaExtendedControl/v1/"));

        assertNotNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void theUrlBaseMayCarryASchemeAnIpv6AddressOrNoPort() {
        assertEquals("192.0.2.7", baseOf("http://192.0.2.7:80/").getHost());
        assertEquals(80, baseOf("192.0.2.7").getPort());
        YxcDescriptionInfo ipv6 = baseOf("[2001:db8::7]:8080");
        assertEquals("2001:db8::7", ipv6.getHost());
        assertEquals(8080, ipv6.getPort());
        assertEquals("http://[2001:db8::7]:8080/YamahaExtendedControl/v1/", ipv6.getControlBaseUrl());
    }

    @Test
    public void anUnusableUrlBaseIsLeftOut() {
        assertNull(baseOf("evil.example/path?x=1").getHost());
        assertNull(baseOf("192.0.2.7:99999").getHost());
        assertNull(baseOf("a b").getHost());
        assertNull(baseOf("").getHost());
    }

    private static YxcDescriptionInfo baseOf(String urlBase) {
        return YxcDescriptionParser.parse(description("", "<yamaha:X_URLBase>" + urlBase + "</yamaha:X_URLBase>"
                + yxcService("/YamahaExtendedControl/v1/")));
    }

    @Test
    public void valuesAreTrimmedStrippedOfControlCharactersAndCapped() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longName.append('x');
        }
        YxcDescriptionInfo info = YxcDescriptionParser.parse(description(
                "<friendlyName>  Living\t Room \n</friendlyName><serialNumber>" + longName + "</serialNumber>",
                yxcService("/YamahaExtendedControl/v1/")));

        assertEquals("Living Room", info.getFriendlyName());
        assertEquals(256, info.getSerialNumber().length());
    }

    // ---- a control URL must be a plain path

    @Test
    public void aControlUrlThatIsNotAPlainPathMakesTheServiceUnusable() {
        String[] bad = {"http://evil.example/x", "//evil.example/x", "relative/path", "/a/../b", "/a b", "/a?x=1", "/a#b", "", "/x\"y"};
        for (String url : bad) {
            assertNull(url, YxcDescriptionParser.parse(description("", yxcService(url))));
        }
    }

    @Test
    public void aBadServiceDoesNotHideAGoodOneBehindIt() {
        String services = "<yamaha:X_serviceList>"
                + "<yamaha:X_service><yamaha:X_yxcControlURL>http://evil.example/x</yamaha:X_yxcControlURL></yamaha:X_service>"
                + "<yamaha:X_service><yamaha:X_yxcControlURL>/YamahaExtendedControl/v1/</yamaha:X_yxcControlURL></yamaha:X_service>"
                + "</yamaha:X_serviceList>";

        assertEquals("/YamahaExtendedControl/v1/", YxcDescriptionParser.parse(description("", services)).getControlUrl());
    }

    // ---- garbage, size, entities

    @Test
    public void garbageIsNotAYxcDeviceAndNeverAnException() {
        String[] garbage = {null, "", "   ", "not xml at all", "<", "<root>", "<root><device></root>", "\u0000\u0001\u0002",
                "{\"json\":true}", "<html><body>404</body></html>", "<root><yamaha:X_device><yamaha:X_service>"};
        for (String text : garbage) {
            assertNull(String.valueOf(text), YxcDescriptionParser.parse(text));
        }
    }

    @Test
    public void aDocumentThatIsTooLargeIsRefused() {
        StringBuilder padding = new StringBuilder();
        while (padding.length() <= YxcDescriptionParser.MAX_DESCRIPTION_CHARS) {
            padding.append("<icon>0123456789</icon>");
        }
        String xml = description("<iconList>" + padding + "</iconList>", yxcService("/YamahaExtendedControl/v1/"));

        assertNull(YxcDescriptionParser.parse(xml));
        assertNotNull("without the padding it is fine", YxcDescriptionParser.parse(description("", yxcService("/YamahaExtendedControl/v1/"))));
    }

    @Test
    public void aDeeplyNestedDocumentIsRefused() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            open.append("<a>");
            close.append("</a>");
        }
        String xml = "<root><device>" + open + close + "</device><yamaha:X_device>" + yxcService("/YamahaExtendedControl/v1/")
                + "</yamaha:X_device></root>";

        assertNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void aDocumentTypeDeclarationIsRefusedSoNoEntityIsEverResolved() throws Exception {
        java.io.File secret = java.io.File.createTempFile("yxc-xxe", ".txt");
        try {
            java.nio.file.Files.write(secret.toPath(), "SECRET-CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY xxe SYSTEM \"" + secret.toURI() + "\">]>"
                    + "<root><device><friendlyName>&xxe;</friendlyName></device><yamaha:X_device>"
                    + yxcService("/YamahaExtendedControl/v1/") + "</yamaha:X_device></root>";

            assertNull(YxcDescriptionParser.parse(xml));
        } finally {
            secret.delete();
        }
    }

    @Test
    public void anEntityExpansionBombIsRefused() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]><root><device><friendlyName>&lol3;</friendlyName></device>"
                + "<yamaha:X_device>" + yxcService("/YamahaExtendedControl/v1/") + "</yamaha:X_device></root>";

        assertNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void aLowerCaseDeclarationKeywordIsRefusedToo() {
        String xml = "<!doctype root><root><device/><yamaha:X_device>" + yxcService("/YamahaExtendedControl/v1/")
                + "</yamaha:X_device></root>";

        assertNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void anUndefinedEntityIsMalformedNotExpanded() {
        String xml = description("<friendlyName>&unknown;</friendlyName>", yxcService("/YamahaExtendedControl/v1/"));

        assertNull(YxcDescriptionParser.parse(xml));
    }

    @Test
    public void theFivePredefinedEntitiesStillWork() {
        YxcDescriptionInfo info = YxcDescriptionParser.parse(description("<friendlyName>Tom &amp; Jerry&apos;s &lt;Room&gt;</friendlyName>",
                yxcService("/YamahaExtendedControl/v1/")));

        assertEquals("Tom & Jerry's <Room>", info.getFriendlyName());
    }
}
