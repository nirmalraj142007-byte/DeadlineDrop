package com.deadlinedrop.storage;

import com.deadlinedrop.TestcontainersConfiguration;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.storage.StoragePort.ObjectInfo;
import com.deadlinedrop.storage.StoragePort.SignedUrl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StorageIT {

    private static final Path TEMP_ROOT;

    static {
        try {
            TEMP_ROOT = Files.createTempDirectory("dd-storage-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final StoragePort storagePort;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    public StorageIT(@Autowired StoragePort storagePort) {
        this.storagePort = storagePort;
    }

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("dd.storage.server.enabled", () -> "true");
        registry.add("dd.storage.root", () -> TEMP_ROOT.toAbsolutePath().toString());
        registry.add("dd.storage.hmac-secret", () -> "test-storage-hmac-secret-at-least-32-bytes");
    }

    private String rewriteUrl(String originalUrl) {
        URI uri = URI.create(originalUrl);
        try {
            return new URI("http", null, "localhost", port, uri.getPath(), uri.getQuery(), null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void signedPutWithHeadersReturns200AndStatMatchesAndOpenReturnsIdenticalBytes() throws Exception {
        byte[] payload = "Hello DeadlineDrop Storage Bytes".getBytes(StandardCharsets.UTF_8);
        String objectPath = "submissions/sub-1/report.pdf";

        SignedUrl signedUrl = storagePort.signPut(objectPath, "application/pdf", 1024, Duration.ofMinutes(5));
        String targetUrl = rewriteUrl(signedUrl.url());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        Optional<ObjectInfo> stat = storagePort.stat(objectPath);
        assertThat(stat).isPresent();
        assertThat(stat.get().size()).isEqualTo(payload.length);
        assertThat(stat.get().contentType()).isEqualTo("application/pdf");

        try (InputStream in = storagePort.open(objectPath)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void noSigReturns403() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/storage/submissions/nosig.pdf"))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofString("bytes"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains(ErrorCodes.SIGNATURE_INVALID);
    }

    @Test
    void ttlInThePastReturns403() throws Exception {
        SignedUrl expiredUrl = storagePort.signPut("submissions/expired.pdf", "application/pdf", 1024, Duration.ofSeconds(-10));
        String targetUrl = rewriteUrl(expiredUrl.url());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofString("late upload"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains(ErrorCodes.SIGNATURE_INVALID);
    }

    @Test
    void sameQueryOnAnotherPathReturns403() throws Exception {
        SignedUrl signedUrl = storagePort.signPut("submissions/pathA.pdf", "application/pdf", 1024, Duration.ofMinutes(5));
        String targetUrl = rewriteUrl(signedUrl.url());
        String tamperedUrl = targetUrl.replace("submissions/pathA.pdf", "submissions/pathB.pdf");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tamperedUrl))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofString("path hijack"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains(ErrorCodes.SIGNATURE_INVALID);
    }

    @Test
    void contentTypeTextPlainWhenApplicationPdfWasSignedReturns403() throws Exception {
        SignedUrl signedUrl = storagePort.signPut("submissions/type-test.pdf", "application/pdf", 1024, Duration.ofMinutes(5));
        String targetUrl = rewriteUrl(signedUrl.url());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("plain text upload"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains(ErrorCodes.SIGNATURE_INVALID);
    }

    @Test
    void bodyOfMaxPlusOneBytesReturns413() throws Exception {
        long maxBytes = 10;
        byte[] body = new byte[(int) maxBytes + 1];

        SignedUrl signedUrl = storagePort.signPut("submissions/too-large.pdf", "application/pdf", maxBytes, Duration.ofMinutes(5));
        String targetUrl = rewriteUrl(signedUrl.url());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains(ErrorCodes.FILE_TOO_LARGE);
    }

    @Test
    void signGetReturns200WithContentDisposition() throws Exception {
        byte[] payload = "Downloaded assignment text".getBytes(StandardCharsets.UTF_8);
        String objectPath = "submissions/download/assignment1.pdf";

        // Seed file via storagePort.put (arm A legacy upload path)
        storagePort.put(objectPath, "application/pdf", new ByteArrayInputStream(payload), payload.length);

        SignedUrl getUrl = storagePort.signGet(objectPath, "assignment-submission.pdf", Duration.ofMinutes(5));
        String targetUrl = rewriteUrl(getUrl.url());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .isPresent()
                .hasValueSatisfying(cd -> assertThat(cd).contains("attachment; filename=\"assignment-submission.pdf\""));
        assertThat(response.body()).isEqualTo(payload);
    }
}
