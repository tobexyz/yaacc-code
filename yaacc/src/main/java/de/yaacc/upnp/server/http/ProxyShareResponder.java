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

import android.content.Context;
import android.content.SharedPreferences;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler.ResponseTrigger;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.seamless.util.MimeType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.share.ByteRangeResolver;
import de.yaacc.share.HttpLinkTarget;
import de.yaacc.share.PositionalSource;
import de.yaacc.share.ProxyAccessPolicy;
import de.yaacc.share.RendererIdPolicy;
import de.yaacc.share.ServerSideSeeking;
import de.yaacc.share.RangeStreamer;
import de.yaacc.share.ShareConcurrencyLimiter;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.settings.SettingsFragment;
import de.yaacc.upnp.server.http.YaaccUpnpServerContentHttpHandler.ContentHolder;
import de.yaacc.util.HttpRange;
import de.yaacc.util.YaaccLogger;

/**
 * Answers requests for {@code /proxy/<key>}: the shares of the in-memory {@link ShareRegistry}.
 * Runs before the handler's preference gate (the HTTP server always runs while the process
 * lives; a share is reachable only by its random key).
 * <p>
 * Decisions, in order (every answer for a bad key is the same constant page, no path, key or
 * link is ever echoed or logged):
 * <ol>
 * <li>path is not {@code /proxy/<well-formed key>} or {@code /proxy/<deviceId>/<well-formed key>}, no registry, unknown, expired or released-and-over key: 404</li>
 * <li>remote address (only from the connection's endpoint details, never from a header) not allowed by
 * {@link ProxyAccessPolicy} for the entry: 403, on every request including {@code HEAD} and range continuations</li>
 * <li>LOCAL_FILE: {@link ByteRangeResolver} against the held source: 200, 206 or 416</li>
 * <li>HTTP_LINK: the existing upstream relay; a {@code Range} is re-serialized from the parsed
 * range, never passed through; only http(s) is fetched</li>
 * <li>a GET that would exceed {@link ShareLimits#MAX_CONCURRENT_RESPONSES_PER_SHARE} running responses of the share: 503</li>
 * </ol>
 */
final class ProxyShareResponder {

    private static final String NOT_FOUND_BODY = "<html><body>Not found</body></html>";
    private static final String FORBIDDEN_BODY = "<html><body>Access denied</body></html>";
    private static final String BUSY_BODY = "<html><body>Busy</body></html>";
    private static final String RANGE_BODY = "<html><body>Range not satisfiable</body></html>";
    private static final String UPSTREAM_BODY = "<html><body>Unavailable</body></html>";

    /** Running responses per share, for the whole process. */
    private static final ShareConcurrencyLimiter LIMITER = ShareConcurrencyLimiter.shared();

    /** Preference that holds the playback position (ms) after the last seek, per renderer. */
    static final String SEEK_POSITION_PREFERENCE_PREFIX = "server_position_";

    private final Context context;
    private final SharedPreferences preferences;

    ProxyShareResponder(Context context, SharedPreferences preferences) {
        this.context = context;
        this.preferences = preferences;
    }

