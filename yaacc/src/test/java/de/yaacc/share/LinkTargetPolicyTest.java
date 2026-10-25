/*
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
package de.yaacc.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link LinkTargetPolicy}: which http/https link targets the share intake accepts.
 * The phone must not be made to fetch from itself or from link-local addresses; the private LAN
 * stays allowed. Hosts are parsed by hand (no DNS), so every form that can encode loopback must
 * be refused. Plain JVM.
 */
public class LinkTargetPolicyTest {

    private static final Set<String> NONE = Collections.emptySet();
    private static final Set<String> OWN = new HashSet<>(Arrays.asList("192.168.1.5", "fd00::5"));

    private static void assertAllowed(String url) {
        assertTrue("should be allowed: " + url, LinkTargetPolicy.isAllowedTarget(url, OWN));
    }

    private static void assertRejected(String url) {
        assertFalse("should be rejected: " + url, LinkTargetPolicy.isAllowedTarget(url, OWN));
    }

    // ---- ordinary targets are allowed

    @Test
    public void ordinaryHostNamesAreAllowed() {
        assertAllowed("http://example.com/song.mp3");
        assertAllowed("https://example.com/song.mp3");
        assertAllowed("https://media.example.org:8443/a/b.mp4?x=1#t");
        assertAllowed("HTTP://EXAMPLE.COM/");
        assertAllowed("http://a-b_c.example.com/");
        assertAllowed("http://example.com");
        assertAllowed("http://example.com?x=1");
        assertAllowed("http://example.com#frag");
        assertAllowed("http://example.com./");
        assertAllowed("http://my-nas/music.mp3");
        assertAllowed("http://nas.fritz.box/music.mp3");
        assertAllowed("http://localhost.example.com/");
        assertAllowed("http://notlocalhost/");
        assertAllowed("http://localhostx/");
        assertAllowed("http://127.0.0.1.example.com/");
    }

    @Test
    public void publicIpAddressesAreAllowed() {
        assertAllowed("http://93.184.216.34/");
        assertAllowed("http://8.8.8.8:8080/x");
        assertAllowed("http://[2001:db8::1]/");
        assertAllowed("http://[2606:4700:4700::1111]:443/x");
    }

    @Test
    public void privateLanRangesAreAllowed() {
        assertAllowed("http://10.0.0.1/");
        assertAllowed("http://10.255.255.255/");
        assertAllowed("http://172.16.0.1/");
        assertAllowed("http://172.31.255.254/");
        assertAllowed("http://192.168.0.10:49157/x");
        assertAllowed("http://192.168.178.1/");
        assertAllowed("http://[fc00::1]/");
        assertAllowed("http://[fd12:3456:789a::1]/");
        assertAllowed("http://[::ffff:192.168.1.77]/");
    }

    @Test
    public void addressesJustOutsideTheForbiddenRangesAreAllowed() {
        assertAllowed("http://126.255.255.255/");
        assertAllowed("http://128.0.0.1/");
        assertAllowed("http://169.253.255.255/");
        assertAllowed("http://169.255.0.0/");
        assertAllowed("http://1.0.0.1/");
        assertAllowed("http://[fec0::1]/");
        assertAllowed("http://[fe7f::1]/");
        assertAllowed("http://[fec0::1]:80/");
        assertAllowed("http://[2001:db8::2]/");
    }

    // ---- scheme and general shape

    @Test
    public void onlyHttpAndHttpsAreAllowed() {
        assertRejected("ftp://example.com/a.mp3");
        assertRejected("file:///sdcard/a.mp3");
        assertRejected("content://com.example/a.mp3");
        assertRejected("javascript:alert(1)");
        assertRejected("intent://example.com#Intent;end");
        assertRejected("//example.com/a.mp3");
        assertRejected("example.com/a.mp3");
        assertRejected("httpx://example.com/");
        assertRejected("http:example.com");
    }

    @Test
    public void nullEmptyAndMalformedTextsAreRejected() {
        assertFalse(LinkTargetPolicy.isAllowedTarget(null, OWN));
        assertRejected("");
        assertRejected(" ");
        assertRejected("http://");
        assertRejected("https://");
        assertRejected("http:///a.mp3");
        assertRejected(" http://example.com/");
        assertRejected("http://exa mple.com/");
        assertRejected("http://example.com/a b");
        assertRejected("http://example.com/\n");
        assertRejected("http://exam\u0000ple.com/");
    }

    @Test
    public void anOverlongLinkIsRejected() {
        StringBuilder sb = new StringBuilder("http://example.com/");
        while (sb.length() <= HttpLinkTarget.MAX_LENGTH) {
            sb.append('a');
        }

        assertRejected(sb.toString());
    }

