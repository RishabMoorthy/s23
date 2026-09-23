package org.framework.core;

import com.sun.net.httpserver.Headers;
import org.common.db.config.ConfigLoader;
import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the destination server for the two modes that route traffic to it.
 *
 * <p>The distinction that matters to Failover is between <em>the server answered</em> and
 * <em>the call never completed</em>. Any HTTP status - including 500 - is an answer and is
 * returned as-is; only a transport failure (connection refused, timeout, DNS, reset, TLS)
 * counts as a failed call, and only that makes Failover serve the mock response instead.
 *
 * <p>Failover uses shorter timeouts than Live Invocation by default, because a caller waiting
 * out the full Live Invocation read timeout defeats the point of having a fallback. All four
 * values can be overridden in config.properties.
 */
public class LiveInvocation {

    /** Reported as the response name and source in the request logs. */
    public static final String LIVE_RESPONSE_NAME = "Live Response";
    public static final String LIVE_RESPONSE_PARTIAL_NAME = "Live Response (partial)";
    private static final String LIVE_SOURCE = "live";

    private static final int DEFAULT_LIVE_CONNECT_SECONDS = 30;
    private static final int DEFAULT_LIVE_REQUEST_SECONDS = 120;
    private static final int DEFAULT_FAILOVER_CONNECT_SECONDS = 10;
    private static final int DEFAULT_FAILOVER_REQUEST_SECONDS = 30;

    /**
     * The outcome of one live call.
     *
     * <p>{@link #isLiveAnswered()} is the only thing Failover needs: true means the
     * destination server produced an HTTP response and {@link #getResponse()} carries it.
     */
    public static final class LiveResult {

        private final MockResponse response;
        private final boolean liveAnswered;
        private final String failureReason;

        private LiveResult(MockResponse response, boolean liveAnswered, String failureReason) {
            this.response = response;
            this.liveAnswered = liveAnswered;
            this.failureReason = failureReason;
        }

        static LiveResult answered(MockResponse response) {
            return new LiveResult(response, true, null);
        }

        static LiveResult failed(MockResponse response, String reason) {
            return new LiveResult(response, false, reason);
        }

        /** True when the destination server returned an HTTP response, whatever its status. */
        public boolean isLiveAnswered() {
            return liveAnswered;
        }

        /** The live response, or - when the call failed and the mode is not Failover - an error response. */
        public MockResponse getResponse() {
            return response;
        }

        /** Why the call failed; null when it did not. */
        public String getFailureReason() {
            return failureReason;
        }
    }

    /**
     * Existing entry point: Live Invocation semantics, where a failed call is surfaced to the
     * caller as a 500 rather than falling back.
     */
    public MockResponse getLiveResponse(Context context, MockRequest mockRequest, MockResponse resp) {
        return invoke(context, mockRequest, resp, false).getResponse();
    }

    /**
     * @param failoverMode true to apply Failover behaviour: shorter timeouts, and a failed call
     *                     reported through {@link LiveResult#isLiveAnswered()} with no fabricated
     *                     error response and no partial body
     */
    public LiveResult invoke(Context context,
            MockRequest mockRequest,
            MockResponse resp,
            boolean failoverMode) {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); // for partial data
        String endpoint = null;

        // Live Invocation populates the matched response object in place; there may not be one
        // on the TCP path, so give it a container to fill rather than dereferencing null.
        MockResponse target = resp != null
                ? resp
                : new MockResponse("live_response", 200, new byte[0], null);

        try {
            String host = context.getMockService().getConfig().getRouteEndpoint();

            String requestURI = mockRequest.getHttpRequest().getRequestURI();
            String queryString = mockRequest.getHttpRequest().getQueryString();

            endpoint = host + requestURI;
            if (queryString != null && !queryString.isEmpty()) {
                endpoint += "?" + queryString;
            }

            Logger.getInstance().info("Endpoint: " + endpoint);

            Duration connectTimeout = Duration.ofSeconds(failoverMode
                    ? seconds("executionmode.failover.connectTimeoutSeconds", DEFAULT_FAILOVER_CONNECT_SECONDS)
                    : seconds("executionmode.live.connectTimeoutSeconds", DEFAULT_LIVE_CONNECT_SECONDS));

            Duration requestTimeout = Duration.ofSeconds(failoverMode
                    ? seconds("executionmode.failover.requestTimeoutSeconds", DEFAULT_FAILOVER_REQUEST_SECONDS)
                    : seconds("executionmode.live.requestTimeoutSeconds", DEFAULT_LIVE_REQUEST_SECONDS));

            // HTTP CLIENT (force HTTP/1.1)
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(connectTimeout)
                    .build();

            // BUILD REQUEST
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout);