    void respond(Message<HttpRequest, byte[]> request, ResponseTrigger responseTrigger,
                 HttpContext httpContext, boolean head, List<String> pathSegments)
            throws HttpException, IOException {
        // /proxy/<key>, or /proxy/<deviceId>/<key> from a renderer with server-side time range management
        String key = pathSegments.size() == 2 ? pathSegments.get(1)
                : pathSegments.size() == 3 ? pathSegments.get(2) : null;
        String deviceId = pathSegments.size() == 3 ? pathSegments.get(1) : null;
        ShareRegistry registry = registryOf();
        ShareRegistry.Entry entry = registry != null && ShareRegistry.isWellFormedKey(key)
                ? registry.lookup(key) : null;
        if (entry == null) {
            submitConstant(responseTrigger, httpContext, HttpStatus.SC_NOT_FOUND, NOT_FOUND_BODY);
            return;
        }
        String remote = remoteAddressOf(httpContext);
        if (!ProxyAccessPolicy.isAllowed(restrictToReceivers(), entry.getAllowedAddresses(), remote)) {
            // no key, no link: the address is what a user needs to see why a receiver was refused
            YaaccLogger.i(getClass().getName(), "Media proxy refused a request from "
                    + (remote == null ? "an unknown address" : remote));
            submitConstant(responseTrigger, httpContext, HttpStatus.SC_FORBIDDEN, FORBIDDEN_BODY);
            return;
        }
        if (deviceId != null && !RendererIdPolicy.isValid(deviceId)) {
            // untrusted: only checked after the key and the address were accepted
            submitConstant(responseTrigger, httpContext, HttpStatus.SC_NOT_FOUND, NOT_FOUND_BODY);
            return;
        }
        Header rangeHeader = request.getHead().getHeader(HttpHeaders.RANGE);
        String rangeValue = rangeHeader == null ? null : rangeHeader.getValue();
        BooleanSupplier live = () -> registry.lookup(key) != null;
        if (entry.getKind() == ShareRegistry.Kind.LOCAL_FILE
                && entry.getTarget() instanceof PositionalSource) {
            respondFile(responseTrigger, httpContext, entry, (PositionalSource) entry.getTarget(), key,
                    rangeValue, head, live);
        } else if (entry.getKind() == ShareRegistry.Kind.HTTP_LINK
                && entry.getTarget() instanceof HttpLinkTarget) {
            respondLink(responseTrigger, httpContext, entry, (HttpLinkTarget) entry.getTarget(), key,
                    deviceId, rangeValue, head, live);
        } else {
            submitConstant(responseTrigger, httpContext, HttpStatus.SC_NOT_FOUND, NOT_FOUND_BODY);
        }
    }

    // ---- LOCAL_FILE

    private void respondFile(ResponseTrigger trigger, HttpContext httpContext, ShareRegistry.Entry entry,
                             PositionalSource source, String key, String rangeValue, boolean head,
                             BooleanSupplier live) throws HttpException, IOException {
        if (source.isClosing()) {
            submitConstant(trigger, httpContext, HttpStatus.SC_NOT_FOUND, NOT_FOUND_BODY);
            return;
        }
        ByteRangeResolver.Result result = ByteRangeResolver.resolve(
                ByteRangeResolver.parseHeader(rangeValue), source.size());
        if (result.status() == HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE) {
            AsyncResponseBuilder builder = AsyncResponseBuilder.create(result.status());
            builder.setHeader(HttpHeaders.CONTENT_RANGE, result.contentRangeHeader());
            builder.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            builder.setEntity(AsyncEntityProducers.create(RANGE_BODY, ContentType.TEXT_HTML));
            trigger.submitResponse(builder.build(), httpContext);
            return;
        }
        ContentType contentType = localFileContentType(entry.getMimeType());
        AsyncResponseBuilder builder = AsyncResponseBuilder.create(result.status());
        if (result.status() == HttpStatus.SC_PARTIAL_CONTENT) {
            builder.setHeader(HttpHeaders.CONTENT_RANGE, result.contentRangeHeader());
        }
        ShareConcurrencyLimiter.Lease lease = null;
        AsyncEntityProducer producer;
        if (head) {
            producer = new LengthOnlyEntityProducer(result.length(), contentType);
        } else {
            lease = LIMITER.tryAcquire(key);
            if (lease == null) {
                submitConstant(trigger, httpContext, HttpStatus.SC_SERVICE_UNAVAILABLE, BUSY_BODY);
                return;
            }
            try {
                producer = new ShareEntityProducer(new RangeStreamer(source, result, ShareLimits.CHUNK_BYTES),
                        result.length(), contentType, live, lease);
            } catch (RuntimeException e) {
                // the slot goes back at once instead of waiting for the idle watchdog
                lease.release();
                throw e;
            }
        }
        addStreamingHeaders(builder);
        builder.setEntity(producer);
        submit(trigger, httpContext, builder, lease);
    }

    // ---- HTTP_LINK