    @Test
    public void aMissingOwnAddressSetIsFine() {
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://example.com/", null));
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://192.168.1.5/", null));
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://192.168.1.5/", NONE));
        assertFalse(LinkTargetPolicy.isAllowedTarget("http://127.0.0.1/", null));
    }

    // ---- loopback

    @Test
    public void ipv4LoopbackIsRejected() {
        assertRejected("http://127.0.0.1/");
        assertRejected("http://127.0.0.1:49157/proxy/x");
        assertRejected("https://127.0.0.1/");
        assertRejected("http://127.0.0.2/");
        assertRejected("http://127.1.2.3/");
        assertRejected("http://127.255.255.255/");
        assertRejected("HTTP://127.0.0.1/");
        assertRejected("http://127.0.0.1");
        assertRejected("http://127.0.0.1?x=1");
        assertRejected("http://127.0.0.1#x");
        assertRejected("http://127.0.0.1./");
    }

    @Test
    public void ipv6LoopbackIsRejected() {
        assertRejected("http://[::1]/");
        assertRejected("http://[::1]:8080/x");
        assertRejected("http://[0:0:0:0:0:0:0:1]/");
        assertRejected("http://[0000:0000:0000:0000:0000:0000:0000:0001]/");
        assertRejected("http://[0::1]/");
        assertRejected("http://[::0001]/");
    }

    @Test
    public void ipv4MappedLoopbackIsRejected() {
        assertRejected("http://[::ffff:127.0.0.1]/");
        assertRejected("http://[::ffff:7f00:1]/");
        assertRejected("http://[::FFFF:7F00:0001]/");
        assertRejected("http://[0:0:0:0:0:ffff:127.0.0.1]/");
        assertRejected("http://[::ffff:127.9.9.9]/");
    }

    @Test
    public void ipv4CompatibleAndNat64FormsOfLoopbackAreRejected() {
        assertRejected("http://[::127.0.0.1]/");
        assertRejected("http://[::7f00:1]/");
        assertRejected("http://[64:ff9b::127.0.0.1]/");
        assertRejected("http://[64:ff9b::7f00:1]/");
        assertRejected("http://[64:ff9b::169.254.1.1]/");
    }

    @Test
    public void localhostNamesAreRejected() {
        assertRejected("http://localhost/");
        assertRejected("http://localhost:8080/");
        assertRejected("http://LOCALHOST/");
        assertRejected("http://LocalHost/");
        assertRejected("http://localhost./");
        assertRejected("http://LOCALHOST.:80/");
        assertRejected("http://a.localhost/");
        assertRejected("http://a.b.localhost/");
        assertRejected("http://A.LocalHost./x");
        assertRejected("http://service.localhost:3000/");
    }

    // ---- unspecified

    @Test
    public void unspecifiedAddressesAreRejected() {
        assertRejected("http://0.0.0.0/");
        assertRejected("http://0.0.0.0:49157/");
        assertRejected("http://[::]/");
        assertRejected("http://[0:0:0:0:0:0:0:0]/");
        assertRejected("http://[::ffff:0.0.0.0]/");
        assertRejected("http://[::ffff:0:0]/");
        assertRejected("http://0.1.2.3/");
    }

    // ---- link-local

    @Test
    public void ipv4LinkLocalIsRejected() {
        assertRejected("http://169.254.0.1/");
        assertRejected("http://169.254.169.254/latest/meta-data");
        assertRejected("http://169.254.255.255/");
        assertRejected("http://[::ffff:169.254.1.1]/");
        assertRejected("http://[::ffff:a9fe:101]/");
    }

    @Test
    public void ipv6LinkLocalIsRejected() {
        assertRejected("http://[fe80::1]/");
        assertRejected("http://[FE80::1]/");
        assertRejected("http://[fe80:0:0:0:1:2:3:4]/");
        assertRejected("http://[fe90::1]/");
        assertRejected("http://[fea0::1]/");
        assertRejected("http://[febf::1]/");
        assertRejected("http://[febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff]:80/");
    }

    @Test
    public void aZoneIdInTheHostIsRejected() {
        assertRejected("http://[fe80::1%25wlan0]/");
        assertRejected("http://[fe80::1%wlan0]/");
        assertRejected("http://[2001:db8::1%25eth0]/");
    }

    // ---- the phone's own addresses

    @Test
    public void theOwnAddressesAreRejected() {
        assertRejected("http://192.168.1.5/");
        assertRejected("http://192.168.1.5:49157/proxy/x");
        assertRejected("http://[fd00::5]/");
        assertRejected("http://[FD00:0:0:0:0:0:0:5]/");
        assertRejected("http://[::ffff:192.168.1.5]/");
        assertRejected("http://192.168.1.5./");
    }

