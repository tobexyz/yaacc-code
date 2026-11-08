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
package de.yaacc.upnp.server.http;

import android.os.Build;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.fourthline.cling.model.ServerClientTokens;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.seamless.util.MimeType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import de.yaacc.share.ShareLogRedactor;
import de.yaacc.util.YaaccLogger;

public class HttpRequestSender {

    /**
     * How long a ContentDirectory action (Browse, Search, ...) may take until the response arrives.
     * The server may need seconds to answer a request for a big folder (this app's own server
     * included), so such actions are not cut off by the 10 s socket timeout that all other
     * requests keep.
     */
    static final Timeout CONTENT_DIRECTORY_RESPONSE_TIMEOUT = Timeout.of(30, TimeUnit.SECONDS);

    /** The most bytes of the body of the answer to a ContentDirectory action (a Browse of a huge folder is some megabytes). */
    static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    /** The most bytes of any other answer: descriptions, control and event answers are some kilobytes. */
    static final int MAX_OTHER_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** How long reading the body of an answer may take altogether; the socket timeout only limits the wait for the next bytes. */
    static final long RESPONSE_DEADLINE_MILLIS = 120_000;

    private static final String CONTENT_DIRECTORY_SERVICE_TYPE = ":service:ContentDirectory:";

    final private CloseableHttpClient httpClient;
    private final int maxResponseBytes;
    private final long responseDeadlineMillis;

    public HttpRequestSender() {
        this(MAX_RESPONSE_BYTES, RESPONSE_DEADLINE_MILLIS);
    }