    private void respondLink(ResponseTrigger trigger, HttpContext httpContext, ShareRegistry.Entry entry,
                             HttpLinkTarget target, String key, String deviceId, String rangeValue,
                             boolean head, BooleanSupplier live) throws HttpException, IOException {
        // the scheme is checked again where the link is fetched: nothing but http(s) leaves this class
        if (!HttpLinkTarget.isFetchable(target.getUrl())) {
            submitConstant(trigger, httpContext, HttpStatus.SC_NOT_FOUND, NOT_FOUND_BODY);
            return;
        }
        MimeType mimeType = mimeTypeOf(entry.getMimeType());
        ContentType contentType = contentTypeOf(mimeType.toString());
        List<ByteRangeResolver.Range> parsed = ByteRangeResolver.parseHeader(rangeValue);
        ByteRangeResolver.Range requested = parsed.size() == 1 && parsed.get(0).isValid() ? parsed.get(0) : null;
        // server-side time range management: only for a renderer that has the per-device setting on
        boolean serverSeeking = deviceId != null && !parsed.isEmpty() && serverSideSeekingEnabled(deviceId);

        int status = HttpStatus.SC_OK;
        String contentRange = null;
        long headLength = -1L;
        List<HttpRange> upstreamRanges = Collections.emptyList();
        if (requested != null || head || serverSeeking) {
            // blocking HEAD to the remote host (5 s timeouts) on the I/O thread, as before
            long length = new ContentHolder(mimeType, target.getUrl(), Collections.emptyList(), context)
                    .getContentLength();
            headLength = length;
            if (serverSeeking) {
                long position = ServerSideSeeking.plan(target.getRendererStates(), deviceId,
                        () -> storedSeekPosition(deviceId), length);
                if (position > 0L) {
                    // replaces the range the renderer asked for by the position the server computed
                    requested = ByteRangeResolver.Range.from(position);
                }
            }
            if (requested != null) {
                if (length > 0L) {
                    ByteRangeResolver.Result result = ByteRangeResolver.resolve(
                            Collections.singletonList(requested), length);
                    if (result.status() == HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE) {
                        AsyncResponseBuilder builder = AsyncResponseBuilder.create(result.status());
                        builder.setHeader(HttpHeaders.CONTENT_RANGE, result.contentRangeHeader());
                        builder.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                        builder.setEntity(AsyncEntityProducers.create(RANGE_BODY, ContentType.TEXT_HTML));
                        trigger.submitResponse(builder.build(), httpContext);
                        return;
                    }
                    status = result.status();
                    contentRange = result.contentRangeHeader();
                    headLength = result.length();
                    // re-serialized from the resolved numbers, not from the client's text
                    upstreamRanges = Collections.singletonList(
                            new HttpRange("bytes", result.start(), result.endInclusive(), null));
                } else {
                    // length unknown: relay the parsed range and answer 206 without Content-Range, as before
                    status = HttpStatus.SC_PARTIAL_CONTENT;
                    upstreamRanges = Collections.singletonList(toHttpRange(requested));
                }
            }
        }
        AsyncResponseBuilder builder = AsyncResponseBuilder.create(status);
        if (contentRange != null) {
            builder.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
        }
        ShareConcurrencyLimiter.Lease lease = null;
        AsyncEntityProducer producer;
        if (head) {
            producer = new LengthOnlyEntityProducer(headLength > 0L ? headLength : -1L, contentType);
        } else {
            lease = LIMITER.tryAcquire(key);
            if (lease == null) {
                submitConstant(trigger, httpContext, HttpStatus.SC_SERVICE_UNAVAILABLE, BUSY_BODY);
                return;
            }
            try {
                producer = new GuardedEntityProducer(
                        new ContentHolder(mimeType, target.getUrl(), new ArrayList<>(upstreamRanges), context)
                                .getEntityProducer(), live, lease);
            } catch (IOException | RuntimeException e) {
                lease.release();
                YaaccLogger.w(getClass().getName(), "Media proxy could not open the upstream ("
                        + e.getClass().getSimpleName() + ")");
                submitConstant(trigger, httpContext, HttpStatus.SC_BAD_GATEWAY, UPSTREAM_BODY);
                return;
            }
        }
        addStreamingHeaders(builder);
        builder.setEntity(producer);
        submit(trigger, httpContext, builder, lease);
    }