    @Test
    public void otherAddressesOfTheSameNetworkAreAllowed() {
        assertAllowed("http://192.168.1.6/");
        assertAllowed("http://192.168.1.50/");
        assertAllowed("http://[fd00::6]/");
    }

    @Test
    public void theOwnSetIsNormalizedBeforeComparing() {
        Set<String> raw = new HashSet<>(Arrays.asList(" 10.1.2.3 ", "[fd00:0:0:0:0:0:0:9]",
                "::ffff:10.9.9.9", "FE80::1%wlan0", "not-an-ip", "", null));

        assertFalse(LinkTargetPolicy.isAllowedTarget("http://10.1.2.3/", raw));
        assertFalse(LinkTargetPolicy.isAllowedTarget("http://[fd00::9]/", raw));
        assertFalse(LinkTargetPolicy.isAllowedTarget("http://10.9.9.9/", raw));
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://10.1.2.4/", raw));
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://not-an-ip/", raw));
    }

    @Test
    public void theOwnSetIsNeverResolvedAndHostNamesInItAreIgnored() {
        Set<String> names = new HashSet<>(Arrays.asList("myphone.local", "localhost"));

        assertTrue(LinkTargetPolicy.isAllowedTarget("http://myphone.local/", names));
        assertTrue(LinkTargetPolicy.isAllowedTarget("http://10.0.0.1/", names));
    }

    // ---- userinfo tricks: the authority is what follows the last '@'

    @Test
    public void userinfoDoesNotHideLoopback() {
        assertRejected("http://good@127.0.0.1/");
        assertRejected("http://good:pw@127.0.0.1:80/");
        assertRejected("http://example.com@127.0.0.1/");
        assertRejected("http://example.com:80@127.0.0.1/");
        assertRejected("http://a@b@127.0.0.1/");
        assertRejected("http://user@[::1]/");
        assertRejected("http://user@localhost/");
        assertRejected("http://example.com@169.254.169.254/");
        assertRejected("http://user@192.168.1.5/");
    }

    @Test
    public void aLoopbackLookingUserinfoDoesNotMakeAGoodHostBad() {
        assertAllowed("http://127.0.0.1:80@evil.example.com/");
        assertAllowed("http://127.0.0.1@example.com/");
        assertAllowed("http://localhost@example.com/");
        assertAllowed("http://[::1]@example.com/");
        assertAllowed("http://user:pw@example.com:8080/x");
    }

    @Test
    public void anAtSignAfterTheAuthorityIsNotUserinfo() {
        assertAllowed("http://example.com/path@127.0.0.1");
        assertAllowed("http://example.com/?u=@127.0.0.1/");
        assertAllowed("http://example.com/#@127.0.0.1");
        assertRejected("http://127.0.0.1/path@example.com");
        assertRejected("http://127.0.0.1?u=@example.com");
        assertRejected("http://127.0.0.1#@example.com");
    }

    @Test
    public void aBackslashInTheAuthorityIsRejected() {
        assertRejected("http://example.com\\@127.0.0.1/");
        assertRejected("http://127.0.0.1\\@example.com/");
        assertRejected("http://example.com\\.evil.com/");
        assertRejected("http:\\\\127.0.0.1\\");
    }

    // ---- other spellings of 127.0.0.1 are refused (fail closed)

    @Test
    public void decimalOctalAndHexIpv4FormsAreRejected() {
        assertRejected("http://2130706433/");
        assertRejected("http://0177.0.0.1/");
        assertRejected("http://0x7f.1/");
        assertRejected("http://0x7f.0.0.1/");
        assertRejected("http://0x7f000001/");
        assertRejected("http://0X7F000001/");
        assertRejected("http://017700000001/");
        assertRejected("http://127.1/");
        assertRejected("http://127.0.1/");
        assertRejected("http://0/");
        assertRejected("http://00/");
        assertRejected("http://0x0/");
        assertRejected("http://3232235781/");
        assertRejected("http://0300.0250.0.5/");
        assertRejected("http://192.168.1/");
        assertRejected("http://1.2.3.4.5/");
        assertRejected("http://127.0.0.01/");
        assertRejected("http://127.0.0.256/");
        assertRejected("http://256.256.256.256/");
        assertRejected("http://1.2.3.04/");
        assertRejected("http://4294967295/");
        assertRejected("http://99999999999999999999/");
    }

    @Test
    public void numericSpellingsAreRejectedEvenWithPortAndTrailingDot() {
        assertRejected("http://2130706433:80/");
        assertRejected("http://2130706433./");
        assertRejected("http://0x7f.1:8080/x");
    }

    @Test
    public void aNumberLikeLabelInTheMiddleOfANameIsFine() {
        assertAllowed("http://1.example.com/");
        assertAllowed("http://127.example.com/");
        assertAllowed("http://0x7f.example.com/");
        assertAllowed("http://12345.example.com/");
    }

