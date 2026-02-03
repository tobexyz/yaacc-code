package de.yaacc.upnp.server.http;

import android.os.Build;
import android.util.Log;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class HttpRequestSender {

    final private CloseableHttpClient httpClient;

    public HttpRequestSender() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setSocketTimeout(Timeout.of(60, TimeUnit.SECONDS))
                .setValidateAfterInactivity(TimeValue.of(10, TimeUnit.MILLISECONDS))
                .build());
        connectionManager.setMaxTotal(10);
        httpClient = HttpClientBuilder.create().setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE).setConnectionManager(connectionManager).build();
    }

    public StreamResponseMessage send(StreamRequestMessage requestMessage) throws IOException {

        Log.v(getClass().getName(), "Sending HTTP request: " + requestMessage);
        Log.v(getClass().getName(), "HTTP body: " + requestMessage.getBodyString());
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpUriRequestBase request = new HttpUriRequestBase(requestMessage.getOperation().getHttpMethodName(), requestMessage.getUri());
        applyRequestHeader(requestMessage, request);
        applyRequestBody(requestMessage, request);
        return httpClient.execute(request, this::createResponse);
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

            Log.d(getClass().getName(), "Setting header '" + UpnpHeader.Type.USER_AGENT.getHttpName() + "': " + value);
            request.addHeader(UpnpHeader.Type.USER_AGENT.getHttpName(), value);
        }
        for (Map.Entry<String, List<String>> entry : requestMessage.getHeaders().entrySet()) {
            for (String v : entry.getValue()) {
                String headerName = entry.getKey();
                Log.d(getClass().getName(), "Setting header '" + headerName + "': " + v);
                request.addHeader(headerName, v);
            }
        }
    }

    private void applyRequestBody(StreamRequestMessage requestMessage, ClassicHttpRequest request) {
        // Body
        if (requestMessage.hasBody()) {
            Log.d(getClass().getName(), "Writing textual request body: " + requestMessage);
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
        if (UpnpResponse.Status.getByStatusCode(response.getCode()) == null) {
            throw new IllegalStateException("can't create UpnpResponse.Status from http response status: " + response.getCode());
        }
        UpnpResponse responseOperation =
                new UpnpResponse(
                        response.getCode(),
                        Objects.requireNonNull(UpnpResponse.Status.getByStatusCode(response.getCode())).getStatusMsg()
                );
        Log.d(getClass().getName(), "Received response: " + responseOperation);
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
            Log.d(getClass().getName(), "Response contains textual entity body, converting then setting string on message");
            try {
                responseMessage.setBodyCharacters(bytes);
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException("Unsupported character encoding: " + ex, ex);
            }
        } else if (bytes != null && bytes.length > 0) {
            Log.d(getClass().getName(), "Response contains binary entity body, setting bytes on message");
            responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes);
        } else {
            Log.d(getClass().getName(), "Response did not contain entity body");
        }
        Log.d(getClass().getName(), "Response message complete: " + responseMessage);
        return responseMessage;
    }

}
