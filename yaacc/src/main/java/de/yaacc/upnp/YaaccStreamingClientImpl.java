/*
 *
 * Copyright (C) 2023 Tobias Schoene www.yaacc.de
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
package de.yaacc.upnp;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.transport.spi.AbstractStreamClient;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.StreamClient;
import org.seamless.util.MimeType;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class YaaccStreamingClientImpl extends AbstractStreamClient<YaaccStreamingClientConfigurationImpl, ClassicHttpRequest> {

    final private static Logger log = Logger.getLogger(StreamClient.class.getName());

    final protected YaaccStreamingClientConfigurationImpl configuration;
    final private CloseableHttpClient httpClient;

    public YaaccStreamingClientImpl(YaaccStreamingClientConfigurationImpl configuration) throws InitializationException {
        this.configuration = configuration;
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setValidateAfterInactivity(TimeValue.of(10, TimeUnit.MILLISECONDS));
        httpClient = HttpClientBuilder.create().setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE).setConnectionManager(connectionManager).build();
    }

    @Override
    public YaaccStreamingClientConfigurationImpl getConfiguration() {
        return configuration;
    }

    @Override
    protected ClassicHttpRequest createRequest(StreamRequestMessage requestMessage) {
        return new HttpUriRequestBase(requestMessage.getOperation().getHttpMethodName(), requestMessage.getUri());
    }

    @Override
    protected Callable<StreamResponseMessage> createCallable(final StreamRequestMessage requestMessage,
                                                             final ClassicHttpRequest request) {
        return new Callable<StreamResponseMessage>() {
            public StreamResponseMessage call() throws Exception {

                log.info("Sending HTTP request: " + requestMessage);
                log.info("Body: " + requestMessage.getBodyString());
                applyRequestHeader(requestMessage, request);
                applyRequestBody(requestMessage, request);
                return httpClient.execute(request, new HttpClientResponseHandler<StreamResponseMessage>() {

                    @Override
                    public StreamResponseMessage handleResponse(ClassicHttpResponse response) throws HttpException, IOException {
                        return createResponse(response);
                    }
                });

            }
        };
    }

    @Override
    protected boolean abort(ClassicHttpRequest request, String reason) {
        log.info("Received request abort, ignoring it!! Reason:" + reason);
        return true;
    }

    @Override
    protected boolean logExecutionException(Throwable t) {
        return false;
    }

    @Override
    public void stop() {
        try {
            httpClient.close();
        } catch (Exception ex) {
            log.info("Error stopping HTTP client: " + ex);
        }
    }

    private void applyRequestHeader(StreamRequestMessage requestMessage, ClassicHttpRequest request) {
        if (!requestMessage.getHeaders().containsKey(UpnpHeader.Type.USER_AGENT)) {
            String value = getConfiguration().getUserAgentValue(
                    requestMessage.getUdaMajorVersion(),
                    requestMessage.getUdaMinorVersion());
            if (log.isLoggable(Level.FINE))
                log.fine("Setting header '" + UpnpHeader.Type.USER_AGENT.getHttpName() + "': " + value);
            request.addHeader(UpnpHeader.Type.USER_AGENT.getHttpName(), value);
        }
        for (Map.Entry<String, List<String>> entry : requestMessage.getHeaders().entrySet()) {
            for (String v : entry.getValue()) {
                String headerName = entry.getKey();
                if (log.isLoggable(Level.FINE))
                    log.fine("Setting header '" + headerName + "': " + v);
                request.addHeader(headerName, v);
            }
        }
    }

    private void applyRequestBody(StreamRequestMessage requestMessage, ClassicHttpRequest request) {
        // Body
        if (requestMessage.hasBody()) {

            if (log.isLoggable(Level.FINE))
                log.fine("Writing textual request body: " + requestMessage);

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


    protected StreamResponseMessage createResponse(ClassicHttpResponse response) throws IOException {
        // Status
        UpnpResponse responseOperation =
                new UpnpResponse(
                        response.getCode(),
                        UpnpResponse.Status.getByStatusCode(response.getCode()).getStatusMsg()
                );


        log.info("Received response: " + responseOperation);

        StreamResponseMessage responseMessage = new StreamResponseMessage(responseOperation);

        // Headers
        UpnpHeaders headers = new UpnpHeaders();
        Header[] responseFields = response.getHeaders();
        for (Header header : responseFields) {
            headers.add(header.getName(), header.getValue());
        }
        responseMessage.setHeaders(headers);

        // Body
        byte[] bytes = EntityUtils.toByteArray(response.getEntity());
        if (bytes != null && bytes.length > 0 && responseMessage.isContentTypeMissingOrText()) {


            log.info("Response contains textual entity body, converting then setting string on message");
            try {
                responseMessage.setBodyCharacters(bytes);
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException("Unsupported character encoding: " + ex, ex);
            }

        } else if (bytes != null && bytes.length > 0) {

            if (log.isLoggable(Level.FINE))
                log.fine("Response contains binary entity body, setting bytes on message");
            responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes);

        } else {
            if (log.isLoggable(Level.FINE))
                log.fine("Response did not contain entity body");
        }

        if (log.isLoggable(Level.FINE))
            log.fine("Response message complete: " + responseMessage);

        return responseMessage;
    }

}

