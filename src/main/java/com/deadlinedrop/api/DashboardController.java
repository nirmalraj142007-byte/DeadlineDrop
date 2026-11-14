package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.ReadDtos.DashboardDto;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.DashboardService;
import com.deadlinedrop.service.DashboardService.DashboardResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("!naive & !direct-finalize & !storage")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = Objects.requireNonNull(dashboardService, "dashboardService must not be null");
    }

    @GetMapping("/api/assignments/{id}/dashboard")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<DashboardDto> getDashboard(
            @PathVariable("id") UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        UUID teacherId = CurrentUser.id(authentication);
        DashboardResult result = dashboardService.getDashboard(id, teacherId);

        if (ifNoneMatch != null && matchesEtag(ifNoneMatch, result.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(result.etag())
                    .build();
        }

        return ResponseEntity.ok()
                .eTag(result.etag())
                .body(result.dto());
    }

    private static boolean matchesEtag(String ifNoneMatch, String etag) {
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }
        String unquotedEtag = etag.replace("\"", "");
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim().replace("\"", "");
            if (trimmed.startsWith("W/")) {
                trimmed = trimmed.substring(2).replace("\"", "");
            }
            if (trimmed.equals(unquotedEtag)) {
                return true;
            }
        }
        return false;
    }
}
