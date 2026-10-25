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
package de.yaacc.upnp.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncPushProducer;
import org.apache.hc.core5.http.nio.AsyncResponseProducer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler.ResponseTrigger;
import org.apache.hc.core5.http.nio.ResponseChannel;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.yaacc.Yaacc;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareRegistry;
import de.yaacc.upnp.server.http.ProducerTestSupport.ArraySource;
import de.yaacc.upnp.server.http.ProducerTestSupport.FakeChannel;

/**
 * The decision order of {@link ProxyShareResponder} for held-file shares, with a fake server
 * (response trigger, response channel, endpoint details) and a real {@link ShareRegistry}: 404
 * before 403, 403 before the device-id check, constant bodies, no lease for HEAD, ranges, the
 * concurrency cap and the lease when the response cannot be submitted. The HTTP_LINK branch
 * needs a network and is a device check.
 */
public class ProxyShareResponderTest {

    private static final String RECEIVER = "192.168.1.20";
    private static final String STRANGER = "192.168.1.99";
    private static final int SIZE = 10_000;

    private ShareRegistry registry;
    private SharedPreferences preferences;
    private ProxyShareResponder responder;
    private byte[] body;
    private ArraySource source;
    private String key;
    private boolean restrict;

    /** What the fake server was told to send. */
    private static final class Sent {
        HttpResponse response;
        AsyncEntityProducer entity;

        int code() {
            return response.getCode();
        }

        String bodyText() throws IOException {
            FakeChannel channel = new FakeChannel();
            entity.produce(channel);
            return new String(channel.bytes(), StandardCharsets.UTF_8);
        }
    }