    /** The per-device setting; the id was validated, default false, a read failure counts as off. */
    private boolean serverSideSeekingEnabled(String deviceId) {
        try {
            return preferences.getBoolean(SettingsFragment.MANAGE_EXTERNAL_SEEKING + deviceId, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Position (ms) stored after the last seek on this renderer; 0 if none. */
    private long storedSeekPosition(String deviceId) {
        try {
            return preferences.getLong(SEEK_POSITION_PREFERENCE_PREFIX + deviceId, 0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static HttpRange toHttpRange(ByteRangeResolver.Range range) {
        if (range.isSuffix()) {
            return new HttpRange("bytes", null, null, range.suffixLength());
        }
        return new HttpRange("bytes", range.first(),
                range.last() == ByteRangeResolver.OPEN ? null : range.last(), null);
    }

    // ---- helpers

    /** Sends the response; if that fails the lease goes back at once (the producer never ran). */
    static void submit(ResponseTrigger trigger, HttpContext httpContext, AsyncResponseBuilder builder,
                       ShareConcurrencyLimiter.Lease lease) throws HttpException, IOException {
        builder.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        try {
            trigger.submitResponse(builder.build(), httpContext);
        } catch (HttpException | IOException | RuntimeException e) {
            if (lease != null) {
                lease.release();
            }
            throw e;
        }
    }

    private static void addStreamingHeaders(AsyncResponseBuilder builder) {
        builder.setHeader(HttpHeaders.CONNECTION, "close");
        builder.setHeader("transferMode.dlna.org", "Streaming");
        builder.setHeader("contentFeatures.dlna.org",
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    }

    private static void submitConstant(ResponseTrigger trigger, HttpContext httpContext, int status,
                                       String body) throws HttpException, IOException {
        AsyncResponseBuilder builder = AsyncResponseBuilder.create(status);
        builder.setEntity(AsyncEntityProducers.create(body, ContentType.TEXT_HTML));
        trigger.submitResponse(builder.build(), httpContext);
    }

    private ShareRegistry registryOf() {
        try {
            return ((Yaacc) context.getApplicationContext()).getShareRegistry();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Default, and on any failure, is true: the check is on unless the user switched it off. */
    private boolean restrictToReceivers() {
        try {
            return preferences.getBoolean(context.getString(R.string.settings_proxy_restrict_receivers_chkbx), true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The address of the peer of this connection, from the endpoint details only (never from a
     * header, never a host name lookup); null when it is not an IP socket address.
     */
    static String remoteAddressOf(HttpContext httpContext) {
        try {
            EndpointDetails details = HttpCoreContext.cast(httpContext).getEndpointDetails();
            if (details != null) {
                SocketAddress address = details.getRemoteAddress();
                if (address instanceof InetSocketAddress) {
                    InetAddress inet = ((InetSocketAddress) address).getAddress();
                    if (inet != null) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (RuntimeException e) {
            // treated as a missing address
        }
        return null;
    }

    private static MimeType mimeTypeOf(String mimeType) {
        try {
            if (mimeType != null) {
                return MimeType.valueOf(mimeType);
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return MimeType.valueOf("application/octet-stream");
    }

    /**
     * The Content-Type of a held file: the entry's MIME type only if it is a concrete audio,
     * video or image type (checked again here, so free text can never reach the header), else
     * {@code application/octet-stream}.
     */
    static ContentType localFileContentType(String mimeType) {
        if (SharedContentPolicy.isAcceptableMimeType(mimeType)) {
            return contentTypeOf(mimeType);
        }
        return ContentType.APPLICATION_OCTET_STREAM;
    }

    private static ContentType contentTypeOf(String mimeType) {
        try {
            ContentType parsed = mimeType == null ? null : ContentType.parse(mimeType);
            if (parsed != null) {
                return parsed;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return ContentType.APPLICATION_OCTET_STREAM;
    }
}