            String method = mockRequest.getMethod();
            String content = mockRequest.getRequestContent();

            if ("POST".equalsIgnoreCase(method) ||
                    "PUT".equalsIgnoreCase(method) ||
                    "PATCH".equalsIgnoreCase(method)) {

                requestBuilder.method(method,
                        HttpRequest.BodyPublishers.ofString(
                                content != null ? content : "",
                                StandardCharsets.UTF_8
                        ));
            } else {
                requestBuilder.GET();
            }

            //COPY SAFE HEADERS
            for (Map.Entry<String, List<String>> entry : mockRequest.getRequestHeaders().entrySet()) {
                String key = entry.getKey();

                if (key == null) continue;

                if (key.equalsIgnoreCase("host") ||
                        key.equalsIgnoreCase("content-length") ||
                        key.equalsIgnoreCase("connection") ||
                        key.equalsIgnoreCase("accept-encoding")) {
                    continue;
                }

                for (String value : entry.getValue()) {
                    requestBuilder.header(key, value);
                }
            }

            long start = System.currentTimeMillis();

            // STREAMING RESPONSE
            HttpResponse<InputStream> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            long end = System.currentTimeMillis();
            Logger.getInstance().info("Response Time: " + (end - start) + " ms");
            target.setStatusCode(response.statusCode());

            // READ RESPONSE IN CHUNKS
            InputStream is = response.body();
            byte[] chunk = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);

                // optional progress log
                if (buffer.size() % (50 * 1024) < 8192) {
                    Logger.getInstance().info("Downloaded bytes: " + buffer.size());
                }
            }

            target.setResponseBytes(buffer.toByteArray());

            // HEADERS
            Headers respHeaders = target.getHeaders();
            if (respHeaders == null) {
                respHeaders = new Headers();
                target.setHeaders(respHeaders);
            } else {
                respHeaders.clear();
            }

            for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
                respHeaders.put(entry.getKey(), entry.getValue());
            }

            // The body, status and headers now all belong to the live server, so relabel
            // the response object. The request logs key off these two fields:
            // MockResponse derives "source" from a name containing "live", and
            // getServiceLogs reports the name as the response that was served. Without
            // this the log would still show the mock response that was matched and then
            // discarded.
            target.setName(LIVE_RESPONSE_NAME);
            target.setSource(LIVE_SOURCE);

            // The server answered. Any status code is an answer, so Failover does not
            // second-guess a 500 that the destination genuinely returned.
            return LiveResult.answered(target);

        } catch (Exception e) {

            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());

            Logger.getInstance().error("Live call failed"
                    + (endpoint == null ? "" : " for " + endpoint), e);

            if (failoverMode) {
                // Nothing usable is handed back: a truncated body is worse than the mock
                // response the caller is about to fall back to.
                if (buffer.size() > 0) {
                    Logger.getInstance().info("Discarding " + buffer.size()
                            + " partial bytes; Failover will serve the mock response.");
                }
                return LiveResult.failed(null, reason);
            }

            // Live Invocation: unchanged behaviour - return whatever arrived, else a 500.
            if (buffer.size() > 0) {
                Logger.getInstance().info("Returning partial response. Bytes received: " + buffer.size());
                target.setResponseBytes(buffer.toByteArray());
                // Truncated, but still the live server's content - log it as live.
                target.setName(LIVE_RESPONSE_PARTIAL_NAME);
                target.setSource(LIVE_SOURCE);
                return LiveResult.failed(target, reason);
            }

            return LiveResult.failed(new MockResponse(
                    "live_error",
                    500,
                    ("Error invoking live service: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                    null
            ), reason);
        }
    }

    private static int seconds(String key, int fallback) {
        try {
            String raw = ConfigLoader.getProperty(key);
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return fallback;
    }
}