    @Before
    public void setUp() {
        restrict = true;
        registry = ShareRegistry.createDefault();
        Yaacc application = mock(Yaacc.class);
        when(application.getShareRegistry()).thenReturn(registry);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(application);
        preferences = mock(SharedPreferences.class);
        when(preferences.getBoolean(any(), eq(true))).thenAnswer(invocation -> restrict);
        responder = new ProxyShareResponder(context, preferences);
        body = ProducerTestSupport.body(SIZE);
        source = new ArraySource(body);
        key = registry.register(new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, source, "video/mp4",
                Set.of(RECEIVER), ShareLimits.IDLE_GRACE_MILLIS, ShareLimits.ABSOLUTE_MAX_MILLIS));
        assertNotNull(key);
    }

    private static HttpContext contextFrom(String remote) throws IOException {
        HttpCoreContext context = HttpCoreContext.create();
        EndpointDetails details = mock(EndpointDetails.class);
        when(details.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(remote), 40_000));
        context.setEndpointDetails(details);
        return context;
    }

    private ResponseTrigger recordingTrigger(List<Sent> sent) throws HttpException, IOException {
        ResponseTrigger trigger = mock(ResponseTrigger.class);
        doAnswer(invocation -> {
            AsyncResponseProducer producer = invocation.getArgument(0);
            HttpContext context = invocation.getArgument(1);
            producer.sendResponse(new ResponseChannel() {
                @Override
                public void sendInformation(HttpResponse response, HttpContext c) {
                }

                @Override
                public void sendResponse(HttpResponse response, EntityDetails entityDetails, HttpContext c) {
                    Sent one = new Sent();
                    one.response = response;
                    one.entity = (AsyncEntityProducer) entityDetails;
                    sent.add(one);
                }

                @Override
                public void pushPromise(HttpRequest promise, AsyncPushProducer responseProducer,
                                        HttpContext c) {
                }
            }, context);
            return null;
        }).when(trigger).submitResponse(any(), any());
        return trigger;
    }

    private Sent request(String method, String remote, List<String> segments, String range) throws Exception {
        BasicHttpRequest head = new BasicHttpRequest(method, "/" + String.join("/", segments));
        if (range != null) {
            head.addHeader("Range", range);
        }
        List<Sent> sent = new ArrayList<>();
        responder.respond(new Message<HttpRequest, byte[]>(head, new byte[0]), recordingTrigger(sent),
                contextFrom(remote), "HEAD".equals(method), segments);
        assertEquals("exactly one answer", 1, sent.size());
        return sent.get(0);
    }

    private Sent get(String remote, String range) throws Exception {
        return request("GET", remote, List.of("proxy", key), range);
    }

    private int active() {
        return ShareConcurrencyLimiter.shared().activeCount(key);
    }

    @Test
    public void anUnknownKeyGetsTheConstantNotFoundPage() throws Exception {
        Sent sent = request("GET", RECEIVER, List.of("proxy", "ZZZZZZZZZZZZZZZZZZZZZZ"), null);

        assertEquals(404, sent.code());
        assertEquals("<html><body>Not found</body></html>", sent.bodyText());
    }

    @Test
    public void aMalformedKeyGetsTheSameNotFoundPageAndNeverReachesTheRegistry() throws Exception {
        Sent sent = request("GET", RECEIVER, List.of("proxy", "short"), null);

        assertEquals(404, sent.code());
        assertEquals("<html><body>Not found</body></html>", sent.bodyText());
    }

    @Test
    public void theNotFoundPageEchoesNeitherTheKeyNorThePath() throws Exception {
        Sent sent = request("GET", RECEIVER, List.of("proxy", "ZZZZZZZZZZZZZZZZZZZZZZ"), null);

        assertFalse(sent.bodyText().contains("ZZZZ"));
        assertFalse(sent.bodyText().contains("proxy"));
    }

    @Test
    public void aStrangerGetsForbiddenWhileTheRestrictionIsOn() throws Exception {
        Sent sent = get(STRANGER, null);

        assertEquals(403, sent.code());
        assertEquals("<html><body>Access denied</body></html>", sent.bodyText());
        assertEquals(0, active());
    }

    @Test
    public void theAddressIsCheckedBeforeTheDeviceId() throws Exception {
        // an invalid device id from a stranger must not tell the stranger that the key exists
        Sent sent = request("GET", STRANGER, List.of("proxy", "bad id!", key), null);

        assertEquals(403, sent.code());
    }

    @Test
    public void anUnknownKeyIsNotFoundEvenForAStrangerWithABadDeviceId() throws Exception {
        Sent sent = request("GET", STRANGER, List.of("proxy", "bad id!", "ZZZZZZZZZZZZZZZZZZZZZZ"), null);

        assertEquals(404, sent.code());
    }

    @Test
    public void aReceiverWithAnInvalidDeviceIdGetsNotFound() throws Exception {
        Sent sent = request("GET", RECEIVER, List.of("proxy", "bad id!", key), null);

        assertEquals(404, sent.code());
        assertEquals(0, active());
    }

    @Test
    public void aStrangerIsServedWhenTheRestrictionIsSwitchedOff() throws Exception {
        restrict = false;

        Sent sent = get(STRANGER, null);

        assertEquals(200, sent.code());
        sent.entity.releaseResources();
    }

    @Test
    public void aReceiverGetsTheWholeFileAndHoldsOneLeaseUntilTheResponseEnds() throws Exception {
        Sent sent = get(RECEIVER, null);

        assertEquals(200, sent.code());
        assertEquals(SIZE, sent.entity.getContentLength());
        assertEquals("video/mp4", sent.entity.getContentType());
        assertEquals("bytes", sent.response.getFirstHeader("Accept-Ranges").getValue());
        assertEquals(1, active());
        sent.entity.releaseResources();
        assertEquals(0, active());
    }

    @Test
    public void aRangeIsAnsweredWith206AndContentRange() throws Exception {
        Sent sent = get(RECEIVER, "bytes=100-199");

        assertEquals(206, sent.code());
        assertEquals("bytes 100-199/" + SIZE, sent.response.getFirstHeader("Content-Range").getValue());
        assertEquals(100L, sent.entity.getContentLength());
        sent.entity.releaseResources();
    }

    @Test
    public void anUnsatisfiableRangeIsAnswered416WithContentRangeAndNoLease() throws Exception {
        Sent sent = get(RECEIVER, "bytes=" + (SIZE + 10) + "-");

        assertEquals(416, sent.code());
        assertEquals("bytes */" + SIZE, sent.response.getFirstHeader("Content-Range").getValue());
        assertEquals(0, active());
    }

    @Test
    public void headTakesNoLeaseAndProducesNoData() throws Exception {
        Sent sent = request("HEAD", RECEIVER, List.of("proxy", key), null);

        assertEquals(200, sent.code());
        assertEquals(SIZE, sent.entity.getContentLength());
        assertEquals(0, active());
        assertEquals("", sent.bodyText());
    }

    @Test
    public void aFifthSimultaneousResponseGetsBusyAndLeavesTheOthersAlone() throws Exception {
        List<Sent> running = new ArrayList<>();
        for (int i = 0; i < ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE; i++) {
            Sent sent = get(RECEIVER, null);
            assertEquals(200, sent.code());
            running.add(sent);
        }

        Sent busy = get(RECEIVER, null);

        assertEquals(503, busy.code());
        assertEquals("<html><body>Busy</body></html>", busy.bodyText());
        assertEquals(ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE, active());
        running.get(0).entity.releaseResources();
        Sent again = get(RECEIVER, null);
        assertEquals(200, again.code());
        again.entity.releaseResources();
        for (int i = 1; i < running.size(); i++) {
            running.get(i).entity.releaseResources();
        }
        assertEquals(0, active());
    }

    @Test
    public void headIsStillAnsweredWhenAllSlotsAreBusy() throws Exception {
        List<Sent> running = new ArrayList<>();
        for (int i = 0; i < ShareLimits.MAX_CONCURRENT_RESPONSES_PER_SHARE; i++) {
            running.add(get(RECEIVER, null));
        }

        Sent head = request("HEAD", RECEIVER, List.of("proxy", key), null);

        assertEquals(200, head.code());
        running.forEach(sent -> sent.entity.releaseResources());
    }

    @Test
    public void aSourceThatIsClosingIsNotFound() throws Exception {
        source.close();

        Sent sent = get(RECEIVER, null);

        assertEquals(404, sent.code());
        assertEquals(0, active());
    }

    @Test
    public void aReleasedAndExpiredShareIsNotFound() throws Exception {
        registry.removeAll();

        assertEquals(404, get(RECEIVER, null).code());
    }

    @Test
    public void theLeaseGoesBackWhenTheResponseCannotBeSubmitted() throws Exception {
        ResponseTrigger failing = mock(ResponseTrigger.class);
        doThrow(new IOException("connection gone")).when(failing).submitResponse(any(), any());

        try {
            responder.respond(new Message<HttpRequest, byte[]>(new BasicHttpRequest("GET", "/proxy/" + key),
                    new byte[0]), failing, contextFrom(RECEIVER), false, List.of("proxy", key));
            fail("the failure is passed on");
        } catch (IOException expected) {
            assertEquals("no lease may leak", 0, active());
        }
    }

    @Test
    public void submitGivesTheLeaseBackWhenTheTriggerFailsAndPassesTheFailureOn() throws Exception {
        ShareConcurrencyLimiter.Lease lease = ShareConcurrencyLimiter.shared().tryAcquire(key);
        assertNotNull(lease);
        ResponseTrigger failing = mock(ResponseTrigger.class);
        IllegalStateException failure = new IllegalStateException("closed");
        doThrow(failure).when(failing).submitResponse(any(), any());

        try {
            ProxyShareResponder.submit(failing, HttpCoreContext.create(), AsyncResponseBuilder.create(200), lease);
            fail("the failure is passed on");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
        }

        assertEquals(0, active());
        assertFalse(lease.isLive());
    }

    @Test
    public void submitKeepsTheLeaseWhenTheResponseWasHandedOver() throws Exception {
        ShareConcurrencyLimiter.Lease lease = ShareConcurrencyLimiter.shared().tryAcquire(key);
        List<Sent> sent = new ArrayList<>();

        ProxyShareResponder.submit(recordingTrigger(sent), HttpCoreContext.create(),
                AsyncResponseBuilder.create(200), lease);

        assertEquals(1, active());
        assertTrue(lease.isLive());
        lease.release();
    }

    @Test
    public void aMissingEndpointAddressIsTreatedAsAStrangerWhileTheRestrictionIsOn() throws Exception {
        HttpCoreContext noEndpoint = HttpCoreContext.create();
        List<Sent> sent = new ArrayList<>();

        responder.respond(new Message<HttpRequest, byte[]>(new BasicHttpRequest("GET", "/proxy/" + key),
                new byte[0]), recordingTrigger(sent), noEndpoint, false, List.of("proxy", key));

        assertEquals(403, sent.get(0).code());
    }

    @Test
    public void aHeldFileIsServedWithItsMediaTypeWhenThatIsAcceptable() throws Exception {
        Sent sent = get(RECEIVER, null);

        assertTrue(sent.entity.getContentType().startsWith("video/mp4"));
    }

    @Test
    public void aHeldFileEntryWithFreeTextAsMimeTypeIsServedAsOctetStream() throws Exception {
        String other = registry.register(new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE,
                new ArraySource(ProducerTestSupport.body(100)), "text/html; x=y\r\nX-Evil: 1",
                Set.of(RECEIVER), ShareLimits.IDLE_GRACE_MILLIS, ShareLimits.ABSOLUTE_MAX_MILLIS));
        assertNotNull(other);

        Sent sent = request("GET", RECEIVER, List.of("proxy", other), null);

        assertEquals(200, sent.code());
        assertTrue(sent.entity.getContentType().startsWith("application/octet-stream"));
    }

    @Test
    public void onlyAConcreteAudioVideoOrImageTypeIsKeptForAHeldFile() {
        assertEquals("audio/mpeg", ProxyShareResponder.localFileContentType("audio/mpeg").getMimeType());
        assertEquals("application/octet-stream", ProxyShareResponder.localFileContentType("text/html").getMimeType());
        assertEquals("application/octet-stream", ProxyShareResponder.localFileContentType("image/*").getMimeType());
        assertEquals("application/octet-stream", ProxyShareResponder.localFileContentType(null).getMimeType());
    }
}