    @Test
    public void unicodeLookalikesOfLoopbackAreRejected() {
        // fullwidth digits and an ideographic full stop fold to ASCII under IDNA/NFKC
        assertRejected("http://１２７.０.０.１/");
        assertRejected("http://127。0。0。1/");
        assertRejected("http://127．0．0．1/");
        assertRejected("http://127｡0｡0｡1/");
        assertRejected("http://12​7.0.0.1/");
        assertRejected("http://127.0.0.1­/");
        assertRejected("http://local​host/");
        assertRejected("http://ｌｏｃａｌｈｏｓｔ/");
    }

    @Test
    public void anInternationalNameIsAllowed() {
        assertAllowed("http://bücher.example/");
        assertAllowed("https://例え.jp/song.mp3");
    }

    // ---- percent-encoded host characters

    @Test
    public void percentEncodedHostCharactersAreRejected() {
        assertRejected("http://127.0.0.%31/");
        assertRejected("http://%31%32%37.0.0.1/");
        assertRejected("http://local%68ost/");
        assertRejected("http://example%2ecom/");
        assertRejected("http://example.com%2f@127.0.0.1/");
        assertRejected("http://%/");
        assertRejected("http://[%3a%3a1]/");
        assertRejected("http://exa%mple.com/");
    }

    @Test
    public void aPercentSignInThePathOrQueryIsFine() {
        assertAllowed("http://example.com/a%20b?x=%41#%42");
    }

    // ---- bracketed hosts

    @Test
    public void malformedBracketedHostsAreRejected() {
        assertRejected("http://[::1/");
        assertRejected("http://::1]/");
        assertRejected("http://[]/");
        assertRejected("http://[/");
        assertRejected("http://[example.com]/");
        assertRejected("http://[127.0.0.1]/");
        assertRejected("http://[192.168.0.9]/");
        assertRejected("http://[::1]x/");
        assertRejected("http://[::1]:abc/");
        assertRejected("http://[::1]]/");
        assertRejected("http://[[::1]]/");
        assertRejected("http://[2001:db8::1]x/");
        assertRejected("http://[2001:db8::g]/");
        assertRejected("http://[2001:db8:::1]/");
        assertRejected("http://[1:2:3:4:5:6:7:8:9]/");
        assertRejected("http://2001:db8::1/");
        assertRejected("http://::1/");
    }

    // ---- ports

    @Test
    public void portsAreChecked() {
        assertAllowed("http://example.com:1/");
        assertAllowed("http://example.com:65535/");
        assertAllowed("http://example.com:0080/");
        assertAllowed("http://example.com:/");
        assertAllowed("http://[2001:db8::1]:8080/");
        assertRejected("http://example.com:65536/");
        assertRejected("http://example.com:99999/");
        assertRejected("http://example.com:abc/");
        assertRejected("http://example.com:80:80/");
        assertRejected("http://example.com:-1/");
        assertRejected("http://example.com:+1/");
        assertRejected("http://example.com:1e3/");
        assertRejected("http://example.com:123456789012345/");
        assertRejected("http://127.0.0.1:99999/");
    }

    // ---- empty host and bad host syntax

    @Test
    public void anEmptyHostIsRejected() {
        assertRejected("http://:80/");
        assertRejected("http://@/");
        assertRejected("http://user@/");
        assertRejected("http://user@:80/");
        assertRejected("http:///");
        assertRejected("http://?x=1");
        assertRejected("http://#x");
        assertRejected("http://./");
        assertRejected("http://../");
    }

    @Test
    public void emptyLabelsAndOddCharactersInANameAreRejected() {
        assertRejected("http://.example.com/");
        assertRejected("http://example..com/");
        assertRejected("http://example.com../");
        assertRejected("http://exa*mple.com/");
        assertRejected("http://exa,mple.com/");
        assertRejected("http://exa;mple.com/");
        assertRejected("http://exa'mple.com/");
        assertRejected("http://exa<mple.com/");
        assertRejected("http://exa|mple.com/");
        assertRejected("http://exa^mple.com/");
        assertRejected("http://exa`mple.com/");
        assertRejected("http://exa{mple.com/");
        assertRejected("http://exa!mple.com/");
        assertRejected("http://exa$mple.com/");
        assertRejected("http://exa=mple.com/");
    }

    @Test
    public void aTooLongHostIsRejected() {
        StringBuilder host = new StringBuilder();
        while (host.length() <= 253) {
            host.append("abcdefghij.");
        }
        host.append("com");

        assertRejected("http://" + host + "/");
    }

    @Test
    public void textThatIsNeverResolved() {
        // a name that would resolve to a loopback address through DNS is not looked up: names pass
        assertAllowed("http://loopback.example.com/");
        assertAllowed("http://ip6-localhost/");
    }
}
