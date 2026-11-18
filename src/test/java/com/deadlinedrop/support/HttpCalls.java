package com.deadlinedrop.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Raw HTTP calls for the security tests. The JDK client, unlike a servlet mock, sends exactly the
 * bytes we give it: an {@code Origin} header, an unescaped path, a 5,000-character segment.
 */
public final class HttpCalls {

    /** What came back: status, body text and response headers (names lower-cased). */
    public record Reply(int status, String body, Map<String, List<String>> headers) {

        public Optional<String> header(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public HttpCalls(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Reply call(String method, String path, String bearer, Map<String, String> extraHeaders, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        if (jsonBody != null) {
            headers.putIfAbsent("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        request.method(method, jsonBody == null ? BodyPublishers.noBody() : BodyPublishers.ofString(jsonBody));
        try {
            HttpResponse<byte[]> response = client.send(request.build(), BodyHandlers.ofByteArray());
            Map<String, List<String>> lower = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
            return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), lower);
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + method + " " + path, e);
        }
    }

    public Reply call(String method, String path, String bearer, String jsonBody) {
        return call(method, path, bearer, Map.of(), jsonBody);
    }
}
