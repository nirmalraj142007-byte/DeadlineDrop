package com.deadlinedrop.storage;

import com.deadlinedrop.api.ApiException;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcsStorageAdapterTest {

    @Mock
    private Storage storage;

    private StorageProperties properties;
    private Clock clock;
    private GcsStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setBucket("test-bucket");
        clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        adapter = new GcsStorageAdapter(properties, clock, storage);
    }

    @Test
    @DisplayName("signPut generates V4 signed PUT URL with content-length and content-type headers")
    void signPutGeneratesSignedUrl() throws Exception {
        URL fakeUrl = URI.create("https://storage.googleapis.com/test-bucket/sub/file.pdf?sig=123").toURL();
        when(storage.signUrl(any(BlobInfo.class), eq(900000L), eq(TimeUnit.MILLISECONDS), any(SignUrlOption[].class)))
                .thenReturn(fakeUrl);

        StoragePort.SignedUrl signedUrl = adapter.signPut("sub/file.pdf", "application/pdf", 1048576L, Duration.ofMinutes(15));

        assertThat(signedUrl.url()).isEqualTo(fakeUrl.toString());
        assertThat(signedUrl.method()).isEqualTo("PUT");
        assertThat(signedUrl.headers()).containsEntry("Content-Type", "application/pdf");
        assertThat(signedUrl.headers()).containsEntry("x-goog-content-length-range", "0,1048576");
        assertThat(signedUrl.expiresAt()).isEqualTo(Instant.parse("2026-11-11T12:15:00Z"));
    }

    @Test
    @DisplayName("signGet generates signed GET URL with response-content-disposition query parameter")
    void signGetGeneratesSignedUrl() throws Exception {
        URL fakeUrl = URI.create("https://storage.googleapis.com/test-bucket/sub/file.pdf?sig=456").toURL();
        when(storage.signUrl(any(BlobInfo.class), eq(300000L), eq(TimeUnit.MILLISECONDS), any(SignUrlOption[].class)))
                .thenReturn(fakeUrl);

        StoragePort.SignedUrl signedUrl = adapter.signGet("sub/file.pdf", "homework.pdf", Duration.ofMinutes(5));

        assertThat(signedUrl.url()).isEqualTo(fakeUrl.toString());
        assertThat(signedUrl.method()).isEqualTo("GET");
        assertThat(signedUrl.headers()).isEmpty();
        assertThat(signedUrl.expiresAt()).isEqualTo(Instant.parse("2026-11-11T12:05:00Z"));
    }

    @Test
    @DisplayName("stat returns size and contentType when object exists")
    void statReturnsObjectInfoWhenPresent() {
        Blob blob = mock(Blob.class);
        when(blob.exists()).thenReturn(true);
        when(blob.getSize()).thenReturn(2048L);
        when(blob.getContentType()).thenReturn("application/pdf");
        when(storage.get(BlobId.of("test-bucket", "a/assign1/sub1.pdf"))).thenReturn(blob);

        Optional<StoragePort.ObjectInfo> info = adapter.stat("a/assign1/sub1.pdf");

        assertThat(info).isPresent();
        assertThat(info.get().size()).isEqualTo(2048L);
        assertThat(info.get().contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("stat returns empty when object does not exist")
    void statReturnsEmptyWhenNotPresent() {
        when(storage.get(BlobId.of("test-bucket", "a/assign1/missing.pdf"))).thenReturn(null);

        Optional<StoragePort.ObjectInfo> info = adapter.stat("a/assign1/missing.pdf");

        assertThat(info).isEmpty();
    }

    @Test
    @DisplayName("open returns stream when blob exists")
    void openReturnsStreamWhenPresent() throws Exception {
        Blob blob = mock(Blob.class);
        when(blob.exists()).thenReturn(true);
        when(storage.get(BlobId.of("test-bucket", "file.txt"))).thenReturn(blob);

        ReadChannel readChannel = mock(ReadChannel.class);
        when(storage.reader(BlobId.of("test-bucket", "file.txt"))).thenReturn(readChannel);
        when(readChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        try (InputStream in = adapter.open("file.txt")) {
            assertThat(in).isNotNull();
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("open throws 404 when blob does not exist")
    void openThrowsNotFoundWhenAbsent() {
        when(storage.get(BlobId.of("test-bucket", "missing.txt"))).thenReturn(null);

        assertThatThrownBy(() -> adapter.open("missing.txt"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Object not found");
    }

    @Test
    @DisplayName("put streams into storage via createFrom")
    void putUploadsViaCreateFrom() throws Exception {
        byte[] content = "test file content".getBytes();
        ByteArrayInputStream in = new ByteArrayInputStream(content);

        adapter.put("folder/upload.bin", "application/octet-stream", in, content.length);

        ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).createFrom(captor.capture(), eq(in));
        assertThat(captor.getValue().getBucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().getName()).isEqualTo("folder/upload.bin");
        assertThat(captor.getValue().getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("delete invokes storage.delete")
    void deleteCallsStorage() {
        adapter.delete("to-delete.pdf");

        verify(storage).delete(BlobId.of("test-bucket", "to-delete.pdf"));
    }

    @Test
    @DisplayName("Path traversal or blank paths are rejected")
    void rejectsInvalidPaths() {
        assertThatThrownBy(() -> adapter.stat("../secret.txt"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> adapter.stat(""))
                .isInstanceOf(ApiException.class);
    }
}
