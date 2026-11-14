package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.ReadDtos.DownloadUrlResponse;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.DownloadService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("!naive & !direct-finalize & !storage")
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService must not be null");
    }

    @GetMapping("/api/submissions/{id}/download")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        DownloadUrlResponse response = downloadService.url(id, authentication);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/assignments/{id}/download-all")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<StreamingResponseBody> downloadAll(
            @PathVariable("id") UUID id,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        downloadService.validateDownloadAll(id, teacherId);

        StreamingResponseBody responseBody = outputStream -> downloadService.downloadAll(id, teacherId, outputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"assignment-" + id + "-submissions.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(responseBody);
    }
}