    /** For tests: other limits for the body of an answer. */
    HttpRequestSender(int maxResponseBytes, long responseDeadlineMillis) {
        this.maxResponseBytes = maxResponseBytes;
        this.responseDeadlineMillis = responseDeadlineMillis;
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(5, TimeUnit.SECONDS))  // Connection timeout
                .setSocketTimeout(Timeout.of(10, TimeUnit.SECONDS))   // Reduced from 60s
                .setValidateAfterInactivity(TimeValue.of(10, TimeUnit.MILLISECONDS))
                .build());
        connectionManager.setMaxTotal(10);
        httpClient = HttpClientBuilder.create().setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE).setConnectionManager(connectionManager).build();
    }

    public StreamResponseMessage send(StreamRequestMessage requestMessage) throws IOException {

        YaaccLogger.v(getClass().getName(), "Sending HTTP request: " + ShareLogRedactor.redact(String.valueOf(requestMessage)));
        YaaccLogger.v(getClass().getName(), "HTTP body: " + ShareLogRedactor.redact(requestMessage.getBodyString()));
        HttpUriRequestBase request = new HttpUriRequestBase(requestMessage.getOperation().getHttpMethodName(), requestMessage.getUri());
        applyRequestHeader(requestMessage, request);
        applyRequestBody(requestMessage, request);
        if (isContentDirectoryAction(requestMessage)) {
            request.setConfig(RequestConfig.custom().setResponseTimeout(CONTENT_DIRECTORY_RESPONSE_TIMEOUT).build());
        }
        int limit = isContentDirectoryAction(requestMessage) ? maxResponseBytes : Math.min(maxResponseBytes, MAX_OTHER_RESPONSE_BYTES);
        return httpClient.execute(request, response -> {
            try {
                return createResponse(response, limit);
            } catch (IOException | RuntimeException e) {
                // the connection is dropped at once: leaving the handler would read the rest of a body that may never end
                request.abort();
                throw e;
            }
        });
    }

    private static boolean isContentDirectoryAction(StreamRequestMessage requestMessage) {
        return isContentDirectoryAction(requestMessage.getHeaders().getFirstHeaderString(UpnpHeader.Type.SOAPACTION));
    }

    /**
     * @param soapActionHeader the SOAPACTION header value, e.g.
     *                         {@code "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"}
     * @return true if the action belongs to a ContentDirectory service
     */
    static boolean isContentDirectoryAction(String soapActionHeader) {
        return soapActionHeader != null && soapActionHeader.contains(CONTENT_DIRECTORY_SERVICE_TYPE);
    }


    public String getUserAgentValue(int majorVersion, int minorVersion) {
        // TODO: UPNP VIOLATION: Synology NAS requires User-Agent to contain
        // "Android" to return DLNA protocolInfo required to stream to Samsung TV
        // see: http://two-play.com/forums/viewtopic.php?f=6&t=81
        ServerClientTokens tokens = new ServerClientTokens(majorVersion, minorVersion);
        tokens.setOsName("Android");
        tokens.setOsVersion(Build.VERSION.RELEASE);
        return tokens.toString();
    }

    private void applyRequestHeader(StreamRequestMessage requestMessage, ClassicHttpRequest request) {
        if (!requestMessage.getHeaders().containsKey(UpnpHeader.Type.USER_AGENT)) {
            String value = getUserAgentValue(
                    requestMessage.getUdaMajorVersion(),
                    requestMessage.getUdaMinorVersion());

            YaaccLogger.d(getClass().getName(), "Setting header '" + UpnpHeader.Type.USER_AGENT.getHttpName() + "': " + value);
            request.addHeader(UpnpHeader.Type.USER_AGENT.getHttpName(), value);
        }
        for (Map.Entry<String, List<String>> entry : requestMessage.getHeaders().entrySet()) {
            for (String v : entry.getValue()) {
                String headerName = entry.getKey();
                YaaccLogger.d(getClass().getName(), "Setting header '" + headerName + "': " + v);
                request.addHeader(headerName, v);
            }
        }
    }

    private void applyRequestBody(StreamRequestMessage requestMessage, ClassicHttpRequest request) {
        // Body
        if (requestMessage.hasBody()) {
            YaaccLogger.d(getClass().getName(), "Writing textual request body: " + ShareLogRedactor.redact(String.valueOf(requestMessage)));
            MimeType contentType =
                    requestMessage.getContentTypeHeader() != null
                            ? requestMessage.getContentTypeHeader().getValue()
                            : ContentTypeHeader.DEFAULT_CONTENT_TYPE_UTF8;
            String charset =
                    requestMessage.getContentTypeCharset() != null
                            ? requestMessage.getContentTypeCharset()
                            : "UTF-8";
            byte[] content = requestMessage.getBodyString().getBytes(Charset.forName(charset));
            request.setEntity(new ByteArrayEntity(content, ContentType.parse(contentType.toString())));
        }
    }


    /**
     * Reads the body of an answer. The device is not trusted to stop: a body over the limit, or one that is still coming after the
     * deadline, ends in an exception.
     *
     * @return the body, null for an answer without one
     */
    static byte[] readBounded(HttpEntity entity, int maxBytes, long deadlineMillis) throws IOException {
        if (entity == null) {
            return null;
        }
        if (entity.getContentLength() > maxBytes) {
            throw new IOException("the answer is larger than " + maxBytes + " bytes");
        }
        long end = System.nanoTime() + deadlineMillis * 1_000_000L;
        InputStream in = entity.getContent();
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (out.size() + (long) read > maxBytes) {
                // the stream is NOT closed here: closing reads the rest of the body, which may never end. The exception leaves
                // the client, which drops the connection.
                throw new IOException("the answer is larger than " + maxBytes + " bytes");
            }
            out.write(chunk, 0, read);
            if (System.nanoTime() - end > 0) {
                throw new IOException("the answer took longer than " + deadlineMillis + " ms");
            }
        }
        in.close(); // read completely: the connection goes back to the pool
        return out.toByteArray();
    }

    protected StreamResponseMessage createResponse(ClassicHttpResponse response) throws IOException {
        return createResponse(response, maxResponseBytes);
    }

    private StreamResponseMessage createResponse(ClassicHttpResponse response, int maxBytes) throws IOException {
        YaaccLogger.d(getClass().getName(), "Received response code: " + response.getCode());
        UpnpResponse.Status mappedStatus = UpnpResponse.Status.getByStatusCode(response.getCode());
        UpnpResponse responseOperation;
        if (mappedStatus != null) {
            responseOperation = new UpnpResponse(response.getCode(), mappedStatus.getStatusMsg());
        } else {
            // Some real-world UPnP devices return HTTP statuses outside the small canonical
            // UpnpResponse.Status enum (e.g. Sky Q DVRs return 401 on description fetches by
            // design - see issue #232). Synthesize a generic failed UpnpResponse instead of
            // throwing - the caller path (RetrieveRemoteDescriptors.describe) already treats
            // isFailed() responses as a per-device skip, so unmapped statuses flow through
            // the same skip path.
            YaaccLogger.w(getClass().getName(),
                    "Unmapped HTTP status " + response.getCode()
                            + " from upstream UPnP device - treating as non-UPnP failure response, skipping device");
            responseOperation = new UpnpResponse(response.getCode(), "HTTP " + response.getCode());
        }
        YaaccLogger.d(getClass().getName(), "Received response: " + ShareLogRedactor.redact(String.valueOf(responseOperation)));
        StreamResponseMessage responseMessage = new StreamResponseMessage(responseOperation);
        // Headers
        UpnpHeaders headers = new UpnpHeaders();
        Header[] responseFields = response.getHeaders();
        for (Header header : responseFields) {
            headers.add(header.getName(), header.getValue());
        }
        responseMessage.setHeaders(headers);
        // Body
        byte[] bytes = readBounded(response.getEntity(), maxBytes, responseDeadlineMillis);
        if (bytes != null && bytes.length > 0 && responseMessage.isContentTypeMissingOrText()) {
            YaaccLogger.d(getClass().getName(), "Response contains textual entity body, converting then setting string on message");
            try {
                responseMessage.setBodyCharacters(bytes);
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException("Unsupported character encoding: " + ex, ex);
            }
        } else if (bytes != null && bytes.length > 0) {
            YaaccLogger.d(getClass().getName(), "Response contains binary entity body, setting bytes on message");
            responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes);
        } else {
            YaaccLogger.d(getClass().getName(), "Response did not contain entity body");
        }
        YaaccLogger.d(getClass().getName(), "Response message complete: " + ShareLogRedactor.redact(String.valueOf(responseMessage)));
        return responseMessage;
    }

}
