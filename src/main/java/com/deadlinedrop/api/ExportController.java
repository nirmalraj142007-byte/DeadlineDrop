package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.DomainDtos.ExportRow;
import com.deadlinedrop.security.CurrentUser;
import com.deadlinedrop.service.ExportService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/assignments/{id}/export")
@Profile("!storage")
public class ExportController {

    private static final String CSV_HEADER = "studentId,studentEmail,submissionId,stampedAt,uploadedAt,status,timing,stampSource\n";

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = Objects.requireNonNull(exportService, "exportService must not be null");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<?> export(
            @PathVariable("id") UUID id,
            @RequestParam(name = "format", defaultValue = "json") String format,
            Authentication authentication) {
        UUID callerId = CurrentUser.id(authentication);
        String role = CurrentUser.role(authentication);
        List<ExportRow> rows = exportService.getExportRows(id, callerId, role);

        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(rows);
        } else if ("csv".equalsIgnoreCase(format)) {
            String csv = formatCsv(rows);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(csv);
        } else {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Unsupported export format: " + format);
        }
    }

    private static String formatCsv(List<ExportRow> rows) {
        StringBuilder sb = new StringBuilder(CSV_HEADER);
        for (ExportRow row : rows) {
            sb.append(escapeCsv(row.studentId() != null ? row.studentId().toString() : "")).append(",");
            sb.append(escapeCsv(row.studentEmail() != null ? row.studentEmail() : "")).append(",");
            sb.append(escapeCsv(row.submissionId() != null ? row.submissionId().toString() : "")).append(",");
            sb.append(escapeCsv(row.stampedAt() != null ? row.stampedAt().toString() : "")).append(",");
            sb.append(escapeCsv(row.uploadedAt() != null ? row.uploadedAt().toString() : "")).append(",");
            sb.append(escapeCsv(row.status() != null ? row.status() : "")).append(",");
            sb.append(escapeCsv(row.timing() != null ? row.timing() : "")).append(",");
            sb.append(escapeCsv(row.stampSource() != null ? row.stampSource() : "")).append("\n");
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
